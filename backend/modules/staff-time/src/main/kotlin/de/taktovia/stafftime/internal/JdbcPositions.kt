package de.taktovia.stafftime.internal

import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.stafftime.api.LatestPosition
import de.taktovia.stafftime.api.PositionBatch
import de.taktovia.stafftime.api.PositionRejection
import de.taktovia.stafftime.api.PositionResult
import de.taktovia.stafftime.api.Positions
import de.taktovia.stafftime.api.RejectedSample
import de.taktovia.stafftime.api.StafftimeSettings
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Positionserfassung (WP-M3-34). Eine Transaktion je Meldung: Schicht des Fahrers unter `FOR SHARE` (der Zustand ändert sich während der Annahme nicht), Gerät prüfen, Schicht- und
 * Fahrzeugbindung, Messzeitfenster je Punkt, Einfügen mit `ON CONFLICT DO NOTHING` auf `(tenant_id, shift_id, measured_at)` (erneute Sendung ist idempotent, auch parallel).
 * Die Empfangszeit setzt allein der Server aus seiner Uhr; die Gerätezeit steht getrennt in `measured_at`.
 */
internal class JdbcPositions(private val ctxTx: ContextTransactions, private val clock: Clock, private val settings: StafftimeSettings) : Positions {

    override fun record(ctx: RequestContext, batch: PositionBatch): PositionResult {
        if (ctx.actor.type != ActorType.HUMAN || ctx.actor.sessionId == null || "driver" !in ctx.roles || !ctx.hasScope(SCOPE_RECORD)) throw ProblemException(ErrorCode.FORBIDDEN)
        return ctxTx.run(ctx) { j -> doRecord(j, ctx, batch) }
    }

    private fun doRecord(j: JdbcOperations, ctx: RequestContext, batch: PositionBatch): PositionResult {
        val now = clock.instant()
        val s = j.query(
            "SELECT s.vehicle_id, s.state, s.started_at, s.ended_at FROM taktovia.staff_shift s WHERE s.id = ? AND $OWN_SHIFT FOR SHARE OF s",
            { rs, _ -> Shift(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getTimestamp(3)?.toInstant(), rs.getTimestamp(4)?.toInstant()) },
            batch.shiftId, *ShiftOwnership.args(ctx.actor.issuer, ctx.actor.subject),
        ).firstOrNull() ?: throw ProblemException.notFound()
        if (s.vehicleId != batch.vehicleId) throw ProblemException.notFound()          // Position für ein fremdes Fahrzeug: abgewiesen, ohne Auskunft
        DeviceGuard.requireBound(j, ctx, batch.deviceId)
        // Außerhalb eines angetretenen Dienstes werden keine Positionen angenommen: nur STARTED, oder ENDED für noch nachzuliefernde Punkte aus dem Dienst
        if ((s.state != "STARTED" && s.state != "ENDED") || s.startedAt == null)
            throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Positionen werden nur innerhalb eines angetretenen Dienstes angenommen.")
        if (settings.positionRequiresRetentionRule && j.queryForObject("SELECT count(*) FROM taktovia.retention_rule WHERE data_class = 'LK_STANDORT' AND status = 'APPROVED'", Int::class.java) == 0)
            throw ProblemException(ErrorCode.CONFLICT, "Die Positionserfassung ist nicht aktiviert: für Standortdaten ist keine freigegebene Aufbewahrungsfrist hinterlegt.")
        var accepted = 0
        val rejected = mutableListOf<RejectedSample>()
        val limit = now.plus(settings.positionFutureTolerance)
        batch.samples.forEachIndexed { i, p ->
            when {
                p.measuredAt.isAfter(limit) -> rejected += RejectedSample(i, PositionRejection.FUTURE_TIME)
                p.measuredAt.isBefore(s.startedAt) || (s.endedAt != null && p.measuredAt.isAfter(s.endedAt)) -> rejected += RejectedSample(i, PositionRejection.OUTSIDE_SERVICE)
                else -> {
                    j.update(
                        "INSERT INTO taktovia.position_sample (tenant_id, id, vehicle_id, shift_id, position, accuracy_m, measured_at, received_at, created_by) " +
                            "VALUES (taktovia.current_tenant(), ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?, ?, ?) ON CONFLICT (tenant_id, shift_id, measured_at) DO NOTHING",
                        UUID.randomUUID(), s.vehicleId, batch.shiftId, p.longitude, p.latitude, p.accuracyMeters, Timestamp.from(p.measuredAt), Timestamp.from(now), "user:" + ctx.actor.subject,
                    )
                    accepted++
                }
            }
        }
        return PositionResult(accepted, rejected, now)
    }

    override fun latest(ctx: RequestContext, vehicleIds: Collection<UUID>): Map<UUID, LatestPosition> {
        if (ctx.actor.type != ActorType.HUMAN || !ctx.hasScope(SCOPE_READ)) throw ProblemException(ErrorCode.FORBIDDEN)
        if (vehicleIds.isEmpty()) return emptyMap()
        val now = clock.instant()
        return ctxTx.run(ctx, readOnly = true) { j ->
            // jüngste MESSzeit je Fahrzeug (nicht die jüngste Empfangszeit): eine verspätet eintreffende ältere Meldung verdrängt die neuere Position nie
            j.query(
                "SELECT DISTINCT ON (p.vehicle_id) p.vehicle_id, ST_X(p.position::geometry), ST_Y(p.position::geometry), p.accuracy_m, p.measured_at, p.received_at FROM taktovia.position_sample p " +
                    "WHERE p.vehicle_id = ANY (CAST(? AS uuid[])) ORDER BY p.vehicle_id, p.measured_at DESC, p.received_at DESC",
                { rs, _ ->
                    val measured = rs.getTimestamp(5).toInstant()
                    val age = maxOf(0L, Duration.between(measured, now).seconds)
                    LatestPosition(rs.getObject(1, UUID::class.java), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), measured, rs.getTimestamp(6).toInstant(), age, age > settings.positionStaleAfter.seconds)
                },
                vehicleIds.joinToString(",", "{", "}"),
            ).associateBy { it.vehicleId }
        }
    }

    private class Shift(val vehicleId: UUID, val state: String, val startedAt: Instant?, val endedAt: Instant?)

    private companion object {
        /** Eigene Schicht (siehe [ShiftOwnership]); als `const val` in dieser Datei, damit die SQL-Prüfung sie als Konstante erkennt. */
        const val OWN_SHIFT = ShiftOwnership.PREDICATE
        const val SCOPE_RECORD = "driver:positions"
        const val SCOPE_READ = "operator:read"
    }
}
