package de.taktovia.stafftime

import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.retention.ErasureEngine
import de.taktovia.platform.retention.JobStatus
import de.taktovia.platform.retention.RetentionAction
import de.taktovia.stafftime.api.PositionBatch
import de.taktovia.stafftime.api.PositionRejection
import de.taktovia.stafftime.api.PositionRetention
import de.taktovia.stafftime.api.StafftimeSettings
import de.taktovia.stafftime.api.StartShiftCommand
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * WP-M3-34 über das Modul `staff-time` (echte Datenbank, Runtime-Rolle, veränderbare Serveruhr): Positionserfassung mit getrennter Mess- und Empfangszeit und Standortalter.
 * Positiv: laufender Strom, Alter unter der Schwelle. Negativ: AT-10 (Strom bricht ab: „veraltet“ mit letztem Stand, nie extrapoliert), Position ohne Dienst, Zukunftszeit, fremdes
 * Fahrzeug, fremde Schicht, Gerätezeit nicht als Serverzeit, keine Aufbewahrungsfrist in Produktion. Konkurrierend: verspätete ältere Position überschreibt die neuere nicht.
 * Löschung der Rohpositionen nach der Frist des Aufbewahrungsregisters durch den Löschlauf.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PositionCaptureTest {
    private lateinit var fx: StaffFixture
    private lateinit var w: ShiftWorld

    @BeforeAll fun up() { fx = StaffFixture(); w = ShiftWorld(fx) }
    @AfterAll fun down() = fx.close()

    private fun at(iso: String) { w.clock.now = Instant.parse(iso) }
    private val operator get() = w.dispatcher(scopes = setOf("operator:read"))

    /** Angetretene Schicht 06:00-14:00; die Uhr steht danach auf 06:00:00. */
    private class Scene(val p: ShiftWorld.Person, val vehicle: UUID, val shift: UUID, val device: UUID)

    private fun started(world: ShiftWorld = w): Scene {
        val p = world.person(); val v = world.vehicle(); val s = world.shiftRow(p, v, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z"); val d = world.device(p)
        world.clock.now = Instant.parse("2026-12-01T06:00:00Z")
        world.staff.driverShifts.start(world.driverCtx(p), StartShiftCommand(s, v, d))
        return Scene(p, v, s, d)
    }

    private fun batch(sc: Scene, vararg samples: Triple<String, Double, Double>, vehicle: UUID = sc.vehicle, shift: UUID = sc.shift): PositionBatch = PositionBatch.parse(mapOf(
        "shift_id" to shift.toString(), "vehicle_id" to vehicle.toString(), "device_id" to sc.device.toString(),
        "samples" to samples.map { (t, lat, lon) -> mapOf("position" to mapOf("latitude" to lat, "longitude" to lon), "accuracy_meters" to 5.0, "measured_at" to t) },
    ))

    private fun send(world: ShiftWorld, sc: Scene, b: PositionBatch) = world.staff.positions.record(world.driverCtx(sc.p), b)

    @Test
    fun laufender_strom_standortalter_unter_der_schwelle() {
        val sc = started()
        var last: Triple<String, Double, Double>? = null
        for (i in 1..6) {          // alle 10 s eine Messung, Empfang 2 s nach der Messung (Normalbetrieb)
            val measured = Instant.parse("2026-12-01T06:00:00Z").plusSeconds(10L * i)
            at(measured.plusSeconds(2).toString())
            last = Triple(measured.toString(), 51.05 + i * 0.001, 13.74 + i * 0.001)
            val r = send(w, sc, batch(sc, last)); assertEquals(1, r.accepted); assertTrue(r.rejected.isEmpty())
        }
        val p = w.staff.positions.latest(operator, listOf(sc.vehicle)).getValue(sc.vehicle)
        assertEquals(2L, p.ageSeconds); assertFalse(p.stale); assertEquals(last!!.third, p.longitude, 1e-9); assertEquals(last.second, p.latitude, 1e-9)
        assertEquals(60L, java.time.Duration.between(Instant.parse("2026-12-01T06:00:00Z"), p.measuredAt).seconds)
        // Messung und Empfang sind getrennt gespeichert; die Differenz (Erfassung bis Empfang) liegt im Normalbetrieb unter 30 s (Kap. 08 §8.6)
        val delays = fx.strings("SELECT extract(epoch from received_at - measured_at)::text FROM taktovia.position_sample WHERE tenant_id=? AND shift_id=?", w.tenant.value, sc.shift)
        assertEquals(6, delays.size); assertTrue(delays.all { it.toDouble() == 2.0 }, delays.toString())
    }

    @Test
    fun at_10_unterbrochener_strom_zeigt_veraltet_mit_letztem_stand_ohne_extrapolation() {
        val sc = started()
        at("2026-12-01T06:05:02Z"); send(w, sc, batch(sc, Triple("2026-12-01T06:05:00Z", 51.0500, 13.7400)))
        at("2026-12-01T06:05:30Z"); assertFalse(w.staff.positions.latest(operator, listOf(sc.vehicle)).getValue(sc.vehicle).stale, "30 s alt ist noch nicht über der Schwelle")
        at("2026-12-01T06:05:31Z")
        val stale = w.staff.positions.latest(operator, listOf(sc.vehicle)).getValue(sc.vehicle)
        assertTrue(stale.stale); assertEquals(31L, stale.ageSeconds)
        at("2026-12-01T06:20:00Z")        // lange danach: weiter der letzte gemessene Punkt, keine bewegte oder fortgeschriebene Position
        val later = w.staff.positions.latest(operator, listOf(sc.vehicle)).getValue(sc.vehicle)
        assertTrue(later.stale); assertEquals(51.05, later.latitude, 1e-9); assertEquals(13.74, later.longitude, 1e-9); assertEquals(stale.measuredAt, later.measuredAt); assertEquals(900L, later.ageSeconds)
        assertTrue(w.staff.positions.latest(operator, listOf(UUID.randomUUID())).isEmpty(), "ohne Messung gibt es keine Position")
    }

    @Test
    fun nachgelieferte_positionen_werden_mit_messzeit_gespeichert_und_nicht_als_aktuell_dargestellt() {
        val sc = started()
        at("2026-12-01T06:10:00Z"); send(w, sc, batch(sc, Triple("2026-12-01T06:09:58Z", 51.2, 13.8)))
        // Funkloch, danach Wiederverbindung: ältere Punkte kommen um 06:30 an
        at("2026-12-01T06:30:00Z")
        val r = send(w, sc, batch(sc, Triple("2026-12-01T06:12:00Z", 51.3, 13.9), Triple("2026-12-01T06:14:00Z", 51.4, 14.0)))
        assertEquals(2, r.accepted)
        assertEquals(listOf("2026-12-01T06:12:00Z", "2026-12-01T06:14:00Z"), fx.strings("SELECT to_char(measured_at at time zone 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM taktovia.position_sample WHERE tenant_id=? AND shift_id=? AND measured_at > '2026-12-01T06:10:00Z' ORDER BY measured_at", w.tenant.value, sc.shift))
        val p = w.staff.positions.latest(operator, listOf(sc.vehicle)).getValue(sc.vehicle)
        assertEquals(Instant.parse("2026-12-01T06:14:00Z"), p.measuredAt); assertTrue(p.stale, "nachgelieferte Punkte sind keine aktuelle Position"); assertEquals(960L, p.ageSeconds)
        // erneute Sendung derselben Punkte (Outbox-Wiederholung): idempotent, keine zweite Zeile
        assertEquals(2, send(w, sc, batch(sc, Triple("2026-12-01T06:12:00Z", 51.3, 13.9), Triple("2026-12-01T06:14:00Z", 51.4, 14.0))).accepted)
        assertEquals(3, fx.count("SELECT count(*) FROM taktovia.position_sample WHERE tenant_id=? AND shift_id=?", w.tenant.value, sc.shift))
    }

    @Test
    fun position_ohne_dienst_wird_nicht_angenommen_oder_gespeichert() {
        val p = w.person(); val v = w.vehicle(); val d = w.device(p)
        val planned = w.shiftRow(p, v, "2026-12-02T06:00:00Z", "2026-12-02T14:00:00Z")
        at("2026-12-02T06:10:00Z")
        val sc = Scene(p, v, planned, d)
        // nicht angetretene Schicht
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ShiftWorld.problem { send(w, sc, batch(sc, Triple("2026-12-02T06:09:00Z", 51.0, 13.0))) }.code)
        // beendeter Dienst: Punkte aus dem Dienst werden nachgeliefert, Punkte außerhalb einzeln abgewiesen
        val s2 = started(); at("2026-12-01T10:00:00Z")
        w.staff.driverShifts.end(w.driverCtx(s2.p), de.taktovia.stafftime.api.EndShiftCommand(s2.shift, s2.device))
        at("2026-12-01T10:05:00Z")
        val r = send(w, s2, batch(s2, Triple("2026-12-01T09:59:00Z", 51.0, 13.0), Triple("2026-12-01T10:02:00Z", 51.0, 13.0), Triple("2026-12-01T05:59:59Z", 51.0, 13.0)))
        assertEquals(1, r.accepted); assertEquals(setOf(1 to PositionRejection.OUTSIDE_SERVICE, 2 to PositionRejection.OUTSIDE_SERVICE), r.rejected.map { it.index to it.reason }.toSet())
        // entzogene Schicht
        val s3 = started(); at("2026-12-01T06:30:00Z")
        fx.exec("UPDATE taktovia.staff_shift SET state='CANCELLED' WHERE id=?", s3.shift)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ShiftWorld.problem { send(w, s3, batch(s3, Triple("2026-12-01T06:29:00Z", 51.0, 13.0))) }.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.position_sample WHERE tenant_id=? AND shift_id IN (?,?)", w.tenant.value, planned, s3.shift))
        // keine Angabe über den Dienst hinweg ohne Rolle oder Recht
        assertEquals(ErrorCode.FORBIDDEN, ShiftWorld.problem { w.staff.positions.record(w.driverCtx(p, roles = setOf("dispatcher")), batch(sc, Triple("2026-12-02T06:09:00Z", 51.0, 13.0))) }.code)
        assertEquals(ErrorCode.FORBIDDEN, ShiftWorld.problem { w.staff.positions.record(w.driverCtx(p, scopes = setOf("driver:run")), batch(sc, Triple("2026-12-02T06:09:00Z", 51.0, 13.0))) }.code)
    }

    @Test
    fun position_mit_zukunftszeit_und_fremdes_fahrzeug_oder_fremde_schicht_werden_abgewiesen() {
        val sc = started(); at("2026-12-01T07:00:00Z")
        val r = send(w, sc, batch(sc, Triple("2026-12-01T07:00:03Z", 51.0, 13.0), Triple("2026-12-01T07:00:10Z", 51.0, 13.0), Triple("2026-12-01T09:00:00Z", 51.0, 13.0)))
        assertEquals(1, r.accepted); assertEquals(listOf(1 to PositionRejection.FUTURE_TIME, 2 to PositionRejection.FUTURE_TIME), r.rejected.map { it.index to it.reason }, "bis zur Toleranz (5 s) akzeptiert, darüber abgewiesen")
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.position_sample WHERE tenant_id=? AND shift_id=?", w.tenant.value, sc.shift))
        // fremdes Fahrzeug (Fahrzeug eines anderen Dienstes), unbekannte und fremde Schicht, Schicht eines anderen Mandanten
        val other = started()
        assertEquals(ErrorCode.NOT_FOUND, ShiftWorld.problem { send(w, sc, batch(sc, Triple("2026-12-01T07:00:00Z", 51.0, 13.0), vehicle = other.vehicle)) }.code)
        assertEquals(ErrorCode.NOT_FOUND, ShiftWorld.problem { send(w, sc, batch(sc, Triple("2026-12-01T07:00:00Z", 51.0, 13.0), shift = other.shift)) }.code)
        assertEquals(ErrorCode.NOT_FOUND, ShiftWorld.problem { w.staff.positions.record(w.driverCtx(other.p), batch(sc, Triple("2026-12-01T07:00:00Z", 51.0, 13.0))) }.code, "Schicht eines anderen Fahrers")
        val w2 = ShiftWorld(fx); val foreign = started(w2)
        assertEquals(ErrorCode.NOT_FOUND, ShiftWorld.problem { send(w, sc, batch(sc, Triple("2026-12-01T07:00:00Z", 51.0, 13.0), shift = foreign.shift, vehicle = foreign.vehicle)) }.code)
        // Gerät beendet oder fremd
        val dead = w.device(sc.p, revoked = true)
        assertEquals(ErrorCode.FORBIDDEN, ShiftWorld.problem { send(w, Scene(sc.p, sc.vehicle, sc.shift, dead), batch(Scene(sc.p, sc.vehicle, sc.shift, dead), Triple("2026-12-01T07:00:00Z", 51.0, 13.0))) }.code)
        // Eingabe: Pflichtfelder, Zeit ohne Offset, Breite außerhalb, leere Liste
        for (raw in listOf(mapOf("shift_id" to sc.shift.toString()), mapOf("shift_id" to sc.shift.toString(), "vehicle_id" to sc.vehicle.toString(), "device_id" to sc.device.toString(), "samples" to emptyList<Any>()),
            mapOf("shift_id" to sc.shift.toString(), "vehicle_id" to sc.vehicle.toString(), "device_id" to sc.device.toString(), "samples" to listOf(mapOf("position" to mapOf("latitude" to 91.0, "longitude" to 0.0), "accuracy_meters" to 1.0, "measured_at" to "2026-12-01T07:00:00Z"))),
            mapOf("shift_id" to sc.shift.toString(), "vehicle_id" to sc.vehicle.toString(), "device_id" to sc.device.toString(), "samples" to listOf(mapOf("position" to mapOf("latitude" to 1.0, "longitude" to 0.0), "accuracy_meters" to 1.0, "measured_at" to "2026-12-01T07:00:00"))),
            mapOf("tenant_id" to UUID.randomUUID().toString()))) assertEquals(ErrorCode.VALIDATION_FAILED, ShiftWorld.problem { PositionBatch.parse(raw) }.code, raw.toString())
    }

    @Test
    fun geraetezeit_ist_keine_serverzeit_empfangszeit_kommt_von_der_serveruhr() {
        val sc = started(); at("2026-12-01T08:00:20Z")
        // Gerät mit falscher Uhr (eine Stunde nach): die Messzeit wird wie gemeldet gespeichert, die Empfangszeit stammt allein vom Server
        send(w, sc, batch(sc, Triple("2026-12-01T07:00:20Z", 51.0, 13.0)))
        val row = fx.strings("SELECT measured_at::text || '|' || received_at::text FROM taktovia.position_sample WHERE tenant_id=? AND shift_id=?", w.tenant.value, sc.shift).single()
        assertTrue(row.startsWith("2026-12-01 07:00:20") && row.contains("2026-12-01 08:00:20"), row)
        val p = w.staff.positions.latest(operator, listOf(sc.vehicle)).getValue(sc.vehicle)
        assertTrue(p.stale); assertEquals(3600L, p.ageSeconds)
        assertNotNull(p.receivedAt)
    }

    @Test
    fun verspaetete_aeltere_position_ueberschreibt_die_neuere_letzte_position_nicht() {
        repeat(5) { round ->
            val sc = started(); at("2026-12-01T09:00:00Z")
            val newer = batch(sc, Triple("2026-12-01T08:59:50Z", 51.9, 13.9)); val older = batch(sc, Triple("2026-12-01T08:55:00Z", 51.1, 13.1))
            val order = if (round % 2 == 0) listOf(newer, older) else listOf(older, newer)
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            val results = order.map { b -> pool.submit<Int> { go.await(); send(w, sc, b).accepted } }
            go.countDown(); results.forEach { assertEquals(1, it.get(60, TimeUnit.SECONDS)) }; pool.shutdown()
            val p = w.staff.positions.latest(operator, listOf(sc.vehicle)).getValue(sc.vehicle)
            assertEquals(Instant.parse("2026-12-01T08:59:50Z"), p.measuredAt, "Runde $round: die jüngste MESSzeit gilt, nicht die letzte Empfangszeit"); assertEquals(51.9, p.latitude, 1e-9)
            assertEquals(2, fx.count("SELECT count(*) FROM taktovia.position_sample WHERE tenant_id=? AND shift_id=?", w.tenant.value, sc.shift))
        }
    }

    @Test
    fun ohne_hinterlegte_aufbewahrungsfrist_ist_die_erfassung_in_produktion_nicht_aktivierbar() {
        val prod = ShiftWorld(fx, settings = StafftimeSettings(positionRequiresRetentionRule = true)); val sc = started(prod)
        prod.clock.now = Instant.parse("2026-12-01T07:00:00Z")
        val e = ShiftWorld.problem { send(prod, sc, batch(sc, Triple("2026-12-01T06:59:00Z", 51.0, 13.0))) }
        assertEquals(ErrorCode.CONFLICT, e.code); assertTrue(e.detail!!.contains("Aufbewahrungsfrist"), e.detail)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.position_sample WHERE tenant_id=?", prod.tenant.value))
        // Entwurf einer Regel genügt nicht, nur eine freigegebene Regel (Vier-Augen) aktiviert die Erfassung
        fx.exec("INSERT INTO taktovia.retention_rule(tenant_id,id,data_class,retention_days,action,status,changed_by) VALUES (?,?,'LK_STANDORT',30,'DELETE','DRAFT','user:a')", prod.tenant.value, UUID.randomUUID())
        assertEquals(ErrorCode.CONFLICT, ShiftWorld.problem { send(prod, sc, batch(sc, Triple("2026-12-01T06:59:00Z", 51.0, 13.0))) }.code)
        fx.exec("UPDATE taktovia.retention_rule SET status='APPROVED', approved_by='user:b', approved_at=now() WHERE tenant_id=? AND data_class='LK_STANDORT'", prod.tenant.value)
        assertEquals(1, send(prod, sc, batch(sc, Triple("2026-12-01T06:59:00Z", 51.0, 13.0))).accepted)
        // Nicht-Produktion (Standard) erfasst auch ohne Regel (Entwicklung und Tests)
        val dev = started(); w.clock.now = Instant.parse("2026-12-01T07:00:00Z")
        assertEquals(1, send(w, dev, batch(dev, Triple("2026-12-01T06:59:00Z", 51.0, 13.0))).accepted)
    }

    @Test
    fun rohpositionen_werden_nach_der_frist_des_aufbewahrungsregisters_geloescht() {
        val world = ShiftWorld(fx); val sc = started(world); world.clock.now = Instant.parse("2026-12-01T07:00:00Z")
        send(world, sc, batch(sc, Triple("2026-12-01T06:58:00Z", 51.0, 13.0)))
        // zwei alte Positionen (Empfang vor 40 bzw. 10 Tagen) direkt anlegen
        for ((days, minute) in listOf(40 to 1, 10 to 2)) fx.exec(
            "INSERT INTO taktovia.position_sample(tenant_id,id,vehicle_id,shift_id,position,accuracy_m,measured_at,received_at,created_by) VALUES (?,?,?,?,ST_GeogFromText('POINT(13 51)'),5,?::timestamptz,now() - make_interval(days => ?),'system:test')",
            world.tenant.value, UUID.randomUUID(), sc.vehicle, sc.shift, "2026-12-01T06:0$minute:00Z", days)
        val target = PositionRetention.target()
        assertEquals("LK_STANDORT", target.dataClass); assertEquals(setOf(RetentionAction.DELETE), target.supportedActions)
        // Regel (freigegeben, 30 Tage) und Löschlauf der Plattform
        val rule = UUID.randomUUID(); val job = UUID.randomUUID()
        fx.exec("INSERT INTO taktovia.retention_rule(tenant_id,id,data_class,retention_days,action,status,changed_by,approved_by,approved_at) VALUES (?,?,'LK_STANDORT',30,'DELETE','APPROVED','user:a','user:b',now())", world.tenant.value, rule)
        fx.exec("INSERT INTO taktovia.erasure_job(tenant_id,id,rule_id,rule_revision,data_class,action,retention_days,created_by) VALUES (?,?,?,1,'LK_STANDORT','DELETE',30,'system:test')", world.tenant.value, job, rule)
        val view = ErasureEngine(fx.tx, listOf(target), JdbcAuditPort()).run(world.tenant, job)
        assertEquals(JobStatus.COMPLETED, view.status); assertEquals(1, view.processedCount)
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.position_sample WHERE tenant_id=?", world.tenant.value), "die Position von vor 40 Tagen ist gelöscht, die jüngeren bleiben")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.position_sample WHERE tenant_id=? AND received_at < now() - interval '30 days'", world.tenant.value))
        // Wiederholung ist wirkungslos; ändern per UPDATE bleibt verboten
        assertEquals(JobStatus.COMPLETED, ErasureEngine(fx.tx, listOf(target), JdbcAuditPort()).run(world.tenant, job).status)
        assertNull(fx.strings("SELECT 'x' FROM taktovia.position_sample WHERE tenant_id=? AND false", world.tenant.value).firstOrNull())
    }
}
