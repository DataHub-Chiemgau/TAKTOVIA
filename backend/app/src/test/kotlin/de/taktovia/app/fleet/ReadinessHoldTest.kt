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
 * WP-M3-08, Kern von PH-AT-020 im echten Hold-Pfad (`booking-dispatch`) und echten Sperrpfad (`fleet-safety`) gegen die Datenbank: Sperre und neue Zusage in zwei parallelen
 * Transaktionen. Der Hold sperrt die Fahrzeugzeile gemeinsam (nach dem Umlauf), die Sperre exklusiv; in keiner Serialisierung entsteht eine Zusage nach der Sperre. Die Freigabe stammt
 * aus der echten Freigabe-Erteilung (`issueRelease`), nicht aus einem Fixture, damit der Leseport `ResourceAvailability` die M3-Daten sieht (Umstellung, PH-AT-004 bleibt Aufgabe der Suite
 * `booking-dispatch`). (Der Test liegt in `app`, weil `fleet-safety` nicht von `booking-dispatch` abhängen darf.)
 */
class ReadinessHoldTest {
    private val env = IdentityEnv
    private val ctxTx by lazy { ContextTransactions(env.tx, object : ContextRevalidator { override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, lock: Boolean) {} }) }
    private val fleet by lazy { FleetsafetyApi.create(ctxTx, JdbcAuditPort(), Outbox(), clock = java.time.Clock.fixed(Instant.parse("2040-06-01T04:10:00Z"), java.time.ZoneOffset.UTC)) }   // Uhr zum Schichtbeginn: die Freigabe gilt bis zum Schichtende
    private val dispatch by lazy { BookingdispatchApi.create(ctxTx) }

    private val window = TimeWindow(Instant.parse("2040-06-01T06:00:00Z"), Instant.parse("2040-06-01T07:00:00Z"))
    private val demand = BookingDemand(1, ResourceVector(1, 0), emptySet())

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
            t, vehicle, org.toString(), """{"schema_version":"1.0","seats":4,"wheelchair":0,"equipment":[]}""", "V-" + vehicle.toString().take(8))
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

    private fun release(w: World): ReleaseOutcome = fleet.readiness.issueRelease(ctx(w.tenant, service = true), IssueRelease(w.vehicle, w.shift, w.inspection))
    private fun place(w: World) = dispatch.holds.place(w.ctx, PlaceHold(w.run, 1, w.service, window, 0, 2, demand.vector, reference = "ref-" + UUID.randomUUID()))

    @Test
    fun ohne_freigabe_keine_zusage_mit_freigabe_ja_nach_sperre_nein() {
        val w = world()
        val before = place(w)
        assertTrue(before is HoldOutcome.Rejected && before.reason == RejectionReason.RESOURCE_UNUSABLE, "ohne Einsatzfreigabe nie einsetzbar: $before")
        val issued = release(w) as ReleaseOutcome.Issued
        assertTrue(place(w) is HoldOutcome.Placed, "mit echter Freigabe (M3-Daten) setzt der Hold-Pfad auf das Fahrzeug")
        val block = fleet.readiness.setBlock(ctx(w.tenant, "dispatcher"), SetBlock(w.vehicle, BlockSource.OPERATIONS, BlockReason.OPERATIONAL_DISRUPTION))
        assertEquals(listOf(issued.release.id), block.revokedReleaseIds)
        val after = place(w)
        assertTrue(after is HoldOutcome.Rejected && after.reason == RejectionReason.RESOURCE_UNUSABLE, "gesperrt: keine neue Zusage: $after")
        val verdict = fleet.readiness.assertDispatchable(ctx(w.tenant, "dispatcher"), w.vehicle, w.shift, null, window.from)
        assertTrue(verdict is DispatchVerdict.Refused)
    }

    @Test
    fun sperre_und_neue_zusage_parallel_nie_zusage_nach_der_sperre() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(12) { i ->
                val w = world(); release(w)
                val start = CountDownLatch(1)
                val holdOut = AtomicReference<HoldOutcome>(); val holdEnd = AtomicReference<Instant>(); val blockEnd = AtomicReference<Instant>()
                val a = pool.submit { start.await(); holdOut.set(place(w)); holdEnd.set(Instant.now()) }
                val b = pool.submit {
                    start.await(); Thread.sleep((i % 4) * 7L)
                    fleet.readiness.setBlock(ctx(w.tenant, "dispatcher"), SetBlock(w.vehicle, BlockSource.OPERATIONS, BlockReason.OPERATIONAL_DISRUPTION, "race-$i")); blockEnd.set(Instant.now())
                }
                start.countDown(); a.get(30, TimeUnit.SECONDS); b.get(30, TimeUnit.SECONDS)
                when (val o = holdOut.get()) {
                    is HoldOutcome.Placed -> assertFalse(blockEnd.get().isBefore(holdEnd.get()), "Zusage nur vor dem Wirksamwerden der Sperre (Lauf $i)")
                    is HoldOutcome.Rejected -> assertEquals(RejectionReason.RESOURCE_UNUSABLE, o.reason, "Lauf $i")
                    else -> throw AssertionError("unerwartet: $o")
                }
                // nach beiden Transaktionen: gesperrt, keine weitere Zusage in keiner Serialisierung
                val late = place(w)
                assertTrue(late is HoldOutcome.Rejected, "Lauf $i: nach der Sperre keine Zusage: $late")
            }
        } finally { pool.shutdownNow() }
    }
}
