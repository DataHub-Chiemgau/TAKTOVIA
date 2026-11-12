package de.taktovia.bookingdispatch

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.bookingdispatch.api.BookingDemand
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.HoldOutcome
import de.taktovia.bookingdispatch.api.HoldRideOffer
import de.taktovia.bookingdispatch.api.OfferState
import de.taktovia.bookingdispatch.api.PlaceHold
import de.taktovia.bookingdispatch.api.PromiseCore
import de.taktovia.bookingdispatch.api.RejectionReason
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.TimeWindow
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * WP-M2-22 / LH-6.5-OPT-009: Hold-Befehl, Freigabe und ihre Konkurrenz gegen die echte Datenbank unter der Runtime-Rolle (echte parallele Transaktionen). Der Spike-Befund (a) gilt:
 * Zeilenlock auf `vehicle_run`, danach Angebot und Hold, READ COMMITTED, kein Redis-Lock.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HoldCommandTest {
    private lateinit var fx: OfferObjectsFixture
    private lateinit var api: BookingdispatchApi
    private val json = JsonMapper.builder().build()
    private val promise = PromiseCore("2026-11-02T10:00:00+01:00", "2026-11-02T10:15:00+01:00", "2026-11-02T10:10:00+01:00", "2026-11-02T10:30:00+01:00", 950, "EUR", "std-24h", "standard")

    @BeforeAll fun up() { fx = OfferObjectsFixture(); api = BookingdispatchApi.create(fx.base.ctxTx) }
    @AfterAll fun down() = fx.close()

    /** Hält mit dem im Angebot ausgewiesenen Preis als akzeptiertem Betrag (je nach Personenzahl verschieden). */
    private fun hold(ctx: RequestContext, offerId: UUID, revision: Long = 1, key: String = "hold-" + UUID.randomUUID()) =
        fx.offers.hold(ctx, offerId, HoldRideOffer(revision, key, fx.offers.get(ctx, offerId).quote.payAtPlatformMinor, "EUR"))
    private fun problem(block: () -> Unit) = assertThrows<ProblemException> { block() }
    private fun schema(name: String) = SchemaRegistry.withDialect(Dialects.getDraft202012()) { b ->
        b.schemas(mapOf("https://taktovia.example/schemas/resource-vector.v1.schema.json" to Files.readString(BookingTestDb.repoRoot().resolve("docs/umsetzungsplan/vertraege/schemas/resource-vector.v1.schema.json"))))
    }.getSchema(Files.readString(BookingTestDb.repoRoot().resolve("api/events/$name.v1.schema.json")))
    private fun events(type: String, aggregate: UUID): List<String> = fx.base.eventPayloads(type, aggregate)

    @Test
    fun hold_gegen_veraltete_run_revision_wird_abgewiesen() {
        val w = fx.world(); val ctx = fx.passenger(w.tenant); val run = fx.run(w)
        // Kern: eine Revision, die nicht mehr die aktuelle ist, ergibt Abweisung ohne Hold (nie ungeprüft übernommen)
        fx.adminUpdate("UPDATE taktovia.vehicle_run SET revision = 2 WHERE id = ?", run.runId)
        val cmd = PlaceHold(run.runId, 1, w.serviceId, TimeWindow(Instant.parse("2026-11-02T09:00:00Z"), Instant.parse("2026-11-02T09:25:00Z")), 0, 2, ResourceVector(1, 0), reference = "ref-" + UUID.randomUUID())
        val rejected = api.holds.place(ctx, cmd) as HoldOutcome.Rejected
        assertEquals(RejectionReason.REVISION_CONFLICT, rejected.reason)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.capacity_hold WHERE run_id = ?", run.runId))
        // aktuelle Revision: angenommen
        assertTrue(api.holds.place(ctx, cmd.copy(expectedRunRevision = 2, reference = "ref-" + UUID.randomUUID())) is HoldOutcome.Placed)
        // Angebotspfad: das an Revision 1 gebundene Angebot ist veraltet (409 REQUOTE_REQUIRED), kein Hold
        val w2 = fx.world(); val ctx2 = fx.passenger(w2.tenant); val run2 = fx.run(w2)
        val offer = fx.issue(w2, ctx2, fx.assigning(run2))
        fx.adminUpdate("UPDATE taktovia.vehicle_run SET revision = 2 WHERE id = ?", run2.runId)
        assertEquals(ErrorCode.REQUOTE_REQUIRED, problem { hold(ctx2, offer.id) }.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.capacity_hold WHERE run_id = ?", run2.runId))
        assertEquals(OfferState.OFFERED, fx.offers.get(ctx2, offer.id).state)
    }

    @Test
    fun hold_und_outbox_gemeinsam_oder_gar_nicht() {
        val w = fx.world(); val ctx = fx.passenger(w.tenant); val run = fx.run(w)
        val offer = fx.issue(w, ctx, fx.assigning(run))
        val tenant = w.tenant.toString().replace("-", "_")
        // Fehler nach dem Hold-Insert, vor dem Outbox-Insert: die Transaktion rollt beides zurück
        fx.adminUpdate("CREATE OR REPLACE FUNCTION taktovia.test_fail_hold_event() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'test: Ereignis nicht schreibbar'; END \$\$")
        fx.adminUpdate("CREATE TRIGGER test_fail_hold_$tenant BEFORE INSERT ON taktovia.outbox FOR EACH ROW WHEN (NEW.tenant_id = '${w.tenant}' AND NEW.event_type = 'booking.hold_placed') EXECUTE FUNCTION taktovia.test_fail_hold_event()")
        try {
            assertThrows<Exception> { hold(ctx, offer.id) }
            assertEquals(0, fx.count("SELECT count(*) FROM taktovia.capacity_hold WHERE run_id = ?", run.runId), "kein Hold ohne Ereignis")
            assertEquals(0, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'booking.hold_placed'", w.tenant.value))
            assertEquals(OfferState.OFFERED, fx.offers.get(ctx, offer.id).state); assertEquals(1, fx.offers.get(ctx, offer.id).revision)
        } finally { fx.adminUpdate("DROP TRIGGER test_fail_hold_$tenant ON taktovia.outbox"); fx.adminUpdate("DROP FUNCTION taktovia.test_fail_hold_event()") }   // keine Reste im Schema (Migrationstest)
        // ohne Störung entstehen Hold, Angebotsrevision und Ereignis gemeinsam; Ereignis nach Schema
        val ok = hold(ctx, offer.id)
        assertEquals("HELD", ok.hold.state); assertEquals(2, ok.offerRevision)
        val ev = events("booking.hold_placed", ok.hold.id).single()
        assertEquals(emptyList<String>(), schema("booking.hold_placed").validate(json.readTree(ev)).map { it.message }, ev)
        assertEquals(OfferState.HELD, fx.offers.get(ctx, offer.id).state)
        assertTrue(ok.hold.expiresAt.isAfter(Instant.now()), "Hold-Dauer läuft ab jetzt (Serverzeit), 180 s")
    }

    @Test
    fun kapazitaet_erschoepft_und_fahrzeug_gesperrt_ergeben_409_ohne_hold() {
        val w = fx.world(); val ctx = fx.passenger(w.tenant)
        // Kapazität erschöpft: ein einziger Sitz
        val run = fx.run(w, OfferObjectsFixture.cap(1, 0))
        val a = fx.issue(w, ctx, fx.assigning(run), passengers = 1); val b = fx.issue(w, ctx, fx.assigning(run), passengers = 1)
        assertEquals("HELD", hold(ctx, a.id).hold.state)
        assertEquals(ErrorCode.CAPACITY_UNAVAILABLE, problem { hold(ctx, b.id) }.code)
        assertEquals(OfferState.OFFERED, fx.offers.get(ctx, b.id).state, "das abgewiesene Angebot bleibt unverändert")
        // über den Leseport gesperrtes Fahrzeug (Fixture-Sperre, in M2 der Nachweis zu Kriterium 6): zwischen Angebot und Hold gesperrt
        val run2 = fx.run(w); val c = fx.issue(w, ctx, fx.assigning(run2), passengers = 1)
        fx.adminUpdate("UPDATE taktovia.vehicle SET active_block = true WHERE id = ?", run2.vehicleId)
        assertEquals(ErrorCode.CAPACITY_UNAVAILABLE, problem { hold(ctx, c.id) }.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.capacity_hold WHERE run_id = ?", run2.runId))
        // belegter Rollstuhlplatz
        val run3 = fx.run(w, OfferObjectsFixture.cap(4, 1))
        val wc1 = fx.issue(w, ctx, fx.assigning(run3), passengers = 1, wheelchair = 1); val wc2 = fx.issue(w, ctx, fx.assigning(run3), passengers = 1, wheelchair = 1)
        assertEquals("HELD", hold(ctx, wc1.id).hold.state)
        assertEquals(ErrorCode.CAPACITY_UNAVAILABLE, problem { hold(ctx, wc2.id) }.code)
        // ein Sitz bleibt dabei frei: Dimensionen werden getrennt geprüft
        assertEquals("HELD", hold(ctx, fx.issue(w, ctx, fx.assigning(run3), passengers = 1).id).hold.state)
    }

    @Test
    fun freigabe_idempotent_nur_urheber_und_verbrauchter_hold_unveraendert() {
        val w = fx.world(); val owner = fx.passenger(w.tenant); val run = fx.run(w, OfferObjectsFixture.cap(1, 0))
        val offer = fx.issue(w, owner, fx.assigning(run), passengers = 1)
        val held = hold(owner, offer.id)
        // fremder Akteur und fremder Mandant: 404, der Hold bleibt gehalten
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.offers.release(fx.passenger(w.tenant), offer.id, 2) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.offers.release(fx.passenger(fx.world().tenant), offer.id, 2) }.code)
        assertEquals("HELD", fx.offers.get(owner, offer.id).hold!!.state)
        // veraltete Revision: 412; Freigabe setzt RELEASED, Kapazität ist frei, Angebot DECLINED
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { fx.offers.release(owner, offer.id, 1) }.code)
        val released = fx.offers.release(owner, offer.id, held.offerRevision)
        assertEquals("RELEASED", released.hold.state); assertEquals(false, released.replayed)
        assertEquals(OfferState.DECLINED, fx.offers.get(owner, offer.id).state)
        val next = fx.issue(w, owner, fx.assigning(run), passengers = 1)
        assertEquals("HELD", hold(owner, next.id).hold.state, "freigegebene Kapazität ist wieder buchbar")
        // Wiederholung: idempotent (auch mit veralteter If-Match), kein zweites Ereignis
        val again = fx.offers.release(owner, offer.id, held.offerRevision)
        assertEquals("RELEASED", again.hold.state); assertEquals(true, again.replayed)
        val ev = events("booking.hold_released", held.hold.id).single()
        assertEquals(emptyList<String>(), schema("booking.hold_released").validate(json.readTree(ev)).map { it.message }, ev)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE aggregate_id = ? AND event_type = 'booking.hold_released'", held.hold.id))
        // ein bereits CONSUMED Hold wird durch eine Freigabe nicht verändert (409)
        val booked = api.holds.confirm(owner, fx.offers.get(owner, next.id).hold!!.id, w.serviceId, promise, BookingDemand.of(1, 0, emptySet(), emptyList()))
        val cur = fx.offers.get(owner, next.id)
        assertEquals("CONSUMED", cur.hold!!.state)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { fx.offers.release(owner, next.id, cur.revision) }.code)
        assertEquals("CONSUMED", fx.offers.get(owner, next.id).hold!!.state)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.booking WHERE id = ?", booked.id))
        // Freigabe eines Angebots ohne Hold: 404
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.offers.release(owner, fx.issue(w, owner, fx.assigning(fx.run(w))).id, 1) }.code)
    }

    @Test
    fun hold_dauer_wird_in_p1_nicht_verlaengert() {
        val w = fx.world(); val ctx = fx.passenger(w.tenant); val run = fx.run(w)
        val held = hold(ctx, fx.issue(w, ctx, fx.assigning(run)).id)
        // Kapazität, Revision, Abschnitte und Frist sind unveränderlich, auch für die Administration (Trigger capacity_hold_core_guard)
        for (sql in listOf("UPDATE taktovia.capacity_hold SET expires_at = expires_at + interval '10 minutes' WHERE id = ?", "UPDATE taktovia.capacity_hold SET interval_to = interval_to + 1 WHERE id = ?",
            "UPDATE taktovia.capacity_hold SET capacity_vector = jsonb_set(capacity_vector, '{seats}', '1') WHERE id = ?", "UPDATE taktovia.capacity_hold SET run_revision = 9 WHERE id = ?")) {
            assertThrows<Exception>(sql) { fx.adminUpdate(sql, held.hold.id) }
        }
        // Zustandsautomat auch gegen direktes SQL: aus einem Endzustand gibt es keinen Weg zurück
        fx.adminUpdate("UPDATE taktovia.capacity_hold SET state = 'RELEASED' WHERE id = ?", held.hold.id)
        assertThrows<Exception> { fx.adminUpdate("UPDATE taktovia.capacity_hold SET state = 'HELD' WHERE id = ?", held.hold.id) }
        assertThrows<Exception> { fx.adminUpdate("UPDATE taktovia.capacity_hold SET state = 'CONSUMED' WHERE id = ?", held.hold.id) }
    }

    @Test
    fun freigabe_parallel_zu_confirm_genau_ein_ausgang() {
        val w = fx.world(); val ctx = fx.passenger(w.tenant)
        var released = 0; var consumed = 0
        repeat(16) { round ->
            val run = fx.run(w, OfferObjectsFixture.cap(1, 0))
            val offer = fx.issue(w, ctx, fx.assigning(run), passengers = 1)
            val held = hold(ctx, offer.id)
            val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
            val outcomes = try {
                val rel = pool.submit(Callable { start.await(); runCatching { fx.offers.release(ctx, offer.id, held.offerRevision) } })
                val con = pool.submit(Callable { start.await(); runCatching { api.holds.confirm(ctx, held.hold.id, w.serviceId, promise, BookingDemand.of(1, 0, emptySet(), emptyList())) } })
                start.countDown(); rel.get() to con.get()
            } finally { pool.shutdownNow() }
            val state = fx.stateOf(held.hold.id); val bookings = fx.count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", held.hold.id)
            assertEquals(1, listOf(outcomes.first.isSuccess, outcomes.second.isSuccess).count { it }, "Runde $round: genau ein Ausgang ($state)")
            if (outcomes.first.isSuccess) { assertEquals("RELEASED", state); assertEquals(0, bookings); released++ }
            else {
                assertEquals("CONSUMED", state); assertEquals(1, bookings); consumed++
                assertEquals(ErrorCode.INVALID_STATE_TRANSITION, (outcomes.first.exceptionOrNull() as ProblemException).code)
            }
            if (outcomes.second.isFailure) assertTrue((outcomes.second.exceptionOrNull() as ProblemException).code in setOf(ErrorCode.INVALID_STATE_TRANSITION, ErrorCode.HOLD_EXPIRED))
        }
        assertEquals(16, released + consumed)
    }
}
