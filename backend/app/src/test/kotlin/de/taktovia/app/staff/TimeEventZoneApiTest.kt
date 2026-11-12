package de.taktovia.app.staff

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M4-10 / LH-6.24-TIM-005 über HTTP: Zeitereignis mit Zone, lokale Darstellung mit Offset (doppelte Herbst-02:30 unterscheidbar), Ortszeit in der Frühjahrslücke mit 422 abgelehnt,
 * Intervall über die Herbstumstellung mit 9 h absoluter Arbeitszeit und lokaler Darstellung 22:00-06:00.
 */
class TimeEventZoneApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())
    private fun body(kind: String, at: String, zone: String? = "Europe/Berlin") =
        """{"event_id":"${UUID.randomUUID()}","kind":"$kind","occurred_at":"$at","device_recorded_at":"$at","channel":"APP"${zone?.let { ""","time_zone":"$it"""" } ?: ""}}"""

    @Test
    fun nachtschicht_ueber_herbstumstellung_ueber_http_9_stunden_und_lokale_darstellung() {
        val tenant = env.tenant("Zone-" + UUID.randomUUID()); val driver = UUID.randomUUID(); env.grant(tenant, driver, "driver"); val token = env.token(driver)
        assertEquals(201, env.post("/v1/time-events", body("WORK_START", "2026-10-24T22:00:00+02:00"), token, extra = idem()).status)
        val end = env.post("/v1/time-events", body("WORK_END", "2026-10-25T06:00:00+01:00"), token, extra = idem())
        assertEquals(201, end.status, end.text)
        assertEquals("Europe/Berlin", end.json["time_zone"].asString()); assertEquals("2026-10-25T06:00:00+01:00", end.json["occurred_at_local"]["local"].asString())
        val iv = env.get("/v1/time-events/intervals?from=2026-10-24T00:00:00Z&to=2026-10-26T00:00:00Z", token)
        assertEquals(200, iv.status, iv.text); val i = iv.json["intervals"].single()
        assertEquals(9 * 3600, i["net_seconds"].asLong())
        assertEquals("2026-10-24T22:00:00+02:00", i["start_local"]["local"].asString()); assertEquals("2026-10-25T06:00:00+01:00", i["end_local"]["local"].asString())
        assertEquals("2026-10-24", i["operating_day"].asString())
    }

    @Test
    fun doppelte_ortszeit_beide_gespeichert_und_luecke_abgelehnt() {
        val tenant = env.tenant("Zone-" + UUID.randomUUID()); val driver = UUID.randomUUID(); env.grant(tenant, driver, "driver"); val token = env.token(driver)
        val a = env.post("/v1/time-events", body("WORK_START", "2026-10-25T02:30:00+02:00"), token, extra = idem())
        val b = env.post("/v1/time-events", body("WORK_END", "2026-10-25T02:30:00+01:00"), token, extra = idem())
        assertEquals(201, a.status, a.text); assertEquals(201, b.status, b.text)
        assertTrue(a.json["occurred_at_local"]["ambiguous"].asBoolean()); assertEquals("+02:00", a.json["occurred_at_local"]["offset"].asString()); assertEquals("+01:00", b.json["occurred_at_local"]["offset"].asString())
        assertEquals(2, env.get("/v1/time-events?from=2026-10-24T00:00:00Z&to=2026-10-26T00:00:00Z", token).json["items"].size())
        val gap = env.post("/v1/time-events", body("WORK_START", "2026-03-29T02:30:00+01:00"), token, extra = idem())
        assertEquals(422, gap.status, gap.text)
        assertEquals(201, env.post("/v1/time-events", body("WORK_START", "2026-06-10T12:00:00Z"), token, extra = idem()).status, "UTC-Stempel ist ein eindeutiger Zeitpunkt")
        assertEquals(422, env.post("/v1/time-events", body("WORK_START", "2026-06-10T12:00:00+02:00", zone = "CET"), token, extra = idem()).status, "keine IANA-Zone")
    }
}
