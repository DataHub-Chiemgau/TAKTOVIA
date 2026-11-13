package de.taktovia.app.fleet

import de.taktovia.TaktoviaTestSupport
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.bookingdispatch.api.BookingDemand
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.HoldOutcome
import de.taktovia.bookingdispatch.api.PlaceHold
import de.taktovia.bookingdispatch.api.RejectionReason
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.TimeWindow
import de.taktovia.fleetsafety.api.AssignPoolService
import de.taktovia.fleetsafety.api.BlockReason
import de.taktovia.fleetsafety.api.BlockSource
import de.taktovia.fleetsafety.api.DispatchVerdict
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.IssueRelease
import de.taktovia.fleetsafety.api.ReleaseOutcome
import de.taktovia.fleetsafety.api.SetBlock
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
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WP-M3-12 / LH-6.21-CHK-007 im echten Hold-Pfad (`booking-dispatch`) und echten Freigabepfad (`fleet-safety`) gegen die Datenbank: die eingeschränkte Freigabe (Ergebnis PASSED_RESTRICTED, Dimension
 * `wheelchair` entfallen) liefert der Disposition den reduzierten Ressourcenvektor. Positiv: volle Freigabe nimmt Rollstuhlfahrten an. Negativ: eingeschränkte Freigabe nimmt Fahrten mit Rollstuhlbedarf nicht an, andere
 * Fahrten schon; die Freigabe der jüngeren Prüfung ersetzt die ältere. Konkurrierend: Einschränkung parallel zu einem Hold mit Rollstuhlplatz: kein Commit auf die entfallene Dimension nach dem Wirksamwerden.
 * (Der Test liegt in `app`, weil `fleet-safety` nicht von `booking-dispatch` abhängen darf.)
 */
class AccessibilityHoldTest {
    private val env = IdentityEnv
    private val ctxTx by lazy { ContextTransactions(env.tx, object : ContextRevalidator { override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, lock: Boolean) {} }) }
    private val fleet by lazy { FleetsafetyApi.create(ctxTx, JdbcAuditPort(), Outbox(), clock = java.time.Clock.fixed(Instant.parse("2040-06-01T04:10:00Z"), java.time.ZoneOffset.UTC)) }
    private val dispatch by lazy { BookingdispatchApi.create(ctxTx) }

    private val window = TimeWindow(Instant.parse("2040-06-01T06:00:00Z"), Instant.parse("2040-06-01T07:00:00Z"))
    private val seat = BookingDemand(1, ResourceVector(1, 0), emptySet())
    private val wheelchair = BookingDemand(1, ResourceVector(0, 1), emptySet())

    private class World(val tenant: TenantId, val vehicle: UUID, val run: UUID, val service: UUID, val shift: UUID, val inspection: UUID, val ctx: RequestContext)

    private fun ctx(tenant: TenantId, vararg roles: String, service: Boolean = false) = RequestContext(
        Actor(if (service) ActorType.SERVICE else ActorType.HUMAN, "https://idp.test/realms/fleet", "u-" + UUID.randomUUID(), "sid-x", !service), tenant, roles.toSet(), setOf("fleet:write"), emptySet(), UUID.randomUUID())

    /** Mandant mit Dienst, Fahrzeug (Vorgabe `active_block = true`), qualifiziertem Fahrer, Schicht, bestandener Prüfung und Fahrtenplan; noch ohne Einsatzfreigabe. */
    private fun world(): World {
        TaktoviaTestSupport.requireDatabase()
        val tenant = env.tenant("Ready-" + UUID.randomUUID()); val t = tenant.value
        val le = UUID.randomUUID(); val org = UUID.randomUUID(); val cfg = UUID.randomUUID(); val service = UUID.randomUUID(); val vehicle = UUID.randomUUID(); val shift = UUID.randomUUID(); val run = UUID.randomUUID()
        val driver = UUID.randomUUID(); val template = UUID.randomUUID(); val inspection = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", t, le)
        env.exec("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,'Org',?,CAST(? AS text[]))", t, org, le, "f-$org", "{TRANSPORT_COMPANY}")
        env.exec("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'TENANT',?,1,'1',CAST('{}' AS jsonb),'2026-01-01T00:00:00Z','user:t','Test')", t, cfg, t)
        env.exec("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id) VALUES (?,?,?,'Dienst','Europe/Berlin',?)", t, service, org, cfg)
        env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,safety_revision,vehicle_label,vehicle_type) VALUES (?,?,?,CAST(? AS jsonb),1,?,'PKW')",
            t, vehicle, org.toString(), """{"schema_version":"1.0","seats":4,"wheelchair":1,"equipment":["RAMP"]}""", "V-" + vehicle.toString().take(8))
        env.exec("INSERT INTO taktovia.driver(tenant_id,id,display_name,contact_channel,contact_value,qualification_seq) VALUES (?,?,'Fahrer','APP','x',1)", t, driver)
        env.exec("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,approval_status,driver_seq) VALUES (?,?,?,'PBEFG',1,now() - interval '30 days','APPROVED',1)", t, UUID.randomUUID(), driver)
        env.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,CAST('2040-06-01 04:00:00+00' AS timestamptz),CAST('2040-06-01 14:00:00+00' AS timestamptz),'Europe/Berlin',1,'PUBLISHED')", t, shift, driver, vehicle)
        env.exec("INSERT INTO taktovia.checklist_template_revision(tenant_id,id,template_id,version,vehicle_class,service_mode,state,published_at,created_by,approved_by) VALUES (?,?,?,1,'PKW','STANDARD','PUBLISHED',now(),'user:a','user:b')", t, template, UUID.randomUUID())
        env.exec("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,status,outcome,started_at,completed_at,received_at,created_by) VALUES (?,?,?,?,?,?,'PRE_SHIFT','COMPLETED','PASSED',?,?,?,'user:t')",
            t, inspection, shift, vehicle, driver, template, Timestamp.from(Instant.parse("2040-06-01T04:00:00Z")), Timestamp.from(Instant.parse("2040-06-01T04:05:00Z")), Timestamp.from(Instant.parse("2040-06-01T04:05:01Z")))
        env.exec("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (?,?,?,?,1,'ACTIVE')", t, run, vehicle, shift)
        val c = ctx(tenant, "dispatcher")
        fleet.vehiclePool.assign(c, AssignPoolService(vehicle, service, null, Instant.parse("2026-01-01T00:00:00Z"), null))
        return World(tenant, vehicle, run, service, shift, inspection, c)
    }


    private fun ctx2(w: World) = ctx(w.tenant, service = true)
    private fun release(w: World): ReleaseOutcome = fleet.readiness.issueRelease(ctx2(w), IssueRelease(w.vehicle, w.shift, w.inspection))

    /** Zweite, eingeschränkte Prüfung derselben Schicht (Ergebnis PASSED_RESTRICTED, Rampe nicht funktionsfähig); noch ohne Freigabe. */
    private fun restrictedInspection(w: World): UUID = UUID.randomUUID().also { id ->
        env.exec("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,status,outcome,restriction,started_at,completed_at,received_at,created_by) " +
            "SELECT tenant_id,?,shift_id,vehicle_id,driver_ref,template_revision_id,'FOLLOW_UP','COMPLETED','PASSED_RESTRICTED',CAST(? AS jsonb),?,?,?,'user:test' FROM taktovia.inspection_run WHERE id = ?",
            id, """{"unavailable_dimensions":["wheelchair"],"step_codes":["RAMPE"]}""", Timestamp.from(Instant.parse("2040-06-01T04:06:00Z")), Timestamp.from(Instant.parse("2040-06-01T04:08:00Z")), Timestamp.from(Instant.parse("2040-06-01T04:08:01Z")), w.inspection)
    }

    private fun place(w: World, d: BookingDemand) = dispatch.holds.place(w.ctx, PlaceHold(w.run, 1, w.service, window, 0, 2, d.vector, reference = "ref-" + UUID.randomUUID()))

    @Test
    fun volle_freigabe_nimmt_rollstuhlfahrten_an_eingeschraenkte_nur_andere() {
        val full = world(); release(full)
        assertTrue(place(full, wheelchair) is HoldOutcome.Placed, "funktionsfähige Ausstattung: volle Freigabe inklusive Rollstuhldimension")
        val w = world(); release(w)
        val restricted = restrictedInspection(w)
        val issued = fleet.readiness.issueRelease(ctx2(w), IssueRelease(w.vehicle, w.shift, restricted)) as ReleaseOutcome.Issued
        // die jüngere Prüfung ersetzt die ältere Freigabe: genau eine gültige Freigabe, diese trägt die Einschränkung
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.resource_release WHERE shift_id = ? AND revoked_at IS NULL", w.shift)); assertEquals(1, env.count("SELECT count(*) FROM taktovia.resource_release WHERE shift_id = ? AND revoked_at IS NOT NULL AND revoke_reason = 'SUPERSEDED'", w.shift))
        val verdict = fleet.readiness.assertDispatchable(ctx(w.tenant, "dispatcher"), w.vehicle, w.shift, null, window.from) as DispatchVerdict.Dispatchable
        assertEquals(setOf("wheelchair"), verdict.unavailableDimensions); assertEquals(issued.release.id, verdict.releaseId)
        // Fahrt mit Rollstuhlbedarf: dieses Fahrzeug nimmt sie nicht an (Ausstattung fehlt in der Dimension Rollstuhl); Fahrt ohne Rollstuhl: weiter nutzbar
        val rejected = place(w, wheelchair)
        assertTrue(rejected is HoldOutcome.Rejected && rejected.reason == RejectionReason.EQUIPMENT_MISSING, "kein Commit auf die entfallene Dimension: $rejected")
        assertTrue(place(w, seat) is HoldOutcome.Placed, "für andere Fahrten bleibt das Fahrzeug nutzbar")
        val snap = dispatch.resources.usableVehicles(w.ctx, w.service, window)
        assertEquals(setOf("wheelchair"), snap.vehicles.single().unavailableDimensions)
        val vector = ResourceVector.parse(snap.vehicles.single().effectiveCapacityConfig, "/capacity_config")
        assertEquals(0, vector.wheelchair); assertFalse(vector.equipment.any { it.name == "RAMP" || it.name == "LIFT" }, "ohne Rampe/Lift in der Ausstattung"); assertEquals(4, vector.seats, "Sitze bleiben")
    }

    private fun promiseFor(w: TimeWindow) = de.taktovia.bookingdispatch.api.PromiseCore(w.from.toString(), w.from.plusSeconds(900).toString(), w.from.plusSeconds(2400).toString(), w.from.plusSeconds(3300).toString(), 950, "EUR", "std-24h", "STANDARD")

    @Test
    fun hold_mit_rollstuhlplatz_vor_der_einschraenkung_wird_danach_nicht_mehr_zugesagt() {
        val w = world(); release(w)
        val held = place(w, wheelchair) as HoldOutcome.Placed                          // Hold vor der Einschränkung möglich (volle Freigabe)
        fleet.readiness.issueRelease(ctx2(w), IssueRelease(w.vehicle, w.shift, restrictedInspection(w)))   // Rampe fällt aus
        val e = org.junit.jupiter.api.Assertions.assertThrows(de.taktovia.platform.problem.ProblemException::class.java) { dispatch.holds.confirm(w.ctx, held.hold.id, w.service, promiseFor(window), wheelchair) }
        assertEquals(de.taktovia.platform.problem.ErrorCode.CAPACITY_UNAVAILABLE, e.code)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ?", w.tenant.value), "kein Commit auf die entfallene Dimension")
        assertEquals("HELD", env.string("SELECT state FROM taktovia.capacity_hold WHERE id = ?", held.hold.id).single())
    }

    @Test
    fun einschraenkung_parallel_zu_hold_mit_rollstuhlplatz_kein_commit_auf_die_entfallene_dimension() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(12) { i ->
                val w = world(); release(w); val restricted = restrictedInspection(w)
                val start = CountDownLatch(1)
                val holdOut = AtomicReference<HoldOutcome>(); val holdEnd = AtomicReference<Instant>(); val restrictEnd = AtomicReference<Instant>()
                val a = pool.submit { start.await(); holdOut.set(place(w, wheelchair)); holdEnd.set(Instant.now()) }
                val b = pool.submit { start.await(); Thread.sleep((i % 4) * 7L); fleet.readiness.issueRelease(ctx2(w), IssueRelease(w.vehicle, w.shift, restricted)); restrictEnd.set(Instant.now()) }
                start.countDown(); a.get(30, TimeUnit.SECONDS); b.get(30, TimeUnit.SECONDS)
                when (val o = holdOut.get()) {
                    is HoldOutcome.Placed -> assertFalse(restrictEnd.get().isBefore(holdEnd.get()), "Zusage mit Rollstuhlplatz nur vor dem Wirksamwerden der Einschränkung (Lauf $i)")
                    is HoldOutcome.Rejected -> assertEquals(RejectionReason.EQUIPMENT_MISSING, o.reason, "Lauf $i")
                    else -> throw AssertionError("unerwartet: $o")
                }
                // nach beiden Transaktionen: in keiner Serialisierung eine weitere Zusage auf die entfallene Dimension
                assertTrue(place(w, wheelchair) is HoldOutcome.Rejected, "Lauf $i: nach der Einschränkung kein Commit mit Rollstuhlplatz")
                val other = place(w, seat); assertTrue(other is HoldOutcome.Placed, "Lauf $i: andere Fahrten bleiben möglich: $other (Hold-Ergebnis ${holdOut.get()})")
            }
        } finally { pool.shutdownNow() }
    }
}
