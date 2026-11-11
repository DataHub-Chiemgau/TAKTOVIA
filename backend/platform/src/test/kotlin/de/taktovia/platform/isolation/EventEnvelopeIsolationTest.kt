package de.taktovia.platform.isolation

import de.taktovia.platform.outbox.EnvelopeAddressing
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.outbox.RecipientPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * WP-M1-11: Ereignishülle mit Eigentums-`tenant_id` und expliziter Empfängeradressierung. Eine Hülle ohne Eigentümer lässt sich nicht lesen, eine ohne Empfänger oder mit
 * einem Empfänger, den die Richtlinie nicht erlaubt, ist nicht zustellbar; ein Ereignis des Eigentümers erreicht nie einen anderen Mandanten. Die Zustellung selbst (Relay, Inbox,
 * Dead-Letter und Sicherheitsereignis bei fremder Adressierung) prüfen `OutboxRelayTest` und `InboxProcessorTest` gegen die echte Datenbank.
 */
class EventEnvelopeIsolationTest {
    private val a = UUID.randomUUID(); private val b = UUID.randomUUID()
    private fun envelope(owner: UUID, recipient: UUID?) = EventEnvelope(UUID.randomUUID(), "test.thing.changed", "1.0", UUID.randomUUID(), 1, Instant.now(), Instant.now(), owner, recipient, null,
        JsonMapper.builder().build().readTree("""{"n":1}"""))

    @Test
    fun ereignis_nur_an_adressierte_empfaenger_des_eigentuemers() {
        val own = envelope(a, a)
        assertEquals(a, EnvelopeAddressing.requireAddressed(own)); assertTrue(EnvelopeAddressing.deliverableTo(own, a))
        assertFalse(EnvelopeAddressing.deliverableTo(own, b), "ein Ereignis von A erreicht keinen Empfänger von B")
        // an B adressiert, aber nur der Eigentümer ist zulässig: nicht zustellbar, auch nicht für B
        val toB = envelope(a, b)
        assertThrows(IllegalArgumentException::class.java) { EnvelopeAddressing.requireAddressed(toB) }
        assertFalse(EnvelopeAddressing.deliverableTo(toB, b)); assertFalse(EnvelopeAddressing.deliverableTo(toB, a))
        // mit ausdrücklicher Freigabe (Kooperation, M6) ist derselbe Weg zulässig: die Richtlinie entscheidet, nicht der Absender
        val coop = RecipientPolicy { owner, recipient -> owner == a && recipient == b }
        assertEquals(b, EnvelopeAddressing.requireAddressed(toB, coop)); assertTrue(EnvelopeAddressing.deliverableTo(toB, b, coop))
    }

    @Test
    fun ereignis_ohne_empfaengeradressierung_an_fremdadressat_abgewiesen() {
        assertThrows(IllegalArgumentException::class.java) { EnvelopeAddressing.requireAddressed(envelope(a, null)) }
        assertFalse(EnvelopeAddressing.deliverableTo(envelope(a, null), a))
        assertFalse(EnvelopeAddressing.deliverableTo(envelope(a, null), b))
    }

    @Test
    fun huelle_ohne_eigentuemer_tenant_id_wird_abgewiesen() {
        val json = envelope(a, a).toJson()
        val mapper = JsonMapper.builder().build()
        val without = mapper.writeValueAsString(mapper.readTree(json).also { (it as tools.jackson.databind.node.ObjectNode).remove("owner_tenant_id") })
        assertThrows(IllegalArgumentException::class.java) { EventEnvelope.fromJson(without) }
        val noRecipient = mapper.writeValueAsString(mapper.readTree(json).also { (it as tools.jackson.databind.node.ObjectNode).remove("recipient_tenant_id") })
        assertThrows(IllegalArgumentException::class.java) { EnvelopeAddressing.requireAddressed(EventEnvelope.fromJson(noRecipient)) }
    }
}
