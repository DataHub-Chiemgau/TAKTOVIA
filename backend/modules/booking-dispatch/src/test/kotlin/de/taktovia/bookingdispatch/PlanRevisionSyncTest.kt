package de.taktovia.bookingdispatch

import de.taktovia.booking.ConfirmFixture
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.DriverPlanChange
import de.taktovia.bookingdispatch.api.DriverPlanSnapshot
import de.taktovia.bookingdispatch.api.PassengerPlanChange
import de.taktovia.bookingdispatch.api.PassengerPlanSnapshot
import de.taktovia.bookingdispatch.api.PlanBookingChangeKind
import de.taktovia.bookingdispatch.api.PlanChangeEntry
import de.taktovia.bookingdispatch.api.PlanChanges
import de.taktovia.bookingdispatch.api.PlanChannel
import de.taktovia.bookingdispatch.api.PlanStop
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RevisionCause
import de.taktovia.bookingdispatch.api.StopMovement
import de.taktovia.bookingdispatch.api.RevisionTracker
import de.taktovia.bookingdispatch.api.RunPlans
import de.taktovia.bookingdispatch.api.SyncDecision
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M2-28 / LH-6.5-OPT-010 (PH-AT-017): Planrevisionen werden versioniert und an betroffene Fahrgäste und Fahrer verteilt; ein Client mit veralteter Revision wird erkannt und
 * synchronisiert. Gegen die echte Datenbank unter der Runtime-Rolle; der Testclient [Client] nutzt nur die öffentliche Schnittstelle [PlanChanges] und den [RevisionTracker].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanRevisionSyncTest {
    private lateinit var cf: ConfirmFixture
    private lateinit var api: BookingdispatchApi
    private val runs: RunPlans get() = api.runs
    private val feed: PlanChanges get() = api.planChanges

    @BeforeAll fun up() { cf = ConfirmFixture(); api = BookingdispatchApi.create(cf.fx.base.ctxTx, cf.fx.clock, cf.fx.base.outbox) }
    @AfterAll fun down() = cf.close()

    /** Ein Umlauf mit zwei bestätigten Buchungen verschiedener Fahrgäste und einem Fahrer, dem die Schicht gehört. */
    private inner class Scene(val tenantW: de.taktovia.bookingdispatch.RideOfferFixture.World = cf.world()) {
        val tenant: TenantId = tenantW.tenant
        val run = cf.fx.run(tenantW, OfferObjectsFixture.cap(4, 0))
        val op: RequestContext = cf.operator(tenant, setOf(tenantW.serviceId))
        val pa = cf.passenger(tenantW); val pb = cf.passenger(tenantW)
        val ha = cf.booked(tenantW, run, pa, passengers = 1); val hb = cf.booked(tenantW, run, pb, passengers = 1)
        val a = ha.second; val b = hb.second
        val driverSubject = UUID.randomUUID().toString()
        val driver = RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/booking", driverSubject, "sid-d", true), tenant, setOf("driver"), setOf("driver:run"), emptySet(), UUID.randomUUID())
        init {
            cf.fx.adminUpdate("UPDATE taktovia.staff_shift SET driver_ref = ?::uuid WHERE id = ?", driverSubject, run.shiftId)
            // Ausgangsplan (Revision 2): A steigt zuerst ein und aus, danach B; Planzeiten 09:00, 09:05, 09:15, 09:20
            val t0 = java.time.Instant.parse("2026-11-02T09:00:00Z"); val v = ResourceVector(1, 0)
            val from = ha.first.offer.pickupStop!!.revisionId; val to = ha.first.offer.dropoffStop!!.revisionId
            runs.appendRevision(op, run.runId, 1, listOf(
                PlanStop(0, from, t0, boardings = listOf(StopMovement(a.id, v)), legDurationSec = 300, legDistanceM = 2000),
                PlanStop(1, from, t0.plusSeconds(300), boardings = listOf(StopMovement(b.id, v)), legDurationSec = 600, legDistanceM = 4000),
                PlanStop(2, to, t0.plusSeconds(900), alightings = listOf(StopMovement(a.id, v)), legDurationSec = 300, legDistanceM = 2000),
                PlanStop(3, to, t0.plusSeconds(1200), alightings = listOf(StopMovement(b.id, v))),
            ), RevisionCause.BOOKING_INSERTED, listOf(a.id, b.id))
        }
        fun revision() = runs.run(op, run.runId).revision
        /** Verschiebt alle Planzeiten um [seconds] (manuelle Planänderung auf der gelesenen Revision). */
        fun shift(seconds: Long, expected: Long = revision()): List<PlanStop> {
            val moved = runs.currentPlan(op, run.runId).stops.map { it.copy(plannedAt = it.plannedAt.plusSeconds(seconds)) }
            runs.appendRevision(op, run.runId, expected, moved, RevisionCause.MANUAL_CHANGE, emptyList())
            return moved
        }
    }

    /** Testclient des Änderungsstroms: liest den Strom, wendet Einträge über den Tracker an und holt bei Lücken den Snapshot. */
    private class Client(val feed: PlanChanges, val ctx: RequestContext, val channel: PlanChannel) {
        val tracker = RevisionTracker()
        var cursor = 0L
        var snapshots = 0
        val applied = mutableListOf<PlanChangeEntry>()

        fun start() { resync() }

        fun resync() {
            snapshots++
            when (val s = feed.snapshot(ctx, channel)) {
                is PassengerPlanSnapshot -> { cursor = s.cursor; s.bookings.forEach { tracker.resync(it.bookingId, it.sequence) } }
                is DriverPlanSnapshot -> { cursor = s.cursor; s.runs.forEach { tracker.resync(it.runId, it.revision) } }
            }
        }

        fun receive(e: PlanChangeEntry) {
            val decisions = when (e) {
                is PassengerPlanChange -> e.bookings.map { tracker.offer(it.bookingId, it.sequence, it.sequence - 1) }
                is DriverPlanChange -> listOf(tracker.offer(e.runId, e.revision, e.previousRevision))
            }
            if (decisions.any { it == SyncDecision.NEEDS_SNAPSHOT }) resync() else if (decisions.any { it == SyncDecision.APPLY }) applied += e
        }

        /** Liest den Strom ab dem Cursor und verarbeitet die Einträge. */
        fun poll(): List<PlanChangeEntry> {
            val f = feed.feed(ctx, channel, cursor, 100)
            f.items.forEach { receive(it) }
            cursor = maxOf(cursor, f.nextCursor)
            return f.items
        }
    }

    private fun code(block: () -> Unit) = assertThrows<ProblemException> { block() }

    @Test
    fun veraltete_revision_liefert_412_mit_aktuellem_stand() {
        val s = Scene(); val current = s.revision()
        val before = cf.count("SELECT count(*) FROM taktovia.plan_change WHERE run_id = ?", s.run.runId)
        val stale = code { runs.appendRevision(s.op, s.run.runId, current - 1, runs.currentPlan(s.op, s.run.runId).stops, RevisionCause.MANUAL_CHANGE) }
        assertEquals(ErrorCode.REVISION_MISMATCH, stale.code); assertEquals(412, stale.code.status)
        assertEquals("\"$current\"", stale.headers["ETag"])                                  // aktueller Stand für die Synchronisation
        assertTrue(stale.detail!!.contains(current.toString()))
        assertEquals(current, s.revision()); assertEquals(before, cf.count("SELECT count(*) FROM taktovia.plan_change WHERE run_id = ?", s.run.runId))   // nichts gespeichert
        // ein Client mit veraltetem Stand wird im Strom erkannt: sein Stand liegt hinter previous_revision, der Tracker verlangt den Snapshot
        val driver = Client(feed, s.driver, PlanChannel.DRIVER); driver.start()
        s.shift(60); s.shift(60)
        val items = feed.feed(s.driver, PlanChannel.DRIVER, driver.cursor, 100).items.map { it as DriverPlanChange }
        assertEquals(listOf(current + 1, current + 2), items.map { it.revision })
        assertEquals(SyncDecision.NEEDS_SNAPSHOT, RevisionTracker().also { it.resync(s.run.runId, current) }.offer(s.run.runId, current + 2, current + 1))
    }

    @Test
    fun ungeordnete_und_doppelte_events_setzen_nie_zurueck() {
        val key = UUID.randomUUID(); val t = RevisionTracker()
        t.resync(key, 6)
        assertEquals(SyncDecision.NEEDS_SNAPSHOT, t.offer(key, 8, 7))   // Revision 8 vor 7: Lücke, nicht übernehmen
        assertEquals(6L, t.known(key))
        t.resync(key, 8)                                              // Snapshot liefert Stand 8
        assertEquals(SyncDecision.IGNORE, t.offer(key, 7, 6)); assertEquals(8L, t.known(key))   // der Nachzügler setzt nie zurück
        repeat(3) { assertEquals(SyncDecision.IGNORE, t.offer(key, 8, 7)) }                      // dreifach zugestellte 8
        assertEquals(8L, t.known(key))
        t.resync(key, 5); assertEquals(8L, t.known(key))              // ein älterer Snapshot ändert nichts
        // ohne Vorwissen: nur die erste Revision (previous = 0) wird übernommen, alles andere verlangt den Snapshot
        val fresh = RevisionTracker()
        assertEquals(SyncDecision.NEEDS_SNAPSHOT, fresh.offer(key, 8, 7)); assertNull(fresh.known(key))
        assertEquals(SyncDecision.APPLY, fresh.offer(key, 1, 0)); assertEquals(SyncDecision.APPLY, fresh.offer(key, 2, 1))
        // dasselbe über den echten Strom: gemischte, doppelte und vertauschte Zustellung ergibt denselben Endstand
        val s = Scene(); val client = Client(feed, s.pa.ctx, PlanChannel.PASSENGER); client.start()
        val known0 = client.tracker.known(s.a.id)!!
        s.shift(60); s.shift(120); s.shift(180)
        val items = feed.feed(s.pa.ctx, PlanChannel.PASSENGER, client.cursor, 100).items
        assertEquals(3, items.size)
        listOf(items[2], items[1], items[2], items[0], items[2], items[1], items[0]).forEach { client.receive(it) }
        assertEquals(known0 + 3, client.tracker.known(s.a.id))
        assertTrue(client.snapshots >= 2)                              // die Lücke (Eintrag 3 vor 1) hat einen Snapshot ausgelöst
        val last = (items[2] as PassengerPlanChange).bookings.single().sequence
        assertEquals(last, client.tracker.known(s.a.id))
    }

    @Test
    fun zwei_mandanten_am_selben_strom_getrennt() {
        val x = Scene(); val y = Scene()
        x.shift(300); y.shift(900)
        // je Mandant: Fahrgast und Fahrer sehen nur die eigenen Änderungen
        val xp = feed.feed(x.pa.ctx, PlanChannel.PASSENGER, 0, 200).items.flatMap { (it as PassengerPlanChange).bookings }.map { it.bookingId }.toSet()
        assertEquals(setOf(x.a.id), xp); assertFalse(y.a.id in xp || y.b.id in xp)
        val yd = feed.feed(y.driver, PlanChannel.DRIVER, 0, 200).items.map { it as DriverPlanChange }
        assertTrue(yd.isNotEmpty() && yd.all { it.runId == y.run.runId })
        assertTrue(feed.feed(x.driver, PlanChannel.DRIVER, 0, 200).items.none { (it as DriverPlanChange).runId == y.run.runId })
        // ein Konto des Mandanten X mit der Rolle des Mandanten Y bleibt ohne Daten (RLS): gleicher Aussteller und Subject, anderer Mandant
        val crossTenant = RequestContext(Actor(ActorType.HUMAN, x.pa.ctx.actor.issuer, x.pa.ctx.actor.subject, "sid", true), y.tenant, setOf("passenger"), setOf("rides:read"), emptySet(), UUID.randomUUID())
        assertTrue(feed.feed(crossTenant, PlanChannel.PASSENGER, 0, 200).items.isEmpty())
        val snap = feed.snapshot(crossTenant, PlanChannel.PASSENGER) as PassengerPlanSnapshot
        assertTrue(snap.bookings.isEmpty())
        // fremder Fahrer (anderer Mandant, gleiche Subject-UUID) sieht den Umlauf nicht
        val foreignDriver = RequestContext(Actor(ActorType.HUMAN, x.driver.actor.issuer, x.driver.actor.subject, "sid", true), y.tenant, setOf("driver"), setOf("driver:run"), emptySet(), UUID.randomUUID())
        assertTrue(feed.feed(foreignDriver, PlanChannel.DRIVER, 0, 200).items.isEmpty())
    }

    @Test
    fun planaenderung_erreicht_je_fahrgast_und_fahrer_genau_eine_wirksame_benachrichtigung() {
        val s = Scene()
        val pa = Client(feed, s.pa.ctx, PlanChannel.PASSENGER).also { it.start() }
        val pb = Client(feed, s.pb.ctx, PlanChannel.PASSENGER).also { it.start() }
        val dr = Client(feed, s.driver, PlanChannel.DRIVER).also { it.start() }
        val revBefore = s.revision(); val seqA = pa.tracker.known(s.a.id)!!
        val moved = s.shift(600)
        assertEquals(revBefore + 1, s.revision())                       // jede Routenänderung erzeugt eine neue Revision
        val ea = pa.poll(); val eb = pb.poll(); val ed = dr.poll()
        // Fahrgast A: genau ein Eintrag, nur die eigene Buchung, Zeit um 10 Minuten später
        assertEquals(1, ea.size); val ca = (ea.single() as PassengerPlanChange).bookings.single()
        assertEquals(s.a.id, ca.bookingId); assertEquals(PlanBookingChangeKind.TIME_CHANGED, ca.kind); assertEquals(seqA + 1, ca.sequence)
        assertEquals(ca.pickupBefore!!.plusSeconds(600), ca.pickupAfter)
        assertEquals(moved.first { s.a.id in it.boardings.map { m -> m.bookingId } }.plannedAt, ca.pickupAfter)
        // Fahrgast B erhält nur B, nie A (Kriterium 4)
        assertEquals(1, eb.size); assertEquals(listOf(s.b.id), (eb.single() as PassengerPlanChange).bookings.map { it.bookingId })
        // Fahrer: genau ein Eintrag mit der neuen Revision, lückenlos an die vorige gekettet
        assertEquals(1, ed.size); val d = ed.single() as DriverPlanChange
        assertEquals(revBefore + 1, d.revision); assertEquals(revBefore, d.previousRevision); assertEquals(s.run.runId, d.runId)
        assertEquals(setOf(s.a.id, s.b.id), d.bookingIds.toSet())
        // Clientstand nach Synchronisation: aktuelle Revision
        assertEquals(revBefore + 1, dr.tracker.known(s.run.runId)); assertEquals(seqA + 1, pa.tracker.known(s.a.id))
        // dasselbe Ereignis ein zweites Mal gelesen ändert den Stand nicht und erzeugt keinen weiteren Eintrag
        pa.receive(ea.single()); assertEquals(seqA + 1, pa.tracker.known(s.a.id)); assertEquals(1, pa.applied.size)
        // Outbox: ein Ereignis je Revision mit den Änderungen je Buchung
        val payload = cf.stringOf("SELECT payload::text FROM taktovia.outbox WHERE aggregate_id = ? AND aggregate_revision = ? AND event_type = 'dispatch.run_revised'", s.run.runId, revBefore + 1)
        assertTrue(payload.contains("booking_changes") && payload.contains(s.a.id.toString()) && payload.contains("TIME_CHANGED"))
        assertEquals(1, cf.count("SELECT count(*) FROM taktovia.outbox WHERE aggregate_id = ? AND aggregate_revision = ? AND event_type = 'dispatch.run_revised'", s.run.runId, revBefore + 1))
    }

    @Test
    fun nach_verbindungsabbruch_schliesst_der_snapshot_die_luecke() {
        val s = Scene()
        val client = Client(feed, s.pa.ctx, PlanChannel.PASSENGER).also { it.start() }
        val dr = Client(feed, s.driver, PlanChannel.DRIVER).also { it.start() }
        val known = client.tracker.known(s.a.id)!!; val runKnown = dr.tracker.known(s.run.runId)!!
        s.shift(60); s.shift(60); s.shift(60)                            // Verbindung weg: drei Änderungen werden nicht zugestellt
        // nur die jüngste erreicht den Client (die beiden früheren gingen verloren): Lücke erkannt, Snapshot schließt sie
        val newest = feed.feed(s.pa.ctx, PlanChannel.PASSENGER, 0, 200).items.last()
        client.receive(newest)
        assertEquals(2, client.snapshots)
        assertEquals(known + 3, client.tracker.known(s.a.id))
        val snap = feed.snapshot(s.pa.ctx, PlanChannel.PASSENGER) as PassengerPlanSnapshot
        assertEquals(known + 3, snap.bookings.single { it.bookingId == s.a.id }.sequence)
        assertEquals(s.revision(), (feed.snapshot(s.driver, PlanChannel.DRIVER) as DriverPlanSnapshot).runs.single().revision)
        // der Fahrer-Client liest nach dem Abbruch die Lücke aus dem Strom und bleibt konsistent
        dr.poll(); assertEquals(runKnown + 3, dr.tracker.known(s.run.runId)); assertEquals(s.revision(), dr.tracker.known(s.run.runId))
    }

    @Test
    fun fremder_kanal_und_betriebspersonal_haben_keinen_zugriff() {
        val s = Scene()
        assertEquals(ErrorCode.FORBIDDEN, code { feed.feed(s.pa.ctx, PlanChannel.DRIVER, 0, 10) }.code)        // Fahrgast am Fahrerkanal
        assertEquals(ErrorCode.FORBIDDEN, code { feed.feed(s.driver, PlanChannel.PASSENGER, 0, 10) }.code)     // Fahrer am Fahrgastkanal
        assertEquals(ErrorCode.FORBIDDEN, code { feed.snapshot(s.op, PlanChannel.PASSENGER) }.code)            // Disposition hat keinen Empfängerkanal
        assertEquals(ErrorCode.FORBIDDEN, code { feed.snapshot(s.op, PlanChannel.DRIVER) }.code)
        // ein Fahrer fremder Schicht desselben Mandanten sieht den Umlauf nicht
        val other = RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/booking", UUID.randomUUID().toString(), "sid-o", true), s.tenant, setOf("driver"), setOf("driver:run"), emptySet(), UUID.randomUUID())
        s.shift(60)
        assertTrue(feed.feed(other, PlanChannel.DRIVER, 0, 200).items.isEmpty())
        assertTrue((feed.snapshot(other, PlanChannel.DRIVER) as DriverPlanSnapshot).runs.isEmpty())
    }

    @Test
    fun gleichzeitige_routenaenderung_und_clientschreibzugriff_genau_einer_gewinnt() {
        val s = Scene(); val expected = s.revision(); val stops = runs.currentPlan(s.op, s.run.runId).stops
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        try {
            val tasks = (1..2).map { n ->
                pool.submit<Result<Long>> {
                    start.await()
                    runCatching { runs.appendRevision(s.op, s.run.runId, expected, stops.map { it.copy(plannedAt = it.plannedAt.plusSeconds(60L * n)) }, RevisionCause.MANUAL_CHANGE).revision }
                }
            }
            start.countDown()
            val results = tasks.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            val loser = results.single { it.isFailure }.exceptionOrNull() as ProblemException
            assertEquals(ErrorCode.REVISION_MISMATCH, loser.code); assertEquals("\"${expected + 1}\"", loser.headers["ETag"])
            assertEquals(expected + 1, s.revision())
            assertEquals(1, cf.count("SELECT count(*) FROM taktovia.plan_change WHERE run_id = ? AND run_revision = ?", s.run.runId, expected + 1))
        } finally { pool.shutdownNow() }
    }
}
