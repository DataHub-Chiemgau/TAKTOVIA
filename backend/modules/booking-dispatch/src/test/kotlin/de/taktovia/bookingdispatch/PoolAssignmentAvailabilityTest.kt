package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.CandidateRequest
import de.taktovia.bookingdispatch.api.ExclusionReason
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.TimeWindow
import de.taktovia.bookingdispatch.api.Unusable
import de.taktovia.bookingdispatch.api.VehicleAvailability
import de.taktovia.platform.geo.GeoPoint
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID

/**
 * WP-M3-06 / LH-6.8-FLT-002 am Leseport `ResourceAvailability` und am Kandidatenfilter (das, was die Disposition vergibt): ein Fahrzeug erhält nur Fahrten aus aktuell zugeordneten
 * Diensten. Falscher Dienst, noch nicht begonnene, abgelaufene Zuordnung (`valid_to` exklusiv), zeitliche und geografische Einschränkung unabhängig, mehrere Dienste gleichzeitig.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PoolAssignmentAvailabilityTest {
    private lateinit var fx: BookingFixture
    private lateinit var api: BookingdispatchApi

    @BeforeAll fun up() { fx = BookingFixture(); api = BookingdispatchApi.create(fx.ctxTx) }
    @AfterAll fun down() = fx.close()

    private val cap = """{"schema_version":"1.0","seats":4,"wheelchair":1,"equipment":[]}"""
    private fun window(from: String = "2026-12-01T06:00:00Z", to: String = "2026-12-01T07:00:00Z") = TimeWindow(Instant.parse(from), Instant.parse(to))
    private fun reason(v: VehicleAvailability) = (v as VehicleAvailability.NotUsable).reason

    /** Neuer Dienst im Mandanten A (Betreiber und Konfiguration vom Dienst „svc-buchung“) mit optionalem Bediengebiet (Quadrat ab Längengrad 8, Breitengrad 49, 0,1 Grad). */
    private fun newService(withArea: Boolean): Pair<UUID, UUID?> {
        val s = UUID.randomUUID(); val a = if (withArea) UUID.randomUUID() else null
        fx.migratorExec(fx.tenantA, "INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,created_by) SELECT tenant_id, ?, operator_id, 'Pool-Test', timezone, config_revision_id, 'system:test' FROM taktovia.service WHERE id = ?", s, fixtureId("a", "svc-buchung"))
        if (a != null) fx.migratorExec(fx.tenantA, "INSERT INTO taktovia.service_area(tenant_id,id,service_id,revision,area,valid_from) VALUES (taktovia.current_tenant(),?,?,1,ST_GeomFromText('MULTIPOLYGON(((8.0 49.0,8.1 49.0,8.1 49.1,8.0 49.1,8.0 49.0)))',4326),'2026-01-01T00:00:00Z')", a, s)
        return s to a
    }

    private fun assign(vehicle: UUID, service: UUID, from: String, to: String?, area: UUID? = null) =
        fx.migratorExec(fx.tenantA, "INSERT INTO taktovia.vehicle_pool_assignment(tenant_id,id,service_id,vehicle_id,service_area_id,valid_from,valid_to,created_by) VALUES (taktovia.current_tenant(),?,?,?,?,CAST(? AS timestamptz),CAST(? AS timestamptz),'system:test')",
            UUID.randomUUID(), service, vehicle, area, from, to)

    @Test
    fun falscher_dienst_ohne_zuordnung_und_mehrere_dienste() {
        val run = fx.createRun(cap); val ctx = fx.context(fx.tenantA); val (other, _) = newService(false)
        assertTrue(api.resources.check(ctx, run.vehicleId, run.serviceId, window()) is VehicleAvailability.Usable, "Fahrt aus dem zugeordneten Dienst A")
        assertEquals(Unusable.NOT_IN_POOL, reason(api.resources.check(ctx, run.vehicleId, other, window())), "keine Fahrt aus Dienst B")
        assertTrue(api.resources.usableVehicles(ctx, other, window()).vehicles.none { it.vehicleId == run.vehicleId })
        assign(run.vehicleId, other, "2026-01-01T00:00:00Z", null)   // gleichzeitig zwei Diensten zugeordnet: Fahrten aus beiden
        assertTrue(api.resources.check(ctx, run.vehicleId, other, window()) is VehicleAvailability.Usable)
        assertTrue(api.resources.check(ctx, run.vehicleId, run.serviceId, window()) is VehicleAvailability.Usable)
        assertTrue(api.resources.usableVehicles(ctx, other, window()).vehicles.any { it.vehicleId == run.vehicleId })
    }

    @Test
    fun abgelaufene_und_noch_nicht_begonnene_zuordnung_valid_to_exklusiv() {
        val run = fx.createRun(cap); val ctx = fx.context(fx.tenantA)
        fx.migratorExec(fx.tenantA, "UPDATE taktovia.vehicle_pool_assignment SET valid_to = CAST('2026-12-01T05:00:00Z' AS timestamptz) WHERE vehicle_id = ? AND service_id = ?", run.vehicleId, run.serviceId)
        assertTrue(api.resources.check(ctx, run.vehicleId, run.serviceId, window("2026-12-01T04:59:59Z", "2026-12-01T06:00:00Z")) is VehicleAvailability.Usable, "vor valid_to")
        assertEquals(Unusable.NOT_IN_POOL, reason(api.resources.check(ctx, run.vehicleId, run.serviceId, window("2026-12-01T05:00:00Z", "2026-12-01T06:00:00Z"))), "genau zu valid_to: keine neue Fahrt")
        assertEquals(Unusable.NOT_IN_POOL, reason(api.resources.check(ctx, run.vehicleId, run.serviceId, window())), "nach Ablauf auch aus Dienst A keine neue Fahrt")
        val (later, _) = newService(false); assign(run.vehicleId, later, "2026-12-01T06:30:00Z", null)
        assertEquals(Unusable.NOT_IN_POOL, reason(api.resources.check(ctx, run.vehicleId, later, window())), "noch nicht begonnen (vor valid_from)")
        assertTrue(api.resources.check(ctx, run.vehicleId, later, window("2026-12-01T06:30:00Z", "2026-12-01T07:00:00Z")) is VehicleAvailability.Usable)
    }

    @Test
    fun zeitliche_und_geografische_einschraenkung_wirken_unabhaengig_auch_im_kandidatenfilter() {
        val run = fx.createRun(cap); val ctx = fx.context(fx.tenantA); val (svc, area) = newService(true)
        assign(run.vehicleId, svc, "2026-01-01T00:00:00Z", "2026-12-01T12:00:00Z", area)
        val inside = listOf(GeoPoint(8.05, 49.05)); val outside = listOf(GeoPoint(9.05, 49.05))
        assertTrue(api.resources.check(ctx, run.vehicleId, svc, window(), inside) is VehicleAvailability.Usable, "im Intervall und im Gebiet")
        assertEquals(Unusable.OUTSIDE_SERVICE_AREA, reason(api.resources.check(ctx, run.vehicleId, svc, window(), outside)), "im Intervall, außerhalb des Gebiets")
        assertEquals(Unusable.OUTSIDE_SERVICE_AREA, reason(api.resources.check(ctx, run.vehicleId, svc, window())), "ohne Ortsangabe nicht prüfbar")
        assertEquals(Unusable.NOT_IN_POOL, reason(api.resources.check(ctx, run.vehicleId, svc, window("2026-12-01T12:00:00Z", "2026-12-01T13:00:00Z"), inside)), "im Gebiet, außerhalb des Intervalls")
        // Kandidatenfilter: dasselbe Verhalten; der Ausschlussgrund ist benannt
        fun request(points: List<GeoPoint>) = CandidateRequest(svc, window(), ResourceVector(1, 0), "stop-x", Instant.parse("2026-12-01T06:30:00Z"), locations = points)
        val out = api.candidates.find(ctx, request(outside))
        assertEquals(ExclusionReason.OUTSIDE_SERVICE_AREA, out.excluded.single { it.vehicleId == run.vehicleId }.reason)
        val fresh = api.resources.usableVehicles(ctx, svc, window(), inside)
        assertTrue(fresh.vehicles.any { it.vehicleId == run.vehicleId }); assertTrue(api.resources.usableVehicles(ctx, svc, window(), outside).vehicles.none { it.vehicleId == run.vehicleId })
    }

    @Test
    fun zuordnung_eines_fremden_mandanten_wirkt_nicht() {
        val run = fx.createRun(cap); val other = fx.createRun(cap, fx.tenantB)
        val ctxB = fx.context(fx.tenantB)
        assertEquals(Unusable.UNKNOWN_VEHICLE, reason(api.resources.check(ctxB, run.vehicleId, run.serviceId, window())), "Fahrzeug des Mandanten A ist für B unbekannt")
        assertTrue(api.resources.check(ctxB, other.vehicleId, other.serviceId, window()) is VehicleAvailability.Usable)
    }
}
