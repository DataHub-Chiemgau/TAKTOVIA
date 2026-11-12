package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.CapacityCombination
import de.taktovia.fleetsafety.api.CapacityLayout
import de.taktovia.fleetsafety.api.CapacityPatch
import de.taktovia.fleetsafety.api.CapacityRestriction
import de.taktovia.fleetsafety.api.CapacitySpaces
import de.taktovia.fleetsafety.api.VehicleMasterInput
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-04 / LH-6.8-FLT-004: Kapazitäts- und Stellplatzkonfiguration. Originalabnahme: ein Rollstuhlplatz reduziert die Sitzkapazität gemäß Fahrzeugkonfiguration (um genau n).
 * Die Konkurrenz zu einem Hold (echter Hold-Pfad aus `booking-dispatch`) prüft der Test der Anwendung (`app`: CapacityConfigHoldTest), weil `fleet-safety` nicht von
 * `booking-dispatch` abhängen darf.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CapacityConfigTest {
    private lateinit var fx: FleetFixture

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private fun vehicle(w: FleetFixture.World, ctx: de.taktovia.platform.context.RequestContext) = fx.fleet.vehicles.create(ctx, VehicleMasterInput.parse(linkedMapOf(
        "vehicle_label" to "CAP-" + UUID.randomUUID().toString().take(8), "vehicle_type" to "PKW", "operator_organization_id" to w.orgs[0].toString(),
        "capacity" to mapOf("seats" to 6, "wheelchair" to 0), "equipment" to listOf("RAMP"), "active" to true)))

    private val spaces = CapacitySpaces(seatPlaces = 6, wheelchairSpaces = 2, seatsReplacedPerWheelchair = 2)
    private val combos = listOf(CapacityCombination("STANDARD", 0), CapacityCombination("ONE_WC", 1), CapacityCombination("TWO_WC", 2))

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)

    @Test
    fun rollstuhlplatz_reduziert_sitzkapazitaet_um_n() {
        val w = fx.world(); val ctx = fx.context(w.tenant); val v = vehicle(w, ctx)
        val view = fx.fleet.vehicleCapacities.updateCapacity(ctx, v.id, v.revision, CapacityPatch(spaces, null, combos, "ONE_WC", listOf(CapacityRestriction("MAX_TOTAL_PERSONS", 5))))
        val layout = view.layout!!
        // n = 2: ein belegter Rollstuhlplatz reduziert die verfügbare Sitzkapazität um genau 2, zwei um genau 4
        assertEquals(6, layout.availableAfterOccupying(0).seats)
        assertEquals(4, layout.availableAfterOccupying(1).seats); assertEquals(1, layout.availableAfterOccupying(1).wheelchair)
        assertEquals(2, layout.availableAfterOccupying(2).seats)
        // die Disposition erhält diesen Vektor je Konfiguration (Modul-API) und den wirksamen Vektor in vehicle.capacity_config
        val vectors = fx.ctxTx.run(ctx, readOnly = true) { j -> fx.fleet.vehicleCapacities.resourceVectors(j, v.id) }.associateBy { it.code }
        assertEquals(setOf("STANDARD", "ONE_WC", "TWO_WC"), vectors.keys)
        assertEquals(6 to 0, vectors["STANDARD"]!!.let { it.seats to it.wheelchair })
        assertEquals(4 to 1, vectors["ONE_WC"]!!.let { it.seats to it.wheelchair })
        assertEquals(2 to 2, vectors["TWO_WC"]!!.let { it.seats to it.wheelchair })
        assertEquals(listOf("RAMP"), vectors["ONE_WC"]!!.equipment)
        assertEquals(4 to 1, view.effective.let { it.seats to it.wheelchair })
        val config = fx.strings("SELECT capacity_config::text FROM taktovia.vehicle WHERE id=?", v.id).single()
        assertTrue(config.contains("\"seats\": 4") && config.contains("\"wheelchair\": 1"), config)
        // anderer n-Wert ist Konfigurationsdatum des Fahrzeugs, keine feste Zahl
        val v3 = fx.fleet.vehicleCapacities.updateCapacity(ctx, v.id, view.revision, CapacityPatch(spaces = CapacitySpaces(6, 2, 1))).layout!!
        assertEquals(5, v3.availableAfterOccupying(1).seats); assertEquals(4, v3.availableAfterOccupying(2).seats)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.vehicle.capacity_changed' AND object_ref=? AND redacted_changes->>'previous_revision'=?", w.tenant.value, "vehicle:${v.id}", (view.revision).toString()))
    }

    @Test
    fun widerspruechliche_kombination_wird_abgewiesen() {
        val w = fx.world(); val ctx = fx.context(w.tenant); val v = vehicle(w, ctx)
        fun reject(name: String, pointer: String, patch: CapacityPatch) {
            val e = problem { fx.fleet.vehicleCapacities.updateCapacity(ctx, v.id, v.revision, patch) }
            assertEquals(ErrorCode.VALIDATION_FAILED, e.code, name)
            assertTrue(e.violations.any { it.pointer == pointer }, "$name: erwartet $pointer, erhalten ${e.violations}")
        }
        // mehr belegte Fläche als vorhanden: zwei Rollstuhlplätze ersetzen je 4 Sitze, es gibt nur 6
        reject("Ersatz größer als Sitzbestand", "/spaces/seats_replaced_per_wheelchair", CapacityPatch(CapacitySpaces(6, 2, 4), null, combos, "STANDARD"))
        // Kombination mit mehr Rollstuhlplätzen als Stellplätzen
        reject("Kombination über Stellplätzen", "/combinations/1/wheelchair_places", CapacityPatch(spaces, null, listOf(CapacityCombination("STANDARD", 0), CapacityCombination("BAD", 3)), "STANDARD"))
        // unbekannte aktive Kombination, doppelter Code, ungültige Restriktion
        reject("aktive Kombination unbekannt", "/active_combination", CapacityPatch(spaces, null, combos, "GIBTS_NICHT"))
        reject("doppelter Code", "/combinations/1/code", CapacityPatch(spaces, null, listOf(CapacityCombination("STANDARD", 0), CapacityCombination("STANDARD", 1)), "STANDARD"))
        reject("Restriktion über Stellplätzen", "/restrictions/0/value", CapacityPatch(spaces, null, combos, "STANDARD", listOf(CapacityRestriction("MAX_WHEELCHAIR_SPACES", 5))))
        // unbekannte Dimension/Restriktion, negative und nicht ganzzahlige Werte (Rohform wie vom Client)
        for ((name, raw, pointer) in listOf(
            Triple("unbekannte Dimension", mapOf("spaces" to mapOf("seat_places" to 6, "wheelchair_spaces" to 1, "seats_replaced_per_wheelchair" to 1, "stehplaetze" to 3)), "/spaces/stehplaetze"),
            Triple("unbekannter Teil", mapOf("laderaum" to 3), "/laderaum"),
            Triple("negativer Wert", mapOf("spaces" to mapOf("seat_places" to -1, "wheelchair_spaces" to 1, "seats_replaced_per_wheelchair" to 1)), "/spaces/seat_places"),
            Triple("nicht ganzzahlig", mapOf("spaces" to mapOf("seat_places" to 6, "wheelchair_spaces" to 1.5, "seats_replaced_per_wheelchair" to 1)), "/spaces/wheelchair_spaces"),
            Triple("unbekannte Restriktion", mapOf("restrictions" to listOf(mapOf("code" to "FLIEGEN", "value" to 1))), "/restrictions/0/code"),
        )) {
            val e = problem { CapacityPatch.parse(raw) }
            assertTrue(e.violations.any { it.pointer == pointer }, "$name: erwartet $pointer, erhalten ${e.violations}")
        }
        // erste Konfiguration verlangt Stellplätze und Kombinationen
        val e = problem { fx.fleet.vehicleCapacities.updateCapacity(ctx, v.id, v.revision, CapacityPatch(equipment = setOf("RAMP"))) }
        assertTrue(e.violations.map { it.pointer }.containsAll(listOf("/spaces", "/combinations", "/active_combination")))
        // nichts davon wurde gespeichert; ohne If-Match-Revision (veraltet) 412, fremder Mandant 404
        val unchanged = fx.fleet.vehicleCapacities.getCapacity(ctx, v.id)
        assertEquals(null, unchanged.layout); assertEquals(v.revision, unchanged.revision)
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { fx.fleet.vehicleCapacities.updateCapacity(ctx, v.id, 99, CapacityPatch(spaces, null, combos, "STANDARD")) }.code)
        val other = fx.world()
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.vehicleCapacities.getCapacity(fx.context(other.tenant), v.id) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.vehicleCapacities.updateCapacity(fx.context(other.tenant), v.id, v.revision, CapacityPatch(spaces, null, combos, "STANDARD")) }.code)
    }

    @Test
    fun teile_unabhaengig_aenderbar_und_nicht_rueckwirkend() {
        val w = fx.world(); val ctx = fx.context(w.tenant); val v = vehicle(w, ctx)
        var rev = fx.fleet.vehicleCapacities.updateCapacity(ctx, v.id, v.revision, CapacityPatch(spaces, setOf("RAMP"), combos, "TWO_WC", listOf(CapacityRestriction("MAX_TOTAL_PERSONS", 4)))).revision
        // bestehender Hold und Buchungszusage (Fixture) tragen ihren eigenen Vektor
        val shift = UUID.randomUUID(); val run = UUID.randomUUID(); val hold = UUID.randomUUID()
        fx.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,now(),now()+interval '8 hours','Europe/Berlin',0,'PUBLISHED')", w.tenant.value, shift, UUID.randomUUID(), v.id)
        fx.exec("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (?,?,?,?,1,'ACTIVE')", w.tenant.value, run, v.id, shift)
        fx.exec("INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state) VALUES (?,?,?,1,?,CAST(? AS jsonb),0,2,now()+interval '1 hour','HELD')",
            w.tenant.value, hold, run, "ref-$hold", "{\"schema_version\":\"1.0\",\"seats\":0,\"wheelchair\":2,\"equipment\":[]}")
        val snapshot = { fx.strings("SELECT run_revision::text || capacity_vector::text || state FROM taktovia.capacity_hold WHERE id=?", hold).single() }
        val holdBefore = snapshot()

        fun cap() = fx.fleet.vehicleCapacities.getCapacity(ctx, v.id)
        val base = cap().layout!!
        // Restriktionen ändern: Stellplätze und Kombinationen bleiben unverändert
        rev = fx.fleet.vehicleCapacities.updateCapacity(ctx, v.id, rev, CapacityPatch(restrictions = listOf(CapacityRestriction("MAX_TOTAL_PERSONS", 3)))).revision
        var now = cap().layout!!
        assertEquals(base.spaces, now.spaces); assertEquals(base.combinations, now.combinations); assertEquals(base.activeCombination, now.activeCombination); assertEquals(listOf(CapacityRestriction("MAX_TOTAL_PERSONS", 3)), now.restrictions)
        // Kombinationen ändern: Stellplätze und Restriktionen bleiben
        rev = fx.fleet.vehicleCapacities.updateCapacity(ctx, v.id, rev, CapacityPatch(combinations = listOf(CapacityCombination("STANDARD", 0), CapacityCombination("ONE_WC", 1)), activeCombination = "STANDARD")).revision
        now = cap().layout!!
        assertEquals(base.spaces, now.spaces); assertEquals(listOf(CapacityRestriction("MAX_TOTAL_PERSONS", 3)), now.restrictions); assertEquals(2, now.combinations.size)
        // Stellplätze ändern: Kombinationen und Restriktionen bleiben (sofern widerspruchsfrei)
        rev = fx.fleet.vehicleCapacities.updateCapacity(ctx, v.id, rev, CapacityPatch(spaces = CapacitySpaces(8, 2, 2))).revision
        now = cap().layout!!
        assertEquals(CapacitySpaces(8, 2, 2), now.spaces); assertEquals(2, now.combinations.size); assertEquals(listOf(CapacityRestriction("MAX_TOTAL_PERSONS", 3)), now.restrictions)
        assertEquals(8, cap().effective.seats)   // wirksamer Vektor folgt der aktiven Kombination STANDARD
        // nicht rückwirkend: der bestehende Hold ist unverändert, obwohl sich die Kapazität dreimal geändert hat
        assertEquals(holdBefore, snapshot())
        // der Fahrzeugstamm bleibt konsistent: Stamm mit abweichender Kapazität ist bei bestehender Konfiguration ein Konflikt; die Kapazitätsrevision zählt mit
        val stamm = fx.fleet.vehicles.get(ctx, v.id)
        assertEquals(rev, stamm.revision); assertEquals(8, stamm.seats)
        val conflict = problem { fx.fleet.vehicles.update(ctx, v.id, rev, VehicleMasterInput.parse(linkedMapOf("vehicle_label" to stamm.label, "vehicle_type" to "PKW", "operator_organization_id" to w.orgs[0].toString(),
            "capacity" to mapOf("seats" to 3, "wheelchair" to 0), "equipment" to listOf("RAMP"), "active" to true))) }
        assertEquals(ErrorCode.CONFLICT, conflict.code)
        assertEquals(CapacityLayout.VERSION, "1.0")
    }

    @Test
    fun gleichzeitige_aenderung_derselben_revision_genau_eine_gewinnt() {
        val w = fx.world(); val ctx = fx.context(w.tenant); val v = vehicle(w, ctx)
        val rev = fx.fleet.vehicleCapacities.updateCapacity(ctx, v.id, v.revision, CapacityPatch(spaces, null, combos, "STANDARD")).revision
        val pool = Executors.newFixedThreadPool(4); val start = CountDownLatch(1)
        try {
            val f = (0 until 4).map { i -> pool.submit<Result<Long>> { start.await(); runCatching { fx.fleet.vehicleCapacities.updateCapacity(ctx, v.id, rev, CapacityPatch(restrictions = listOf(CapacityRestriction("MAX_TOTAL_PERSONS", 1 + i)))).revision } } }
            start.countDown()
            val r = f.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, r.count { it.isSuccess })
            r.filter { it.isFailure }.forEach { assertEquals(ErrorCode.REVISION_MISMATCH, (it.exceptionOrNull() as ProblemException).code) }
        } finally { pool.shutdownNow() }
    }
}
