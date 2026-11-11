package de.taktovia.platform.outbox

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DeliverCallback
import com.rabbitmq.client.MessageProperties

/**
 * RabbitMQ-Anbindung des Relays (WP-M1-14). **NICHT AUSGEFÜHRT** in dieser Umgebung (kein Docker/Broker): kompiliert, aber ohne laufenden
 * Broker ungetestet; Nachweis gegen den Broker folgt mit der lokalen Compose-Umgebung. Der Relay-Kern ist über [MessagePublisher] mit einem
 * Speicher-Broker vollständig getestet.
 *
 * Topologie (Detailvertrag docs/umsetzungsplan/vertraege/event-topologie.md): Topic-Exchange `taktovia.events` (dauerhaft), Routing-Key
 * `events.<empfänger_tenant_id>.<ereignistyp>`; je Consumer eine Quorum Queue `taktovia.events.<consumer>` mit Dead-Letter-Exchange
 * `taktovia.events.dlx` und Zustelllimit; Veröffentlichung dauerhaft (persistent) mit Publisher-Confirm.
 */
object RabbitMqTopology {
    const val EXCHANGE = "taktovia.events"
    const val DEAD_LETTER_EXCHANGE = "taktovia.events.dlx"
    const val DELIVERY_LIMIT = 10

    fun queue(consumer: String) = "taktovia.events.$consumer"

    /** Legt Exchanges, die Quorum Queue des Consumers und Bindungen an (idempotent). Bindungsmuster z. B. `events.*.identity.#`. */
    fun declare(ch: Channel, consumer: String, bindings: List<String>) {
        ch.exchangeDeclare(EXCHANGE, "topic", true)
        ch.exchangeDeclare(DEAD_LETTER_EXCHANGE, "topic", true)
        val args = mapOf<String, Any>("x-queue-type" to "quorum", "x-dead-letter-exchange" to DEAD_LETTER_EXCHANGE, "x-delivery-limit" to DELIVERY_LIMIT)
        ch.queueDeclare(queue(consumer), true, false, false, args)
        ch.queueDeclare("${queue(consumer)}.dead", true, false, false, mapOf<String, Any>("x-queue-type" to "quorum"))
        ch.queueBind("${queue(consumer)}.dead", DEAD_LETTER_EXCHANGE, "events.#")
        bindings.forEach { ch.queueBind(queue(consumer), EXCHANGE, it) }
    }

    /** Verbindungsfabrik: TLS 1.2/1.3 mit Hostnamenprüfung (LH-6.18-SEC-002); Zugang kommt aus der Secret-Verwaltung, nie aus dem Code. */
    fun factory(host: String, port: Int, user: String, password: String): ConnectionFactory = ConnectionFactory().also {
        it.host = host; it.port = port; it.username = user; it.password = password
        it.useSslProtocol("TLSv1.3"); it.enableHostnameVerification()
        it.isAutomaticRecoveryEnabled = true
    }
}

class RabbitMqPublisher(private val connection: Connection) : MessagePublisher {
    private val channel: Channel = connection.createChannel().also { it.confirmSelect() }

    @Synchronized
    override fun publish(routingKey: String, messageId: String, body: String) {
        val props: AMQP.BasicProperties = MessageProperties.PERSISTENT_TEXT_PLAIN.builder().messageId(messageId).contentType("application/json").build()
        channel.basicPublish(RabbitMqTopology.EXCHANGE, routingKey, true, props, body.toByteArray())
        channel.waitForConfirmsOrDie(10_000)
    }
}

/** Verbindet eine Quorum Queue mit dem [InboxProcessor]: Ack bei verarbeitet/Duplikat/veraltet, Nack ohne Requeue (-> Dead-Letter) bei Ablehnung. */
class RabbitMqInboxConsumer(private val channel: Channel, private val consumer: String, private val processor: InboxProcessor, private val handler: InboxHandler) {
    fun start() {
        channel.basicQos(20)
        channel.basicConsume(RabbitMqTopology.queue(consumer), false, DeliverCallback { _, d ->
            val tag = d.envelope.deliveryTag
            try {
                when (processor.process(consumer, String(d.body), handler)) {
                    InboxOutcome.PROCESSED, InboxOutcome.DUPLICATE, InboxOutcome.STALE, InboxOutcome.GAP_SNAPSHOT -> channel.basicAck(tag, false)
                    else -> channel.basicNack(tag, false, false)
                }
            } catch (e: Exception) { channel.basicNack(tag, false, true) }   // vorübergehender Fehler: erneut zustellen
        }, com.rabbitmq.client.CancelCallback { })
    }
}
