package de.taktovia.app.communication

import de.taktovia.TaktoviaTestSupport
import de.taktovia.adapters.notification.api.NotificationAdapter
import de.taktovia.adapters.notification.api.SimulatorProvider
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.app.probe.ProbeServer
import de.taktovia.communicationsupport.api.CommunicationsupportApi
import de.taktovia.communicationsupport.api.NotificationChannel
import de.taktovia.communicationsupport.api.NotificationDispatcher
import de.taktovia.communicationsupport.api.NotificationRequest
import de.taktovia.identity.api.ContactChannel
import de.taktovia.identity.api.ContactInput
import de.taktovia.identity.api.InMemoryVerificationSender
import de.taktovia.identity.api.PassengerApi
import de.taktovia.identity.api.ProfilePatch
import de.taktovia.identity.api.RegistrationCaller
import de.taktovia.identity.api.VerificationSender
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M2-32 / -33 Ende zu Ende in der echten Anwendung (Runtime-Rolle, Feldschutz, Identität) bis zum Simulator-Provider: Verdrahtung (Provider nur auf ausdrückliche
 * Konfiguration), verifizierter Kontakt und Sprache aus dem Fahrgastkonto, Standardvorlagen, Sprachwechsel mit unveränderter Buchungsreferenz, Kanäle ohne verifizierten
 * Kontakt dokumentiert unterdrückt. Nicht ausgeführt: Broker (RabbitMQ) und realer Provider.
 */
class NotificationEndToEndTest {
    private val server by lazy { ProbeServer.production("taktovia.notifications.provider=simulator") }
    private fun <T : Any> bean(c: Class<T>): T = server.ctx.getBean(c)

    @Test
    fun ohne_provider_kein_versandworker_und_mit_simulator_verdrahtet() {
        assertTrue(IdentityEnv.server.ctx.getBeansOfType(NotificationDispatcher::class.java).isEmpty(), "ohne Provider (D-05 offen) gibt es keinen Versandworker")
        assertEquals(1, server.ctx.getBeansOfType(NotificationDispatcher::class.java).size)
        assertTrue(bean(NotificationAdapter::class.java) is SimulatorProvider)
    }

    @Test
    fun ereignis_bis_zum_provider_mit_sprachwechsel_und_unterdrueckten_kanaelen() {
        TaktoviaTestSupport.requireDatabase()
        val comm = bean(CommunicationsupportApi::class.java); val dispatcher = bean(NotificationDispatcher::class.java); val provider = bean(NotificationAdapter::class.java) as SimulatorProvider
        val passengers = bean(PassengerApi::class.java); val tx = bean(TenantTransactions::class.java); val sender = bean(VerificationSender::class.java) as InMemoryVerificationSender
        val tenant: TenantId = IdentityEnv.tenant("E2E-Nachricht-" + UUID.randomUUID())
        val subject = UUID.randomUUID().toString(); val email = "e2e.${UUID.randomUUID().toString().take(8)}@example.org"; val issuer = "https://idp.test/realms/e2e"
        val registered = passengers.accounts.register(RegistrationCaller(issuer, subject, UUID.randomUUID()), tenant, "en-GB", ContactInput(ContactChannel.EMAIL, email))
        val passenger = registered.passenger.id
        val membership = tx.run(tenant) { j -> j.query("SELECT id FROM taktovia.membership WHERE subject = ? AND role_code = 'passenger'", { rs, _ -> rs.getObject(1, UUID::class.java) }, subject) }.toSet()
        val ctx = RequestContext(Actor(ActorType.HUMAN, issuer, subject, "sid-$subject", true), tenant, setOf("passenger"), emptySet(), emptySet(), UUID.randomUUID(), membershipIds = membership)
        passengers.accounts.confirmVerification(ctx, ContactChannel.EMAIL, sender.last(ContactChannel.EMAIL, email)!!)
        val system = RequestContext(Actor(ActorType.SERVICE, issuer, "seed", "sid", true), tenant, emptySet(), emptySet(), emptySet(), UUID.randomUUID())
        comm.templates.seedDefaults(system)

        fun enqueue(eventType: String, channel: NotificationChannel, ref: String) = tx.run(tenant) { j ->
            comm.notifications.send(j, NotificationRequest(UUID.randomUUID(), passenger, channel, eventType, null,
                mapOf("booking_reference" to ref, "booking_number" to "815", "meeting_point" to "Markt <1>", "time_window" to "10:00-10:15", "eta" to "10:07"))).notificationId
        }
        val ref = "BK-E2E-" + UUID.randomUUID().toString().take(6)
        val mail1 = enqueue("booking.confirmed", NotificationChannel.EMAIL, ref)
        val sms = enqueue("booking.confirmed", NotificationChannel.SMS, ref); val push = enqueue("booking.confirmed", NotificationChannel.PUSH, ref)
        val stats = dispatcher.runTenant(tenant)
        assertEquals(1, stats.delivered); assertEquals(2, stats.suppressed)
        val d1 = provider.deliveryOf(mail1)!!
        assertEquals(email, d1.to, "verifizierter Kontakt aus dem Feldschutz"); assertTrue(d1.body.contains(ref) && d1.body.contains("is confirmed"), d1.body)
        assertTrue(d1.body.contains("Markt &lt;1&gt;"), "Markup im Treffpunkt maskiert: ${d1.body}")
        assertEquals(null, provider.deliveryOf(sms), "kein verifiziertes Telefon: nichts gesendet"); assertEquals(null, provider.deliveryOf(push), "kein Geräteregister in P1: nichts gesendet")
        for (id in listOf(sms, push)) { val v = comm.notifications.get(system, id); assertEquals("SUPPRESSED", v.state.name); assertEquals("NO_VERIFIED_CONTACT", v.lastFailure?.name) }
        assertEquals("DELIVERED", comm.notifications.get(system, mail1).state.name)

        // Sprachwechsel des Fahrgasts: dieselbe Buchung, derselbe Ereignistyp, neue Nachricht in der neuen Sprache, gleiche Buchungsreferenz
        val profile = passengers.profiles.get(ctx)
        passengers.profiles.update(ctx, profile.revision, ProfilePatch(locale = "de-DE"))
        val mail2 = enqueue("booking.confirmed", NotificationChannel.EMAIL, ref)
        dispatcher.runTenant(tenant)
        val d2 = provider.deliveryOf(mail2)!!
        assertTrue(d2.body.contains(ref) && d2.body.contains("bestätigt"), d2.body); assertFalse(d2.body.contains("is confirmed"))
        assertEquals(d1.subject != d2.subject, true, "Betreff ebenfalls in der neuen Sprache")
        // eine weitere Zustellung desselben Ereignisses erzeugt keinen zweiten Versand
        assertEquals(1, provider.deliveries.count { it.messageId == mail2 })
        // Kontakt und Inhalt nicht in der Auftragszeile
        assertEquals(0, IdentityEnv.tx.run(tenant) { j -> j.queryForObject("SELECT count(*) FROM taktovia.notification WHERE notification::text LIKE ?", Int::class.java, "%$email%") })
    }
}
