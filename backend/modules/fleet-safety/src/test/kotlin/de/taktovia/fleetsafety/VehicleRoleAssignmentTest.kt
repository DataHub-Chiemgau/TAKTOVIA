package de.taktovia.fleetsafety

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.fleetsafety.api.AssignVehicleRole
import de.taktovia.fleetsafety.api.VehicleRole
import de.taktovia.fleetsafety.api.VehicleRole.BILLING_PARTNER
import de.taktovia.fleetsafety.api.VehicleRole.DISPATCH_PROVIDER
import de.taktovia.fleetsafety.api.VehicleRole.OPERATOR
import de.taktovia.fleetsafety.api.VehicleRole.OWNER
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M1-34 / LH-I.4-MUL-004: Fahrzeugrollen mit Gültigkeitsintervallen an Fixture-Fahrzeugen des Stammteils (`vehicle`): vier Organisationen in den vier Rollen,
 * Eigentümerwechsel zum Stichtag (As-of vorher/nachher, Snapshot bleibt unverändert), Überlappung, rückwirkende Änderung, genau ein Audit und ein Outbox-Ereignis,
 * paralleler Wechsel zum selben Stichtag. Der Nachweis an abgerechneten Fahrzeugfahrten (`vehicle_run`) folgt mit WP-M2-16, WP-M3-03, WP-M4-33.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VehicleRoleAssignmentTest {
    private lateinit var fx: FleetFixture
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val registry = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012())

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private val stichtag = Instant.parse("2030-07-01T00:00:00Z")   // in der Zukunft: der Wechsel wirkt erst ab dann

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)

    private fun assign(w: FleetFixture.World, role: VehicleRole, org: Int, from: Instant, to: Instant? = null, vehicle: UUID = w.vehicle) =
        fx.fleet.vehicleRoles.assign(fx.context(w.tenant), AssignVehicleRole(vehicle, role, w.orgs[org], from, to))

    @Test
    fun vier_rollen_je_einzeln_aenderbar_und_as_of_aufloesung() {
        val w = fx.world(); val ctx = fx.context(w.tenant)
        // vier verschiedene Organisationen in den vier Rollen gleichzeitig
        val a = listOf(OWNER to 0, OPERATOR to 1, DISPATCH_PROVIDER to 2, BILLING_PARTNER to 3).associate { (r, o) -> r to assign(w, r, o, t0) }
        val snap = fx.fleet.vehicleRoles.rolesAt(ctx, w.vehicle, Instant.parse("2026-06-01T00:00:00Z"))
        assertEquals(mapOf(OWNER to w.orgs[0], OPERATOR to w.orgs[1], DISPATCH_PROVIDER to w.orgs[2], BILLING_PARTNER to w.orgs[3]), snap.roles.mapValues { it.value!!.organizationId })
        assertEquals(a.mapValues { it.value.id }, snap.roles.mapValues { it.value!!.assignmentId }, "Snapshot nennt die Intervall-IDs")
        // jede Rolle ist einzeln änderbar, die übrigen bleiben unberührt
        assign(w, OPERATOR, 4, stichtag)
        val after = fx.fleet.vehicleRoles.rolesAt(ctx, w.vehicle, stichtag.plusSeconds(1))
        assertEquals(w.orgs[4], after.roles[OPERATOR]!!.organizationId)
        listOf(OWNER to 0, DISPATCH_PROVIDER to 2, BILLING_PARTNER to 3).forEach { (r, o) -> assertEquals(w.orgs[o], after.roles[r]!!.organizationId, r.name) }
        // As-of-Auflösung ist halboffen: zum Stichtag selbst gilt schon die neue Zuordnung, davor noch die alte; vor allem Beginn gilt keine
        assertEquals(w.orgs[1], fx.fleet.vehicleRoles.rolesAt(ctx, w.vehicle, stichtag.minusMillis(1)).roles[OPERATOR]!!.organizationId)
        assertEquals(w.orgs[4], fx.fleet.vehicleRoles.rolesAt(ctx, w.vehicle, stichtag).roles[OPERATOR]!!.organizationId)
        assertNull(fx.fleet.vehicleRoles.rolesAt(ctx, w.vehicle, t0.minusMillis(1)).roles[OWNER])
        // die Liste kennt dieselbe Auflösung
        val valid = fx.fleet.vehicleRoles.list(ctx, w.vehicle, stichtag.plusSeconds(1), 50, null)
        assertEquals(4, valid.size); assertEquals(5, fx.fleet.vehicleRoles.list(ctx, w.vehicle, null, 50, null).size)
        // unbekanntes Fahrzeug: 404
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.vehicleRoles.rolesAt(ctx, UUID.randomUUID(), t0) }.code)
    }

    @Test
    fun eigentuemerwechsel_laesst_historischen_snapshot_unveraendert() {
        val w = fx.world(); val ctx = fx.context(w.tenant)
        assign(w, OWNER, 0, t0)
        val leistungszeitpunkt = Instant.parse("2027-03-01T10:00:00Z")
        val before = fx.fleet.vehicleRoles.rolesAt(ctx, w.vehicle, leistungszeitpunkt)   // ein abrechnendes Modul legt diesen Snapshot ab
        val stored = before.toMap()
        assertEquals(w.orgs[0], before.roles[OWNER]!!.organizationId)
        // Eigentümerwechsel zum Stichtag
        val change = assign(w, OWNER, 1, stichtag)
        assertEquals(1L, change.revision)
        // der bereits erzeugte Snapshot ist ein unveränderlicher Wert; erneut zum selben Leistungszeitpunkt aufgelöst gilt weiterhin der alte Eigentümer
        assertEquals(stored, before.toMap())
        val again = fx.fleet.vehicleRoles.rolesAt(ctx, w.vehicle, leistungszeitpunkt)
        assertEquals(before.roles[OWNER]!!.assignmentId, again.roles[OWNER]!!.assignmentId); assertEquals(w.orgs[0], again.roles[OWNER]!!.organizationId)
        // nach dem Stichtag der neue Eigentümer; das alte Intervall wurde genau zum Stichtag geschlossen und nicht verändert
        assertEquals(w.orgs[1], fx.fleet.vehicleRoles.rolesAt(ctx, w.vehicle, stichtag.plusSeconds(60)).roles[OWNER]!!.organizationId)
        val old = fx.fleet.vehicleRoles.list(ctx, w.vehicle, null, 50, null).single { it.organizationId == w.orgs[0] }
        assertEquals(stichtag, old.validTo); assertEquals(t0, old.validFrom); assertEquals(2, old.revision)
        // Snapshot in der Transaktion eines abrechnenden Moduls (Mandantentransaktion): gleiches Ergebnis
        val inTx = fx.tx.run(w.tenant, readOnly = true) { j -> fx.fleet.vehicleRoles.rolesAt(j, w.vehicle, leistungszeitpunkt) }
        assertEquals(again, inTx)
    }

    @Test
    fun ueberlappung_gleicher_rolle_wird_abgewiesen_andere_rollen_nicht() {
        val w = fx.world()
        assign(w, OWNER, 0, t0, Instant.parse("2026-06-01T00:00:00Z"))       // geschlossen in der Vergangenheit
        assign(w, OWNER, 1, Instant.parse("2026-06-01T00:00:00Z"), stichtag)  // anschließend, endet in der Zukunft
        // laufende bzw. künftige Überschneidung: 409
        assertEquals(ErrorCode.CONFLICT, problem { assign(w, OWNER, 2, Instant.parse("2029-01-01T00:00:00Z")) }.code)
        assertEquals(ErrorCode.CONFLICT, problem { assign(w, OWNER, 2, Instant.parse("2028-01-01T00:00:00Z"), Instant.parse("2028-02-01T00:00:00Z")) }.code)
        // eine andere Rolle am selben Zeitraum ist unabhängig
        assign(w, OPERATOR, 2, Instant.parse("2028-01-01T00:00:00Z"), Instant.parse("2028-02-01T00:00:00Z"))
        // anschließend (Ende exklusiv) ist zulässig
        assign(w, OWNER, 2, stichtag)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { assign(w, OWNER, 3, stichtag.plusSeconds(10), stichtag.plusSeconds(10)) }.code, "Ende nicht nach Beginn")
        // nichts davon hinterließ Audit oder Ereignis
        assertEquals(4, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='vehicle.role_assignment_changed'", w.tenant.value))
    }

    @Test
    fun rueckwirkende_aenderung_in_abgeschlossenem_intervall_422() {
        val w = fx.world()
        assign(w, OWNER, 0, t0, Instant.parse("2026-06-01T00:00:00Z"))   // abgeschlossen (Ende liegt in der Vergangenheit)
        assign(w, OWNER, 1, Instant.parse("2026-06-01T00:00:00Z"))       // läuft unbegrenzt
        val e = problem { assign(w, OWNER, 2, Instant.parse("2026-03-01T00:00:00Z")) }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code)
        assertTrue(e.violations.any { it.pointer == "/valid_from" }, e.violations.toString())
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { assign(w, OWNER, 2, Instant.parse("2025-12-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z")) }.code)
        // alles unverändert, nichts geschrieben
        val ctx = fx.context(w.tenant)
        assertEquals(2, fx.fleet.vehicleRoles.list(ctx, w.vehicle, null, 50, null).size)
        assertEquals(w.orgs[0], fx.fleet.vehicleRoles.rolesAt(ctx, w.vehicle, Instant.parse("2026-03-01T00:00:00Z")).roles[OWNER]!!.organizationId)
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.vehicle_role.changed'", w.tenant.value))
    }

    @Test
    fun organisation_oder_fahrzeug_eines_fremden_mandanten_ergibt_404_ohne_wirkung() {
        val a = fx.world(); val b = fx.world()
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.vehicleRoles.assign(fx.context(a.tenant), AssignVehicleRole(a.vehicle, OWNER, b.orgs[0], t0)) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.vehicleRoles.assign(fx.context(a.tenant), AssignVehicleRole(b.vehicle, OWNER, a.orgs[0], t0)) }.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle_assignment WHERE tenant_id IN (?, ?)", a.tenant.value, b.tenant.value))
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.vehicleRoles.rolesAt(fx.context(a.tenant), b.vehicle, t0) }.code)
    }

    @Test
    fun aenderung_erzeugt_genau_ein_audit_und_ein_outbox_ereignis() {
        val w = fx.world(); val ctx = fx.context(w.tenant, "disp-1")
        val first = fx.fleet.vehicleRoles.assign(ctx, AssignVehicleRole(w.vehicle, OWNER, w.orgs[0], t0))
        val change = fx.fleet.vehicleRoles.assign(ctx, AssignVehicleRole(w.vehicle, OWNER, w.orgs[1], stichtag))
        fun events(id: UUID) = fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='vehicle.role_assignment_changed'", w.tenant.value, id)
        assertEquals(1, events(first.id).size); assertEquals(1, events(change.id).size, "der Wechsel (Schließen + Neuanlage) ist genau eine Änderung")
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.vehicle_role.changed' AND object_ref=? AND actor_ref='user:disp-1'", w.tenant.value, "vehicle:${w.vehicle}"))
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='vehicle.role_assignment_changed'", w.tenant.value))
        val schema = registry.getSchema(Files.readString(repo.resolve("api/events/vehicle.roleassignmentchanged.v1.schema.json")))
        (events(first.id) + events(change.id)).forEach { assertEquals(emptyList<String>(), schema.validate(it, InputFormat.JSON).map { m -> m.toString() }, it) }
        assertTrue(events(change.id).single().contains(first.id.toString()), "das Ereignis nennt das geschlossene Intervall")
        // Fehlerfall hinterlässt nichts
        assertEquals(ErrorCode.CONFLICT, problem { fx.fleet.vehicleRoles.assign(ctx, AssignVehicleRole(w.vehicle, OWNER, w.orgs[2], stichtag)) }.code)
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='vehicle.role_assignment_changed'", w.tenant.value))
    }

    @Test
    fun paralleler_eigentuemerwechsel_gleicher_stichtag() {
        val w = fx.world(); assign(w, OWNER, 0, t0)
        val pool = Executors.newFixedThreadPool(8); val start = CountDownLatch(1)
        val r = (1..8).map { n -> pool.submit<String> { start.await(); try { assign(w, OWNER, 1 + (n % 4), stichtag); "ok" } catch (e: ProblemException) { e.code.name } } }
        start.countDown()
        val res = r.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, res.count { it == "ok" }, res.toString()); assertEquals(7, res.count { it == "CONFLICT" }, res.toString())
        val ctx = fx.context(w.tenant)
        assertEquals(2, fx.fleet.vehicleRoles.list(ctx, w.vehicle, null, 50, null).size, "genau ein Wechsel: altes Intervall geschlossen, ein neues")
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle_assignment WHERE tenant_id=? AND role='OWNER' AND valid_to IS NULL", w.tenant.value))
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='vehicle.role_assignment_changed'", w.tenant.value), "Erstzuordnung + ein Wechsel")
    }

    @Test
    fun snapshot_parallel_zum_wechsel_ist_nie_gemischt() {
        val w = fx.world(); assign(w, OWNER, 0, t0); assign(w, OPERATOR, 1, t0)
        val at = Instant.parse("2031-01-01T00:00:00Z")   // nach dem Stichtag: entweder alter oder neuer Eigentümer, nie keiner oder zwei
        val pool = Executors.newFixedThreadPool(6); val start = CountDownLatch(1)
        val change = pool.submit { start.await(); assign(w, OWNER, 2, stichtag) }
        val snaps = (1..5).map { pool.submit<Set<UUID?>> { start.await(); (1..20).flatMap { _ -> listOf(fx.fleet.vehicleRoles.rolesAt(fx.context(w.tenant), w.vehicle, at)) }.map { it.roles[OWNER]?.organizationId }.toSet() } }
        start.countDown()
        change.get(60, TimeUnit.SECONDS)
        val seen = snaps.flatMap { it.get(60, TimeUnit.SECONDS) }.toSet(); pool.shutdown()
        assertTrue(seen.all { it == w.orgs[0] || it == w.orgs[2] }, "nur alter oder neuer Eigentümer, nie keiner: $seen")
        assertNotEquals(setOf<UUID?>(null), seen)
        assertEquals(w.orgs[2], fx.fleet.vehicleRoles.rolesAt(fx.context(w.tenant), w.vehicle, at).roles[OWNER]!!.organizationId)
        assertEquals(w.orgs[1], fx.fleet.vehicleRoles.rolesAt(fx.context(w.tenant), w.vehicle, at).roles[OPERATOR]!!.organizationId)
    }
}
