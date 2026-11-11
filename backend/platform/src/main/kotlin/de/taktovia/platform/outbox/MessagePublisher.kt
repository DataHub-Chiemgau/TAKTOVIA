package de.taktovia.platform.outbox

import java.util.concurrent.CopyOnWriteArrayList

/** Veröffentlicht eine Nachricht beim Broker und kehrt erst nach dessen Bestätigung zurück (Publisher-Confirm); wirft bei Fehler oder Nack. */
interface MessagePublisher {
    fun publish(routingKey: String, messageId: String, body: String)
}

/** Nachrichten im Speicher: lokale Läufe und Tests ohne Broker. Fehler lassen sich einschleusen. */
class InMemoryMessagePublisher : MessagePublisher {
    data class Message(val routingKey: String, val messageId: String, val body: String)
    val messages = CopyOnWriteArrayList<Message>()
    @Volatile var failure: (() -> Throwable?)? = null

    override fun publish(routingKey: String, messageId: String, body: String) {
        failure?.invoke()?.let { throw it }
        messages.add(Message(routingKey, messageId, body))
    }
}
