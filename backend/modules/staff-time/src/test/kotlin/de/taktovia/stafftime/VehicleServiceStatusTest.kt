package de.taktovia.stafftime

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.stafftime.api.DriverEventCommand
import de.taktovia.stafftime.api.DriverEventOutcome
import de.taktovia.stafftime.api.DriverEventType
import de.taktovia.stafftime.api.ServiceStatus
import de.taktovia.stafftime.api.ServiceStatusAutomaton
import de.taktovia.stafftime.api.ServiceStatusReport
import de.taktovia.stafftime.api.StartShiftCommand
import de.taktovia.stafftime.api.StatusOutcome
import de.taktovia.stafftime.api.StatusTrigger
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-31 / LH-6.6-DRV-007 (echte Datenbank, Runtime-Rolle, veränderbare Serveruhr): Fahrzeug- und Dienststatus mit den sechs Werten (plus Anfangszustand), Automat, Historie mit Auslöser, Zeit und Vorstatus,
 * Eigentümerschaft der Schicht (404), Ereignis `vehicle.service_status_changed` bei Störung, Offline-Sync (genau einmal, wirksam ab Serverempfang) und konkurrierende Meldungen.
 * Die Wirkung auf die Disposition (nicht neu disponieren) prüft `VehicleServiceStatusDispatchTest` im Modul `booking-dispatch`, die Parallelität gegen Hold und Zusage `VehicleServiceStatusRaceTest` in der Anwendung.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VehicleServiceStatusTest {
    private lateinit var fx: StaffFixture
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val registry = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012())

    @BeforeAll fun up() { fx = StaffFixture() }
    @AfterAll fun down() = fx.close()

    /** Angetretene Schicht mit gültiger Einsatzfreigabe und entsperrtem Fahrzeug; die Uhr steht auf Schichtbeginn. */
    private inner class Scene(val w: ShiftWorld = ShiftWorld(fx)) {
        val person = w.person(); val vehicle = w.vehicle()
        val shift = w.shiftRow(person, vehicle, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z")
        val ctx = w.driverCtx(person); val device = w.device(person)
        val svc get() = w.staff.serviceStatus
        init {
            w.release(shift, vehicle); w.unblock(vehicle)
            at("2026-12-01T06:00:00Z")
            w.staff.driverShifts.start(ctx, StartShiftCommand(shift, vehicle, device))
        }
        fun at(iso: String) { w.clock.now = Instant.parse(iso) }
        fun report(status: ServiceStatus, id: UUID = UUID.randomUUID(), offline: Boolean = false, occurred: Instant = w.clock.now, shiftId: UUID = shift) =
            svc.report(ctx, ServiceStatusReport(id, shiftId, null, device, status, occurred, occurred, offline))
        fun status() = svc.current(ctx, shift).status
        fun event(type: DriverEventType, id: UUID = UUID.randomUUID(), stop: UUID? = null) = w.staff.driverEvents.record(ctx, DriverEventCommand(id, device, shift, vehicle, 1, type, if (type.needsBooking) UUID.randomUUID() else null, stop, w.clock.now, w.clock.now, false))
        fun outbox(): List<String> = fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'vehicle.service_status_changed' AND (payload->>'shift_id') = ? ORDER BY aggregate_revision", w.tenant.value, shift.toString())
    }

    private fun problem(block: () -> Unit): ProblemException = ShiftWorld.problem(block)

    @Test
    fun durchlauf_sechs_status_unterscheidbar_gespeichert() {
        val s = Scene()
        assertEquals(ServiceStatus.NOT_READY, s.status(), "vor der ersten Meldung gilt der Anfangszustand")
        val path = listOf(ServiceStatus.AVAILABLE, ServiceStatus.EN_ROUTE, ServiceStatus.WAITING, ServiceStatus.AVAILABLE, ServiceStatus.BREAK, ServiceStatus.ENDED)
        for (target in path) {
            s.at("2026-12-01T%02d:00:00Z".format(6 + path.indexOf(target)))
            val r = s.report(target)
            assertEquals(StatusOutcome.APPLIED, r.outcome, target.name); assertEquals(target, r.status.status); assertEquals(target, s.status())
        }
        // die sechste Dienstzeit-Gruppe: Störung (eigene Schicht, damit ENDED endgültig bleibt) – alle sechs Werte stehen unterscheidbar in der Datenbank
        val t = Scene(); t.report(ServiceStatus.AVAILABLE); t.report(ServiceStatus.DISRUPTED)
        assertEquals(ServiceStatus.DISRUPTED, t.status())
        val stored = (fx.strings("SELECT status FROM taktovia.vehicle_service_status WHERE tenant_id = ?", s.w.tenant.value) + fx.strings("SELECT status FROM taktovia.vehicle_service_status WHERE tenant_id = ?", t.w.tenant.value)).toSet()
        assertEquals(setOf("ENDED", "DISRUPTED"), stored)
        val seen = (s.svc.current(s.ctx, s.shift).history + t.svc.current(t.ctx, t.shift).history).map { it.to }.toSet()
        assertEquals(ServiceStatus.entries.toSet(), seen, "Verfügbarkeit, Anfahrt, Wartezeit, Pause, Störung, Ende (und Anfangszustand) kommen in der Historie vor")
        assertEquals(setOf(ServiceStatus.AVAILABLE, ServiceStatus.EN_ROUTE, ServiceStatus.WAITING), ServiceStatus.entries.filter { it.dispatchable }.toSet(), "nur Verfügbarkeit, Anfahrt und Wartezeit sind disponierbar")
    }

    @Test
    fun statushistorie_mit_ausloeser_zeit_vorstatus() {
        val s = Scene()
        s.at("2026-12-01T06:01:00Z"); s.report(ServiceStatus.AVAILABLE)
        s.at("2026-12-01T06:02:00Z"); s.report(ServiceStatus.EN_ROUTE)
        s.at("2026-12-01T06:03:00Z"); s.event(DriverEventType.ARRIVAL)   // abgeleitet: Auslöser SYSTEM
        s.at("2026-12-01T06:04:00Z"); s.event(DriverEventType.BREAK_START)   // Handlung des Fahrers
        val disp = s.w.dispatcher(); s.at("2026-12-01T06:05:00Z"); s.svc.byDispatch(disp, s.shift, ServiceStatus.DISRUPTED)
        val h = s.svc.current(s.ctx, s.shift).history
        assertEquals(listOf(null, ServiceStatus.NOT_READY, ServiceStatus.AVAILABLE, ServiceStatus.EN_ROUTE, ServiceStatus.WAITING, ServiceStatus.BREAK), h.map { it.from })
        assertEquals(listOf(ServiceStatus.NOT_READY, ServiceStatus.AVAILABLE, ServiceStatus.EN_ROUTE, ServiceStatus.WAITING, ServiceStatus.BREAK, ServiceStatus.DISRUPTED), h.map { it.to })
        assertEquals(listOf(StatusTrigger.SYSTEM, StatusTrigger.DRIVER, StatusTrigger.DRIVER, StatusTrigger.SYSTEM, StatusTrigger.DRIVER, StatusTrigger.DISPATCH), h.map { it.trigger }, "Auslöser Fahrer, System, Leitstelle")
        assertEquals(listOf("06:01", "06:01", "06:02", "06:03", "06:04", "06:05").map { Instant.parse("2026-12-01T${it}:00Z") }, h.map { it.recordedAt }, "Serverzeit je Wechsel (die Anlage entsteht mit der ersten Meldung)")
        assertEquals(h.indices.map { it + 1L }, h.map { it.revision })
        assertTrue(h.all { it.shiftId == s.shift && it.vehicleId == s.vehicle })
        // Urheber als Pseudonym, nie Name: nur user:/system:-Referenzen
        val by = fx.strings("SELECT created_by FROM taktovia.vehicle_status_history WHERE tenant_id = ? AND shift_id = ? ORDER BY created_at, revision", s.w.tenant.value, s.shift)
        assertTrue(by.all { it.startsWith("user:") || it.startsWith("system:") }, by.toString())
        // unveränderlich: kein Ändern oder Löschen der Historie
        val upd = runCatching { fx.tx.run(s.w.tenant) { j -> j.update("UPDATE taktovia.vehicle_status_history SET to_status = 'ENDED' WHERE shift_id = ?", s.shift) } }
        assertTrue(upd.isFailure, "Historie ist unveränderlich")
        // Leitstelle nur mit Rolle dispatcher
        assertEquals(ErrorCode.FORBIDDEN, problem { s.svc.byDispatch(s.ctx, s.shift, ServiceStatus.NOT_READY) }.code)
        s.svc.byDispatch(disp, s.shift, ServiceStatus.NOT_READY)
        assertEquals(ServiceStatus.NOT_READY, s.status(), "Leitstelle nimmt die Störung zurück; die Bereitmeldung braucht danach wieder die Freigabe")
    }

    @Test
    fun ereignisse_leiten_anfahrt_wartezeit_pause_stoerung_ende_ab() {
        val s = Scene()
        s.report(ServiceStatus.AVAILABLE); s.report(ServiceStatus.EN_ROUTE)
        s.at("2026-12-01T07:00:00Z")
        assertEquals(DriverEventOutcome.APPLIED, s.event(DriverEventType.ARRIVAL).outcome); assertEquals(ServiceStatus.WAITING, s.status())
        s.event(DriverEventType.BREAK_START); assertEquals(ServiceStatus.BREAK, s.status(), "Pause aus der Wartezeit (Klärfall bei zugesagten Stopps, Zusagen bleiben)")
        s.event(DriverEventType.BREAK_END); assertEquals(ServiceStatus.AVAILABLE, s.status())
        s.event(DriverEventType.DISRUPTION); assertEquals(ServiceStatus.DISRUPTED, s.status())
        // verspätetes BREAK_END nach der Störung: Ereignis erfasst, Status bleibt (überschreibt nie)
        val late = s.event(DriverEventType.BREAK_END); assertEquals(DriverEventOutcome.APPLIED, late.outcome); assertEquals(ServiceStatus.DISRUPTED, s.status())
        s.event(DriverEventType.SHIFT_END); assertEquals(ServiceStatus.ENDED, s.status())
        // Ereignis ohne angetretene Schicht verändert nichts: eigene Schicht, nicht angetreten
        val p = s.w.person(); val v = s.w.vehicle(); val sh = s.w.shiftRow(p, v, "2026-12-02T06:00:00Z", "2026-12-02T14:00:00Z")
        val dev = s.w.device(p); val c2 = s.w.driverCtx(p)
        s.w.staff.driverEvents.record(c2, DriverEventCommand(UUID.randomUUID(), dev, sh, v, 1, DriverEventType.DISRUPTION, null, null, s.w.clock.now, s.w.clock.now, false))
        assertEquals(ServiceStatus.NOT_READY, s.w.staff.serviceStatus.current(c2, sh).status)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle_service_status WHERE shift_id = ?", sh))
    }

    @Test
    fun unzulaessiger_uebergang_abgewiesen() {
        val s = Scene()
        s.report(ServiceStatus.AVAILABLE); s.report(ServiceStatus.EN_ROUTE)
        val before = s.svc.current(s.ctx, s.shift)
        // Ende nach Anfahrt ohne neuen Dienstantritt; Pause aus der Anfahrt; Wiederholung desselben Status mit neuer Ereignis-ID
        for (bad in listOf(ServiceStatus.ENDED, ServiceStatus.BREAK, ServiceStatus.EN_ROUTE, ServiceStatus.NOT_READY, ServiceStatus.AVAILABLE)) {
            assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { s.report(bad) }.code, bad.name)
        }
        val after = s.svc.current(s.ctx, s.shift)
        assertEquals(before.status, after.status); assertEquals(before.revision, after.revision); assertEquals(before.history.size, after.history.size, "abgewiesen heißt: kein Statuswechsel, keine Historie, kein Ereignis")
        assertEquals(before.history.size - 1, s.outbox().size)
        // Ende ist endgültig
        s.report(ServiceStatus.DISRUPTED); s.report(ServiceStatus.ENDED)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { s.report(ServiceStatus.AVAILABLE) }.code)
        // Bereitmeldung ohne Einsatzfreigabe (CHK-001): abgewiesen, Status bleibt NOT_READY
        val n = Scene(); n.w.fx.exec("UPDATE taktovia.resource_release SET revoked_at = now() WHERE shift_id = ?", n.shift)
        val no = problem { n.report(ServiceStatus.AVAILABLE) }
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, no.code); assertTrue(no.detail!!.contains("Einsatzfreigabe"), no.detail)
        assertEquals(ServiceStatus.NOT_READY, n.status())
        // nicht angetretene Schicht: keine Statusmeldung
        val p = n.w.person(); val v = n.w.vehicle(); val sh = n.w.shiftRow(p, v, "2026-12-03T06:00:00Z", "2026-12-03T14:00:00Z"); val c = n.w.driverCtx(p); val d = n.w.device(p)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { n.svc.report(c, ServiceStatusReport(UUID.randomUUID(), sh, null, d, ServiceStatus.AVAILABLE, n.w.clock.now, n.w.clock.now, false)) }.code)
    }

    @Test
    fun db_waechter_und_dokumentierte_kanten_entsprechen_dem_automaten() {
        // (a) die Kantentabelle des Vertragsdokuments ist die des Codes
        val doc = Files.readAllLines(repo.resolve("docs/umsetzungsplan/vertraege/zustaende/fahrzeug_dienststatus.md"))
        val docEdges = doc.mapNotNull { Regex("^\\| `(\\w+)` \\| `(\\w+)` \\|").find(it) }.map { ServiceStatus.valueOf(it.groupValues[1]) to ServiceStatus.valueOf(it.groupValues[2]) }.toSet()
        assertEquals(docEdges, ServiceStatusAutomaton.edges)
        // (b) der Zustandswächter der Datenbank lässt genau diese Kanten zu (auch bei direktem SQL)
        val w = ShiftWorld(fx)
        for (from in ServiceStatus.entries) {
            val p = w.person(); val v = w.vehicle(); val sh = w.shiftRow(p, v, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z")
            fx.exec("INSERT INTO taktovia.vehicle_service_status(tenant_id, shift_id, vehicle_id, status) VALUES (?,?,?,?)", w.tenant.value, sh, v, from.name)
            for (to in ServiceStatus.entries) {
                // jeder Versuch in einer eigenen Transaktion unter der Runtime-Rolle, die immer zurückgerollt wird
                val ok = fx.ds.connection.use { c ->
                    c.autoCommit = false
                    try {
                        c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)").use { it.setString(1, w.tenant.toString()); it.execute() }
                        runCatching { c.prepareStatement("UPDATE taktovia.vehicle_service_status SET status = ? WHERE shift_id = ?").use { it.setString(1, to.name); it.setObject(2, sh); it.executeUpdate() } }.isSuccess
                    } finally { c.rollback() }
                }
                assertEquals(from == to || ServiceStatusAutomaton.exists(from, to), ok, "$from -> $to")
            }
        }
    }

    @Test
    fun fremde_schicht_meldet_status_ergibt_404() {
        val s = Scene(); val other = Scene()
        // anderer Fahrer desselben Mandanten
        val thief = s.w.person(); val tctx = s.w.driverCtx(thief); val tdev = s.w.device(thief)
        fun foreign(ctx: de.taktovia.platform.context.RequestContext, dev: UUID, shift: UUID) =
            problem { s.svc.report(ctx, ServiceStatusReport(UUID.randomUUID(), shift, null, dev, ServiceStatus.AVAILABLE, s.w.clock.now, s.w.clock.now, false)) }
        assertEquals(ErrorCode.NOT_FOUND, foreign(tctx, tdev, s.shift).code)
        assertEquals(ErrorCode.NOT_FOUND, foreign(s.ctx, s.device, UUID.randomUUID()).code, "unbekannte Schicht")
        // Schicht eines anderen Mandanten mit eigenem Konto und Gerät
        assertEquals(ErrorCode.NOT_FOUND, foreign(s.ctx, s.device, other.shift).code)
        assertEquals(ErrorCode.NOT_FOUND, problem { s.svc.current(tctx, s.shift) }.code)
        // fremdes Fahrzeug an der eigenen Schicht
        assertEquals(ErrorCode.NOT_FOUND, problem { s.svc.report(s.ctx, ServiceStatusReport(UUID.randomUUID(), s.shift, other.vehicle, s.device, ServiceStatus.AVAILABLE, s.w.clock.now, s.w.clock.now, false)) }.code)
        // fremdes Gerät: 404, kein Fahrer-Konto (Dienstkonto): 403
        assertEquals(ErrorCode.NOT_FOUND, foreign(s.ctx, tdev, s.shift).code)
        assertEquals(ErrorCode.FORBIDDEN, problem { s.svc.report(s.w.driverCtx(s.person, human = false), ServiceStatusReport(UUID.randomUUID(), s.shift, null, s.device, ServiceStatus.AVAILABLE, s.w.clock.now, s.w.clock.now, false)) }.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle_service_status WHERE shift_id IN (?, ?)", s.shift, other.shift), "nichts gespeichert")
        assertEquals(0, s.outbox().size)
    }

    @Test
    fun stoerung_erzeugt_outbox_ereignis() {
        val s = Scene(); s.report(ServiceStatus.AVAILABLE)
        val before = s.outbox().size
        s.at("2026-12-01T09:30:00Z"); val r = s.report(ServiceStatus.DISRUPTED)
        assertEquals(StatusOutcome.APPLIED, r.outcome)
        val events = s.outbox(); assertEquals(before + 1, events.size)
        val schema = registry.getSchema(Files.readString(repo.resolve("api/events/vehicle.service_status_changed.v1.schema.json")))
        events.forEach { assertEquals(emptyList<Any>(), schema.validate(it, InputFormat.JSON).toList().map { m -> m.message }, "Payload entspricht dem Ereignisschema: $it") }
        val last = events.last()
        assertTrue(last.contains("\"current\": \"DISRUPTED\"") || last.contains("\"current\":\"DISRUPTED\""), last)
        assertTrue(last.contains("AVAILABLE") && last.contains("DRIVER") && last.contains(s.vehicle.toString()), last)
        // Ereignis und Wechsel sind eine Transaktion: ein abgewiesener Wechsel hinterlässt kein Ereignis
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { s.report(ServiceStatus.EN_ROUTE) }.code)
        assertEquals(before + 1, s.outbox().size)
        // Störung wird nie abgewiesen, auch nicht vor der Bereitmeldung
        val n = Scene(); n.report(ServiceStatus.DISRUPTED); assertEquals(ServiceStatus.DISRUPTED, n.status())
        assertEquals(1, n.outbox().count { it.contains("DISRUPTED") })
    }

    @Test
    fun zwei_gleichzeitige_statusmeldungen_ein_endstatus() {
        repeat(12) { round ->
            val s = Scene(); s.report(ServiceStatus.AVAILABLE)
            val base = s.svc.current(s.ctx, s.shift)
            val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
            // Anfahrt und Pause schließen sich aus: nach der einen ist die andere keine zulässige Kante mehr
            val targets = listOf(ServiceStatus.EN_ROUTE, ServiceStatus.BREAK)
            val results = targets.map { t -> pool.submit(Callable { start.await(); runCatching { s.report(t) } }) }
            start.countDown(); val out = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            val won = out.withIndex().filter { it.value.isSuccess }
            assertEquals(1, won.size, "Runde $round: genau eine Meldung wirkt, die andere ist 409: ${out.map { it.exceptionOrNull() }}")
            assertTrue(out.filter { it.isFailure }.all { (it.exceptionOrNull() as? ProblemException)?.code == ErrorCode.INVALID_STATE_TRANSITION })
            val view = s.svc.current(s.ctx, s.shift)
            assertEquals(targets[won.single().index], view.status, "Endstatus ist der des Gewinners")
            assertEquals(base.history.size + 1, view.history.size); assertEquals(base.revision + 1, view.revision)
            assertEquals(view.status, view.history.last().to); assertEquals(ServiceStatus.AVAILABLE, view.history.last().from)
        }
    }

    @Test
    fun offline_statusmeldung_genau_einmal_wirksam_ab_serverempfang() {
        val s = Scene(); s.at("2026-12-01T06:01:00Z"); s.report(ServiceStatus.AVAILABLE)
        // Störung wird um 06:10 auf dem Gerät erfasst (ohne Netz), der Server empfängt sie um 06:40
        val id = UUID.randomUUID(); val device = Instant.parse("2026-12-01T06:10:00Z")
        s.at("2026-12-01T06:20:00Z"); assertEquals(ServiceStatus.AVAILABLE, s.status(), "bis zum Serverempfang bleibt das Fahrzeug disponierbar")
        s.at("2026-12-01T06:40:00Z")
        val first = s.report(ServiceStatus.DISRUPTED, id, offline = true, occurred = device)
        assertEquals(StatusOutcome.APPLIED, first.outcome)
        val row = s.svc.current(s.ctx, s.shift)
        assertEquals(Instant.parse("2026-12-01T06:40:00Z"), row.changedAt, "wirksam ab Serverempfang, nicht ab Gerätezeit")
        val h = row.history.last(); assertEquals(device, h.occurredAt); assertEquals(Instant.parse("2026-12-01T06:40:00Z"), h.recordedAt); assertTrue(h.offlineRecorded)
        // erneute Sendung (Sync-Wiederholung, auch parallel): genau einmal wirksam
        val pool = Executors.newFixedThreadPool(8); val start = CountDownLatch(1)
        val rs = (1..20).map { pool.submit(Callable { start.await(); s.report(ServiceStatus.DISRUPTED, id, offline = true, occurred = device) }) }
        start.countDown(); val outcomes = rs.map { it.get(60, TimeUnit.SECONDS).outcome }; pool.shutdown()
        assertTrue(outcomes.all { it == StatusOutcome.DUPLICATE }, outcomes.toString())
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle_status_history WHERE shift_id = ? AND event_id = ?", s.shift, id))
        assertEquals(1, s.outbox().count { it.contains("DISRUPTED") }, "ein Ereignis")
        assertEquals(row.revision, s.svc.current(s.ctx, s.shift).revision)
        // dieselbe Ereignis-ID mit anderem Ziel: Idempotenzkonflikt
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, problem { s.report(ServiceStatus.ENDED, id, offline = true, occurred = device) }.code)
        // offline erfasste Meldung, die zum Serverstand nicht mehr passt oder älter ist: Klärfall, keine Änderung
        s.at("2026-12-01T07:00:00Z")
        val stale = s.report(ServiceStatus.ENDED, offline = true, occurred = Instant.parse("2026-12-01T06:30:00Z"))   // 06:30 < Wechsel um 06:40
        assertEquals(StatusOutcome.REVIEW_REQUIRED, stale.outcome); assertEquals("STALE_EVENT", stale.reasonCode)
        val invalid = s.report(ServiceStatus.BREAK, offline = true, occurred = Instant.parse("2026-12-01T06:55:00Z"))
        assertEquals(StatusOutcome.REVIEW_REQUIRED, invalid.outcome); assertEquals("INVALID_TRANSITION", invalid.reasonCode)
        assertEquals(ServiceStatus.DISRUPTED, s.status(), "Offline-Meldungen überschreiben nie einen neueren Serverstand")
        assertEquals(row.history.size, s.svc.current(s.ctx, s.shift).history.size)
    }

    @Test
    fun letzter_stopp_beendet_die_wartezeit_mit_verfuegbarkeit() {
        val s = Scene(); s.report(ServiceStatus.AVAILABLE); s.report(ServiceStatus.EN_ROUTE)
        val (first, last) = twoStops(s)
        s.event(DriverEventType.ARRIVAL, stop = first); assertEquals(ServiceStatus.WAITING, s.status())
        s.event(DriverEventType.CHECK_IN, stop = first); assertEquals(ServiceStatus.EN_ROUTE, s.status(), "weitere Stopps folgen")
        s.event(DriverEventType.ARRIVAL, stop = last); s.event(DriverEventType.CHECK_OUT, stop = last)
        assertEquals(ServiceStatus.AVAILABLE, s.status(), "letzter Stopp abgearbeitet: wieder verfügbar")
        assertNull(s.svc.current(s.ctx, s.shift).history.first().from, "die Historie beginnt mit der Anlage ohne Vorstatus")
        assertFalse(s.svc.current(s.ctx, s.shift).history.any { it.to == ServiceStatus.ENDED })
    }

    /** Umlauf mit zwei Stopps für die Schicht der Szene (direkt per SQL, der Fahrtenplan gehört `booking-dispatch`; nur Testaufbau). */
    private fun twoStops(s: Scene): Pair<UUID, UUID> {
        val t = s.w.tenant.value; val run = UUID.randomUUID()
        fx.exec("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (?,?,?,?,1,'ACTIVE')", t, run, s.vehicle, s.shift)
        val ids = (0..1).map { seq ->
            val rev = UUID.randomUUID(); val stop = UUID.randomUUID(); val rs = UUID.randomUUID()
            fx.exec("INSERT INTO taktovia.stop_revision(tenant_id,id,stop_id,revision,point,name,accessibility,approved_by,valid_from) VALUES (?,?,?,1,ST_SetSRID(ST_MakePoint(9.7,52.3),4326),?,'{}'::jsonb,'system:test','2026-01-01T00:00:00Z')", t, rev, stop, "S$seq")
            fx.exec("INSERT INTO taktovia.route_stop(tenant_id,id,run_id,run_revision,seq,stop_revision_id,planned_at) VALUES (?,?,?,1,?,?,'2026-12-01T07:00:00Z')", t, rs, run, seq, rev)
            rs
        }
        return ids[0] to ids[1]
    }
}
