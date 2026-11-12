package de.taktovia.stafftime

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.stafftime.api.BackfillTimeEvent
import de.taktovia.stafftime.api.RecordTimeEvent
import de.taktovia.stafftime.api.TimeEventKind
import de.taktovia.stafftime.api.TimeEventSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-08 / LH-6.24-TIM-001 (Ist-Arbeitszeit als Zeitereignisse) gegen die echte Datenbank (Runtime-Rolle): Positiv (Beginn, Pause, Ende; zwei Ereignisse zur selben Schicht;
 * Arbeitszeit ohne Schicht), Negativ (fremde Person 404, fremder Mandant, Nacherfassung ohne Rolle 403, gleiche ID anderer Inhalt 409, Nacherfassung überschreibt nichts),
 * Konkurrierend (identisches Ereignis parallel = eine Zeile, verschiedene Ereignisse parallel zur selben Schicht = je eine Zeile).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimeEventTest {
    private lateinit var fx: StaffFixture
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val registry = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012())

    @BeforeAll fun up() { fx = StaffFixture() }
    @AfterAll fun down() { fx.close() }

    private fun problem(block: () -> Unit): ProblemException = try { block(); throw AssertionError("ProblemException erwartet") } catch (e: ProblemException) { e }
    private fun at(h: Int, m: Int = 0) = Instant.parse("2026-12-01T00:00:00Z").plusSeconds(h * 3600L + m * 60L)
    private fun rec(kind: TimeEventKind, time: Instant, shift: UUID? = null, id: UUID = UUID.randomUUID(), channel: TimeEventSource = TimeEventSource.APP) =
        RecordTimeEvent(id, kind, time, time.plusSeconds(1), channel, shift)

    @Test
    fun zwei_ist_zeit_ereignisse_zu_einer_schicht_bleiben_nachvollziehbar() {
        val w = fx.world(); val ctx = StaffM4Support.employee(w.tenant, w.subject)
        val start = fx.staff.timeEvents.record(ctx, rec(TimeEventKind.WORK_START, at(6), w.shift))
        val end = fx.staff.timeEvents.record(ctx, rec(TimeEventKind.WORK_END, at(14), w.shift, channel = TimeEventSource.WEB))
        assertTrue(start.created); assertTrue(end.created)
        val rows = fx.staff.timeEvents.list(ctx, null, null, null, w.shift, 50, null)
        assertEquals(listOf(start.event.eventId, end.event.eventId), rows.map { it.eventId }, "beide Ereignisse zur Schicht bleiben erhalten")
        assertEquals(listOf(TimeEventSource.APP, TimeEventSource.WEB), rows.map { it.source }, "Quelle je Ereignis")
        assertTrue(rows.all { it.recordedBy == w.subject.toString() && it.personRef == w.subject.toString() && it.shiftId == w.shift }, "Urheber und Schicht nachvollziehbar")
        assertEquals(at(6), rows[0].occurredAt); assertEquals(at(6).plusSeconds(1), rows[0].deviceRecordedAt)
        assertTrue(rows[0].receivedAt.isAfter(Instant.now().minusSeconds(60)), "Serverempfangszeit, nicht Gerätezeit von 2026-12")
        // Outbox-Ereignis „Zeitereignis erfasst“ gegen das Schema
        val payload = fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND event_type='staff.time_event_recorded' AND aggregate_id=?", w.tenant.value, start.event.eventId).single()
        val schema = registry.getSchema(Files.readString(repo.resolve("api/events/staff.time_event_recorded.v1.schema.json")))
        assertEquals(emptyList<Any>(), schema.validate(payload, InputFormat.JSON).toList().map { it.message })
        assertFalse(payload.contains("reason"), "keine Begründungstexte im Ereignis")
    }

    @Test
    fun paralleles_identisches_ereignis_genau_eine_zeile() {
        val w = fx.world(); val ctx = StaffM4Support.employee(w.tenant, w.subject)
        val cmd = rec(TimeEventKind.WORK_START, at(6), w.shift)
        val pool = Executors.newFixedThreadPool(20); val start = CountDownLatch(1)
        val futures = (1..60).map { pool.submit<Any> { start.await(); try { fx.staff.timeEvents.record(ctx, cmd) } catch (e: Throwable) { e } } }
        start.countDown(); val results = futures.map { it.get(60, TimeUnit.SECONDS) }
        assertEquals(emptyList<Any>(), results.filterIsInstance<Throwable>().map { it.toString() })
        val created = results.filterIsInstance<de.taktovia.stafftime.api.TimeEventResult>()
        assertEquals(1, created.count { it.created }, "genau eine Erstwirkung"); assertEquals(59, created.count { !it.created })
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.time_event WHERE id=?", cmd.eventId))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='staff.time_event_recorded' AND aggregate_id=?", w.tenant.value, cmd.eventId), "genau ein Ereignis")
        // verschiedene Ereignisse parallel zur selben Schicht: je eine Zeile
        val others = (1..20).map { rec(TimeEventKind.BREAK_START, at(10, it), w.shift) }
        val f2 = others.map { c -> pool.submit<Any> { start.await(); fx.staff.timeEvents.record(ctx, c) } }
        f2.forEach { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(21, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=? AND shift_id=?", w.tenant.value, w.shift))
    }

    @Test
    fun arbeitszeit_ohne_schicht_erfassbar_und_intervall_abzueglich_pausen() {
        val t = fx.world().tenant; val person = UUID.randomUUID(); val ctx = StaffM4Support.employee(t, person)
        listOf(rec(TimeEventKind.WORK_START, at(6)), rec(TimeEventKind.BREAK_START, at(10)), rec(TimeEventKind.BREAK_END, at(10, 30)), rec(TimeEventKind.WORK_END, at(14))).forEach {
            assertEquals(null, fx.staff.timeEvents.record(ctx, it).event.shiftId, "Arbeitszeit ohne zugeordnete Schicht")
        }
        val d = fx.staff.timeEvents.intervals(ctx, null, at(0), at(23))
        assertEquals(1, d.intervals.size); val i = d.intervals.single()
        assertEquals(at(6), i.start); assertEquals(at(14), i.end); assertEquals(8 * 3600L - 1800L, i.netSeconds, "Beginn bis Ende abzüglich Pause"); assertEquals(emptySet<Any>(), i.flags)
        assertEquals(1, i.breaks.size)
        // Ende ohne Beginn wird gekennzeichnet statt verworfen; fehlendes Ende bleibt offen (kein Auto-Abschluss)
        val p2 = UUID.randomUUID(); val c2 = StaffM4Support.employee(t, p2)
        fx.staff.timeEvents.record(c2, rec(TimeEventKind.WORK_END, at(14)))
        val d2 = fx.staff.timeEvents.intervals(c2, null, at(0), at(23)).intervals.single()
        assertEquals(null, d2.start); assertEquals(at(14), d2.end); assertEquals(setOf(de.taktovia.stafftime.api.IntervalFlag.END_WITHOUT_START), d2.flags); assertEquals(null, d2.netSeconds)
        val p3 = UUID.randomUUID(); val c3 = StaffM4Support.employee(t, p3)
        fx.staff.timeEvents.record(c3, rec(TimeEventKind.WORK_START, at(6)))
        val d3 = fx.staff.timeEvents.intervals(c3, null, at(0), at(23)).intervals.single()
        assertEquals(at(6), d3.start); assertEquals(null, d3.end); assertEquals(setOf(de.taktovia.stafftime.api.IntervalFlag.MISSING_END), d3.flags); assertEquals(null, d3.netSeconds)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=? AND person_ref=?", t.value, p2.toString()), "das einzelne Ende ist gespeichert, nicht verworfen")
        assertEquals(422, problem { fx.staff.timeEvents.intervals(ctx, null, at(0), at(0).plusSeconds(32L * 86400)) }.code.status, "Fenster höchstens 31 Tage")
    }

    @Test
    fun mitarbeiter_sieht_nur_eigene_ereignisse_fremder_mandant_abgewiesen() {
        val w = fx.world(); val other = UUID.randomUUID(); val me = StaffM4Support.employee(w.tenant, w.subject); val them = StaffM4Support.employee(w.tenant, other)
        val mine = fx.staff.timeEvents.record(me, rec(TimeEventKind.WORK_START, at(6), w.shift)).event
        val theirs = fx.staff.timeEvents.record(them, rec(TimeEventKind.WORK_START, at(7))).event
        assertEquals(listOf(mine.eventId), fx.staff.timeEvents.list(me, null, null, null, null, 50, null).map { it.eventId }, "nur eigene Ereignisse")
        assertEquals(404, problem { fx.staff.timeEvents.list(me, other.toString(), null, null, null, 50, null) }.code.status, "fremde Person: nicht auffindbar")
        assertEquals(404, problem { fx.staff.timeEvents.intervals(me, other.toString(), at(0), at(23)) }.code.status)
        // Personalrolle (Planersicht) darf fremde Personen lesen
        val hr = StaffM4Support.hr(w.tenant)
        assertEquals(listOf(theirs.eventId), fx.staff.timeEvents.list(hr, other.toString(), null, null, null, 50, null).map { it.eventId })
        // fremde Schicht und fremde Ereignis-ID: 404 (nie Auskunft über den anderen)
        assertEquals(404, problem { fx.staff.timeEvents.record(them, rec(TimeEventKind.WORK_END, at(15), w.shift)) }.code.status, "Schicht gehört einem anderen Fahrer")
        assertEquals(404, problem { fx.staff.timeEvents.record(them, rec(TimeEventKind.WORK_START, at(6), id = mine.eventId)) }.code.status, "Ereignis-ID gehört einer anderen Person")
        // fremder Mandant: nichts sichtbar, Schicht und ID nicht erreichbar
        val b = fx.world(); val bCtx = StaffM4Support.employee(b.tenant, w.subject)
        assertEquals(emptyList<Any>(), fx.staff.timeEvents.list(bCtx, null, null, null, null, 50, null))
        assertEquals(emptyList<Any>(), fx.staff.timeEvents.list(StaffM4Support.hr(b.tenant), w.subject.toString(), null, null, null, 50, null))
        assertEquals(404, problem { fx.staff.timeEvents.record(bCtx, rec(TimeEventKind.WORK_START, at(6), w.shift)) }.code.status)
        val sameIdInB = fx.staff.timeEvents.record(bCtx, rec(TimeEventKind.WORK_START, at(6), id = mine.eventId))
        assertTrue(sameIdInB.created, "die ID ist je Mandant eindeutig: dieselbe ID in einem anderen Mandanten ist ein eigenes Ereignis")
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=? AND id=?", w.tenant.value, mine.eventId))
    }

    @Test
    fun nacherfassung_eigene_quelle_mit_akteur_im_audit_ohne_ueberschreiben() {
        val w = fx.world(); val me = StaffM4Support.employee(w.tenant, w.subject); val hr = StaffM4Support.hr(w.tenant)
        val original = fx.staff.timeEvents.record(me, rec(TimeEventKind.WORK_START, at(6), w.shift)).event
        val cmd = BackfillTimeEvent(UUID.randomUUID(), w.subject.toString(), TimeEventKind.WORK_END, at(14), w.shift, "Stempeln vergessen")
        assertEquals(403, problem { fx.staff.timeEvents.backfill(me, cmd) }.code.status, "ohne berechtigte Rolle")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.time_event WHERE id=?", cmd.eventId))
        val r = fx.staff.timeEvents.backfill(hr, cmd)
        assertTrue(r.created); assertEquals(TimeEventSource.BACKFILL, r.event.source); assertEquals(hr.actor.subject, r.event.recordedBy); assertEquals(w.subject.toString(), r.event.personRef)
        assertEquals("Stempeln vergessen", r.event.backfillReason); assertEquals(null, r.event.deviceRecordedAt)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.time_event.backfilled' AND object_ref=?", w.tenant.value, "time_event:${cmd.eventId}"), "Nacherfassung steht im audit_event")
        // sie ersetzt nichts: das Original ist unverändert, beide Ereignisse stehen nebeneinander
        assertEquals(setOf(original.eventId, cmd.eventId), fx.staff.timeEvents.list(me, null, null, null, w.shift, 50, null).map { it.eventId }.toSet())
        assertEquals(TimeEventSource.APP, fx.staff.timeEvents.list(me, null, null, null, w.shift, 50, null).single { it.eventId == original.eventId }.source)
        // dieselbe ID mit anderem Inhalt: 409, das Vorhandene bleibt; identische Wiederholung: kein zweiter Eintrag
        assertEquals(409, problem { fx.staff.timeEvents.backfill(hr, BackfillTimeEvent(original.eventId, w.subject.toString(), TimeEventKind.WORK_START, at(5), w.shift, "ueberschreiben")) }.code.status)
        assertEquals(at(6), fx.staff.timeEvents.list(me, null, null, null, w.shift, 50, null).single { it.eventId == original.eventId }.occurredAt)
        assertFalse(fx.staff.timeEvents.backfill(hr, cmd).created); assertEquals(1, fx.count("SELECT count(*) FROM taktovia.time_event WHERE id=?", cmd.eventId))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.time_event.backfilled'", w.tenant.value), "Wiederholung erzeugt keinen zweiten Auditeintrag")
        // Begründung ist Pflicht; Schicht muss zur Person gehören
        assertEquals(422, problem { fx.staff.timeEvents.backfill(hr, cmd.copy(eventId = UUID.randomUUID(), reason = " ")) }.code.status)
        assertEquals(422, problem { fx.staff.timeEvents.backfill(hr, cmd.copy(eventId = UUID.randomUUID(), personRef = UUID.randomUUID().toString())) }.code.status, "Schicht gehört einer anderen Person")
    }

    @Test
    fun gleiche_ereignis_id_mit_anderem_inhalt_ist_409() {
        val w = fx.world(); val me = StaffM4Support.employee(w.tenant, w.subject)
        val first = rec(TimeEventKind.WORK_START, at(6))
        fx.staff.timeEvents.record(me, first)
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, problem { fx.staff.timeEvents.record(me, first.copy(kind = TimeEventKind.WORK_END)) }.code)
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, problem { fx.staff.timeEvents.record(me, first.copy(occurredAt = at(7))) }.code)
        assertEquals(403, problem { fx.staff.timeEvents.record(StaffM4Support.ctx(w.tenant, w.subject, setOf("passenger"), emptySet()), first) }.code.status, "ohne Scope")
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.time_event WHERE id=?", first.eventId))
    }
}
