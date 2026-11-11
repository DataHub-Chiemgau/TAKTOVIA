package de.taktovia.app.security

import de.taktovia.app.probe.Resp
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * LH-6.2-IAM-010 / WP-M1-04 AK 2: Ein Administrator mit gültigem Token ohne zweiten Faktor wird an jeder geschützten API mit 403 abgewiesen
 * und als Sicherheitsereignis protokolliert; mit zweitem Faktor gelingt der Zugriff. Authentisierungsstärke kommt ausschließlich aus dem
 * signierten Token (`acr`), nie aus Anfrage-Headern oder dem Hinweis-Claim `mfa_required`.
 */
class MfaEnforcementTest {
    private val env get() = SecurityEnv.shared
    private val idp get() = env.idp
    private fun admin(acr: String = "loa1", amr: List<String>? = null, sub: UUID = UUID.randomUUID(), role: String = "tenant_admin") =
        idp.humanClaims(sub = sub, roles = listOf(role), acr = acr, amr = amr, tenants = listOf(env.tenantA))

    private fun assertMfaDenied(r: Resp, what: String) {
        assertEquals(403, r.status, "$what: ${r.text}")
        assertEquals("FORBIDDEN", r.json["code"].asString(), what)
        assertTrue(r.header("WWW-Authenticate")!!.contains("insufficient_user_authentication"), "$what: Step-up-Hinweis (RFC 9470)")
        assertTrue(r.header("WWW-Authenticate")!!.contains("loa2"), what)
    }

    @Test
    fun admin_ohne_zweiten_faktor_403() {
        assertMfaDenied(env.call(idp.sign(admin())), "tenant_admin loa1")
        assertMfaDenied(env.call(idp.sign(admin(acr = "loa1", amr = listOf("pwd")))), "mit amr=pwd")
        assertMfaDenied(env.call(idp.sign(admin(role = "dispatcher"))), "dispatcher")
        assertMfaDenied(env.call(idp.sign(admin(role = "finance"))), "finance")
        // Der Hinweis-Claim und Header ändern nichts: die Entscheidung folgt dem signierten acr.
        assertMfaDenied(env.call(idp.sign(admin().also { it["mfa_required"] = "false" })), "mfa_required=false im Token")
        assertMfaDenied(env.server.get("/v1/probe/leaky", idp.bearer(idp.sign(admin())) + mapOf("X-Test-Mfa" to "true", "X-Authentication-Strength" to "loa2")), "MFA-Header")
    }

    @Test
    fun admin_mit_zweitem_faktor_erfolgreich() {
        assertEquals(200, env.call(idp.sign(admin(acr = "loa2", amr = listOf("pwd", "otp")))).status)
        assertEquals(200, env.call(idp.sign(admin(acr = "loa2"))).status, "acr genügt, amr ist optional")
        assertEquals(200, env.call(idp.sign(admin(acr = "loa2", amr = listOf("hwk", "user")))).status, "Hardwareschlüssel als zweiter Faktor")
    }

    @Test
    fun jeder_geschuetzte_endpunkt_verlangt_mfa_fuer_admin() {
        val ops = env.server.registry.operations
        assertTrue(ops.size > 50, "alle dokumentierten Operationen (api/*.yaml und Prüfvertrag): ${ops.size}")
        val noMfa = idp.sign(admin())
        val withMfa = idp.sign(admin(acr = "loa2"))
        val uuid = "6f0c1d2e-3a4b-4c5d-8e9f-0a1b2c3d4e5f"
        val failures = mutableListOf<String>()
        ops.filter { !it.signatureAuthenticated }.forEach { op ->   // Operationen mit PSP-Signatur kennen keinen Menschen und keine MFA (WP-M2-29, ProviderEventApiTest)
            val path = op.path.replace(Regex("\\{[^}]+}"), uuid)
            val body = if (op.method in setOf("POST", "PUT", "PATCH")) "{}" else null
            val denied = env.server.send(op.method, path, body, idp.bearer(noMfa))
            if (denied.status != 403) failures += "${op.method} ${op.path} ohne MFA: ${denied.status}"
            // mit zweitem Faktor wird die Anfrage nicht wegen MFA abgewiesen (404/422/… sind erlaubt, 403 nur mit anderem Grund unwahrscheinlich)
            val allowed = env.server.send(op.method, path, body, idp.bearer(withMfa))
            if (allowed.status == 403 && allowed.header("WWW-Authenticate")?.contains("insufficient_user_authentication") == true) failures += "${op.method} ${op.path} mit MFA wegen MFA abgewiesen"
            if (allowed.status == 401) failures += "${op.method} ${op.path} mit MFA: 401"
        }
        assertEquals(emptyList<String>(), failures)
        // auch Pfade ohne Vertrag und ohne Route sind für den Administrator ohne zweiten Faktor nicht anders erreichbar
        assertMfaDenied(env.call(noMfa, "/v1/irgendetwas/unbekannt"), "Pfad ohne Vertrag")
    }

    @Test
    fun abweisung_erzeugt_sicherheitsereignis() {
        val sub = UUID.randomUUID()
        val r = env.call(idp.sign(admin(sub = sub)))
        assertMfaDenied(r, "Admin ohne MFA")
        val correlation = UUID.fromString(r.header("X-Correlation-Id"))
        val rows = env.tenantTx().run(TenantId(env.tenantA), readOnly = true) { j ->
            j.queryForList("SELECT actor_ref, action, object_ref, correlation_id FROM taktovia.audit_event WHERE correlation_id = ?", correlation)
        }
        assertEquals(1, rows.size, rows.toString())
        assertEquals("user:$sub", rows[0]["actor_ref"])
        assertEquals("security.auth.mfa_missing", rows[0]["action"])
        assertEquals("user:$sub", rows[0]["object_ref"])
        // Erfolgreiche Anfragen und Nicht-Privilegierte erzeugen kein solches Ereignis
        val ok = env.call(idp.sign(admin(acr = "loa2", sub = sub)))
        assertEquals(200, ok.status)
        val n = env.tenantTx().run(TenantId(env.tenantA), readOnly = true) { j ->
            j.queryForObject("SELECT count(*) FROM taktovia.audit_event WHERE correlation_id = ?", Int::class.java, UUID.fromString(ok.header("X-Correlation-Id")))
        }
        assertEquals(0, n)
        // Token ohne Mandantenhinweis: kein Absturz, Abweisung bleibt 403
        assertMfaDenied(env.call(idp.sign(idp.humanClaims(roles = listOf("tenant_admin")))), "ohne tenant_ids")
    }

    @Test
    fun manipulierte_authentisierungsclaims_abgewiesen() {
        val loa1 = idp.sign(admin())
        assertEquals(401, env.call(idp.tamper(loa1) { it["acr"] = "loa2" }).status, "acr nachträglich auf loa2 gesetzt, Signatur unverändert")
        assertEquals(401, env.call(idp.tamper(loa1) { it["acr"] = "loa2"; it["amr"] = listOf("pwd", "otp") }).status, "acr und amr verändert")
        assertEquals(401, env.call(idp.tamper(loa1) { it["roles"] = listOf("passenger") }).status, "Rolle entfernt, Signatur unverändert")
        // Widersprüchliche oder fehlerhafte Claims, korrekt signiert
        assertEquals(401, env.call(idp.sign(admin(acr = "loa2", amr = listOf("pwd")))).status, "loa2 ohne Mehrfaktor-Methode in amr")
        assertEquals(401, env.call(idp.sign(admin().also { it["acr"] = 2 })).status, "acr als Zahl")
        assertEquals(401, env.call(idp.sign(admin().also { it["acr"] = "loa3" })).status, "unbekannte Stufe")
        assertEquals(401, env.call(idp.sign(admin().also { it["acr"] = null })).status, "acr fehlt")
        assertEquals(401, env.call(idp.sign(admin(acr = "loa2").also { it["amr"] = "otp" })).status, "amr als Text")
        assertEquals(401, env.call(idp.sign(admin(acr = "loa2").also { it["roles"] = "tenant_admin" })).status, "roles als Text")
        assertEquals(401, env.call(idp.sign(admin(acr = "loa2").also { it["tenant_ids"] = listOf("kein-uuid") })).status, "tenant_ids ungültig")
    }

    @Test
    fun nicht_privilegierte_rolle_nicht_geblockt() {
        listOf("passenger", "driver", "unbekannte_rolle").forEach { role ->
            assertEquals(200, env.call(idp.sign(admin(role = role))).status, role)
        }
        assertEquals(200, env.call(idp.sign(idp.humanClaims(roles = emptyList()))).status, "ohne Rollen")
        assertEquals(200, env.call(idp.sign(idp.humanClaims(roles = listOf("passenger", "driver"), acr = "loa1"))).status)
    }

    @Test
    fun rollenzuweisung_waehrend_sitzung_ohne_mfa_abgewiesen() {
        // Sitzung eines Fahrgasts ohne zweiten Faktor; während sie läuft, wird dem Konto die Administratorrolle zugewiesen
        // (Quelle: Mitgliedschaft, WP-M1-07; hier das veränderliche Verzeichnis). Dasselbe Token wird nun abgewiesen.
        val sub = UUID.randomUUID()
        val token = idp.sign(idp.humanClaims(sub = sub, roles = listOf("passenger"), tenants = listOf(env.tenantA)))
        assertEquals(200, env.call(token).status)
        env.roles.assign(sub, "tenant_admin")
        try {
            assertMfaDenied(env.call(token), "Folgeanfrage nach Rollenzuweisung")
            val mfa = idp.sign(idp.humanClaims(sub = sub, roles = listOf("passenger"), acr = "loa2", amr = listOf("pwd", "otp"), tenants = listOf(env.tenantA)))
            assertEquals(200, env.call(mfa).status, "mit zweitem Faktor weiterhin zugelassen")
        } finally { env.roles.revoke(sub) }
        assertEquals(200, env.call(token).status, "nach Entzug der Rolle wieder zugelassen")
    }

    @Test
    fun parallele_anfragen_mit_und_ohne_faktor() {
        val sub = UUID.randomUUID()
        val without = idp.sign(admin(sub = sub))
        val with = idp.sign(admin(acr = "loa2", amr = listOf("pwd", "otp"), sub = sub))
        val pool = Executors.newFixedThreadPool(16)
        try {
            val results = (0 until 400).map { i -> pool.submit(Callable { Pair(i % 2 == 0, env.call(if (i % 2 == 0) with else without).status) }) }
                .map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(setOf(200), results.filter { it.first }.map { it.second }.toSet(), "mit zweitem Faktor immer 200")
            assertEquals(setOf(403), results.filter { !it.first }.map { it.second }.toSet(), "ohne zweiten Faktor immer 403")
            assertEquals(400, results.size)
        } finally { pool.shutdown() }
    }
}
