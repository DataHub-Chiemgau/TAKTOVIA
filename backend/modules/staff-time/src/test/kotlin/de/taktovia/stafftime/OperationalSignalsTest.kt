package de.taktovia.stafftime

import de.taktovia.stafftime.api.PositionBatch
import de.taktovia.stafftime.api.StartShiftCommand
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * WP-M3-42: Meldungen des Fahrerbetriebs für die Überfälligkeitsauswertung (veralteter Standort, offener Klärfall) über das Modul `staff-time` (echte Datenbank, Runtime-Rolle, steuerbare Uhr).
 * Nur Kennungen und Zeiten, im Mandanten des Auftrags.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationalSignalsTest {
    private lateinit var fx: StaffFixture
    private lateinit var w: ShiftWorld

    @BeforeAll fun up() { fx = StaffFixture(); w = ShiftWorld(fx) }
    @AfterAll fun down() = fx.close()

    private fun at(iso: String) { w.clock.now = Instant.parse(iso) }

    private class Scene(val p: ShiftWorld.Person, val vehicle: UUID, val shift: UUID, val device: UUID)
    private fun started(): Scene {
        val p = w.person(); val v = w.vehicle(); val s = w.shiftRow(p, v, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z"); val d = w.device(p)
        at("2026-12-01T06:00:00Z"); w.staff.driverShifts.start(w.driverCtx(p), StartShiftCommand(s, v, d))
        return Scene(p, v, s, d)
    }
    private fun position(sc: Scene, measured: String) {
        at(Instant.parse(measured).plusSeconds(1).toString())
        w.staff.positions.record(w.driverCtx(sc.p), PositionBatch.parse(mapOf("shift_id" to sc.shift.toString(), "vehicle_id" to sc.vehicle.toString(), "device_id" to sc.device.toString(),
            "samples" to listOf(mapOf("position" to mapOf("latitude" to 51.05, "longitude" to 13.74), "accuracy_meters" to 5.0, "measured_at" to measured)))))
    }
    private fun event(sc: Scene, outcome: String, reason: String?, receivedAt: String) = fx.exec(
        "INSERT INTO taktovia.driver_event(tenant_id, event_id, device_id, shift_id, vehicle_id, event_type, payload_sha256, outcome, reason_code, plan_revision, device_recorded_at, occurred_at, received_at) " +
            "VALUES (?, ?, ?, ?, ?, 'ARRIVAL', ?, ?, ?, 1, ?::timestamptz, ?::timestamptz, ?::timestamptz)",
        w.tenant.value, UUID.randomUUID(), sc.device, sc.shift, sc.vehicle, "a".repeat(64), outcome, reason, receivedAt, receivedAt, receivedAt)

    @Test
    fun veralteter_standort_erst_ueber_der_schwelle_und_nur_mit_angetretener_schicht() {
        val sc = started(); val other = started()
        position(other, "2026-12-01T06:19:00Z")
        at("2026-12-01T06:14:00Z")
        assertTrue(w.staff.signals.staleVehicles(w.tenant, Duration.ofMinutes(15)).none { it.shiftId == sc.shift }, "14 Minuten seit Dienstantritt: unter der Schwelle")
        at("2026-12-01T06:20:00Z")
        val stale = w.staff.signals.staleVehicles(w.tenant, Duration.ofMinutes(15)).single { it.shiftId == sc.shift }
        assertEquals(sc.vehicle, stale.vehicleId); assertEquals(Instant.parse("2026-12-01T06:00:00Z"), stale.since, "ohne Messung gilt der Dienstantritt")
        assertTrue(w.staff.signals.staleVehicles(w.tenant, Duration.ofMinutes(15)).none { it.shiftId == other.shift }, "frische Position: nicht veraltet")
        // eine neue Position beendet die Meldung; eine nicht angetretene Schicht erscheint nie
        position(sc, "2026-12-01T06:20:00Z"); at("2026-12-01T06:21:00Z")
        assertTrue(w.staff.signals.staleVehicles(w.tenant, Duration.ofMinutes(15)).none { it.shiftId == sc.shift })
        val planned = w.shiftRow(w.person(), w.vehicle(), "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z")
        at("2026-12-01T13:00:00Z"); assertTrue(w.staff.signals.staleVehicles(w.tenant, Duration.ofMinutes(15)).none { it.shiftId == planned })
    }

    @Test
    fun offener_klaerfall_bis_ein_spaeteres_ereignis_regulaer_angewendet_wurde() {
        val sc = started()
        event(sc, "REVIEW_REQUIRED", "STALE_PLAN", "2026-12-01T07:00:00Z")
        at("2026-12-01T07:05:00Z")
        assertTrue(w.staff.signals.openReviews(w.tenant, Duration.ofMinutes(10)).none { it.shiftId == sc.shift }, "5 Minuten offen: unter der Schwelle")
        at("2026-12-01T07:15:00Z")
        val open = w.staff.signals.openReviews(w.tenant, Duration.ofMinutes(10)).single { it.shiftId == sc.shift }
        assertEquals(sc.vehicle, open.vehicleId); assertEquals("STALE_PLAN", open.reasonCode); assertEquals(Instant.parse("2026-12-01T07:00:00Z"), open.since)
        // ein späteres, regulär angewendetes Ereignis der Schicht: der Fall gilt als geklärt
        event(sc, "APPLIED", null, "2026-12-01T07:10:00Z")
        assertTrue(w.staff.signals.openReviews(w.tenant, Duration.ofMinutes(10)).none { it.shiftId == sc.shift })
    }

    @Test
    fun meldungen_bleiben_im_mandanten() {
        val sc = started()
        at("2026-12-01T07:00:00Z")
        val foreign = ShiftWorld(fx)
        assertTrue(foreign.staff.signals.staleVehicles(foreign.tenant, Duration.ofMinutes(15)).none { it.shiftId == sc.shift })
        assertTrue(w.staff.signals.staleVehicles(w.tenant, Duration.ofMinutes(15)).any { it.shiftId == sc.shift })
    }
}
