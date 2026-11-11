package de.taktovia.servicegeo

import de.taktovia.platform.context.RequestContext
import de.taktovia.servicegeo.GeoTestSupport.square
import de.taktovia.servicegeo.api.CreateServiceArea
import de.taktovia.servicegeo.api.CreateStop
import de.taktovia.servicegeo.api.GeoPoint
import de.taktovia.servicegeo.api.PublishRelationRule
import de.taktovia.servicegeo.api.RelationDecision
import de.taktovia.servicegeo.api.RelationEnd
import de.taktovia.servicegeo.api.RelationRejection
import de.taktovia.servicegeo.api.ReviseStop
import de.taktovia.servicegeo.api.ZoneRole
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** WP-M2-06 / LH-6.9-GEO-003 gegen die echte Datenbank unter der Runtime-Rolle: Wahrheitstabelle der Relationsprüfung nach Zonen und Zielpunkten. */
class RelationRuleTest {
    private val fx = fixture
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private val now = Instant.parse("2026-06-01T10:00:00Z")
    private val acc = mapOf("schema_version" to "1.0", "step_free" to "YES", "wheelchair_boarding" to "UNVERIFIED")

    /** Dienst mit Startzone A (13.0/51.0) und Zielzone B (14.0/51.0), Haltepunkten in der Zielzone und einem Start-Haltepunkt. */
    private inner class Scenario(zones: Boolean = true) {
        val w = fx.world(); val ctx: RequestContext = GeoTestSupport.ctx(fx, w); val svc = GeoTestSupport.service(fx, w, ctx)
        val originPoint = GeoPoint(13.05, 51.05); val destPoint = GeoPoint(14.05, 51.05)
        val outsidePoint = GeoPoint(20.0, 40.0)
        val origin = if (zones) fx.geo.areas.create(ctx, CreateServiceArea(svc, square(13.0, 51.0), t0, null, ZoneRole.ORIGIN)) else null
        val dest = if (zones) fx.geo.areas.create(ctx, CreateServiceArea(svc, square(14.0, 51.0), t0, null, ZoneRole.DESTINATION)) else null
        val stopB1 = stop(14.02, 51.02, "Ziel 1"); val stopB2 = stop(14.05, 51.05, "Ziel 2"); val stopB3 = stop(14.08, 51.08, "Ziel 3"); val stopBoundary = stop(14.0, 51.05, "Grenze")
        val stopA = stop(13.02, 51.02, "Start")
        fun stop(lon: Double, lat: Double, name: String) = fx.geo.stops.create(ctx, CreateStop(null, name, mapOf("latitude" to lat, "longitude" to lon), acc, listOf("AREA_ON_DEMAND"), true, t0, null)).stopId
        fun rule(revision: Long, stops: List<UUID>, from: Instant = t0, to: Instant? = null) =
            fx.geo.relations.publish(ctx, svc, revision, PublishRelationRule(stops.map { it.toString() }, from, to, "Zielpunktregel"))
        fun check(from: GeoPoint, to: RelationEnd, at: Instant = now): RelationDecision = fx.geo.relations.check(ctx, svc, from, to, at)
        fun toPoint(p: GeoPoint) = RelationEnd(point = p)
        fun toStop(id: UUID) = RelationEnd(stopId = id)
    }

    private fun assertRejected(d: RelationDecision, reason: RelationRejection) { assertEquals(false, d.admissible, d.toString()); assertEquals(reason, d.reason); assertTrue(d.detail != null && d.detail!!.isNotBlank()) }

    @Test
    fun zulaessige_relation_wird_angenommen() {
        val s = Scenario()
        // Start in der Startzone, Ziel in der Zielzone, keine Zielpunktmenge: zulässig
        assertEquals(RelationDecision(true, null, null), s.check(s.originPoint, s.toPoint(s.destPoint)))
        // Punkt exakt auf der Zonengrenze (Rand und Ecke) zählt als innerhalb
        assertEquals(true, s.check(s.originPoint, s.toPoint(GeoPoint(14.0, 51.05))).admissible)
        assertEquals(true, s.check(GeoPoint(13.0, 51.0), s.toPoint(GeoPoint(14.1, 51.1))).admissible)
        assertEquals(true, s.check(s.originPoint, s.toStop(s.stopBoundary)).admissible)
        // mit Zielpunktmenge: Ziel an einem erlaubten Zielpunkt ist zulässig
        s.rule(0, listOf(s.stopB1, s.stopB2, s.stopBoundary))
        assertEquals(RelationDecision(true, null, null), s.check(s.originPoint, s.toStop(s.stopB1)))
        assertEquals(RelationDecision(true, null, null), s.check(s.originPoint, s.toStop(s.stopB2)))
        // Zonenrolle BOTH wirkt für Start und Ziel
        val both = fx.world(); val bctx = GeoTestSupport.ctx(fx, both); val bsvc = GeoTestSupport.service(fx, both, bctx)
        fx.geo.areas.create(bctx, CreateServiceArea(bsvc, square(13.0, 51.0), t0, null))
        assertEquals(true, fx.geo.relations.check(bctx, bsvc, GeoPoint(13.02, 51.02), RelationEnd(point = GeoPoint(13.08, 51.08)), now).admissible)
    }

    @Test
    fun nicht_erlaubtes_ziel_wird_abgelehnt() {
        val s = Scenario()
        s.rule(0, listOf(s.stopB1))
        // Ziel in der Zielzone, aber nicht in der Zielpunktmenge: Regelverbot (Haltepunkt und freie Koordinate)
        assertRejected(s.check(s.originPoint, s.toStop(s.stopB2)), RelationRejection.RULE_FORBIDDEN)
        assertRejected(s.check(s.originPoint, s.toPoint(s.destPoint)), RelationRejection.RULE_FORBIDDEN)
        // Ziel außerhalb der Zielzone: Gebiet
        assertRejected(s.check(s.originPoint, s.toPoint(s.outsidePoint)), RelationRejection.OUTSIDE_AREA)
        assertRejected(s.check(s.originPoint, s.toPoint(GeoPoint(13.06, 51.06))), RelationRejection.OUTSIDE_AREA)   // in der Startzone, aber nicht in der Zielzone
    }

    @Test
    fun start_ausserhalb_startzone_abgelehnt() {
        val s = Scenario()
        assertRejected(s.check(s.outsidePoint, s.toPoint(s.destPoint)), RelationRejection.OUTSIDE_AREA)
        assertRejected(s.check(GeoPoint(14.05, 51.05), s.toPoint(s.destPoint)), RelationRejection.OUTSIDE_AREA)   // Zielzone ist keine Startzone
        // ohne Zonen (kein Bediengebiet): nichts zulässig
        val empty = Scenario(zones = false)
        assertRejected(empty.check(empty.originPoint, empty.toPoint(empty.destPoint)), RelationRejection.OUTSIDE_AREA)
    }

    @Test
    fun umkehrrichtung_wird_unabhaengig_bewertet() {
        val s = Scenario()
        assertEquals(true, s.check(s.originPoint, s.toPoint(s.destPoint)).admissible)
        // dieselbe Relation umgekehrt: Start liegt nur in der Zielzone, Ziel nur in der Startzone: nach den eigenen Zonenregeln abgelehnt
        assertRejected(s.check(s.destPoint, s.toPoint(s.originPoint)), RelationRejection.OUTSIDE_AREA)
        // die Rückrichtung kann erlaubt sein, obwohl die Hinrichtung abgelehnt wird: Zonen mit vertauschten Rollen in einem zweiten Dienst
        val back = Scenario(zones = false)
        fx.geo.areas.create(back.ctx, CreateServiceArea(back.svc, square(13.0, 51.0), t0, null, ZoneRole.DESTINATION))
        fx.geo.areas.create(back.ctx, CreateServiceArea(back.svc, square(14.0, 51.0), t0, null, ZoneRole.ORIGIN))
        assertEquals(true, back.check(back.destPoint, back.toPoint(back.originPoint)).admissible)
        assertRejected(back.check(back.originPoint, back.toPoint(back.destPoint)), RelationRejection.OUTSIDE_AREA)
    }

    @Test
    fun abgelaufene_regel_wird_nicht_angewendet() {
        val s = Scenario()
        val rule = s.rule(0, listOf(s.stopB1), from = Instant.parse("2026-03-01T00:00:00Z"), to = Instant.parse("2026-04-01T00:00:00Z"))
        assertEquals(1, rule.revision)
        val during = Instant.parse("2026-03-15T00:00:00Z")
        assertRejected(s.check(s.originPoint, s.toStop(s.stopB2), during), RelationRejection.RULE_FORBIDDEN)
        assertEquals(true, s.check(s.originPoint, s.toStop(s.stopB2), Instant.parse("2026-04-01T00:00:00Z")).admissible, "abgelaufen (rechts offen): keine Einschränkung mehr")
        assertEquals(true, s.check(s.originPoint, s.toStop(s.stopB2), Instant.parse("2026-02-28T23:59:59Z")).admissible, "noch nicht gültig: wirkt nicht")
        assertEquals(false, s.check(s.originPoint, s.toStop(s.stopB2), Instant.parse("2026-03-01T00:00:00Z")).admissible, "links geschlossen: ab valid_from wirksam")
        // eine abgelaufene Zone gilt nicht mehr: die Relation ist dann nicht zulässig (Gebiet)
        val z = Scenario(zones = false)
        fx.geo.areas.create(z.ctx, CreateServiceArea(z.svc, square(13.0, 51.0), t0, Instant.parse("2026-02-01T00:00:00Z"), ZoneRole.ORIGIN))
        fx.geo.areas.create(z.ctx, CreateServiceArea(z.svc, square(14.0, 51.0), t0, null, ZoneRole.DESTINATION))
        assertEquals(true, z.check(z.originPoint, z.toPoint(z.destPoint), Instant.parse("2026-01-15T00:00:00Z")).admissible)
        assertRejected(z.check(z.originPoint, z.toPoint(z.destPoint), Instant.parse("2026-02-01T00:00:00Z")), RelationRejection.OUTSIDE_AREA)
    }

    @Test
    fun zielpunkt_auf_inaktivem_haltepunkt_nicht_zulaessig() {
        val s = Scenario()
        s.rule(0, listOf(s.stopB1, s.stopB2))
        assertEquals(true, s.check(s.originPoint, s.toStop(s.stopB1)).admissible)
        val off = Instant.parse("2026-07-01T00:00:00Z")
        fx.geo.stops.revise(s.ctx, s.stopB1, 1, ReviseStop("Ziel 1", mapOf("latitude" to 51.02, "longitude" to 14.02), acc, listOf("AREA_ON_DEMAND"), false, off, null))
        assertEquals(true, s.check(s.originPoint, s.toStop(s.stopB1), off.minusSeconds(1)).admissible, "vor der Deaktivierung zulässig")
        assertRejected(s.check(s.originPoint, s.toStop(s.stopB1), off), RelationRejection.RULE_FORBIDDEN)
        assertEquals(true, s.check(s.originPoint, s.toStop(s.stopB2), off).admissible, "andere Zielpunkte bleiben zulässig")
        // auch ohne Zielpunktmenge ist ein inaktiver oder unbekannter Haltepunkt kein zulässiges Ziel
        val plain = Scenario()
        val inactive = plain.stopB1
        fx.geo.stops.revise(plain.ctx, inactive, 1, ReviseStop("Ziel 1", mapOf("latitude" to 51.02, "longitude" to 14.02), acc, listOf("AREA_ON_DEMAND"), false, off, null))
        assertRejected(plain.check(plain.originPoint, plain.toStop(inactive), off), RelationRejection.RULE_FORBIDDEN)
        assertRejected(plain.check(plain.originPoint, plain.toStop(UUID.randomUUID()), off), RelationRejection.RULE_FORBIDDEN)
    }

    @Test
    fun ablehnung_traegt_katalog_grund() {
        val s = Scenario()
        s.rule(0, listOf(s.stopB1))
        // genau ein Grund, deterministische Priorität: Gebiet vor Regelverbot (Ziel außerhalb der Zone und nicht in der Menge: nur Gebiet)
        val both = s.check(s.originPoint, s.toStop(s.stopA))   // Start-Haltepunkt als Ziel: weder in der Zielzone noch in der Menge
        assertRejected(both, RelationRejection.OUTSIDE_AREA)
        assertRejected(s.check(s.originPoint, s.toStop(s.stopB3)), RelationRejection.RULE_FORBIDDEN)
        // dieselbe Anfrage auf demselben Datenstand ergibt denselben Grund
        repeat(5) { assertEquals(both, s.check(s.originPoint, s.toStop(s.stopA))) }
        // Start außerhalb hat Vorrang vor jedem Zielgrund
        assertRejected(s.check(s.outsidePoint, s.toStop(s.stopB3)), RelationRejection.OUTSIDE_AREA)
        // der Katalog kennt genau die beiden Gründe dieses Bausteins
        assertEquals(setOf("OUTSIDE_AREA", "RULE_FORBIDDEN"), RelationRejection.entries.map { it.name }.toSet())
    }

    @Test
    fun regelaenderung_parallel_zur_pruefung_alte_oder_neue_version() {
        val s = Scenario()
        s.rule(0, listOf(s.stopB1))                                   // Version 1: nur Ziel 1 erlaubt
        val switchAt = Instant.now().minusSeconds(5)                  // Version 2 gilt ab hier; wirksam sofort
        val stop = AtomicBoolean(false); val pool = Executors.newFixedThreadPool(4)
        try {
            val readers = (1..3).map {
                pool.submit<List<RelationDecision>> {
                    val seen = mutableListOf<RelationDecision>()
                    // Ziel 3 steht in keiner Version: eine halbe Umstellung (alte geschlossen, neue unsichtbar) hieße „keine Einschränkung“ und ließe es zu
                    while (!stop.get()) seen += s.check(s.originPoint, s.toStop(s.stopB3), Instant.now())
                    seen
                }
            }
            Thread.sleep(150)
            s.rule(1, listOf(s.stopB2), from = switchAt)             // schließt Version 1 und legt Version 2 an: eine Transaktion
            Thread.sleep(150); stop.set(true)
            val all = readers.flatMap { it.get(30, TimeUnit.SECONDS) }
            assertTrue(all.size > 10)
            all.forEach { assertRejected(it, RelationRejection.RULE_FORBIDDEN) }
            // nach der Änderung gilt vollständig die neue Version
            assertEquals(true, s.check(s.originPoint, s.toStop(s.stopB2), Instant.now()).admissible)
            assertRejected(s.check(s.originPoint, s.toStop(s.stopB1), Instant.now()), RelationRejection.RULE_FORBIDDEN)
        } finally { stop.set(true); pool.shutdownNow() }
    }

    companion object {
        private val fixture by lazy { StructureFixture() }
        @JvmStatic @AfterAll fun close() { fixture.close() }
    }
}
