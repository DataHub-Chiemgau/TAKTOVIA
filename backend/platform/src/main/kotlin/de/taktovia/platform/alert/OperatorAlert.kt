package de.taktovia.platform.alert

import java.time.Instant
import java.util.UUID

enum class Severity { INFO, WARNING, CRITICAL }

/**
 * Betreiberwarnung (WP-M1-47). Enthält nur technische Angaben: Quelle, Art, Schweregrad, Korrelations-ID, den Mandanten als opake ID und
 * wenige zugelassene technische Attribute. Personen-, Kontakt- und Inhaltsdaten sind nicht darstellbar (siehe [OperatorWarningPolicy]).
 * Schema: docs/umsetzungsplan/vertraege/schemas/operator-warning.v1.schema.json
 */
data class OperatorWarning(
    val source: String,
    val kind: String,
    val severity: Severity,
    val correlationId: UUID,
    val tenantRef: UUID? = null,
    val attributes: Map<String, String> = emptyMap(),
)

class PersonalDataInWarningException(message: String) : IllegalArgumentException(message)

/** Zusammengefasster offener Alarm: gleichartige Warnungen (gleiche Quelle und Art) erzeugen genau einen. */
data class OpenAlert(
    val fingerprint: String, val source: String, val kind: String, val severity: Severity,
    val openedAt: Instant, val lastSeenAt: Instant, val count: Long, val lastCorrelationId: UUID,
)

/** Mitteilung an den Empfängerkanal. `triggeredAt` ist der Auslösezeitpunkt, die Zustellung wird beim Zustellen protokolliert. */
data class AlertNotification(
    val dedupKey: String, val source: String, val kind: String, val severity: Severity, val correlationId: UUID,
    val tenantRef: UUID?, val triggeredAt: Instant, val attributes: Map<String, String>, val reason: String,
)

/** Port für Betreiberwarnungen: Module melden, der Betrieb erhält. */
interface OperatorAlertPort {
    fun report(warning: OperatorWarning)
}

/** Empfängerkanal je Umgebung (genau einer konfiguriert). Wirft bei Nichterreichbarkeit; der Dispatcher wiederholt. */
fun interface AlertChannel {
    fun deliver(notification: AlertNotification)
}

/** Zeilenausgabe der strukturierten Logzeile (Standard: SLF4J; in Tests aufzeichenbar). */
fun interface OperatorLog {
    fun line(json: String)
}
