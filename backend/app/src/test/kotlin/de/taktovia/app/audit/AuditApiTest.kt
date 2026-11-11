package de.taktovia.app.audit

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP-M1-12 (LH-6.18-SEC-006, AT-49/E2E-091 am Backend): Auditabfrage über die echte Anwendung mit Test-Issuer statt Keycloak. */
class AuditApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    @Test
    fun rollenaenderung_ist_mit_akteur_zeitpunkt_und_aktivitaet_abfragbar_und_der_zugriff_wird_auditiert() {
        val t = env.tenant(); val admin = UUID.randomUUID(); val newcomer = UUID.randomUUID()
        env.grant(t, admin, "tenant_admin")
        val token = env.token(admin)
        val created = env.post("/v1/memberships", """{"issuer":"${env.idp.issuer}","subject":"$newcomer","role_code":"support"}""", token, null, idem())
        assertEquals(201, created.status, created.text)
        val id = created.json["id"].asString()

        val r = env.get("/v1/audit-events?purpose=security_review&action=identity.membership.granted&object_ref=membership:$id", token)
        assertEquals(200, r.status, r.text)
        val item = r.json["items"].values().single()
        assertEquals("user:$admin", item["actor_ref"].asString()); assertEquals("identity.membership.granted", item["action"].asString())
        assertEquals("membership:$id", item["object_ref"].asString()); assertTrue(item["occurred_at"].asString().endsWith("Z"))
        assertEquals("support", item["redacted_changes"]["role_code"].asString())

        // der Auditzugriff selbst ist protokolliert, mit Zweck
        val self = env.get("/v1/audit-events?purpose=incident_response&action=audit.events.read", token)
        val reads = self.json["items"].values().toList()
        assertTrue(reads.isNotEmpty() && reads.all { it["actor_ref"].asString() == "user:$admin" })
        assertTrue(reads.any { it["redacted_changes"]["purpose"].asString() == "security_review" })
    }

    @Test
    fun ohne_zweck_ohne_berechtigung_und_fremdmandant_abgewiesen() {
        val a = env.tenant(); val b = env.tenant()
        val adminA = UUID.randomUUID(); val adminB = UUID.randomUUID(); val passenger = UUID.randomUUID(); val support = UUID.randomUUID()
        env.grant(a, adminA, "tenant_admin"); env.grant(a, passenger, "passenger"); env.grant(a, support, "support"); env.grant(b, adminB, "tenant_admin")
        val ta = env.token(adminA); val tb = env.token(adminB)
        val marker = env.post("/v1/memberships", """{"issuer":"${env.idp.issuer}","subject":"nur-b-${UUID.randomUUID()}","role_code":"dispatcher"}""", tb, null, idem())
        assertEquals(201, marker.status)

        assertEquals(403, env.get("/v1/audit-events?purpose=security_review", env.token(passenger)).status, "Fahrgast ohne Berechtigung")
        assertEquals(200, env.get("/v1/audit-events?purpose=support_case", env.token(support)).status, "Rolle Support darf nach Rechtematrix")
        assertEquals(422, env.get("/v1/audit-events", ta).status, "ohne Zweck")
        assertEquals(422, env.get("/v1/audit-events?purpose=neugier", ta).status, "unbekannter Zweck")
        assertEquals(422, env.get("/v1/audit-events?purpose=security_review&from=gestern", ta).status, "ungültiger Zeitpunkt")
        assertEquals(401, env.server.send("GET", "/v1/audit-events?purpose=security_review", null, emptyMap()).status, "ohne Anmeldung")

        val a2 = env.get("/v1/audit-events?purpose=security_review&limit=100", ta)
        assertEquals(200, a2.status)
        assertFalse(a2.text.contains(adminB.toString()) || a2.text.contains(marker.json["id"].asString()), "Mandant A sah Auditzeilen von B")
        val b2 = env.get("/v1/audit-events?purpose=security_review&action=identity.membership.granted", tb)
        assertTrue(b2.text.contains(marker.json["id"].asString()))
    }

    @Test
    fun cursor_liefert_folgeseite_und_ist_an_den_filter_gebunden() {
        val t = env.tenant(); val admin = UUID.randomUUID(); env.grant(t, admin, "tenant_admin")
        val token = env.token(admin)
        repeat(3) { env.post("/v1/memberships", """{"issuer":"${env.idp.issuer}","subject":"p-${UUID.randomUUID()}","role_code":"driver"}""", token, null, idem()) }
        val q = "purpose=security_review&action=identity.membership.granted"
        val p1 = env.get("/v1/audit-events?$q&limit=2", token)
        assertEquals(2, p1.json["items"].size()); val next = p1.json["next_cursor"].asString()
        val p2 = env.get("/v1/audit-events?$q&limit=2&cursor=$next", token)
        assertEquals(200, p2.status, p2.text); assertEquals(1, p2.json["items"].size()); assertTrue(p2.json["next_cursor"].isNull)
        assertEquals(400, env.get("/v1/audit-events?purpose=security_review&limit=2&cursor=$next", token).status, "Cursor eines anderen Filters")
    }
}
