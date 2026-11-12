package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.DefectFilter
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.ReportDefect
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextRevokedException
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-43 / LH-6.22-DMG-001, DMG-002: Schadenakte unter der Runtime-Rolle. Originalabnahme: Schaden ohne aktive Buchung mit eindeutiger Fallnummer erfassbar;
 * Pflichtdaten werden validiert; Fall über Fahrzeug und Datum auffindbar. Positiv, negativ (Pflichtfelder, fremdes Fahrzeug, Zukunftszeit, Clientwerte) und konkurrierend
 * (parallele Meldungen, parallele Wiederholung derselben Geräteereignis-ID).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefectCaseCreateTest {
    private lateinit var fx: FleetFixture

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)
    private fun report(w: DefectWorld, ctx: RequestContext, raw: Map<String, Any?> = w.raw()) = fx.fleet.defects.report(ctx, ReportDefect.parse(raw))

    @Test
    fun meldung_ohne_buchung_erhaelt_eindeutige_fallnummer() {
        val w = DefectWorld(fx); val ctx = w.ctx("dispatcher")
        // weder Buchung noch Schicht noch Prüfung existieren in diesem Mandanten
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.staff_shift WHERE tenant_id=?", w.tenant.value))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.booking WHERE tenant_id=?", w.tenant.value))
        val r = report(w, ctx); val d = r.defect
        assertTrue(r.created); assertEquals("NEW", d.status); assertEquals(1, d.revision); assertNull(d.severity)
        assertTrue(Regex("^SCH-\\d{4}-\\d{6}$").matches(d.caseNumber), d.caseNumber)
        assertEquals(w.vehicle, d.vehicleId); assertEquals("BODY", d.categoryCode); assertEquals("Kratzer an der Beifahrertür", d.description)
        val second = report(w, ctx).defect
        assertNotEquals(d.caseNumber, second.caseNumber); assertTrue(d.caseNumber.endsWith("-000001")); assertTrue(second.caseNumber.endsWith("-000002"))
        // genau ein Audit und ein Ereignis je Akte, ohne Beschreibungstext
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.defect.reported' AND object_ref=?", w.tenant.value, "defect:${d.id}"))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='defect.reported'", w.tenant.value, d.id))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND payload::text LIKE '%Kratzer%'", w.tenant.value, d.id))
        assertEquals("dispatcher", fx.strings("SELECT payload->>'reporter_role' FROM taktovia.outbox WHERE aggregate_id=? AND event_type='defect.reported'", d.id).single())
    }

    @Test
    fun mitarbeiter_meldet_ueber_ui11_und_akte_ueber_fahrzeug_und_datum_auffindbar() {
        val w = DefectWorld(fx); val ctx = w.ctx("workshop"); val other = fx.vehicle(w.tenant)
        val a = report(w, ctx).defect
        report(w, ctx, w.raw(vehicle = other))
        val from = Instant.now().minusSeconds(86_400); val to = Instant.now().plusSeconds(86_400)
        assertEquals(listOf(a.id), fx.fleet.defects.list(ctx, DefectFilter(vehicleId = w.vehicle, reportedFrom = from, reportedTo = to), 10, null).map { it.id })
        assertEquals(0, fx.fleet.defects.list(ctx, DefectFilter(vehicleId = w.vehicle, reportedFrom = to, reportedTo = to.plusSeconds(10)), 10, null).size)
        assertEquals(2, fx.fleet.defects.list(ctx, DefectFilter(), 10, null).size)
        assertEquals(a, fx.fleet.defects.get(ctx, a.id))
    }

    @Test
    fun nutzer_ohne_mandantenbezug_darf_nicht_melden() {
        val w = DefectWorld(fx)
        // Ersatz der Mitgliedschaftsprüfung des Moduls `identity`: der Kontext hat im Mandanten keine aktive Mitgliedschaft
        val denying = FleetsafetyApi.create(ContextTransactions(fx.tx, object : ContextRevalidator {
            override fun revalidate(jdbc: JdbcOperations, ctx: RequestContext, lock: Boolean) { throw ContextRevokedException() }
        }), JdbcAuditPort(), Outbox())
        assertThrows(ContextRevokedException::class.java) { denying.defects.report(w.ctx("dispatcher"), ReportDefect.parse(w.raw())) }
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
        // eine Rolle ohne Meldeberechtigung (z. B. Fahrgast, Support) meldet nicht
        assertEquals(ErrorCode.FORBIDDEN, problem { report(w, w.ctx("passenger")) }.code)
        assertEquals(ErrorCode.FORBIDDEN, problem { report(w, w.ctx("support")) }.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
    }

    @Test
    fun fahrer_fuer_fahrzeug_ohne_bezug_ergibt_404() {
        val w = DefectWorld(fx); val driver = UUID.randomUUID(); val ctx = w.ctx("driver", subject = driver)
        // ohne Schicht: 404 ohne Hinweis auf die Existenz, kein Datensatz
        assertEquals(ErrorCode.NOT_FOUND, problem { report(w, ctx) }.code)
        // Schicht für ein anderes Fahrzeug begründet keinen Bezug
        w.shift(driver, fx.vehicle(w.tenant), startOffsetHours = -30)
        assertEquals(ErrorCode.NOT_FOUND, problem { report(w, ctx) }.code)
        // Schicht eines anderen Fahrers: kein Bezug
        w.shift(UUID.randomUUID(), startOffsetHours = -100)
        assertEquals(ErrorCode.NOT_FOUND, problem { report(w, ctx) }.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
        // mit Schicht für das Fahrzeug (laufend) meldet der Fahrer, auch ohne Buchung und ohne laufende Prüfung
        w.shift(driver)
        val d = report(w, ctx).defect
        assertEquals(driver, d.reportedBy)
        assertEquals("driver", fx.strings("SELECT payload->>'reporter_role' FROM taktovia.outbox WHERE aggregate_id=? AND event_type='defect.reported'", d.id).single())
        // kürzlich beendete Schicht (innerhalb der Karenz von 24 h) trägt den Bezug, eine ältere nicht
        val late = UUID.randomUUID(); val lateCtx = w.ctx("driver", subject = late)
        w.shift(late, startOffsetHours = -30, lengthHours = 8)     // endete vor 22 h
        assertEquals(d.vehicleId, report(w, lateCtx).defect.vehicleId)
        val old = UUID.randomUUID(); w.shift(old, startOffsetHours = -60, lengthHours = 8)   // endete vor 52 h
        assertEquals(ErrorCode.NOT_FOUND, problem { report(w, w.ctx("driver", subject = old)) }.code)
        // ein Fahrer sieht nur eigene Akten
        val foreign = report(w, w.ctx("dispatcher")).defect
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.defects.get(ctx, foreign.id) }.code)
        assertEquals(listOf(d.id), fx.fleet.defects.list(ctx, DefectFilter(), 10, null).map { it.id })
    }

    @Test
    fun fehlende_pflichtfelder_und_zukunftszeit_ergeben_422_mit_feldverweis() {
        val w = DefectWorld(fx); val ctx = w.ctx("dispatcher")
        fun pointers(raw: Map<String, Any?>) = problem { ReportDefect.parse(raw) }.violations.map { it.pointer }
        val base = w.raw()
        listOf("vehicle_id", "occurred_at", "category_code", "description").forEach { f ->
            assertEquals(listOf("/$f"), pointers(base - f), f)
            assertEquals(ViolationCode.REQUIRED, problem { ReportDefect.parse(base - f) }.violations.single().code)
        }
        assertEquals(listOf("/vehicle_id", "/occurred_at", "/category_code", "/description"), pointers(emptyMap()))
        assertEquals(listOf("/description"), pointers(base + ("description" to "   ")))
        assertEquals(listOf("/description"), pointers(base + ("description" to "x".repeat(2001))))
        assertEquals(listOf("/category_code"), pointers(base + ("category_code" to "ERFUNDEN")))
        assertEquals(listOf("/occurred_at"), pointers(base + ("occurred_at" to "2026-11-12T08:00:00")))     // ohne Offset
        assertEquals(listOf("/location_value"), pointers(base + ("location_kind" to "DEPOT")))
        // Ereigniszeit in der Zukunft: 422 mit Feldverweis, nichts gespeichert
        val future = problem { report(w, ctx, w.raw(occurredAt = Instant.now().plusSeconds(3600))) }
        assertEquals(ErrorCode.VALIDATION_FAILED, future.code); assertEquals("/occurred_at", future.violations.single().pointer)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
        // geführte Erfassung verlangt Art, Bereich, Auswirkung und ein Foto (DMG-004)
        assertEquals(listOf("/kind", "/vehicle_area", "/observed_effect", "/evidence_ids"), pointers(base + ("capture_mode" to "GUIDED")))
    }

    @Test
    fun clientwerte_mandant_meldender_status_werden_nicht_uebernommen() {
        val w = DefectWorld(fx); val ctx = w.ctx("dispatcher"); val foreignTenant = UUID.randomUUID()
        for ((k, v) in listOf("tenant_id" to foreignTenant.toString(), "reported_by" to UUID.randomUUID().toString(), "status" to "CLOSED", "case_number" to "SCH-1999-000001",
            "reported_at" to "2020-01-01T00:00:00Z", "severity" to "COSMETIC")) {
            val p = problem { ReportDefect.parse(w.raw(extra = mapOf(k to v))) }
            assertEquals(listOf("/$k"), p.violations.map { it.pointer }); assertEquals(ViolationCode.UNKNOWN_FIELD, p.violations.single().code)
        }
        val d = report(w, ctx).defect
        assertEquals(UUID.fromString(ctx.actor.subject), d.reportedBy); assertEquals("NEW", d.status); assertNull(d.severity)
        assertTrue(d.reportedAt.isAfter(Instant.now().minusSeconds(60)))
        assertEquals(w.tenant.value, fx.strings("SELECT tenant_id::text FROM taktovia.defect_case WHERE id=?", d.id).single().let(UUID::fromString))
    }

    @Test
    fun fallnummer_fremdmandant_nicht_abrufbar() {
        val a = DefectWorld(fx); val b = DefectWorld(fx)
        val da = report(a, a.ctx("dispatcher")).defect
        val db = report(b, b.ctx("dispatcher")).defect
        // beide Mandanten beginnen bei 000001: gleiche Nummer, aber kein Abruf über Mandantengrenzen
        assertEquals(da.caseNumber, db.caseNumber)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.defects.get(b.ctx("dispatcher"), da.id) }.code)
        assertEquals(listOf(db.id), fx.fleet.defects.list(b.ctx("dispatcher"), DefectFilter(), 10, null).map { it.id })
        // Fahrzeug eines fremden Mandanten: 404, keine Akte
        assertEquals(ErrorCode.NOT_FOUND, problem { report(a, a.ctx("dispatcher"), a.raw(vehicle = b.vehicle)) }.code)
        // ein Kontext des Mandanten A kann im Mandanten B keine Akte lesen oder ändern, auch nicht mit bekannter ID und fremdem Tenantkontext
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=? AND vehicle_id=?", a.tenant.value, b.vehicle))
    }

    @Test
    fun parallele_meldungen_erhalten_eindeutige_fallnummern() {
        val w = DefectWorld(fx); val n = 10
        val pool = Executors.newFixedThreadPool(n); val start = CountDownLatch(1)
        val futures = (1..n).map { pool.submit<String> { start.await(); report(w, w.ctx("dispatcher")).defect.caseNumber } }
        start.countDown()
        val numbers = futures.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(n, numbers.toSet().size, "alle Fallnummern verschieden")
        assertEquals((1..n).toList(), numbers.map { it.substringAfterLast('-').toInt() }.sorted(), "lückenlos 1..n")
        assertEquals(n, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
    }

    @Test
    fun gleiche_geraeteereignis_id_erzeugt_keine_zweite_akte() {
        val w = DefectWorld(fx); val ctx = w.ctx("dispatcher"); val ev = UUID.randomUUID()
        val first = report(w, ctx, w.raw(extra = mapOf("device_event_id" to ev.toString())))
        val again = report(w, ctx, w.raw(extra = mapOf("device_event_id" to ev.toString())))
        assertTrue(first.created); assertFalse(again.created); assertEquals(first.defect.id, again.defect.id); assertEquals(first.defect.caseNumber, again.defect.caseNumber)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
        // dieselbe ID von einem anderen Meldenden ist ein Konflikt, keine fremde Akte
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, problem { report(w, w.ctx("dispatcher"), w.raw(extra = mapOf("device_event_id" to ev.toString()))) }.code)
        // parallele Wiederholung: genau eine Akte
        val ev2 = UUID.randomUUID(); val n = 8
        val pool = Executors.newFixedThreadPool(n); val start = CountDownLatch(1)
        val rs = (1..n).map { pool.submit<Pair<UUID, Boolean>> { start.await(); fx.fleet.defects.report(ctx, ReportDefect.parse(w.raw(extra = mapOf("device_event_id" to ev2.toString())))).let { it.defect.id to it.created } } }
        start.countDown()
        val res = rs.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, res.map { it.first }.toSet().size); assertEquals(1, res.count { it.second }, "genau eine Anlage")
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
    }
}
