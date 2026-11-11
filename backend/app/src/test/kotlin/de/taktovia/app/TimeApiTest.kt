package de.taktovia.app

import de.taktovia.app.probe.ProbeDb
import de.taktovia.app.probe.ProbeServer
import de.taktovia.app.probe.Resp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** LH-6.1-SYS-006 auf API-Ebene: RFC 3339 mit Offset, Feldfehler für DST-Lücke, Mehrdeutigkeit, Zone und Intervall. */
class TimeApiTest {
    private val s get() = ProbeServer.get()
    private val h get() = mapOf("X-Test-Tenant" to ProbeDb.tenantA.toString(), "X-Test-Actor" to "time-api-test")

    private fun appt(at: String, extra: String = "") = s.post("/v1/probe/appointments", """{"at":$at$extra}""", h)
    private fun at(desired: String, zone: String = "Europe/Berlin") = """{"desired_at":"$desired","time_zone":"$zone"}"""
    private fun v(r: Resp) = r.json["violations"].values().map { it["pointer"].asString() to it["code"].asString() }

    @Test
    fun doppelte_ortszeit_ueber_die_api_zwei_unterscheidbare_zeitpunkte() {
        val a = appt(at("2026-10-25T02:30:00+02:00")); val b = appt(at("2026-10-25T02:30:00+01:00"))
        assertEquals(201, a.status, a.text); assertEquals(201, b.status, b.text)
        assertEquals("2026-10-25T00:30:00Z", a.json["instant"].asString()); assertEquals("2026-10-25T01:30:00Z", b.json["instant"].asString())
        assertNotEquals(a.json["instant"].asString(), b.json["instant"].asString())
        assertEquals("2026-10-25T02:30:00+02:00", a.json["local"].asString()); assertEquals("2026-10-25T02:30:00+01:00", b.json["local"].asString())
        // lokaler Betriebstag in der Zone des Dienstes, zusammen mit dem Zeitpunkt
        val c = appt(at("2026-10-24T23:30:00Z", "UTC"), ""","service_zone":"Europe/Berlin"""")
        assertEquals(201, c.status, c.text); assertEquals("2026-10-25", c.json["operating_day"].asString())
        assertEquals("2026-10-24", appt(at("2026-10-24T23:30:00Z", "UTC")).json["operating_day"].asString())
    }

    @Test
    fun feldfehler_ohne_offset_luecke_zone_und_intervall() {
        // ohne Offset: Feldfehler am Feld, auch bei mehrdeutiger Ortszeit
        assertEquals(listOf("/at/desired_at" to "OFFSET_REQUIRED"), v(appt(at("2026-10-25T02:30:00"))).also { assertEquals(422, appt(at("2026-10-25T02:30:00")).status) })
        assertEquals(listOf("/at/desired_at" to "OFFSET_REQUIRED"), v(appt(at("2026-07-01T12:00:00"))))
        // DST-Lücke
        assertEquals(listOf("/at/desired_at" to "LOCAL_TIME_NONEXISTENT"), v(appt(at("2026-03-29T02:30:00+01:00"))))
        // Zone widerspricht Offset, unbekannte Zone
        assertEquals(listOf("/at/time_zone" to "ZONE_OFFSET_MISMATCH"), v(appt(at("2026-07-01T12:00:00+05:00"))))
        assertEquals(listOf("/at/time_zone" to "ZONE_UNKNOWN"), v(appt(at("2026-07-01T12:00:00+02:00", "Mars/Olympus"))))
        assertEquals(listOf("/service_zone" to "ZONE_UNKNOWN"), v(appt(at("2026-07-01T12:00:00+02:00"), ""","service_zone":"CET"""")))
        // lokale Eingabe: mehrdeutig ohne Offset, mit Offset gültig, Lücke
        fun local(l: String, offset: String? = null) = appt(at("2026-07-01T12:00:00+02:00"), ""","local":{"local":"$l","time_zone":"Europe/Berlin"${offset?.let { ""","utc_offset":"$it"""" } ?: ""}}""")
        assertEquals(listOf("/local/local" to "LOCAL_TIME_AMBIGUOUS"), v(local("2026-10-25T02:30:00")))
        assertEquals(201, local("2026-10-25T02:30:00", "+01:00").status)
        assertEquals(listOf("/local/utc_offset" to "ZONE_OFFSET_MISMATCH"), v(local("2026-10-25T02:30:00", "+05:00")))
        assertEquals(listOf("/local/local" to "LOCAL_TIME_NONEXISTENT"), v(local("2026-03-29T02:30:00")))
        // Intervall: Ende vor Beginn
        fun slot(f: String, t: String) = appt(at("2026-07-01T12:00:00+02:00"), ""","slot":{"valid_from":"$f","valid_to":"$t"}""")
        assertEquals(listOf("/slot/valid_to" to "INTERVAL_INVALID"), v(slot("2026-12-01T10:00:00+01:00", "2026-12-01T09:00:00Z")))
        assertEquals(listOf("/slot/valid_to" to "INTERVAL_INVALID"), v(slot("2026-12-01T10:00:00+01:00", "2026-12-01T09:00:00+00:00")))
        assertEquals(201, slot("2026-12-01T10:00:00+01:00", "2026-12-01T09:00:01Z").status)
    }
}
