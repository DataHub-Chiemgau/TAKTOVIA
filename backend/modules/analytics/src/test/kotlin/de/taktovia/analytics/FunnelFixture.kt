package de.taktovia.analytics

import de.taktovia.analytics.api.ConsumeOutcome
import de.taktovia.platform.tenant.TenantId
import java.util.UUID

/**
 * Testhilfe des Clusters wave14-c9 (WP-M4-51): Ereignisse des Nachfragetrichters (Angebot, Buchung bestätigt, Storno, Zahlungsfehlschlag, Fahrerereignis) auf der echten Datenbank, aufbauend auf [KpiFixture].
 * [Log] ist das **bekannte Ereignisprotokoll**: die erwarteten Zahlen werden aus dieser Ereignisliste gezählt, nicht aus den Fakten.
 */
class FunnelFixture : AutoCloseable {
    val kpi = KpiFixture()
    val metrics get() = kpi.metrics
    val day get() = kpi.day

    class Ev(val type: String, val id: UUID, val json: String)

    /** Bekanntes Protokoll eines Betriebstags: zehn Anfragen (drei abgelehnt, eine technisch fehlgeschlagen, sechs mit Angebot), vier bestätigte Buchungen und ihre Enden. */
    class Log(val events: List<Ev>, val bookings: List<UUID>, val offers: List<UUID>) {
        val b1get get() = bookings[0]
        fun count(type: String) = events.count { it.type == type }
    }

    private fun ev(t: TenantId, type: String, aggregate: UUID, revision: Long, at: String, payload: String): Ev =
        base().envelope(t, type, aggregate, revision, at, payload).let { Ev(type, it.first, it.second) }

    private fun base() = kpi.base

    fun offer(t: TenantId, offerId: UUID, attemptId: UUID, at: String = "2026-12-01T05:01:00Z", serviceId: UUID = kpi.service): Ev =
        ev(t, "booking.offer_created", offerId, 1, at, """{"offer_id":"$offerId","attempt_id":"$attemptId","service_id":"$serviceId","expires_at":"2026-12-01T05:16:00Z","pickup_earliest":"2026-12-01T06:00:00Z","pickup_latest":"2026-12-01T06:10:00Z","gross_minor":850,"currency":"EUR"}""")

    fun confirmed(t: TenantId, booking: UUID, offerId: UUID?, at: String = "2026-12-01T05:02:00Z", serviceId: UUID = kpi.service): Ev =
        ev(t, "booking.confirmed", booking, 3, at, """{"booking_id":"$booking","hold_id":"${UUID.randomUUID()}",${offerId?.let { "\"offer_id\":\"$it\"," } ?: ""}"service_id":"$serviceId","pickup_earliest":"2026-12-01T06:00:00Z","pickup_latest":"2026-12-01T06:10:00Z","gross_minor":850,"currency":"EUR","capacity_vector":{"seats":1}}""")

    fun cancelled(t: TenantId, booking: UUID, reason: String, cause: String, initiator: String, at: String): Ev =
        ev(t, "booking.cancelled", booking, 5, at, """{"booking_id":"$booking","reason":"$reason","reason_code":"$reason","cause_class":"$cause","initiator":"$initiator"}""")

    fun paymentFailed(t: TenantId, booking: UUID, at: String): Ev {
        val payment = UUID.randomUUID()
        return ev(t, "payment.authorization_failed", payment, 1, at, """{"payment_id":"$payment","booking_id":"$booking","failure_code":"DECLINED"}""")
    }

    fun driver(t: TenantId, booking: UUID, type: String, at: String): Ev {
        val id = UUID.randomUUID()
        return ev(t, "driver.event_recorded", id, 1, at, """{"event_id":"$id","shift_id":"${UUID.randomUUID()}","vehicle_id":"${UUID.randomUUID()}","event_type":"$type","outcome":"APPLIED","booking_id":"$booking","effective_at":"$at"}""")
    }

    fun attemptEv(t: TenantId, id: UUID, outcome: String = "RECORDED", primary: String? = null): Ev = kpi.attempt(t, id, outcome = outcome, primary = primary).let { Ev("booking.attempt_recorded", it.second, it.third) }

    fun knownLog(t: TenantId): Log {
        val events = mutableListOf<Ev>()
        repeat(3) { events += attemptEv(t, UUID.randomUUID(), "REJECTED", "NO_CAPACITY") }
        events += attemptEv(t, UUID.randomUUID(), "TECHNICAL_ERROR")
        val attempts = (1..6).map { UUID.randomUUID() }
        attempts.forEach { events += attemptEv(t, it) }
        val offers = attempts.map { UUID.randomUUID() }
        offers.forEachIndexed { i, o -> events += offer(t, o, attempts[i]) }
        val bookings = (1..4).map { UUID.randomUUID() }
        bookings.forEachIndexed { i, b -> events += confirmed(t, b, offers[i]) }
        events += driver(t, bookings[0], "CHECK_IN", "2026-12-01T05:50:00Z"); events += driver(t, bookings[0], "CHECK_OUT", "2026-12-01T06:00:00Z")
        events += cancelled(t, bookings[1], "PASSENGER_REQUEST", "FAHRGAST", "PASSENGER", "2026-12-01T05:30:00Z")
        events += cancelled(t, bookings[2], "OPERATOR_CANCELLATION", "BETREIBER", "OPERATOR", "2026-12-01T05:31:00Z")
        events += paymentFailed(t, bookings[3], "2026-12-01T05:31:30Z"); events += cancelled(t, bookings[3], "PAYMENT_FAILED", "FAHRGAST", "SYSTEM", "2026-12-01T05:32:00Z")
        return Log(events, bookings, offers)
    }

    fun feed(t: TenantId, events: List<Ev>): List<ConsumeOutcome> = events.map { kpi.base.analytics.facts.consume(t, it.json) }

    fun tenant(): TenantId = kpi.tenant()

    override fun close() = kpi.close()
}
