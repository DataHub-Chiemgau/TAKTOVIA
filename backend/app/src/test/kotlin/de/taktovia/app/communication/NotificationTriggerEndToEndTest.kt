package de.taktovia.app.communication

import de.taktovia.adapters.notification.api.NotificationAdapter
import de.taktovia.adapters.notification.api.SimulatorProvider
import de.taktovia.app.booking.BookingHttpSupport
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.app.probe.ProbeServer
import de.taktovia.communicationsupport.api.CommunicationsupportApi
import de.taktovia.communicationsupport.api.NotificationDispatcher
import de.taktovia.communicationsupport.api.NotificationEventConsumer
import de.taktovia.identity.api.ContactChannel
import de.taktovia.identity.api.ContactInput
import de.taktovia.identity.api.InMemoryVerificationSender
import de.taktovia.identity.api.PassengerApi
import de.taktovia.identity.api.RegistrationCaller
import de.taktovia.identity.api.VerificationSender
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.outbox.InboxOutcome
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * WP-M2-34 Ende zu Ende in der echten Anwendung: echte Buchungen (HTTP), echte Outbox-Ereignisse, echter Verbraucher (Inbox, Auslöser, Buchungsfakten unter Lesesperre), echtes
 * Fahrgastkonto mit verifiziertem Kontakt (Feldschutz) und der Simulator-Provider. Nicht ausgeführt: Broker (RabbitMQ) und realer Provider. Das Ereignis „Ankunft“ der Driver-App
 * gibt es erst mit WP-M3-29; es wird hier als Testereignis nach dem Schema `vehicle.arrived` eingespeist.
 */
class NotificationTriggerEndToEndTest {
    private val server by lazy { ProbeServer.production("taktovia.notifications.provider=simulator") }
    private fun <T : Any> bean(c: Class<T>): T = server.ctx.getBean(c)
    private val s = BookingHttpSupport()
    private val tenant = s.tenant

    /** Fahrgast mit Konto, verifizierter E-Mail und Token; Buchungen entstehen über [BookingHttpSupport]. */
    private inner class Passenger {
        val subject = UUID.randomUUID(); val email = "trigger.${UUID.randomUUID().toString().take(8)}@example.org"
        val account: BookingHttpSupport.Account
        val id: UUID
        init {
            val passengers = bean(PassengerApi::class.java); val sender = bean(VerificationSender::class.java) as InMemoryVerificationSender
            val issuer = IdentityEnv.idp.issuer
            id = passengers.accounts.register(RegistrationCaller(issuer, subject.toString(), UUID.randomUUID()), tenant, "de-DE", ContactInput(ContactChannel.EMAIL, email)).passenger.id
            val membership = IdentityEnv.tx.run(tenant) { j -> j.query("SELECT id FROM taktovia.membership WHERE subject = ? AND role_code = 'passenger'", { rs, _ -> rs.getObject(1, UUID::class.java) }, subject.toString()) }.toSet()
            val ctx = RequestContext(Actor(ActorType.HUMAN, issuer, subject.toString(), "sid-$subject", true), tenant, setOf("passenger"), emptySet(), emptySet(), UUID.randomUUID(), membershipIds = membership)
            passengers.accounts.confirmVerification(ctx, ContactChannel.EMAIL, sender.last(ContactChannel.EMAIL, email)!!)
            account = BookingHttpSupport.Account(s.env.token(subject), subject)
        }
        fun booked(): Pair<String, BookingHttpSupport.Held> { val h = s.held(account = account, passengers = 1); return s.confirm(h).json["id"].asString() to h }
    }

    private fun outbox(aggregate: String, type: String): String = s.env.string(
        "SELECT json_build_object('event_id', event_id, 'event_type', event_type, 'schema_version', schema_version, 'aggregate_id', aggregate_id, 'aggregate_revision', aggregate_revision, " +
            "'occurred_at', to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'), 'recorded_at', to_char(recorded_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'), " +
            "'owner_tenant_id', tenant_id, 'recipient_tenant_id', recipient_tenant_id, 'payload', payload)::text FROM taktovia.outbox WHERE aggregate_id = ?::uuid AND event_type = ? ORDER BY aggregate_revision DESC LIMIT 1",
        aggregate, type).single()

    private fun notifications(passenger: UUID): List<Pair<UUID, String>> = IdentityEnv.tx.run(tenant) { j ->
        j.query("SELECT id, event_type FROM taktovia.notification WHERE recipient_ref = ? ORDER BY created_at, id", { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }, passenger)
    }

    private fun planEvent(booking: String, shiftMinutes: Long, revision: Long = 2): String {
        val t = Instant.parse("2026-11-05T09:00:00Z")
        val payload = """{"run_id":"${UUID.randomUUID()}","previous_revision":${revision - 1},"revision":$revision,"cause":"MANUAL_CHANGE","affected_booking_ids":["$booking"],""" +
            """"booking_changes":[{"booking_id":"$booking","kind":"TIME_CHANGED","sequence":2,"pickup_before":"$t","pickup_after":"${t.plusSeconds(shiftMinutes * 60)}"}]}"""
        return EventEnvelope(UUID.randomUUID(), "dispatch.run_revised", "1.0", UUID.randomUUID(), revision, Instant.now(), Instant.now(), tenant.value, tenant.value, UUID.randomUUID(),
            EventEnvelope.parsePayload(payload)).toJson()
    }

    @Test
    fun bestaetigung_eta_ankunft_storno_und_ausfall_vom_ereignis_bis_zum_simulator_provider() {
        val consumer = bean(NotificationEventConsumer::class.java); val dispatcher = bean(NotificationDispatcher::class.java); val provider = bean(NotificationAdapter::class.java) as SimulatorProvider
        val comm = bean(CommunicationsupportApi::class.java)
        comm.templates.seedDefaults(RequestContext(Actor(ActorType.SERVICE, "https://idp.test/realms/e2e", "seed", "sid", true), tenant, emptySet(), emptySet(), emptySet(), UUID.randomUUID()))
        val a = Passenger(); val b = Passenger()
        val (bookingA, heldA) = a.booked(); val (bookingB, _) = b.booked()

        // 1. Bestätigung: das echte Ereignis `booking.confirmed` der Buchung, Nachricht an den Fahrgast der Buchung
        assertEquals(InboxOutcome.PROCESSED, consumer.consume(outbox(bookingA, "booking.confirmed")))
        assertEquals(InboxOutcome.DUPLICATE, consumer.consume(outbox(bookingA, "booking.confirmed")))
        assertEquals(listOf("booking.confirmed"), notifications(a.id).map { it.second }); assertEquals(emptyList<String>(), notifications(b.id).map { it.second })
        // 2. ETA-Änderung: unter der Schwelle (3 Minuten) keine Nachricht, ab der Schwelle (20 Minuten) eine
        assertEquals(InboxOutcome.PROCESSED, consumer.consume(planEvent(bookingA, 3))); assertEquals(1, notifications(a.id).size)
        assertEquals(InboxOutcome.PROCESSED, consumer.consume(planEvent(bookingA, 20))); assertEquals(listOf("booking.confirmed", "eta.changed"), notifications(a.id).map { it.second })
        // 3. Ankunft (Testereignis nach dem Schema `vehicle.arrived`): nur die Buchung des Stopps, nicht die Mitfahrenden
        val arrival = EventEnvelope(UUID.randomUUID(), "vehicle.arrived", "1.0", UUID.randomUUID(), 1, Instant.now(), Instant.now(), tenant.value, tenant.value, UUID.randomUUID(), EventEnvelope.parsePayload(
            """{"shift_id":"${UUID.randomUUID()}","run_id":"${heldA.run}","route_stop_id":"${UUID.randomUUID()}","booking_ids":["$bookingA"],"arrived_at":"${Instant.now()}"}""")).toJson()
        assertEquals(InboxOutcome.PROCESSED, consumer.consume(arrival)); assertEquals(3, notifications(a.id).size); assertEquals(0, notifications(b.id).size)
        // 4. Storno durch den Fahrgast (echte HTTP-Operation) und 5. Betreiberstorno: verschiedene Nachrichtentypen
        val rev = s.get(bookingA, a.account.token).header("ETag")!!.trim('"')
        assertEquals(200, s.cancel(bookingA, a.account.token, ifMatch = rev).status)
        assertEquals(InboxOutcome.PROCESSED, consumer.consume(outbox(bookingA, "booking.cancelled")))
        val op = s.operator(listOf(UUID.fromString(s.world.service)))
        val revB = s.get(bookingB, b.account.token).header("ETag")!!.trim('"')
        assertEquals(200, s.cancel(bookingB, op, reason = "OPERATOR_CANCELLATION", ifMatch = revB).status)
        assertEquals(InboxOutcome.PROCESSED, consumer.consume(outbox(bookingB, "booking.cancelled")))
        assertEquals(listOf("booking.confirmed", "eta.changed", "vehicle.arrived", "booking.cancelled_by_passenger"), notifications(a.id).map { it.second })
        assertEquals(listOf("booking.cancelled_by_operator"), notifications(b.id).map { it.second })
        // nach dem Storno keine ETA-Nachricht mehr, auch nicht für eine spätere Planrevision
        assertEquals(InboxOutcome.PROCESSED, consumer.consume(planEvent(bookingA, 40, revision = 3))); assertEquals(4, notifications(a.id).size)

        // bis zum Provider: alle Nachrichten erreichen den verifizierten Kontakt (E-Mail, der einzige Kanal mit Kontakt), in der Sprache des Kontos
        val stats = dispatcher.runTenant(tenant)
        assertEquals(5, stats.delivered)
        val bodies = notifications(a.id).map { provider.deliveryOf(it.first)!! }
        assertTrue(bodies.all { it.to == a.email }, "verifizierter Kontakt aus dem Feldschutz")
        assertTrue(bodies[0].body.contains("bestätigt") && bodies[0].body.contains(Regex("BK-[0-9]{4}-[0-9]{6}").find(bodies[0].body)!!.value), bodies[0].body)
        assertTrue(bodies[3].body.contains("storniert"), bodies[3].body)
        assertTrue(provider.deliveryOf(notifications(b.id).single().first)!!.body.contains("entfällt"))
        // der Fahrgast B erhielt nie Daten der Buchung von A
        val aRef = Regex("BK-[0-9]{4}-[0-9]{6}").find(bodies[0].body)!!.value
        assertTrue(notifications(b.id).none { provider.deliveryOf(it.first)!!.body.contains(aRef) })
    }

    @Test
    fun storno_parallel_zu_eta_aenderung_keine_eta_nachricht_nach_dem_storno() {
        val consumer = bean(NotificationEventConsumer::class.java)
        bean(CommunicationsupportApi::class.java).templates.seedDefaults(RequestContext(Actor(ActorType.SERVICE, "https://idp.test/realms/e2e", "seed", "sid", true), tenant, emptySet(), emptySet(), emptySet(), UUID.randomUUID()))
        val p = Passenger(); val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(6) {
                val (booking, _) = p.booked()
                val before = notifications(p.id).size
                val rev = s.get(booking, p.account.token).header("ETag")!!.trim('"'); val event = planEvent(booking, 30)
                val barrier = CyclicBarrier(2)
                val cancel = pool.submit(Callable { barrier.await(); s.cancel(booking, p.account.token, ifMatch = rev).status })
                val eta = pool.submit(Callable { barrier.await(); consumer.consume(event) })
                assertEquals(200, cancel.get()); assertEquals(InboxOutcome.PROCESSED, eta.get())
                assertTrue(notifications(p.id).size - before <= 1, "höchstens eine ETA-Nachricht, wenn sie vor dem Storno entstand")
                // nach dem Commit des Stornos: eine weitere Planrevision erzeugt nie mehr eine ETA-Nachricht
                val during = notifications(p.id).size
                assertEquals(InboxOutcome.PROCESSED, consumer.consume(planEvent(booking, 45, revision = 3)))
                assertEquals(during, notifications(p.id).size)
            }
        } finally { pool.shutdownNow() }
    }
}
