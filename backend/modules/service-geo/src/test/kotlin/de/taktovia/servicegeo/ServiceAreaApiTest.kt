package de.taktovia.servicegeo

import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.servicegeo.GeoTestSupport.BOWTIE
import de.taktovia.servicegeo.GeoTestSupport.problem
import de.taktovia.servicegeo.GeoTestSupport.square
import de.taktovia.servicegeo.api.CreateServiceArea
import de.taktovia.servicegeo.api.GeoPoint
import de.taktovia.servicegeo.api.ReviseServiceArea
import de.taktovia.servicegeo.api.ServiceAreaView
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** WP-M2-04 / LH-6.9-GEO-001 gegen die echte Datenbank unter der Runtime-Rolle (Portebene; die HTTP-Ebene prüft `ServiceAreaHttpApiTest` in der Anwendung). */
class ServiceAreaApiTest {
    private val fx = fixture
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun selbstueberschneidendes_polygon_ergibt_422() {
        val w = fx.world(); val ctx = GeoTestSupport.ctx(fx, w); val svc = GeoTestSupport.service(fx, w, ctx)
        val bow = problem { fx.geo.areas.create(ctx, CreateServiceArea(svc, BOWTIE, t0, null)) }
        assertEquals(ErrorCode.VALIDATION_FAILED, bow.code); assertEquals("/area", bow.violations.single().pointer)
        assertTrue(bow.violations.single().message.contains("überschneidet"), bow.violations.single().message)
        // offener Ring: Feldverweis auf den Ring
        val open = square(13.0, 51.0).let { m -> m + ("coordinates" to listOf(listOf(listOf(listOf(0.0, 0.0), listOf(1.0, 0.0), listOf(1.0, 1.0), listOf(0.0, 1.0), listOf(0.0, 0.5))))) }
        val openProblem = problem { fx.geo.areas.create(ctx, CreateServiceArea(svc, open, t0, null)) }
        assertEquals("/area/coordinates/0/0", openProblem.violations.single().pointer)
        // Koordinaten außerhalb von WGS84 (z. B. projiziert) und falscher Typ
        assertEquals("/area/coordinates/0/0/0/0", problem { fx.geo.areas.create(ctx, CreateServiceArea(svc, square(1_500_000.0, 6_700_000.0), t0, null)) }.violations.first().pointer)
        assertEquals("/area/type", problem { fx.geo.areas.create(ctx, CreateServiceArea(svc, mapOf("type" to "Polygon", "coordinates" to emptyList<Any>()), t0, null)) }.violations.first().pointer)
        // Zeitraum: valid_to <= valid_from
        assertEquals("/valid_to", problem { fx.geo.areas.create(ctx, CreateServiceArea(svc, square(13.0, 51.0), t0, t0)) }.violations.single().pointer)
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.service_area WHERE tenant_id=? AND service_id=?", w.tenant.value, svc), "nichts gespeichert")
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action=?", w.tenant.value, AuditActions.GEO_SERVICE_AREA_CHANGED))
    }

    @Test
    fun punkt_im_wirksamen_gebiet_zum_zeitpunkt() {
        val w = fx.world(); val ctx = GeoTestSupport.ctx(fx, w); val svc = GeoTestSupport.service(fx, w, ctx)
        val from = Instant.parse("2026-06-01T00:00:00Z"); val to = Instant.parse("2026-09-01T00:00:00Z")
        val a = fx.geo.areas.create(ctx, CreateServiceArea(svc, square(13.0, 51.0), from, to))
        assertEquals(1, a.revision); assertEquals(a.id, a.revisionId)
        val inside = GeoPoint(13.05, 51.05); val outside = GeoPoint(13.5, 51.5)
        assertEquals(a.revisionId, fx.geo.areas.covering(ctx, svc, inside, from.plusSeconds(60))!!.revisionId)
        assertNull(fx.geo.areas.covering(ctx, svc, outside, from.plusSeconds(60)), "Punkt außerhalb: abgelehnt")
        // links geschlossen, rechts offen
        assertNotNull(fx.geo.areas.covering(ctx, svc, inside, from)); assertNull(fx.geo.areas.covering(ctx, svc, inside, from.minusSeconds(1)))
        assertNotNull(fx.geo.areas.covering(ctx, svc, inside, to.minusSeconds(1))); assertNull(fx.geo.areas.covering(ctx, svc, inside, to))
        // Punkt exakt auf der Grenze (Ecke und Kante) zählt als innerhalb
        assertNotNull(fx.geo.areas.covering(ctx, svc, GeoPoint(13.0, 51.0), from)); assertNotNull(fx.geo.areas.covering(ctx, svc, GeoPoint(13.05, 51.0), from))
        // Bearbeitung: Revision 2 mit anderem Gebiet, Revision 1 bleibt lesbar und für ihre Zeit maßgeblich
        val r2 = fx.geo.areas.revise(ctx, a.id, 1, ReviseServiceArea(square(14.0, 51.0), to, null))
        assertEquals(2, r2.revision); assertEquals(a.id, r2.id); assertTrue(r2.revisionId != a.revisionId)
        assertEquals(1, fx.geo.areas.get(ctx, a.id, 1).revision); assertEquals(2, fx.geo.areas.get(ctx, a.id).revision)
        assertEquals(a.revisionId, fx.geo.areas.covering(ctx, svc, inside, to.minusSeconds(1))!!.revisionId)
        assertNull(fx.geo.areas.covering(ctx, svc, inside, to), "ab dem Beginn der Revision 2 gilt das neue Gebiet")
        assertEquals(r2.revisionId, fx.geo.areas.covering(ctx, svc, GeoPoint(14.05, 51.05), to)!!.revisionId)
        assertEquals(to, fx.geo.areas.get(ctx, a.id, 1).validTo)
        assertEquals(listOf(a.id), fx.geo.areas.list(ctx, svc, 10, null).map { it.id }, "Liste: jüngste Revision je Gebiet")
    }

    @Test
    fun parallele_revisionen_eine_erhaelt_412() {
        val w = fx.world(); val ctx = GeoTestSupport.ctx(fx, w); val svc = GeoTestSupport.service(fx, w, ctx)
        val a = fx.geo.areas.create(ctx, CreateServiceArea(svc, square(13.0, 51.0), t0, null))
        val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        try {
            val results = (1..2).map { n ->
                pool.submit<Result<ServiceAreaView>> { go.await(); runCatching { fx.geo.areas.revise(GeoTestSupport.ctx(fx, w), a.id, 1, ReviseServiceArea(square(13.0 + n, 51.0), t0.plusSeconds(86_400L * n), null)) } }
            }
            go.countDown()
            val r = results.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, r.count { it.isSuccess }, r.toString())
            assertEquals(ErrorCode.REVISION_MISMATCH, (r.first { it.isFailure }.exceptionOrNull() as de.taktovia.platform.problem.ProblemException).code)
            assertEquals(2, fx.geo.areas.get(ctx, a.id).revision)
        } finally { pool.shutdownNow() }
        // veraltete Revision sequenziell: 412; zwei überlappende Veröffentlichungen für denselben Dienst: genau eine gewinnt (409 für die andere)
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { fx.geo.areas.revise(ctx, a.id, 1, ReviseServiceArea(square(20.0, 51.0), t0.plusSeconds(999_999), null)) }.code)
        val svc2 = GeoTestSupport.service(fx, w, ctx)
        val pool2 = Executors.newFixedThreadPool(2); val go2 = CountDownLatch(1)
        try {
            val results = (1..2).map { n -> pool2.submit<Result<ServiceAreaView>> { go2.await(); runCatching { fx.geo.areas.create(GeoTestSupport.ctx(fx, w), CreateServiceArea(svc2, square(30.0 + n, 40.0), t0, null)) } } }
            go2.countDown()
            val r = results.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, r.count { it.isSuccess }, r.toString())
            assertEquals(ErrorCode.CONFLICT, (r.first { it.isFailure }.exceptionOrNull() as de.taktovia.platform.problem.ProblemException).code)
        } finally { pool2.shutdownNow() }
    }

    @Test
    fun fremder_mandant_404_audit_je_aenderung() {
        val a = fx.world(); val b = fx.world(); val ctxA = GeoTestSupport.ctx(fx, a); val ctxB = GeoTestSupport.ctx(fx, b); val svc = GeoTestSupport.service(fx, a, ctxA)
        val area = fx.geo.areas.create(ctxA, CreateServiceArea(svc, square(13.0, 51.0), t0, null))
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.areas.get(ctxB, area.id) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.areas.revise(ctxB, area.id, 1, ReviseServiceArea(square(13.0, 51.0), t0.plusSeconds(1), null)) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.areas.create(ctxB, CreateServiceArea(svc, square(13.0, 51.0), t0, null)) }.code)
        assertTrue(fx.geo.areas.list(ctxB, svc, 10, null).isEmpty()); assertNull(fx.geo.areas.covering(ctxB, svc, GeoPoint(13.05, 51.05), t0.plusSeconds(60)))
        // ohne Stammdatenrecht (kein geo:write) und ohne Dienst im Dienstscope: wie nicht vorhanden (fail-closed); das HTTP-Recht (403) prüft der Rand
        val reader = fx.context(a.tenant, scopes = setOf("geo:read"))
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.areas.create(reader, CreateServiceArea(svc, square(15.0, 51.0), t0, null)) }.code)
        assertEquals(1, fx.geo.areas.get(reader, area.id).revision, "Lesen ist mandantenweit")
        val r2 = fx.geo.areas.revise(ctxA, area.id, 1, ReviseServiceArea(square(13.0, 51.0, 0.2), t0.plusSeconds(86_400), null))
        assertEquals(2, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action=? AND object_ref=? AND actor_ref=?", a.tenant.value,
            AuditActions.GEO_SERVICE_AREA_CHANGED, "service_area:${area.id}", "user:${ctxA.actor.subject}"), "je Änderung ein Auditeintrag")
        assertEquals(2, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='geo.service_area_revision_effective'", a.tenant.value, area.id))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.service_area WHERE tenant_id=? AND area_key=? AND revision=2 AND id=?", a.tenant.value, area.id, r2.revisionId))
    }

    companion object {
        private val fixture by lazy { StructureFixture() }
        @JvmStatic @AfterAll fun close() { fixture.close() }
    }
}
