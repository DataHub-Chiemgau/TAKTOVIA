package de.taktovia.servicegeo

import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.servicegeo.api.CreateService
import de.taktovia.servicegeo.api.PublishServiceRevision
import de.taktovia.servicegeo.api.ServiceRevisionView
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** WP-M2-03 gegen die echte Datenbank unter der Runtime-Rolle: Pflege, Revisionskette, Überlappung, Konkurrenz, Mandantentrennung, Audit mit Grund, Ereignis. */
class ServiceRevisionTest {
    private val fx = fixture
    private val weekly = listOf(mapOf("weekdays" to listOf("MON", "TUE"), "from" to "06:00", "to" to "22:00"))
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    private fun service(w: StructureFixture.World, ctx: de.taktovia.platform.context.RequestContext) =
        fx.geo.structure.createService(ctx, CreateService(w.operator, "Rufbus", "Europe/Berlin")).id

    private fun cmd(from: Instant = t0, to: Instant? = null, reason: String = "Winterfahrplan", product: String = "AREA_ON_DEMAND", w: Any? = weekly) =
        PublishServiceRevision(product, w, emptyList<Any>(), from, to, reason)

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)

    @Test
    fun pflege_lesen_und_revision_kette() {
        val w = fx.world(); val ctx = fx.context(w.tenant); val svc = service(w, ctx)
        assertNull(fx.geo.serviceRevisions.head(ctx, svc), "ohne Betriebszeit keine Version")
        val r1 = fx.geo.serviceRevisions.publish(ctx, svc, 0, cmd())
        assertEquals(1, r1.revision); assertEquals("Europe/Berlin", r1.timezone); assertEquals(weekly, r1.weeklyHours); assertNull(r1.validTo)
        val r2 = fx.geo.serviceRevisions.publish(ctx, svc, 1, cmd(from = Instant.parse("2026-06-01T00:00:00Z"), reason = "Sommerfahrplan"))
        assertEquals(2, r2.revision)
        // Revision 1 bleibt unverändert lesbar, wird aber mit dem Beginn von Revision 2 geschlossen
        val list = fx.geo.serviceRevisions.list(ctx, svc, 10)
        assertEquals(listOf(2L, 1L), list.map { it.revision })
        assertEquals(Instant.parse("2026-06-01T00:00:00Z"), list[1].validTo)
        assertEquals(weekly, list[1].weeklyHours); assertEquals("Winterfahrplan", list[1].reason)
        // wirksame Version je Zeitpunkt (links geschlossen, rechts offen)
        assertEquals(1, fx.geo.serviceRevisions.effective(ctx, svc, Instant.parse("2026-05-31T23:59:59Z"))!!.revision)
        assertEquals(2, fx.geo.serviceRevisions.effective(ctx, svc, Instant.parse("2026-06-01T00:00:00Z"))!!.revision)
        assertNull(fx.geo.serviceRevisions.effective(ctx, svc, Instant.parse("2025-12-31T23:59:59Z")))
    }

    @Test
    fun veraltete_revision_412_ungueltige_eingaben_422_ohne_speicherung() {
        val w = fx.world(); val ctx = fx.context(w.tenant); val svc = service(w, ctx)
        fx.geo.serviceRevisions.publish(ctx, svc, 0, cmd())
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { fx.geo.serviceRevisions.publish(ctx, svc, 0, cmd(from = Instant.parse("2027-01-01T00:00:00Z"))) }.code)
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { fx.geo.serviceRevisions.publish(ctx, svc, 5, cmd(from = Instant.parse("2027-01-01T00:00:00Z"))) }.code)
        val bad = problem { fx.geo.serviceRevisions.publish(ctx, svc, 1, cmd(from = Instant.parse("2027-01-01T00:00:00Z"), product = "klein", reason = "", w = listOf(mapOf("weekdays" to listOf("MON"), "from" to "9", "to" to "22:00")))) }
        assertEquals(ErrorCode.VALIDATION_FAILED, bad.code)
        val pointers = bad.violations.map { it.pointer }
        assertTrue("/traffic_product" in pointers && "/reason" in pointers && "/weekly_hours/0/from" in pointers, pointers.toString())
        assertEquals(1, fx.geo.serviceRevisions.head(ctx, svc)!!.revision, "nichts gespeichert")
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { fx.geo.serviceRevisions.publish(ctx, svc, 1, cmd(from = t0.plusSeconds(10), to = t0.plusSeconds(10))) }.code)
    }

    @Test
    fun ueberlappende_version_wird_abgelehnt_409_ohne_die_kette_zu_veraendern() {
        val w = fx.world(); val ctx = fx.context(w.tenant); val svc = service(w, ctx)
        fx.geo.serviceRevisions.publish(ctx, svc, 0, cmd(from = Instant.parse("2026-06-01T00:00:00Z"), to = Instant.parse("2026-09-01T00:00:00Z")))
        // beginnt vor der vorhandenen und reicht hinein: Überlappung
        assertEquals(ErrorCode.CONFLICT, problem { fx.geo.serviceRevisions.publish(ctx, svc, 1, cmd(from = Instant.parse("2026-05-01T00:00:00Z"), to = Instant.parse("2026-07-01T00:00:00Z"))) }.code)
        // beginnt vor der vorhandenen und ist offen: Überlappung
        assertEquals(ErrorCode.CONFLICT, problem { fx.geo.serviceRevisions.publish(ctx, svc, 1, cmd(from = Instant.parse("2026-05-01T00:00:00Z"))) }.code)
        assertEquals(1, fx.geo.serviceRevisions.list(ctx, svc, 10).size)
        // angrenzend ist zulässig
        fx.geo.serviceRevisions.publish(ctx, svc, 1, cmd(from = Instant.parse("2026-09-01T00:00:00Z")))
    }

    @Test
    fun zwei_parallele_aktivierungen_desselben_zeitraums_genau_eine_gewinnt() {
        val w = fx.world(); val ctx = fx.context(w.tenant); val svc = service(w, ctx)
        val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        try {
            val futures = (1..2).map { n ->
                pool.submit<Result<ServiceRevisionView>> {
                    go.await()
                    runCatching { fx.geo.serviceRevisions.publish(fx.context(w.tenant), svc, 0, cmd(from = Instant.parse("2026-03-01T00:00:00Z"), to = Instant.parse("2026-09-01T00:00:00Z"), reason = "Antrag $n")) }
                }
            }
            go.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess }, results.toString())
            val loser = results.first { it.isFailure }.exceptionOrNull() as ProblemException
            assertEquals(ErrorCode.REVISION_MISMATCH, loser.code)
            assertEquals(1, fx.geo.serviceRevisions.list(ctx, svc, 10).size)
        } finally { pool.shutdownNow() }
    }

    @Test
    fun fremder_mandant_404_ohne_dienstrecht_404_audit_mit_akteur_und_grund_ereignis() {
        val a = fx.world(); val b = fx.world(); val ctxA = fx.context(a.tenant); val ctxB = fx.context(b.tenant); val svc = service(a, ctxA)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.serviceRevisions.head(ctxB, svc) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.serviceRevisions.publish(ctxB, svc, 0, cmd()) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.availability.at(ctxB, svc, t0) }.code)
        // ohne Verwaltungsscope und ohne Dienst im Dienstscope: wie nicht vorhanden
        val limited = fx.context(a.tenant, scopes = setOf("services:read"))
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.serviceRevisions.publish(limited, svc, 0, cmd()) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.serviceRevisions.head(limited, svc) }.code)
        // mit Dienst im Scope: lesbar, aber ohne Verwaltungsscope nicht schreibbar (Mitgliedschaft fehlt im Test, Momentaufnahme des Kontexts entscheidet)
        val scoped = fx.context(a.tenant, services = setOf(svc), scopes = setOf("services:read"))
        fx.geo.serviceRevisions.publish(ctxA, svc, 0, cmd(reason = "Erste Fassung wegen Pilotstart"))
        assertEquals(1, fx.geo.serviceRevisions.head(scoped, svc)!!.revision)
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action=? AND object_ref=? AND actor_ref=? AND redacted_changes->>'reason'=?",
            a.tenant.value, AuditActions.GEO_SERVICE_REVISION_PUBLISHED, "service:$svc", "user:${ctxA.actor.subject}", "Erste Fassung wegen Pilotstart"))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='service.revision_published' AND aggregate_revision=1", a.tenant.value, svc))
        // Fehlschlag erzeugt weder Audit noch Ereignis
        problem { fx.geo.serviceRevisions.publish(ctxA, svc, 0, cmd()) }
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action=? AND object_ref=?", a.tenant.value, AuditActions.GEO_SERVICE_REVISION_PUBLISHED, "service:$svc"))
    }

    @Test
    fun dienst_ohne_betriebszeit_ist_ausser_betrieb_und_wirksame_version_bestimmt_den_status() {
        val w = fx.world(); val ctx = fx.context(w.tenant); val svc = service(w, ctx)
        val noHours = fx.geo.availability.at(ctx, svc, Instant.parse("2026-12-01T10:00:00Z"))
        assertFalse(noHours.inOperation); assertNull(noHours.nextOpening); assertNull(noHours.trafficProduct)
        fx.geo.serviceRevisions.publish(ctx, svc, 0, cmd(from = Instant.parse("2026-12-01T00:00:00+01:00"), w = listOf(mapOf("weekdays" to listOf("TUE"), "from" to "06:00", "to" to "22:00"))))
        assertTrue(fx.geo.availability.at(ctx, svc, Instant.parse("2026-12-01T10:00:00+01:00")).inOperation)          // Dienstag
        assertFalse(fx.geo.availability.at(ctx, svc, Instant.parse("2026-12-02T10:00:00+01:00")).inOperation)         // Mittwoch
        assertFalse(fx.geo.availability.at(ctx, svc, Instant.parse("2026-11-30T10:00:00+01:00")).inOperation)         // vor Wirksamkeit
        assertEquals(Instant.parse("2026-12-08T06:00:00+01:00"), fx.geo.availability.at(ctx, svc, Instant.parse("2026-12-02T10:00:00+01:00")).nextOpening)
        assertNotNull(fx.geo.availability.at(ctx, svc, Instant.parse("2026-11-30T10:00:00+01:00")).nextOpening)
    }

    companion object {
        private val fixture by lazy { StructureFixture() }
        @JvmStatic @AfterAll fun close() { fixture.close() }
    }
}
