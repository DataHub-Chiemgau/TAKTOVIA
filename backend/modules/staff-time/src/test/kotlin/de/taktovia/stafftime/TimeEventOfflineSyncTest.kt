package de.taktovia.stafftime

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.stafftime.api.RecordTimeEvent
import de.taktovia.stafftime.api.TimeConflict
import de.taktovia.stafftime.api.TimeEventKind
import de.taktovia.stafftime.api.TimeEventOrigin
import de.taktovia.stafftime.api.TimeEventOutcome
import de.taktovia.stafftime.api.TimeEventResult
import de.taktovia.stafftime.api.TimeEventSource
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-12 / LH-6.24-TIM-003 (Offline-Stempel mit einmaliger Übernahme, Serverseite) gegen die echte Datenbank (Runtime-Rolle): Sync-Wiederholung ergibt genau eine Zeile,
 * Herkunft, Gerätezeit und Serverempfangszeit sind gespeichert, unplausible Gerätezeit und widersprüchliche Reihenfolge werden markiert (nie korrigiert oder verworfen),
 * Stempel für eine fremde Person werden abgewiesen, die Deduplikation hängt nicht am Response-Cache, parallele Sync-Läufe verlieren keinen Stempel.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimeEventOfflineSyncTest {
    private lateinit var fx: StaffFixture

    @BeforeAll fun up() { fx = StaffFixture() }
    @AfterAll fun down() { fx.close() }

    private fun problem(block: () -> Unit): ProblemException = try { block(); throw AssertionError("ProblemException erwartet") } catch (e: ProblemException) { e }
    private val base: Instant = Instant.now().minusSeconds(4 * 3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
    private fun off(kind: TimeEventKind, min: Long, id: UUID = UUID.randomUUID(), at: Instant = base.plusSeconds(min * 60), device: Instant = at.plusSeconds(1), person: String? = null) =
        RecordTimeEvent(id, kind, at, device, TimeEventSource.APP, null, origin = TimeEventOrigin.OFFLINE, claimedPerson = person)

    @Test
    fun offline_stempel_genau_einmal_uebernommen() {
        val w = fx.world(); val ctx = StaffM4Support.employee(w.tenant, w.subject)
        val kommen = off(TimeEventKind.WORK_START, 0); val gehen = off(TimeEventKind.WORK_END, 120)
        // erster Sync-Lauf übernimmt beide, weitere Läufe (Wiederholung nach Verbindungsabbruch) liefern DUPLICATE
        val run1 = listOf(kommen, gehen).map { fx.staff.timeEvents.record(ctx, it) }
        assertEquals(listOf(TimeEventOutcome.APPLIED, TimeEventOutcome.APPLIED), run1.map { it.outcome })
        repeat(3) { run -> assertEquals(listOf(TimeEventOutcome.DUPLICATE, TimeEventOutcome.DUPLICATE), listOf(kommen, gehen).map { fx.staff.timeEvents.record(ctx, it).outcome }, "Wiederholung ${run + 1}") }
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=?", w.tenant.value), "genau eine Zeile je Ereignis-ID")
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='staff.time_event_recorded'", w.tenant.value), "Ereignis nur bei Erstübernahme")
        // Herkunft, Gerätezeit und Serverempfangszeit am Ereignis
        val v = run1[0].event
        assertEquals(TimeEventOrigin.OFFLINE, v.origin); assertEquals(kommen.deviceRecordedAt, v.deviceRecordedAt); assertEquals(kommen.occurredAt, v.occurredAt); assertNull(v.conflict)
        assertTrue(v.receivedAt.isAfter(Instant.now().minusSeconds(60)), "Serverempfangszeit statt Gerätezeit von vor vier Stunden")
        assertEquals("OFFLINE", fx.strings("SELECT origin FROM taktovia.time_event WHERE tenant_id=? AND id=?", w.tenant.value, kommen.eventId).single())
        val iv = fx.staff.timeEvents.intervals(ctx, null, base.minusSeconds(60), base.plusSeconds(86_400)).intervals.single()
        assertEquals(7200L, iv.netSeconds)
    }

    @Test
    fun manipulierte_geraetezeit_review_required_ohne_korrektur() {
        val w = fx.world(); val ctx = StaffM4Support.employee(w.tenant, w.subject)
        // Gerätezeit zwei Stunden in der Zukunft
        val future = Instant.now().plusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        val r = fx.staff.timeEvents.record(ctx, off(TimeEventKind.WORK_START, 0, at = future, device = future))
        assertEquals(TimeEventOutcome.REVIEW_REQUIRED, r.outcome); assertEquals(TimeConflict.CLOCK_SKEW, r.event.conflict)
        assertEquals(future, r.event.occurredAt, "Ereigniszeit nicht korrigiert")
        assertEquals("CLOCK_SKEW", fx.strings("SELECT conflict_flag FROM taktovia.time_event WHERE tenant_id=? AND id=?", w.tenant.value, r.event.eventId).single(), "gespeichert, nicht verworfen")
        // Gerätezeit und Ereigniszeit widersprechen sich
        val w2 = fx.world(); val c2 = StaffM4Support.employee(w2.tenant, w2.subject)
        val skew = fx.staff.timeEvents.record(c2, off(TimeEventKind.WORK_START, 0, at = base, device = base.plusSeconds(3600)))
        assertEquals(TimeConflict.CLOCK_SKEW, skew.event.conflict)
        // zu alter Offline-Stempel (älter als die Planungsgrenze von 7 Tagen)
        val w3 = fx.world(); val c3 = StaffM4Support.employee(w3.tenant, w3.subject); val old = Instant.now().minusSeconds(10 * 86_400).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        assertEquals(TimeConflict.CLOCK_SKEW, fx.staff.timeEvents.record(c3, off(TimeEventKind.WORK_START, 0, at = old, device = old)).event.conflict)
        // die Markierung ist gespeichert und nachträglich nicht änderbar (Unveränderlichkeit bleibt)
        assertNotNull(fx.staff.timeEvents.list(ctx, null, null, null, null, 10, null).single().conflict)
    }

    @Test
    fun widerspruechliche_reihenfolge_wird_markiert_und_nicht_verworfen() {
        val w = fx.world(); val ctx = StaffM4Support.employee(w.tenant, w.subject)
        assertEquals(TimeEventOutcome.APPLIED, fx.staff.timeEvents.record(ctx, off(TimeEventKind.WORK_START, 0)).outcome)
        // zweites Kommen bei laufender Arbeitszeit: offline übernommen, aber zu prüfen (online wäre es ein 409)
        val dbl = fx.staff.timeEvents.record(ctx, off(TimeEventKind.WORK_START, 30))
        assertEquals(TimeEventOutcome.REVIEW_REQUIRED, dbl.outcome); assertEquals(TimeConflict.INVALID_ORDER, dbl.event.conflict)
        // Pausenende ohne Pausenbeginn
        assertEquals(TimeConflict.INVALID_ORDER, fx.staff.timeEvents.record(ctx, off(TimeEventKind.BREAK_END, 40)).event.conflict)
        // nachträglich eingereihter Stempel, der das spätere Ereignis widersprüchlich macht (Gehen davor, dann folgt noch Pause)
        val w2 = fx.world(); val c2 = StaffM4Support.employee(w2.tenant, w2.subject)
        fx.staff.timeEvents.record(c2, off(TimeEventKind.WORK_START, 0)); fx.staff.timeEvents.record(c2, off(TimeEventKind.BREAK_START, 60)); fx.staff.timeEvents.record(c2, off(TimeEventKind.BREAK_END, 90))
        val late = fx.staff.timeEvents.record(c2, off(TimeEventKind.WORK_END, 45))   // Gehen vor der Pause: überlappende Intervalle
        assertEquals(TimeConflict.INVALID_ORDER, late.event.conflict)
        assertEquals(4, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=?", w2.tenant.value), "nichts verworfen")
        // eine passende Folge bleibt unmarkiert
        val w3 = fx.world(); val c3 = StaffM4Support.employee(w3.tenant, w3.subject)
        val seq = listOf(TimeEventKind.WORK_START to 0L, TimeEventKind.BREAK_START to 60L, TimeEventKind.BREAK_END to 90L, TimeEventKind.WORK_END to 180L).map { (k, m) -> fx.staff.timeEvents.record(c3, off(k, m)) }
        assertEquals(listOf(null, null, null, null), seq.map { it.event.conflict })
    }

    @Test
    fun offline_stempel_fremde_person_wird_abgewiesen() {
        val w = fx.world(); val ctx = StaffM4Support.employee(w.tenant, w.subject)
        assertEquals(ErrorCode.FORBIDDEN, problem { fx.staff.timeEvents.record(ctx, off(TimeEventKind.WORK_START, 0, person = UUID.randomUUID().toString())) }.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=?", w.tenant.value))
        // dieselbe Ereignis-ID einer anderen Person im selben Mandanten ist nicht auffindbar und erzeugt keinen Stempel
        val id = UUID.randomUUID(); fx.staff.timeEvents.record(ctx, off(TimeEventKind.WORK_START, 0, id = id))
        val other = StaffM4Support.employee(w.tenant, UUID.randomUUID())
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.staff.timeEvents.record(other, off(TimeEventKind.WORK_START, 0, id = id)) }.code)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=?", w.tenant.value))
        // Offline über den Webkanal ist nicht vorgesehen
        val web = off(TimeEventKind.WORK_END, 5).copy(channel = TimeEventSource.WEB)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { fx.staff.timeEvents.record(ctx, web) }.code)
    }

    @Test
    fun paralleler_sync_gleiche_ereignis_id_genau_eine_zeile() {
        val w = fx.world(); val ctx = StaffM4Support.employee(w.tenant, w.subject)
        val cmd = off(TimeEventKind.WORK_START, 0)
        val pool = Executors.newFixedThreadPool(12); val go = CountDownLatch(1)
        // zwei Sync-Läufe (je 20 Wiederholungen) senden denselben Stempel gleichzeitig
        val results = (1..40).map { pool.submit<Any> { go.await(); try { fx.staff.timeEvents.record(ctx, cmd) } catch (t: Throwable) { t } } }
        go.countDown(); val r = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(emptyList<String>(), r.filterIsInstance<Throwable>().map { it.toString() })
        val ok = r.filterIsInstance<TimeEventResult>()
        assertEquals(1, ok.count { it.created }); assertEquals(39, ok.count { it.outcome == TimeEventOutcome.DUPLICATE })
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=? AND id=?", w.tenant.value, cmd.eventId))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='staff.time_event_recorded' AND aggregate_id=?", w.tenant.value, cmd.eventId))
    }

    @Test
    fun sync_parallel_zu_online_stempel_derselben_person_verliert_keinen_stempel() {
        val w = fx.world(); val ctx = StaffM4Support.employee(w.tenant, w.subject)
        val ids = (0 until 12).map { UUID.randomUUID() }
        val pool = Executors.newFixedThreadPool(12); val go = CountDownLatch(1)
        val fs = ids.mapIndexed { i, id ->
            pool.submit<Any> {
                go.await()
                try {
                    // abwechselnd offline (Sync) und online erfasst; Pausenfolge je Index, damit kein Doppel-Kommen entsteht
                    val kind = if (i == 0) TimeEventKind.WORK_START else TimeEventKind.BREAK_START
                    val t = base.plusSeconds(60L * (i + 1))
                    fx.staff.timeEvents.record(ctx, RecordTimeEvent(id, kind, t, t.plusSeconds(1), TimeEventSource.APP, null, origin = if (i % 2 == 0) TimeEventOrigin.OFFLINE else TimeEventOrigin.ONLINE))
                } catch (t: Throwable) { t }
            }
        }
        go.countDown(); val r = fs.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(emptyList<String>(), r.filterIsInstance<Throwable>().map { it.toString() })
        assertEquals(12, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=? AND person_ref=?", w.tenant.value, w.subject.toString()), "kein Stempel geht verloren")
        assertEquals(ids.toSet(), fx.strings("SELECT id::text FROM taktovia.time_event WHERE tenant_id=?", w.tenant.value).map(UUID::fromString).toSet())
    }

    @Test
    fun wiederholung_nach_72h_cache_ablauf_weiter_duplicate() {
        val w = fx.world(); val ctx = StaffM4Support.employee(w.tenant, w.subject)
        val cmd = off(TimeEventKind.WORK_START, 0)
        assertEquals(TimeEventOutcome.APPLIED, fx.staff.timeEvents.record(ctx, cmd).outcome)
        // 72-h-Response-Cache des Idempotenzregisters: dieser Pfad liest ihn nicht. Zeitreise: der Stempel gilt als vor 80 Stunden empfangen (Trigger nur für diese Prüfung kurz ausgesetzt,
        // Administratorzugang; die Runtime-Rolle kann das nicht). Die Wiederholung ist trotzdem DUPLICATE, nicht eine zweite Zeile und nicht ein Fehler.
        fx.exec("ALTER TABLE taktovia.time_event DISABLE TRIGGER time_event_immutable")
        try { fx.exec("UPDATE taktovia.time_event SET received_at = received_at - interval '80 hours' WHERE tenant_id = ? AND id = ?", w.tenant.value, cmd.eventId) }
        finally { fx.exec("ALTER TABLE taktovia.time_event ENABLE TRIGGER time_event_immutable") }
        assertTrue(fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=? AND id=? AND received_at < now() - interval '72 hours'", w.tenant.value, cmd.eventId) == 1)
        val again = fx.staff.timeEvents.record(ctx, cmd)
        assertEquals(TimeEventOutcome.DUPLICATE, again.outcome); assertTrue(again.event.receivedAt.isBefore(Instant.now().minusSeconds(72 * 3600)), "das gespeicherte Ereignis, nicht ein neues")
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=? AND id=?", w.tenant.value, cmd.eventId))
        assertEquals(1, fx.count("SELECT count(*) FROM pg_trigger WHERE tgname = 'time_event_immutable' AND tgenabled = 'O'"), "Unveränderlichkeit wieder aktiv")
    }
}
