package de.taktovia.adapters.notification.api

import de.taktovia.communicationsupport.api.FailureReason
import de.taktovia.communicationsupport.api.NotificationChannel
import de.taktovia.communicationsupport.api.OutboundMessage
import de.taktovia.communicationsupport.api.ProviderResult
import de.taktovia.communicationsupport.api.ProviderStatus
import de.taktovia.communicationsupport.api.ProviderTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Simulator-Provider für Push, SMS und E-Mail (WP-M2-32; Ersatz für den nicht festgelegten realen Provider, D-05). Verhalten wie ein Provider mit Idempotenzreferenz:
 * dieselbe Nachrichten-ID ergibt höchstens **eine** Zustellung ([deliveries]), jede weitere Annahme liefert dieselbe Providerreferenz. Prüft den Kontakt je Kanal
 * (E.164, E-Mail-Adresse, Geräte-Token) und lehnt ungültige dauerhaft ab. Fehler lassen sich steuern: [failNext], [timeoutNext] (mit oder ohne späte Zustellung), [outage].
 * Aufgezeichnet werden Zustellungen (für Tests); der Simulator protokolliert weder Kontakt noch Inhalt.
 */
class SimulatorProvider : NotificationAdapter {
    /** Eine Zustellung beim Endnutzer. */
    class Delivery(val messageId: UUID, val channel: NotificationChannel, val to: String, val subject: String?, val body: String, val providerRef: String) {
        override fun toString() = "Delivery(messageId=$messageId, channel=$channel)"
    }

    enum class Fault { TEMPORARY_ERROR, PERMANENT_ERROR, TIMEOUT_AFTER_DELIVERY, TIMEOUT_WITHOUT_DELIVERY }

    private val delivered = ConcurrentHashMap<UUID, Delivery>()
    private val faults = ConcurrentLinkedQueue<Fault>()
    private val calls = AtomicInteger()
    private val log = CopyOnWriteArrayList<Pair<UUID, NotificationChannel>>()

    /** Während eines Ausfalls antwortet der Simulator auf `send` nie (Timeout ohne Zustellung) und auf `status` mit „keine Auskunft“. */
    @Volatile var outage: Boolean = false

    /** Die nächsten [count] Aufrufe von `send` verhalten sich wie [fault]. */
    fun failNext(count: Int, fault: Fault) { repeat(count) { faults.add(fault) } }

    /** Die nächste Annahme läuft in ein Timeout; [deliver] = der Provider stellt trotzdem zu („später Erfolg“), die Antwort kommt nie an. */
    fun timeoutNext(deliver: Boolean) { faults.add(if (deliver) Fault.TIMEOUT_AFTER_DELIVERY else Fault.TIMEOUT_WITHOUT_DELIVERY) }

    val deliveries: List<Delivery> get() = delivered.values.toList()
    fun deliveryOf(messageId: UUID): Delivery? = delivered[messageId]
    /** Zahl der `send`-Aufrufe (auch der abgelehnten und der Wiederholungen). */
    val sendCalls: Int get() = calls.get()
    /** (Nachrichten-ID, Kanal) je Aufruf in der Reihenfolge des Eingangs, ohne Kontakt und Inhalt. */
    val callLog: List<Pair<UUID, NotificationChannel>> get() = log.toList()

    override fun send(message: OutboundMessage): ProviderResult {
        calls.incrementAndGet(); log += message.messageId to message.channel
        if (outage) throw ProviderTimeoutException("Simulator-Ausfall")
        // Providerseitige Idempotenz: eine bereits zugestellte Nachrichten-ID wird nur bestätigt, nie erneut zugestellt
        delivered[message.messageId]?.let { return ProviderResult.Accepted(it.providerRef) }
        if (!validContact(message.channel, message.to)) return ProviderResult.Rejected(true, FailureReason.INVALID_CONTACT)
        return when (faults.poll()) {
            Fault.TEMPORARY_ERROR -> ProviderResult.Rejected(false, FailureReason.PROVIDER_ERROR)
            Fault.PERMANENT_ERROR -> ProviderResult.Rejected(true, FailureReason.PROVIDER_ERROR)
            Fault.TIMEOUT_AFTER_DELIVERY -> { deliver(message); throw ProviderTimeoutException() }
            Fault.TIMEOUT_WITHOUT_DELIVERY -> throw ProviderTimeoutException()
            null -> ProviderResult.Accepted(deliver(message).providerRef)
        }
    }

    override fun status(channel: NotificationChannel, messageId: UUID): ProviderStatus {
        if (outage) return ProviderStatus.Unavailable
        return delivered[messageId]?.let { ProviderStatus.Accepted(it.providerRef) } ?: ProviderStatus.NotKnown
    }

    private fun deliver(m: OutboundMessage): Delivery {
        val fresh = Delivery(m.messageId, m.channel, m.to, m.subject, m.body, "sim-${m.messageId}")
        return delivered.putIfAbsent(m.messageId, fresh) ?: fresh
    }

    private fun validContact(channel: NotificationChannel, to: String): Boolean = when (channel) {
        NotificationChannel.SMS -> PHONE.matches(to)
        NotificationChannel.EMAIL -> EMAIL.matches(to)
        NotificationChannel.PUSH -> to.length in 8..4096 && to.none { it.isWhitespace() }
    }

    private companion object {
        val PHONE = Regex("^\\+[1-9][0-9]{6,14}$")
        val EMAIL = Regex("^[^@\\s]{1,64}@[^@\\s]{1,189}\\.[^@\\s.]{2,}$")
    }
}
