package de.taktovia.adapters.notification

import de.taktovia.adapters.notification.api.SimulatorProvider
import de.taktovia.communicationsupport.api.FailureReason
import de.taktovia.communicationsupport.api.NotificationChannel
import de.taktovia.communicationsupport.api.OutboundMessage
import de.taktovia.communicationsupport.api.ProviderResult
import de.taktovia.communicationsupport.api.ProviderStatus
import de.taktovia.communicationsupport.api.ProviderTimeoutException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP-M2-32: Simulator-Provider als Ersatz des realen Providers (D-05): Versand je Kanal, Idempotenz je Nachrichten-ID, steuerbare Fehler, ungültiger Kontakt. */
class SimulatorProviderTest {
    private fun msg(channel: NotificationChannel, to: String, id: UUID = UUID.randomUUID()) = OutboundMessage(id, channel, to, "Betreff", "Text")
    private val valid = mapOf(NotificationChannel.SMS to "+491701234567", NotificationChannel.EMAIL to "fahrgast@example.org", NotificationChannel.PUSH to "device-token-0001")

    @Test fun versand_je_kanal_wird_angenommen_und_zugestellt() {
        val p = SimulatorProvider()
        for ((ch, to) in valid) {
            val m = msg(ch, to); val r = p.send(m)
            assertTrue(r is ProviderResult.Accepted, ch.name)
            assertEquals(to, p.deliveryOf(m.messageId)!!.to); assertEquals(ch, p.deliveryOf(m.messageId)!!.channel)
        }
        assertEquals(3, p.deliveries.size)
    }

    @Test fun nachrichten_id_ist_idempotenzreferenz() {
        val p = SimulatorProvider(); val m = msg(NotificationChannel.SMS, valid.getValue(NotificationChannel.SMS))
        val first = p.send(m) as ProviderResult.Accepted; val again = p.send(m) as ProviderResult.Accepted
        assertEquals(first.providerRef, again.providerRef)
        assertEquals(2, p.sendCalls); assertEquals(1, p.deliveries.size, "eine fachliche Nachricht je ID")
    }

    @Test fun timeout_mit_spaeter_zustellung_und_abgleich() {
        val p = SimulatorProvider(); val late = msg(NotificationChannel.EMAIL, valid.getValue(NotificationChannel.EMAIL)); val lost = msg(NotificationChannel.EMAIL, valid.getValue(NotificationChannel.EMAIL))
        p.timeoutNext(deliver = true); assertThrows(ProviderTimeoutException::class.java) { p.send(late) }
        p.timeoutNext(deliver = false); assertThrows(ProviderTimeoutException::class.java) { p.send(lost) }
        assertTrue(p.status(NotificationChannel.EMAIL, late.messageId) is ProviderStatus.Accepted, "später Erfolg ist beim Abgleich sichtbar")
        assertEquals(ProviderStatus.NotKnown, p.status(NotificationChannel.EMAIL, lost.messageId))
        assertTrue(p.send(lost) is ProviderResult.Accepted, "Wiederholung mit derselben ID")
        assertEquals(2, p.deliveries.size)
    }

    @Test fun ungueltiger_kontakt_und_steuerbare_fehler() {
        val p = SimulatorProvider()
        for ((ch, bad) in mapOf(NotificationChannel.SMS to "0170 123", NotificationChannel.EMAIL to "kein-kontakt", NotificationChannel.PUSH to "kurz")) {
            val r = p.send(msg(ch, bad)) as ProviderResult.Rejected
            assertTrue(r.permanent); assertEquals(FailureReason.INVALID_CONTACT, r.reason)
        }
        assertEquals(0, p.deliveries.size)
        p.failNext(1, SimulatorProvider.Fault.TEMPORARY_ERROR); p.failNext(1, SimulatorProvider.Fault.PERMANENT_ERROR)
        val t = p.send(msg(NotificationChannel.SMS, valid.getValue(NotificationChannel.SMS))) as ProviderResult.Rejected
        val d = p.send(msg(NotificationChannel.SMS, valid.getValue(NotificationChannel.SMS))) as ProviderResult.Rejected
        assertFalse(t.permanent); assertTrue(d.permanent)
        assertTrue(p.send(msg(NotificationChannel.SMS, valid.getValue(NotificationChannel.SMS))) is ProviderResult.Accepted, "danach wieder normal")
    }

    @Test fun ausfall_liefert_timeout_und_keine_auskunft() {
        val p = SimulatorProvider(); p.outage = true
        val m = msg(NotificationChannel.PUSH, valid.getValue(NotificationChannel.PUSH))
        assertThrows(ProviderTimeoutException::class.java) { p.send(m) }
        assertEquals(ProviderStatus.Unavailable, p.status(NotificationChannel.PUSH, m.messageId))
        assertNull(p.deliveryOf(m.messageId))
        p.outage = false; assertTrue(p.send(m) is ProviderResult.Accepted)
    }

    @Test fun ausgabe_enthaelt_weder_kontakt_noch_inhalt() {
        val m = msg(NotificationChannel.SMS, "+491701234567")
        assertFalse(m.toString().contains("+49170")); assertFalse(m.toString().contains("Text"))
        val p = SimulatorProvider(); p.send(m)
        assertFalse(p.deliveries.single().toString().contains("+49170"))
    }
}
