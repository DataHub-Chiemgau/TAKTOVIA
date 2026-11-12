package de.taktovia.servicegeo

import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.servicegeo.GeoTestSupport.problem
import de.taktovia.servicegeo.api.CreateStop
import de.taktovia.servicegeo.api.GeoPoint
import de.taktovia.servicegeo.api.PublishRelationRule
import de.taktovia.servicegeo.api.RelationEnd
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

/** WP-M2-06 Pflege der Mengenversionen (Portebene): Revisionen, 422/409/412, Mandantentrennung, Audit mit Grund, Ereignis. */
class RelationRuleApiTest {
    private val fx = fixture
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private val acc = mapOf("schema_version" to "1.0", "step_free" to "YES", "wheelchair_boarding" to "UNVERIFIED")
    private fun stop(ctx: de.taktovia.platform.context.RequestContext) =
        fx.geo.stops.create(ctx, CreateStop(null, "Ziel", mapOf("latitude" to 51.0, "longitude" to 14.0), acc, listOf("AREA_ON_DEMAND"), true, t0, null)).stopId
    private fun cmd(ids: Any?, from: Instant = t0, to: Instant? = null, reason: String = "Zielpunkte festgelegt") = PublishRelationRule(ids, from, to, reason)

    @Test
    fun fremder_mandant_404_ohne_recht_nicht_schreibbar_audit() {
        val a = fx.world(); val b = fx.world(); val ctxA = GeoTestSupport.ctx(fx, a); val ctxB = GeoTestSupport.ctx(fx, b); val svc = GeoTestSupport.service(fx, a, ctxA)
        val s1 = stop(ctxA)
        assertNull(fx.geo.relations.head(ctxA, svc))
        val r1 = fx.geo.relations.publish(ctxA, svc, 0, cmd(listOf(s1.toString())))
        assertEquals(1, r1.revision); assertEquals(listOf(s1), r1.destinationStopIds); assertEquals("Zielpunkte festgelegt", r1.reason)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.relations.head(ctxB, svc) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.relations.publish(ctxB, svc, 0, cmd(emptyList<String>())) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.relations.check(ctxB, svc, GeoPoint(13.0, 51.0), RelationEnd(point = GeoPoint(14.0, 51.0)), t0) }.code)
        // Regel des anderen Mandanten wirkt nicht: ein Zielpunkt des Mandanten A ist für B unbekannt (422), nicht lesbar
        val svcB = GeoTestSupport.service(fx, b, ctxB)
        assertEquals("/destination_stop_ids/0", problem { fx.geo.relations.publish(ctxB, svcB, 0, cmd(listOf(s1.toString()))) }.violations.single().pointer)
        // ohne Stammdatenrecht und ohne Dienst im Dienstscope: wie nicht vorhanden; Lesen mandantenweit
        val reader = fx.context(a.tenant, scopes = setOf("geo:read"))
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.relations.publish(reader, svc, 1, cmd(emptyList<String>(), from = Instant.parse("2027-01-01T00:00:00Z"))) }.code)
        assertEquals(1, fx.geo.relations.head(reader, svc)!!.revision)
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action=? AND object_ref=? AND actor_ref=? AND redacted_changes->>'reason'=?",
            a.tenant.value, AuditActions.GEO_RELATION_RULE_PUBLISHED, "service:$svc", "user:${ctxA.actor.subject}", "Zielpunkte festgelegt"))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='geo.relation_rule_effective'", a.tenant.value, svc))
    }

    @Test
    fun revisionen_422_409_412() {
        val w = fx.world(); val ctx = GeoTestSupport.ctx(fx, w); val svc = GeoTestSupport.service(fx, w, ctx); val s1 = stop(ctx)
        fun pointers(block: () -> Unit) = problem(block).violations.map { it.pointer }
        assertTrue("/destination_stop_ids/0" in pointers { fx.geo.relations.publish(ctx, svc, 0, cmd(listOf("kein-uuid"))) })
        assertTrue("/destination_stop_ids/0" in pointers { fx.geo.relations.publish(ctx, svc, 0, cmd(listOf(UUID.randomUUID().toString()))) })       // unbekannter Haltepunkt
        assertTrue("/destination_stop_ids" in pointers { fx.geo.relations.publish(ctx, svc, 0, cmd(listOf(s1.toString(), s1.toString()))) })
        assertTrue("/destination_stop_ids" in pointers { fx.geo.relations.publish(ctx, svc, 0, cmd(null)) })
        assertTrue("/reason" in pointers { fx.geo.relations.publish(ctx, svc, 0, cmd(emptyList<String>(), reason = "")) })
        assertTrue("/valid_to" in pointers { fx.geo.relations.publish(ctx, svc, 0, cmd(emptyList<String>(), to = t0)) })
        assertNull(fx.geo.relations.head(ctx, svc), "nichts gespeichert")
        fx.geo.relations.publish(ctx, svc, 0, cmd(listOf(s1.toString()), to = Instant.parse("2026-06-01T00:00:00Z")))
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { fx.geo.relations.publish(ctx, svc, 0, cmd(emptyList<String>(), from = Instant.parse("2027-01-01T00:00:00Z"))) }.code)
        assertEquals(ErrorCode.CONFLICT, problem { fx.geo.relations.publish(ctx, svc, 1, cmd(emptyList<String>(), from = Instant.parse("2026-05-01T00:00:00Z"))) }.code)
        // leere Menge ist eine gültige Version (keine Einschränkung); angrenzend zulässig
        assertEquals(2, fx.geo.relations.publish(ctx, svc, 1, cmd(emptyList<String>(), from = Instant.parse("2026-06-01T00:00:00Z"))).revision)
        // parallele Veröffentlichungen mit derselben erwarteten Revision: genau eine gewinnt
        val svc2 = GeoTestSupport.service(fx, w, ctx)
        val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        try {
            val r = (1..2).map { n -> pool.submit<Result<Any>> { go.await(); runCatching { fx.geo.relations.publish(GeoTestSupport.ctx(fx, w), svc2, 0, cmd(listOf(s1.toString()), reason = "Antrag $n")) } } }
            go.countDown()
            val res = r.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, res.count { it.isSuccess }, res.toString())
        } finally { pool.shutdownNow() }
    }

    companion object {
        private val fixture by lazy { StructureFixture() }
        @JvmStatic @AfterAll fun close() { fixture.close() }
    }
}
