package de.taktovia.fleetsafety.api

import de.taktovia.platform.alert.AlertPriority
import de.taktovia.platform.alert.BusinessAlertPort
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Alarmarten der P1-Grundlage (WP-M3-52, Katalog im Detailvertrag `m3-live-alarm.md`): kritische Sperre, Technikereignis, Überfälligkeit, Fahrzeugausfall, ausbleibende Planbestätigung (WP-M3-27).
 * Technische Betriebsalarme (WP-M5-04) tragen das Präfix `TECH_`.
 */
object AlertKinds {
    const val VEHICLE_BLOCK_CRITICAL = "VEHICLE_BLOCK_CRITICAL"
    const val TECHNICAL_EVENT = "TECHNICAL_EVENT"
    const val OVERDUE = "OVERDUE"
    const val VEHICLE_OUTAGE = "VEHICLE_OUTAGE"
    const val PLAN_ACK_OVERDUE = "PLAN_ACK_OVERDUE"
    const val TECH_PREFIX = "TECH_"

    /** Standardregel je Art: Priorität; kritische Alarme umgehen die Eskalationsverzögerung. */
    val DEFAULTS: List<AlertRule> = listOf(
        AlertRule(VEHICLE_BLOCK_CRITICAL, AlertPriority.CRITICAL, "dispatcher"),
        AlertRule(TECHNICAL_EVENT, AlertPriority.HIGH, "dispatcher"),
        AlertRule(OVERDUE, AlertPriority.HIGH, "dispatcher"),
        AlertRule(VEHICLE_OUTAGE, AlertPriority.HIGH, "dispatcher"),
        AlertRule(PLAN_ACK_OVERDUE, AlertPriority.NORMAL, "dispatcher"),
    )
    val FALLBACK = AlertRule("*", AlertPriority.NORMAL, "dispatcher")
}

/** Eine Regel der Alarmrichtlinie: Art (oder `*`), Priorität, Empfängerrolle. */
data class AlertRule(val kind: String, val priority: AlertPriority, val recipientRole: String)

enum class AlertState { OPEN, ACKNOWLEDGED, RESOLVED }

/** Ursache der Quittierung (fester Katalog, nie Freitext; ein kurzer Vermerk steht zusätzlich in `note`). */
enum class AckCause { REPAIRED, HANDLED, NOT_RELEVANT, FALSE_ALARM, ESCALATED_ELSEWHERE, OTHER }

data class AlertAcknowledgementView(val acknowledgedBy: String, val acknowledgedAt: Instant, val cause: AckCause?, val note: String?)

/**
 * Alarm der Arbeitsliste „Offene Störungen“ (UI-07). [policyRevisionNo] nennt die Alarmrichtlinie, nach der er entstand. [escalationLevel] > 0 und [escalatedAt] machen die Eskalation sichtbar.
 * Keine Fahrgast-PII, keine Diagnosen: nur Codes und Kennungen.
 */
data class AlertView(
    val id: UUID, val kind: String, val priority: AlertPriority, val state: AlertState, val vehicleId: UUID?, val serviceId: UUID?, val recipientRole: String,
    val policyRevisionNo: Int?, val escalationLevel: Int, val escalatedAt: Instant?, val escalateAt: Instant?, val reasonCode: String?, val refType: String?, val refId: UUID?,
    val createdAt: Instant, val updatedAt: Instant, val resolvedAt: Instant?, val revision: Long, val acknowledgement: AlertAcknowledgementView?,
)

data class AlertSettings(
    /** Eskalation nach dieser Verzögerung, wenn nicht quittiert (`alert_escalation_delay_min`, Planungsdefault 10 min, im Katalog nicht beziffert). Kritische Alarme umgehen sie. */
    val escalationDelay: Duration = Duration.ofMinutes(10),
    /** Schwelle für den Alarm „Fahrzeugausfall“ (`vehicle_unavailability_alert_min`, Planungsdefault 15 min; Auslöser WP-M3-53). */
    val vehicleUnavailabilityAlert: Duration = Duration.ofMinutes(15),
    /** Höchste Eskalationsstufe (P1: eine Stufe, Betriebsleitung). */
    val maxEscalationLevel: Int = 1,
) {
    init {
        require(escalationDelay >= Duration.ofSeconds(1) && escalationDelay <= Duration.ofHours(24)) { "Eskalationsverzögerung außerhalb 1 s..24 h" }
        require(maxEscalationLevel in 1..3) { "Eskalationsstufen 1..3" }
    }
}

data class AlertPolicyView(val id: UUID, val revisionNo: Int, val rules: List<AlertRule>, val createdAt: Instant)

/**
 * Alarmdienst (WP-M3-52): Arbeitsliste, Quittierung, Eskalation, Richtlinienrevisionen. Auslösen geschieht über [port] in der Transaktion des Fachmoduls. Sichtbar sind nur Alarme der
 * berechtigten Dienste (Alarme ohne Dienstbezug gelten mandantenweit); Rolle und Scope entscheidet die Rechtematrix im Filter (`operator:read` lesen, `operator:write` quittieren).
 */
interface Alerts {
    val port: BusinessAlertPort

    /** Arbeitsliste nach Alter (älteste zuerst), standardmäßig offene Alarme; [after] = Position `(created_at, id)`. */
    fun list(ctx: RequestContext, state: AlertState?, limit: Int, after: Pair<Instant, UUID>?): List<AlertView>

    fun get(ctx: RequestContext, id: UUID): AlertView

    /**
     * Quittiert einen offenen Alarm mit Akteur, Zeit und Ursache. `If-Match` = Alarmrevision (412 bei Änderung dazwischen, auch bei gleichzeitiger zweiter Quittierung: genau eine wirkt);
     * ein bereits quittierter oder gelöster Alarm ist 409 `INVALID_STATE_TRANSITION`; fremder Dienst oder Mandant 404; ohne Rolle 403.
     */
    fun acknowledge(ctx: RequestContext, id: UUID, ifMatch: Long, cause: AckCause, note: String?): AlertView

    /** Eskaliert fällige offene Alarme genau einmal je Stufe (Betriebsauftrag je Mandant, ohne Personenkontext, durch Sperre gegen parallele Läufe gesichert); liefert die eskalierten Alarm-IDs. */
    fun escalateDue(tenant: TenantId): List<UUID>

    /** Neue Alarmrichtlinie (Revision n+1); sie wirkt auf neue Ereignisse, bestehende Alarme behalten ihre Revision. Nur Betriebsleitung und Disposition. */
    fun publishPolicy(ctx: RequestContext, rules: List<AlertRule>): AlertPolicyView

    fun currentPolicy(ctx: RequestContext): AlertPolicyView?
}
