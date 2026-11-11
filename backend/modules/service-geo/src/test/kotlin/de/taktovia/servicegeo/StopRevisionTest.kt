package de.taktovia.servicegeo

import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.servicegeo.GeoTestSupport.problem
import de.taktovia.servicegeo.api.CreateStop
import de.taktovia.servicegeo.api.GeoPoint
import de.taktovia.servicegeo.api.PublishServiceRevision
import de.taktovia.servicegeo.api.ReviseStop
import de.taktovia.servicegeo.api.StopFilter
import de.taktovia.servicegeo.api.StopView
import de.taktovia.servicegeo.internal.StopAccessibility
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** WP-M2-05 / LH-6.9-GEO-002 gegen die echte Datenbank unter der Runtime-Rolle. */
class StopRevisionTest {
    private val fx = fixture
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private val acc = mapOf("schema_version" to "1.0", "step_free" to "YES", "wheelchair_boarding" to "UNVERIFIED")
    private val point = mapOf("latitude" to 51.05, "longitude" to 13.74)
    private val product = listOf("AREA_ON_DEMAND")

    private fun create(ctx: de.taktovia.platform.context.RequestContext, name: String = "Marktplatz", point: Any? = this.point, accessibility: Any? = acc, products: Any? = product, active: Boolean = true,
                       from: Instant = t0, stopId: UUID? = null) = fx.geo.stops.create(ctx, CreateStop(stopId, name, point, accessibility, products, active, from, null))

    private fun revise(ctx: de.taktovia.platform.context.RequestContext, id: UUID, expected: Long, active: Boolean, from: Instant, name: String = "Marktplatz", products: Any? = product) =
        fx.geo.stops.revise(ctx, id, expected, ReviseStop(name, point, acc, products, active, from, null))

    /** Dienst mit wirksamer Dienstversion (Verkehrsprodukt AREA_ON_DEMAND). */
    private fun serviceWithProduct(w: StructureFixture.World, ctx: de.taktovia.platform.context.RequestContext, product: String = "AREA_ON_DEMAND"): UUID {
        val svc = GeoTestSupport.service(fx, w, ctx)
        fx.geo.serviceRevisions.publish(ctx, svc, 0, PublishServiceRevision(product, listOf(mapOf("weekdays" to listOf("MON"), "from" to "06:00", "to" to "22:00")), emptyList<Any>(), t0, null, "Test"))
        return svc
    }

    @Test
    fun aktive_wirksame_haltestelle_mit_zugelassenem_verkehrsprodukt_wird_angeboten() {
        val w = fx.world(); val ctx = GeoTestSupport.ctx(fx, w); val svc = serviceWithProduct(w, ctx)
        val s = create(ctx)
        assertEquals(1, s.revision); assertTrue(s.active); assertEquals(product, s.trafficProducts); assertEquals(13.74, s.point.longitude); assertEquals("user:${ctx.actor.subject}", s.approvedBy)
        assertEquals(acc, s.accessibility)
        val at = Instant.parse("2026-06-01T10:00:00Z")
        assertEquals(s.stopId, fx.geo.stops.bookable(ctx, svc, s.stopId, at)!!.stopId)
        assertEquals(listOf(s.stopId), fx.geo.stops.list(ctx, StopFilter(serviceId = svc, at = at), 10, null).map { it.stopId })
        // Umkreis: nah dran gefunden, weit weg nicht
        assertEquals(1, fx.geo.stops.list(ctx, StopFilter(serviceId = svc, at = at, near = GeoPoint(13.7401, 51.0501), nearRadiusMeters = 100), 10, null).size)
        assertEquals(0, fx.geo.stops.list(ctx, StopFilter(serviceId = svc, at = at, near = GeoPoint(13.9, 51.3), nearRadiusMeters = 100), 10, null).size)
        // Verkehrsprodukt des Dienstes nicht zugelassen: nicht angeboten
        val other = serviceWithProduct(w, ctx, "LINE_ON_DEMAND")
        assertNull(fx.geo.stops.bookable(ctx, other, s.stopId, at)); assertTrue(fx.geo.stops.list(ctx, StopFilter(serviceId = other, at = at), 10, null).isEmpty())
        // außerhalb der Gültigkeit und Dienst ohne wirksame Dienstversion: nicht angeboten
        assertNull(fx.geo.stops.bookable(ctx, svc, s.stopId, t0.minusSeconds(1)))
        assertNull(fx.geo.stops.bookable(ctx, GeoTestSupport.service(fx, w, ctx), s.stopId, at), "kein Betrieb ohne Dienstversion")
    }

    @Test
    fun inaktive_haltestelle_wird_nicht_angeboten() {
        val w = fx.world(); val ctx = GeoTestSupport.ctx(fx, w); val svc = serviceWithProduct(w, ctx)
        val passenger = fx.context(w.tenant, scopes = setOf("geo:read"))
        val s = create(ctx); val off = Instant.parse("2026-06-01T00:00:00Z")
        val r2 = revise(ctx, s.stopId, 1, active = false, from = off)
        assertEquals(2, r2.revision); assertFalse(r2.active)
        // vor der Deaktivierung gilt die alte Revision, ab ihr nicht mehr
        assertEquals(1L, fx.geo.stops.bookable(ctx, svc, s.stopId, off.minusSeconds(1))!!.revision)
        assertNull(fx.geo.stops.bookable(ctx, svc, s.stopId, off))
        // Haltepunktsuche der Fahrgäste: nie ein inaktiver Haltepunkt, auch nicht über active=false oder ohne Dienst
        val later = off.plusSeconds(3600)
        assertTrue(fx.geo.stops.list(passenger, StopFilter(serviceId = svc, at = later), 10, null).isEmpty())
        assertTrue(fx.geo.stops.list(passenger, StopFilter(at = later, active = false), 10, null).isEmpty(), "active wirkt nur mit Stammdatenrecht")
        assertEquals(listOf(s.stopId), fx.geo.stops.list(ctx, StopFilter(active = false), 10, null).map { it.stopId }, "Pflege sieht die inaktive Revision")
        assertTrue(fx.geo.stops.list(ctx, StopFilter(active = true), 10, null).isEmpty())
        // die alte Revision bleibt unverändert lesbar (Haltepunktsnapshot bestehender Zusagen)
        val old = fx.geo.stops.get(ctx, s.stopId, 1)
        assertTrue(old.active); assertEquals(off, old.validTo); assertEquals(s.revisionId, old.revisionId)
        // Fahrgäste: inaktive Revision ist wie nicht vorhanden
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.stops.get(passenger, s.stopId) }.code)
        // Reaktivierung als weitere Revision
        val r3 = revise(ctx, s.stopId, 2, active = true, from = off.plusSeconds(86_400))
        assertEquals(3, r3.revision); assertNotNull(fx.geo.stops.bookable(ctx, svc, s.stopId, off.plusSeconds(90_000)))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='geo.stop_revision_deactivated' AND aggregate_revision=2", w.tenant.value, s.stopId))
        assertEquals(2, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='geo.stop_revision_effective'", w.tenant.value, s.stopId))
    }

    @Test
    fun deaktivierung_parallel_zur_angebotsbildung_keine_halbe_revision() {
        val w = fx.world(); val ctx = GeoTestSupport.ctx(fx, w); val svc = serviceWithProduct(w, ctx)
        val s = create(ctx, from = Instant.parse("2026-01-01T00:00:00Z"))
        val stop = AtomicBoolean(false); val pool = Executors.newFixedThreadPool(4)
        try {
            val readers = (1..3).map {
                pool.submit<List<StopView?>> {
                    val seen = mutableListOf<StopView?>()
                    while (!stop.get()) seen += fx.geo.stops.bookable(ctx, svc, s.stopId, Instant.now())
                    seen
                }
            }
            Thread.sleep(150)
            revise(ctx, s.stopId, 1, active = false, from = Instant.now())    // Schließen der alten und Anlegen der neuen Revision in einer Transaktion
            Thread.sleep(150); stop.set(true)
            val all = readers.flatMap { it.get(30, TimeUnit.SECONDS) }
            assertTrue(all.size > 10)
            // nie eine halbe Revision: jedes Angebot enthält die alte (aktive) Revision 1 oder den Haltepunkt gar nicht
            all.filterNotNull().forEach { assertEquals(1L, it.revision); assertTrue(it.active) }
            assertTrue(all.any { it != null } && all.any { it == null }, "vor der Deaktivierung angeboten, danach nicht")
            assertNull(fx.geo.stops.bookable(ctx, svc, s.stopId, Instant.now().plusSeconds(1)))
        } finally { stop.set(true); pool.shutdownNow() }
    }

    @Test
    fun koordinate_ausserhalb_wertebereich_und_schemaverletzung_ergeben_422() {
        val w = fx.world(); val ctx = GeoTestSupport.ctx(fx, w)
        fun pointers(block: () -> Unit) = problem(block).violations.map { it.pointer }
        assertTrue("/point/latitude" in pointers { create(ctx, point = mapOf("latitude" to 91.0, "longitude" to 13.0)) })
        assertTrue("/point/longitude" in pointers { create(ctx, point = mapOf("latitude" to 51.0, "longitude" to -181.0)) })
        assertTrue("/point" in pointers { create(ctx, point = null) })
        // accessibility verletzt das Schema stop-accessibility.v1
        assertTrue("/accessibility/step_free" in pointers { create(ctx, accessibility = acc + ("step_free" to "MAYBE")) })
        assertTrue("/accessibility/wheelchair_boarding" in pointers { create(ctx, accessibility = acc - "wheelchair_boarding") })
        assertTrue("/accessibility/rollstuhl" in pointers { create(ctx, accessibility = acc + ("rollstuhl" to "ja")) })
        assertTrue("/accessibility/schema_version" in pointers { create(ctx, accessibility = acc + ("schema_version" to "2.0")) })
        // fehlendes oder ungültiges Verkehrsprodukt
        assertTrue("/traffic_products" in pointers { create(ctx, products = emptyList<String>()) })
        assertTrue("/traffic_products/0" in pointers { create(ctx, products = listOf("klein")) })
        assertTrue("/traffic_products" in pointers { create(ctx, products = null) })
        assertEquals("/valid_to", problem { fx.geo.stops.create(ctx, CreateStop(null, "X", point, acc, product, true, t0, t0)) }.violations.single().pointer)
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.stop_revision WHERE tenant_id=?", w.tenant.value), "nichts gespeichert")
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action=?", w.tenant.value, AuditActions.GEO_STOP_CHANGED))
        // vorgegebener Identifikator, der bereits vergeben ist: 409
        val s = create(ctx, stopId = UUID.randomUUID())
        assertEquals(ErrorCode.CONFLICT, problem { create(ctx, stopId = s.stopId) }.code)
    }

    @Test
    fun paralleler_revisionsschreibversuch_genau_einer_412() {
        val w = fx.world(); val ctx = GeoTestSupport.ctx(fx, w); val s = create(ctx)
        val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        try {
            val results = (1..2).map { n -> pool.submit<Result<StopView>> { go.await(); runCatching { revise(GeoTestSupport.ctx(fx, w), s.stopId, 1, active = true, from = Instant.parse("2026-06-01T00:00:00Z").plusSeconds(n * 60L), name = "Variante $n") } } }
            go.countDown()
            val r = results.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, r.count { it.isSuccess }, r.toString())
            assertEquals(ErrorCode.REVISION_MISMATCH, (r.first { it.isFailure }.exceptionOrNull() as ProblemException).code)
            assertEquals(2, fx.geo.stops.get(ctx, s.stopId).revision)
        } finally { pool.shutdownNow() }
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { revise(ctx, s.stopId, 1, active = true, from = Instant.parse("2026-09-01T00:00:00Z")) }.code)
        // überlappende Wirksamkeit innerhalb derselben Kette: 409
        assertEquals(ErrorCode.CONFLICT, problem { fx.geo.stops.revise(ctx, s.stopId, 2, ReviseStop("X", point, acc, product, true, t0.minusSeconds(86_400), null)) }.code)
    }

    @Test
    fun fremder_mandant_404_ohne_recht_403_audit_approved_by() {
        val a = fx.world(); val b = fx.world(); val ctxA = GeoTestSupport.ctx(fx, a); val ctxB = GeoTestSupport.ctx(fx, b)
        val s = create(ctxA)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.stops.get(ctxB, s.stopId) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { revise(ctxB, s.stopId, 1, active = false, from = t0.plusSeconds(60)) }.code)
        assertTrue(fx.geo.stops.list(ctxB, StopFilter(), 10, null).isEmpty())
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.stop_revision WHERE tenant_id=? AND stop_id=?", b.tenant.value, s.stopId), "unter dem fremden Mandanten nichts angelegt")
        // ohne Stammdatenrecht: 403 (nicht still ignoriert)
        val reader = fx.context(a.tenant, scopes = setOf("geo:read"))
        assertEquals(ErrorCode.FORBIDDEN, problem { create(reader) }.code)
        assertEquals(ErrorCode.FORBIDDEN, problem { revise(reader, s.stopId, 1, active = false, from = t0.plusSeconds(60)) }.code)
        assertEquals(1, fx.geo.stops.get(reader, s.stopId).revision)
        // Audit je Änderung mit Akteur, approved_by gesetzt
        revise(ctxA, s.stopId, 1, active = false, from = t0.plusSeconds(86_400))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action=? AND object_ref=? AND actor_ref=? AND redacted_changes->>'change'='created' AND redacted_changes->>'approved_by'=?",
            a.tenant.value, AuditActions.GEO_STOP_CHANGED, "stop:${s.stopId}", "user:${ctxA.actor.subject}", "user:${ctxA.actor.subject}"))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action=? AND object_ref=? AND redacted_changes->>'change'='deactivated'", a.tenant.value, AuditActions.GEO_STOP_CHANGED, "stop:${s.stopId}"))
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.stop_revision WHERE tenant_id=? AND stop_id=? AND approved_by IS NULL OR approved_by = ''", a.tenant.value, s.stopId))
    }

    @Test
    fun accessibility_pruefung_entspricht_dem_schema_stop_accessibility_v1() {
        val path = Path.of(System.getProperty("taktovia.repoRoot"), "docs/umsetzungsplan/vertraege/schemas/stop-accessibility.v1.schema.json")
        val schema = JsonMapper.builder().build().readTree(Files.readString(path))
        val props = schema.get("properties").propertyNames().toSet()
        assertEquals(props, StopAccessibility.FEATURES.toSet() + setOf("schema_version", "verified_at", "note"), "bekannte Felder")
        assertEquals(schema.get("required").values().map { it.asString() }.toSet(), setOf("schema_version", "step_free", "wheelchair_boarding"))
        assertEquals(schema.get("\$defs").get("Tri").get("enum").values().map { it.asString() }.toSet(), StopAccessibility.TRI)
        schema.get("examples").values().forEach { ex ->
            @Suppress("UNCHECKED_CAST") val map = JsonMapper.builder().build().readValue(ex.toString(), Map::class.java) as Map<String, Any?>
            assertTrue(StopAccessibility.check(map).isEmpty(), "Beispiel des Schemas muss gültig sein: ${StopAccessibility.check(map)}")
        }
    }

    companion object {
        private val fixture by lazy { StructureFixture() }
        @JvmStatic @AfterAll fun close() { fixture.close() }
    }
}
