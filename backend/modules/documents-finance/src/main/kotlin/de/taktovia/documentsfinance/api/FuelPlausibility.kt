package de.taktovia.documentsfinance.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/** Regeln der Tankplausibilität (WP-M4-31, DOC-012). Je Regel und Dokumentrevision höchstens ein Hinweis. */
enum class PlausibilityRule { FUEL_VOLUME, FUEL_PRICE, FUEL_ODOMETER_GAP, FUEL_CARD_MISMATCH }

/** Quittierung eines Hinweises: Akteur, Begründung, Zeit; ein eigener append-only Eintrag, der den Hinweis selbst nie ändert. */
data class HintAcknowledgement(val by: String, val reason: String, val at: Instant)

/**
 * Hinweis zu einem Beleg: Regel, Schwelle, Istwert und Berechnungsbasis (`basis`: Vergleichswert, Schwelle, Quelle; je Regel weitere Angaben). Die erfassten Werte des Belegs bleiben unverändert;
 * [acknowledgement] = `null` heißt offen. Ein offener Hinweis blockiert die finanzielle Freigabe ([ReleaseBlocker.HINT_UNACKNOWLEDGED]).
 */
data class HintView(
    val id: UUID, val rule: PlausibilityRule, val documentRevision: Long, val threshold: String, val actualValue: String, val basis: Map<String, Any?>, val raisedAt: Instant,
    val acknowledgement: HintAcknowledgement?,
) { val open: Boolean get() = acknowledgement == null }

/** Vermerk zu einer Regel, die nicht prüfbar ist: `PARAMETER_MISSING` (Parameter nicht konfiguriert, nie stillschweigend „bestanden“) oder `NO_COMPARISON_BASIS` (kein Vergleichswert, z. B. kein Vorgänger-Kilometerstand). */
data class PlausibilityNote(val rule: PlausibilityRule, val code: String, val text: String)

/** Stand der Plausibilität eines Belegs für seine aktuelle Revision: Hinweise (offen und quittiert) und Vermerke zu nicht prüfbaren Regeln. */
data class PlausibilityReport(val hints: List<HintView> = emptyList(), val notes: List<PlausibilityNote> = emptyList()) {
    val openHints: List<HintView> get() = hints.filter { it.open }
    companion object { val NONE = PlausibilityReport() }
}

/**
 * Tankplausibilität (WP-M4-31, DOC-012): prüft Literpreis, Tankvolumen, Kilometerabstand und Tankkarten-ID eines Tankbelegs gegen die konfigurierten Schwellen (`fuel_max_liters`, `fuel_price_alert_pct`,
 * `fuel_use_alert_pct`, `fuel_card_mapping`, [FinancePolicies]) und erzeugt bei Abweichungen **kontrollierbare Hinweise, keine stillen Änderungen**: kein Hinweis ändert einen erfassten Wert, jeder nennt
 * Schwelle, Istwert und Berechnungsbasis, ist einsehbar, quittierbar (mit Begründung) und im Audit nachvollziehbar. Ein Wert exakt auf der Schwelle löst keinen Hinweis aus. Die Prüfung läuft je
 * Dokumentrevision (nach jeder Revision des Belegs und im Freigabeschritt); eine Quittierung gilt nur für ihre Revision.
 */
interface FuelPlausibility {
    /** Prüflauf (idempotent: je Regel und Revision entsteht höchstens ein Hinweis, auch bei parallelen Läufen). Erfasser und Finanzrolle; sonst 404. */
    fun check(ctx: RequestContext, documentId: UUID): PlausibilityReport

    /** Hinweise der aktuellen Revision und Vermerke (liest nur). */
    fun report(ctx: RequestContext, documentId: UUID): PlausibilityReport

    /**
     * Quittiert einen offenen Hinweis der aktuellen Revision (`expectedRevision` = `If-Match`, veraltet 412; Hinweis einer früheren Revision: 409). Recht: Scope `expenses:review` und Finanzrolle (dasselbe
     * Recht wie die Sachprüfung; Planungsannahme bis zur Rollenfestlegung in WP-M4-34), sonst 403; Begründung 3 bis 1000 Zeichen, sonst 422. Gleichzeitige Quittierungen desselben Hinweises ergeben genau einen Eintrag.
     */
    fun acknowledge(ctx: RequestContext, documentId: UUID, hintId: UUID, expectedRevision: Long, reason: String): ExpenseView
}
