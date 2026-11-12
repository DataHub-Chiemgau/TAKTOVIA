package de.taktovia.app.readports

import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.readports.BookingReadPort
import de.taktovia.platform.readports.DriverEventReadPort
import de.taktovia.platform.readports.DriverReadPort
import de.taktovia.platform.readports.FleetReadPort
import de.taktovia.platform.readports.PositionReadPort
import de.taktovia.platform.readports.TripPlanReadPort
import de.taktovia.platform.readports.stub.FixtureReadPorts
import de.taktovia.platform.readports.stub.m4FixtureId
import de.taktovia.platform.tenant.TenantId
import de.taktovia.stafftime.api.StafftimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID

/**
 * Vertragstest der Leseports (WP-M4-02, Kriterium 3): **derselbe** Test läuft gegen den Stub ([StubReadPortContractTest]) und gegen die echte Implementierung auf dem geladenen Seed
 * ([RealReadPortContractTest]). Er prüft Inhalt gegen die dokumentierten Sollwerte (Musterfahrt 12 400 m, Qualifikationen, Rollen), die Mandantentrennung (fremde Kennung ergibt keine Zeile)
 * und das Verhalten ohne Daten (ein Kontext ohne Referenzdaten sieht nichts).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ReadPortContractTest {
    internal val fx = M4ReferenceFixture()

    abstract val trip: TripPlanReadPort
    abstract val drivers: DriverReadPort
    abstract val fleet: FleetReadPort
    /** Nur der Stub liefert diese Ports, bis M2/M3 die Quellen bringen (WP-M2-23, WP-M3-29, WP-M3-34); `null` = noch keine echte Implementierung. */
    open val booking: BookingReadPort? = null
    open val events: DriverEventReadPort? = null
    open val positions: PositionReadPort? = null

    protected fun id(key: String, tenant: String = "a") = m4FixtureId(tenant, key)
    private val a get() = fx.context(fx.tenantA)
    private val b get() = fx.context(fx.tenantB)
    private val nobody get() = fx.context(TenantId(UUID.randomUUID()))

    @Test
    fun fahrtenplan_liefert_planzeiten_und_dokumentierte_kilometer() {
        val plan = trip.currentPlan(a, id("run-1"))!!
        assertEquals(1L, plan.revision); assertEquals("ACTIVE", plan.state); assertEquals(id("veh-1"), plan.vehicleId); assertEquals(id("shift-day"), plan.shiftId)
        assertEquals(listOf(0, 1, 2), plan.stops.map { it.seq })
        assertEquals(listOf("2026-12-01T06:00:00Z", "2026-12-01T06:15:00Z", "2026-12-01T06:36:00Z").map(Instant::parse), plan.stops.map { it.plannedAt })
        assertEquals(12_400L, plan.totalDistanceM, "Musterfahrt: 12,4 km"); assertEquals(2_160L, plan.totalDurationSec)
        assertEquals(listOf(4200, 8200, null), plan.stops.map { it.legDistanceM })
        assertEquals(listOf(id("bkg-1")), plan.stops[1].boardingBookings); assertEquals(listOf(id("bkg-1")), plan.stops[2].alightingBookings)
        assertEquals(plan, trip.currentPlanOfShift(a, id("shift-day")))
    }

    @Test
    fun fahrtenplan_fremder_mandant_und_unbekannte_kennung_ergeben_nichts() {
        assertNull(trip.currentPlan(b, id("run-1")), "Kennung des Mandanten A im Kontext von B")
        assertNull(trip.currentPlanOfShift(b, id("shift-day")))
        assertNull(trip.currentPlan(a, UUID.randomUUID())); assertNull(trip.currentPlan(nobody, id("run-1")))
    }

    @Test
    fun fahrer_und_qualifikationen_ohne_klarname() {
        val list = drivers.drivers(a)
        assertEquals(listOf(id("drv-1"), id("drv-2")).sortedBy { it.toString() }, list.map { it.id }, "stabil nach ID sortiert")
        val one = drivers.driver(a, id("drv-1"))!!
        assertEquals("ACTIVE", one.status)
        assertEquals(listOf("PERSONENBEFOERDERUNG" to null, "ROLLSTUHLBEFOERDERUNG" to null), one.qualifications.map { it.code to it.validTo })
        assertEquals(Instant.parse("2026-12-31T23:00:00Z"), drivers.driver(a, id("drv-2"))!!.qualifications.single().validTo)
        // die Sicht kennt weder Name noch Kontakt noch Nachweisverweis
        assertFalse(one.toString().contains("synthetisch") || one.toString().contains("geraet"), "kein Klarname/Kontakt in der Sicht")
    }

    @Test
    fun fahrer_fremder_mandant_ergibt_nichts() {
        assertNull(drivers.driver(b, id("drv-1")), "Fahrer des Mandanten A im Kontext von B")
        assertEquals(listOf(id("drv-1", "b"), id("drv-2", "b")).sortedBy { it.toString() }, drivers.drivers(b).map { it.id })
        assertNotEquals(drivers.drivers(a).map { it.id }, drivers.drivers(b).map { it.id })
        assertEquals(emptyList<Any>(), drivers.drivers(nobody))
    }

    @Test
    fun fahrzeuge_und_rollen() {
        val v = fleet.vehicles(a)
        assertEquals(listOf(id("veh-1"), id("veh-2")).sortedBy { it.toString() }, v.map { it.id })
        val two = fleet.vehicle(a, id("veh-2"))!!
        assertEquals(Triple(6, 1, setOf("RAMP")), Triple(two.seats, two.wheelchair, two.equipment)); assertEquals("M4-A-2", two.label); assertTrue(two.active)
        val roles = fleet.roles(a, id("veh-1"))
        assertEquals(listOf("OPERATOR" to id("org-y"), "OWNER" to id("org-x")), roles.map { it.role to it.organizationId })
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), roles.first().validFrom); assertNull(roles.first().validTo)
    }

    @Test
    fun flotte_fremder_mandant_ergibt_nichts() {
        assertNull(fleet.vehicle(b, id("veh-1")), "Fahrzeug des Mandanten A im Kontext von B")
        assertEquals(emptyList<Any>(), fleet.roles(b, id("veh-1")))
        assertEquals(listOf(id("veh-1", "b"), id("veh-2", "b")).sortedBy { it.toString() }, fleet.vehicles(b).map { it.id })
        assertEquals(emptyList<Any>(), fleet.vehicles(nobody))
    }

    @Test
    fun zusage_ist_ereignis_und_position_nur_wo_eine_quelle_existiert() {
        val bk = booking ?: return   // echte Quelle: WP-M2-23 (nicht vorhanden)
        val p = bk.promise(a, id("bkg-1"))!!
        assertEquals("CONFIRMED", p.state); assertEquals(Instant.parse("2026-12-01T06:10:00Z"), p.pickupEarliest); assertEquals(Instant.parse("2026-12-01T06:45:00Z"), p.arrivalLatest)
        assertNull(bk.promise(b, id("bkg-1")))
        val ev = events!!.eventsOfShift(a, id("shift-day"))
        assertEquals(listOf("CHECK_IN", "ARRIVAL"), ev.map { it.type }); assertEquals(Instant.parse("2026-12-01T06:17:00Z"), ev.first().occurredAt)
        assertEquals(emptyList<Any>(), events!!.eventsOfShift(b, id("shift-day")))
        val pos = positions!!.latest(a, id("veh-1"))!!
        assertEquals(Instant.parse("2026-12-01T06:37:00Z"), pos.measuredAt, "jüngste Position"); assertTrue(pos.longitude in 13.79..13.81)
        assertNull(positions!!.latest(b, id("veh-1")))
    }
}

/** Stub gegen den Vertrag (ohne Datenbank). */
class StubReadPortContractTest : ReadPortContractTest() {
    private val stub by lazy { FixtureReadPorts.fromCatalog(fx.catalog) }
    override val trip get() = stub
    override val drivers get() = stub
    override val fleet get() = stub
    override val booking get() = stub
    override val events get() = stub
    override val positions get() = stub

    @Test
    fun stub_unterscheidet_mandanten_ohne_datenbank_zugriff() {
        assertTrue(stub.drivers(fx.context(fx.tenantA)).isNotEmpty() && stub.drivers(fx.context(fx.tenantB)).isNotEmpty())
        assertNotEquals(stub.drivers(fx.context(fx.tenantA)), stub.drivers(fx.context(fx.tenantB)))
    }
}

/** Echte Implementierungen der Module (Fahrtenplan: `booking-dispatch`, Fahrer: `staff-time`, Flotte: `fleet-safety`) auf dem geladenen Seed unter der Runtime-Rolle. */
class RealReadPortContractTest : ReadPortContractTest() {
    private val apis by lazy {
        fx.ds
        val audit = JdbcAuditPort(); val outbox = Outbox()
        Triple(BookingdispatchApi.create(fx.ctxTx), StafftimeApi.create(fx.ctxTx, audit, outbox), FleetsafetyApi.create(fx.ctxTx, audit, outbox))
    }
    override val trip get() = apis.first.tripPlans
    override val drivers get() = apis.second.driverReadPort
    override val fleet get() = apis.third.fleetReadPort

    @BeforeAll fun seed() = fx.loadSeed()

    @Test
    fun stub_und_echte_implementierung_liefern_dieselben_sichten() {
        val stub = FixtureReadPorts.fromCatalog(fx.catalog)
        for (tenant in listOf(fx.tenantA, fx.tenantB)) {
            val c = fx.context(tenant)
            assertEquals(stub.drivers(c), drivers.drivers(c), "Fahrer $tenant")
            assertEquals(stub.vehicles(c).map { it.copy(label = null) }, fleet.vehicles(c).map { it.copy(label = null) }, "Fahrzeuge $tenant")
            for (v in stub.vehicles(c)) assertEquals(stub.roles(c, v.id).map { it.copy(organizationId = it.organizationId) }, fleet.roles(c, v.id), "Rollen ${v.id}")
        }
        val c = fx.context(fx.tenantA)
        val real = trip.currentPlan(c, id("run-1"))!!; val st = stub.currentPlan(c, id("run-1"))!!
        assertEquals(st, real, "Fahrtenplan Stub = Seed")
    }

    @Test
    fun ohne_tenantkontext_liefert_die_datenbank_keine_zeile() {
        // der Port braucht einen Mandanten im Kontext; unmittelbar gegen die Runtime-Rolle ohne gesetzten Kontext sind alle beteiligten Tabellen leer (RLS)
        fx.ds.connection.use { c ->
            for (t in listOf("vehicle_run", "route_stop", "driver", "qualification_revision", "vehicle", "vehicle_assignment")) {
                c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM taktovia.$t").use { r -> r.next(); assertEquals(0, r.getInt(1), "$t ohne Tenantkontext") } }
            }
        }
    }
}
