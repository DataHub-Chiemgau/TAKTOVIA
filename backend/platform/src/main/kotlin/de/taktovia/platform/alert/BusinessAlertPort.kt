package de.taktovia.platform.alert

import org.springframework.jdbc.core.JdbcOperations
import java.util.UUID

/** Priorität eines Fachalarms (Tabelle `business_alert`, WP-M3-52). */
enum class AlertPriority { CRITICAL, HIGH, NORMAL }

/**
 * Auslösung eines Fachalarms durch ein Fachmodul (WP-M3-52). Der Alarm trägt **nur Codes und Kennungen**: weder Freitext noch Fahrgastdaten noch Diagnosen (`reasonCode` ist ein Code
 * `^[A-Z][A-Z0-9_]{1,59}$`, `refType` ein Typname). Dedupe: ereignisbasiert über `(Regel [kind], Quellereignis [sourceEventId], Empfänger [recipientRole])`, in jedem Zustand; bedingungsbasiert
 * (technische Alarme, ohne Quellereignis) über [dedupeKey]: höchstens ein offener Alarm je Schlüssel, nach Behebung entsteht bei erneutem Auftreten ein neuer.
 */
data class BusinessAlertRequest(
    val kind: String,
    val sourceEventId: UUID? = null,
    val dedupeKey: String? = null,
    val vehicleId: UUID? = null,
    val serviceId: UUID? = null,
    val refType: String? = null,
    val refId: UUID? = null,
    val reasonCode: String? = null,
    /** `null` = die Empfängerrolle der Regel (Alarmrichtlinie) entscheidet. */
    val recipientRole: String? = null,
) {
    init {
        require(Regex("^[A-Z][A-Z0-9_]{1,59}$").matches(kind)) { "ungültige Alarmart" }
        require(sourceEventId != null || dedupeKey != null) { "Dedupe braucht ein Quellereignis oder einen Bedingungsschlüssel" }
        require(reasonCode == null || Regex("^[A-Z][A-Z0-9_]{1,59}$").matches(reasonCode)) { "reasonCode ist ein Code, kein Text" }
        require((refType == null) == (refId == null)) { "refType und refId nur gemeinsam" }
        require(refType == null || Regex("^[a-z][a-z_]{1,39}$").matches(refType)) { "ungültiger Bezugstyp" }
        require(recipientRole == null || Regex("^[a-z][a-z_]{1,39}$").matches(recipientRole)) { "ungültige Empfängerrolle" }
        require(dedupeKey == null || dedupeKey.length in 1..200) { "Dedupe-Schlüssel 1..200 Zeichen" }
    }
}

/** [created] = falsch: der Alarm bestand bereits (Wiederzustellung, anhaltender Fehler); [alertId] ist dann der bestehende. */
data class BusinessAlertResult(val alertId: UUID, val created: Boolean, val priority: AlertPriority, val policyRevisionNo: Int, val escalationLevel: Int)

/**
 * Port der Alarm-Grundlage (WP-M3-52): Fachmodule lösen Alarme **in ihrer Transaktion** aus (wie bei der Outbox: Ursache und Alarm gemeinsam oder gar nicht); die Tabellen gehören `fleet-safety`,
 * die Umsetzung liegt dort. Die Regel (Priorität, Empfänger, Eskalation) bestimmt die zum Zeitpunkt gültige `alert_policy_revision`; der Alarm nennt diese Revision.
 */
interface BusinessAlertPort {
    fun raise(jdbc: JdbcOperations, request: BusinessAlertRequest): BusinessAlertResult

    /** Schließt den offenen bedingungsbasierten Alarm mit [dedupeKey] nach Behebung (Zustand `RESOLVED`); liefert die Zahl geschlossener Alarme (0 oder 1). */
    fun resolve(jdbc: JdbcOperations, dedupeKey: String): Int
}
