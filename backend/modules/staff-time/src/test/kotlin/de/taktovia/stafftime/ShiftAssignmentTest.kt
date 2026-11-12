package de.taktovia.stafftime

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.stafftime.api.ShiftInput
import de.taktovia.stafftime.api.ShiftView
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
 * WP-M3-07 / LH-6.8-FLT-003 über das Modul `staff-time` (echte Datenbank, Runtime-Rolle): Doppelte Fahrerschicht ohne Ausnahme erzeugt einen Konflikt (409 mit Ursache); die Gegenprobe
 * für das Fahrzeug, aneinandergrenzende Schichten, die dokumentierte Ausnahme, Pflichtfelder, Zeitumstellung am 25.10.2026 über absolute Zeitpunkte, Audit, Mandantentrennung und
 * zwei parallele Transaktionen. Die Datenbank-Gegenstücke (Trigger, jeder Schreibpfad) stehen in `db/tests/test_schichtueberlappung.py`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShiftAssignmentTest {
    private lateinit var fx: StaffFixture
    private lateinit var w: ShiftWorld

    @BeforeAll fun up() { fx = StaffFixture(); w = ShiftWorld(fx) }
    @AfterAll fun down() = fx.close()

    private fun input(driver: UUID, vehicle: UUID, from: String, to: String, extra: Map<String, Any?> = emptyMap()) = ShiftInput.parse(
        mapOf("driver_id" to driver.toString(), "vehicle_id" to vehicle.toString(), "starts_at" to from, "ends_at" to to, "timezone" to "Europe/Berlin") + extra, update = false)

    private fun create(driver: UUID, vehicle: UUID, from: String, to: String, extra: Map<String, Any?> = emptyMap()): ShiftView = w.staff.shifts.create(w.dispatcher(), input(driver, vehicle, from, to, extra))

    @Test
    fun aufeinanderfolgende_schichten_werden_gespeichert_und_auditiert() {
        val p = w.person(); val v = w.vehicle()
        val a = create(p.driverId, v, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z")
        val b = create(p.driverId, v, "2026-12-01T14:00:00Z", "2026-12-01T22:00:00Z")      // Ende = Beginn, derselbe Fahrer und dasselbe Fahrzeug
        assertEquals("PLANNED", a.state); assertEquals(0L, a.rosterRevision); assertEquals(1L, a.revision); assertEquals(480L, a.durationMinutes); assertNull(a.overlapException)
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.staff_shift WHERE tenant_id=? AND driver_ref=?", w.tenant.value, p.driverId))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.shift.created' AND object_ref=?", w.tenant.value, "staff_shift:${a.id}"))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.shift.created' AND object_ref=?", w.tenant.value, "staff_shift:${b.id}"))
    }

    @Test
    fun doppelschicht_ohne_ausnahme_ergibt_409() {
        val p = w.person(); val v1 = w.vehicle(); val v2 = w.vehicle()
        val first = create(p.driverId, v1, "2026-12-02T06:00:00Z", "2026-12-02T14:00:00Z")
        // Originalabnahme: doppelte Fahrerschicht (anderes Fahrzeug) ohne Ausnahme -> Konflikt mit Ursache Fahrer und der kollidierenden Schicht
        val e = ShiftWorld.problem { create(p.driverId, v2, "2026-12-02T13:00:00Z", "2026-12-02T20:00:00Z") }
        assertEquals(ErrorCode.CONFLICT, e.code)
        assertEquals("/driver_id", e.violations.single().pointer)
        assertTrue(e.detail!!.contains(first.id.toString()), e.detail)
        // Fahrzeug: anderer Fahrer, dasselbe Fahrzeug
        val q = w.person()
        val f = ShiftWorld.problem { create(q.driverId, v1, "2026-12-02T05:00:00Z", "2026-12-02T06:30:00Z") }
        assertEquals(ErrorCode.CONFLICT, f.code); assertEquals("/vehicle_id", f.violations.single().pointer); assertTrue(f.detail!!.contains(first.id.toString()))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.staff_shift WHERE tenant_id=? AND driver_ref=?", w.tenant.value, p.driverId), "nichts gespeichert")
        // Änderung in die Überlappung hinein: 409; Änderung ohne Fremdberührung: ok (die Schicht blockiert sich nicht selbst)
        val other = create(q.driverId, v2, "2026-12-02T15:00:00Z", "2026-12-02T18:00:00Z")
        fun move(driver: UUID, vehicle: UUID, from: String, to: String) = ShiftInput.parse(mapOf("driver_id" to driver.toString(), "vehicle_id" to vehicle.toString(), "starts_at" to from, "ends_at" to to, "timezone" to "Europe/Berlin"), update = true)
        val intoDriver = ShiftWorld.problem { w.staff.shifts.update(w.dispatcher(), other.id, other.revision, move(p.driverId, v2, "2026-12-02T13:00:00Z", "2026-12-02T18:00:00Z")) }
        assertEquals(ErrorCode.CONFLICT, intoDriver.code); assertEquals("/driver_id", intoDriver.violations.single().pointer)
        val intoVehicle = ShiftWorld.problem { w.staff.shifts.update(w.dispatcher(), other.id, other.revision, move(q.driverId, v1, "2026-12-02T13:00:00Z", "2026-12-02T18:00:00Z")) }
        assertEquals(ErrorCode.CONFLICT, intoVehicle.code); assertEquals("/vehicle_id", intoVehicle.violations.single().pointer)
        val grown = w.staff.shifts.update(w.dispatcher(), other.id, other.revision, ShiftInput.parse(mapOf("driver_id" to q.driverId.toString(), "vehicle_id" to v2.toString(),
            "starts_at" to "2026-12-02T15:00:00Z", "ends_at" to "2026-12-02T19:00:00Z", "timezone" to "Europe/Berlin"), update = true))
        assertEquals(2L, grown.revision); assertEquals(240L, grown.durationMinutes)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.shift.changed' AND object_ref=?", w.tenant.value, "staff_shift:${other.id}"))
    }

    @Test
    fun dokumentierte_ausnahme_speichert_genehmiger_grund_zeitpunkt_und_nur_vollstaendig() {
        val p = w.person(); val v1 = w.vehicle("C-1"); val v2 = w.vehicle("C-2")
        create(p.driverId, v1, "2026-12-03T06:00:00Z", "2026-12-03T14:00:00Z")
        // unvollständig (ohne Genehmiger, ohne Grund, leer): bleibt Konflikt
        for (ex in listOf(mapOf("reason" to "Krankheitsvertretung"), mapOf("approved_by" to "user:chef"), mapOf("approved_by" to " ", "reason" to "Krankheitsvertretung"), mapOf("approved_by" to "user:chef", "reason" to "  "))) {
            val e = ShiftWorld.problem { create(p.driverId, v2, "2026-12-03T12:00:00Z", "2026-12-03T18:00:00Z", mapOf("overlap_exception" to ex)) }
            assertEquals(ErrorCode.CONFLICT, e.code, ex.toString())
        }
        val ok = create(p.driverId, v2, "2026-12-03T12:00:00Z", "2026-12-03T18:00:00Z", mapOf("overlap_exception" to mapOf("approved_by" to "user:chef", "reason" to "Krankheitsvertretung")))
        assertEquals("user:chef", ok.overlapException!!.approvedBy); assertEquals("Krankheitsvertretung", ok.overlapException!!.reason); assertNotNull(ok.overlapException!!.approvedAt)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.shift.created' AND object_ref=? AND redacted_changes::text LIKE '%user:chef%'", w.tenant.value, "staff_shift:${ok.id}"))
        // ohne Überlappung wird keine Ausnahme gespeichert; eine unvollständige ist dann ein Feldfehler
        val clean = create(p.driverId, w.vehicle("C-3"), "2026-12-04T06:00:00Z", "2026-12-04T10:00:00Z", mapOf("overlap_exception" to mapOf("approved_by" to "user:chef", "reason" to "vorsorglich")))
        assertNull(clean.overlapException)
        assertEquals(ErrorCode.VALIDATION_FAILED, ShiftWorld.problem { create(p.driverId, w.vehicle(), "2026-12-05T06:00:00Z", "2026-12-05T10:00:00Z", mapOf("overlap_exception" to mapOf("reason" to "nur Grund"))) }.code)
    }

    @Test
    fun dauer_ueber_sommerzeitende_aus_absoluten_zeitpunkten() {
        val p = w.person(); val v = w.vehicle("D-1")
        // 25.10.2026: Mitternacht CEST bis 08:00 CET sind 9 Stunden, nicht 8 (Wandzeit)
        val night = create(p.driverId, v, "2026-10-25T00:00:00+02:00", "2026-10-25T08:00:00+01:00")
        assertEquals(540L, night.durationMinutes)
        // „02:30“ kommt zweimal vor: Ende 02:30+01:00 und Beginn 02:30+02:00 sind nach Wandzeit angrenzend, absolut aber überlappend
        val q = w.person(); val v2 = w.vehicle("D-2")
        create(q.driverId, v2, "2026-10-25T01:30:00+02:00", "2026-10-25T02:30:00+01:00")
        val e = ShiftWorld.problem { create(q.driverId, v2, "2026-10-25T02:30:00+02:00", "2026-10-25T06:00:00+01:00") }
        assertEquals(ErrorCode.CONFLICT, e.code)
        create(q.driverId, v2, "2026-10-25T02:30:00+01:00", "2026-10-25T06:00:00+01:00")                    // die zweite 02:30 grenzt exakt an
        // Eingabe ohne Offset ist keine Zeit (OFFSET_REQUIRED)
        val noOffset = ShiftWorld.problem { ShiftInput.parse(mapOf("driver_id" to p.driverId.toString(), "vehicle_id" to v.toString(), "starts_at" to "2026-10-25T02:30:00", "ends_at" to "2026-10-25T05:00:00+01:00", "timezone" to "Europe/Berlin"), false) }
        assertEquals("/starts_at", noOffset.violations.single().pointer); assertEquals("OFFSET_REQUIRED", noOffset.violations.single().code.name)
    }

    @Test
    fun pflichtfelder_intervall_mandant_und_rechte() {
        val p = w.person(); val v = w.vehicle("E-1")
        val missing = ShiftWorld.problem { ShiftInput.parse(mapOf("driver_id" to p.driverId.toString(), "vehicle_id" to v.toString(), "timezone" to "Europe/Berlin"), false) }
        assertEquals(setOf("/starts_at", "/ends_at"), missing.violations.map { it.pointer }.toSet())
        val backwards = ShiftWorld.problem { ShiftInput.parse(mapOf("driver_id" to p.driverId.toString(), "vehicle_id" to v.toString(), "starts_at" to "2026-12-06T10:00:00Z", "ends_at" to "2026-12-06T10:00:00Z", "timezone" to "Europe/Berlin"), false) }
        assertEquals("INTERVAL_INVALID", backwards.violations.single().code.name)
        assertEquals("UNKNOWN_FIELD", ShiftWorld.problem { ShiftInput.parse(mapOf("tenant_id" to UUID.randomUUID().toString()), false) }.violations.first { it.pointer == "/tenant_id" }.code.name)
        // fremder Mandant: Fahrer, Fahrzeug und Schicht sind nicht auffindbar
        val other = ShiftWorld(fx); val op = other.person(); val ov = other.vehicle("X-1")
        assertEquals(ErrorCode.NOT_FOUND, ShiftWorld.problem { create(op.driverId, v, "2026-12-06T06:00:00Z", "2026-12-06T08:00:00Z") }.code)
        assertEquals(ErrorCode.NOT_FOUND, ShiftWorld.problem { create(p.driverId, ov, "2026-12-06T06:00:00Z", "2026-12-06T08:00:00Z") }.code)
        val foreign = other.staff.shifts.create(other.dispatcher(), ShiftInput.parse(mapOf("driver_id" to op.driverId.toString(), "vehicle_id" to ov.toString(), "starts_at" to "2026-12-06T06:00:00Z",
            "ends_at" to "2026-12-06T08:00:00Z", "timezone" to "Europe/Berlin"), false))
        assertEquals(ErrorCode.NOT_FOUND, ShiftWorld.problem { w.staff.shifts.get(w.dispatcher(), foreign.id) }.code)
        assertEquals(ErrorCode.NOT_FOUND, ShiftWorld.problem { w.staff.shifts.update(w.dispatcher(), foreign.id, 1, input(p.driverId, v, "2026-12-06T06:00:00Z", "2026-12-06T08:00:00Z")) }.code)
        // gleiche Fahrerkennung im anderen Mandanten blockiert nicht, Rechte: ohne Scope 403
        assertEquals(ErrorCode.FORBIDDEN, ShiftWorld.problem { w.staff.shifts.create(w.dispatcher(scopes = setOf("shifts:read")), input(p.driverId, v, "2026-12-06T06:00:00Z", "2026-12-06T08:00:00Z")) }.code)
        // inaktiver Fahrer: Feldfehler
        val inactive = w.person(status = "INACTIVE")
        assertEquals("/driver_id", ShiftWorld.problem { create(inactive.driverId, v, "2026-12-07T06:00:00Z", "2026-12-07T08:00:00Z") }.violations.single().pointer)
    }

    @Test
    fun revision_schuetzt_aenderung_und_liste_filtert() {
        val p = w.person(); val v = w.vehicle("F-1")
        val s = create(p.driverId, v, "2026-12-08T06:00:00Z", "2026-12-08T10:00:00Z")
        val upd = ShiftInput.parse(mapOf("driver_id" to p.driverId.toString(), "vehicle_id" to v.toString(), "starts_at" to "2026-12-08T06:00:00Z", "ends_at" to "2026-12-08T11:00:00Z", "timezone" to "Europe/Berlin"), true)
        assertEquals(ErrorCode.REVISION_MISMATCH, ShiftWorld.problem { w.staff.shifts.update(w.dispatcher(), s.id, 7, upd) }.code)
        val done = w.staff.shifts.update(w.dispatcher(), s.id, 1, upd)
        assertEquals(2L, done.revision)
        assertEquals(listOf(s.id), w.staff.shifts.list(w.dispatcher(), p.driverId, null, Instant.parse("2026-12-08T00:00:00Z"), Instant.parse("2026-12-09T00:00:00Z"), 10, null).map { it.id })
        // Entzug gibt Fahrer und Fahrzeug frei
        val cancelled = w.staff.shifts.update(w.dispatcher(), s.id, 2, ShiftInput.parse(mapOf("driver_id" to p.driverId.toString(), "vehicle_id" to v.toString(), "starts_at" to "2026-12-08T06:00:00Z",
            "ends_at" to "2026-12-08T11:00:00Z", "timezone" to "Europe/Berlin", "withdrawn" to true), true))
        assertEquals("CANCELLED", cancelled.state)
        create(p.driverId, v, "2026-12-08T07:00:00Z", "2026-12-08T09:00:00Z")
    }

    @Test
    fun parallele_ueberlappende_schichten_hoechstens_eine() {
        // je Runde zwei (Fahrer-Konflikt) bzw. acht (Fahrzeug-Konflikt, verschiedene Fahrer) gleichzeitige Anlagen: genau eine wird gespeichert
        for (round in 1..4) {
            val p = w.person(); val v1 = w.vehicle("G-$round-a"); val v2 = w.vehicle("G-$round-b")
            val day = "2027-02-%02d".format(round)
            val sameDriver = listOf(p.driverId to v1, p.driverId to v2)
            val results = parallel(sameDriver.map { (d, v) -> { create(d, v, "${day}T06:00:00Z", "${day}T14:00:00Z") } })
            assertEquals(1, results.count { it == "ok" }, "Fahrer-Runde $round: $results"); assertEquals(1, results.count { it == "CONFLICT" }, results.toString())
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.staff_shift WHERE tenant_id=? AND driver_ref=? AND starts_at=?::timestamptz", w.tenant.value, p.driverId, "${day}T06:00:00Z"))

            val vehicle = w.vehicle("G-$round-c"); val drivers = (1..8).map { w.person() }
            val res2 = parallel(drivers.map { d -> { create(d.driverId, vehicle, "${day}T06:00:00Z", "${day}T14:00:00Z") } })
            assertEquals(1, res2.count { it == "ok" }, "Fahrzeug-Runde $round: $res2"); assertEquals(7, res2.count { it == "CONFLICT" }, res2.toString())
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.staff_shift WHERE tenant_id=? AND vehicle_id=?", w.tenant.value, vehicle))
        }
    }

    private fun parallel(tasks: List<() -> ShiftView>): List<String> {
        val pool = Executors.newFixedThreadPool(tasks.size); val start = CountDownLatch(1)
        val futures = tasks.map { t -> pool.submit<String> { start.await(); try { t(); "ok" } catch (e: de.taktovia.platform.problem.ProblemException) { e.code.name } } }
        start.countDown(); val out = futures.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown(); return out
    }
}
