package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.api.BookingDemand
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.CandidateRequest
import de.taktovia.bookingdispatch.api.ExclusionReason
import de.taktovia.bookingdispatch.api.FixturePlannedPositions
import de.taktovia.bookingdispatch.api.HoldOutcome
import de.taktovia.bookingdispatch.api.MatrixInfo
import de.taktovia.bookingdispatch.api.PlaceHold
import de.taktovia.bookingdispatch.api.PlannedPosition
import de.taktovia.bookingdispatch.api.PromiseCore
import de.taktovia.bookingdispatch.api.RejectionCategory
import de.taktovia.bookingdispatch.api.RejectionReason
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RouteInfo
import de.taktovia.bookingdispatch.api.RoutingPort
import de.taktovia.bookingdispatch.api.TimeWindow
import de.taktovia.bookingdispatch.api.Unusable
import de.taktovia.bookingdispatch.api.VehicleAvailability
import de.taktovia.bookingdispatch.internal.VehicleCandidateFilter
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/**
 * WP-M3-31 / LH-6.6-DRV-007, Originalabnahme „Nicht verfügbares Fahrzeug wird nicht neu disponiert“, auf der Seite der Disposition (`booking-dispatch`, echte Datenbank, Runtime-Rolle): Pause, Störung,
 * Ende und der Anfangszustand sind weder Kandidat noch Hold noch Zusage; Verfügbarkeit, Anfahrt und Wartezeit schon. Den Statuswechsel selbst schreibt `staff-time` (`VehicleServiceStatusTest`); hier
 * setzt der Testaufbau die Statuszeile mit der Migrator-Rolle. Die echte Parallelität von Störungsmeldung gegen Hold und Zusage prüft `VehicleServiceStatusRaceTest` in der Anwendung.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VehicleServiceStatusDispatchTest {
    private lateinit var fx: BookingFixture
    private lateinit var api: BookingdispatchApi
    private val window = TimeWindow(Instant.parse("2026-12-01T06:00:00Z"), Instant.parse("2026-12-01T07:00:00Z"))
    private val promise = PromiseCore("2026-12-01T06:10:00Z", "2026-12-01T06:25:00Z", "2026-12-01T06:40:00Z", "2026-12-01T06:55:00Z", 500, "EUR", "std-24h", "standard")
    private val one = BookingDemand.of(1, 0, emptySet(), emptyList())
    private val cap = """{"schema_version":"1.0","seats":4,"wheelchair":1,"equipment":[]}"""

    @BeforeAll fun up() { fx = BookingFixture(); api = BookingdispatchApi.create(fx.ctxTx) }
    @AfterAll fun down() = fx.close()

    private fun shiftOf(run: BookingFixture.RunRef): UUID = fx.ctxTx.run(fx.context(fx.tenantA), readOnly = true) { j -> j.queryForObject("SELECT shift_id FROM taktovia.vehicle_run WHERE id = ?", UUID::class.java, run.runId)!! }

    /** Frischer Umlauf; mit [status] steht die Statuszeile der Schicht auf diesem Wert (Aufbau mit der Migrator-Rolle, ohne Kantenprüfung durch den Dienst). */
    private fun run(status: String?): BookingFixture.RunRef = fx.createRun(cap).also { r ->
        if (status != null) fx.migratorExec(fx.tenantA, "INSERT INTO taktovia.vehicle_service_status(tenant_id, shift_id, vehicle_id, status) VALUES (taktovia.current_tenant(), ?, ?, ?)", shiftOf(r), r.vehicleId, status)
    }

    private fun place(r: BookingFixture.RunRef): HoldOutcome = api.holds.place(fx.context(fx.tenantA), PlaceHold(r.runId, 1, r.serviceId, window, 0, 1, ResourceVector(1, 0), reference = "ref-" + UUID.randomUUID()))

    private val routing = object : RoutingPort {
        override fun route(ctx: RequestContext, originStop: String, destinationStop: String, profile: String) = RouteInfo(originStop, destinationStop, 180, 1440, emptyList(), profile, "stub", "stub")
        override fun walk(ctx: RequestContext, originStop: String, destinationStop: String) = route(ctx, originStop, destinationStop, "walk")
        override fun matrix(ctx: RequestContext, originStops: List<String>, destinationStops: List<String>, profile: String): MatrixInfo = error("nicht benötigt")
    }

    private fun candidates(r: BookingFixture.RunRef) = VehicleCandidateFilter(api.resources, routing, FixturePlannedPositions(mapOf(r.vehicleId to PlannedPosition("H1", Instant.parse("2026-12-01T05:50:00Z")))))
        .find(fx.context(fx.tenantA), CandidateRequest(r.serviceId, window, ResourceVector(seats = 1), "H4", Instant.parse("2026-12-01T06:30:00Z")))

    private fun assertNotDispatched(r: BookingFixture.RunRef, label: String) {
        val ctx = fx.context(fx.tenantA)
        val v = api.resources.check(ctx, r.vehicleId, r.serviceId, window)
        assertEquals(Unusable.SERVICE_STATUS_NOT_DISPATCHABLE, (v as VehicleAvailability.NotUsable).reason, label)
        assertTrue(api.resources.usableVehicles(ctx, r.serviceId, window).vehicles.none { it.vehicleId == r.vehicleId }, label)
        val search = candidates(r)
        assertTrue(search.candidates.none { it.resource.vehicleId == r.vehicleId }, label)
        assertEquals(ExclusionReason.SERVICE_STATUS, search.excluded.single { it.vehicleId == r.vehicleId }.reason, label)
        assertEquals(RejectionCategory.PERSONNEL, ExclusionReason.SERVICE_STATUS.category, "$label: Ablehnungskategorie (Arbeitsannahme OP-14)")
        val hold = place(r) as HoldOutcome.Rejected
        assertEquals(RejectionReason.RESOURCE_UNUSABLE, hold.reason, "$label: kein Hold")
        assertEquals(0, fx.ctxTx.run(ctx, readOnly = true) { j -> j.queryForObject("SELECT count(*) FROM taktovia.capacity_hold WHERE run_id = ?", Int::class.java, r.runId)!! }, "$label: nichts gehalten")
    }

    @Test
    fun pausierendes_fahrzeug_nicht_disponiert() {
        assertNotDispatched(run("BREAK"), "BREAK")
        // Gegenprobe: dasselbe Fahrzeug im Status Verfügbarkeit, Anfahrt oder Wartezeit wird disponiert
        for (ok in listOf("AVAILABLE", "EN_ROUTE", "WAITING")) {
            val r = run(ok)
            assertTrue(api.resources.check(fx.context(fx.tenantA), r.vehicleId, r.serviceId, window) is VehicleAvailability.Usable, ok)
            assertEquals(r.vehicleId, candidates(r).candidates.single().resource.vehicleId, ok)
            assertTrue(place(r) is HoldOutcome.Placed, "$ok: Hold möglich")
        }
    }

    @Test
    fun stoerung_und_ende_nicht_disponiert() {
        assertNotDispatched(run("DISRUPTED"), "DISRUPTED")
        assertNotDispatched(run("ENDED"), "ENDED")
        assertNotDispatched(run("NOT_READY"), "NOT_READY (keine Bereitmeldung ohne Einsatzfreigabe)")
        // Altbestand ohne Statuszeile bleibt disponierbar (Planungsannahme im Zustandsautomaten)
        val legacy = run(null)
        assertTrue(api.resources.check(fx.context(fx.tenantA), legacy.vehicleId, legacy.serviceId, window) is VehicleAvailability.Usable)
        assertTrue(place(legacy) is HoldOutcome.Placed)
    }

    @Test
    fun stoerung_nach_dem_hold_verhindert_die_zusage() {
        val r = run("AVAILABLE"); val ctx = fx.context(fx.tenantA)
        val held = place(r) as HoldOutcome.Placed
        // Störung gemeldet, bevor die Zusage bestätigt wird: die Bestätigung unter der Fahrzeugsperre sieht den neuen Status
        fx.migratorExec(fx.tenantA, "UPDATE taktovia.vehicle_service_status SET status = 'DISRUPTED' WHERE shift_id = ?", shiftOf(r))
        val e = assertThrows<ProblemException> { api.holds.confirm(ctx, held.hold.id, r.serviceId, promise, one) }
        assertEquals(ErrorCode.CAPACITY_UNAVAILABLE, e.code)
        assertEquals(0, fx.ctxTx.run(ctx, readOnly = true) { j -> j.queryForObject("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", Int::class.java, held.hold.id)!! }, "keine Buchung ohne Zusage")
        // Gegenprobe: ohne Störung wird dieselbe Zusage bestätigt
        val ok = run("EN_ROUTE"); val held2 = place(ok) as HoldOutcome.Placed
        assertEquals("CONFIRMING", api.holds.confirm(ctx, held2.hold.id, ok.serviceId, promise, one).state)
    }
}
