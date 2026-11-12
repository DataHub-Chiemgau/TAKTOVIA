package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.VehicleMasterInput
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * WP-M3-03 / LH-6.8-FLT-001: Fahrzeugstamm unter der Runtime-Rolle (RLS, zusammengesetzte Schlüssel). Originalabnahme: ein Fahrzeug mit ungültiger Kapazität wird nicht
 * speicherbar. Positiv: Anlegen, unveränderliches Lesen, Ändern, Deaktivieren (Audit und Ereignis). Negativ: Kapazität, Pflichtfelder, doppelte Kennung, fremder Mandant, 412.
 * Konkurrierend: gleiche Revision (genau eine gewinnt), gleiche Kennung (genau eine Anlage).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VehicleMasterTest {
    private lateinit var fx: FleetFixture

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private fun raw(org: UUID, label: String = "K-" + UUID.randomUUID().toString().take(8), seats: Any? = 4, wheelchair: Any? = 1, active: Boolean = true, equipment: List<String> = listOf("RAMP")): Map<String, Any?> =
        linkedMapOf("vehicle_label" to label, "vehicle_type" to "PKW_BARRIEREFREI", "operator_organization_id" to org.toString(), "capacity" to linkedMapOf("seats" to seats, "wheelchair" to wheelchair),
            "equipment" to equipment, "active" to active)

    private fun input(org: UUID, label: String = "K-" + UUID.randomUUID().toString().take(8), seats: Int = 4, wheelchair: Int = 1, active: Boolean = true) = VehicleMasterInput.parse(raw(org, label, seats, wheelchair, active))

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)

    @Test
    fun anlegen_und_lesen_alle_felder_unveraendert_und_neu_angelegtes_fahrzeug_nicht_disponierbar() {
        val w = fx.world(); val ctx = fx.context(w.tenant)
        val v = fx.fleet.vehicles.create(ctx, VehicleMasterInput.parse(raw(w.orgs[0], "KA-100", 5, 2, equipment = listOf("RAMP", "LIFT"))))
        val read = fx.fleet.vehicles.get(ctx, v.id)
        assertEquals(v, read)
        assertEquals("KA-100", read.label); assertEquals("PKW_BARRIEREFREI", read.type); assertEquals(w.orgs[0], read.operatorOrganizationId)
        assertEquals(5, read.seats); assertEquals(2, read.wheelchair); assertEquals(listOf("LIFT", "RAMP"), read.equipment); assertTrue(read.active); assertEquals(1, read.revision)
        // Schema-Default active_block = true (OP-01): ohne Einsatzfreigabe nicht disponierbar
        assertTrue(read.dispatchBlocked); assertFalse(read.dispatchable)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.vehicle.created' AND object_ref=?", w.tenant.value, "vehicle:${v.id}"))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='vehicle.created' AND aggregate_id=?", w.tenant.value, v.id))
    }

    @Test
    fun ungueltige_kapazitaet_wird_nicht_gespeichert() {
        val w = fx.world(); val ctx = fx.context(w.tenant)
        val bad = listOf<Triple<String, Map<String, Any?>, String>>(
            Triple("negative Sitze", raw(w.orgs[0], "NEG-1", seats = -1), "/capacity/seats"),
            Triple("negative Rollstuhlplätze", raw(w.orgs[0], "NEG-2", wheelchair = -3), "/capacity/wheelchair"),
            Triple("nicht ganzzahlig", raw(w.orgs[0], "NEG-3", seats = 2.5), "/capacity/seats"),
            Triple("Text statt Zahl", raw(w.orgs[0], "NEG-4", wheelchair = "eins"), "/capacity/wheelchair"),
            Triple("zu groß", raw(w.orgs[0], "NEG-5", seats = 1000), "/capacity/seats"),
            Triple("Sitze fehlen", raw(w.orgs[0], "NEG-6").toMutableMap().also { it["capacity"] = mapOf("wheelchair" to 1) }, "/capacity/seats"),
            Triple("Kapazität fehlt", raw(w.orgs[0], "NEG-7").toMutableMap().also { it.remove("capacity") }, "/capacity"),
        )
        for ((name, body, pointer) in bad) {
            val e = problem { fx.fleet.vehicles.create(ctx, VehicleMasterInput.parse(body)) }
            assertEquals(ErrorCode.VALIDATION_FAILED, e.code, name)
            assertTrue(e.violations.any { it.pointer == pointer }, "$name: Feldverweis $pointer fehlt: ${e.violations}")
        }
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle WHERE tenant_id=? AND vehicle_label LIKE 'NEG-%'", w.tenant.value), "nichts gespeichert")
        // jedes fehlende Pflichtfeld: 422 mit Feldverweis
        for (field in listOf("vehicle_label", "vehicle_type", "operator_organization_id", "equipment", "active")) {
            val body = raw(w.orgs[0], "REQ-$field").toMutableMap().also { it.remove(field) }
            val e = problem { fx.fleet.vehicles.create(ctx, VehicleMasterInput.parse(body)) }
            assertTrue(e.violations.any { it.pointer == "/$field" }, "Pflichtfeld $field")
        }
        // auch bei der Änderung wird eine ungültige Kapazität nicht gespeichert
        val v = fx.fleet.vehicles.create(ctx, input(w.orgs[0], "OK-1"))
        problem { fx.fleet.vehicles.update(ctx, v.id, v.revision, VehicleMasterInput.parse(raw(w.orgs[0], "OK-1", seats = -2))) }
        assertEquals(4, fx.fleet.vehicles.get(ctx, v.id).seats)
    }

    @Test
    fun doppelte_kennung_im_mandanten_abgewiesen_in_anderem_mandanten_zulaessig() {
        val a = fx.world(); val b = fx.world()
        fx.fleet.vehicles.create(fx.context(a.tenant), input(a.orgs[0], "DUP-1"))
        val e = problem { fx.fleet.vehicles.create(fx.context(a.tenant), input(a.orgs[1], "DUP-1")) }
        assertEquals(ErrorCode.CONFLICT, e.code); assertTrue(e.violations.any { it.pointer == "/vehicle_label" })
        fx.fleet.vehicles.create(fx.context(b.tenant), input(b.orgs[0], "DUP-1"))   // gleiche Kennung in anderem Mandanten
    }

    @Test
    fun betreiber_aus_fremdem_mandanten_nicht_speicherbar() {
        val a = fx.world(); val b = fx.world()
        val e = problem { fx.fleet.vehicles.create(fx.context(a.tenant), input(b.orgs[0], "FOREIGN-ORG")) }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code); assertTrue(e.violations.any { it.pointer == "/operator_organization_id" })
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle WHERE vehicle_label='FOREIGN-ORG'"))
    }

    @Test
    fun fremder_mandant_ergibt_404() {
        val a = fx.world(); val b = fx.world()
        val v = fx.fleet.vehicles.create(fx.context(a.tenant), input(a.orgs[0]))
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.vehicles.get(fx.context(b.tenant), v.id) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.vehicles.update(fx.context(b.tenant), v.id, 1, input(b.orgs[0])) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.vehicles.get(fx.context(a.tenant), UUID.randomUUID()) }.code)
        assertTrue(fx.fleet.vehicles.list(fx.context(b.tenant), null, 50, null).none { it.id == v.id })
        assertFalse(fx.ctxTx.run(fx.context(b.tenant), readOnly = true) { j -> fx.fleet.vehicles.isActive(j, v.id) })
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.vehicle.changed' AND object_ref=?", a.tenant.value, "vehicle:${v.id}"))
    }

    @Test
    fun aendern_veraltete_revision_412_deaktivieren_auditiert_und_bleibt_lesbar() {
        val w = fx.world(); val ctx = fx.context(w.tenant)
        val v = fx.fleet.vehicles.create(ctx, input(w.orgs[0], "CHG-1"))
        val v2 = fx.fleet.vehicles.update(ctx, v.id, 1, input(w.orgs[0], "CHG-1", seats = 6))
        assertEquals(2, v2.revision); assertEquals(6, v2.seats)
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { fx.fleet.vehicles.update(ctx, v.id, 1, input(w.orgs[0], "CHG-1", seats = 7)) }.code)
        assertEquals(6, fx.fleet.vehicles.get(ctx, v.id).seats)
        val off = fx.fleet.vehicles.update(ctx, v.id, 2, input(w.orgs[0], "CHG-1", seats = 6, active = false))
        assertFalse(off.active)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.vehicle.deactivated' AND object_ref=? AND actor_ref=?", w.tenant.value, "vehicle:${v.id}", "user:${ctx.actor.subject}"))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='vehicle.deactivated' AND aggregate_id=?", w.tenant.value, v.id))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.vehicle.changed' AND object_ref=?", w.tenant.value, "vehicle:${v.id}"))
        // in Historie und Nachweisen weiter lesbar, aber nicht mehr aktiv/disponierbar
        val read = fx.fleet.vehicles.get(ctx, v.id)
        assertFalse(read.active); assertFalse(read.dispatchable); assertNotNull(read.label)
        assertTrue(fx.fleet.vehicles.list(ctx, false, 50, null).any { it.id == v.id }); assertTrue(fx.fleet.vehicles.list(ctx, true, 50, null).none { it.id == v.id })
        assertFalse(fx.ctxTx.run(ctx, readOnly = true) { j -> fx.fleet.vehicles.isActive(j, v.id) })
    }

    @Test
    fun gleichzeitige_aenderung_genau_eine_gewinnt() {
        val w = fx.world(); val ctx = fx.context(w.tenant)
        repeat(10) { round ->
            val v = fx.fleet.vehicles.create(ctx, input(w.orgs[0], "RACE-$round-" + UUID.randomUUID().toString().take(6)))
            val pool = Executors.newFixedThreadPool(4); val start = CountDownLatch(1)
            try {
                val futures = (0 until 4).map { i -> pool.submit<Result<Long>> { start.await(); runCatching { fx.fleet.vehicles.update(ctx, v.id, v.revision, input(w.orgs[0], v.label!!, seats = 10 + i)).revision } } }
                start.countDown()
                val results = futures.map { it.get(20, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it.isSuccess }, "Runde $round: genau eine Änderung gewinnt")
                results.filter { it.isFailure }.forEach { assertEquals(ErrorCode.REVISION_MISMATCH, (it.exceptionOrNull() as ProblemException).code) }
                assertEquals(2, fx.fleet.vehicles.get(ctx, v.id).revision)
            } finally { pool.shutdownNow() }
        }
    }

    @Test
    fun gleichzeitige_anlage_gleicher_kennung_genau_eine() {
        val w = fx.world(); val ctx = fx.context(w.tenant)
        repeat(10) { round ->
            val label = "SAME-$round-" + UUID.randomUUID().toString().take(6)
            val pool = Executors.newFixedThreadPool(5); val start = CountDownLatch(1)
            try {
                val futures = (0 until 5).map { pool.submit<Result<UUID>> { start.await(); runCatching { fx.fleet.vehicles.create(ctx, input(w.orgs[0], label)).id } } }
                start.countDown()
                val results = futures.map { it.get(20, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it.isSuccess }, "Runde $round: genau eine Anlage")
                results.filter { it.isFailure }.forEach { assertEquals(ErrorCode.CONFLICT, (it.exceptionOrNull() as ProblemException).code) }
                assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle WHERE tenant_id=? AND vehicle_label=?", w.tenant.value, label))
                assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='vehicle.created' AND payload->>'vehicle_label'=?", w.tenant.value, label), "genau ein Ereignis")
            } finally { pool.shutdownNow() }
        }
    }
}
