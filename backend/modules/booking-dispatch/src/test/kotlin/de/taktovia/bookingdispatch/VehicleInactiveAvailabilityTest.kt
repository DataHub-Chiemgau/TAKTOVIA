package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.TimeWindow
import de.taktovia.bookingdispatch.api.Unusable
import de.taktovia.bookingdispatch.api.VehicleAvailability
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant

/** WP-M3-03 (LH-6.8-FLT-001): ein deaktiviertes Fahrzeug (`vehicle.active = false`) ist kein Kandidat neuer Fahrten, ein aktives mit gültiger Freigabe schon. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VehicleInactiveAvailabilityTest {
    private lateinit var fx: BookingFixture
    private lateinit var api: BookingdispatchApi
    private val window = TimeWindow(Instant.parse("2026-12-01T06:00:00Z"), Instant.parse("2026-12-01T07:00:00Z"))

    @BeforeAll fun up() { fx = BookingFixture(); api = BookingdispatchApi.create(fx.ctxTx) }
    @AfterAll fun down() = fx.close()

    @Test
    fun deaktiviertes_fahrzeug_erscheint_nicht_als_kandidat_neuer_fahrten() {
        val run = fx.createRun("""{"schema_version":"1.0","seats":4,"wheelchair":1,"equipment":[]}""")
        val ctx = fx.context(fx.tenantA)
        assertTrue(api.resources.check(ctx, run.vehicleId, run.serviceId, window) is VehicleAvailability.Usable, "aktiv, freigegeben: einsetzbar")
        assertTrue(api.resources.usableVehicles(ctx, run.serviceId, window).vehicles.any { it.vehicleId == run.vehicleId })
        fx.migratorExec(fx.tenantA, "UPDATE taktovia.vehicle SET active = false WHERE id = ?", run.vehicleId)
        val v = api.resources.check(ctx, run.vehicleId, run.serviceId, window)
        assertEquals(Unusable.VEHICLE_INACTIVE, (v as VehicleAvailability.NotUsable).reason)
        val snapshot = api.resources.usableVehicles(ctx, run.serviceId, window)
        assertTrue(snapshot.vehicles.none { it.vehicleId == run.vehicleId })
        assertEquals(Unusable.VEHICLE_INACTIVE, snapshot.excluded.single { it.vehicleId == run.vehicleId }.reason)
        fx.migratorExec(fx.tenantA, "UPDATE taktovia.vehicle SET active = true WHERE id = ?", run.vehicleId)   // Gegenprobe: wieder aktiv, wieder einsetzbar
        assertTrue(api.resources.check(ctx, run.vehicleId, run.serviceId, window) is VehicleAvailability.Usable)
    }
}
