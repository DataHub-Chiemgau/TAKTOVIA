package de.taktovia.analytics.internal

import de.taktovia.analytics.api.FactSource

/** Nur innerhalb des Moduls `analytics` sichtbar (durch Architekturtest erzwungen). */
internal object AnalyticsInternal

/**
 * Faktenquelle und Ereignisse, die sie speisen (WP-M4-45). [contiguous] heißt: jeder Revisionsschritt des Aggregats erzeugt ein verarbeitetes Ereignis, die Revisionsfolge je Aggregat ist also
 * lückenlos 1..n; nur dort ist eine fehlende Revision eine Lücke. Die Buchung ist nicht lückenlos (Zwischenzustände wie `booking.state_changed` verbraucht dieser Baustein nicht).
 * [allowed] sind die Nutzlastfelder, die in Eingang und Fakten gelangen: alles andere (auch versehentlich mitgesendete Namen oder Kontaktdaten) wird verworfen.
 */
internal enum class Source(val api: FactSource, val contiguous: Boolean, val events: Map<String, Set<String>>) {
    ATTEMPT(FactSource.ATTEMPT, true, mapOf("booking.attempt_recorded" to setOf("attempt_id", "service_id", "outcome", "primary_reason", "time_mode", "requested_at", "passengers", "wheelchair", "coarse_origin", "coarse_destination", "channel", "secondary_reasons", "is_test"))),
    OFFER(FactSource.OFFER, true, mapOf("booking.offer_created" to setOf("offer_id", "attempt_id", "service_id", "expires_at", "pickup_earliest", "pickup_latest", "gross_minor", "currency"))),
    BOOKING(FactSource.BOOKING, false, mapOf(
        "booking.confirmed" to setOf("booking_id", "service_id", "pickup_earliest", "pickup_latest", "gross_minor", "currency"),
        "booking.cancelled" to setOf("booking_id", "reason", "reason_code", "cause_class", "initiator"),
    )),
    DRIVER_EVENT(FactSource.DRIVER_EVENT, true, mapOf("driver.event_recorded" to setOf("event_id", "shift_id", "vehicle_id", "event_type", "outcome", "route_stop_id", "booking_id", "reason_code", "effective_at",
        "offline_recorded", "no_show_wait_seconds", "no_show_grace_min"))),
    VEHICLE_STATE(FactSource.VEHICLE_STATE, true, mapOf(
        "fleet.vehicle_blocked" to setOf("vehicle_id", "block_id", "safety_revision"),
        "fleet.vehicle_block_lifted" to setOf("vehicle_id", "block_id", "safety_revision"),
        "fleet.release_issued" to setOf("release_id", "vehicle_id", "safety_revision"),
        "fleet.release_revoked" to setOf("release_id", "vehicle_id"),
    ));

    companion object {
        private val byType: Map<String, Source> = entries.flatMap { s -> s.events.keys.map { it to s } }.toMap()
        fun of(eventType: String): Source? = byType[eventType]
    }
}
