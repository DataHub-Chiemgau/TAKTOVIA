package de.taktovia.app.security

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lokaler JWT-Test-Issuer mit JWKS-Endpunkt (Ersatz für Keycloak, der in dieser Umgebung nicht läuft; kein Docker).
 * Er signiert echte JWTs mit echten Schlüsseln; die Anwendung holt das JWKS per HTTP wie vom echten IdP. Die Schlüsselmenge ist zur Laufzeit
 * austauschbar ([publish]) und erlaubt so Rotationstests. Nur für Tests.
 */
class TestIdp(val realm: String = "taktovia-test") {
    private val json = tools.jackson.databind.json.JsonMapper()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port get() = server.address.port
    val issuer get() = "http://127.0.0.1:$port/realms/$realm"
    val jwksUri get() = "$issuer/protocol/openid-connect/certs"
    val audience = "taktovia-api"

    @Volatile private var published: List<JWK> = emptyList()
    val jwksFetches = AtomicInteger()
    val down = AtomicBoolean(false)

    fun newRsaKey(kid: String): RSAKey = RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.SIGNATURE).generate()
    fun newEcKey(kid: String): ECKey = ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_256).keyID(kid).keyUse(KeyUse.SIGNATURE).generate()

    val key1: RSAKey = newRsaKey("key-1")
    val key2: RSAKey = newRsaKey("key-2")
    val ecKey: ECKey = newEcKey("key-ec")

    private val codes = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Map<String, Any?>>>()

    /**
     * Stand-in für den Autorisierungsendpunkt (Authorization Code + PKCE): merkt sich die `code_challenge` (nur S256) und die Claims,
     * die das Token tragen soll. Liefert den Code, den ein Browser nach der Anmeldung erhielte. Echter Keycloak-Login: nicht ausgeführt.
     */
    fun authorize(codeChallenge: String, claims: Map<String, Any?>): String =
        UUID.randomUUID().toString().also { codes[it] = codeChallenge to claims }

    private fun form(body: String) = body.split('&').filter { it.isNotEmpty() }.associate {
        java.net.URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to java.net.URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
    }

    init {
        server.createContext("/realms/$realm/protocol/openid-connect/token") { ex ->
            val f = form(String(ex.requestBody.readAllBytes()))
            val entry = if (f["grant_type"] == "authorization_code") codes.remove(f["code"]) else null  // Code nur einmal einlösbar
            val verifier = f["code_verifier"]
            val ok = entry != null && verifier != null &&
                Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())) == entry.first
            val body = (if (ok) """{"token_type":"Bearer","access_token":"${sign(entry!!.second)}"}""" else """{"error":"invalid_grant"}""").toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(if (ok) 200 else 400, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        published = listOf(key1)
        server.createContext("/realms/$realm/protocol/openid-connect/certs") { ex ->
            jwksFetches.incrementAndGet()
            if (down.get()) { ex.sendResponseHeaders(503, -1); ex.close(); return@createContext }
            val body = JWKSet(published.map { it.toPublicJWK() }).toString().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    fun publish(vararg keys: JWK) { published = keys.toList() }
    fun close() = server.stop(0)

    /** Claims eines gültigen Menschen-Tokens; einzelne Felder lassen sich überschreiben oder mit `null` entfernen. */
    fun humanClaims(
        sub: UUID = UUID.randomUUID(), roles: List<String> = listOf("passenger"), acr: String = "loa1", amr: List<String>? = null,
        tenants: List<UUID> = emptyList(), azp: String = "operator-web", lifetime: Long = 300,
    ): MutableMap<String, Any?> {
        val now = System.currentTimeMillis() / 1000
        return mutableMapOf<String, Any?>(
            "iss" to issuer, "sub" to sub.toString(), "aud" to listOf(audience), "exp" to now + lifetime, "iat" to now, "azp" to azp,
            "principal_type" to "human", "acr" to acr, "sid" to "sid-" + UUID.randomUUID(), "scope" to "openid profile",
            "roles" to roles, "tenant_ids" to tenants.map { it.toString() },
        ).also { if (amr != null) it["amr"] = amr }
    }

    fun serviceClaims(client: String = "adapter-psp", scope: String = "payments:webhook", tenants: List<UUID> = emptyList()): MutableMap<String, Any?> {
        val now = System.currentTimeMillis() / 1000
        return mutableMapOf<String, Any?>(
            "iss" to issuer, "sub" to UUID.randomUUID().toString(), "aud" to listOf(audience), "exp" to now + 300, "iat" to now, "azp" to client,
            "principal_type" to "service", "acr" to "loa1", "scope" to scope, "tenant_ids" to tenants.map { it.toString() },
        )
    }

    fun sign(claims: Map<String, Any?>, key: JWK = key1, alg: JWSAlgorithm = if (key is ECKey) JWSAlgorithm.ES256 else JWSAlgorithm.RS256): String {
        val b = JWTClaimsSet.Builder()
        claims.forEach { (k, v) ->
            when {
                v == null -> {}
                (k == "exp" || k == "iat" || k == "nbf") && v is Number -> b.claim(k, Date(v.toLong() * 1000))
                else -> b.claim(k, v)
            }
        }
        val jwt = SignedJWT(JWSHeader.Builder(alg).keyID(key.keyID).type(JOSEObjectType.JWT).build(), b.build())
        jwt.sign(if (key is ECKey) ECDSASigner(key) else RSASSASigner(key as RSAKey))
        return jwt.serialize()
    }

    /** Token ohne Signatur (`alg=none`). */
    fun unsigned(claims: Map<String, Any?>): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
        val payload = enc.encodeToString(json.writeValueAsString(claims).toByteArray())
        return "$header.$payload."
    }

    /** Nutzlast eines signierten Tokens austauschen, Signatur unverändert lassen (Manipulation). */
    fun tamper(token: String, mutate: (MutableMap<String, Any?>) -> Unit): String {
        val (h, p, s) = token.split('.')
        val dec = Base64.getUrlDecoder()
        @Suppress("UNCHECKED_CAST")
        val claims = json.readValue(String(dec.decode(p)), MutableMap::class.java) as MutableMap<String, Any?>
        mutate(claims)
        val np = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsString(claims).toByteArray())
        return "$h.$np.$s"
    }

    fun bearer(token: String) = mapOf("Authorization" to "Bearer $token")
}
