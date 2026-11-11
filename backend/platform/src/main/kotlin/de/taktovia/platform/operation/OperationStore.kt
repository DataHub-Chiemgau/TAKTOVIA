package de.taktovia.platform.operation

import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.statemachine.OperationState
import de.taktovia.platform.statemachine.StateMachines
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Von einem Worker beanspruchte Operation (Lease gehalten, Fencing-Token vergeben). */
data class ClaimedOperation(
    val tenant: de.taktovia.platform.tenant.TenantId, val id: UUID, val kind: String, val journeyId: UUID?, val status: OperationState,
    val previousStatus: OperationState, val fencingToken: Long, val attempt: Int, val deadlineAt: Instant, val revision: Long,
) {
    /** Deterministischer Operationsschlüssel (Idempotenzschlüssel externer Wirkungen und Abgleichsreferenz); bleibt über alle Versuche und Worker gleich. */
    val operationKey: String get() = "op-$tenant-$id"
}

/**
 * Persistenz der Operation mit Fencing: **jeder** Schreibzugriff eines Workers trägt den Fencing-Token seiner Lease und wird mit `FOR UPDATE` an
 * die Zeile gebunden; stimmt der Token nicht mehr (Übernahme durch einen anderen Worker) oder ist die Operation final, schreibt er nichts
 * ([LeaseLostException]). Übergänge werden vor dem Schreiben gegen die Tabelle des Automaten `operation` geprüft (zusätzlich zum Datenbanktrigger) und
 * erzeugen je Zustandswechsel genau ein Outbox-Ereignis `operation.state_changed` zur neuen Revision. Die Revision steigt nur bei Zustandswechseln.
 */
class OperationStore(private val outbox: Outbox, private val clock: Clock) {
    private class Row(val status: OperationState, val revision: Long, val token: Long)

    /** Beansprucht die nächste fällige Operation des Mandanten der Transaktion (QUEUED/RECONCILING fällig, RUNNING mit abgelaufener Lease). */
    fun claimNext(jdbc: JdbcOperations, tenant: de.taktovia.platform.tenant.TenantId, now: Instant, leaseUntil: Instant, worker: String): ClaimedOperation? {
        val ts = Timestamp.from(now)
        val c = jdbc.query(
            """WITH c AS (SELECT id, status AS prev FROM taktovia.operation
                           WHERE (status = 'QUEUED' AND (next_attempt_at IS NULL OR next_attempt_at <= ?))
                              OR (status = 'RECONCILING' AND (next_attempt_at IS NULL OR next_attempt_at <= ?) AND (lease_until IS NULL OR lease_until <= ?))
                              OR (status = 'RUNNING' AND (lease_until IS NULL OR lease_until <= ?))
                           ORDER BY COALESCE(next_attempt_at, created_at), created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)
               UPDATE taktovia.operation o SET fencing_token = o.fencing_token + 1, lease_until = ?, lease_owner = ?, attempts = o.attempts + 1, next_attempt_at = NULL,
                      status = CASE WHEN c.prev = 'QUEUED' THEN 'RUNNING' ELSE o.status END,
                      revision = CASE WHEN c.prev = 'QUEUED' THEN o.revision + 1 ELSE o.revision END
               FROM c WHERE o.id = c.id AND o.tenant_id = taktovia.current_tenant()
               RETURNING o.id, o.kind, o.journey_id, o.status, c.prev, o.fencing_token, o.attempts, o.deadline_at, o.revision""",
            { rs, _ ->
                ClaimedOperation(tenant, rs.getObject(1, UUID::class.java), rs.getString(2), rs.getObject(3, UUID::class.java), OperationState.valueOf(rs.getString(4)),
                    OperationState.valueOf(rs.getString(5)), rs.getLong(6), rs.getInt(7), rs.getTimestamp(8).toInstant(), rs.getLong(9))
            }, ts, ts, ts, ts, Timestamp.from(leaseUntil), worker).firstOrNull() ?: return null
        if (c.previousStatus == OperationState.QUEUED) event(jdbc, c.id, OperationState.QUEUED, OperationState.RUNNING, c.revision, now)
        return c
    }

    /** Verlängert die Lease; [LeaseLostException], wenn der Token nicht mehr gilt. */
    fun renew(jdbc: JdbcOperations, id: UUID, token: Long, leaseUntil: Instant) {
        val n = jdbc.update("UPDATE taktovia.operation SET lease_until = ? WHERE tenant_id = taktovia.current_tenant() AND id = ? AND fencing_token = ? AND status IN ('RUNNING', 'RECONCILING')",
            Timestamp.from(leaseUntil), id, token)
        if (n != 1) throw LeaseLostException()
    }

    /**
     * Gefenceter Zustandswechsel (oder, bei gleichem Zustand, Terminierung des nächsten Abgleichs). Gibt die Lease frei. [LeaseLostException], wenn der
     * Token nicht mehr gilt; [ProblemException] 409 `INVALID_STATE_TRANSITION` bei nicht erlaubtem Übergang. Läuft in der Transaktion des Aufrufers,
     * damit Wirkung und Abschluss gemeinsam wirksam werden.
     */
    fun transition(jdbc: JdbcOperations, id: UUID, token: Long, to: OperationState, resultCode: String?, nextAttemptAt: Instant?) {
        val row = jdbc.query("SELECT status, revision, fencing_token FROM taktovia.operation WHERE tenant_id = taktovia.current_tenant() AND id = ? FOR UPDATE",
            { rs, _ -> Row(OperationState.valueOf(rs.getString(1)), rs.getLong(2), rs.getLong(3)) }, id).firstOrNull() ?: throw LeaseLostException("Operation nicht vorhanden")
        if (row.token != token) throw LeaseLostException()
        if (row.status == OperationState.SUCCEEDED || row.status == OperationState.FAILED) {
            throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Operation ist final.")
        }
        val change = row.status != to
        if (change && !StateMachines.OPERATION.allowed(row.status, to)) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Der Übergang von ${row.status.name} nach ${to.name} ist nicht erlaubt.")
        val newRevision = if (change) row.revision + 1 else row.revision
        jdbc.update("UPDATE taktovia.operation SET status = ?, revision = ?, result_code = COALESCE(?, result_code), next_attempt_at = ?, lease_until = NULL, lease_owner = NULL " +
            "WHERE tenant_id = taktovia.current_tenant() AND id = ? AND fencing_token = ?", to.name, newRevision, resultCode, nextAttemptAt?.let { Timestamp.from(it) }, id, token)
        if (change) event(jdbc, id, row.status, to, newRevision, clock.instant())
    }

    private fun event(jdbc: JdbcOperations, id: UUID, from: OperationState, to: OperationState, revision: Long, at: Instant) {
        outbox.append(jdbc, OutboxEvent(id, revision, StateMachines.OPERATION.eventType, mapOf(
            "machine" to "operation", "machine_version" to StateMachines.OPERATION.version, "from" to from.name, "to" to to.name, "previous_revision" to revision - 1, "revision" to revision), at))
    }
}
