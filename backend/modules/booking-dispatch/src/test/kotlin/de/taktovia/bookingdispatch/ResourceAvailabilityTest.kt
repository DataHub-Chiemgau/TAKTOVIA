package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.TimeWindow
import de.taktovia.bookingdispatch.api.Unusable
import de.taktovia.bookingdispatch.api.VehicleAvailability
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * WP-M2-02: Leseport `ResourceAvailability` gegen die Referenzregion (Seed der Fixture, Runtime-Rolle mit Tenantkontext). Die Abnahme in M2 gilt nur für den Port
 * gegen die Seed-Fixtures; dieselben Antworten aus den führenden Daten (WP-M3-03/-06/-08, WP-M4-03/-65) weisen erst diese WPs nach.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResourceAvailabilityTest {
    private lateinit var fx: BookingFixture
    private lateinit var api: BookingdispatchApi
    private val now = Instant.parse("2026-12-01T05:00:00Z")
    private val window = TimeWindow(Instant.parse("2026-12-01T06:00:00Z"), Instant.parse("2026-12-01T07:00:00Z"))
    private fun a(key: String) = fixtureId("a", key)
    private fun b(key: String) = fixtureId("b", key)

    @BeforeAll fun up() { fx = BookingFixture(); api = BookingdispatchApi.create(fx.ctxTx, Clock.fixed(now, ZoneOffset.UTC)) }
    @AfterAll fun down() = fx.close()

    @Test
    fun port_liefert_erwartete_fahrzeuge_je_zeitraum() {
        val ctx = fx.context(fx.tenantA)
        val snap = api.resources.usableVehicles(ctx, a("svc-nord"), window)
        assertEquals(setOf(a("veh-v-std"), a("veh-v-wc")), snap.vehicles.map { it.vehicleId }.toSet())
        assertEquals("FIXTURE", snap.source); assertEquals(now, snap.asOf)
        val wc = snap.vehicles.single { it.vehicleId == a("veh-v-wc") }
        assertTrue(wc.capacityConfig.contains("\"wheelchair\": 1"), wc.capacityConfig)
        assertEquals(a("shift-v-wc"), wc.shiftId); assertEquals(a("rel-v-wc"), wc.releaseId)
        assertEquals(snap.vehicles.map { it.vehicleId }.sorted(), snap.vehicles.map { it.vehicleId }, "nach Fahrzeug-ID sortiert")
        // anderes Fenster: Freigabe von v-expired endet 05:00Z und deckt ein Fenster davor
        val early = api.resources.usableVehicles(ctx, a("svc-nord"), TimeWindow(Instant.parse("2026-12-01T04:10:00Z"), Instant.parse("2026-12-01T04:50:00Z")))
        assertEquals(setOf("v-std", "v-wc", "v-expired"), early.vehicles.map { v -> listOf("v-std", "v-wc", "v-expired").first { a("veh-$it") == v.vehicleId } }.toSet())
        // Fenster außerhalb der Schicht: niemand
        val late = api.resources.usableVehicles(ctx, a("svc-nord"), TimeWindow(Instant.parse("2026-12-01T13:30:00Z"), Instant.parse("2026-12-01T14:30:00Z")))
        assertTrue(late.vehicles.isEmpty(), "Schicht deckt das Fenster nicht")
        assertTrue(late.excluded.all { it.reason == Unusable.NO_SHIFT || it.reason == Unusable.SHIFT_ENDED_IN_WINDOW || it.reason == Unusable.VEHICLE_BLOCKED })
    }

    @Test
    fun gesperrtes_abgelaufenes_widerrufenes_und_unfreigegebenes_fahrzeug_fehlt() {
        val ctx = fx.context(fx.tenantA)
        val snap = api.resources.usableVehicles(ctx, a("svc-nord"), window)
        val why = snap.excluded.associate { it.vehicleId to it.reason }
        assertEquals(Unusable.VEHICLE_BLOCKED, why[a("veh-v-blocked")])
        assertEquals(Unusable.NO_VALID_RELEASE, why[a("veh-v-expired")], "Freigabe endet vor Fensterende")
        assertEquals(Unusable.NO_VALID_RELEASE, why[a("veh-v-norelease")], "ohne Freigabe nie einsetzbar")
        assertEquals(Unusable.NO_VALID_RELEASE, why[a("veh-v-revoked")])
        assertEquals(Unusable.NO_VALID_RELEASE, why[a("veh-v-stale")], "Freigabe gegen veraltete safety_revision")
        assertEquals(Unusable.NO_SHIFT, why[a("veh-v-noshift")])
        assertEquals(6, snap.excluded.size)
        for ((v, reason) in listOf("v-blocked" to Unusable.VEHICLE_BLOCKED, "v-norelease" to Unusable.NO_VALID_RELEASE, "v-noshift" to Unusable.NO_SHIFT)) {
            assertEquals(VehicleAvailability.NotUsable(a("veh-$v"), reason), api.resources.check(ctx, a("veh-$v"), a("svc-nord"), window))
        }
        assertTrue(api.resources.check(ctx, a("veh-v-wc"), a("svc-nord"), window) is VehicleAvailability.Usable)
    }

    @Test
    fun fahrzeug_ausserhalb_des_pools_wird_fuer_den_dienst_nicht_geliefert() {
        val ctx = fx.context(fx.tenantA)
        assertEquals(setOf(a("veh-v-sued")), api.resources.usableVehicles(ctx, a("svc-sued"), window).vehicles.map { it.vehicleId }.toSet())
        assertFalse(api.resources.usableVehicles(ctx, a("svc-nord"), window).vehicles.any { it.vehicleId == a("veh-v-sued") })
        assertEquals(VehicleAvailability.NotUsable(a("veh-v-sued"), Unusable.NOT_IN_POOL), api.resources.check(ctx, a("veh-v-sued"), a("svc-nord"), window))
        assertEquals(VehicleAvailability.NotUsable(a("veh-v-std"), Unusable.NOT_IN_POOL), api.resources.check(ctx, a("veh-v-std"), a("svc-sued"), window))
    }

    @Test
    fun mandant_a_sieht_keine_ressource_von_mandant_b() {
        val ctxA = fx.context(fx.tenantA); val ctxB = fx.context(fx.tenantB)
        assertEquals(setOf(b("veh-v-wc")), api.resources.usableVehicles(ctxB, b("svc-nord"), window).vehicles.map { it.vehicleId }.toSet())
        // Fremde Fahrzeug- und Dienst-IDs sind von nicht vorhandenen nicht zu unterscheiden
        assertEquals(VehicleAvailability.NotUsable(b("veh-v-wc"), Unusable.UNKNOWN_VEHICLE), api.resources.check(ctxA, b("veh-v-wc"), a("svc-nord"), window))
        assertEquals(VehicleAvailability.NotUsable(b("veh-v-wc"), Unusable.UNKNOWN_VEHICLE), api.resources.check(ctxA, b("veh-v-wc"), b("svc-nord"), window))
        val unknown = UUID.randomUUID()
        assertEquals(VehicleAvailability.NotUsable(unknown, Unusable.UNKNOWN_VEHICLE), api.resources.check(ctxA, unknown, a("svc-nord"), window))
        val foreignService = api.resources.usableVehicles(ctxA, b("svc-nord"), window)
        assertTrue(foreignService.vehicles.isEmpty() && foreignService.excluded.isEmpty())
    }

    @Test
    fun paralleler_zugriff_zweier_mandanten_ueber_denselben_pool_liefert_nie_fremde_zeilen() {
        val ctxA = fx.context(fx.tenantA); val ctxB = fx.context(fx.tenantB)
        val aIds = setOf(a("veh-v-std"), a("veh-v-wc")); val bIds = setOf(b("veh-v-wc"))
        val exec = Executors.newFixedThreadPool(8)
        try {
            val jobs = (0 until 400).map { i ->
                Callable {
                    if (i % 2 == 0) api.resources.usableVehicles(ctxA, a("svc-nord"), window).vehicles.map { it.vehicleId }.toSet() == aIds
                    else api.resources.usableVehicles(ctxB, b("svc-nord"), window).vehicles.map { it.vehicleId }.toSet() == bIds
                }
            }
            val results = exec.invokeAll(jobs).map { it.get() }
            assertEquals(400, results.size); assertTrue(results.all { it }, "Mandantenvermischung")
        } finally { exec.shutdownNow() }
        // nach den Läufen wirkt kein Kontext fort: eine Abfrage ohne Mandantenkontext sieht nichts
        fx.ds.connection.use { c -> c.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM taktovia.vehicle").use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) } } }
    }

    @Test
    fun booking_dispatch_schreibt_weder_fahrzeug_noch_schicht_noch_freigabe_noch_pool() {
        // Quelltextprüfung des Moduls (wie TableOwnership, hier gezielt für die vier gelesenen Tabellen)
        val src = BookingTestDb.repoRoot().resolve("backend/modules/booking-dispatch/src/main")
        val write = Regex("""(?i)\b(insert\s+into|update|delete\s+from)\s+(?:taktovia\.)?(vehicle|staff_shift|resource_release|vehicle_pool_assignment)\b""")
        val hits = Files.walk(src).use { s -> s.filter { Files.isRegularFile(it) }.toList() }.flatMap { f -> write.findAll(Files.readString(f)).map { "${f.fileName}: ${it.value}" }.toList() }
        assertEquals(emptyList<String>(), hits)
        // Laufzeit: der Port arbeitet in read-only-Transaktionen, ein Schreibversuch würde scheitern
        val ctx = fx.context(fx.tenantA)
        val failed = runCatching { fx.ctxTx.run(ctx, readOnly = true) { j -> j.update("UPDATE taktovia.vehicle SET active_block = false WHERE id = ?", a("veh-v-blocked")) } }
        assertTrue(failed.isFailure, "read-only: Schreiben muss scheitern")
    }

    @Test
    fun zeitfenster_ohne_laenge_ist_ungueltig() {
        val t = Instant.parse("2026-12-01T06:00:00Z")
        assertTrue(runCatching { TimeWindow(t, t) }.isFailure)
    }
}
