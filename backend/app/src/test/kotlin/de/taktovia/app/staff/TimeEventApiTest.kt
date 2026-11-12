package de.taktovia.app.staff

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-08 / LH-6.24-TIM-001 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): Beginn, Pause, Ende; Wiederholung mit gleicher Ereignis-ID erzeugt keine zweite Zeile
 * (auch 40-fach parallel über HTTP); Mitarbeiter sieht nur eigene Ereignisse (fremde Person 404), Nacherfassung nur für die Personalrolle; Mandantentrennung mit Sicherheitsprotokoll.
 */
class TimeEventApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    private class World(val env: IdentityEnv) {
        val tenant = env.tenant("Zeit-" + UUID.randomUUID())
        val driver = UUID.randomUUID(); val other = UUID.randomUUID(); val hr = UUID.randomUUID(); val planner = UUID.randomUUID(); val admin = UUID.randomUUID(); val passenger = UUID.randomUUID()
        val vehicle = UUID.randomUUID(); val shift = UUID.randomUUID()
        init {
            env.grant(tenant, driver, "driver"); env.grant(tenant, other, "driver"); env.grant(tenant, hr, "staff_planning"); env.grant(tenant, planner, "planner")
            env.grant(tenant, admin, "tenant_admin"); env.grant(tenant, passenger, "passenger")
            env.tx.run(tenant) { j ->
                j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'fixture','{}')", tenant.value, vehicle)
                j.update("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,now(),now()+interval '8 hours','Europe/Berlin',0,'PLANNED')", tenant.value, shift, driver, vehicle)
            }
        }
        val driverToken = env.token(driver); val otherToken = env.token(other); val hrToken = env.token(hr); val plannerToken = env.token(planner); val adminToken = env.token(admin); val passengerToken = env.token(passenger)
    }

    private fun body(kind: String, at: String, id: UUID = UUID.randomUUID(), shift: UUID? = null, channel: String = "APP") =
        """{"event_id":"$id","kind":"$kind","occurred_at":"$at","device_recorded_at":"$at","channel":"$channel","shift_id":${shift?.let { "\"$it\"" } ?: "null"}}"""

    @Test
    fun beginn_pause_ende_und_intervall_abzueglich_pause() {
        val w = World(env)
        listOf("WORK_START" to "06:00", "BREAK_START" to "10:00", "BREAK_END" to "10:30", "WORK_END" to "14:00").forEach { (k, t) ->
            val r = env.post("/v1/time-events", body(k, "2026-12-01T$t:00Z", shift = w.shift), w.driverToken, extra = idem())
            assertEquals(201, r.status, r.text); assertEquals(w.driver.toString(), r.json["person_ref"].asString()); assertEquals("APP", r.json["source"].asString()); assertEquals(w.driver.toString(), r.json["recorded_by"].asString())
            assertEquals(w.shift.toString(), r.json["shift_id"].asString())
        }
        val l = env.get("/v1/time-events?shift_id=${w.shift}", w.driverToken)
        assertEquals(200, l.status, l.text); assertEquals(4, l.json["items"].size())
        val iv = env.get("/v1/time-events/intervals?from=2026-12-01T00:00:00Z&to=2026-12-02T00:00:00Z", w.driverToken)
        assertEquals(200, iv.status, iv.text); val i = iv.json["intervals"].single()
        assertEquals(8 * 3600 - 1800, i["net_seconds"].asLong()); assertEquals(0, i["flags"].size()); assertEquals(1, i["breaks"].size())
        assertEquals(4, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='staff.time_event_recorded'", w.tenant.value))
    }

    @Test
    fun wiederholung_mit_gleicher_ereignis_id_erzeugt_keinen_zweiten_eintrag_auch_parallel() {
        val w = World(env); val id = UUID.randomUUID(); val b = body("WORK_START", "2026-12-01T06:00:00Z", id)
        val first = env.post("/v1/time-events", b, w.driverToken, extra = idem())
        val second = env.post("/v1/time-events", b, w.driverToken, extra = idem())
        assertEquals(201, first.status); assertEquals(200, second.status, second.text); assertEquals(first.json["received_at"].asString(), second.json["received_at"].asString())
        val id2 = UUID.randomUUID(); val b2 = body("WORK_END", "2026-12-01T14:00:00Z", id2)
        val pool = Executors.newFixedThreadPool(16); val start = CountDownLatch(1)
        val futures = (1..40).map { pool.submit<Int> { start.await(); env.post("/v1/time-events", b2, w.driverToken, extra = idem()).status } }
        start.countDown(); val statuses = futures.map { it.get(120, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, statuses.count { it == 201 }, statuses.toString()); assertEquals(39, statuses.count { it == 200 }, statuses.toString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=? AND id=?", w.tenant.value, id2))
        assertEquals(409, env.post("/v1/time-events", body("WORK_END", "2026-12-01T06:00:00Z", id), w.driverToken, extra = idem()).status, "gleiche ID, anderer Inhalt")
    }

    @Test
    fun mitarbeiter_sieht_nur_eigene_ereignisse_nacherfassung_nur_personalrolle() {
        val w = World(env)
        env.post("/v1/time-events", body("WORK_START", "2026-12-01T06:00:00Z"), w.otherToken, extra = idem())
        env.post("/v1/time-events", body("WORK_START", "2026-12-01T06:05:00Z"), w.driverToken, extra = idem())
        assertEquals(1, env.get("/v1/time-events", w.driverToken).json["items"].size(), "nur eigene")
        assertEquals(404, env.get("/v1/time-events?person_ref=${w.other}", w.driverToken).status, "fremde Person")
        assertEquals(404, env.get("/v1/time-events/intervals?person_ref=${w.other}&from=2026-12-01T00:00:00Z&to=2026-12-02T00:00:00Z", w.driverToken).status)
        assertEquals(404, env.get("/v1/time-events?person_ref=${w.other}", w.plannerToken).status, "Planer ohne Personalrecht sieht fremde Personen nicht")
        val hrView = env.get("/v1/time-events?person_ref=${w.other}", w.hrToken)
        assertEquals(200, hrView.status, hrView.text); assertEquals(1, hrView.json["items"].size(), "Planersicht der Personalrolle")
        // Nacherfassung
        val bf = """{"event_id":"${UUID.randomUUID()}","person_ref":"${w.driver}","kind":"WORK_END","occurred_at":"2026-12-01T14:00:00Z","shift_id":"${w.shift}","reason":"Stempeln vergessen"}"""
        assertEquals(403, env.post("/v1/time-events/backfills", bf, w.driverToken, extra = idem()).status)
        assertEquals(403, env.post("/v1/time-events/backfills", bf, w.plannerToken, extra = idem()).status)
        assertEquals(403, env.post("/v1/time-events/backfills", bf, w.adminToken, extra = idem()).status, "Administrator ohne Fachrolle: kein Zugriff auf HR-Daten")
        val ok = env.post("/v1/time-events/backfills", bf, w.hrToken, extra = idem())
        assertEquals(201, ok.status, ok.text); assertEquals("BACKFILL", ok.json["source"].asString()); assertEquals(w.hr.toString(), ok.json["recorded_by"].asString()); assertEquals("Stempeln vergessen", ok.json["backfill_reason"].asString())
        assertEquals(1, env.events(w.tenant, "staff.time_event.backfilled"))
        assertEquals(403, env.get("/v1/time-events", w.passengerToken).status)
        assertEquals(403, env.post("/v1/time-events", body("WORK_START", "2026-12-01T06:00:00Z"), w.passengerToken, extra = idem()).status)
        assertEquals(403, env.get("/v1/time-events", w.adminToken).status)
    }

    @Test
    fun ereignisse_sind_nicht_aenderbar_und_die_person_steht_nicht_im_body() {
        val w = World(env); val id = UUID.randomUUID()
        env.post("/v1/time-events", body("WORK_START", "2026-12-01T06:00:00Z", id), w.driverToken, extra = idem())
        for (m in listOf("PUT", "PATCH", "DELETE")) {
            val r = env.server.send(m, "/v1/time-events/$id", null, env.headers(w.driverToken, extra = idem()))
            assertTrue(r.status in setOf(404, 405), "$m: ${r.status}")
        }
        assertEquals(405, env.server.send("DELETE", "/v1/time-events", null, env.headers(w.driverToken, extra = idem())).status)
        val withPerson = """{"event_id":"${UUID.randomUUID()}","person_ref":"${w.other}","kind":"WORK_START","occurred_at":"2026-12-01T06:00:00Z","device_recorded_at":"2026-12-01T06:00:00Z","channel":"APP","shift_id":null}"""
        assertEquals(403, env.post("/v1/time-events", withPerson, w.driverToken, extra = idem()).status, "die Person folgt aus dem Akteur; eine fremde Personen-ID im Body wird abgewiesen (WP-M4-11)")
        val bf = """{"event_id":"${UUID.randomUUID()}","person_ref":"p","kind":"WORK_START","occurred_at":"2026-12-01T06:00:00Z","reason":"x"}"""
        assertEquals(422, env.post("/v1/time-events/backfills", bf, w.hrToken, extra = idem()).status, "Begründung zu kurz")
        assertEquals(422, env.post("/v1/time-events", body("WORK_START", "2026-12-01T06:00:00Z", channel = "BACKFILL"), w.driverToken, extra = idem()).status, "Nacherfassung ist kein Kanal der Selbsterfassung")
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=?", w.tenant.value))
    }

    @Test
    fun fremdmandant_sieht_nichts_und_der_versuch_ist_protokolliert() {
        val a = World(env); val b = World(env)
        env.post("/v1/time-events", body("WORK_START", "2026-12-01T06:00:00Z"), a.driverToken, extra = idem())
        assertEquals(0, env.get("/v1/time-events?person_ref=${a.driver}", b.hrToken).json["items"].size(), "leere Liste")
        assertEquals(0, env.get("/v1/time-events/intervals?person_ref=${a.driver}&from=2026-12-01T00:00:00Z&to=2026-12-02T00:00:00Z", b.hrToken).json["intervals"].size())
        val before = env.events(b.tenant, "security.tenant.mismatch")
        assertEquals(404, env.get("/v1/time-events?tenant_id=${a.tenant}", b.driverToken).status)
        assertEquals(404, env.get("/v1/time-events", b.driverToken, extra = mapOf("X-Tenant-Id" to a.tenant.toString())).status)
        assertEquals(before + 2, env.events(b.tenant, "security.tenant.mismatch"))
        val cross = env.post("/v1/time-events", body("WORK_START", "2026-12-01T06:00:00Z", shift = a.shift), b.driverToken, extra = idem())
        assertEquals(404, cross.status, cross.text); assertFalse(cross.text.contains(a.shift.toString()))
    }
}
