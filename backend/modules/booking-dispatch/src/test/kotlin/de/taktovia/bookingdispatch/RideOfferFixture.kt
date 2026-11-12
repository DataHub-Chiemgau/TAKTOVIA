package de.taktovia.bookingdispatch

import com.zaxxer.hikari.HikariDataSource
import de.taktovia.bookingdispatch.api.CandidateGate
import de.taktovia.bookingdispatch.api.DirectTravelTime
import de.taktovia.bookingdispatch.api.RideOfferCommand
import de.taktovia.bookingdispatch.api.RideOfferRequests
import de.taktovia.bookingdispatch.api.RidePlace
import de.taktovia.bookingdispatch.api.RideTimeKind
import de.taktovia.config.api.ConfigApi
import de.taktovia.config.api.CreateDraft
import de.taktovia.config.api.ScopeKind
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import de.taktovia.servicegeo.api.CreateService
import de.taktovia.servicegeo.api.CreateServiceArea
import de.taktovia.servicegeo.api.CreateStop
import de.taktovia.servicegeo.api.PublishServiceRevision
import de.taktovia.servicegeo.api.ServicegeoApi
import de.taktovia.servicegeo.api.ZoneRole
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Testaufbau für Anfrageerfassung, Zulässigkeit und Ablehnungsgründe (WP-M2-11 bis -13) gegen die echte Datenbank unter der Runtime-Rolle: je [world] ein eigener Mandant mit
 * Dienst (Betriebszeit täglich [from]-[to] Europe/Berlin, Standard 06:00-22:00), Bediengebiet (Quadrat um Dresden), Haltepunkten und veröffentlichten Dienstparametern. Eigene Klasse, keine Duplikate
 * der Fixtures anderer Pakete (die Modul-Fixtures liegen in `service-geo`/`config`).
 */
class RideOfferFixture : AutoCloseable {
    init { BookingTestDb.require() }
    val ds: HikariDataSource = BookingTestDb.runtime(24)
    private val admin = BookingTestDb.admin()
    val tx = TenantTransactions(ds)
    val ctxTx = ContextTransactions(tx, object : ContextRevalidator { override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, lock: Boolean) {} })
    private val audit = JdbcAuditPort()
    val outbox = Outbox()
    val config: ConfigApi = ConfigApi.create(ctxTx, audit, outbox)
    val geo: ServicegeoApi = ServicegeoApi.create(ctxTx, audit, config)

    /** Montag, 2. November 2026, 08:00 Ortszeit Berlin (MEZ, 07:00Z). */
    val now: Instant = Instant.parse("2026-11-02T07:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    class World(val tenant: TenantId, val operator: UUID, val serviceId: UUID, val admin: RequestContext, val stopA: UUID, val stopB: UUID, val areaKey: UUID)

    /** Mandant mit Dienst, Gebiet und zwei buchbaren Haltepunkten; [params] sind die veröffentlichten Mandantenparameter (`min_booking_lead_min`, …). */
    fun world(params: Map<String, Any?> = mapOf("min_booking_lead_min" to 30, "max_booking_horizon_days" to 14, "pickup_window_min" to 15, "arrival_grace_min" to 5, "stop_walk_max_m" to 400), from: String = "06:00", to: String = "22:00",
              areaTo: Instant? = null, wheelchair: String = "YES"): World {
        val t = UUID.randomUUID(); val le = UUID.randomUUID(); val op = UUID.randomUUID()
        adminUpdate("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,'ACTIVE')", t, "t-$t")
        val tenant = TenantId(t)
        tx.run(tenant) { j ->
            j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", t, le)
            j.update("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,?,?,CAST(? AS text[]))", t, op, le, "Betreiber", "f-$op", "{TRANSPORT_COMPANY}")
        }
        val adminCtx = context(tenant, scopes = setOf("organization:read", "organization:write", "services:read", "services:write", "config:read", "config:write", "config:publish", "geo:read", "geo:write"))
        val service = geo.structure.createService(adminCtx, CreateService(op, "Rufbus", "Europe/Berlin")).id
        val allDays = listOf(mapOf("weekdays" to listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"), "from" to from, "to" to to))
        geo.serviceRevisions.publish(adminCtx, service, 0, PublishServiceRevision("AREA_ON_DEMAND", allDays, emptyList<Any>(), T0, null, "Testaufbau"))
        val area = geo.areas.create(adminCtx, CreateServiceArea(service, square(13.60, 50.95, 0.30), T0, areaTo, ZoneRole.BOTH))
        val acc = mapOf("schema_version" to "1.0", "step_free" to "YES", "wheelchair_boarding" to wheelchair)
        val a = geo.stops.create(adminCtx, CreateStop(null, "Markt", mapOf("latitude" to 51.05, "longitude" to 13.74), acc, listOf("AREA_ON_DEMAND"), true, T0, null)).stopId
        val b = geo.stops.create(adminCtx, CreateStop(null, "Bahnhof", mapOf("latitude" to 51.10, "longitude" to 13.80), acc, listOf("AREA_ON_DEMAND"), true, T0, null)).stopId
        publishTenantParameters(adminCtx, params)
        return World(tenant, op, service, adminCtx, a, b, area.id)
    }

    private fun publishTenantParameters(ctx: RequestContext, values: Map<String, Any?>) {
        val v = config.revisions.createDraft(ctx, CreateDraft(ScopeKind.TENANT, ctx.tenant.value, "1", values, T0, null, "Testaufbau"))
        for ((state, extra) in listOf("VALIDATED" to "", "APPROVED" to ", approved_by = 'user:zweite-person'", "PUBLISHED" to ", published_at = clock_timestamp()")) {
            tx.run(ctx.tenant) { j -> j.update("UPDATE taktovia.config_revision SET state = ?$extra WHERE id = ?", state, v.id) }
        }
    }

    fun requests(travel: DirectTravelTime = DirectTravelTime { _, _ -> 600 }, gate: CandidateGate = CandidateGate.PASS_THROUGH, clock: Clock = this.clock): RideOfferRequests =
        RideOfferRequests.create(ctxTx, outbox, geo, config.effective, clock, travel, gate)

    /** Kontext eines Fahrgasts: nur `rides:write`, kein Dienstscope (Fahrgäste suchen in allen Diensten des Mandanten). */
    fun passenger(tenant: TenantId, subject: String = "fahrgast-" + UUID.randomUUID()): RequestContext =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/booking", subject, "sid-$subject", true), tenant, setOf("passenger"), setOf("rides:write", "rides:read"), emptySet(), UUID.randomUUID())

    fun context(tenant: TenantId, subject: String = UUID.randomUUID().toString(), scopes: Set<String>): RequestContext =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/booking", subject, "sid-$subject", true), tenant, setOf("tenant_admin"), scopes, emptySet(), UUID.randomUUID())

    /** Anfrage nahe der beiden Haltepunkte (Markt -> Bahnhof), Abfahrtswunsch [desiredAt] (RFC 3339 mit Offset). */
    fun command(w: World, desiredAt: String, kind: RideTimeKind = RideTimeKind.DEPARTURE, key: String = "key-" + UUID.randomUUID(), passengers: Int = 2, wheelchair: Int = 0,
                origin: RidePlace = RidePlace(13.7402, 51.0501), destination: RidePlace = RidePlace(13.8001, 51.1001), zone: String = "Europe/Berlin") =
        RideOfferCommand(w.serviceId, origin, destination, kind, desiredAt, zone, passengers, key, wheelchairSpaces = wheelchair)

    fun count(sql: String, vararg args: Any?): Int = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { it.next(); it.getInt(1) } }
    }

    fun adminUpdate(sql: String, vararg args: Any?): Int = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeUpdate() }
    }

    override fun close() { ds.close(); admin.close() }

    companion object {
        val T0: Instant = Instant.parse("2026-01-01T00:00:00Z")

        /** GeoJSON-MultiPolygon eines Quadrats (Länge/Breite). */
        fun square(x0: Double, y0: Double, size: Double): Map<String, Any?> {
            val x1 = x0 + size; val y1 = y0 + size
            return mapOf("type" to "MultiPolygon", "coordinates" to listOf(listOf(listOf(listOf(x0, y0), listOf(x1, y0), listOf(x1, y1), listOf(x0, y1), listOf(x0, y0)))))
        }
    }
}
