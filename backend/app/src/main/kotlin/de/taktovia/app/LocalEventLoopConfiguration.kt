package de.taktovia.app

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.analytics.api.ConsumeOutcome
import de.taktovia.analytics.api.FactBase
import de.taktovia.analytics.api.TimeFacts
import de.taktovia.fleetsafety.api.WorkflowEventConsumer
import de.taktovia.platform.alert.OperatorAlertPort
import de.taktovia.platform.outbox.InboxOutcome
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.outbox.MessagePublisher
import de.taktovia.platform.outbox.OutboxRelay
import de.taktovia.platform.outbox.TenantSource
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ereigniszustellung im Prozess **ohne Messaging-Broker** (WP-M5-23): Outbox-Relay, der jedes Ereignis über [LoopbackPublisher] unmittelbar an die Faktenbasis der Kennzahlen
 * (`analytics`, Inbox mit Dedupe) übergibt. Nur für Umgebungen ohne Broker (lokale Läufe, Akzeptanztests); in Staging und Produktion stellt RabbitMQ zu. Aktiv nur mit
 * `taktovia.events.local-loopback=true` **und** einer Mandantenliste (`taktovia.events.loopback-tenant-file`, sonst die [TenantSource]-Bean aus `taktovia.workers.tenant-file`). Abgelehnte Ereignisse (Schema, Mandant, Grund) werden wie ein Zustellfehler behandelt:
 * Wiederholung nach der Staffel, nach `maxAttempts` Dead-Letter mit Betreiberwarnung. Zeitgeber: `taktovia.events.loopback-poll-seconds` (1).
 */
@Configuration
@ConditionalOnProperty("taktovia.events.local-loopback", havingValue = "true")
class LocalEventLoopConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    class LoopbackPublisher(private val facts: FactBase, private val times: TimeFacts, private val workflow: WorkflowEventConsumer? = null) : MessagePublisher {
        override fun publish(routingKey: String, messageId: String, body: String) {
            val recipient = routingKey.split('.').getOrNull(1)?.let { runCatching { TenantId.parse(it) }.getOrNull() } ?: throw IllegalStateException("Routing-Key ohne Mandanten")
            // Faktenbasis (Versuche, Angebote, Buchungen, Fahrerereignisse) und Zeitfakten (zugesagte und tatsächliche Zeiten für die Pünktlichkeit); jeder Verbraucher dedupliziert selbst
            for ((name, o) in listOf("Faktenbasis" to facts.consume(recipient, body), "Zeitfakten" to times.consume(recipient, body))) when (o) {
                ConsumeOutcome.PROCESSED, ConsumeOutcome.DUPLICATE, ConsumeOutcome.UNSUPPORTED_EVENT -> Unit
                else -> throw IllegalStateException("$name lehnt das Ereignis ab: $o")
            }
            // Störungsablauf (Fahrzeugsperre, Störungsmeldung -> Incident und Ersatzfahrzeugsuche): Inbox-Verbraucher des Moduls `fleet-safety`, Dedupe je Ereignis
            workflow?.consume(body)?.let { o -> if (o !in setOf(InboxOutcome.PROCESSED, InboxOutcome.DUPLICATE, InboxOutcome.STALE)) throw IllegalStateException("Störungsablauf lehnt das Ereignis ab: $o") }
        }
    }

    @Bean
    fun loopbackRelay(tx: TenantTransactions, tenants: org.springframework.beans.factory.ObjectProvider<TenantSource>, alerts: OperatorAlertPort, security: SecurityEventPort, workflow: org.springframework.beans.factory.ObjectProvider<WorkflowEventConsumer>, env: Environment): OutboxRelay {
        // eigene Mandantenliste für die Zustellung (`taktovia.events.loopback-tenant-file`): lässt sich unabhängig von den übrigen Zeitgebern anhalten (Fault-Injection „Messaging nicht erreichbar“)
        val source = env.getProperty("taktovia.events.loopback-tenant-file")?.takeIf { it.isNotBlank() }?.let { tenantFileSource(it) }
            ?: tenants.getIfAvailable() ?: error("taktovia.events.local-loopback benötigt eine Mandantenliste des Betriebs (taktovia.workers.tenant-file)")
        val relay = OutboxRelay(tx, LoopbackPublisher(AnalyticsApi.createFacts(tx), AnalyticsApi.createTimeFacts(tx), workflow.getIfAvailable()), source, alerts, security)
        val poll = env.getProperty("taktovia.events.loopback-poll-seconds", Long::class.java, 1)
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "event-loopback").also { it.isDaemon = true } }
            .scheduleWithFixedDelay({ runCatching { relay.runOnce() }.onFailure { log.warn("Ereignisschleife: Lauf fehlgeschlagen ({})", it.javaClass.simpleName) } }, poll, poll, TimeUnit.SECONDS)
        return relay
    }
}
