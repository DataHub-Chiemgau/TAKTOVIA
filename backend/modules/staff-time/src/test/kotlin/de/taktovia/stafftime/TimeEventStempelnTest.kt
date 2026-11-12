package de.taktovia.stafftime

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.stafftime.api.RecordTimeEvent
import de.taktovia.stafftime.api.TimeEventKind
import de.taktovia.stafftime.api.TimeEventOutcome
import de.taktovia.stafftime.api.TimeEventResult
import de.taktovia.stafftime.api.TimeEventSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-11 / LH-6.24-TIM-002 (Stempeln in der Fahrer-App, Serverseite) gegen die echte Datenbank (Runtime-Rolle): Stempeln vor Fahrzeugfreigabe und Fahrtbeginn wird angenommen und
 * erteilt keine Freigabe; doppeltes Einstempeln ist ein Konflikt (409), fremde Personen-ID wird abgewiesen (403), derselbe Stempel parallel ergibt genau eine Zeile, Stempel zweier
 * Mandanten bleiben getrennt. Der Ablauf mit Vor-Schicht-Checkliste über HTTP steht in `TimeEventStempelnApiTest` (Modul `app`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimeEventStempelnTest {
    private lateinit var fx: StaffFixture

    @BeforeAll fun up() { fx = StaffFixture() }
    @AfterAll fun down() { fx.close() }

    private fun problem(block: () -> Unit): ProblemException = try { block(); throw AssertionError("ProblemException erwartet") } catch (e: ProblemException) { e }
    private val base: Instant = Instant.now().minusSeconds(10 * 3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
    private fun rec(kind: TimeEventKind, offsetMin: Long, id: UUID = UUID.randomUUID(), shift: UUID? = null, person: String? = null): RecordTimeEvent {
        val t = base.plusSeconds(offsetMin * 60)
        return RecordTimeEvent(id, kind, t, t.plusSeconds(1), TimeEventSource.APP, shift, claimedPerson = person)
    }

    @Test
    fun einstempeln_vor_fahrzeugfreigabe_wird_angenommen_ohne_freigabe_und_ohne_fahrt() {
        val w = fx.world(); val ctx = StaffM4Support.employee(w.tenant, w.subject)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE tenant_id=?", w.tenant.value), "Ausgangslage: keine Fahrzeugfreigabe")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle_run WHERE tenant_id=?", w.tenant.value), "Ausgangslage: kein Fahrtbeginn")
        val start = fx.staff.timeEvents.record(ctx, rec(TimeEventKind.WORK_START, 0, shift = w.shift))
        assertTrue(start.created); assertEquals(TimeEventOutcome.APPLIED, start.outcome); assertNull(start.event.conflict)
        // Pause, Pausenende und Gehen sind ohne Freigabe und ohne Fahrt erfassbar und erscheinen als je ein time_event
        listOf(TimeEventKind.BREAK_START to 120L, TimeEventKind.BREAK_END to 150L, TimeEventKind.WORK_END to 480L).forEach { (k, m) ->
            val r = fx.staff.timeEvents.record(ctx, rec(k, m, shift = w.shift)); assertEquals(TimeEventOutcome.APPLIED, r.outcome, "$k")
        }
        assertEquals(4, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=? AND person_ref=?", w.tenant.value, w.subject.toString()))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE tenant_id=?", w.tenant.value), "Einstempeln erteilt keine Fahrzeug- oder Einsatzfreigabe")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle_run WHERE tenant_id=?", w.tenant.value), "Stempeln erzeugt keinen Fahrtzustand")
        val iv = fx.staff.timeEvents.intervals(ctx, null, base.minusSeconds(60), base.plusSeconds(86_400)).intervals.single()
        assertEquals((480 - 30) * 60L, iv.netSeconds, "Arbeitszeit abzüglich Pause")
    }

    @Test
    fun doppeltes_einstempeln_ist_ein_konflikt_und_wird_nicht_still_angenommen() {
        val w = fx.world(); val ctx = StaffM4Support.employee(w.tenant, w.subject)
        fx.staff.timeEvents.record(ctx, rec(TimeEventKind.WORK_START, 0))
        val e = problem { fx.staff.timeEvents.record(ctx, rec(TimeEventKind.WORK_START, 5)) }
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.code)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=?", w.tenant.value), "kein zweiter Stempel gespeichert")
        // nach Gehen ist erneutes Einstempeln zulässig
        fx.staff.timeEvents.record(ctx, rec(TimeEventKind.WORK_END, 10))
        assertEquals(TimeEventOutcome.APPLIED, fx.staff.timeEvents.record(ctx, rec(TimeEventKind.WORK_START, 20)).outcome)
    }

    @Test
    fun stempel_fremde_person_403() {
        val w = fx.world(); val ctx = StaffM4Support.employee(w.tenant, w.subject)
        val other = UUID.randomUUID().toString()
        val e = problem { fx.staff.timeEvents.record(ctx, rec(TimeEventKind.WORK_START, 0, person = other)) }
        assertEquals(ErrorCode.FORBIDDEN, e.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=?", w.tenant.value), "weder für die fremde noch für die eigene Person gespeichert")
        // die eigene Personen-ID ist zulässig
        assertEquals(TimeEventOutcome.APPLIED, fx.staff.timeEvents.record(ctx, rec(TimeEventKind.WORK_START, 0, person = w.subject.toString())).outcome)
        // ohne Recht zum Stempeln (kein Funktionsscope) ist der Stempel verboten
        val noScope = StaffM4Support.ctx(w.tenant, w.subject, setOf("driver"), emptySet())
        assertEquals(ErrorCode.FORBIDDEN, problem { fx.staff.timeEvents.record(noScope, rec(TimeEventKind.WORK_START, 1)) }.code)
    }

    @Test
    fun paralleler_stempel_gleiche_ereignis_id_genau_eine_zeile() {
        val w = fx.world(); val ctx = StaffM4Support.employee(w.tenant, w.subject)
        val cmd = rec(TimeEventKind.WORK_START, 0, shift = w.shift)
        val pool = Executors.newFixedThreadPool(16); val go = CountDownLatch(1)
        val results = (1..48).map { pool.submit<Any> { go.await(); try { fx.staff.timeEvents.record(ctx, cmd) } catch (t: Throwable) { t } } }
        go.countDown(); val r = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(emptyList<String>(), r.filterIsInstance<Throwable>().map { it.toString() }, "keine Wiederholung scheitert (auch nicht als Doppel-Einstempeln)")
        val ok = r.filterIsInstance<TimeEventResult>()
        assertEquals(1, ok.count { it.outcome == TimeEventOutcome.APPLIED }); assertEquals(47, ok.count { it.outcome == TimeEventOutcome.DUPLICATE })
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=? AND id=?", w.tenant.value, cmd.eventId))
    }

    @Test
    fun parallele_stempel_zweier_mandanten_bleiben_getrennt() {
        val a = fx.world(); val b = fx.world()
        val ca = StaffM4Support.employee(a.tenant, a.subject); val cb = StaffM4Support.employee(b.tenant, b.subject)
        val sameId = UUID.randomUUID()   // die Ereignis-ID ist je Mandant eindeutig, nicht global
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        val fa = (1..10).map { pool.submit<Any> { go.await(); try { fx.staff.timeEvents.record(ca, rec(TimeEventKind.WORK_START, 0, id = sameId)) } catch (t: Throwable) { t } } }
        val fb = (1..10).map { pool.submit<Any> { go.await(); try { fx.staff.timeEvents.record(cb, rec(TimeEventKind.WORK_START, 0, id = sameId)) } catch (t: Throwable) { t } } }
        go.countDown(); val all = (fa + fb).map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(emptyList<String>(), all.filterIsInstance<Throwable>().map { it.toString() })
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=? AND id=?", a.tenant.value, sameId))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=? AND id=?", b.tenant.value, sameId))
        assertEquals(1, fx.staff.timeEvents.list(ca, null, null, null, null, 50, null).size); assertEquals(1, fx.staff.timeEvents.list(cb, null, null, null, null, 50, null).size)
    }
}
