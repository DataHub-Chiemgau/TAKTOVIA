package de.taktovia.app.fleet

import de.taktovia.TaktoviaTestSupport
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.bookingdispatch.api.BookingDemand
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.HoldOutcome
import de.taktovia.bookingdispatch.api.PlaceHold
import de.taktovia.bookingdispatch.api.PromiseCore
import de.taktovia.bookingdispatch.api.RejectionReason
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.TimeWindow
import de.taktovia.fleetsafety.api.AssignPoolService
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-06 / LH-6.8-FLT-002, konkurrierend: Dienstzuordnung endet parallel zu einem Hold, im echten Hold-Pfad (`booking-dispatch`) und im echten Zuordnungspfad (`fleet-safety`) gegen
 * die Datenbank. Beenden hält die Fahrzeugzeile exklusiv, Hold und Bestätigung gemeinsam (`FOR SHARE`, Reihenfolge Lauf vor Fahrzeug): der Commit prüft die Zuordnung danach erneut, es
 * entsteht keine Zusage nach dem Ende (beide Serialisierungen); bereits zugesagte Fahrten bleiben unverändert und stehen als Hinweis im Ergebnis.
 * (Der Test liegt in `app`, weil `fleet-safety` nicht von `booking-dispatch` abhängen darf.)
 */
class VehiclePoolHoldTest {
    private val env = IdentityEnv
    private val ctxTx by lazy { ContextTransactions(env.tx, object : ContextRevalidator { override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, lock: Boolean) {} }) }
    private val fleet by lazy { FleetsafetyApi.create(ctxTx, JdbcAuditPort(), Outbox()) }
    private val dispatch by lazy { BookingdispatchApi.create(ctxTx) }

    // feste Zeitpunkte weit in der Zukunft: ein Ende liegt nie in der Vergangenheit, solange der Test läuft
    private val windowAfterEnd = TimeWindow(Instant.parse("2040-06-01T06:00:00Z"), Instant.parse("2040-06-01T07:00:00Z"))
    private val windowBeforeEnd = TimeWindow(Instant.parse("2040-06-01T04:30:00Z"), Instant.parse("2040-06-01T05:30:00Z"))
    private val endAt = Instant.parse("2040-06-01T05:45:00Z")
    private val demand = BookingDemand(1, ResourceVector(1, 0), emptySet())

    private class World(val tenant: TenantId, val vehicle: UUID, val run: UUID, val service: UUID, val assignment: UUID, val ctx: RequestContext)

    private fun ctx(tenant: TenantId) = RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/fleet", "disp-" + UUID.randomUUID(), "sid-x", true), tenant, setOf("dispatcher"), setOf("fleet:write"), emptySet(), UUID.randomUUID())

    /** Mandant mit Dienst, Schicht, Freigabe, Fahrtenplan und einem Fahrzeug, das dem Dienst über die echte Zuordnungs-API (ab 2026-01-01, unbegrenzt) zugeordnet ist. */
    private fun world(): World {
        TaktoviaTestSupport.requireDatabase()
        val tenant = env.tenant("Pool-" + UUID.randomUUID()); val t = tenant.value
        val le = UUID.randomUUID(); val org = UUID.randomUUID(); val cfg = UUID.randomUUID(); val service = UUID.randomUUID(); val vehicle = UUID.randomUUID(); val shift = UUID.randomUUID(); val run = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", t, le)
        env.exec("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,'Org',?,CAST(? AS text[]))", t, org, le, "f-$org", "{TRANSPORT_COMPANY}")
        env.exec("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'TENANT',?,1,'1',CAST('{}' AS jsonb),'2026-01-01T00:00:00Z','user:t','Test')", t, cfg, t)
        env.exec("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id) VALUES (?,?,?,'Dienst','Europe/Berlin',?)", t, service, org, cfg)
        env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,active_block,safety_revision,vehicle_label,vehicle_type) VALUES (?,?,?,CAST(? AS jsonb),false,1,?,'PKW')",
            t, vehicle, org.toString(), """{"schema_version":"1.0","seats":4,"wheelchair":0,"equipment":[]}""", "V-" + vehicle.toString().take(8))
        env.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,CAST('2040-06-01 04:00:00+00' AS timestamptz),CAST('2040-06-01 14:00:00+00' AS timestamptz),'Europe/Berlin',1,'PUBLISHED')", t, shift, UUID.randomUUID(), vehicle)
        env.exec("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until) VALUES (?,?,?,?,1,?,1,CAST('2040-06-01 14:00:00+00' AS timestamptz))", t, UUID.randomUUID(), vehicle, shift, UUID.randomUUID())
        env.exec("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (?,?,?,?,1,'ACTIVE')", t, run, vehicle, shift)
        val c = ctx(tenant)
        val a = fleet.vehiclePool.assign(c, AssignPoolService(vehicle, service, null, Instant.parse("2026-01-01T00:00:00Z"), null))
        return World(tenant, vehicle, run, service, a.id, c)
    }

    private fun place(w: World, window: TimeWindow) = dispatch.holds.place(w.ctx, PlaceHold(w.run, 1, w.service, window, 0, 2, demand.vector, reference = "ref-" + UUID.randomUUID()))
    private fun promiseFor(window: TimeWindow) = PromiseCore(window.from.toString(), window.from.plusSeconds(900).toString(), window.from.plusSeconds(2400).toString(), window.from.plusSeconds(3300).toString(), 950, "EUR", "std-24h", "standard")
    private fun revision(w: World) = fleet.vehiclePool.list(w.ctx, w.vehicle, null, 50, null).single { it.id == w.assignment }.revision
    private fun waitForLockWaiters(queryPart: String): Boolean {
        repeat(100) {
            if (env.count("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock' AND query LIKE ?", "%$queryPart%") >= 1) return true
            Thread.sleep(50)
        }
        return false
    }

    @Test
    fun hold_vor_und_nach_dem_ende_der_zuordnung() {
        val w = world()
        assertTrue(place(w, windowBeforeEnd) is HoldOutcome.Placed, "vor dem Ende zugeordnet: Hold möglich")
        fleet.vehiclePool.end(w.ctx, w.vehicle, w.assignment, 1, endAt)
        val after = place(w, windowAfterEnd)
        assertTrue(after is HoldOutcome.Rejected && after.reason == RejectionReason.RESOURCE_UNUSABLE, "nach dem Ende keine neue Fahrt aus dem Dienst: $after")
        assertTrue(place(w, windowBeforeEnd) is HoldOutcome.Placed, "ein Fenster vor dem Ende bleibt zulässig")
    }

    @Test
    fun zuordnung_endet_parallel_zu_hold_beendigung_zuerst() {
        val w = world()
        // Eine laufende Beendigung wird deterministisch festgehalten: Transaktion, die die Fahrzeugzeile exklusiv sperrt und das Ende setzt, aber noch nicht festschreibt.
        val blocker = TaktoviaTestSupport.adminDataSourceOrNull()!!.connection
        blocker.autoCommit = false
        blocker.prepareStatement("SELECT 1 FROM taktovia.vehicle WHERE id = ? FOR NO KEY UPDATE").use { it.setObject(1, w.vehicle); it.executeQuery().use { rs -> rs.next() } }
        blocker.prepareStatement("UPDATE taktovia.vehicle_pool_assignment SET valid_to = CAST(? AS timestamptz) WHERE id = ?").use { it.setString(1, endAt.toString()); it.setObject(2, w.assignment); it.executeUpdate() }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val hold = pool.submit<HoldOutcome> { place(w, windowAfterEnd) }
            assertTrue(waitForLockWaiters("FOR SHARE"), "der Hold wartet auf die laufende Beendigung")
            assertFalse(hold.isDone, "der Hold darf nicht gegen den alten Stand prüfen, solange die Beendigung läuft")
            blocker.commit()
            val outcome = hold.get(20, TimeUnit.SECONDS)
            assertTrue(outcome is HoldOutcome.Rejected && outcome.reason == RejectionReason.RESOURCE_UNUSABLE, "der Hold sieht die beendete Zuordnung: $outcome")
        } finally { runCatching { blocker.rollback() }; blocker.close(); pool.shutdownNow() }
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.capacity_hold WHERE run_id = ?", w.run), "keine Zusage nach dem Ende")
    }

    @Test
    fun zuordnung_endet_parallel_zu_hold_hold_zuerst() {
        val w = world()
        // Ein laufender Hold: Transaktion, die die Fahrzeugzeile gemeinsam sperrt und noch nicht festschreibt.
        val blocker = TaktoviaTestSupport.adminDataSourceOrNull()!!.connection
        blocker.autoCommit = false
        blocker.prepareStatement("SELECT 1 FROM taktovia.vehicle WHERE id = ? FOR SHARE").use { it.setObject(1, w.vehicle); it.executeQuery().use { rs -> rs.next() } }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val end = pool.submit<Long> { fleet.vehiclePool.end(w.ctx, w.vehicle, w.assignment, 1, endAt).revision }
            assertTrue(waitForLockWaiters("FOR NO KEY UPDATE"), "die Beendigung wartet auf den laufenden Hold")
            assertFalse(end.isDone, "die Beendigung darf nicht an einem laufenden Hold vorbeigehen")
            blocker.commit()
            assertEquals(2L, end.get(20, TimeUnit.SECONDS))
        } finally { runCatching { blocker.rollback() }; blocker.close(); pool.shutdownNow() }
        val after = place(w, windowAfterEnd)
        assertTrue(after is HoldOutcome.Rejected && after.reason == RejectionReason.RESOURCE_UNUSABLE, "nach der Beendigung abgewiesen: $after")
    }

    @Test
    fun hold_vor_dem_ende_wird_beim_bestaetigen_nach_dem_ende_abgewiesen_keine_zusage_nach_ende() {
        val w = world()
        val held = place(w, windowAfterEnd) as HoldOutcome.Placed          // Zuordnung läuft noch: Hold möglich
        fleet.vehiclePool.end(w.ctx, w.vehicle, w.assignment, 1, endAt)    // Zuordnung endet vor dem Abholbeginn
        val e = assertThrows(ProblemException::class.java) { dispatch.holds.confirm(w.ctx, held.hold.id, w.service, promiseFor(windowAfterEnd), demand) }
        assertEquals(ErrorCode.CAPACITY_UNAVAILABLE, e.code)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ?", w.tenant.value), "keine Buchung nach dem Ende")
        assertEquals("HELD", env.string("SELECT state FROM taktovia.capacity_hold WHERE id = ?", held.hold.id).single(), "der Hold bleibt unverändert")
    }

    @Test
    fun zugesagte_fahrt_vor_dem_ende_bleibt_bestehen_und_steht_im_hinweis() {
        val w = world()
        val held = place(w, windowAfterEnd) as HoldOutcome.Placed
        val booking = dispatch.holds.confirm(w.ctx, held.hold.id, w.service, promiseFor(windowAfterEnd), demand)   // Zusage, solange die Zuordnung läuft
        val before = env.string("SELECT b.state || h.state || b.revision FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.id = b.hold_id WHERE b.id = ?", booking.id)
        val ended = fleet.vehiclePool.end(w.ctx, w.vehicle, w.assignment, revision(w), endAt)
        assertEquals(listOf(booking.id), ended.committedRidesAfterEnd.map { it.bookingId }, "Hinweis an die Leitstelle")
        assertEquals(before, env.string("SELECT b.state || h.state || b.revision FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.id = b.hold_id WHERE b.id = ?", booking.id), "die Zusage wird nicht still entfernt")
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='vehicle.pool_assignment_ended' AND payload->>'committed_booking_ids' LIKE ?", w.tenant.value, "%${booking.id}%"))
    }
}
