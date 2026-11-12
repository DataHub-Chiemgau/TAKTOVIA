package de.taktovia.app.driver

import de.taktovia.app.probe.Resp
import java.time.Instant
import java.util.UUID

/**
 * Gemeinsame Testhilfe des Clusters wave12-c3 (WP-M3-29, WP-M3-30): eine Fahrt aus [DriverRunHttpWorld] (echte Buchung, echter Plan, Fahrer mit angetretener, freigegebener Schicht) und
 * Fahrerereignisse über HTTP. Die Buchung ist über den echten Weg der Zahlungskopplung bestätigt (`CONFIRMED`).
 */
internal class DriverRideSupport(val w: DriverRunHttpWorld = DriverRunHttpWorld()) {
    val env = w.env

    class Ride(val d: DriverRunHttpWorld.Driver, val booking: DriverRunHttpWorld.Booking, val rev: Long, val pickup: UUID, val dropoff: UUID)

    /** Die Buchung wartet nach der Bestätigung auf die Zahlung (`CONFIRMING`); die Zahlungskopplung (echter Weg, OP-5) bestätigt sie. */
    fun confirmed(b: DriverRunHttpWorld.Booking) {
        check(env.server.ctx.getBean(de.taktovia.bookingdispatch.api.Bookings::class.java).confirmAfterPayment(w.tenant, b.id) == de.taktovia.bookingdispatch.api.CouplingOutcome.APPLIED)
    }

    fun revision(r: Ride) = env.string("SELECT revision::text FROM taktovia.booking WHERE id = ?", r.booking.id).single()

    fun ride(): Ride {
        val d = w.driver(); val b = w.booking(); confirmed(b); val rev = w.plan(d.run, listOf(b))
        return rideOf(d, b, rev)
    }

    fun rideOf(d: DriverRunHttpWorld.Driver, b: DriverRunHttpWorld.Booking, rev: Long): Ride {
        fun stop(seq: Int) = UUID.fromString(env.string("SELECT id::text FROM taktovia.route_stop WHERE run_id = ? AND run_revision = ? AND seq = ?", d.run, rev, seq).single())
        return Ride(d, b, rev, stop(0), stop(1))
    }

    fun vehicleOf(d: DriverRunHttpWorld.Driver) = env.string("SELECT vehicle_id::text FROM taktovia.staff_shift WHERE id = ?", d.shift).single()

    fun event(r: Ride, type: String, stop: UUID, id: UUID = UUID.randomUUID(), rev: Long = r.rev, offline: Boolean = false, occurred: Instant = Instant.now(), booking: UUID? = r.booking.id, extra: String = "", token: String = r.d.token): Resp {
        val b = if (booking != null && type != "ARRIVAL") ""","booking_id":"$booking"""" else ""
        val body = """{"event_id":"$id","shift_id":"${r.d.shift}","vehicle_id":"${vehicleOf(r.d)}","plan_revision":$rev,"event_type":"$type","route_stop_id":"$stop","occurred_at":"$occurred","device_recorded_at":"$occurred",
            "offline_recorded":$offline$b$extra}"""
        return env.post("/v1/driver/events", body, token, extra = mapOf("Idempotency-Key" to "ev-" + UUID.randomUUID()))
    }

    fun bookingState(r: Ride) = env.string("SELECT state FROM taktovia.booking WHERE id = ?", r.booking.id).single()
    fun legState(r: Ride) = env.string("SELECT l.execution_state FROM taktovia.journey_leg l JOIN taktovia.booking b ON b.tenant_id = l.tenant_id AND b.journey_id = l.journey_id WHERE b.id = ?", r.booking.id).single()
    fun history(r: Ride) = env.string("SELECT to_state || ':' || reason_code FROM taktovia.booking_status_history WHERE booking_id = ? ORDER BY booking_revision", r.booking.id)
    fun journal(r: Ride, type: String) = env.string("SELECT outcome || ':' || coalesce(reason_code,'-') FROM taktovia.driver_event WHERE shift_id = ? AND event_type = ? ORDER BY received_at", r.d.shift, type)
}
