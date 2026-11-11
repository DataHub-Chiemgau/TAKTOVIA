package de.taktovia.platform.operation

import de.taktovia.platform.statemachine.OperationState
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil

/**
 * Anlegen und Lesen persistierter Operationen (WP-M1-15). `enqueue` läuft in der Fachtransaktion des Befehls (Mandant der Transaktion), damit
 * Befehl und Operation gemeinsam wirksam werden oder gar nicht; die Antwort ist dann `202 OperationAccepted` mit `Location` und `Retry-After`.
 * Lesen läuft unter RLS: eine fremde Operation ist nicht auffindbar.
 */
class OperationService(private val clock: Clock = Clock.systemUTC(), private val config: OperationConfig = OperationConfig()) {
    fun enqueue(jdbc: JdbcOperations, kind: String, deadlineAt: Instant, journeyId: UUID? = null, id: UUID = UUID.randomUUID()): UUID {
        require(kind in OperationKind.ALL) { "unbekannte Operationsart" }
        val now = Timestamp.from(clock.instant())
        jdbc.update("INSERT INTO taktovia.operation(tenant_id,id,journey_id,kind,status,deadline_at,created_at,updated_at) VALUES (taktovia.current_tenant(),?,?,?, 'QUEUED',?,?,?)",
            id, journeyId, kind, Timestamp.from(deadlineAt), now, now)
        return id
    }

    fun find(jdbc: JdbcOperations, id: UUID): OperationView? =
        jdbc.query("SELECT id, kind, status, journey_id, created_at, updated_at, result_code, next_attempt_at FROM taktovia.operation WHERE id = ?", { rs, _ ->
            val status = OperationState.valueOf(rs.getString("status"))
            OperationView(rs.getObject("id", UUID::class.java), rs.getString("kind"), status, rs.getObject("journey_id", UUID::class.java),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), rs.getString("result_code"),
                retryAfter(status, rs.getTimestamp("next_attempt_at")?.toInstant()))
        }, id).firstOrNull()

    /** Wiederabfrage-Hinweis 1 bis 300 s: bis zum nächsten Versuch, sonst der Default; bei Endzuständen 1 (kein weiteres Abfragen nötig, der Status genügt). */
    private fun retryAfter(status: OperationState, next: Instant?): Long {
        if (status == OperationState.SUCCEEDED || status == OperationState.FAILED || status == OperationState.MANUAL_REVIEW) return 1
        val secs = if (next == null) config.defaultRetryAfterSeconds else ceil((next.toEpochMilli() - clock.instant().toEpochMilli()) / 1000.0).toLong()
        return secs.coerceIn(1, 300)
    }
}
