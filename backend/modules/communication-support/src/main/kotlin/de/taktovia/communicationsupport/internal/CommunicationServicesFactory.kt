package de.taktovia.communicationsupport.internal

import de.taktovia.communicationsupport.api.BookingFactsSource
import de.taktovia.communicationsupport.api.CommunicationsupportApi
import de.taktovia.communicationsupport.api.DeliveryPurposePolicy
import de.taktovia.communicationsupport.api.TriggerSettings
import de.taktovia.communicationsupport.api.DeliveryGateway
import de.taktovia.communicationsupport.api.DispatchConfig
import de.taktovia.communicationsupport.api.NotificationDispatcher
import de.taktovia.communicationsupport.api.NotificationEventConsumer
import de.taktovia.communicationsupport.api.NotificationService
import de.taktovia.communicationsupport.api.NotificationTrigger
import de.taktovia.communicationsupport.api.RecipientDirectory
import de.taktovia.communicationsupport.api.TemplateService
import de.taktovia.platform.alert.OperatorAlertPort
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.InboxProcessor
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantTransactions
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock

internal object CommunicationServicesFactory {
    fun create(tx: TenantTransactions, ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, clock: Clock): CommunicationsupportApi {
        val notificationService = JdbcNotifications(ctxTx)
        val templateService = JdbcTemplates(ctxTx, audit, clock)
        return object : CommunicationsupportApi {
            override val notifications: NotificationService = notificationService
            override val templates: TemplateService = templateService
            override fun dispatcher(gateway: DeliveryGateway, directory: RecipientDirectory, alerts: OperatorAlertPort, config: DispatchConfig, metrics: MeterRegistry?): NotificationDispatcher =
                NotificationWorker(tx, templateService, gateway, directory, alerts, outbox, config, clock, metrics)
            override fun eventConsumer(processor: InboxProcessor, triggers: List<NotificationTrigger>): NotificationEventConsumer =
                InboxNotificationConsumer(processor, notificationService, triggers)
            override fun transactionalConsumer(processor: InboxProcessor, facts: BookingFactsSource, directory: RecipientDirectory, purposes: DeliveryPurposePolicy, settings: TriggerSettings): NotificationEventConsumer =
                InboxNotificationConsumer(processor, notificationService, emptyList(), StandardTriggers(facts, directory, settings).all(), purposes)
        }
    }
}
