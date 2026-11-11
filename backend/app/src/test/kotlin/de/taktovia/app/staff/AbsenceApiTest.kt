package de.taktovia.app.staff

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M4-06 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): Anlegen durch die Personalrolle, Lesen als Restriktion ohne Art, 403 für Planer, Mandantentrennung
 * (Abwesenheitsteil von AT-49: Fremdmandant erhält 404 und das Versuchsprotokoll), Rücknahme mit Revision.
 */
class AbsenceApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    private class World(val env: IdentityEnv) {
        val tenant = env.tenant("Abw-" + UUID.randomUUID())
        val hr = UUID.randomUUID(); val planner = UUID.randomUUID(); val admin = UUID.randomUUID(); val dispatcher = UUID.randomUUID(); val driver = UUID.randomUUID()
        init { env.grant(tenant, hr, "staff_planning"); env.grant(tenant, planner, "planner"); env.grant(tenant, admin, "tenant_admin"); env.grant(tenant, dispatcher, "dispatcher"); env.grant(tenant, driver, "driver") }
        val hrToken = env.token(hr); val plannerToken = env.token(planner); val adminToken = env.token(admin); val dispatcherToken = env.token(dispatcher); val driverToken = env.token(driver)
    }

    private fun body(person: String, kind: String = "LEAVE") = """{"person_ref":"$person","from":"2027-03-01T00:00:00Z","to":"2027-03-06T00:00:00Z","kind":"$kind"}"""

    @Test
    fun personalrolle_legt_an_und_planer_sieht_nur_nicht_verfuegbar() {
        val w = World(env); val person = UUID.randomUUID().toString()
        val c = env.post("/v1/absences", body(person, "TRAINING"), w.hrToken, extra = idem())
        assertEquals(201, c.status, c.text); assertEquals("\"1\"", c.header("ETag")); val id = c.json["id"].asString(); assertEquals("/v1/absences/$id", c.header("Location"))
        assertEquals("TRAINING", c.json["kind"].asString())
        for (token in listOf(w.plannerToken, w.dispatcherToken, w.hrToken)) {
            val l = env.get("/v1/absences?person_ref=$person", token)
            assertEquals(200, l.status, l.text)
            val item = l.json["items"].single()
            assertEquals("UNAVAILABLE", item["availability"].asString()); assertEquals(person, item["person_ref"].asString())
            assertEquals(setOf("id", "person_ref", "from", "to", "availability"), item.propertyNames().toSet(), "Restriktion trägt nur Person und Zeitraum")
            assertFalse(l.text.contains("TRAINING") || l.text.contains("\"kind\""), "Art darf in der Restriktionssicht nie auftauchen")
        }
        assertEquals("TRAINING", env.get("/v1/absences/$id", w.hrToken).json["kind"].asString(), "Detail mit Art für die Personalrolle")
        assertEquals(1, env.events(w.tenant, "staff.absence.created"), "Anlage steht im audit_event")
    }

    @Test
    fun planer_erhaelt_die_art_nicht_und_darf_nicht_anlegen_oder_zuruecknehmen() {
        val w = World(env); val id = env.post("/v1/absences", body("p-1"), w.hrToken, extra = idem()).json["id"].asString()
        assertEquals(403, env.get("/v1/absences/$id", w.plannerToken).status, "Detail mit Art nur für die Personalrolle")
        assertEquals(403, env.get("/v1/absences/$id", w.dispatcherToken).status)
        for (token in listOf(w.plannerToken, w.dispatcherToken, w.driverToken, w.adminToken)) {
            assertEquals(403, env.post("/v1/absences", body("p-2"), token, extra = idem()).status, "Anlegen ohne Personalrolle")
        }
        assertEquals(403, env.post("/v1/absences/$id/withdraw", "", w.plannerToken, extra = idem() + ("If-Match" to "\"1\"")).status)
        assertEquals(403, env.get("/v1/absences", w.adminToken).status, "Administrator hat ohne Fachrolle keinen Zugriff auf HR-Daten")
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.absence_request WHERE tenant_id=?", w.tenant.value))
    }

    @Test
    fun fremdmandant_erhaelt_404_oder_leere_liste_und_der_versuch_ist_protokolliert() {
        val a = World(env); val b = World(env)
        val id = env.post("/v1/absences", body("p-1"), a.hrToken, extra = idem()).json["id"].asString()
        assertEquals(404, env.get("/v1/absences/$id", b.hrToken).status)
        assertEquals(404, env.post("/v1/absences/$id/withdraw", "", b.hrToken, extra = idem() + ("If-Match" to "\"1\"")).status)
        assertEquals(0, env.get("/v1/absences", b.plannerToken).json["items"].size(), "leere Liste")
        val before = env.events(b.tenant, "security.tenant.mismatch")
        val r = env.get("/v1/absences?tenant_id=${a.tenant}", b.plannerToken)
        assertEquals(404, r.status, r.text); assertFalse(r.text.contains(id))
        val r2 = env.get("/v1/absences", b.plannerToken, extra = mapOf("X-Tenant-Id" to a.tenant.toString()))
        assertEquals(404, r2.status)
        assertEquals(before + 2, env.events(b.tenant, "security.tenant.mismatch"), "Fremdzugriff ist im Sicherheitsprotokoll")
        assertEquals("APPROVED", env.string("SELECT state FROM taktovia.absence_request WHERE id=?", UUID.fromString(id)).single())
    }

    @Test
    fun ruecknahme_verlangt_if_match_und_nimmt_die_restriktion_zurueck() {
        val w = World(env); val person = UUID.randomUUID().toString()
        val id = env.post("/v1/absences", body(person), w.hrToken, extra = idem()).json["id"].asString()
        assertEquals(428, env.post("/v1/absences/$id/withdraw", "", w.hrToken, extra = idem()).status)
        assertEquals(412, env.post("/v1/absences/$id/withdraw", "", w.hrToken, extra = idem() + ("If-Match" to "\"9\"")).status)
        val ok = env.post("/v1/absences/$id/withdraw", "", w.hrToken, extra = idem() + ("If-Match" to "\"1\""))
        assertEquals(200, ok.status, ok.text); assertEquals("WITHDRAWN", ok.json["state"].asString()); assertEquals("\"2\"", ok.header("ETag"))
        assertEquals(0, env.get("/v1/absences?person_ref=$person", w.plannerToken).json["items"].size())
        assertEquals(1, env.events(w.tenant, "staff.absence.withdrawn"))
    }

    @Test
    fun wiederholter_idempotency_key_legt_nur_eine_abwesenheit_an() {
        val w = World(env); val key = idem(); val person = UUID.randomUUID().toString()
        val a = env.post("/v1/absences", body(person), w.hrToken, extra = key); val b = env.post("/v1/absences", body(person), w.hrToken, extra = key)
        assertEquals(201, a.status); assertEquals(a.json["id"].asString(), b.json["id"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.absence_request WHERE tenant_id=? AND person_ref=?", w.tenant.value, person))
    }

    @Test
    fun ungueltige_eingaben_ergeben_422_ohne_diagnose_oder_kommentarfeld() {
        val w = World(env)
        assertEquals(422, env.post("/v1/absences", body("p", "SICK"), w.hrToken, extra = idem()).status, "Krankheit ist keine zulässige Art")
        assertEquals(422, env.post("/v1/absences", """{"person_ref":"p","from":"2027-03-01T00:00:00Z","to":"2027-03-06T00:00:00Z","kind":"LEAVE","comment":"Grippe"}""", w.hrToken, extra = idem()).status)
        assertEquals(422, env.post("/v1/absences", """{"person_ref":"p","from":"2027-03-06T00:00:00Z","to":"2027-03-01T00:00:00Z","kind":"LEAVE"}""", w.hrToken, extra = idem()).status)
        assertTrue(env.count("SELECT count(*) FROM taktovia.absence_request WHERE tenant_id=?", w.tenant.value) == 0)
    }
}
