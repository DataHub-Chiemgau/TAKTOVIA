package de.taktovia.documentsfinance.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/** Schritte des Freigabe-Workflows (WP-M4-34, DOC-010): Erfassung, fachliche Prüfung, Budgetfreigabe (erste Freigabe) und zweite Freigabe (Vier-Augen-Schwelle). Der Exportstatus ist ein eigenes Feld am Beleg ([ExportState]). */
enum class ApprovalStep { CAPTURE, REVIEW, BUDGET, SECOND_APPROVAL }

enum class ApprovalDecision { APPROVED, REJECTED }

/** Belegzustände des Freigabe-Workflows (`expense_document.state`); `STORED` ist die Erfassung, eine Ablehnung führt dorthin zurück. */
object ExpenseStates {
    const val STORED = "STORED"
    const val IN_REVIEW = "IN_REVIEW"
    const val REVIEWED = "REVIEWED"
    const val WAITING_SECOND_APPROVAL = "WAITING_SECOND_APPROVAL"
    const val APPROVED = "APPROVED"
    const val POSTED = "POSTED"
    /** Zustände nach der Erfassung, in denen noch nicht gebucht ist: Hinweise und Dublettentreffer sind dort noch entscheidbar. */
    val IN_WORKFLOW = setOf(IN_REVIEW, REVIEWED, WAITING_SECOND_APPROVAL)
}

enum class ExportState { NOT_EXPORTED, EXPORTED }

/** Ein Freigabeschritt mit Akteur, Dokumentrevision, Entscheidung, Begründung und Zeit (`approval_step`, append-only; zusätzlich im Audit). */
data class ApprovalStepView(val id: UUID, val step: ApprovalStep, val documentRevision: Long, val decision: ApprovalDecision, val decidedBy: String, val reason: String, val decidedAt: Instant)

/** Ergebnis der Freigabe: `state` = `WAITING_SECOND_APPROVAL` (zweite Person erforderlich) oder `POSTED`; `postingId` = Kostenereignis, sonst `null`. */
data class ApprovalOutcome(val documentId: UUID, val revision: Long, val state: String, val postingId: UUID?, val view: ExpenseView)

/**
 * Freigabe-Workflow (WP-M4-34): Erfassung -> fachliche Prüfung -> Budgetfreigabe -> (zweite Freigabe) -> Posting; Export ist ein eigener Status. Jeder Schritt hat ein eigenes Recht und einen eigenen Eintrag
 * (`approval_step` und Audit `finance.expense.approval_step`). Alle Befehle sperren die Belegzeile, binden sich an die exakte Dokumentrevision (veraltet 412) und sind atomar: `POSTED` entsteht erst mit
 * dem Commit von Ereignis, Journal und Outbox in derselben Transaktion (WP-M4-33); scheitert das Posting, bleibt der Beleg unverändert.
 *
 * **Blockaden** (je Hindernis ein eigener stabiler Code, Reihenfolge der Prüfung): Integritätsverletzung 409 `DOCUMENT_INTEGRITY_VIOLATION`; Dublette 409 `DUPLICATE_REVIEW_REQUIRED` bzw.
 * `DUPLICATE_EVIDENCE_LINKED`; Pflichtfelder 422 `UNCONFIRMED_FIELDS`; Kostenzuordnung 422 `COST_ASSIGNMENT_REQUIRED`; Tankwerte 422 `FUEL_FIELDS_MISSING`; Aufteilung 422 `SPLIT_INCOMPLETE`
 * (die Aufteilung selbst folgt mit WP-M4-35, bis dahin gibt es nie einen unvollständigen Split); offener Plausibilitätshinweis 422 `HINT_NOT_ACKNOWLEDGED`.
 *
 * **Rollentrennung (Planungsannahme, D-06, Offener Punkt 17):** Erfassung: Erfasser (`receipts:write`); Sachprüfung: `expenses:review` und Finanzrolle; Budgetfreigabe und zweite Freigabe: `expenses:approve` und
 * Finanzrolle (jeweils ein Mensch, kein Dienstkonto); Export: `expenses:export`. Der Erfasser gibt nie selbst frei (403), die zweite Freigabe erteilt nie der Erstfreigeber (403); beides sichert zusätzlich die
 * Datenbank. Eine Vertretungsregel gibt es nicht (DOC-018, M8): ein Vertreter handelt unter eigener Identität und unterliegt denselben Regeln.
 */
interface ExpenseApprovals {
    /** Schließt die Erfassung ab (`STORED` -> `IN_REVIEW`, Schritt `CAPTURE`); der Beleg ist danach nicht mehr bearbeitbar. Erfasser oder Finanzrolle, Scope `receipts:write`. Evidenz einer Dublette: 409. */
    fun submit(ctx: RequestContext, documentId: UUID, expectedRevision: Long): ExpenseView

    /**
     * Fachliche Prüfung (Scope `expenses:review`, Finanzrolle, Mensch; sonst 403). `APPROVED`: der Beleg durchläuft alle Blockaden und wird `REVIEWED`; `REJECTED`: zurück in die Erfassung (`STORED`) mit
     * Begründung, ohne Kostenereignis und ohne Journal. Nur im Zustand `IN_REVIEW`, sonst 409 `INVALID_STATE_TRANSITION`.
     */
    fun review(ctx: RequestContext, documentId: UUID, expectedRevision: Long, decision: ApprovalDecision, reason: String): ExpenseView

    /**
     * Endgültige Freigabe (Scope `expenses:approve`, Finanzrolle, Mensch; ohne Recht 403, ohne Schritteintrag). Erste Freigabe (Beleg `REVIEWED`, Schritt `BUDGET`): bei einem Betrag größer als
     * `approval_second_threshold_minor` (exakt auf der Schwelle genügt eine Freigabe) wartet der Beleg auf die zweite Person (`WAITING_SECOND_APPROVAL`), sonst entsteht das Kostenereignis (`POSTED`). Zweite
     * Freigabe (Schritt `SECOND_APPROVAL`): eine dritte Person gegenüber Erfasser und Erstfreigeber, dann `POSTED`. `validatedRevision` = exakte Dokumentrevision (veraltet 412). Ein bereits gebuchter Beleg: 409.
     */
    fun approve(ctx: RequestContext, documentId: UUID, validatedRevision: Long, reason: String): ApprovalOutcome

    /** Ablehnung in Budgetfreigabe oder zweiter Freigabe (Recht wie [approve]): zurück in die Erfassung mit Begründung, ohne Kostenereignis und ohne Journal. */
    fun reject(ctx: RequestContext, documentId: UUID, expectedRevision: Long, reason: String): ExpenseView

    /** Alle Freigabeschritte des Belegs in zeitlicher Folge (Erfasser und Finanzrolle; sonst 404). */
    fun steps(ctx: RequestContext, documentId: UUID): List<ApprovalStepView>

    /**
     * Setzt den Exportstatus (eigenes Statusfeld, nur der Exportjob WP-M4-37): Scope `expenses:export` (Mensch oder Dienstkonto), sonst 403. Nur für gebuchte Belege (also nur nach beiden Freigaben), sonst 409
     * `INVALID_STATE_TRANSITION`; wiederholt ist es ein No-op. Die Freigaberechte allein genügen nie.
     */
    fun markExported(ctx: RequestContext, documentId: UUID): ExpenseView
}
