package de.taktovia.bookingdispatch.etabinding

import de.taktovia.booking.ConfirmFixture
import de.taktovia.bookingdispatch.EtaTestFixture
import de.taktovia.bookingdispatch.RideOfferFixture
import de.taktovia.bookingdispatch.StubRoads
import de.taktovia.bookingdispatch.api.Bookings
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.CandidateGate
import de.taktovia.bookingdispatch.api.ConfirmBooking
import de.taktovia.bookingdispatch.api.DirectTravelTime
import de.taktovia.bookingdispatch.api.DriverRunPlans
import de.taktovia.bookingdispatch.api.EtaApi
import de.taktovia.bookingdispatch.api.EtaBasis
import de.taktovia.bookingdispatch.api.EtaStopKeys
import de.taktovia.bookingdispatch.api.HoldRideOffer
import de.taktovia.bookingdispatch.api.PlanStop
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RevisionCause
import de.taktovia.bookingdispatch.api.RideOfferRequests
import de.taktovia.bookingdispatch.api.StopMovement
import de.taktovia.bookingdispatch.api.VehiclePlans
import de.taktovia.bookingdispatch.operator.VehiclePlanScene
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.readports.PositionReadPort
import de.taktovia.platform.readports.PositionView
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID

/**
 * WP-M4-66 (Kriterium 3, Musterfahrt Ende-zu-Ende): Angebot mit ETA-Version, bestätigte Buchung, Plan; danach zeigen Buchungsdetail (UI-04), Stoppliste des Fahrers und Leitstellenansicht
 * **dieselbe** Prognosezeit des Leseports „ETA je Stopp“ mit Datenstand. Bei Positionsdaten älter als `eta_max_data_age_sec` (oder fehlender Position, fehlendem Modell) steht der Rückfall statt einer
 * scheinbar aktuellen Prognose; die verbindliche Zusage und die Planzeit bleiben unverändert. Gegen die echte Datenbank; Straßendaten mit Geschwindigkeitsattributen sind ein Stub (D-09).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtaConsumersTest {
    private lateinit var cf: ConfirmFixture
    private lateinit var efx: EtaTestFixture

    @BeforeAll fun up() { cf = ConfirmFixture(); efx = EtaTestFixture() }
    @AfterAll fun down() { efx.close(); cf.close() }

    private class FixedPositions(@Volatile var position: PositionView?) : PositionReadPort { override fun latest(ctx: RequestContext, vehicleId: UUID) = position }

    private inner class Ride(approveModel: Boolean = true) {
        val w: RideOfferFixture.World = cf.world().also { if (approveModel) efx.approveModel(it.tenant, 1) }
        val run = cf.fx.run(w)
        val op: RequestContext = cf.operator(w.tenant, setOf(w.serviceId))
        val passenger = cf.passenger(w)
        val positions = FixedPositions(null)
        val keys = EtaStopKeys { _, rev -> if (cf.stringOf("SELECT stop_id::text FROM taktovia.stop_revision WHERE id = ?", rev) == w.stopA.toString()) "H1" else "H2" }
        val api = BookingdispatchApi.create(cf.fx.base.ctxTx, cf.fx.clock, cf.fx.base.outbox)
        val eta: EtaApi = EtaApi.create(cf.fx.base.ctxTx, cf.fx.base.config.effective, StubRoads("v1", mapOf("v1" to StubRoads.table())), cf.fx.clock, api.runs, positions, keys)
        val requests: RideOfferRequests = RideOfferRequests.create(cf.fx.base.ctxTx, cf.fx.base.outbox, cf.fx.base.geo, cf.fx.base.config.effective, cf.fx.clock, DirectTravelTime { _, _ -> 600 },
            cf.fx.assigning(run), quotes = cf.fx.quotes, etaSource = eta.offerTime(keys))
        val bookings: Bookings = Bookings.create(cf.fx.base.ctxTx, cf.fx.base.outbox, cf.fx.quotes, cf.fx.clock, cf.fx.base.config.effective, eta = eta.stopEta)
        val plans: VehiclePlans = VehiclePlans.create(cf.fx.base.ctxTx, eta.stopEta, "car", cf.fx.clock)
        val drivers: DriverRunPlans = DriverRunPlans.create(cf.fx.base.ctxTx, eta.stopEta, "car", cf.fx.clock)
        val t0: Instant = Instant.parse("2026-11-02T09:00:00Z")
        val bookingId: UUID; val offerEta: de.taktovia.bookingdispatch.api.OfferEtaVersion?
        val pickupRev: UUID; val dropoffRev: UUID

        init {
            // Angebot mit ETA-Fenster, Hold, Bestätigung (Musterfahrt von der Angebotsbildung an)
            val offer = requests.request(passenger.ctx, cf.fx.base.command(w, "2026-11-02T10:00:00+01:00", passengers = 1)).offers.single()
            offerEta = offer.eta
            val issued = offer.offer!!
            pickupRev = issued.pickupStop!!.revisionId; dropoffRev = issued.dropoffStop!!.revisionId
            val gross = issued.quote.payAtPlatformMinor
            val hold = cf.fx.offers.hold(passenger.ctx, issued.id, HoldRideOffer(issued.revision, "hold-" + UUID.randomUUID(), gross, "EUR"))
            bookingId = cf.bookings.confirm(passenger.ctx, ConfirmBooking(hold.hold.id, hold.offerRevision, gross, "EUR")).booking.id
            val v = ResourceVector(1, 0)
            api.runs.appendRevision(op, run.runId, 1, listOf(
                PlanStop(0, pickupRev, t0, boardings = listOf(StopMovement(bookingId, v)), legDurationSec = 360, legDistanceM = 3000),
                PlanStop(1, dropoffRev, t0.plusSeconds(390), alightings = listOf(StopMovement(bookingId, v))),
            ), RevisionCause.BOOKING_INSERTED, listOf(bookingId))
        }

        fun position(ageSec: Long): PositionView = PositionView(run.vehicleId, run.shiftId, 13.7, 51.0, cf.fx.clock.instant().minusSeconds(ageSec))
    }

    @Test
    fun musterfahrt_buchungsdetail_stoppliste_und_leitstelle_zeigen_dieselbe_prognose_mit_datenstand() {
        val r = Ride()
        assertNotNull(r.offerEta!!.modelVersion); assertNull(r.offerEta.fallbackReason)
        val pos = r.position(30); r.positions.position = pos
        // Buchungsdetail (UI-04): Zusage und Plan-ETA unverändert, daneben die Prognose mit Datenstand
        val t = r.bookings.tracking(r.passenger.ctx, r.bookingId)
        val f = t.eta!!.forecast!!
        assertEquals(EtaBasis.LIVE_POSITION, f.basis); assertEquals(pos.measuredAt, f.dataStand); assertNull(f.fallbackReason)
        assertEquals(r.offerEta.modelVersion, f.modelVersion, "Prognose und Angebot beruhen auf demselben Modell")
        assertEquals(r.t0, t.eta!!.pickupAt, "verbindliche Plan-ETA unverändert"); assertTrue(t.booking.promise.pickupEarliest <= t.booking.promise.pickupLatest)
        // Stoppliste des Fahrers
        val d = r.drivers.currentPlan(cf.driverCtx(r.w.tenant), r.run.shiftId, r.run.vehicleId)!!
        assertEquals("ETA_MODEL", d.forecast.source); assertEquals(pos.measuredAt, d.forecast.dataStand); assertNull(d.forecast.fallbackReason)
        assertEquals(f.earliest, d.stops[0].forecastAt, "dieselbe Prognosezeit wie im Buchungsdetail"); assertEquals(r.t0, d.stops[0].plannedAt, "Planzeit unverändert")
        // Leitstelle
        val l = r.plans.plan(r.op, r.run.vehicleId)
        assertEquals("ETA_MODEL", l.forecastInfo.source); assertEquals(pos.measuredAt, l.forecastInfo.dataStand); assertNull(l.forecastInfo.fallbackReason)
        assertEquals(f.earliest, l.stops[0].forecast!!.earliest); assertEquals(f.latest, l.stops[0].forecast!!.latest); assertEquals(EtaBasis.LIVE_POSITION, l.stops[0].forecast!!.basis)
        assertEquals(r.t0, l.stops[0].scheduledAt, "Sollzeit getrennt von der Prognose"); assertEquals(r.offerEta.modelVersion, l.forecastInfo.modelVersion)
    }

    @Test
    fun veraltete_positionsdaten_zeigen_rueckfall_statt_scheinbar_aktueller_prognose_zusage_unveraendert() {
        val r = Ride()
        val promiseBefore = r.bookings.tracking(r.passenger.ctx, r.bookingId).booking.promise
        val stale = r.position(300); r.positions.position = stale   // Höchstalter laut Katalog 120 s
        val t = r.bookings.tracking(r.passenger.ctx, r.bookingId); val f = t.eta!!.forecast!!
        assertEquals("POSITION_STALE", f.fallbackReason); assertEquals(EtaBasis.PLAN_TIME, f.basis); assertEquals(stale.measuredAt, f.dataStand)
        assertEquals(r.t0, f.earliest, "Rückfall auf die Planzeit, keine neue Prognose"); assertEquals(promiseBefore, t.booking.promise, "die verbindliche Zusage bleibt unverändert")
        val d = r.drivers.currentPlan(cf.driverCtx(r.w.tenant), r.run.shiftId, r.run.vehicleId)!!
        assertEquals("POSITION_STALE", d.forecast.fallbackReason); assertEquals(r.t0, d.stops[0].forecastAt)
        val l = r.plans.plan(r.op, r.run.vehicleId)
        assertEquals("POSITION_STALE", l.forecastInfo.fallbackReason); assertEquals(EtaBasis.PLAN_TIME, l.stops[0].forecast!!.basis); assertEquals(stale.measuredAt, l.forecastInfo.dataStand)
        // ohne Position: eigener Grund, kein Datenstand
        r.positions.position = null
        assertEquals("POSITION_MISSING", r.plans.plan(r.op, r.run.vehicleId).forecastInfo.fallbackReason); assertNull(r.plans.plan(r.op, r.run.vehicleId).forecastInfo.dataStand)
        assertEquals("POSITION_MISSING", r.bookings.tracking(r.passenger.ctx, r.bookingId).eta!!.forecast!!.fallbackReason)
    }

    @Test
    fun ohne_freigegebenes_modell_weisen_alle_anzeigen_den_grund_aus_und_behalten_die_planzeit() {
        val r = Ride(approveModel = false)
        assertEquals("NO_APPROVED_MODEL", r.offerEta!!.fallbackReason, "das Angebot trägt den Grund")
        r.positions.position = r.position(10)
        val f = r.bookings.tracking(r.passenger.ctx, r.bookingId).eta!!.forecast!!
        assertEquals("NO_APPROVED_MODEL", f.fallbackReason); assertEquals(EtaBasis.PLAN_TIME, f.basis); assertEquals(r.t0, f.earliest); assertNull(f.modelVersion)
        val l = r.plans.plan(r.op, r.run.vehicleId)
        assertEquals("NO_APPROVED_MODEL", l.forecastInfo.fallbackReason); assertEquals(r.t0, l.stops[0].forecast!!.earliest)
        assertEquals("NO_APPROVED_MODEL", r.drivers.currentPlan(cf.driverCtx(r.w.tenant), r.run.shiftId, r.run.vehicleId)!!.forecast.fallbackReason)
    }
}
