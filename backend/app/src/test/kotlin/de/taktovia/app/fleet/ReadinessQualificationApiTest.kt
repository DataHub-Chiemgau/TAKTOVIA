package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * WP-M3-19 / LH-6.21-CHK-010 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak): `POST /v1/inspections/{id}/submit` mit der Selbstauskunft „Einsatzbereitschaft“.
 * Zusatztext ist 422 `application/problem+json` ohne den Text zu wiederholen, `NEIN` und fehlende Qualifikation ergeben keine Freigabe; die Fahrermeldung nennt nur den neutralen Code.
 */
class ReadinessQualificationApiTest {
    private val env = IdentityEnv
    private val freetext = "Migraene seit gestern"
    private fun idem(k: String = "k-" + UUID.randomUUID()) = mapOf("Idempotency-Key" to k)

    private class Started(val w: InspectionApiTest.World, val id: String)

    private fun started(qualified: Boolean = true): Started {
        val w = InspectionApiTest.World(env)
        if (qualified) {
            w.env.exec("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,approval_status,driver_seq) VALUES (?,?,?,'PBEFG',1,now() - interval '30 days','APPROVED',1)", w.tenant.value, UUID.randomUUID(), w.driverId)
            w.env.exec("UPDATE taktovia.driver SET qualification_seq = 1 WHERE id = ?", w.driverId)
        }
        w.template("PKW", steps = 1, readinessStep = true)
        val vehicle = w.vehicle("PKW"); val shift = w.shift(vehicle = vehicle)
        val s = env.post("/v1/inspections", w.body(shift, vehicle), w.token, null, idem()); check(s.status == 201) { s.text }
        return Started(w, s.json["id"].asString())
    }

    private fun answers(readiness: String = "JA", extra: String = "") =
        """[{"step_code":"S1","answer":"OK","answered_at":"${Instant.now().minusSeconds(20)}"},{"step_code":"EINSATZBEREITSCHAFT","answer":"$readiness"$extra,"answered_at":"${Instant.now().minusSeconds(20)}"}]"""

    private fun submit(s: Started, a: String) =
        env.server.send("POST", "/v1/inspections/${s.id}/submit", """{"answers":$a,"client_submitted_at":"${Instant.now().minusSeconds(2)}"}""", env.headers(s.w.token, extra = idem() + mapOf("If-Match" to "\"1\"")), "application/json")

    @Test
    fun ja_mit_gueltiger_qualifikation_gibt_frei() {
        val s = started(); val r = submit(s, answers("JA"))
        assertEquals(200, r.status, r.text); assertEquals("PASSED", r.json["outcome"].asString()); assertFalse(r.json["release"].isNull)
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.resource_release WHERE tenant_id=? AND inspection_ref=? AND qualification_revision=1", s.w.tenant.value, UUID.fromString(s.id)))
    }

    @Test
    fun zusatztext_zur_einsatzbereitschaft_ist_422_und_nirgends_gespeichert() {
        val s = started()
        for (field in listOf("note", "text_value", "reason", "comment")) {
            val r = submit(s, answers("JA", ""","$field":"$freetext""""))
            assertEquals(422, r.status, "$field ${r.text}"); assertTrue(r.header("Content-Type")!!.startsWith("application/problem+json"))
            assertFalse(r.text.contains("Migraene"), "die Antwort wiederholt den Text nicht")
        }
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.inspection_answer WHERE tenant_id=? AND inspection_id=?", s.w.tenant.value, UUID.fromString(s.id)))
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND payload::text LIKE '%Migraene%'", s.w.tenant.value))
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND redacted_changes::text LIKE '%Migraene%'", s.w.tenant.value))
        assertEquals("IN_PROGRESS", env.get("/v1/inspections/${s.id}", s.w.token).json["status"].asString())
    }

    @Test
    fun nein_und_fehlende_qualifikation_ergeben_keine_freigabe_mit_neutraler_meldung() {
        val no = started(); val n = submit(no, answers("NEIN"))
        assertEquals(200, n.status, n.text); assertEquals("BLOCKED", n.json["outcome"].asString()); assertTrue(n.json["release"].isNull)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.resource_release WHERE tenant_id=? AND inspection_ref=?", no.w.tenant.value, UUID.fromString(no.id)))
        val un = started(qualified = false); val u = submit(un, answers("JA"))
        assertEquals(200, u.status, u.text); assertTrue(u.json["release"].isNull)
        assertEquals("QUALIFICATION_MISSING", u.json["missing_requirements"][0]["code"].asString())
        // neutral: kein Personen- oder Qualifikationsdetail in der Antwort
        assertFalse(u.text.contains("PBEFG"), "kein Qualifikationsdetail in der Fahrermeldung")
    }
}
