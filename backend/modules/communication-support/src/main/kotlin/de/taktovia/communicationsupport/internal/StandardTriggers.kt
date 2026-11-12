package de.taktovia.communicationsupport.internal

import de.taktovia.communicationsupport.api.BookingFacts
import de.taktovia.communicationsupport.api.BookingFactsSource
import de.taktovia.communicationsupport.api.ContactLookup
import de.taktovia.communicationsupport.api.NotificationChannel
import de.taktovia.communicationsupport.api.NotificationEventTypes
import de.taktovia.communicationsupport.api.NotificationRequest
import de.taktovia.communicationsupport.api.RecipientDirectory
import de.taktovia.communicationsupport.api.TransactionalNotificationTrigger
import de.taktovia.communicationsupport.api.TriggerSettings
import de.taktovia.communicationsupport.api.TriggerSourceEvents
import de.taktovia.communicationsupport.api.TriggeredNotification
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.tenant.TenantId
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Standardauslöser der fünf Szenarien (WP-M2-34). Je Quellereignis und betroffenem Fahrgast höchstens ein Auftrag: der Ereignisschlüssel des Auftrags ist bei Ereignissen mit mehreren
 * Buchungen aus Ereignis und Buchung abgeleitet (damit zwei Buchungen desselben Fahrgasts im selben Ereignis nicht kollidieren), sonst die Ereignis-ID. Empfänger ist immer der Fahrgast
 * **der betroffenen Buchung**; Mitfahrende desselben Umlaufs erhalten nur bei eigener Betroffenheit eine Nachricht. Protokolliert werden nur IDs und technische Gründe.
 */
internal class StandardTriggers(
    private val facts: BookingFactsSource, private val directory: RecipientDirectory, private val settings: TriggerSettings,
) {
    private val log = LoggerFactory.getLogger("taktovia.notification.trigger")

    fun all(): List<TransactionalNotificationTrigger> = listOf(
        TransactionalNotificationTrigger { j, e -> if (e.eventType == TriggerSourceEvents.BOOKING_CONFIRMED) confirmed(j, e) else emptyList() },
        TransactionalNotificationTrigger { j, e -> if (e.eventType == TriggerSourceEvents.BOOKING_CANCELLED) cancelled(j, e) else emptyList() },
        TransactionalNotificationTrigger { j, e -> if (e.eventType == TriggerSourceEvents.RUN_REVISED) etaFromPlan(j, e) else emptyList() },
        TransactionalNotificationTrigger { j, e -> if (e.eventType == TriggerSourceEvents.PROMISE_CHANGED) etaFromPromise(j, e) else emptyList() },
        TransactionalNotificationTrigger { j, e -> if (e.eventType == TriggerSourceEvents.VEHICLE_ARRIVED) arrived(j, e) else emptyList() },
    )

    /** Bestätigung: nur für eine aktive Buchung (eine bereits stornierte erhält keine Bestätigung). */
    private fun confirmed(j: JdbcOperations, e: EventEnvelope): List<TriggeredNotification> {
        val id = uuid(e.payload, "booking_id") ?: return skip(e, "payload_incomplete")
        val b = facts.booking(j, id) ?: return skip(e, "booking_unknown")
        if (!b.active) return skip(e, "booking_not_active")
        return listOf(notification(e, e.eventId, b, NotificationEventTypes.BOOKING_CONFIRMED, eta = b.pickupEarliest))
    }

    /**
     * Storno: Fahrgast (`initiator` PASSENGER) und Betreiber (`OPERATOR`) mit verschiedenen Nachrichtentypen. Ein Systemstorno (kein Fahrgast, kein Betreiber: Nichterscheinen,
     * Zahlungsausfall) gehört nicht zu den fünf Szenarien und löst keine Nachricht aus (Lücke der Fachfrage OP 28, im Vertrag vermerkt).
     */
    private fun cancelled(j: JdbcOperations, e: EventEnvelope): List<TriggeredNotification> {
        val id = uuid(e.payload, "booking_id") ?: return skip(e, "payload_incomplete")
        val type = when (e.payload.get("initiator")?.asString()) {
            "PASSENGER" -> NotificationEventTypes.BOOKING_CANCELLED_BY_PASSENGER
            "OPERATOR" -> NotificationEventTypes.BOOKING_CANCELLED_BY_OPERATOR
            else -> return skip(e, "system_cancellation_not_a_scenario")
        }
        val b = facts.booking(j, id) ?: return skip(e, "booking_unknown")
        return listOf(notification(e, e.eventId, b, type))
    }

    /** Relevante ETA-Änderung aus der Planrevision: je Buchung mit verschobener Abholzeit um mindestens die Schwelle; nur für aktive Buchungen (nie nach einem Storno). */
    private fun etaFromPlan(j: JdbcOperations, e: EventEnvelope): List<TriggeredNotification> {
        val changes = e.payload.get("booking_changes")?.takeIf { it.isArray } ?: return emptyList()
        return changes.values().toList().mapNotNull { c ->
            if (c.get("kind")?.asString() != "TIME_CHANGED") return@mapNotNull null
            val before = instant(c, "pickup_before"); val after = instant(c, "pickup_after")
            if (before == null || after == null || Duration.between(before, after).abs() < settings.etaRelevanceThreshold) return@mapNotNull null
            val id = uuid(c, "booking_id") ?: return@mapNotNull null
            val b = facts.booking(j, id) ?: return@mapNotNull null
            if (!b.active) { log.info("Planänderung ohne Nachricht: Buchung nicht aktiv (event={}, booking={})", e.eventId, id); return@mapNotNull null }
            notification(e, derived(e.eventId, id), b, NotificationEventTypes.ETA_CHANGED, eta = after)
        }
    }

    /**
     * Informationsauftrag aus der Änderung der Zusage (`promise.changed`, WP-M1-27): ändert sich das Abhol- oder Ankunftsfenster, bekommt der Fahrgast „ETA geändert“. Andere
     * fahrgastrelevante Felder (Preis, Stornobedingung) haben in P1 keine Vorlage und lösen keine Nachricht aus. Das Aggregat des Ereignisses ist die Buchung.
     */
    private fun etaFromPromise(j: JdbcOperations, e: EventEnvelope): List<TriggeredNotification> {
        val fields = e.payload.get("information_order")?.get("changed_fields")?.values()?.toList()?.map { it.asString() }.orEmpty()
        if (fields.none { it in WINDOW_FIELDS }) return skip(e, "no_window_field_changed")
        val b = facts.booking(j, e.aggregateId) ?: return skip(e, "booking_unknown")
        if (!b.active) return skip(e, "booking_not_active")
        return listOf(notification(e, e.eventId, b, NotificationEventTypes.ETA_CHANGED, eta = b.pickupEarliest))
    }

    /** Ankunft am Treffpunkt: der Fahrgast jeder Buchung, die an diesem Stopp einsteigt (`booking_ids`); nur aktive Buchungen. */
    private fun arrived(j: JdbcOperations, e: EventEnvelope): List<TriggeredNotification> {
        val ids = e.payload.get("booking_ids")?.takeIf { it.isArray }?.values()?.toList()?.mapNotNull { n -> n.asString()?.let { runCatching { UUID.fromString(it) }.getOrNull() } }?.distinct().orEmpty()
        return ids.mapNotNull { id ->
            val b = facts.booking(j, id) ?: return@mapNotNull null
            if (!b.active) null else notification(e, derived(e.eventId, id), b, NotificationEventTypes.VEHICLE_ARRIVED)
        }
    }

    private fun notification(e: EventEnvelope, eventKey: UUID, b: BookingFacts, type: String, eta: Instant? = null): TriggeredNotification {
        val tenant = TenantId(e.recipientTenantId ?: e.ownerTenantId)
        val zone = runCatching { ZoneId.of(b.timezone) }.getOrDefault(ZoneId.of("Europe/Berlin"))
        val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        val params = linkedMapOf(
            "booking_reference" to b.bookingNumber, "booking_number" to b.bookingNumber.substringAfterLast('-').trimStart('0').ifEmpty { "0" },
            "meeting_point" to (b.meetingPoint?.takeIf { it.isNotBlank() } ?: "siehe Buchung"),
            "time_window" to "${hm.format(b.pickupEarliest)}-${hm.format(b.pickupLatest)}", "eta" to hm.format(eta ?: b.pickupEarliest),
        )
        return TriggeredNotification(NotificationRequest(eventKey, b.passengerRef, channelFor(tenant, b.passengerRef), type, b.serviceId, params))
    }

    /** Erster Kanal der Reihenfolge mit verifiziertem Kontakt; ohne nutzbaren Kanal der erste (der Worker dokumentiert dann `NO_VERIFIED_CONTACT`). */
    private fun channelFor(tenant: TenantId, recipient: UUID): NotificationChannel =
        settings.channelPreference.firstOrNull { directory.contact(tenant, recipient, it) is ContactLookup.Found } ?: settings.channelPreference.first()

    private fun skip(e: EventEnvelope, reason: String): List<TriggeredNotification> {
        log.info("Ereignis ohne Nachricht (event={}, type={}, reason={})", e.eventId, e.eventType, reason)
        return emptyList()
    }

    private fun uuid(n: JsonNode, f: String): UUID? = n.get(f)?.takeIf { it.isString }?.let { runCatching { UUID.fromString(it.asString()) }.getOrNull() }
    private fun instant(n: JsonNode, f: String): Instant? = n.get(f)?.takeIf { it.isString }?.let { runCatching { Instant.parse(it.asString()) }.getOrNull() }
    private fun derived(event: UUID, booking: UUID): UUID = UUID.nameUUIDFromBytes("$event|$booking".toByteArray())

    private companion object { val WINDOW_FIELDS = setOf("pickup_earliest", "pickup_latest", "arrival_earliest", "arrival_latest") }
}
