package de.taktovia.communicationsupport.api

import de.taktovia.communicationsupport.internal.CommunicationServicesFactory
import de.taktovia.platform.alert.OperatorAlertPort
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.InboxProcessor
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantTransactions
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock

/**
 * Öffentliche Ports des Moduls `communication-support`: asynchroner Nachrichtenversand (WP-M2-32) und Nachrichtenvorlagen (WP-M2-33).
 * Der Versand-Worker und der Ereignisverbraucher brauchen die Außenwelt (Provider, Empfängerverzeichnis, Inbox) und entstehen daher über Fabrikmethoden.
 */
interface CommunicationsupportApi {
    val notifications: NotificationService
    val templates: TemplateService

    /** Versand-Worker; [metrics] ohne Zugriff auf Inhalt oder Kontakt (Labels: Kanal und Ausgang). */
    fun dispatcher(gateway: DeliveryGateway, directory: RecipientDirectory, alerts: OperatorAlertPort, config: DispatchConfig = DispatchConfig(), metrics: MeterRegistry? = null): NotificationDispatcher

    /** Ereignisverbraucher über die Inbox: `consumer` = [NotificationEventConsumer.CONSUMER]. */
    fun eventConsumer(processor: InboxProcessor, triggers: List<NotificationTrigger>): NotificationEventConsumer

    companion object {
        fun create(tx: TenantTransactions, ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, clock: Clock = Clock.systemUTC()): CommunicationsupportApi =
            CommunicationServicesFactory.create(tx, ctxTx, audit, outbox, clock)
    }
}
