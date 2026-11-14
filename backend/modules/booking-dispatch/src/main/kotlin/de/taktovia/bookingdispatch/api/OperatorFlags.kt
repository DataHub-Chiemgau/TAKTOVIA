package de.taktovia.bookingdispatch.api

import de.taktovia.bookingdispatch.internal.BookingServicesFactory
import de.taktovia.platform.alert.BusinessAlertPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Ursachen der Kennzeichen (WP-M3-42, Arbeitsannahme bis zur Entscheidung OP-06): [PICKUP_OVERDUE] ist eine **Verspätung** (`OVERDUE`), die übrigen sind **ausstehende Informationen**
 * (`INFO_PENDING`): ein veralteter Standort, eine ausbleibende Fahrerbestätigung einer Planrevision und ein offener `REVIEW_REQUIRED`-Fall des Fahrers. Beide Arten sind getrennte Zustände.
 */
enum class FlagCause(val kind: String) {
    PICKUP_OVERDUE("OVERDUE"), POSITION_STALE("INFO_PENDING"), PLAN_ACK_MISSING("INFO_PENDING"), REVIEW_REQUIRED_OPEN("INFO_PENDING");
    companion object { fun parse(code: String?): FlagCause? = entries.firstOrNull { it.name == code } }
}

/**
 * Schwelle je Ursache und Dienst in Minuten, **bei jeder Auswertung neu gelesen** (wirksame Konfiguration des Dienstes). Zuordnung (Arbeitsannahme, das Pflichtenheft legt sie nicht fest): Abholung
 * überfällig = `on_time_pickup_tolerance_min`, Standort veraltet = `vehicle_unavailability_alert_min`, Planbestätigung = `plan_ack_overdue_min`, offener Klärfall = `alert_escalation_delay_min`.
 */
fun interface ThresholdSource {
    fun minutes(tenant: TenantId, serviceId: UUID?, cause: FlagCause): Int

    companion object {
        /** Planungsdefaults der Katalogparameter (nicht freigegeben, D-05): 5, 15, 5 und 10 Minuten. */
        val DEFAULTS = ThresholdSource { _, _, cause -> when (cause) { FlagCause.PICKUP_OVERDUE -> 5; FlagCause.POSITION_STALE -> 15; FlagCause.PLAN_ACK_MISSING -> 5; FlagCause.REVIEW_REQUIRED_OPEN -> 10 } }
    }
}

/** Eine bereits fällige Meldung eines anderen Moduls (Standortalter, Planbestätigung, Klärfall): [since] ist der Beginn der Bedingung, [thresholdMin] die dort wirksame Schwelle (nur zur Anzeige). */
data class DueSignal(val cause: FlagCause, val vehicleId: UUID, val runId: UUID?, val since: Instant, val thresholdMin: Int?) {
    init { require(cause != FlagCause.PICKUP_OVERDUE) { "Verspätungen berechnet booking-dispatch selbst" } }
}

/** Quelle der fälligen Meldungen anderer Module (die Anwendung setzt sie aus `staff-time` zusammen; `booking-dispatch` liest deren Tabellen nicht). */
fun interface SignalSource {
    fun due(tenant: TenantId, now: Instant): List<DueSignal>
    companion object { val NONE = SignalSource { _, _ -> emptyList() } }
}

/** Ergebnis eines Auswertungslaufs; [skipped] = ein anderer Lauf desselben Mandanten hielt die Lease (der Lauf tut nichts, der andere wertet aus). */
data class FlagEvaluation(val opened: Int, val updated: Int, val resolved: Int, val skipped: Boolean = false)

/** Hinweis der Arbeitsliste: nur Kennungen und Zahlen, keine Namen (WP-M3-42 Kriterium 6). */
data class OperatorFlagItem(
    val id: UUID, val kind: String, val cause: String, val state: String, val serviceId: UUID?, val vehicleId: UUID?, val affectedCount: Int, val openedAt: Instant, val resolvedAt: Instant?,
    val thresholdMin: Int?, val revision: Long,
)

/**
 * Überfälligkeit und ausstehende Informationen (WP-M3-42, LH-6.7-OPS-006). Der **Auswertungslauf** [evaluate] ist ein Betriebsauftrag je Mandant ohne Personenkontext: unter einer Lease (transaktionsbezogene
 * Sperre je Mandant, ein zweiter gleichzeitiger Lauf überspringt) werden die Bedingungen mit den Schwellen des jeweiligen Dienstes geprüft; je Sachverhalt entsteht **genau ein offener Hinweis**
 * (Teilindex der Datenbank auch gegen konkurrierende Läufe) mit der Zahl betroffener Buchungen **des Dienstes**, Fehlendes wird erledigt, sobald die Ursache entfällt. Die beim Auslösen wirksame
 * Schwelle bleibt am Hinweis stehen: eine Schwellenänderung wirkt auf neue Auswertungen und neue Hinweise, nie rückwirkend. Jeder neue Hinweis löst einen Alarm der Alarm-Grundlage (WP-M3-52) aus.
 *
 * Lesen: nur Hinweise der Dienste im **aktuellen** Dienstscope der Mitgliedschaft (Hinweise ohne Dienstbezug gelten mandantenweit); fremder Mandant nie (RLS).
 */
interface OperatorFlags {
    fun evaluate(tenant: TenantId): FlagEvaluation

    /** Arbeitsliste: Hinweise im Zustand [state] (`OPEN`, Standard, oder `RESOLVED`), nach `(opened_at, id)`; [limit] + 1 Zeilen ab [after]. */
    fun list(ctx: RequestContext, state: String?, kind: String?, serviceId: UUID?, limit: Int, after: Pair<Instant, UUID>?): List<OperatorFlagItem>

    /** Offene Hinweise je Fahrzeug für die Fahrzeugansicht (Fahrzeuge ohne Hinweis fehlen). */
    fun forVehicles(ctx: RequestContext, vehicleIds: Collection<UUID>): Map<UUID, List<OperatorFlagView>>

    companion object {
        fun create(ctxTx: ContextTransactions, clock: Clock = Clock.systemUTC(), thresholds: ThresholdSource = ThresholdSource.DEFAULTS, signals: SignalSource = SignalSource.NONE, alerts: BusinessAlertPort? = null): OperatorFlags =
            BookingServicesFactory.operatorFlags(ctxTx, clock, thresholds, signals, alerts)
    }
}
