package de.taktovia.stafftime

import de.taktovia.stafftime.api.RecordTimeEvent
import de.taktovia.stafftime.api.ShiftInput
import de.taktovia.stafftime.api.TargetActualFlag
import de.taktovia.stafftime.api.TimeEventKind
import de.taktovia.stafftime.api.TimeEventSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-09 / LH-6.24-TIM-004: Trennung von Schicht-Soll (`staff_shift`) und Arbeitszeit-Ist (`time_event`) gegen die echte Datenbank (Runtime-Rolle).
 * Positiv (Soll/Ist je Tag, AT-45), Negativ (fehlende Ist-Buchung wird gekennzeichnet; Schreibpfade getrennt), Konkurrierend (Neuveröffentlichung parallel zu Stempeln).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SollIstTrennungTest {
    private lateinit var fx: StaffFixture
    private lateinit var w: ShiftWorld
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val berlin = ZoneId.of("Europe/Berlin")
    private val day = LocalDate.of(2026, 12, 1)

    @BeforeAll fun up() { fx = StaffFixture(); w = ShiftWorld(fx) }
    @AfterAll fun down() { fx.close() }

    private val scopes = setOf("time-events:write", "time-events:read")
    private fun me(p: ShiftWorld.Person) = w.driverCtx(p, scopes = scopes)
    private fun t(s: String) = Instant.parse(s)
    private fun rec(kind: TimeEventKind, at: String, shift: UUID? = null, id: UUID = UUID.randomUUID()) = RecordTimeEvent(id, kind, t(at), t(at).plusSeconds(1), TimeEventSource.APP, shift)
    private fun input(p: ShiftWorld.Person, v: UUID, from: String, to: String) = ShiftInput.parse(mapOf("driver_id" to p.driverId.toString(), "vehicle_id" to v.toString(), "starts_at" to from, "ends_at" to to, "timezone" to "Europe/Berlin"), false)

    /** Ist-Zeilen der Person als Text (alle Spalten, stabil sortiert): Vergleichsstand vor und nach einer Planänderung. */
    private fun snapshot(p: ShiftWorld.Person): List<String> = fx.strings("SELECT e.id || '|' || e.kind || '|' || e.occurred_at || '|' || e.received_at || '|' || e.payload_sha256 FROM taktovia.time_event e WHERE e.tenant_id=? AND e.person_ref=? ORDER BY e.id", w.tenant.value, p.subject)

    @Test
    fun at45_vorbereitungszeit_vor_fahrzeugpruefung_ist_erfasste_arbeitszeit() {
        val p = w.person(); val v = w.vehicle()
        val shift = w.staff.shifts.create(w.dispatcher(), input(p, v, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z"))
        val ctx = me(p)
        // Arbeitsbeginn 05:30 UTC, vor Schichtbeginn und vor jeder Fahrzeugprüfung/Einsatzfreigabe (keine Freigabe existiert)
        w.staff.timeEvents.record(ctx, rec(TimeEventKind.WORK_START, "2026-12-01T05:30:00Z", shift.id))
        w.staff.timeEvents.record(ctx, rec(TimeEventKind.WORK_END, "2026-12-01T14:00:00Z", shift.id))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE tenant_id=? AND shift_id=?", w.tenant.value, shift.id), "keine Fahrzeugfreigabe")
        val d = w.staff.timeTargetActual.days(ctx, null, day, day, berlin).single()
        assertEquals(8 * 3600L, d.plannedSeconds, "Soll aus der Schicht")
        assertEquals(t("2026-12-01T05:30:00Z"), d.actual.single().start, "Ist beginnt mit der Vorbereitungszeit")
        assertEquals(8 * 3600L + 1800L, d.actualNetSeconds, "Vorbereitungszeit zählt als erfasste Arbeitszeit")
        assertTrue(d.flags.isEmpty(), "${d.flags}")
    }

    @Test
    fun fehlende_ist_buchung_gekennzeichnet_nicht_mit_planwert_gefuellt() {
        val p = w.person(); val v = w.vehicle()
        w.staff.shifts.create(w.dispatcher(), input(p, v, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z"))
        val ctx = me(p)
        val d = w.staff.timeTargetActual.days(ctx, null, day, day.plusDays(1), berlin)
        assertEquals(setOf(TargetActualFlag.NO_ACTUAL_BOOKING), d[0].flags)
        assertEquals(8 * 3600L, d[0].plannedSeconds); assertNull(d[0].actualNetSeconds, "kein Planwert als Istwert"); assertEquals(emptyList<Any>(), d[0].actual)
        assertEquals(emptySet<TargetActualFlag>(), d[1].flags, "Tag ohne Soll und ohne Ist: keine Kennzeichnung")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=? AND person_ref=?", w.tenant.value, p.subject), "die Sicht erzeugt keine Ist-Zeile")
        // nur Beginn gebucht: Ist unvollständig, kein Nettowert
        w.staff.timeEvents.record(ctx, rec(TimeEventKind.WORK_START, "2026-12-01T06:05:00Z"))
        val open = w.staff.timeTargetActual.days(ctx, null, day, day, berlin).single()
        assertTrue(TargetActualFlag.ACTUAL_INCOMPLETE in open.flags); assertNull(open.actualNetSeconds)
        // Ist ohne Soll
        val q = w.person(); val cq = me(q)
        w.staff.timeEvents.record(cq, rec(TimeEventKind.WORK_START, "2026-12-01T08:00:00Z")); w.staff.timeEvents.record(cq, rec(TimeEventKind.WORK_END, "2026-12-01T09:00:00Z"))
        val noPlan = w.staff.timeTargetActual.days(cq, null, day, day, berlin).single()
        assertEquals(setOf(TargetActualFlag.ACTUAL_WITHOUT_PLAN), noPlan.flags); assertEquals(3600L, noPlan.actualNetSeconds); assertEquals(0L, noPlan.plannedSeconds)
        // Rechte: fremde Person ohne manage nicht auffindbar, ohne Lesescope verboten, Fenster begrenzt
        assertEquals(404, ShiftWorld.problem { w.staff.timeTargetActual.days(ctx, q.subject, day, day, berlin) }.code.status)
        assertEquals(403, ShiftWorld.problem { w.staff.timeTargetActual.days(w.driverCtx(p), null, day, day, berlin) }.code.status)
        assertEquals(422, ShiftWorld.problem { w.staff.timeTargetActual.days(ctx, null, day, day.plusDays(31), berlin) }.code.status)
        val hr = StaffM4Support.hr(w.tenant)
        assertEquals(3600L, w.staff.timeTargetActual.days(hr, q.subject, day, day, berlin).single().actualNetSeconds, "Personalrolle darf fremde Person lesen")
    }

    @Test
    fun umplanung_von_fahrten_aendert_ist_zeiten_nicht() {
        val p = w.person(); val v = w.vehicle(); val ctx = me(p)
        val shift = w.staff.shifts.create(w.dispatcher(), input(p, v, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z"))
        w.staff.timeEvents.record(ctx, rec(TimeEventKind.WORK_START, "2026-12-01T06:00:00Z", shift.id)); w.staff.timeEvents.record(ctx, rec(TimeEventKind.WORK_END, "2026-12-01T14:00:00Z", shift.id))
        fx.exec("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (?,?,?,?,1,'PLANNED')", w.tenant.value, UUID.randomUUID(), v, shift.id)
        val before = snapshot(p); val daysBefore = w.staff.timeTargetActual.days(ctx, null, day, day, berlin)
        // Planrevision der Disposition (wie JdbcRunPlans: Revision +1, Zustandswechsel), unter der Runtime-Rolle
        repeat(3) { w.fx.tx.run(w.tenant) { j -> j.update("UPDATE taktovia.vehicle_run SET revision = revision + 1 WHERE shift_id = ?", shift.id); j.update("UPDATE taktovia.vehicle_run SET state = 'ACTIVE' WHERE shift_id = ?", shift.id) } }
        assertEquals(4L, fx.count("SELECT revision FROM taktovia.vehicle_run WHERE shift_id=?", shift.id).toLong())
        assertEquals(before, snapshot(p), "Ist-Zeilen unverändert"); val daysAfter = w.staff.timeTargetActual.days(ctx, null, day, day, berlin)
        assertEquals(daysBefore.single().actualNetSeconds, daysAfter.single().actualNetSeconds); assertEquals(daysBefore.single().actual, daysAfter.single().actual)
    }

    @Test
    fun neuveroeffentlichung_aendert_ist_zeiten_nicht() {
        val p = w.person(); val v = w.vehicle(); val ctx = me(p)
        val shift = w.staff.shifts.create(w.dispatcher(), input(p, v, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z"))
        w.staff.timeEvents.record(ctx, rec(TimeEventKind.WORK_START, "2026-12-01T06:10:00Z", shift.id)); w.staff.timeEvents.record(ctx, rec(TimeEventKind.WORK_END, "2026-12-01T13:50:00Z", shift.id))
        val before = snapshot(p); val netBefore = w.staff.timeTargetActual.days(ctx, null, day, day, berlin).single().actualNetSeconds
        // Schichtänderung durch die Leitstelle: anderer Zeitraum, dann Rücknahme und Neuveröffentlichung (Revision der Schicht steigt)
        var rev = shift.revision
        rev = w.staff.shifts.update(w.dispatcher(), shift.id, rev, input(p, v, "2026-12-01T07:00:00Z", "2026-12-01T12:00:00Z")).revision
        rev = w.staff.shifts.update(w.dispatcher(), shift.id, rev, ShiftInput.parse(mapOf("driver_id" to p.driverId.toString(), "vehicle_id" to v.toString(), "starts_at" to "2026-12-01T05:00:00Z", "ends_at" to "2026-12-01T15:00:00Z", "timezone" to "Europe/Berlin", "withdrawn" to true), true)).revision
        w.staff.shifts.update(w.dispatcher(), shift.id, rev, ShiftInput.parse(mapOf("driver_id" to p.driverId.toString(), "vehicle_id" to v.toString(), "starts_at" to "2026-12-01T05:00:00Z", "ends_at" to "2026-12-01T15:00:00Z", "timezone" to "Europe/Berlin", "withdrawn" to false), true))
        assertEquals(before, snapshot(p), "Ist-Zeilen unverändert")
        val after = w.staff.timeTargetActual.days(ctx, null, day, day, berlin).single()
        assertEquals(netBefore, after.actualNetSeconds); assertEquals(10 * 3600L, after.plannedSeconds, "das Soll folgt der neuen Planung, das Ist nicht")
        assertEquals(t("2026-12-01T06:10:00Z"), after.actual.single().start)
    }

    @Test
    fun neuveroeffentlichung_parallel_zu_stempel_beide_erhalten() {
        val p = w.person(); val v = w.vehicle(); val ctx = me(p)
        val shift = w.staff.shifts.create(w.dispatcher(), input(p, v, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z"))
        val kinds = listOf(TimeEventKind.WORK_START, TimeEventKind.BREAK_START, TimeEventKind.BREAK_END, TimeEventKind.WORK_END)
        val stamps = (0 until 40).map { rec(kinds[it % 4], "2026-12-01T06:00:00Z", shift.id).copy(occurredAt = t("2026-12-01T06:00:00Z").plusSeconds(60L * it)) }
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        val stampJobs = stamps.map { c -> pool.submit<Any> { go.await(); try { w.staff.timeEvents.record(ctx, c) } catch (e: Throwable) { e } } }
        // Neuveröffentlichung der Schicht: Revision wiederholt erhöhen (Änderung des Soll), parallel zu den Stempeln
        val republish = pool.submit<Any> {
            go.await(); var rev = shift.revision; var n = 0
            try { repeat(10) { i -> rev = w.staff.shifts.update(w.dispatcher(), shift.id, rev, input(p, v, "2026-12-01T06:00:00Z", "2026-12-01T${15 + (i % 2)}:00:00Z")).revision; n++ }; n } catch (e: Throwable) { e }
        }
        go.countDown()
        val results = stampJobs.map { it.get(60, TimeUnit.SECONDS) }; val rp = republish.get(60, TimeUnit.SECONDS); pool.shutdown()
        assertEquals(emptyList<Any>(), results.filterIsInstance<Throwable>().map { it.toString() }); assertEquals(10, rp, "alle Neuveröffentlichungen wirksam: $rp")
        assertEquals(40, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=? AND person_ref=? AND shift_id=?", w.tenant.value, p.subject, shift.id), "kein Ist-Wert geht verloren")
        assertEquals(shift.revision + 10, fx.count("SELECT shift_revision FROM taktovia.staff_shift WHERE id=?", shift.id).toLong(), "Soll-Änderungen erhalten")
        assertNotNull(w.staff.timeTargetActual.days(ctx, null, day, day, berlin).single().actualNetSeconds)
    }

    @Test
    fun dienstplan_und_dispositionsbefehle_ohne_schreibzugriff_auf_time_event() {
        // Quelltext-Architekturtest: Schreibzugriffe auf time_event gibt es genau in JdbcTimeEvents (Stempelpfad); Dienstplan-, Schicht- und Dispositionscode erwähnt die Tabelle nie.
        val write = Regex("(?is)(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|TRUNCATE(\\s+TABLE)?|MERGE\\s+INTO)\\s+(taktovia\\.)?time_event\\b")
        val mention = Regex("\\btime_event\\b")
        val writers = Files.walk(repo.resolve("backend")).use { s ->
            s.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") && it.toString().contains("/src/main/") }.toList()
        }.filter { write.containsMatchIn(Files.readString(it)) }.map { it.fileName.toString() }
        assertEquals(listOf("JdbcTimeEvents.kt"), writers, "nur der Stempelpfad schreibt time_event")
        val forbidden = listOf("backend/modules/booking-dispatch", "backend/modules/staff-time/src/main/kotlin/de/taktovia/stafftime/internal/JdbcRosterShifts.kt",
            "backend/modules/staff-time/src/main/kotlin/de/taktovia/stafftime/internal/JdbcShifts.kt", "backend/modules/staff-time/src/main/kotlin/de/taktovia/stafftime/internal/JdbcDriverShifts.kt")
        forbidden.forEach { rel ->
            Files.walk(repo.resolve(rel)).use { s -> s.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList() }.forEach {
                assertTrue(!mention.containsMatchIn(Files.readString(it)), "${repo.relativize(it)} darf time_event nicht berühren")
            }
        }
        // Selbstprüfung der Regel: ein Dienstplanbefehl mit Schreibzugriff würde erkannt
        assertTrue(write.containsMatchIn("j.update(\"UPDATE taktovia.time_event SET kind = 'X'\")")); assertTrue(write.containsMatchIn("DELETE FROM time_event WHERE 1=1"))
        assertTrue(!write.containsMatchIn("SELECT * FROM taktovia.time_event e"))
    }
}
