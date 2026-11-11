package de.taktovia.communicationsupport.internal

import de.taktovia.communicationsupport.api.NotificationEventConsumer
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
) : NotificationEventConsumer {
    private val handler = object : InboxHandler {
        override fun handle(jdbc: JdbcOperations, event: EventEnvelope) {
            triggers.flatMap { it.requests(event) }.forEach { notifications.send(jdbc, it) }
        }
        /** Der Verbraucher hält keinen Aggregatzustand: ein Snapshot ändert nichts (spätere Ereignisse lösen ihre Nachrichten selbst aus). */
        override fun applySnapshot(jdbc: JdbcOperations, snapshot: AggregateSnapshot) = Unit
    }

    override fun consume(json: String): InboxOutcome = processor.process(NotificationEventConsumer.CONSUMER, json, handler)
}
