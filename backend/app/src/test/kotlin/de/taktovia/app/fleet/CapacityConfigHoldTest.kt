package de.taktovia.app.fleet

import de.taktovia.TaktoviaTestSupport
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.EquipmentCode
import de.taktovia.bookingdispatch.api.HoldOutcome
import de.taktovia.bookingdispatch.api.PlaceHold
import de.taktovia.bookingdispatch.api.RejectionReason
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.TimeWindow
import de.taktovia.fleetsafety.api.CapacityCombination
import de.taktovia.fleetsafety.api.CapacityPatch
import de.taktovia.fleetsafety.api.CapacitySpaces
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.VehicleCatalog
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-04 / LH-6.8-FLT-004, konkurrierend: Konfigurationsänderung parallel zu einem Hold im echten Hold-Pfad (`booking-dispatch`) und im echten Konfigurationspfad
 * (`fleet-safety`) gegen die Datenbank. Der Hold sperrt die Fahrzeugzeile gemeinsam (`FOR SHARE`), die Änderung exklusiv: der Hold prüft gegen eine konsistente Revision.
 * (Der Test liegt in `app`, weil `fleet-safety` nicht von `booking-dispatch` abhängen darf.)
 */
class CapacityConfigHoldTest {
    private val env = IdentityEnv
    private val ctxTx by lazy { ContextTransactions(env.tx, object : ContextRevalidator { override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, lock: Boolean) {} }) }
    private val fleet by lazy { FleetsafetyApi.create(ctxTx, JdbcAuditPort(), Outbox()) }
    private val dispatch by lazy { BookingdispatchApi.create(ctxTx) }
    private val window = TimeWindow(Instant.parse("2026-12-01T06:00:00Z"), Instant.parse("2026-12-01T07:00:00Z"))

    private class World(val tenant: TenantId, val vehicle: UUID, val run: UUID, val service: UUID, val ctx: RequestContext)

    private fun ctx(tenant: TenantId) = RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/fleet", "disp-" + UUID.randomUUID(), "sid-x", true), tenant, setOf("dispatcher"), setOf("fleet:write"), emptySet(), UUID.randomUUID())

    /** Mandant mit Dienst, Pool, Schicht, Freigabe, Fahrtenplan und einem Fahrzeug mit Konfiguration: 4 Sitzplätze, 2 Stellplätze, n = 1, aktive Kombination TWO_WC (2 Rollstuhlplätze). */
    private fun world(): World {
        TaktoviaTestSupport.requireDatabase()
        val tenant = env.tenant("Kap-" + UUID.randomUUID()); val t = tenant.value
        val le = UUID.randomUUID(); val org = UUID.randomUUID(); val cfg = UUID.randomUUID(); val service = UUID.randomUUID(); val vehicle = UUID.randomUUID(); val shift = UUID.randomUUID(); val run = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", t, le)
        env.exec("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,'Org',?,CAST(? AS text[]))", t, org, le, "f-$org", "{TRANSPORT_COMPANY}")
        env.exec("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'TENANT',?,1,'1',CAST('{}' AS jsonb),'2026-01-01T00:00:00Z','user:t','Test')", t, cfg, t)
        env.exec("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id) VALUES (?,?,?,'Dienst','Europe/Berlin',?)", t, service, org, cfg)
        env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,active_block,safety_revision,vehicle_label,vehicle_type) VALUES (?,?,?,CAST(? AS jsonb),false,1,?,'PKW')",
            t, vehicle, org.toString(), """{"schema_version":"1.0","seats":4,"wheelchair":0,"equipment":["RAMP"]}""", "V-" + vehicle.toString().take(8))
        env.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,CAST('2026-12-01 04:00:00+00' AS timestamptz),CAST('2026-12-01 14:00:00+00' AS timestamptz),'Europe/Berlin',1,'PUBLISHED')", t, shift, UUID.randomUUID(), vehicle)
        env.exec("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until) VALUES (?,?,?,?,1,?,1,CAST('2026-12-01 14:00:00+00' AS timestamptz))", t, UUID.randomUUID(), vehicle, shift, UUID.randomUUID())
        env.exec("INSERT INTO taktovia.vehicle_pool_assignment(tenant_id,id,service_id,vehicle_id,valid_from,created_by) VALUES (?,?,?,?,CAST('2026-01-01T00:00:00Z' AS timestamptz),'system:test')", t, UUID.randomUUID(), service, vehicle)
        env.exec("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (?,?,?,?,1,'ACTIVE')", t, run, vehicle, shift)
        val c = ctx(tenant)
        val v = fleet.vehicles.get(c, vehicle)
        fleet.vehicleCapacities.updateCapacity(c, vehicle, v.revision, CapacityPatch(CapacitySpaces(4, 2, 1), setOf("RAMP"),
            listOf(CapacityCombination("STANDARD", 0), CapacityCombination("ONE_WC", 1), CapacityCombination("TWO_WC", 2)), "TWO_WC"))
        return World(tenant, vehicle, run, service, c)
    }

    private fun place(w: World, demand: ResourceVector, from: Int = 0, to: Int = 2) =
        dispatch.holds.place(w.ctx, PlaceHold(w.run, 1, w.service, window, from, to, demand, reference = "ref-" + UUID.randomUUID()))

    private fun revision(w: World) = fleet.vehicles.get(w.ctx, w.vehicle).revision

    @Test
    fun katalog_der_ausstattung_entspricht_dem_ressourcenvektor() {
        assertEquals(EquipmentCode.entries.map { it.name }.toSet(), VehicleCatalog.EQUIPMENT)
    }

    @Test
    fun hold_vor_konfigaenderung_bleibt_unveraendert_neuer_hold_prueft_gegen_neuen_stand() {
        val w = world()
        val first = place(w, ResourceVector(0, 1)) as HoldOutcome.Placed          // Kapazität vor der Änderung: 2 Rollstuhlplätze
        val sql = "SELECT capacity_vector::text || run_revision::text || state FROM taktovia.capacity_hold WHERE id=?"
        val before = env.string(sql, first.hold.id).single()
        fleet.vehicleCapacities.updateCapacity(w.ctx, w.vehicle, revision(w), CapacityPatch(activeCombination = "STANDARD"))   // 0 Rollstuhlplätze, 4 Sitze
        assertEquals(before, env.string(sql, first.hold.id).single(), "bestehender Hold unverändert (nicht rückwirkend)")
        val second = place(w, ResourceVector(0, 1))
        assertTrue(second is HoldOutcome.Rejected && second.reason == RejectionReason.EQUIPMENT_MISSING, "neuer Hold prüft gegen den neuen Stand: $second")
        // auf Abschnitten ohne Altbelegung passt der Sitzbedarf weiterhin; Abschnitte mit Altbelegung über der neuen Kapazität nehmen nichts mehr an (konservativ, keine Überbuchung)
        assertTrue(place(w, ResourceVector(2, 0), 2, 4) is HoldOutcome.Placed, "Sitzbedarf passt weiterhin")
        assertTrue(place(w, ResourceVector(1, 0)) is HoldOutcome.Rejected, "Abschnitt mit Altbelegung über der neuen Kapazität")
    }

    @Test
    fun hold_wartet_auf_laufende_konfigaenderung_und_prueft_danach_gegen_den_neuen_stand() {
        val w = world()
        val rev = revision(w)
        // Eine laufende Änderung wird deterministisch festgehalten: ein unbestätigter Outbox-Eintrag mit dem Schlüssel des Änderungsereignisses lässt die Änderung nach dem
        // Sperren der Fahrzeugzeile am eindeutigen Schlüssel warten.
        val blocker = TaktoviaTestSupport.adminDataSourceOrNull()!!.connection
        blocker.autoCommit = false
        blocker.prepareStatement("INSERT INTO taktovia.outbox(tenant_id,event_id,aggregate_id,aggregate_revision,event_type,schema_version,occurred_at,payload,recipient_tenant_id) VALUES (?,?,?,?,'vehicle.changed','1.0',now(),CAST('{}' AS jsonb),?)").use {
            it.setObject(1, w.tenant.value); it.setObject(2, UUID.randomUUID()); it.setObject(3, w.vehicle); it.setLong(4, rev + 1); it.setObject(5, w.tenant.value); it.executeUpdate()
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val change = pool.submit<Long> { fleet.vehicleCapacities.updateCapacity(w.ctx, w.vehicle, rev, CapacityPatch(activeCombination = "STANDARD")).revision }
            assertTrue(waitForLockWaiters("INSERT INTO taktovia.outbox"), "die Änderung hält die Fahrzeugzeile und wartet")
            val hold = pool.submit<HoldOutcome> { place(w, ResourceVector(0, 1)) }
            assertTrue(waitForLockWaiters("FOR SHARE"), "der Hold wartet auf die laufende Änderung")
            assertFalse(hold.isDone, "der Hold darf nicht gegen den alten Stand prüfen, solange die Änderung läuft")
            blocker.rollback()
            assertEquals(rev + 1, change.get(20, TimeUnit.SECONDS))
            val outcome = hold.get(20, TimeUnit.SECONDS)
            assertTrue(outcome is HoldOutcome.Rejected && outcome.reason == RejectionReason.EQUIPMENT_MISSING, "der Hold sieht den Stand nach der Änderung: $outcome")
        } finally { runCatching { blocker.rollback() }; blocker.close(); pool.shutdownNow() }
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.capacity_hold h JOIN taktovia.vehicle_run r ON r.tenant_id=h.tenant_id AND r.id=h.run_id WHERE r.vehicle_id=?", w.vehicle))
    }

    @Test
    fun konfigaenderung_parallel_zu_hold_keine_ueberbuchung() {
        repeat(12) { round ->
            val w = world()
            // Rollstuhlplatz-Holds (je einer) laufen parallel zur Umstellung von 2 auf 1 Rollstuhlplatz
            val pool = Executors.newFixedThreadPool(4); val start = CountDownLatch(1)
            try {
                val holds = (0 until 3).map { pool.submit<HoldOutcome> { start.await(); place(w, ResourceVector(0, 1)) } }
                val change = pool.submit<Instant> { start.await(); fleet.vehicleCapacities.updateCapacity(w.ctx, w.vehicle, revision(w), CapacityPatch(activeCombination = "ONE_WC")); Instant.now() }
                start.countDown()
                val results = holds.map { it.get(30, TimeUnit.SECONDS) }; change.get(30, TimeUnit.SECONDS)
                val placed = results.count { it is HoldOutcome.Placed }
                assertTrue(placed in 1..2, "Runde $round: höchstens die alte Kapazität (2), mindestens ein Hold: $placed")
                // Invariante: ein Hold, der NACH der festgeschriebenen Änderung angelegt wurde, hat gegen den neuen Stand (1 Rollstuhlplatz) geprüft, nie gegen den alten
                val changedAt = env.string("SELECT occurred_at::text FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='vehicle.changed' AND payload->>'change_kind'='CAPACITY' ORDER BY aggregate_revision DESC LIMIT 1", w.tenant.value, w.vehicle).single()
                val lateHolds = env.count("SELECT count(*) FROM taktovia.capacity_hold h JOIN taktovia.vehicle_run r ON r.tenant_id=h.tenant_id AND r.id=h.run_id WHERE r.vehicle_id=? AND (h.expires_at - interval '180 seconds') > CAST(? AS timestamptz) + interval '5 milliseconds'", w.vehicle, changedAt)
                if (lateHolds > 0) assertEquals(1, placed, "Runde $round: ein Hold nach der Änderung darf die neue Kapazität (1) nicht überbuchen")
                assertEquals(placed, env.count("SELECT coalesce(sum((capacity_vector->>'wheelchair')::int),0) FROM taktovia.capacity_hold WHERE run_id=? AND state='HELD'", w.run))
            } finally { pool.shutdownNow() }
        }
    }

    private fun waitForLockWaiters(queryPart: String): Boolean {
        repeat(100) {
            val c = env.count("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock' AND query LIKE ?", "%$queryPart%")
            if (c >= 1) return true
            Thread.sleep(50)
        }
        return false
    }
}
