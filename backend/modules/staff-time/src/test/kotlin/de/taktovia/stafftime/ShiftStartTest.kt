package de.taktovia.stafftime

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.stafftime.api.DriverRunProjection
import de.taktovia.stafftime.api.DriverRunSource
import de.taktovia.stafftime.api.DriverRunStopView
import de.taktovia.stafftime.api.EndShiftCommand
import de.taktovia.stafftime.api.RunReleaseState
import de.taktovia.stafftime.api.ShiftInput
import de.taktovia.stafftime.api.StartShiftCommand
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * WP-M3-25 / LH-6.6-DRV-001 über das Modul `staff-time` (echte Datenbank, Runtime-Rolle, veränderbare Serveruhr): ohne gültige Schicht werden keine ausführbaren Aufträge freigegeben.
 * Die Stoppliste kommt aus einer Testquelle ([DriverRunSource]), die zählt, wie oft sie gefragt wird: der Server fragt sie nur bei angetretener, gültiger und freigegebener Schicht.
 * Positiv: Antritt und Run. Negativ: keine Schicht, abgelaufen, Entzug, fremde Schicht und Mandant, keine Freigabe, Konto ohne Fahrerrolle, gesperrte Gerätesitzung.
 * Konkurrierend: zwei Geräte desselben Fahrers, Entzug parallel zu Run-Abrufen.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShiftStartTest {
    private lateinit var fx: StaffFixture
    private lateinit var w: ShiftWorld
    private val asked = AtomicInteger()
    private val source = DriverRunSource { _, _, _ -> asked.incrementAndGet(); DriverRunProjection(3, listOf(STOP)) }

    @BeforeAll fun up() { fx = StaffFixture(); w = ShiftWorld(fx, source = source) }
    @AfterAll fun down() = fx.close()

    private fun at(iso: String) { w.clock.now = Instant.parse(iso) }

    /** Eigene Schicht 06:00-14:00 am 1.12.2026; die Uhr steht 10 Minuten vorher. */
    private fun scene(): Triple<ShiftWorld.Person, UUID, UUID> {
        val p = w.person(); val v = w.vehicle(); val s = w.shiftRow(p, v, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z")
        at("2026-12-01T05:50:00Z")
        return Triple(p, v, s)
    }

    @Test
    fun anmeldung_und_antritt_liefert_den_run_nur_mit_freigabe() {
        val (p, v, s) = scene(); val ctx = w.driverCtx(p); val dev = w.device(p)
        assertEquals(listOf(s), w.staff.driverShifts.mine(ctx).map { it.shiftId })
        val mine = w.staff.driverShifts.mine(ctx).single()
        assertEquals(v, mine.vehicleId); assertFalse(mine.valid); assertEquals("PLANNED", mine.state); assertTrue(mine.vehicleLabel!!.startsWith("A-"))
        at("2026-12-01T06:00:00Z")
        val r = w.staff.driverShifts.start(ctx, StartShiftCommand(s, v, dev))
        assertFalse(r.alreadyStarted); assertEquals("STARTED", r.shift.state)
        // Dienstantritt allein ist keine Einsatzfreigabe: keine Aufträge, Grund NO_INSPECTION
        asked.set(0)
        val before = w.staff.driverShifts.run(ctx, s)
        assertTrue(before.shiftValid); assertEquals(RunReleaseState.NOT_RELEASED, before.releaseState); assertEquals(listOf("NO_INSPECTION"), before.missingRequirements); assertTrue(before.stops.isEmpty()); assertEquals(0, asked.get())
        // erst mit gültiger Einsatzfreigabe (und ohne operative Sperre) kommen die Aufträge
        w.release(s, v); w.unblock(v)
        asked.set(0)
        val after = w.staff.driverShifts.run(ctx, s)
        assertEquals(RunReleaseState.RELEASED, after.releaseState); assertEquals(listOf(STOP.routeStopId), after.stops.map { it.routeStopId }); assertEquals(3L, after.planRevision); assertEquals(1, asked.get())
    }

    @Test
    fun ohne_gueltige_schicht_keine_auftraege() {
        val p = w.person(); val ctx = w.driverCtx(p)
        at("2026-12-01T05:50:00Z")
        assertTrue(w.staff.driverShifts.mine(ctx).isEmpty(), "ohne Zuweisung gibt es keinen Dienst")
        assertEquals(ErrorCode.NOT_FOUND, ShiftWorld.problem { w.staff.driverShifts.run(ctx, UUID.randomUUID()) }.code)
        // eigene, aber nicht angetretene Schicht (auch mit Freigabe): Leerzustand, die Quelle wird nicht gefragt
        val v = w.vehicle(); val s = w.shiftRow(p, v, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z"); w.release(s, v); w.unblock(v)
        asked.set(0)
        val run = w.staff.driverShifts.run(ctx, s)
        assertFalse(run.shiftValid); assertEquals("PLANNED", run.shiftState); assertTrue(run.stops.isEmpty()); assertEquals(0, asked.get())
    }

    @Test
    fun abgelaufene_schicht_und_entzug_keine_auftraege() {
        val (p, v, s) = scene(); val ctx = w.driverCtx(p); val dev = w.device(p)
        w.release(s, v); w.unblock(v)
        at("2026-12-01T06:00:00Z"); w.staff.driverShifts.start(ctx, StartShiftCommand(s, v, dev))
        at("2026-12-01T10:00:00Z"); assertEquals(1, w.staff.driverShifts.run(ctx, s).stops.size, "während der Schicht werden Aufträge geliefert")
        // nach Schichtende (ends_at ist exklusiv): kein Auftrag mehr, die Schicht ist aber nicht fremd (200, Leerzustand)
        at("2026-12-01T14:00:00Z"); asked.set(0)
        val over = w.staff.driverShifts.run(ctx, s)
        assertFalse(over.shiftValid); assertTrue(over.stops.isEmpty()); assertEquals(0, asked.get())
        // Entzug der Zuweisung durch die Leitstelle: sofort keine Aufträge, kein Antritt
        val (p2, v2, s2) = scene(); val ctx2 = w.driverCtx(p2); val dev2 = w.device(p2)
        w.release(s2, v2); w.unblock(v2); at("2026-12-01T06:30:00Z")
        w.staff.driverShifts.start(ctx2, StartShiftCommand(s2, v2, dev2)); assertEquals(1, w.staff.driverShifts.run(ctx2, s2).stops.size)
        withdraw(s2, p2, v2)
        asked.set(0)
        val gone = w.staff.driverShifts.run(ctx2, s2)
        assertEquals("CANCELLED", gone.shiftState); assertFalse(gone.shiftValid); assertTrue(gone.stops.isEmpty()); assertEquals(0, asked.get())
        assertTrue(w.staff.driverShifts.mine(ctx2).isEmpty(), "entzogene Schicht erscheint nicht mehr als Dienst")
        val (p3, v3, s3) = scene(); val dev3 = w.device(p3); withdraw(s3, p3, v3)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ShiftWorld.problem { w.staff.driverShifts.start(w.driverCtx(p3), StartShiftCommand(s3, v3, dev3)) }.code)
    }

    @Test
    fun antritt_toleranz_zeitfenster_und_fahrzeug() {
        val (p, v, s) = scene(); val ctx = w.driverCtx(p); val dev = w.device(p)
        at("2026-12-01T05:29:59Z")        // mehr als 30 Minuten vor Beginn
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ShiftWorld.problem { w.staff.driverShifts.start(ctx, StartShiftCommand(s, v, dev)) }.code)
        at("2026-12-01T06:00:00Z")
        assertEquals(ErrorCode.VALIDATION_FAILED, ShiftWorld.problem { w.staff.driverShifts.start(ctx, StartShiftCommand(s, UUID.randomUUID(), dev)) }.code, "Fahrzeug der Schicht ist Pflicht")
        assertEquals("PLANNED", fx.strings("SELECT state FROM taktovia.staff_shift WHERE id=?", s).single())
        at("2026-12-01T05:30:00Z")        // genau an der Toleranzgrenze
        assertEquals("STARTED", w.staff.driverShifts.start(ctx, StartShiftCommand(s, v, dev)).shift.state)
        val (p2, v2, s2) = scene(); val ctx2 = w.driverCtx(p2); val dev2 = w.device(p2)
        at("2026-12-01T14:00:00Z")        // Schichtende erreicht
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ShiftWorld.problem { w.staff.driverShifts.start(ctx2, StartShiftCommand(s2, v2, dev2)) }.code)
        // Dienstende und Wiederholung
        at("2026-12-01T13:00:00Z")
        val ended = w.staff.driverShifts.end(ctx, EndShiftCommand(s, dev)); assertEquals("ENDED", ended.state); assertNotNull(ended.endedAt)
        assertEquals("ENDED", w.staff.driverShifts.end(ctx, EndShiftCommand(s, dev)).state)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ShiftWorld.problem { w.staff.driverShifts.end(ctx2, EndShiftCommand(s2, dev2)) }.code, "nicht angetretene Schicht lässt sich nicht beenden")
    }

    @Test
    fun fremde_schicht_ergibt_404() {
        val (p, v, s) = scene(); val other = w.person(); val octx = w.driverCtx(other); val odev = w.device(other)
        assertEquals(ErrorCode.NOT_FOUND, ShiftWorld.problem { w.staff.driverShifts.start(octx, StartShiftCommand(s, v, odev)) }.code)
        assertEquals(ErrorCode.NOT_FOUND, ShiftWorld.problem { w.staff.driverShifts.run(octx, s) }.code)
        assertEquals(ErrorCode.NOT_FOUND, ShiftWorld.problem { w.staff.driverShifts.end(octx, EndShiftCommand(s, odev)) }.code)
        // Schicht eines anderen Mandanten: ebenfalls 404, mit gleichem Fehlerbild
        val w2 = ShiftWorld(fx); val p2 = w2.person(); val v2 = w2.vehicle(); val s2 = w2.shiftRow(p2, v2, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z")
        val foreign = ShiftWorld.problem { w.staff.driverShifts.run(w.driverCtx(p), s2) }
        assertEquals(ErrorCode.NOT_FOUND, foreign.code); assertEquals(ShiftWorld.problem { w.staff.driverShifts.run(octx, s) }.detail, foreign.detail)
        assertEquals("PLANNED", fx.strings("SELECT state FROM taktovia.staff_shift WHERE id=?", s).single())
        assertEquals(v, v)
    }

    @Test
    fun dienstantritt_ohne_resource_release_keine_auftraege() {
        val (p, v, s) = scene(); val ctx = w.driverCtx(p); val dev = w.device(p)
        at("2026-12-01T06:00:00Z"); w.staff.driverShifts.start(ctx, StartShiftCommand(s, v, dev))
        fun codes() = w.staff.driverShifts.run(ctx, s).let { Triple(it.releaseState, it.missingRequirements, it.stops.size) }
        assertEquals(Triple(RunReleaseState.NOT_RELEASED, listOf("NO_INSPECTION"), 0), codes(), "keine resource_release")
        w.release(s, v, revoked = true)
        assertEquals(Triple(RunReleaseState.NOT_RELEASED, listOf("RELEASE_REVOKED"), 0), codes(), "widerrufene Freigabe")
        fx.exec("DELETE FROM taktovia.resource_release WHERE tenant_id=? AND shift_id=?", w.tenant.value, s)
        w.release(s, v, safetyRevision = 7)
        assertEquals(Triple(RunReleaseState.NOT_RELEASED, listOf("SAFETY_REVISION_CHANGED"), 0), codes(), "veraltete safety_revision")
        fx.exec("DELETE FROM taktovia.resource_release WHERE tenant_id=? AND shift_id=?", w.tenant.value, s)
        w.release(s, v)       // gültig, aber das neue Fahrzeug hat active_block = true (Standard bis zur Freigabe)
        assertEquals(Triple(RunReleaseState.BLOCKED, listOf("OPERATIONAL_BLOCK"), 0), codes())
        w.unblock(v)
        assertEquals(Triple(RunReleaseState.RELEASED, emptyList<String>(), 1), codes())
        at("2026-12-01T18:01:00Z")      // Freigabe abgelaufen und Schicht zu Ende
        assertEquals(0, w.staff.driverShifts.run(ctx, s).stops.size)
    }

    @Test
    fun konto_ohne_fahrerrolle_oder_gesperrte_sitzung_kein_antritt() {
        val (p, v, s) = scene(); val dev = w.device(p)
        for (ctx in listOf(w.driverCtx(p, roles = setOf("dispatcher")), w.driverCtx(p, scopes = setOf("driver:devices")), w.driverCtx(p, human = false))) {
            assertEquals(ErrorCode.FORBIDDEN, ShiftWorld.problem { w.staff.driverShifts.start(ctx, StartShiftCommand(s, v, dev)) }.code)
            assertEquals(ErrorCode.FORBIDDEN, ShiftWorld.problem { w.staff.driverShifts.run(ctx, s) }.code)
        }
        // Gerät beendet (Sitzung gesperrt/abgemeldet), an eine andere Sitzung gebunden, oder Gerät eines anderen Kontos
        val revoked = w.device(p, revoked = true); val elsewhere = w.device(p, session = "sid-andere-sitzung"); val foreignDevice = w.device(w.person())
        assertEquals(ErrorCode.FORBIDDEN, ShiftWorld.problem { w.staff.driverShifts.start(w.driverCtx(p), StartShiftCommand(s, v, revoked)) }.code)
        assertEquals(ErrorCode.FORBIDDEN, ShiftWorld.problem { w.staff.driverShifts.start(w.driverCtx(p), StartShiftCommand(s, v, elsewhere)) }.code)
        assertEquals(ErrorCode.NOT_FOUND, ShiftWorld.problem { w.staff.driverShifts.start(w.driverCtx(p), StartShiftCommand(s, v, foreignDevice)) }.code)
        assertEquals(ErrorCode.FORBIDDEN, ShiftWorld.problem { w.staff.driverShifts.start(w.driverCtx(p, session = "sid-neu"), StartShiftCommand(s, v, dev)) }.code, "Sitzung weicht von der Gerätebindung ab")
        assertEquals("PLANNED", fx.strings("SELECT state FROM taktovia.staff_shift WHERE id=?", s).single())
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.shift.started' AND object_ref=?", w.tenant.value, "staff_shift:$s"))
    }

    @Test
    fun antritt_schreibt_audit_event() {
        val (p, v, s) = scene(); val ctx = w.driverCtx(p); val dev = w.device(p)
        at("2026-12-01T05:55:00Z"); w.staff.driverShifts.start(ctx, StartShiftCommand(s, v, dev))
        at("2026-12-01T13:58:00Z"); w.staff.driverShifts.end(ctx, EndShiftCommand(s, dev))
        val started = fx.strings("SELECT actor_ref || '|' || redacted_changes::text || '|' || occurred_at::text FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.shift.started' AND object_ref=?", w.tenant.value, "staff_shift:$s").single()
        assertTrue(started.startsWith("user:${p.subject}|"), started); assertTrue(started.contains(dev.toString()), "Gerät im Audit: $started"); assertTrue(started.contains("2026-12-01T05:55:00Z"), "Zeit im Audit: $started")
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.shift.ended' AND object_ref=? AND redacted_changes::text LIKE ?", w.tenant.value, "staff_shift:$s", "%$dev%"))
        // Zeile trägt Gerät und Zeit; die Arbeitszeiterfassung bleibt getrennt (keine Zeitereignisse)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.staff_shift WHERE id=? AND started_device_id=? AND started_at='2026-12-01T05:55:00Z' AND ended_at='2026-12-01T13:58:00Z'", s, dev))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=?", w.tenant.value))
    }

    @Test
    fun schicht_mit_subject_als_driver_ref_gilt_als_eigene_schicht() {
        // Altbestand/Fixtures (WP-M3-24): driver_ref ist die IdP-Subject-UUID des Kontos
        val p = w.person(); val v = w.vehicle(); val s = UUID.randomUUID()
        fx.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?::uuid,?,?::timestamptz,?::timestamptz,'Europe/Berlin',0,'PLANNED')",
            w.tenant.value, s, p.subject, v, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z")
        at("2026-12-01T06:00:00Z")
        assertEquals(listOf(s), w.staff.driverShifts.mine(w.driverCtx(p)).map { it.shiftId })
        assertEquals("STARTED", w.staff.driverShifts.start(w.driverCtx(p), StartShiftCommand(s, v, w.device(p))).shift.state)
    }

    @Test
    fun fahrerereignis_und_zeitereignis_gelten_auch_fuer_schichten_mit_fahrerstamm_als_driver_ref() {
        val (p, v, s) = scene(); val ctx = w.driverCtx(p, scopes = setOf("driver:run", "driver:events", "driver:devices", "time-events:write")); val dev = w.device(p)
        val result = w.staff.driverEvents.record(ctx, de.taktovia.stafftime.api.DriverEventCommand(UUID.randomUUID(), dev, s, v, 1, de.taktovia.stafftime.api.DriverEventType.BREAK_START, null, null,
            Instant.parse("2026-12-01T06:00:00Z"), Instant.parse("2026-12-01T06:00:00Z"), false))
        assertEquals(de.taktovia.stafftime.api.DriverEventOutcome.APPLIED, result.outcome)
        val other = w.person()
        assertEquals(ErrorCode.NOT_FOUND, ShiftWorld.problem { w.staff.driverEvents.record(w.driverCtx(other), de.taktovia.stafftime.api.DriverEventCommand(UUID.randomUUID(), w.device(other), s, v, 1,
            de.taktovia.stafftime.api.DriverEventType.BREAK_START, null, null, Instant.parse("2026-12-01T06:00:00Z"), Instant.parse("2026-12-01T06:00:00Z"), false)) }.code)
    }

    @Test
    fun zwei_geraete_treten_gleichzeitig_an() {
        repeat(3) {
            val (p, v, s) = scene(); val ctx = w.driverCtx(p); val d1 = w.device(p); val d2 = w.device(p)
            at("2026-12-01T06:00:00Z")
            val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
            val futures = listOf(d1, d2).map { d -> pool.submit<Boolean> { start.await(); w.staff.driverShifts.start(ctx, StartShiftCommand(s, v, d)).alreadyStarted } }
            start.countDown(); val already = futures.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            assertEquals(listOf(false, true), already.sorted(), "genau ein Antritt, das zweite Gerät sieht den bestehenden Zustand")
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.shift.started' AND object_ref=?", w.tenant.value, "staff_shift:$s"))
            val device = fx.strings("SELECT started_device_id::text FROM taktovia.staff_shift WHERE id=?", s).single()
            assertTrue(device == d1.toString() || device == d2.toString())
            assertEquals("STARTED", fx.strings("SELECT state FROM taktovia.staff_shift WHERE id=?", s).single())
        }
    }

    @Test
    fun entzug_parallel_zu_run_abruf_keine_auslieferung() {
        val (p, v, s) = scene(); val ctx = w.driverCtx(p); val dev = w.device(p)
        w.release(s, v); w.unblock(v); at("2026-12-01T06:30:00Z")
        w.staff.driverShifts.start(ctx, StartShiftCommand(s, v, dev))
        val withdrawn = AtomicBoolean(false); val stop = AtomicBoolean(false); val violations = AtomicInteger(); val delivered = AtomicInteger(); val afterWithdrawal = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        val readers = (1..3).map {
            pool.submit {
                while (!stop.get()) {
                    val sawWithdrawn = withdrawn.get()      // Entzug war vor diesem Abruf bereits bestätigt
                    val n = w.staff.driverShifts.run(ctx, s).stops.size
                    if (n > 0) delivered.incrementAndGet()
                    if (sawWithdrawn) { afterWithdrawal.incrementAndGet(); if (n > 0) violations.incrementAndGet() }
                }
            }
        }
        Thread.sleep(150)
        withdraw(s, p, v); withdrawn.set(true)
        Thread.sleep(300); stop.set(true); readers.forEach { it.get(30, TimeUnit.SECONDS) }; pool.shutdown()
        assertTrue(delivered.get() > 0, "vor dem Entzug wurden Aufträge geliefert")
        assertTrue(afterWithdrawal.get() > 0, "nach dem Entzug wurde weiter abgerufen")
        assertEquals(0, violations.get(), "nach dem bestätigten Entzug wird nie ein Auftrag ausgeliefert")
    }

    private fun withdraw(shift: UUID, p: ShiftWorld.Person, v: UUID) {
        val cur = w.staff.shifts.get(w.dispatcher(), shift)
        w.staff.shifts.update(w.dispatcher(), shift, cur.revision, ShiftInput.parse(mapOf("driver_id" to p.driverId.toString(), "vehicle_id" to v.toString(), "starts_at" to cur.startsAt.toString(),
            "ends_at" to cur.endsAt.toString(), "timezone" to cur.timezone, "withdrawn" to true), update = true))
    }

    private companion object {
        val STOP = DriverRunStopView(UUID.fromString("00000000-0000-4000-8000-0000000000a5"), 1, "PICKUP", Instant.parse("2026-12-01T07:00:00Z"), null, "Haltestelle", 13.74, 51.05, 1, emptyList(), null, emptyList())
    }
}
