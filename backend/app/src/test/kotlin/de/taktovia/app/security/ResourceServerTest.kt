package de.taktovia.app.security

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * LH-6.2-IAM-002 / WP-M1-04: Der Ressourcenserver prüft Signatur, `iss`, `aud`, `exp` gegen das JWKS des Issuers; jeder Fehler ergibt 401.
 * Läuft gegen die echte Anwendung und die echte Datenbank. Der IdP ist ein lokaler Test-Issuer mit echten Schlüsseln und JWKS-Endpunkt
 * ([TestIdp]); Keycloak selbst wurde hier nicht gestartet (kein Docker).
 */
class ResourceServerTest {
    private val env get() = SecurityEnv.shared
    private val idp get() = env.idp

    private fun human(vararg tweak: Pair<String, Any?>): MutableMap<String, Any?> =
        idp.humanClaims().also { c -> tweak.forEach { (k, v) -> if (v == null) c.remove(k) else c[k] = v } }

    private fun assert401(r: de.taktovia.app.probe.Resp, what: String) {
        assertEquals(401, r.status, "$what: ${r.text}")
        assertEquals("UNAUTHENTICATED", r.json["code"].asString(), what)
        assertTrue(r.header("WWW-Authenticate")!!.startsWith("Bearer"), what)
        assertNotNull(r.header("X-Correlation-Id"))
        // keine Hinweise auf den genauen Grund (Signatur, Issuer, Schlüssel) in der Antwort
        assertFalse(Regex("(?i)signature|signatur|issuer|audience|kid|key-1|jwks|nimbus|exception").containsMatchIn(r.text), "$what leakt Details: ${r.text}")
    }

    @Test
    fun pkce_login_und_gueltiges_token_erfolgreich() {
        // Stand-in für den Browser-Login: Authorization Code + PKCE (S256) am Test-Issuer, danach Aufruf der API mit dem bezogenen Token.
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val http = HttpClient.newHttpClient()
        fun exchange(code: String, v: String) = http.send(HttpRequest.newBuilder(URI("${idp.issuer}/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=authorization_code&client_id=passenger-web&code=$code&code_verifier=$v")).build(),
            HttpResponse.BodyHandlers.ofString())

        val code = idp.authorize(challenge, idp.humanClaims(azp = "passenger-web"))
        assertEquals(400, exchange(code, "falscher-verifier-falscher-verifier-falscher").statusCode(), "falscher code_verifier wird vom IdP abgewiesen")
        val code2 = idp.authorize(challenge, idp.humanClaims(azp = "passenger-web"))
        val resp = exchange(code2, verifier)
        assertEquals(200, resp.statusCode())
        assertEquals(400, exchange(code2, verifier).statusCode(), "Code ist nur einmal einlösbar")
        val token = tools.jackson.databind.json.JsonMapper().readTree(resp.body())["access_token"].asString()

        val ok = env.call(token)
        assertEquals(200, ok.status, ok.text)
        assertEquals(401, env.call(null).status, "ohne Token")
    }

    @Test
    fun gueltige_tokens_rs256_und_es256_sowie_aud_als_text() {
        idp.publish(idp.key1, idp.ecKey)
        try {
            assertEquals(200, env.call(idp.sign(human())).status)
            assertEquals(200, env.call(idp.sign(human(), idp.ecKey)).status, "ES256")
            assertEquals(200, env.call(idp.sign(human("aud" to idp.audience))).status, "aud als einzelner Text")
            assertEquals(200, env.call(idp.sign(human("aud" to listOf("anderer-dienst", idp.audience)))).status, "mehrere Audiences")
            val clockSkewOk = human("exp" to System.currentTimeMillis() / 1000 - 10)
            assertEquals(200, env.call(idp.sign(clockSkewOk)).status, "Ablauf innerhalb der Toleranz (30 s)")
        } finally { idp.publish(idp.key1) }
    }

    @Test
    fun falscher_issuer_audience_und_abgelaufen_ergeben_401() {
        val now = System.currentTimeMillis() / 1000
        assert401(env.call(idp.sign(human("iss" to "http://127.0.0.1:1/realms/fremd"))), "falscher Issuer")
        assert401(env.call(idp.sign(human("iss" to idp.issuer + "/"))), "Issuer mit abweichendem Schrägstrich")
        assert401(env.call(idp.sign(human("aud" to listOf("anderer-dienst")))), "falsche Audience")
        assert401(env.call(idp.sign(human("aud" to null))), "Audience fehlt")
        assert401(env.call(idp.sign(human("exp" to now - 3600, "iat" to now - 4000))), "abgelaufen")
        assert401(env.call(idp.sign(human("exp" to now - 60))), "abgelaufen außerhalb der Toleranz")
        assert401(env.call(idp.sign(human("exp" to null))), "exp fehlt")
        assert401(env.call(idp.sign(human("nbf" to now + 3600))), "noch nicht gültig")
        assert401(env.call(idp.sign(human("exp" to now + 86_400, "iat" to now))), "Laufzeit über dem Höchstwert")
        assert401(env.call(idp.sign(human("sub" to "kein-uuid"))), "sub keine UUID")
        assert401(env.call(idp.sign(human("sid" to null))), "Menschen-Token ohne Session-ID")
        assert401(env.call(idp.sign(human("principal_type" to null))), "principal_type fehlt")
        assert401(env.call(idp.sign(human("azp" to "unbekannter-client"))), "unbekannter Client")
        assert401(env.server.get("/v1/probe/leaky", mapOf("Authorization" to "Basic dGVzdDp0ZXN0")), "Basic statt Bearer")
        assert401(env.server.get("/v1/probe/leaky", mapOf("Authorization" to "Bearer ")), "leeres Token")
        assert401(env.server.get("/v1/probe/leaky", mapOf("Authorization" to "Bearer kein.jwt.token")), "kein JWT")
        // übergroßes Token: schon der Container (Headergrenze 8 KiB) oder der Prüfer weist es ab; nie 200
        assertTrue(env.server.get("/v1/probe/leaky", mapOf("Authorization" to "Bearer " + "a".repeat(20_000))).status in setOf(400, 401, 431), "übergroßes Token")
        assert401(env.server.get("/v1/probe/leaky", mapOf("Authorization" to "Bearer " + "a".repeat(4_000))), "großes Token im Headerlimit")
        // ohne Token ist auch ein nicht vorhandener Pfad nicht erkennbar (401 statt 404)
        assert401(env.call(null, "/v1/gibt-es-nicht"), "unbekannter Pfad ohne Token")
        assertEquals(200, env.server.get("/actuator/health").status, "Health bleibt ohne Token erreichbar")
    }

    @Test
    fun staging_token_gegen_produktion_abgewiesen() {
        // Die Anwendung vertraut genau einem Issuer (hier: Identitätsdomäne "Produktion"). Ein Token der Staging-Domäne scheitert
        // gleich dreifach: fremder Issuer-Claim, fremder Schlüssel, und selbst mit gefälschtem Issuer-Claim passt die Signatur nicht.
        val staging = TestIdp("taktovia-staging")
        try {
            assert401(env.call(staging.sign(staging.humanClaims())), "echtes Staging-Token")
            assert401(env.call(staging.sign(staging.humanClaims().also { it["iss"] = idp.issuer })), "Staging-Schlüssel mit gefälschtem Issuer")
            val sameKid = staging.sign(staging.humanClaims().also { it["iss"] = idp.issuer }, staging.newRsaKey("key-1"))
            assert401(env.call(sameKid), "gleiche kid, fremder Schlüssel")
            assertEquals(200, env.call(idp.sign(human())).status, "das eigene Token funktioniert weiterhin")
        } finally { staging.close() }
    }

    @Test
    fun manipulierte_signatur_und_alg_none_ergeben_401() {
        val good = idp.sign(human())
        assertEquals(200, env.call(good).status)
        assert401(env.call(idp.tamper(good) { it["roles"] = listOf("tenant_admin") }), "veränderte Nutzlast bei alter Signatur")
        val parts = good.split('.')
        val flipped = parts[2].let { (if (it[0] == 'A') "B" else "A") + it.substring(1) }
        assert401(env.call("${parts[0]}.${parts[1]}.$flipped"), "veränderte Signatur")
        assert401(env.call("${parts[0]}.${parts[1]}."), "leere Signatur")
        assert401(env.call("${parts[0]}.${parts[1]}"), "fehlende Signatur")
        assert401(env.call(idp.unsigned(human())), "alg=none")
        assert401(env.call(idp.unsigned(human()).removeSuffix(".") + "." + parts[2]), "alg=none mit fremder Signatur")

        // Algorithmenverwechslung: HS256 mit dem öffentlichen Schlüssel als MAC-Geheimnis
        val hmac = SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).keyID("key-1").build(), JWTClaimsSet.parse(
            tools.jackson.databind.json.JsonMapper().writeValueAsString(human().mapValues { (k, v) -> if (k == "exp" || k == "iat") (v as Long) else v })))
        hmac.sign(MACSigner(ByteArray(64) { 7 }))
        assert401(env.call(hmac.serialize()), "HS256")

        // unbekannte kid, fremder Schlüssel unter bekannter kid, nicht veröffentlichter Schlüssel, Header mit jku
        assert401(env.call(idp.sign(human(), idp.newRsaKey("key-unbekannt"))), "unbekannte kid")
        assert401(env.call(idp.sign(human(), idp.newRsaKey("key-1"))), "gleiche kid, anderer Schlüssel")
        assert401(env.call(idp.sign(human(), idp.ecKey)), "gültig signiert, aber Schlüssel nicht im JWKS")
        val jku = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("key-1").jwkURL(URI("http://127.0.0.1:1/evil.json")).build(),
            JWTClaimsSet.Builder().issuer(idp.issuer).build()).also { it.sign(RSASSASigner(idp.key1)) }
        assert401(env.call(jku.serialize()), "Header jku")
        // Ablauf als Text statt Zahl
        assert401(env.call(good.let { idp.tamper(it) { c -> c["exp"] = "9999999999" } }), "veränderte exp")
    }

    @Test
    fun jwks_rotation_waehrend_anfragen() {
        // Eigene Instanz mit kurzem JWKS-Cache (1 s): Rotation von key-1 auf key-2 mitten in laufenden Anfragen.
        val e = SecurityEnv.create("taktovia.idp.jwks-cache-seconds=1", "taktovia.idp.max-stale-seconds=60")
        val idp = e.idp
        fun t(key: com.nimbusds.jose.jwk.JWK) = idp.sign(idp.humanClaims(), key)
        assertEquals(200, e.call(t(idp.key1)).status)
        assertEquals(401, e.call(t(idp.key2)).status, "key-2 ist vor der Rotation unbekannt")

        class Obs(val key: String, val start: Long, val end: Long, val status: Int)
        val observations = CopyOnWriteArrayList<Obs>()
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(10)
        val started = CountDownLatch(10)
        val workers = (0 until 10).map { i ->
            pool.submit(Callable {
                started.countDown()
                var n = 0
                while (!stop.get()) {
                    val k = if ((i + n++) % 2 == 0) "key-1" else "key-2"
                    val token = t(if (k == "key-1") idp.key1 else idp.key2)
                    val s = System.nanoTime(); val r = e.call(token); observations += Obs(k, s, System.nanoTime(), r.status)
                }
            })
        }
        started.await()
        Thread.sleep(300)
        val rotatedAt = System.nanoTime()
        idp.publish(idp.key2)                       // key-1 wird zurückgezogen
        val deadline = System.currentTimeMillis() + 10_000
        // Warten, bis key-2 akzeptiert und key-1 abgewiesen wird; danach noch eine Weile unter Last beobachten.
        while (System.currentTimeMillis() < deadline && observations.none { it.key == "key-2" && it.status == 200 && it.start > rotatedAt }) Thread.sleep(20)
        val firstNewOk = observations.filter { it.key == "key-2" && it.status == 200 }.minOfOrNull { it.end } ?: error("key-2 wurde nie akzeptiert")
        Thread.sleep(2500)
        stop.set(true); workers.forEach { it.get(30, TimeUnit.SECONDS) }; pool.shutdown()

        // Invariante 1: key-2 ist ab der Rotation nie abgewiesen worden, sobald es einmal galt.
        assertEquals(emptyList<Int>(), observations.filter { it.key == "key-2" && it.start > firstNewOk && it.status != 200 }.map { it.status }, "key-2 nach Akzeptanz")
        // Invariante 2: nach höchstens Cache-Laufzeit + Toleranz nimmt kein Anfrage mehr key-1 an; sie wird nie wieder akzeptiert.
        val firstOldReject = observations.filter { it.key == "key-1" && it.status == 401 }.minOfOrNull { it.end } ?: error("key-1 wurde nie abgewiesen")
        assertTrue((firstOldReject - rotatedAt) / 1_000_000 < 4_000, "key-1 noch ${(firstOldReject - rotatedAt) / 1_000_000} ms nach der Rotation akzeptiert")
        assertEquals(emptyList<Int>(), observations.filter { it.key == "key-1" && it.start > firstOldReject && it.status != 401 }.map { it.status }, "key-1 nach Rückzug akzeptiert")
        // Vor der Rotation galten beide Fälle wie erwartet (key-1 gültig), und es gab keine anderen Statuswerte als 200/401.
        assertEquals(setOf(200, 401), observations.map { it.status }.toSet())
        assertTrue(observations.filter { it.key == "key-1" && it.end < rotatedAt }.all { it.status == 200 || it.status == 401 })
        assertTrue(observations.filter { it.key == "key-1" && it.end < rotatedAt && it.start > 0 }.count { it.status == 200 } > 0)
        assertTrue(observations.size > 100, "genügend Last: ${observations.size}")
    }

    @Test
    fun jwks_ausfall_fail_closed_nach_max_stale() {
        // Ohne erreichbares JWKS gelten die zuletzt geladenen Schlüssel höchstens max-stale-seconds weiter; danach 503 statt Annahme.
        val e = SecurityEnv.create("taktovia.idp.jwks-cache-seconds=1", "taktovia.idp.max-stale-seconds=3")
        val token = { e.idp.sign(e.idp.humanClaims()) }
        assertEquals(200, e.call(token()).status)
        e.idp.down.set(true)
        Thread.sleep(1_300)
        assertEquals(200, e.call(token()).status, "Cache abgelaufen, IdP gestört, aber innerhalb max-stale")
        Thread.sleep(2_500)
        val r = e.call(token())
        assertEquals(503, r.status, r.text)
        assertEquals("SERVICE_UNAVAILABLE", r.json["code"].asString())
        e.idp.down.set(false)
        assertEquals(200, e.call(token()).status, "nach Wiederkehr des IdP wieder erreichbar")
    }
}
