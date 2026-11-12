package de.taktovia.platform.operation

import de.taktovia.platform.statemachine.OperationState
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Arten persistierter Operationen (OP-15): `HOLD`, `CONFIRM`, `CANCEL`, `RECONCILE` aus Kap. 02 §7.3 und die nicht reisebezogenen M1-Befehle.
 * Das Enum steht ebenso in `api/common.yaml` (Schema `Operation.kind`); die Spalte `operation.kind` bleibt Text, damit spätere Meilensteine Arten
 * ohne Migration ergänzen.
 */
object OperationKind {
    val ALL = setOf("HOLD", "CONFIRM", "CANCEL", "RECONCILE", "PUBLISH_CONFIG", "IMPACT_ANALYSIS", "RETENTION_RUN", "TEST_COMMAND", "LEDGER_EXPORT")
}

/** Planungsdefaults (Detailvertrag `operation.md`): Lease 60 s, Wiederabfrage-Hinweis 3 s, Backoff nach der Staffel des Outbox-Relays. */
data class OperationConfig(
    val lease: Duration = Duration.ofSeconds(60),
    /** `retry_after_seconds`, solange kein `next_attempt_at` gesetzt ist. */
    val defaultRetryAfterSeconds: Long = 3,
    /** Verzögerung des nächsten Abgleichs, wenn der Handler keine nennt. */
    val reconcileDelay: Duration = Duration.ofSeconds(5),
    val maxPerTenantRun: Int = 100,
)

/** Persistierter Stand für `GET /v1/operations/{operation_id}`. */
data class OperationView(
    val id: UUID, val kind: String, val status: OperationState, val journeyId: UUID?, val createdAt: Instant, val updatedAt: Instant,
    val resultCode: String?, val retryAfterSeconds: Long,
)

/** Die Lease dieses Workers ist verloren (anderer Fencing-Token, Endzustand oder Operation weg): er darf keinen Zustand mehr schreiben. */
class LeaseLostException(message: String = "Lease verloren") : RuntimeException(message)

/** Der Handler hat vor jeder Wirkung festgestellt, dass ein späterer Versuch nötig ist (z. B. Partner kurz nicht erreichbar): zurück nach QUEUED mit Backoff. */
class OperationRetryException(val resultCode: String, val delay: Duration? = null) : RuntimeException(resultCode)

/**
 * Ergebnis eines Handlerschritts. `Failed` bedeutet ein **bestimmtes** Fehlerergebnis; ein unbestimmtes ist [Unknown] und wird abgeglichen,
 * nie zu Erfolg oder Fehler verkürzt (Leitlinie 6).
 */
sealed interface OperationOutcome {
    data class Succeeded(val resultCode: String = "OK") : OperationOutcome
    data class Failed(val resultCode: String) : OperationOutcome
    /** Vor jeder Wirkung gescheitert: zurück nach QUEUED, nächster Versuch nach [delay]. */
    data class Retry(val resultCode: String, val delay: Duration) : OperationOutcome
    data class Unknown(val resultCode: String, val retryAfter: Duration? = null) : OperationOutcome
    data class ManualReview(val resultCode: String) : OperationOutcome
    /** Der Handler hat über [OperationContext.commit] bereits atomar mit seiner Wirkung abgeschlossen. */
    data object Completed : OperationOutcome
}

/**
 * Fachlicher Ausführer je Operationsart. [execute] läuft im Zustand RUNNING, [reconcile] im Zustand RECONCILING (Abgleich über den unveränderten
 * [OperationContext.operationKey]). Beide müssen mit Wiederholung rechnen: nach Prozessabbruch wird dieselbe Operation von einem anderen Worker
 * erneut aufgenommen; externe Wirkungen tragen deshalb den Operationsschlüssel als Idempotenzschlüssel, lokale Wirkungen laufen über [OperationContext.commit].
 */
interface OperationHandler {
    val kind: String
    fun execute(ctx: OperationContext): OperationOutcome
    fun reconcile(ctx: OperationContext): OperationOutcome = OperationOutcome.ManualReview("RECONCILE_NOT_SUPPORTED")
}
