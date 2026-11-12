package de.taktovia.communicationsupport

import de.taktovia.communicationsupport.api.BookingFacts
import de.taktovia.communicationsupport.api.BookingFactsSource
import de.taktovia.communicationsupport.api.DeliveryPurposePolicy
import de.taktovia.communicationsupport.api.NotificationChannel
import de.taktovia.communicationsupport.api.NotificationEventConsumer
import de.taktovia.communicationsupport.api.NotificationPurpose
import de.taktovia.communicationsupport.api.NotificationRequest
import de.taktovia.communicationsupport.api.TransactionalNotificationTrigger
import de.taktovia.communicationsupport.api.TriggerSettings
import de.taktovia.communicationsupport.api.TriggeredNotification
import de.taktovia.communicationsupport.internal.InboxNotificationConsumer
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.outbox.InboxOutcome
import de.taktovia.platform.outbox.InboxProcessor
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * LH-6.12-COM-001 / WP-M2-34 (AT-07 Infoteil, AT-01 Benachrichtigungsteil): die fünf Szenarien vom Ereignis bis zum Provider-Testdouble gegen die echte Datenbank unter der Runtime-Rolle.
 * Die Buchungsfakten liefert ein mandantenbewusstes Testdouble ([Facts]) für den Port [BookingFactsSource]; der Nachweis mit echten Buchungen und dem Simulator-Provider steht in
 * `NotificationTriggerEndToEndTest` der Anwendung. „Ankunft“ ist in M2 ein Testereignis (Schema `vehicle.arrived`), die ETA ist die Plan-ETA der Planrevision (WP-M4-61/-62 später).
 */
class NotificationTriggerTest {
    private companion object {
        val f = CommFixture()
        @JvmStatic @AfterAll fun close() = f.close()
        val mapper: JsonMapper = JsonMapper.builder().build()
        val PICKUP: Instant = Instant.parse("2026-11-02T09:00:00Z")   // 10:00 Ortszeit Berlin
    }

    /** Buchungsfakten je (Mandant, Buchung); der Mandant kommt aus der laufenden Transaktion, wie bei RLS. */
    private class Facts : BookingFactsSource {
        val map = ConcurrentHashMap<Pair<UUID, UUID>, BookingFacts>()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        override fun booking(jdbc: JdbcOperations, bookingId: UUID): BookingFacts? {
            reads.incrementAndGet()
            val tenant = jdbc.queryForObject("SELECT taktovia.current_tenant()", UUID::class.java)!!
            return map[tenant to bookingId]
        }
    }

    private class Scene(val w: CommFixture.World, val facts: Facts, val consumer: NotificationEventConsumer) {
        fun booking(i: Int = 0, state: String = "CONFIRMED", n: Int = 1000 + i, tenant: UUID = w.tenant.value, passenger: UUID = w.passengers[i]): UUID {
            val id = UUID.randomUUID()
            facts.map[tenant to id] = BookingFacts(id, "BK-2026-%06d".format(n), passenger, w.serviceA, state, "Marktplatz", PICKUP, PICKUP.plusSeconds(900), "Europe/Berlin")
            return id
        }
        fun setState(id: UUID, state: String) { facts.map.compute(w.tenant.value to id) { _, b -> b!!.copy(state = state) } }

        fun event(type: String, payload: String, aggregate: UUID = UUID.randomUUID(), revision: Long = 1, tenant: UUID = w.tenant.value, recipient: UUID? = tenant, id: UUID = UUID.randomUUID()) =
            EventEnvelope(id, type, "1.0", aggregate, revision, f.clock.instant(), f.clock.instant(), tenant, recipient, UUID.randomUUID(), mapper.readTree(payload)).toJson()

        fun consume(json: String) = consumer.consume(json)
        fun confirmed(b: UUID, aggregate: UUID = b) = consume(event("booking.confirmed", """{"booking_id":"$b"}""", aggregate))
        fun cancelled(b: UUID, initiator: String, revision: Long = 3) = consume(event("booking.cancelled", """{"booking_id":"$b","initiator":"$initiator","reason":"OPERATOR_CANCELLATION"}""", b, revision))
        fun planChange(vararg changes: Triple<UUID, String, Pair<Int, Int>>, run: UUID = UUID.randomUUID(), revision: Long = 2): InboxOutcome {
            val arr = changes.joinToString(",") { (b, kind, shift) ->
                """{"booking_id":"$b","kind":"$kind","sequence":2,"pickup_before":"$PICKUP","pickup_after":"${PICKUP.plusSeconds(shift.first * 60L)}","dropoff_before":"${PICKUP.plusSeconds(900)}","dropoff_after":"${PICKUP.plusSeconds(900L + shift.second * 60)}"}"""
            }
            return consume(event("dispatch.run_revised", """{"run_id":"$run","previous_revision":${revision - 1},"revision":$revision,"cause":"MANUAL_CHANGE","affected_booking_ids":[],"booking_changes":[$arr]}""", run, revision))
        }
        fun arrived(vararg bookings: UUID) = consume(event("vehicle.arrived", """{"shift_id":"${UUID.randomUUID()}","run_id":"${UUID.randomUUID()}","route_stop_id":"${UUID.randomUUID()}","booking_ids":[${bookings.joinToString(",") { "\"$it\"" }}],"arrived_at":"$PICKUP"}"""))

        fun types(passenger: UUID = w.passenger): List<String> = f.strings("SELECT event_type FROM taktovia.notification WHERE tenant_id = ? AND recipient_ref = ? ORDER BY created_at, id", w.tenant.value, passenger)
        fun count() = f.count("SELECT count(*) FROM taktovia.notification WHERE tenant_id = ?", w.tenant.value)
        fun dispatch() = f.dispatcher().runTenant(w.tenant)
    }

    private fun scene(seed: Boolean = true, purposes: DeliveryPurposePolicy = DeliveryPurposePolicy.OPERATIONAL_ONLY, settings: TriggerSettings = TriggerSettings()): Scene {
        val w = f.world(); if (seed) f.api.templates.seedDefaults(f.context(w.tenant))
        val facts = Facts()
        val processor = InboxProcessor(f.tx, SecurityEventPort(JdbcAuditPort()), { _, _, _ -> error("kein Snapshot erwartet") })
        return Scene(w, facts, f.api.transactionalConsumer(processor, facts, f.directory, purposes, settings))
    }

    private fun bodiesTo(passenger: UUID, channel: NotificationChannel = NotificationChannel.PUSH): List<String> =
        f.gateway.sent.filter { it.to == f.directory.contacts[passenger to channel] && it.channel == channel }.map { it.body }

    @Test
    fun jedes_szenario_erzeugt_nachricht_oder_kanalfehler() {
        val s = scene(); val p = s.w.passengers
        val confirmed = s.booking(0); val eta = s.booking(1, n = 1001); val arrival = s.booking(0, n = 1002)
        val cancelledP = s.booking(1, n = 1003); val cancelledO = s.booking(0, n = 1004)
        assertEquals(InboxOutcome.PROCESSED, s.confirmed(confirmed))
        assertEquals(InboxOutcome.PROCESSED, s.planChange(Triple(eta, "TIME_CHANGED", 10 to 10)))
        assertEquals(InboxOutcome.PROCESSED, s.arrived(arrival))
        s.setState(cancelledP, "CANCELED"); s.setState(cancelledO, "CANCELED")
        assertEquals(InboxOutcome.PROCESSED, s.cancelled(cancelledP, "PASSENGER")); assertEquals(InboxOutcome.PROCESSED, s.cancelled(cancelledO, "OPERATOR"))
        assertEquals(setOf("booking.confirmed", "vehicle.arrived", "booking.cancelled_by_operator"), s.types(p[0]).toSet())
        assertEquals(setOf("eta.changed", "booking.cancelled_by_passenger"), s.types(p[1]).toSet())
        assertEquals(5, s.count())
        val stats = s.dispatch()
        assertEquals(5, stats.delivered)                                                   // alle fünf bis zum Provider (Push, erster Kanal mit Kontakt)
        val a = bodiesTo(p[0]); val b = bodiesTo(p[1])
        assertTrue(a.any { it.contains("BK-2026-001000") && it.contains("ist bestätigt") && it.contains("Marktplatz") }, a.toString())
        assertTrue(b.any { it.contains("BK-2026-001001") && it.contains("neue voraussichtliche Ankunft: 10:10") }, b.toString())      // Plan-ETA 09:10Z = 10:10 Ortszeit
        assertTrue(a.any { it.contains("BK-2026-001002") && it.contains("wartet am Treffpunkt") }, a.toString())
        assertTrue(b.any { it.contains("BK-2026-001003") && it.contains("auf Ihren Wunsch storniert") }, b.toString())
        assertTrue(a.any { it.contains("BK-2026-001004") && it.contains("entfällt") }, a.toString())
    }

    @Test
    fun storno_und_ausfall_haben_verschiedene_nachrichtentypen() {
        val s = scene(); val p0 = s.booking(0); val p1 = s.booking(1, n = 2001); val sys = s.booking(0, n = 2002)
        listOf(p0, p1, sys).forEach { s.setState(it, "CANCELED") }
        s.cancelled(p0, "PASSENGER"); s.cancelled(p1, "OPERATOR")
        assertEquals(listOf("booking.cancelled_by_passenger"), s.types(s.w.passengers[0]))
        assertEquals(listOf("booking.cancelled_by_operator"), s.types(s.w.passengers[1]))
        // Systemstorno (Zahlungsausfall, Nichterscheinen) ist keines der fünf Szenarien: keine Nachricht, aber verbraucht (Inbox)
        assertEquals(InboxOutcome.PROCESSED, s.cancelled(sys, "SYSTEM")); assertEquals(2, s.count())
    }

    @Test
    fun kein_nutzbarer_kanal_dokumentiert_kanalfehler_buchung_unberuehrt() {
        val s = scene(); val p = s.w.passengers[0]
        NotificationChannel.entries.forEach { f.directory.contacts.remove(p to it) }             // kein verifizierter Kontakt
        val b = s.booking(0); val before = s.facts.map[s.w.tenant.value to b]
        assertEquals(InboxOutcome.PROCESSED, s.confirmed(b))
        assertEquals(1, s.count())                                                                // der Auftrag entsteht trotzdem (dokumentierbar)
        s.dispatch()
        val n = f.strings("SELECT id::text FROM taktovia.notification WHERE tenant_id = ? AND recipient_ref = ?", s.w.tenant.value, p).single().let(UUID::fromString)
        assertEquals("SUPPRESSED", f.stateOf(n)); assertEquals("NO_VERIFIED_CONTACT", f.failureOf(n))
        assertTrue(f.gateway.sent.none { it.messageId == n }, "ohne Kontakt wird nichts gesendet")
        assertEquals(before, s.facts.map[s.w.tenant.value to b], "die Buchung bleibt unberührt")
        // fehlende Vorlage: Kanalfehler statt leerer Nachricht
        val bare = scene(seed = false); val b2 = bare.booking(0)
        bare.confirmed(b2); bare.dispatch()
        val n2 = f.strings("SELECT id::text FROM taktovia.notification WHERE tenant_id = ?", bare.w.tenant.value).single().let(UUID::fromString)
        assertEquals("FAILED", f.stateOf(n2)); assertEquals("TEMPLATE_UNAVAILABLE", f.failureOf(n2))
        // Provider-Ausfall: dokumentierter Fehlschlag, die Buchung ist nicht betroffen
        val down = scene(); f.gateway.default = ScriptedGateway.Step.REJECT_PERMANENT
        try { down.confirmed(down.booking(0)); down.dispatch() } finally { f.gateway.default = ScriptedGateway.Step.ACCEPT }
        val n3 = f.strings("SELECT id::text FROM taktovia.notification WHERE tenant_id = ?", down.w.tenant.value).single().let(UUID::fromString)
        assertEquals("FAILED", f.stateOf(n3)); assertEquals("PROVIDER_ERROR", f.failureOf(n3))
    }

    @Test
    fun eta_aenderung_unter_schwelle_erzeugt_keine_nachricht() {
        val s = scene(settings = TriggerSettings(etaRelevanceThreshold = java.time.Duration.ofMinutes(5)))
        val small = s.booking(0); val exact = s.booking(1, n = 3001); val early = s.booking(0, n = 3002); val added = s.booking(1, n = 3003); val removed = s.booking(0, n = 3004)
        s.planChange(Triple(small, "TIME_CHANGED", 4 to 4))
        assertEquals(0, s.count(), "4 Minuten liegen unter der Schwelle")
        s.planChange(Triple(exact, "TIME_CHANGED", 5 to 5), run = UUID.randomUUID())
        assertEquals(listOf("eta.changed"), s.types(s.w.passengers[1]), "genau die Schwelle ist relevant")
        s.planChange(Triple(early, "TIME_CHANGED", -12 to 0), run = UUID.randomUUID())                  // früher als geplant ist ebenso relevant
        assertEquals(listOf("eta.changed"), s.types(s.w.passengers[0]))
        s.planChange(Triple(added, "ADDED", 30 to 30), Triple(removed, "REMOVED", 30 to 30), run = UUID.randomUUID())
        assertEquals(2, s.count(), "Einfügen und Entfernen sind keine ETA-Änderung")
        // zwei Buchungen desselben Fahrgasts in derselben Planrevision: zwei Nachrichten, keine Kollision
        val s2 = scene(); val x = s2.booking(0); val y = s2.booking(0, n = 3010)
        s2.planChange(Triple(x, "TIME_CHANGED", 10 to 10), Triple(y, "TIME_CHANGED", 20 to 20))
        assertEquals(2, s2.count())
    }

    @Test
    fun betriebsnachricht_ohne_marketingeinwilligung_zugestellt_marketinginhalt_abgelehnt() {
        // Policy wie die Identität: Werbung nur mit Einwilligung (hier: keine erteilt), Betriebsmitteilung immer
        val noMarketing = DeliveryPurposePolicy { _, _, purpose -> purpose == NotificationPurpose.OPERATIONAL }
        val s = scene(purposes = noMarketing); val b = s.booking(0)
        s.confirmed(b); s.dispatch()
        assertEquals(1, bodiesTo(s.w.passengers[0]).count { it.contains("ist bestätigt") }, "Betriebsnachricht trotz fehlender Marketingeinwilligung")
        // ein Auslöser mit Werbezweck wird verworfen, nichts angelegt
        val w = s.w
        val marketing = TransactionalNotificationTrigger { _, e -> listOf(TriggeredNotification(NotificationRequest(e.eventId, w.passengers[1], NotificationChannel.EMAIL, "booking.confirmed", w.serviceA, f.defaultParams()), NotificationPurpose.MARKETING)) }
        val processor = InboxProcessor(f.tx, SecurityEventPort(JdbcAuditPort()), { _, _, _ -> error("kein Snapshot erwartet") })
        val direct = InboxNotificationConsumer(processor, f.api.notifications, emptyList(), listOf(marketing), noMarketing)
        assertEquals(InboxOutcome.PROCESSED, direct.consume(s.event("booking.confirmed", """{"booking_id":"${UUID.randomUUID()}"}""")))
        assertEquals(0, s.types(w.passengers[1]).size)
        // und Marketinginhalt (unbekannter Ereignistyp) lässt sich über den Versandweg gar nicht anlegen
        val ex = assertThrows<ProblemException> { f.tx.run(w.tenant) { j -> f.api.notifications.send(j, NotificationRequest(UUID.randomUUID(), w.passengers[1], NotificationChannel.EMAIL, "marketing.newsletter", w.serviceA, f.defaultParams())) } }
        assertEquals(ErrorCode.VALIDATION_FAILED, ex.code)
        assertEquals(0, s.types(w.passengers[1]).size)
    }

    @Test
    fun nur_betroffene_mitfahrende_erhalten_nachricht() {
        val s = scene(); val a = s.booking(0); val b = s.booking(1, n = 4001)         // zwei Fahrgäste im selben Umlauf
        s.planChange(Triple(a, "TIME_CHANGED", 15 to 15))                              // nur A ist betroffen
        assertEquals(listOf("eta.changed"), s.types(s.w.passengers[0])); assertEquals(emptyList<String>(), s.types(s.w.passengers[1]))
        s.arrived(b)                                                                    // Ankunft am Stopp von B: nur B
        assertEquals(listOf("vehicle.arrived"), s.types(s.w.passengers[1])); assertEquals(listOf("eta.changed"), s.types(s.w.passengers[0]))
        s.dispatch()
        assertTrue(bodiesTo(s.w.passengers[0]).none { it.contains("4001") }, "A erhält nie Daten von B")
        assertTrue(bodiesTo(s.w.passengers[1]).none { it.contains("BK-2026-001000") })
    }

    @Test
    fun eta_nach_storno_oder_bei_inaktiver_buchung_erzeugt_keine_nachricht() {
        val s = scene(); val a = s.booking(0); val b = s.booking(1, n = 5001)
        s.setState(a, "CANCELED")                                                       // Storno vor der Verarbeitung der Planänderung
        s.planChange(Triple(a, "TIME_CHANGED", 30 to 30), Triple(b, "TIME_CHANGED", 30 to 30))
        assertEquals(emptyList<String>(), s.types(s.w.passengers[0])); assertEquals(listOf("eta.changed"), s.types(s.w.passengers[1]))
        s.confirmed(a)                                                                  // und keine Bestätigung für eine stornierte Buchung
        assertEquals(emptyList<String>(), s.types(s.w.passengers[0]))
        s.arrived(a); assertEquals(emptyList<String>(), s.types(s.w.passengers[0]))
    }

    @Test
    fun doppeltes_ereignis_erzeugt_eine_nachricht() {
        val s = scene(); val b = s.booking(0)
        val json = s.event("booking.confirmed", """{"booking_id":"$b"}""", b)
        val n = 6; val barrier = CyclicBarrier(n); val pool = Executors.newFixedThreadPool(n)
        try {
            val outcomes = (1..n).map { pool.submit(Callable { barrier.await(); s.consume(json) }) }.map { it.get() }
            assertEquals(1, outcomes.count { it == InboxOutcome.PROCESSED }, outcomes.toString()); assertEquals(n - 1, outcomes.count { it == InboxOutcome.DUPLICATE })
        } finally { pool.shutdownNow() }
        assertEquals(1, s.count()); assertEquals(InboxOutcome.DUPLICATE, s.consume(json)); assertEquals(1, s.count())
        // dieselbe Planrevision (gleiche Ereignis-ID) mehrfach: je Buchung eine Nachricht
        val c = s.booking(1, n = 6001); val run = UUID.randomUUID(); val id = UUID.randomUUID()
        val pc = s.event("dispatch.run_revised", """{"run_id":"$run","previous_revision":1,"revision":2,"cause":"MANUAL_CHANGE","affected_booking_ids":[],"booking_changes":[{"booking_id":"$c","kind":"TIME_CHANGED","sequence":2,"pickup_before":"$PICKUP","pickup_after":"${PICKUP.plusSeconds(1200)}"}]}""", run, 2, id = id)
        repeat(3) { s.consume(pc) }
        assertEquals(1, s.types(s.w.passengers[1]).size)
        // Konsumentennamen je Quellereignis: die Revisionen 1 (bestätigt) und 3 (storniert) derselben Buchung sind keine „Lücke“
        s.setState(b, "CANCELED"); assertEquals(InboxOutcome.PROCESSED, s.cancelled(b, "PASSENGER", revision = 3))
        assertEquals(setOf("booking.confirmed", "booking.cancelled_by_passenger"), s.types(s.w.passengers[0]).toSet())
    }

    @Test
    fun fremder_mandant_erzeugt_keinen_auftrag() {
        val a = scene(); val b = scene()
        val bookingOfA = a.booking(0)
        // Ereignis des Mandanten B verweist auf eine Buchung des Mandanten A: B kennt sie nicht, nirgends entsteht ein Auftrag
        assertEquals(InboxOutcome.PROCESSED, b.consume(b.event("booking.confirmed", """{"booking_id":"$bookingOfA"}""", bookingOfA)))
        assertEquals(0, b.count()); assertEquals(0, a.count())
        // Ereignis des Eigentümers B mit Empfänger A: nicht zustellbar adressiert
        val foreign = b.consume(b.event("booking.confirmed", """{"booking_id":"$bookingOfA"}""", bookingOfA, tenant = b.w.tenant.value, recipient = a.w.tenant.value))
        assertEquals(InboxOutcome.REJECTED_RECIPIENT, foreign); assertEquals(0, a.count())
        // und das eigene Ereignis des Mandanten A erzeugt genau dort den Auftrag
        a.confirmed(bookingOfA); assertEquals(1, a.count()); assertEquals(0, b.count())
        assertFalse(f.count("SELECT count(*) FROM taktovia.notification WHERE tenant_id = ?", b.w.tenant.value) > 0)
    }
}
