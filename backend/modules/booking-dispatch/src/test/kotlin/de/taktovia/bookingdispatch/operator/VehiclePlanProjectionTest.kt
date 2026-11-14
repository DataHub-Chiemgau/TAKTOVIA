package de.taktovia.bookingdispatch.operator

import de.taktovia.booking.ConfirmFixture
import de.taktovia.bookingdispatch.api.EtaBasis
import de.taktovia.bookingdispatch.api.PromiseWindowKind
import de.taktovia.bookingdispatch.api.RevisionCause
import de.taktovia.bookingdispatch.api.RunState
import de.taktovia.bookingdispatch.api.VehiclePlans
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WP-M3-38 / LH-6.7-OPS-003: Fahrzeugplan der Leitstelle (Stoppreihenfolge, Sollzeit, Prognose, Zusagefenster, Besetzung) gegen die echte Datenbank unter der Runtime-Rolle.
 * Die Dienstberechtigung (fremder Dienst = 404) entscheidet die Anwendungsschicht vor diesem Port; ihr Nachweis steht in `OperatorVehiclePlanApiTest` (Modul `app`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VehiclePlanProjectionTest {
    private lateinit var cf: ConfirmFixture
    private lateinit var plans: VehiclePlans

    @BeforeAll fun up() { cf = ConfirmFixture(); plans = VehiclePlans.create(cf.fx.base.ctxTx, null, "car", cf.fx.clock) }
    @AfterAll fun down() = cf.close()

    @Test
    fun `stoppreihenfolge_entspricht_aktueller_runrevision_mit_soll_prognose_und_zusagefenster`() {
        val s = VehiclePlanScene(cf)
        val plan = plans.plan(s.op, s.run.vehicleId)
        assertEquals(s.run.runId, plan.runId); assertEquals(2L, plan.planRevision); assertEquals(RunState.ACTIVE, plan.runState)
        assertEquals(listOf(1, 2, 3, 4), plan.stops.map { it.sequence })
        assertEquals(listOf(listOf(s.a.id), listOf(s.b.id), emptyList(), emptyList()), plan.stops.map { it.boardingBookingIds })
        assertEquals(listOf(emptyList(), emptyList(), listOf(s.a.id), listOf(s.b.id)), plan.stops.map { it.alightingBookingIds })
        assertEquals(listOf(s.t0, s.t0.plusSeconds(300), s.t0.plusSeconds(900), s.t0.plusSeconds(1200)), plan.stops.map { it.scheduledAt })
        // Prognose bis zum ETA-Grundmodell: Planzeit aus dem Routing, ausdrücklich als solche gekennzeichnet
        assertTrue(plan.stops.all { it.forecast?.basis == EtaBasis.PLAN_TIME })
        assertEquals("PLAN_TIME", plan.forecastInfo.source)
        // Zusagefenster: Abholfenster am Einstieg, Ankunftsfenster am Ausstieg; getrennt von Sollzeit und Prognose
        val pickupA = plan.stops[0].promiseWindows.single()
        assertEquals(s.a.id, pickupA.bookingId); assertEquals(PromiseWindowKind.PICKUP, pickupA.kind)
        assertTrue(pickupA.latest.isAfter(pickupA.earliest))
        val dropA = plan.stops[2].promiseWindows.single()
        assertEquals(PromiseWindowKind.DROPOFF, dropA.kind); assertEquals(s.a.id, dropA.bookingId)
        // die Revision im Ergebnis ist die aktuelle: nach einer Planänderung gilt die neue Reihenfolge samt Zeiten
        s.api.runs.appendRevision(s.op, s.run.runId, 2, s.stops(120), RevisionCause.MANUAL_CHANGE, emptyList())
        val moved = plans.plan(s.op, s.run.vehicleId)
        assertEquals(3L, moved.planRevision); assertEquals(s.t0.plusSeconds(120), moved.stops.first().scheduledAt)
    }

    @Test
    fun `besetzung_ist_differenz_bestaetigter_ein_und_ausstiege`() {
        val s = VehiclePlanScene(cf, wheelchairA = 1)   // A: ein Rollstuhlplatz (Sitz 0, Rollstuhl 1), B: ein Sitz
        assertEquals(0, plans.plan(s.op, s.run.vehicleId).occupancy.seats); assertEquals(0, plans.plan(s.op, s.run.vehicleId).occupancy.wheelchair)
        s.event("CHECK_IN", s.a.id); s.event("CHECK_IN", s.b.id)
        val both = plans.plan(s.op, s.run.vehicleId).occupancy
        assertEquals(1, both.seats); assertEquals(1, both.wheelchair)   // Dimensionen bleiben getrennt: ein Sitz deckt keinen Rollstuhlplatz
        s.event("CHECK_OUT", s.a.id)
        val afterA = plans.plan(s.op, s.run.vehicleId).occupancy
        assertEquals(1, afterA.seats); assertEquals(0, afterA.wheelchair)
        s.event("CHECK_OUT", s.b.id)
        val none = plans.plan(s.op, s.run.vehicleId).occupancy
        assertEquals(0, none.seats); assertEquals(0, none.wheelchair)
        assertEquals(4, plans.plan(s.op, s.run.vehicleId).capacity!!.seats)
        // Ankunft und Nicht-Erscheinen verändern die Besetzung nie
        s.event("ARRIVAL", null); s.event("NO_SHOW", s.b.id)
        assertEquals(0, plans.plan(s.op, s.run.vehicleId).occupancy.seats)
    }

    @Test
    fun `review_required_nicht_in_besetzung_aber_ausgewiesen`() {
        val s = VehiclePlanScene(cf)
        s.event("CHECK_IN", s.a.id)
        s.event("CHECK_IN", s.b.id, applied = false, reason = "OUTSIDE_STOP")
        val plan = plans.plan(s.op, s.run.vehicleId)
        assertEquals(1, plan.occupancy.seats)   // nur das bestätigte Einsteigen von A
        val review = plan.reviewRequired.single()
        assertEquals("OUTSIDE_STOP", review.reasonCode); assertEquals(s.b.id, review.bookingId); assertEquals("CHECK_IN", review.eventType)
    }

    @Test
    fun `fahrzeug_ohne_run_leerzustand_und_fremder_dienst_oder_mandant_404`() {
        val s = VehiclePlanScene(cf)
        // Leerzustand: Fahrzeug mit Schicht, aber ohne Umlauf
        val emptyVehicle = java.util.UUID.randomUUID()
        cf.fx.adminUpdate("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,active_block,safety_revision) VALUES (?,?,'fed-org-x',CAST(? AS jsonb),false,1)", s.tenant.value, emptyVehicle, de.taktovia.bookingdispatch.OfferObjectsFixture.cap(4, 1))
        val empty = plans.plan(s.op, emptyVehicle)
        assertTrue(empty.empty); assertNull(empty.runId); assertNull(empty.planRevision); assertTrue(empty.stops.isEmpty())
        assertEquals(0, empty.occupancy.seats); assertTrue(empty.reviewRequired.isEmpty())
        // fremder Mandant: das Fahrzeug existiert für ihn nicht, nie ein Hinweis auf die Existenz
        val other = VehiclePlanScene(cf)
        assertEquals(404, assertThrows<ProblemException> { plans.plan(other.op, s.run.vehicleId) }.code.status)
        assertEquals(404, assertThrows<ProblemException> { plans.plan(s.op, java.util.UUID.randomUUID()) }.code.status)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { plans.plan(other.op, s.run.vehicleId) }.code)
    }

    @Test
    fun `fehlende_prognose_wird_ausgewiesen`() {
        val s = VehiclePlanScene(cf)
        s.api.runs.appendRevision(s.op, s.run.runId, 2, s.stops(0, lastLegKnown = false), RevisionCause.MANUAL_CHANGE, emptyList())
        val plan = plans.plan(s.op, s.run.vehicleId)
        // die Fahrzeit zu Stopp 3 stammt nicht aus dem Routing (Abschnitt 2 ohne Dauer): keine Prognose statt einer erfundenen Zeit; die Sollzeit bleibt
        assertNotNull(plan.stops[1].forecast)
        assertNull(plan.stops[2].forecast)
        assertEquals(s.t0.plusSeconds(900), plan.stops[2].scheduledAt)
        assertNotNull(plan.stops[3].forecast)
    }

    @Test
    fun `lesen_waehrend_planaenderungen_liefert_nie_gemischte_revisionen`() {
        val s = VehiclePlanScene(cf)
        val stop = AtomicBoolean(false); val bad = java.util.concurrent.CopyOnWriteArrayList<String>()
        val pool = Executors.newFixedThreadPool(4); val start = CountDownLatch(1)
        val readers = (1..3).map { pool.submit { start.await(); while (!stop.get()) {
            val p = plans.plan(s.op, s.run.vehicleId)
            // Revision n trägt die Verschiebung 60*(n-2) Sekunden für alle vier Stopps
            val shift = 60L * (p.planRevision!! - 2)
            p.stops.forEach { st -> if (st.scheduledAt != s.t0.plusSeconds(shift + listOf(0L, 300L, 900L, 1200L)[st.sequence - 1])) bad += "rev ${p.planRevision} seq ${st.sequence}" }
            if (p.stops.size != 4) bad += "stops ${p.stops.size}"
        } } }
        val writer = pool.submit { start.await(); (1..15).forEach { i -> s.api.runs.appendRevision(s.op, s.run.runId, 1L + i, s.stops(60L * i), RevisionCause.MANUAL_CHANGE, emptyList()) } }
        start.countDown(); writer.get(60, TimeUnit.SECONDS); stop.set(true); readers.forEach { it.get(30, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(emptyList<String>(), bad.toList())
        assertEquals(17L, s.revision())
    }
}
