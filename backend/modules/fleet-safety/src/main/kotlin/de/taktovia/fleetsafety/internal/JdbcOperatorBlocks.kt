package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.BlockSource
import de.taktovia.fleetsafety.api.OperationalBlockInput
import de.taktovia.fleetsafety.api.OperationalBlockResult
import de.taktovia.fleetsafety.api.OperationalLiftInput
import de.taktovia.fleetsafety.api.OperatorBlocks
import de.taktovia.fleetsafety.api.SetBlock
import de.taktovia.platform.authz.ServiceScopeAuthorizer
import de.taktovia.platform.authz.ServiceScoped
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/**
 * Operative Fahrzeugsperre durch die Leitstelle (WP-M3-41, siehe [OperatorBlocks]). Die Fachlogik (Sperrzeile, `safety_revision`, Widerruf der Freigaben, Audit, Outbox, Aufhebungsregeln) liegt im
 * Sperr- und Freigabekern [JdbcReadiness]; dieser Dienst fügt Sichtbarkeit (Dienstscope des Fahrzeugpools), `If-Match` unter der Fahrzeugzeilensperre und die Revision der Leitstellensicht
 * (`vehicle.master_revision`) hinzu. Alle Schritte laufen in einer Mandantentransaktion unter der exklusiven Fahrzeugzeilensperre (Reihenfolge wie der Hold-Commit der Disposition).
 */
@ServiceScoped
internal class JdbcOperatorBlocks(private val ctxTx: ContextTransactions, private val readiness: JdbcReadiness, private val clock: Clock) : OperatorBlocks {

    override fun create(ctx: RequestContext, vehicleId: UUID, expectedVehicleRevision: Long, input: OperationalBlockInput): OperationalBlockResult {
        readiness.requireOperator(ctx)
        OperationalBlockInput.parse(mapOf("reason_code" to input.reason.name, "comment" to input.comment))   // Katalog und Pflichtbegründung gelten auch für Aufrufer im Prozess
        return ctxTx.run(ctx) { j ->
            requireVisible(j, ctx, vehicleId, activeOnly = true)
            readiness.lockedSafetyRevision(j, vehicleId) ?: throw ProblemException.notFound()
            if (vehicleRevision(j, vehicleId) != expectedVehicleRevision) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Das Fahrzeug wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
            val out = readiness.setBlockIn(j, readiness.actorOf(ctx), ctx.correlationId, SetBlock(vehicleId, BlockSource.OPERATIONS, input.reason, comment = input.comment))
            val revision = if (out.created) bumpVehicleRevision(j, vehicleId) else vehicleRevision(j, vehicleId)
            OperationalBlockResult(out.block, out.created, out.safetyRevision, revision, stillBlocked = true)
        }
    }

    override fun lift(ctx: RequestContext, vehicleId: UUID, blockId: UUID, expectedBlockRevision: Long, input: OperationalLiftInput): OperationalBlockResult {
        readiness.requireOperator(ctx)
        try {
            return ctxTx.run(ctx) { j ->
                requireVisible(j, ctx, vehicleId, activeOnly = false)
                val out = readiness.liftIn(j, readiness.actorOf(ctx), ctx.correlationId, vehicleId, blockId, null, expectedBlockRevision, input.comment)
                OperationalBlockResult(out.block, created = false, safetyRevision = out.safetyRevision, vehicleRevision = bumpVehicleRevision(j, vehicleId), stillBlocked = out.stillBlocked)
            }
        } catch (e: ProblemException) {
            // AT-51: der Versuch, eine Sicherheitssperre ohne Werkstattnachweis zu entfernen, wird verhindert und als Fehlversuch protokolliert (eigene Transaktion)
            if (e.code == ErrorCode.INVALID_STATE_TRANSITION) readiness.recordRefusedLift(ctx, vehicleId)
            throw e
        }
    }

    /** Fahrzeug im Mandanten (RLS) und im aktuellen Dienstscope des Aufrufers (Fahrzeugpool, wie `OperatorVehicles`); sonst 404 (kein Hinweis auf die Existenz). */
    private fun requireVisible(j: JdbcOperations, ctx: RequestContext, vehicleId: UUID, activeOnly: Boolean) {
        val scope = ServiceScopeAuthorizer.currentServices(j, ctx)
        val ts = Timestamp.from(clock.instant())
        val visible = j.query(
            "SELECT 1 FROM taktovia.vehicle v WHERE v.id = ? AND (v.active OR NOT CAST(? AS boolean)) " +
                "AND (EXISTS (SELECT 1 FROM taktovia.vehicle_pool_assignment a WHERE a.vehicle_id = v.id AND a.valid_from <= ? AND (a.valid_to IS NULL OR a.valid_to > ?) AND a.service_id = ANY(CAST(? AS uuid[]))) " +
                "OR NOT EXISTS (SELECT 1 FROM taktovia.vehicle_pool_assignment a WHERE a.vehicle_id = v.id AND a.valid_from <= ? AND (a.valid_to IS NULL OR a.valid_to > ?)))",
            { _, _ -> 1 }, vehicleId, activeOnly, ts, ts, scope.joinToString(",", "{", "}"), ts, ts)
        if (visible.isEmpty()) throw ProblemException.notFound()
    }

    private fun vehicleRevision(j: JdbcOperations, vehicleId: UUID): Long = j.queryForObject("SELECT master_revision FROM taktovia.vehicle WHERE id = ?", Long::class.java, vehicleId)!!

    private fun bumpVehicleRevision(j: JdbcOperations, vehicleId: UUID): Long =
        j.queryForObject("UPDATE taktovia.vehicle SET master_revision = master_revision + 1, updated_at = clock_timestamp() WHERE id = ? RETURNING master_revision", Long::class.java, vehicleId)!!
}
