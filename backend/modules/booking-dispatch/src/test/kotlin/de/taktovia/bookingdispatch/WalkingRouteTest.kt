package de.taktovia.bookingdispatch

import de.taktovia.booking.ConfirmFixture
import de.taktovia.bookingdispatch.api.EtaStopKeys
import de.taktovia.bookingdispatch.api.RouteInfo
import de.taktovia.bookingdispatch.api.RoutingException
import de.taktovia.bookingdispatch.api.RoutingFailure
import de.taktovia.bookingdispatch.api.RoutingPort
import de.taktovia.bookingdispatch.api.MatrixInfo
import de.taktovia.bookingdispatch.api.WalkManeuver
import de.taktovia.bookingdispatch.api.WalkOrigin
import de.taktovia.bookingdispatch.api.WalkPlan
import de.taktovia.bookingdispatch.api.WalkPlanner
import de.taktovia.bookingdispatch.api.WalkStatus
import de.taktovia.bookingdispatch.api.WalkTarget
import de.taktovia.bookingdispatch.api.WalkingRoutes
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.servicegeo.api.GeoPoint
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/**
 * WP-M2-44 / LH-6.3-PAX-007: Fußwegplanung (rein) und Fußwegservice gegen die echte Datenbank unter der Runtime-Rolle. Der Routing-Worker selbst ist ein **Ersatz** ([FakeWalkRouting], eine
 * Implementierung des `RoutingPort`): die echte Wegberechnung liefert erst ein angeschlossener Worker mit Fußprofil (D-09); geprüft werden hier Planung, Grenzen, Rückfall und Ablage.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WalkingRouteTest {
    private val now = Instant.parse("2026-11-02T07:00:00Z")
    private val offerId = UUID.randomUUID()
    private val target = WalkTarget(UUID.randomUUID(), UUID.randomUUID(), "Altmarkt", GeoPoint(13.7400, 51.0500))
    /** Startort etwa 160 m südlich des Haltepunkts. */
    private val near = GeoPoint(13.7400, 51.0486)

    private fun route(distance: Int, time: Int = 240, geometry: List<Pair<Double, Double>> = listOf(51.0486 to 13.7400, 51.0490 to 13.7400, 51.0490 to 13.7410, 51.0500 to 13.7410, 51.0500 to 13.7400)) =
        RouteInfo("A", "B", time, distance, geometry, "walk", "referenz-2026-11-01", "stub")

    private fun plan(origin: GeoPoint = near, originStop: UUID? = UUID.randomUUID(), maxM: Int = 400, route: RouteInfo? = route(300), reason: String = "ROUTING_UNAVAILABLE") =
        WalkPlanner.plan(WalkOrigin(origin, originStop), target, maxM, route, reason, offerId, now)

    @Test
    fun weg_mit_geometrie_gehzeit_und_nummerierten_schritten() {
        val p = plan() as WalkPlan.Offered; val w = p.snapshot
        assertEquals(WalkStatus.ROUTE, w.status); assertEquals(300, w.distanceM); assertEquals(240, w.durationS); assertEquals("walk", w.profile); assertEquals("referenz-2026-11-01", w.mapVersion)
        assertEquals(5, w.geometry.size); assertEquals(GeoPoint(13.7400, 51.0486), w.geometry.first(), "(Breite, Länge) des Workers wird zu GeoPoint(Länge, Breite)")
        // Schritte: Start, drei Abbiegungen (rechts, links, links), Ankunft; fortlaufend nummeriert, Gehzeit verteilt sich auf die Gesamtzeit
        assertEquals((1..w.steps.size).toList(), w.steps.map { it.seq })
        assertEquals(listOf(WalkManeuver.START, WalkManeuver.TURN_RIGHT, WalkManeuver.TURN_LEFT, WalkManeuver.TURN_LEFT, WalkManeuver.ARRIVE), w.steps.map { it.maneuver })
        assertEquals(240, w.steps.sumOf { it.durationS }); assertTrue(w.steps.first().instruction.startsWith("Gehen Sie Richtung Norden los"), w.steps.first().instruction)
        assertTrue(w.steps.last().instruction.contains("„Altmarkt“")); assertTrue(w.steps.all { it.instruction.isNotBlank() })
    }

    @Test
    fun fussweg_ueber_stop_walk_max_m_wird_nicht_angeboten() {
        // Grenzfall: genau stop_walk_max_m wird angeboten, +1 m nicht
        assertEquals(WalkStatus.ROUTE, (plan(route = route(400)) as WalkPlan.Offered).snapshot.status)
        assertEquals(WalkPlan.TooFar(401, 400), plan(route = route(401)))
        // Rückfall: schon die aufgerundete Luftlinie (10 m) darf die Grenze nicht überschreiten (160 m Luftlinie, Grenze 150)
        assertEquals(WalkPlan.TooFar(160, 150), plan(route = null, maxM = 150, originStop = null))
        assertEquals(WalkStatus.FALLBACK, (plan(route = null, maxM = 160, originStop = null) as WalkPlan.Offered).snapshot.status)
    }

    @Test
    fun routingausfall_liefert_rueckfall() {
        val w = (plan(route = null, reason = "ROUTING_UNAVAILABLE") as WalkPlan.Offered).snapshot
        assertEquals(WalkStatus.FALLBACK, w.status); assertEquals("ROUTING_UNAVAILABLE", w.fallbackReason); assertEquals("Altmarkt", w.pickupStopName)
        assertTrue(w.geometry.isEmpty(), "ohne Routing keine erfundene Geometrie"); assertEquals(160, w.distanceM, "Luftlinie auf 10 m aufgerundet"); assertEquals(128, w.durationS)
        assertEquals(1, w.steps.size); assertTrue(w.steps[0].instruction.contains("konnte nicht berechnet werden") && w.steps[0].instruction.contains("Luftlinie") && w.steps[0].instruction.contains("Altmarkt"))
        // freier Startpunkt: Rückfall mit eigenem Grund, die Koordinate steckt nirgends im Snapshot
        val free = (plan(route = null, originStop = null, reason = "NO_ORIGIN_STOP") as WalkPlan.Offered).snapshot
        assertEquals("NO_ORIGIN_STOP", free.fallbackReason); assertTrue(!free.toString().contains("51.0486"), "die Koordinate des Startpunkts steckt nirgends im Snapshot")
    }

    @Test
    fun start_gleich_haltepunkt_liefert_keinen_weg() {
        for (p in listOf(plan(origin = GeoPoint(13.7400, 51.0500), originStop = UUID.randomUUID(), route = route(0)), plan(originStop = target.stopId, route = null), plan(origin = GeoPoint(13.74002, 51.05002), originStop = null, route = null))) {
            val w = (p as WalkPlan.Offered).snapshot
            assertEquals(WalkStatus.NO_WALK, w.status); assertEquals(0, w.distanceM); assertEquals(0, w.durationS); assertTrue(w.geometry.isEmpty())
            assertTrue(w.steps.single().instruction.contains("bereits am Treffpunkt"))
        }
    }

    // ---- Service gegen die Datenbank ------------------------------------------------------------------------------------------------

    private lateinit var cf: ConfirmFixture
    @BeforeAll fun up() { cf = ConfirmFixture() }
    @AfterAll fun down() = cf.close()

    private class FakeWalkRouting(var result: () -> RouteInfo) : RoutingPort {
        val calls = mutableListOf<Pair<String, String>>()
        override fun route(ctx: RequestContext, originStop: String, destinationStop: String, profile: String) = error("nicht benötigt")
        override fun walk(ctx: RequestContext, originStop: String, destinationStop: String): RouteInfo { calls += originStop to destinationStop; return result() }
        override fun matrix(ctx: RequestContext, originStops: List<String>, destinationStops: List<String>, profile: String): MatrixInfo = error("nicht benötigt")
    }

    @Test
    fun service_plant_ueber_routingport_speichert_snapshot_und_liest_ihn_nur_fuer_den_besitzer() {
        val w = cf.world(); val run = cf.fx.run(w); val me = cf.passenger(w); val other = cf.passenger(w)
        val offer = cf.fx.issue(w, me.ctx, cf.fx.assigning(run))
        val pickup = offer.pickupStop!!; val dropoff = offer.dropoffStop!!
        val routing = FakeWalkRouting { route(250) }
        val keys = EtaStopKeys { _, rev -> "K-$rev" }
        val svc = WalkingRoutes.create(cf.fx.base.ctxTx, cf.fx.base.config.effective, routing, keys, cf.fx.clock)
        // Start = anderer Haltepunkt: Weg über den RoutingPort mit den Schlüsseln beider Haltepunktrevisionen
        val origin = WalkOrigin(GeoPoint(13.74, 51.0486), dropoff.stopId)
        val plan = svc.plan(me.ctx, offer.id, origin) as WalkPlan.Offered
        assertEquals(WalkStatus.ROUTE, plan.snapshot.status); assertEquals(250, plan.snapshot.distanceM)
        assertEquals(1, routing.calls.size); assertEquals("K-${pickup.revisionId}", routing.calls[0].second)
        assertTrue(plan.snapshot.pickupStopName.isNotBlank(), "Name stammt aus der Haltepunktrevision")
        assertNull(svc.forOffer(me.ctx, offer.id), "vor dem Speichern gibt es keinen Snapshot")
        val stored = svc.store(me.ctx, plan.snapshot)
        assertEquals(plan.snapshot.id, stored.id); assertEquals(plan.snapshot.steps, stored.steps); assertEquals(plan.snapshot.geometry, stored.geometry)
        // der erste Snapshot gilt, ein zweiter (anderer Stand) ändert ihn nie
        val again = svc.plan(me.ctx, offer.id, origin) as WalkPlan.Offered
        assertEquals(stored.id, svc.store(me.ctx, again.snapshot).id)
        assertEquals(1, cf.count("SELECT count(*) FROM taktovia.ride_offer_walk WHERE offer_id = ?", offer.id))
        // fremder Fahrgast: weder planen noch speichern (404), Lesen liefert nichts
        assertThrows<ProblemException> { svc.plan(other.ctx, offer.id, origin) }; assertThrows<ProblemException> { svc.store(other.ctx, stored) }
        assertNull(svc.forOffer(other.ctx, offer.id)); assertNotNull(svc.forOffer(me.ctx, offer.id))
        // Routingausfall: gekennzeichneter Rückfall, kein Fehler
        val offer2 = cf.fx.issue(w, me.ctx, cf.fx.assigning(run))
        routing.result = { throw RoutingException(RoutingFailure.PROVIDER_UNAVAILABLE, "Worker nicht erreichbar") }
        val fb = (svc.plan(me.ctx, offer2.id, WalkOrigin(GeoPoint(13.74, 51.0486), offer2.dropoffStop!!.stopId)) as WalkPlan.Offered).snapshot
        assertEquals(WalkStatus.FALLBACK, fb.status); assertEquals("ROUTING_UNAVAILABLE", fb.fallbackReason)
        // ohne angeschlossenen Worker: ebenfalls Rückfall; freier Startpunkt: NO_ORIGIN_STOP
        val none = WalkingRoutes.create(cf.fx.base.ctxTx, cf.fx.base.config.effective, null, null, cf.fx.clock)
        assertEquals("ROUTING_UNAVAILABLE", ((none.plan(me.ctx, offer2.id, WalkOrigin(GeoPoint(13.74, 51.0486), offer2.dropoffStop!!.stopId)) as WalkPlan.Offered).snapshot.fallbackReason))
        assertEquals("NO_ORIGIN_STOP", ((none.plan(me.ctx, offer2.id, WalkOrigin(GeoPoint(13.74, 51.0486), null)) as WalkPlan.Offered).snapshot.fallbackReason))
    }

    @Test
    fun snapshot_ist_unveraenderlich_und_mandantengetrennt() {
        val w = cf.world(); val run = cf.fx.run(w); val me = cf.passenger(w)
        val offer = cf.fx.issue(w, me.ctx, cf.fx.assigning(run))
        val svc = WalkingRoutes.create(cf.fx.base.ctxTx, cf.fx.base.config.effective, null, null, cf.fx.clock)
        val snap = svc.store(me.ctx, (svc.plan(me.ctx, offer.id, WalkOrigin(GeoPoint(13.74, 51.0486), null)) as WalkPlan.Offered).snapshot)
        // die Runtime-Rolle darf nicht ändern oder löschen (kein UPDATE/DELETE-Recht); auch der Fremdschlüssel sichert den Bezug zum Angebot
        assertThrows<Exception> { cf.fx.base.ctxTx.run(me.ctx) { j -> j.update("UPDATE taktovia.ride_offer_walk SET distance_m = 1 WHERE offer_id = ?", offer.id) } }
        assertThrows<Exception> { cf.fx.base.ctxTx.run(me.ctx) { j -> j.update("DELETE FROM taktovia.ride_offer_walk WHERE offer_id = ?", offer.id) } }
        assertEquals(snap.distanceM, svc.forOffer(me.ctx, offer.id)!!.distanceM)
        // ein anderer Mandant sieht die Zeile nicht
        val w2 = cf.world(); val p2 = cf.passenger(w2)
        assertEquals(0, cf.fx.base.ctxTx.run(p2.ctx, readOnly = true) { j -> j.queryForObject("SELECT count(*) FROM taktovia.ride_offer_walk", Int::class.java) })
    }
}
