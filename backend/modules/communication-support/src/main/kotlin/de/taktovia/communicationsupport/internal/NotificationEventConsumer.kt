package de.taktovia.communicationsupport.internal

import de.taktovia.communicationsupport.api.DeliveryPurposePolicy
import de.taktovia.communicationsupport.api.NotificationEventConsumer
import de.taktovia.communicationsupport.api.NotificationPurpose
import de.taktovia.communicationsupport.api.TransactionalNotificationTrigger
import de.taktovia.communicationsupport.api.TriggerSourceEvents
import de.taktovia.platform.tenant.TenantId
import org.slf4j.LoggerFactory
import de.taktovia.communicationsupport.api.NotificationService
import de.taktovia.communicationsupport.api.NotificationTrigger
import de.taktovia.platform.outbox.AggregateSnapshot
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.outbox.InboxHandler
import de.taktovia.platform.outbox.InboxOutcome
import de.taktovia.platform.outbox.InboxProcessor
import org.springframework.jdbc.core.JdbcOperations

/**
 * Ereignisverbraucher (WP-M2-32): je Ereignis über die Inbox (`consumer = communication-support`) genau einmal. Die Aufträge entstehen in der Inbox-Transaktion
 * des Empfängermandanten; der Unique-Schlüssel (Ereignis, Empfänger, Kanal) sichert die Einmaligkeit zusätzlich ab. Versendet wird nichts: das tut der Worker.
 */
internal class InboxNotificationConsumer(
    private val processor: InboxProcessor, private val notifications: NotificationService, private val triggers: List<NotificationTrigger>,
    private val transactional: List<TransactionalNotificationTrigger> = emptyList(), private val purposes: DeliveryPurposePolicy = DeliveryPurposePolicy.OPERATIONAL_ONLY,
) : NotificationEventConsumer {
    private val log = LoggerFactory.getLogger("taktovia.notification.trigger")
    private val handler = object : InboxHandler {
        override fun handle(jdbc: JdbcOperations, event: EventEnvelope) {
            triggers.flatMap { it.requests(event) }.forEach { notifications.send(jdbc, it) }
            val tenant = TenantId(event.recipientTenantId ?: event.ownerTenantId)
            transactional.flatMap { it.requests(jdbc, event) }.forEach { t ->
                // Werbung läuft nie über diesen Weg; jede Betriebsmitteilung braucht einen zulässigen Zweck, unabhängig von einer Marketingeinwilligung
                if (t.purpose != NotificationPurpose.OPERATIONAL || !purposes.allowed(tenant, t.request.recipientRef, t.purpose)) {
                    log.warn("Auslöser verworfen: Zweck nicht zulässig (event={}, purpose={})", event.eventId, t.purpose); return@forEach
                }
                notifications.send(jdbc, t.request)
            }
        }
        /** Der Verbraucher hält keinen Aggregatzustand: ein Snapshot ändert nichts (spätere Ereignisse lösen ihre Nachrichten selbst aus). */
        override fun applySnapshot(jdbc: JdbcOperations, snapshot: AggregateSnapshot) = Unit
    }

    /**
     * Die Standardauslöser haben je Quellereignis einen eigenen Konsumentennamen (`communication-support.<ereignis>`): die Aggregatrevision der Inbox gilt dann je Ereignistyp.
     * Sonst würde eine Buchung, deren Ereignisse die Revisionen 1 (bestätigt) und 3 (storniert) tragen, beim Storno als „Lücke“ gewertet und die Nachricht ginge verloren.
     */
    override fun consume(json: String): InboxOutcome {
        val type = runCatching { EventEnvelope.fromJson(json).eventType }.getOrNull()
        val consumer = if (type != null && transactional.isNotEmpty() && type in TriggerSourceEvents.ALL) "${NotificationEventConsumer.CONSUMER}.$type" else NotificationEventConsumer.CONSUMER
        return processor.process(consumer, json, handler)
    }
}
