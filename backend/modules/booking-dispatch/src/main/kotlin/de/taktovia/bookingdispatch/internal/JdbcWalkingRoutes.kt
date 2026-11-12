package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.EtaStopKeys
import de.taktovia.bookingdispatch.api.RideOfferParameters
import de.taktovia.bookingdispatch.api.RouteInfo
import de.taktovia.bookingdispatch.api.RoutingException
import de.taktovia.bookingdispatch.api.RoutingPort
import de.taktovia.bookingdispatch.api.WalkManeuver
import de.taktovia.bookingdispatch.api.WalkOrigin
import de.taktovia.bookingdispatch.api.WalkPlan
import de.taktovia.bookingdispatch.api.WalkPlanner
import de.taktovia.bookingdispatch.api.WalkSnapshot
import de.taktovia.bookingdispatch.api.WalkStatus
import de.taktovia.bookingdispatch.api.WalkStep
import de.taktovia.bookingdispatch.api.WalkTarget
import de.taktovia.bookingdispatch.api.WalkingRoutes
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.config.api.EffectiveRequest
import de.taktovia.config.api.EffectiveStatus
import de.taktovia.platform.approval.FourEyes
import de.taktovia.platform.authz.ServiceScoped
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.servicegeo.api.GeoPoint
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

@ServiceScoped
internal class JdbcWalkingRoutes(
    private val ctxTx: ContextTransactions, private val config: EffectiveConfig?, private val routing: RoutingPort?, private val stopKeys: EtaStopKeys?, private val clock: Clock,
) : WalkingRoutes {
    private val json = JsonMapper.builder().build()

    private class Input(val serviceId: UUID, val target: WalkTarget, val maxM: Int, val originRevision: UUID?)

    override fun plan(ctx: RequestContext, offerId: UUID, origin: WalkOrigin): WalkPlan? {
        // 1. lesen (Transaktion endet vor dem Routingaufruf)
        val input = ctxTx.run(ctx, readOnly = true) { j ->
            val o = j.query("SELECT service_id, actor_ref, pickup_stop_id, pickup_stop_revision_id FROM taktovia.ride_offer WHERE id = ?",
                { rs, _ -> arrayOf(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getObject(3, UUID::class.java), rs.getObject(4, UUID::class.java)) }, offerId).firstOrNull() ?: throw ProblemException.notFound()
            if (o[1] != FourEyes.refOf(ctx).value) throw ProblemException.notFound()
            val stopId = o[2] as UUID? ?: return@run null
            val rev = o[3] as UUID
            val target = j.query("SELECT name, ST_X(point), ST_Y(point) FROM taktovia.stop_revision WHERE id = ?", { rs, _ -> WalkTarget(stopId, rev, rs.getString(1), GeoPoint(rs.getDouble(2), rs.getDouble(3))) }, rev).first()
            val originRev = origin.stopId?.let { sid -> j.query("SELECT id FROM taktovia.stop_revision WHERE stop_id = ? ORDER BY revision DESC LIMIT 1", { rs, _ -> rs.getObject(1, UUID::class.java) }, sid).firstOrNull() }
            Input(o[0] as UUID, target, maxWalk(j, ctx, o[0] as UUID), originRev)
        } ?: return null
        // 2. Routing außerhalb jeder Transaktion; Ausfall, fehlender Worker oder freier Startpunkt führen zum Rückfall
        var route: RouteInfo? = null
        var reason = "ROUTING_UNAVAILABLE"
        if (origin.stopId == null) reason = "NO_ORIGIN_STOP"
        else if (routing != null && stopKeys != null && input.originRevision != null && origin.stopId != input.target.stopId) {
            route = try { routing.walk(ctx, stopKeys.keyOf(ctx, input.originRevision), stopKeys.keyOf(ctx, input.target.revisionId)) } catch (_: RoutingException) { null } catch (_: IllegalArgumentException) { null }
        }
        return WalkPlanner.plan(origin, input.target, input.maxM, route, reason, offerId, clock.instant())
    }

    /** `stop_walk_max_m` des Dienstes; ohne veröffentlichten Wert der Planungsdefault (nie ein erfundener Wert, die Herkunft steht in der Konfiguration). */
    private fun maxWalk(j: JdbcOperations, ctx: RequestContext, serviceId: UUID): Int {
        val item = config?.resolveInTransaction(j, ctx, EffectiveRequest(parameter = "stop_walk_max_m", serviceId = serviceId, at = clock.instant()))?.items?.firstOrNull()
        return (if (item != null && item.status == EffectiveStatus.EFFECTIVE) (item.value as? Number)?.toInt() else null) ?: RideOfferParameters.DEFAULTS.stopWalkMaxM
    }

    override fun store(ctx: RequestContext, snapshot: WalkSnapshot): WalkSnapshot = ctxTx.run(ctx) { j ->
        val owner = j.query("SELECT actor_ref FROM taktovia.ride_offer WHERE id = ?", { rs, _ -> rs.getString(1) }, snapshot.offerId).firstOrNull() ?: throw ProblemException.notFound()
        if (owner != FourEyes.refOf(ctx).value) throw ProblemException.notFound()
        j.update(
            "INSERT INTO taktovia.ride_offer_walk (tenant_id, id, offer_id, status, distance_m, duration_s, profile, map_version, fallback_reason, geometry, steps, pickup_stop_name, computed_at) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?) ON CONFLICT (tenant_id, offer_id) DO NOTHING",
            snapshot.id, snapshot.offerId, snapshot.status.name, snapshot.distanceM, snapshot.durationS, snapshot.profile, snapshot.mapVersion, snapshot.fallbackReason,
            json.writeValueAsString(snapshot.geometry.map { listOf(it.latitude, it.longitude) }),
            json.writeValueAsString(snapshot.steps.map { mapOf("seq" to it.seq, "maneuver" to it.maneuver.name, "distance_m" to it.distanceM, "duration_s" to it.durationS, "instruction" to it.instruction) }),
            snapshot.pickupStopName, java.sql.Timestamp.from(snapshot.computedAt),
        )
        read(j, ctx, snapshot.offerId)!!
    }

    override fun forOffer(ctx: RequestContext, offerId: UUID): WalkSnapshot? = ctxTx.run(ctx, readOnly = true) { j -> read(j, ctx, offerId) }

    private fun read(j: JdbcOperations, ctx: RequestContext, offerId: UUID): WalkSnapshot? = j.query(
        "SELECT w.id, w.status, w.distance_m, w.duration_s, w.profile, w.map_version, w.fallback_reason, w.geometry::text, w.steps::text, w.pickup_stop_name, w.computed_at " +
            "FROM taktovia.ride_offer_walk w JOIN taktovia.ride_offer o ON o.tenant_id = w.tenant_id AND o.id = w.offer_id WHERE w.offer_id = ? AND o.actor_ref = ?",
        { rs, _ ->
            WalkSnapshot(rs.getObject(1, UUID::class.java), offerId, WalkStatus.valueOf(rs.getString(2)), rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getString(7),
                json.readTree(rs.getString(8)).values().map { GeoPoint(it[1].asDouble(), it[0].asDouble()) },
                json.readTree(rs.getString(9)).values().map { WalkStep(it["seq"].asInt(), WalkManeuver.valueOf(it["maneuver"].asString()), it["distance_m"].asInt(), it["duration_s"].asInt(), it["instruction"].asString()) },
                rs.getString(10), rs.getTimestamp(11).toInstant())
        }, offerId, FourEyes.refOf(ctx).value,
    ).firstOrNull()
}
