package de.taktovia.app.staff

import de.taktovia.TaktoviaTestSupport
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.CandidateRequest
import de.taktovia.bookingdispatch.api.HoldOutcome
import de.taktovia.bookingdispatch.api.PlaceHold
import de.taktovia.bookingdispatch.api.PlannedPosition
import de.taktovia.bookingdispatch.api.RejectionCategory
import de.taktovia.bookingdispatch.api.RejectionReason
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.TimeWindow
import de.taktovia.bookingdispatch.api.Unusable
import de.taktovia.bookingdispatch.api.VehicleAvailability
import de.taktovia.fleetsafety.api.AssignPoolService
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantId
import de.taktovia.stafftime.api.StafftimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WP-M4-65 (offene Vollabnahme aus WP-M4-17, WP-M2-02, WP-M2-15, WP-M2-22): die Disposition wertet die Besetzung aus dem Leseport „Schichtbesetzung“ von `staff-time` aus, nicht aus der
 * Schicht-Fixture. Echte Module, echte Datenbank (Runtime-Rolle). Der Test liegt in `app`, weil `booking-dispatch` nicht von `staff-time` abhängen darf.
 * Nachweise: (1) besetzte, veröffentlichte Schicht -> einsetzbar und Hold möglich (Quelle `STAFFING_PORT`); (2) freies Fahrzeug ohne veröffentlichte, besetzte Schicht (offen, Entwurf, nur geplant)
 * -> kein Angebot, Grund Personalmangel (E2E-081); (3) Besetzung entfällt, während ein Hold läuft -> nie eine Zusage nach dem Wegfall.
 */
class ShiftStaffingDispatchIntegrationTest {
    private val env = IdentityEnv
    private val ctxTx by lazy { ContextTransactions(env.tx, object : ContextRevalidator { override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, lock: Boolean) {} }) }
    private val fleet by lazy { FleetsafetyApi.create(ctxTx, JdbcAuditPort(), Outbox()) }
    private val staff by lazy { StafftimeApi.create(ctxTx, JdbcAuditPort(), Outbox()) }
    private val dispatch by lazy { BookingdispatchApi.create(ctxTx, staffing = staff.shiftStaffing, plannedPositions = { _, _, _, _ -> PlannedPosition("stop-1", Instant.parse("2040-06-01T05:00:00Z")) }) }

    private val window = TimeWindow(Instant.parse("2040-06-01T06:00:00Z"), Instant.parse("2040-06-01T07:00:00Z"))
    private class World(val tenant: TenantId, val vehicle: UUID, val run: UUID, val service: UUID, val shift: UUID, val ctx: RequestContext)

    private fun ctx(tenant: TenantId, vararg roles: String) = RequestContext(
        Actor(ActorType.HUMAN, "https://idp.test/realms/fleet", "u-" + UUID.randomUUID(), "sid-x", true), tenant, roles.toSet(), setOf("fleet:write"), emptySet(), UUID.randomUUID())

    /** Mandant mit Dienst, Fahrzeug im Pool, Fahrer, Umlauf und Einsatzfreigabe; die Schicht erhält der Test im gewünschten Zustand. */
    private fun world(shiftState: String?, driver: Boolean = true): World {
        TaktoviaTestSupport.requireDatabase()
        val tenant = env.tenant("Besetzung-" + UUID.randomUUID()); val t = tenant.value
        val le = UUID.randomUUID(); val org = UUID.randomUUID(); val cfg = UUID.randomUUID(); val service = UUID.randomUUID(); val vehicle = UUID.randomUUID(); val shift = UUID.randomUUID(); val run = UUID.randomUUID()
        val drv = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", t, le)
        env.exec("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,'Org',?,CAST(? AS text[]))", t, org, le, "f-$org", "{TRANSPORT_COMPANY}")
        env.exec("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'TENANT',?,1,'1',CAST('{}' AS jsonb),'2026-01-01T00:00:00Z','user:t','Test')", t, cfg, t)
        env.exec("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id) VALUES (?,?,?,'Dienst','Europe/Berlin',?)", t, service, org, cfg)
        env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,safety_revision,vehicle_label,vehicle_type,active_block) VALUES (?,?,?,CAST(? AS jsonb),1,?,'PKW',false)",
            t, vehicle, org.toString(), """{"schema_version":"1.0","seats":4,"wheelchair":0,"equipment":[]}""", "V-" + vehicle.toString().take(8))
        env.exec("INSERT INTO taktovia.driver(tenant_id,id,display_name,contact_channel,contact_value,qualification_seq) VALUES (?,?,'Fahrer','APP','x',1)", t, drv)
        if (shiftState != null) {
            env.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,CAST('2040-06-01 04:00:00+00' AS timestamptz),CAST('2040-06-01 14:00:00+00' AS timestamptz),'Europe/Berlin',1,?)",
                t, shift, if (driver) drv else null, if (driver) vehicle else null, shiftState)
        } else {
            // Fahrzeug ohne eigene Schicht: der Umlauf hängt an einer fremden, abgelaufenen Schicht (Fixture), damit ein Hold überhaupt adressierbar ist
            env.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,CAST('2040-05-01 04:00:00+00' AS timestamptz),CAST('2040-05-01 14:00:00+00' AS timestamptz),'Europe/Berlin',1,'ENDED')", t, shift, drv, vehicle)
        }
        env.exec("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (?,?,?,?,1,'ACTIVE')", t, run, vehicle, shift)
        env.exec("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until) VALUES (?,?,?,?,1,?,1,CAST('2040-06-01 14:00:00+00' AS timestamptz))", t, UUID.randomUUID(), vehicle, shift, UUID.randomUUID())
        val c = ctx(tenant, "dispatcher")
        fleet.vehiclePool.assign(c, AssignPoolService(vehicle, service, null, Instant.parse("2026-01-01T00:00:00Z"), null))
        return World(tenant, vehicle, run, service, shift, c)
    }

    private fun place(w: World) = dispatch.holds.place(w.ctx, PlaceHold(w.run, 1, w.service, window, 0, 2, ResourceVector(1, 0), reference = "ref-" + UUID.randomUUID()))
    private fun request(w: World) = CandidateRequest(w.service, window, ResourceVector(1, 0), "stop-1", Instant.parse("2040-06-01T06:30:00Z"))

    @Test
    fun besetzte_veroeffentlichte_schicht_macht_fahrzeug_einsetzbar_quelle_ist_der_port() {
        val w = world("PUBLISHED")
        val snap = dispatch.resources.usableVehicles(w.ctx, w.service, window)
        assertEquals("STAFFING_PORT", snap.source, "die Schicht-Fixture ist abgelöst"); assertEquals(listOf(w.vehicle), snap.vehicles.map { it.vehicleId })
        assertEquals(w.shift, snap.vehicles.single().shiftId, "Schicht und Freigabe gehören zusammen")
        assertTrue(dispatch.resources.check(w.ctx, w.vehicle, w.service, window) is VehicleAvailability.Usable)
        assertTrue(place(w) is HoldOutcome.Placed, "Hold auf besetzter, veröffentlichter Schicht")
        assertEquals(listOf(w.vehicle), dispatch.candidates.find(w.ctx, request(w)).candidates.map { it.resource.vehicleId })
    }

    @Test
    fun freies_fahrzeug_ohne_veroeffentlichte_besetzte_schicht_ergibt_kein_angebot_personalmangel() {
        // E2E-081: geplante Schicht (nicht veröffentlicht), Entwurf, offene Fahrerschicht (ohne Fahrer und Fahrzeug) und fehlende Schicht
        for ((label, w) in listOf("nur geplant (PLANNED)" to world("PLANNED"), "Entwurf (DRAFT)" to world("DRAFT"), "offene Fahrerschicht" to world("PUBLISHED", driver = false), "keine Schicht im Fenster" to world(null))) {
            val check = dispatch.resources.check(w.ctx, w.vehicle, w.service, window)
            assertEquals(VehicleAvailability.NotUsable(w.vehicle, Unusable.NO_SHIFT), check, label)
            val search = dispatch.candidates.find(w.ctx, request(w))
            assertTrue(search.candidates.isEmpty(), label); assertEquals(RejectionCategory.PERSONNEL, search.rejection!!.primary, "$label: Personalmangel")
            val hold = place(w)
            assertTrue(hold is HoldOutcome.Rejected && hold.reason == RejectionReason.RESOURCE_UNUSABLE, "$label: kein Hold ($hold)")
        }
    }

    @Test
    fun besetzung_entfaellt_waehrend_hold_nie_eine_zusage_nach_dem_wegfall() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(12) { i ->
                val w = world("PUBLISHED")
                val start = CountDownLatch(1)
                val holdOut = AtomicReference<HoldOutcome>(); val holdEnd = AtomicReference<Instant>(); val removedAt = AtomicReference<Instant>()
                val a = pool.submit { start.await(); holdOut.set(place(w)); holdEnd.set(Instant.now()) }
                val b = pool.submit {
                    start.await(); Thread.sleep((i % 4) * 7L)
                    // Wegfall der Besetzung: die Revision wird ersetzt (wie die Veröffentlichung einer neuen Revision ohne Fahrer)
                    env.exec("UPDATE taktovia.staff_shift SET state = 'SUPERSEDED' WHERE id = ?", w.shift); removedAt.set(Instant.now())
                }
                start.countDown(); a.get(30, TimeUnit.SECONDS); b.get(30, TimeUnit.SECONDS)
                when (val o = holdOut.get()) {
                    is HoldOutcome.Placed -> assertFalse(removedAt.get().isBefore(holdEnd.get()), "Zusage nur vor dem Wirksamwerden des Wegfalls (Lauf $i)")
                    is HoldOutcome.Rejected -> assertEquals(RejectionReason.RESOURCE_UNUSABLE, o.reason, "Lauf $i")
                    else -> throw AssertionError("unerwartet: $o")
                }
                val late = place(w)
                assertTrue(late is HoldOutcome.Rejected && late.reason == RejectionReason.RESOURCE_UNUSABLE, "Lauf $i: nach dem Wegfall keine Zusage: $late")
            }
        } finally { pool.shutdownNow() }
    }
}
