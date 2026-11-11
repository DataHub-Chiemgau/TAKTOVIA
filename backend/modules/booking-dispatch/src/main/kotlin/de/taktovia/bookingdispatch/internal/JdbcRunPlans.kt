package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.PlanStop
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RevisionCause
import de.taktovia.bookingdispatch.api.RunPlan
import de.taktovia.bookingdispatch.api.RunPlanRejectedException
import de.taktovia.bookingdispatch.api.RunPlanValidator
import de.taktovia.bookingdispatch.api.RunPlans
import de.taktovia.bookingdispatch.api.RunState
import de.taktovia.bookingdispatch.api.RunView
import de.taktovia.bookingdispatch.api.StopMovement
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/**
 * Fahrtenplan auf PostgreSQL (WP-M2-16). Sperrreihenfolge wie bei Hold und Confirm (Spike (a)): zuerst die Zeile `vehicle_run`, danach die abhängigen Zeilen. Die Datenbank
 * sichert zusätzlich: ein Umlauf je Schicht, Revision nur +1, Stoppsequenz je Revision eindeutig, Planrevisionen unveränderlich.
 */
internal class JdbcRunPlans(private val ctxTx: ContextTransactions, private val outbox: Outbox, private val clock: Clock) : RunPlans {
    private val json = JsonMapper.builder().build()

    private class RunRow(val id: UUID, val vehicleId: UUID, val shiftId: UUID, val revision: Long, val state: RunState) {
        fun view() = RunView(id, vehicleId, shiftId, revision, state)
    }

    private fun load(j: JdbcOperations, runId: UUID, lock: Boolean): RunRow {
        val map = { rs: java.sql.ResultSet, _: Int -> RunRow(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getLong(4), RunState.valueOf(rs.getString(5))) }
        val rows = if (lock) j.query("SELECT id, vehicle_id, shift_id, revision, state FROM taktovia.vehicle_run WHERE id = ? FOR UPDATE", map, runId)
        else j.query("SELECT id, vehicle_id, shift_id, revision, state FROM taktovia.vehicle_run WHERE id = ?", map, runId)
        return rows.firstOrNull() ?: throw ProblemException.notFound()
    }

    override fun ensureRun(ctx: RequestContext, shiftId: UUID): RunView = ctxTx.run(ctx) { j ->
        val vehicleId = j.query("SELECT vehicle_id FROM taktovia.staff_shift WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, shiftId).firstOrNull() ?: throw ProblemException.notFound()
        // Genau ein Umlauf je Schicht auch bei parallelen Erstanfragen: der UNIQUE-Schlüssel (Mandant, Schicht) entscheidet, der Verlierer wartet auf das Commit und liest den Gewinner.
        j.update(
            "INSERT INTO taktovia.vehicle_run(tenant_id, id, vehicle_id, shift_id, revision, state) VALUES (taktovia.current_tenant(), ?, ?, ?, 1, 'PLANNED') ON CONFLICT (tenant_id, shift_id) DO NOTHING",
            UUID.randomUUID(), vehicleId, shiftId,
        )
        j.query(
            "SELECT id, vehicle_id, shift_id, revision, state FROM taktovia.vehicle_run WHERE shift_id = ?",
            { rs, _ -> RunRow(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getLong(4), RunState.valueOf(rs.getString(5))).view() }, shiftId,
        ).first()
    }

    override fun run(ctx: RequestContext, runId: UUID): RunView = ctxTx.run(ctx, readOnly = true) { j -> load(j, runId, false).view() }

    override fun transition(ctx: RequestContext, runId: UUID, to: RunState): RunView = ctxTx.run(ctx) { j ->
        val row = load(j, runId, true)
        if (!row.state.canGoTo(to)) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Übergang ${row.state} nach $to ist für den Fahrtenplan nicht erlaubt.")
        j.update("UPDATE taktovia.vehicle_run SET state = ? WHERE id = ?", to.name, runId)
        row.view().copy(state = to)
    }

    override fun currentPlan(ctx: RequestContext, runId: UUID): RunPlan = ctxTx.run(ctx, readOnly = true) { j -> readPlan(j, runId, load(j, runId, false).revision) }

    override fun plan(ctx: RequestContext, runId: UUID, revision: Long): RunPlan = ctxTx.run(ctx, readOnly = true) { j ->
        val current = load(j, runId, false).revision
        if (revision < 1 || revision > current) throw ProblemException.notFound()
        readPlan(j, runId, revision)
    }

    private fun readPlan(j: JdbcOperations, runId: UUID, revision: Long): RunPlan = RunPlan(runId, revision, j.query(
        "SELECT seq, stop_revision_id, planned_at, boardings::text, alightings::text, leg_duration_sec, leg_distance_m FROM taktovia.route_stop WHERE run_id = ? AND run_revision = ? ORDER BY seq",
        { rs, _ ->
            PlanStop(
                rs.getInt(1), rs.getObject(2, UUID::class.java), rs.getTimestamp(3).toInstant(), movements(rs.getString(4)), movements(rs.getString(5)),
                rs.getObject(6) as Int?, rs.getObject(7) as Int?,
            )
        }, runId, revision,
    ))

    private fun movements(text: String): List<StopMovement> = json.readTree(text).let { arr -> (0 until arr.size()).map { i -> arr.get(i).let { StopMovement(UUID.fromString(it.get("booking_id").asString()), ResourceVector.parse(it.get("vector"))) } } }

    private fun movementsJson(list: List<StopMovement>): String =
        json.writeValueAsString(list.map { linkedMapOf("booking_id" to it.bookingId.toString(), "vector" to it.vector.toMap()) })

    override fun appendRevision(ctx: RequestContext, runId: UUID, expectedRevision: Long, stops: List<PlanStop>, cause: RevisionCause, affectedBookingIds: List<UUID>): RunPlan =
        ctxTx.run(ctx) { j -> appendIn(j, ctx, runId, expectedRevision, stops, cause, affectedBookingIds) }

    /** Plan der Revision in der laufenden Transaktion des Aufrufers (Hold unter dem Lock des Umlaufs, WP-M2-19). */
    internal fun planIn(j: JdbcOperations, runId: UUID, revision: Long): RunPlan = readPlan(j, runId, revision)

    /** Wie [appendRevision], aber in der Transaktion des Aufrufers; der Zeilenlock des Umlaufs wird (erneut) genommen. */
    internal fun appendIn(j: JdbcOperations, ctx: RequestContext, runId: UUID, expectedRevision: Long, stops: List<PlanStop>, cause: RevisionCause, affectedBookingIds: List<UUID>): RunPlan {
        val run = load(j, runId, true)
        if (run.revision != expectedRevision) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Fahrtenplan wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        if (run.state !in RunState.PLAN_CHANGEABLE) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Im Zustand ${run.state} ist keine Planänderung möglich.")
        val capacity = ResourceVector.parse(
            j.queryForObject("SELECT capacity_config::text FROM taktovia.vehicle WHERE id = ?", String::class.java, run.vehicleId)!!, "/vehicle/capacity_config",
        )
        // Verletzendes Ergebnis wird verworfen: die Prüfung läuft vor jedem Schreiben, die Ausnahme rollt die Transaktion zurück
        val violations = RunPlanValidator.validate(capacity, stops)
        if (violations.isNotEmpty()) throw RunPlanRejectedException(violations)
        val ordered = stops.sortedBy { it.seq }
        ordered.forEach { s ->
            val known = j.queryForObject("SELECT count(*) FROM taktovia.stop_revision WHERE id = ?", Long::class.java, s.stopRevisionId)!!
            if (known == 0L) throw ProblemException.invalid(Violation("/stops/${s.seq}/stop_revision_id", ViolationCode.NOT_IN_ENUM))
            if ((s.legDurationSec == null) != (s.legDistanceM == null)) throw ProblemException.invalid(Violation("/stops/${s.seq}/leg_duration_sec", ViolationCode.REQUIRED))
        }
        val next = run.revision + 1
        // Revision zuerst (Datenbank prüft +1), dann die Stopps der neuen Revision
        j.update("UPDATE taktovia.vehicle_run SET revision = ? WHERE id = ?", next, runId)
        ordered.forEach { s ->
            j.update(
                "INSERT INTO taktovia.route_stop(tenant_id, id, run_id, run_revision, seq, stop_revision_id, planned_at, boardings, alightings, leg_duration_sec, leg_distance_m) " +
                    "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)",
                UUID.randomUUID(), runId, next, s.seq, s.stopRevisionId, Timestamp.from(s.plannedAt), movementsJson(s.boardings), movementsJson(s.alightings), s.legDurationSec, s.legDistanceM,
            )
        }
        outbox.append(j, OutboxEvent(runId, next, "dispatch.run_revised", linkedMapOf(
            "run_id" to runId.toString(), "previous_revision" to run.revision, "revision" to next, "cause" to cause.name,
            "affected_booking_ids" to affectedBookingIds.map { it.toString() },
        ), clock.instant(), correlationId = ctx.correlationId))
        return RunPlan(runId, next, ordered)
    }
}
