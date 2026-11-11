package de.taktovia.platform.idempotency

import de.taktovia.platform.tenant.TenantId
import java.time.Duration
import java.util.UUID

/**
 * Scope eines idempotenten Befehls (WP-M1-13): `(auth_principal, tenant_context, operation_name, Idempotency-Key)`.
 * [principalRef] ist der geprüfte Akteur (Aussteller und Subject aus dem verifizierten Token, nie aus dem Request), [commandScope] der
 * Operationsname des API-Vertrags (`operationId`), [fingerprint] der kanonische Request-Fingerprint ([RequestFingerprint]).
 */
data class IdempotentRequest(val tenant: TenantId, val principalRef: String, val commandScope: String, val key: String, val fingerprint: String) {
    override fun toString() = "IdempotentRequest(tenant=$tenant, scope=$commandScope)"   // weder Akteur noch Schlüssel in Logs
}

/** Gespeichertes bzw. zu speicherndes HTTP-Ergebnis eines angenommenen Befehls. [body] ist im Regelfall JSON. */
data class StoredResponse(val status: Int, val headers: Map<String, String>, val body: String?, val operationId: UUID? = null)

/** Ergebnis von [IdempotencyRegistry.begin]. */
sealed interface Begin {
    /** Dieser Aufruf führt den Befehl aus; der Eintrag steht auf IN_PROGRESS mit Lease. */
    data object Execute : Begin
    data class Replay(val response: StoredResponse) : Begin
    /** Gleicher Schlüssel mit anderem Fingerprint. */
    data object Conflict : Begin
    /** Erstverarbeitung läuft (Lease gültig). */
    data object InProgress : Begin
}

sealed interface IdempotencyResult {
    /** Erstausführung. [stored] ist falsch, wenn das Ergebnis nicht angenommen war (kein 2xx) und der Schlüssel freigegeben wurde. */
    data class Executed(val response: StoredResponse, val stored: Boolean) : IdempotencyResult
    data class Replayed(val response: StoredResponse) : IdempotencyResult
    data object Conflict : IdempotencyResult
    data class InProgress(val retryAfterSeconds: Long) : IdempotencyResult
}

/**
 * Planungsdefaults (Kap. 05 §5.1; OP-20): Antwortcache 72 h (Entwurfswert des Pflichtenhefts), Lease der Erstverarbeitung 120 s (größer als jedes
 * Anfrage-Zeitlimit; nach Ablauf gilt die Verarbeitung als abgebrochen), Wartezeit eines parallelen Retransmits auf die Erstverarbeitung 10 s,
 * danach `409 OPERATION_IN_PROGRESS`.
 */
data class IdempotencyConfig(
    val responseTtl: Duration = Duration.ofHours(72),
    val lease: Duration = Duration.ofSeconds(120),
    val inProgressWait: Duration = Duration.ofSeconds(10),
    val poll: Duration = Duration.ofMillis(25),
    val retryAfterSeconds: Long = 1,
)

object IdempotencyKeys {
    val PATTERN = Regex("[A-Za-z0-9._:-]{16,128}")
    fun valid(key: String) = PATTERN.matches(key)
}
