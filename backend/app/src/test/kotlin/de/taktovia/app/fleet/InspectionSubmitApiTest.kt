package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * WP-M3-14 / LH-6.21-CHK-013 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak): `POST /v1/inspections/{id}/submit` mit `If-Match` (428/412), Idempotenz
 * (gleicher Schlüssel: dieselbe Antwort ohne zweite Freigabe; anderer Inhalt: 409 `IDEMPOTENCY_CONFLICT`), Clientfelder (422), fremde Prüfung (404), Rechte (403) und `GET` mit
 * Vorlage und Antworten im Originalstand.
 */
class InspectionSubmitApiTest {
    private val env = IdentityEnv
    private fun idem(k: String = "k-" + UUID.randomUUID()) = mapOf("Idempotency-Key" to k)

    /** Welt mit qualifiziertem Fahrer (Qualifikation für die Einsatzfreigabe), freigegebener Vorlage und gestartetem Lauf. */
    private class Started(val w: InspectionApiTest.World, val id: String, val shift: UUID, val vehicle: UUID)

    private fun qualify(w: InspectionApiTest.World) {
        w.env.exec("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,approval_status,driver_seq) VALUES (?,?,?,'PBEFG',1,now() - interval '30 days','APPROVED',1)", w.tenant.value, UUID.randomUUID(), w.driverId)
        w.env.exec("UPDATE taktovia.driver SET qualification_seq = 1 WHERE id = ?", w.driverId)
    }

    private fun started(steps: Int = 3, qualified: Boolean = true): Started {
        val w = InspectionApiTest.World(env); if (qualified) qualify(w); w.template("PKW", steps = steps)
        val vehicle = w.vehicle("PKW"); val shift = w.shift(vehicle = vehicle)
        val c = env.post("/v1/inspections", w.body(shift, vehicle), w.token, null, idem())
        check(c.status == 201) { c.text }
        return Started(w, c.json["id"].asString(), shift, vehicle)
    }

    private fun answers(steps: Int = 3, defectAt: Int? = null) = (1..steps).joinToString(",") {
        if (it == defectAt) """{"step_code":"S$it","answer":"DEFECT","note":"Riss","answered_at":"${Instant.now().minusSeconds(20)}"}""" else """{"step_code":"S$it","answer":"OK","answered_at":"${Instant.now().minusSeconds(20)}"}"""
    }
    private fun body(a: String = answers(), extra: String = "") = """{"answers":[$a],"client_submitted_at":"${Instant.now().minusSeconds(2)}"$extra}"""
    private fun submit(s: Started, body: String = body(), token: String = s.w.token, rev: String? = "\"1\"", key: String = "k-" + UUID.randomUUID()) =
        env.server.send("POST", "/v1/inspections/${s.id}/submit", body, env.headers(token, extra = idem(key) + (rev?.let { mapOf("If-Match" to it) } ?: emptyMap())), "application/json")

    @Test
    fun abschluss_liefert_ergebnis_freigabe_und_unveraenderlichen_nachweis() {
        val s = started()
        val r = submit(s)
        assertEquals(200, r.status, r.text); assertEquals("\"2\"", r.header("ETag"))
        assertEquals("COMPLETED", r.json["status"].asString()); assertEquals("PASSED", r.json["outcome"].asString()); assertEquals("user:${s.w.account}", r.json["completed_by"].asString())
        assertFalse(r.json["release"].isNull); assertEquals(0, r.json["missing_requirements"].size()); assertEquals(3, r.json["answers"].size())
        // lesen: Vorlage und Antworten im Originalstand
        val g = env.get("/v1/inspections/${s.id}", s.w.token)
        assertEquals(200, g.status); assertEquals("COMPLETED", g.json["status"].asString()); assertEquals(3, g.json["template"]["steps"].size()); assertEquals("\"2\"", g.header("ETag"))
        // Freigabe ist die einzige und gilt für genau diese Schicht
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.resource_release WHERE tenant_id=? AND inspection_ref=? AND shift_id=? AND vehicle_id=?", s.w.tenant.value, UUID.fromString(s.id), s.shift, s.vehicle))
    }

    @Test
    fun if_match_fehlt_428_veraltet_412() {
        val s = started()
        val missing = submit(s, rev = null)
        assertEquals(428, missing.status, missing.text); assertEquals("PRECONDITION_REQUIRED", missing.json["code"].asString())
        val stale = submit(s, rev = "\"9\"")
        assertEquals(412, stale.status, stale.text); assertEquals("REVISION_MISMATCH", stale.json["code"].asString())
        assertEquals(422, submit(s, rev = "*").status)
        assertEquals("IN_PROGRESS", env.get("/v1/inspections/${s.id}", s.w.token).json["status"].asString())
    }

    @Test
    fun gleicher_schluessel_anderer_inhalt_ergibt_409_idempotency_conflict() {
        val s = started()
        val key = "k-" + UUID.randomUUID(); val b = body()
        val first = submit(s, b, key = key)
        assertEquals(200, first.status, first.text)
        val replay = submit(s, b, key = key)
        assertEquals(200, replay.status, replay.text); assertEquals("true", replay.header("Idempotent-Replayed")); assertEquals(first.json["id"].asString(), replay.json["id"].asString())
        assertEquals(first.json["release"]["release_id"].asString(), replay.json["release"]["release_id"].asString(), "dieselbe Freigabe, keine zweite")
        val conflict = submit(s, body(answers(defectAt = 2)), key = key)
        assertEquals(409, conflict.status, conflict.text); assertEquals("IDEMPOTENCY_CONFLICT", conflict.json["code"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.resource_release WHERE tenant_id=? AND inspection_ref=?", s.w.tenant.value, UUID.fromString(s.id)))
        // anderer Schlüssel nach dem Abschluss: Prüfnachweis ist unveränderlich (409 IMMUTABLE_RECORD)
        val again = submit(s, b, rev = "\"2\"")
        assertEquals(409, again.status, again.text); assertEquals("IMMUTABLE_RECORD", again.json["code"].asString())
    }

    @Test
    fun clientfelder_pflichtantworten_fremde_pruefung_und_rechte() {
        val s = started()
        // Prüfer, Mandant, Ergebnis vom Client: abgewiesen (422), nichts übernommen
        for (f in listOf("inspector_ref", "outcome", "completed_by")) {
            val r = submit(s, body(extra = ""","$f":"x""""))
            assertEquals(422, r.status, "$f ${r.text}"); assertEquals("UNKNOWN_FIELD", r.json["violations"][0]["code"].asString())
        }
        assertEquals(422, submit(s, body(extra = ""","tenant_id":"${s.w.tenant.value}"""")).status, "der eigene Mandant im Body: unbekanntes Feld")
        assertEquals(404, submit(s, body(extra = ""","tenant_id":"${UUID.randomUUID()}"""")).status, "ein fremder Mandant im Body: kein Eingabeweg (Plattformregel)")
        // fehlende Pflichtantwort: 422 mit Verweis auf das Element
        val missing = submit(s, body(answers(2)))
        assertEquals(422, missing.status, missing.text); assertEquals("/answers/S3", missing.json["violations"][0]["pointer"].asString())
        // fremde Prüfung: anderer Fahrer 404, anderer Mandant 404, Disposition und Fahrgast 403
        assertEquals(404, submit(s, token = s.w.otherToken).status)
        assertEquals(404, submit(s, token = InspectionApiTest.World(env).token).status)
        assertEquals(403, submit(s, token = s.w.dispatcherToken).status)
        assertEquals(403, submit(s, token = s.w.passengerToken).status)
        assertEquals(401, env.server.post("/v1/inspections/${s.id}/submit", body(), idem() + mapOf("If-Match" to "\"1\"")).status)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.inspection_answer WHERE tenant_id=? AND inspection_id=?", s.w.tenant.value, UUID.fromString(s.id)))
    }

    @Test
    fun kritischer_mangel_und_fehlende_qualifikation_ergeben_keine_freigabe_mit_fehlenden_voraussetzungen() {
        val unqualified = started(qualified = false)
        val r = submit(unqualified)
        assertEquals(200, r.status, r.text); assertEquals("PASSED", r.json["outcome"].asString()); assertTrue(r.json["release"].isNull)
        assertEquals("QUALIFICATION_MISSING", r.json["missing_requirements"][0]["code"].asString())
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.resource_release WHERE tenant_id=?", unqualified.w.tenant.value))
    }
}
