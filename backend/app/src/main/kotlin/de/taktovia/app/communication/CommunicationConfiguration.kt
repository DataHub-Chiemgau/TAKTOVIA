package de.taktovia.app.communication

import de.taktovia.adapters.notification.api.NotificationAdapter
import de.taktovia.adapters.notification.api.SimulatorProvider
import de.taktovia.bookingdispatch.api.BookingNotificationFactsPort
import de.taktovia.communicationsupport.api.BookingFacts
import de.taktovia.communicationsupport.api.BookingFactsSource
import de.taktovia.communicationsupport.api.CommunicationsupportApi
import de.taktovia.communicationsupport.api.DeliveryPurposePolicy
import de.taktovia.communicationsupport.api.NotificationEventConsumer
import de.taktovia.communicationsupport.api.NotificationPurpose
import de.taktovia.communicationsupport.api.TriggerSettings
import de.taktovia.identity.api.ConsentPurpose
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.outbox.InboxProcessor
import de.taktovia.communicationsupport.api.ContactLookup
import de.taktovia.communicationsupport.api.DispatchConfig
import de.taktovia.communicationsupport.api.NotificationChannel
import de.taktovia.communicationsupport.api.NotificationDispatcher
import de.taktovia.communicationsupport.api.RecipientDirectory
import de.taktovia.identity.api.ContactChannel
import de.taktovia.identity.api.PassengerApi
import de.taktovia.platform.alert.OperatorAlertPort
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.TenantSource
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verdrahtung des Moduls `communication-support` (WP-M2-32, -33). Der Provider ist offen (D-05, Betrieb/Hosting bestimmt Push, SMS, E-Mail): ohne konfigurierten Provider
 * gibt es keinen Versand-Worker, Aufträge bleiben `QUEUED` und sichtbar (kein stiller Verlust). Mit `taktovia.notifications.provider=simulator` (Entwicklung, Tests)
 * läuft der Worker gegen den [SimulatorProvider]; sein Zeitgeber braucht zusätzlich `taktovia.notifications.worker-enabled=true` und eine [TenantSource]-Bean des Betriebs
 * (OP-06/OP-08). Planungswerte (OP 28): `max-attempts` 6, `lease-seconds` 60, `poll-seconds` 2.
 */
@Configuration
class CommunicationConfiguration {
    @Bean fun communicationsupportApi(tx: TenantTransactions, ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox): CommunicationsupportApi =
        CommunicationsupportApi.create(tx, ctxTx, audit, outbox)

    /** Buchungsfakten für die Auslöser (WP-M2-34): Lesesicht von `booking-dispatch`, unter Lesesperre der Buchungszeile. */
    @Bean fun bookingNotificationFacts(): BookingNotificationFactsPort = BookingNotificationFactsPort.create()

    /**
     * Ereignisverbraucher mit den Auslösern der fünf Szenarien (WP-M2-34): Bestätigung, relevante ETA-Änderung, Ankunft, Storno, Ausfall. Der Broker-Verbraucher (RabbitMQ, nicht ausgeführt)
     * ruft [NotificationEventConsumer.consume] mit der Ereignishülle auf. `taktovia.notifications.eta-threshold-minutes` (Vorläuferwert 5, OP 30) ist die Relevanzschwelle der ETA-Änderung,
     * `taktovia.notifications.channel-preference` (Standard `PUSH,SMS,EMAIL`, OP 28) die Reihenfolge der Kanalwahl. Lazy: ohne Aufrufer entsteht nichts.
     */
    @org.springframework.context.annotation.Lazy @Bean fun notificationEventConsumer(
        api: CommunicationsupportApi, tx: TenantTransactions, security: SecurityEventPort, facts: BookingNotificationFactsPort, directory: RecipientDirectory, passengers: ObjectProvider<PassengerApi>, env: Environment,
    ): NotificationEventConsumer {
        val settings = TriggerSettings(
            etaRelevanceThreshold = Duration.ofMinutes(env.getProperty("taktovia.notifications.eta-threshold-minutes", Long::class.java, 5)),
            channelPreference = env.getProperty("taktovia.notifications.channel-preference", "PUSH,SMS,EMAIL").split(",").map { NotificationChannel.valueOf(it.trim().uppercase()) },
        )
        val purposes = DeliveryPurposePolicy { tenant, recipient, purpose ->
            when (purpose) {
                NotificationPurpose.OPERATIONAL -> passengers.getObject().delivery.isDeliveryAllowed(tenant, recipient, ConsentPurpose.OPERATIONAL_SAFETY)
                NotificationPurpose.MARKETING -> false   // Werbung läuft nie über den Auslöserweg
            }
        }
        val source = BookingFactsSource { jdbc, id ->
            facts.facts(jdbc, id)?.let { BookingFacts(it.bookingId, it.bookingNumber, it.passengerRef, it.serviceId, it.state, it.meetingPoint, it.pickupEarliest, it.pickupLatest, it.timezone) }
        }
        return api.transactionalConsumer(InboxProcessor(tx, security, OwnerSnapshotFetcher(tx)), source, directory, purposes, settings)
    }

    /**
     * Empfängerverzeichnis über das Fahrgastkonto der Identität: verifizierter Kontakt (E-Mail, Telefon für SMS) und Sprache. Für Push gibt es in P1 noch kein Geräteregister
     * (Web-Push-Fähigkeit der PWA ist offen, OP 28): der Kanal liefert keinen Kontakt und Aufträge dafür enden als unterdrückt, dokumentiert.
     */
    @Bean fun recipientDirectory(passengers: ObjectProvider<PassengerApi>): RecipientDirectory = object : RecipientDirectory {
        override fun contact(tenant: TenantId, recipientRef: UUID, channel: NotificationChannel): ContactLookup {
            val ch = when (channel) { NotificationChannel.EMAIL -> ContactChannel.EMAIL; NotificationChannel.SMS -> ContactChannel.PHONE; NotificationChannel.PUSH -> return ContactLookup.None }
            return passengers.getObject().directory.verifiedContact(tenant, recipientRef, ch)?.let { ContactLookup.Found(it) } ?: ContactLookup.None
        }
        override fun language(tenant: TenantId, recipientRef: UUID): String? = passengers.getObject().directory.locale(tenant, recipientRef)
    }
}

/** Simulator-Provider und Versand-Worker (nur mit `taktovia.notifications.provider=simulator`). */
@Configuration
@ConditionalOnProperty("taktovia.notifications.provider", havingValue = "simulator")
class SimulatedNotificationConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean fun simulatorProvider(): NotificationAdapter = SimulatorProvider().also { log.warn("Nachrichtenversand gegen den Simulator-Provider (kein produktiver Versand, D-05 offen).") }

    @Bean fun notificationDispatcher(
        api: CommunicationsupportApi, provider: NotificationAdapter, directory: RecipientDirectory, alerts: OperatorAlertPort, tenants: ObjectProvider<TenantSource>,
        metrics: ObjectProvider<MeterRegistry>, env: Environment,
    ): NotificationDispatcher {
        val d = DispatchConfig()
        val config = DispatchConfig(
            maxAttempts = env.getProperty("taktovia.notifications.max-attempts", Int::class.java, d.maxAttempts),
            lease = Duration.ofSeconds(env.getProperty("taktovia.notifications.lease-seconds", Long::class.java, d.lease.seconds)),
        )
        val dispatcher = api.dispatcher(provider, directory, alerts, config, metrics.ifAvailable)
        val source = tenants.getIfAvailable()
        if (source != null && env.getProperty("taktovia.notifications.worker-enabled", Boolean::class.java, false)) {
            val poll = env.getProperty("taktovia.notifications.poll-seconds", Long::class.java, 2)
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "notification-dispatch").also { it.isDaemon = true } }.scheduleWithFixedDelay({
                runCatching { source.tenants().distinct().forEach { t -> dispatcher.runTenant(t) } }.onFailure { log.warn("Nachrichtenversand: Lauf fehlgeschlagen ({})", it.javaClass.simpleName) }
            }, poll, poll, TimeUnit.SECONDS)
        }
        return dispatcher
    }
}
