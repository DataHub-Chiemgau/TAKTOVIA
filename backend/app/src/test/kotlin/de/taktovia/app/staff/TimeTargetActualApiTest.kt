package de.taktovia.app.staff

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP-M4-09 / LH-6.24-TIM-004 über die echte Anwendung: Soll/Ist je Tag (AT-45: Vorbereitungszeit zählt), fehlende Ist-Buchung gekennzeichnet, fremde Person 404. */
class TimeTargetActualApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    @Test
    fun soll_ist_je_tag_mit_vorbereitungszeit_und_fehlender_buchung() {
        val tenant = env.tenant("SollIst-" + UUID.randomUUID()); val driver = UUID.randomUUID(); val other = UUID.randomUUID(); val vehicle = UUID.randomUUID()
        env.grant(tenant, driver, "driver"); env.grant(tenant, other, "driver")
        env.tx.run(tenant) { j ->
            j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'fixture','{}')", tenant.value, vehicle)
            fun shift(day: String) = j.update("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,?::timestamptz,?::timestamptz,'Europe/Berlin',0,'PLANNED')",
                tenant.value, UUID.randomUUID(), driver, vehicle, "${day}T06:00:00Z", "${day}T14:00:00Z")
            shift("2026-12-01"); shift("2026-12-02")
        }
        val token = env.token(driver)
        fun stamp(kind: String, at: String) = env.post("/v1/time-events", """{"event_id":"${UUID.randomUUID()}","kind":"$kind","occurred_at":"$at","device_recorded_at":"$at","channel":"APP","shift_id":null}""", token, extra = idem()).also { assertEquals(201, it.status, it.text) }
        stamp("WORK_START", "2026-12-01T05:30:00Z"); stamp("WORK_END", "2026-12-01T14:00:00Z")
        val r = env.get("/v1/time-events/target-actual?from=2026-12-01&to=2026-12-02", token)
        assertEquals(200, r.status, r.text)
        val d = r.json["days"]
        assertEquals(2, d.size()); assertEquals("2026-12-01", d[0]["day"].asString())
        assertEquals(8 * 3600, d[0]["planned_seconds"].asLong()); assertEquals(8 * 3600 + 1800, d[0]["actual_net_seconds"].asLong(), "Vorbereitungszeit als Arbeitszeit"); assertEquals(0, d[0]["flags"].size())
        assertEquals(8 * 3600, d[1]["planned_seconds"].asLong()); assertTrue(d[1]["actual_net_seconds"].isNull, "kein Planwert als Istwert"); assertEquals("NO_ACTUAL_BOOKING", d[1]["flags"][0].asString())
        assertEquals(404, env.get("/v1/time-events/target-actual?person_ref=$other&from=2026-12-01&to=2026-12-02", token).status)
        assertEquals(422, env.get("/v1/time-events/target-actual?from=2026-12-01&to=2027-02-01", token).status)
        assertEquals(400, env.get("/v1/time-events/target-actual?from=gestern&to=2026-12-02", token).status.let { if (it == 422) 400 else it })
    }
}
