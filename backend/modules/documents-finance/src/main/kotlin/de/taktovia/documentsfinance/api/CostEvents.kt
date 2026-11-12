package de.taktovia.documentsfinance.api

import de.taktovia.platform.context.RequestContext
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Steuerbehandlung beim Posting (Testregel, D-06 offen): `FULLY_DEDUCTIBLE` = die Vorsteuer ist voll abziehbar (Kostenbasis = netto), `NON_DEDUCTIBLE` = nicht abziehbar (Kostenbasis = brutto).
 * Versionierte Steuerkennzeichen sind P2 (DOC-021, M8).
 */
enum class TaxTreatment { FULLY_DEDUCTIBLE, NON_DEDUCTIBLE }

/** Posting-Befehl eines freigegebenen Belegs: `category` = Kostenart (Schlüssel der Kontierungsregel, z. B. `FUEL`). */
data class PostExpense(val category: String, val tax: TaxTreatment = TaxTreatment.FULLY_DEDUCTIBLE)

/** Unveränderliches wirtschaftliches Ereignis (`economic_event`); Beträge in kleinster Einheit, `costBasisMinor = grossMinor - recoverableTaxMinor`. */
data class CostEventView(
    val id: UUID, val legalEntityId: UUID, val sourceSystem: String, val sourceRef: String, val eventType: String, val scenario: String, val category: String, val currency: String,
    val netMinor: Long, val taxMinor: Long, val grossMinor: Long, val recoverableTaxMinor: Long, val costBasisMinor: Long, val taxRuleRevision: String,
    val occurredAt: Instant, val bookedAt: Instant, val documentId: UUID?, val reversesId: UUID?,
)

data class JournalLineView(val account: String, val side: String, val amountMinor: Long)

data class JournalView(val id: UUID, val eventId: UUID, val state: String, val accountingDate: LocalDate, val periodId: UUID, val postedBy: String?, val lines: List<JournalLineView>)

/** Ergebnis des Postings; `reused = true`: der Ursprung war schon gebucht, es wurde nichts verändert (kein zweites Ereignis, keine neue Summe). */
data class PostingResult(val event: CostEventView, val journal: JournalView, val reused: Boolean)

/**
 * Kostenereignis, Journal und einmaliges Posting (WP-M4-33). Eine Freigabe (WP-M4-34) ruft [postExpense] in ihrer Transaktion: unter Sperre (Beleg `FOR UPDATE`, Kostenobjekte, Buchungsperiode)
 * werden Zustand, Integrität, Kostenzuordnung, Kontierung, Periode und Summen erneut geprüft, dann entstehen in **einer** Transaktion Ereignis, Journal (erst `DRAFT` mit Zeilen, dann `POSTED`),
 * Belegzustand `POSTED`, Audit und Outbox-Ereignis `cost_event.posted`. Ereignis und gebuchtes Journal sind unveränderlich (Datenbank); Korrekturen sind Gegenereignisse (WP-M4-36).
 * Salden gibt es nur als Projektion der Journalzeilen gebuchter Journale ([accountBalances]).
 */
interface CostEvents {
    /**
     * Bucht den **freigegebenen** Beleg (`state = APPROVED`, `approved_by` gesetzt) genau einmal. Erforderlich: Scope `expenses:approve` und Finanzrolle bzw. Dienstkonto, sonst 403. Ein zweiter Auslöser für
     * denselben Beleg (auch parallel) liefert dasselbe Ereignis (`reused`). 404 fremder Beleg; 409 (`CONFLICT`) bei fehlender Kontierung, fehlender Buchungsperiode, geschlossener Periode, fehlender oder
     * inaktiver Kostenzuordnung; 409 `INVALID_STATE_TRANSITION` bei nicht freigegebenem Beleg; 409 `DOCUMENT_INTEGRITY_VIOLATION`; 422 bei fehlenden Pflichtfeldern oder Beträgen außerhalb des sicheren Bereichs.
     */
    fun postExpense(ctx: RequestContext, documentId: UUID, cmd: PostExpense): PostingResult

    /** Wie [postExpense], aber in der laufenden Mandantentransaktion des Aufrufers (Freigabe-Workflow: `POSTED` erst nach atomarem Journal- und Outbox-Commit). */
    fun postExpense(jdbc: JdbcOperations, ctx: RequestContext, documentId: UUID, cmd: PostExpense): PostingResult

    /** Ist-Bericht: Ereignisse des Szenarios `ACTUAL` **mit gebuchtem Journal**, aufsteigend nach `(booked_at, id)`; `asOf` begrenzt auf `booked_at <= asOf`. Scope `costs:read`. */
    fun list(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?, asOf: Instant? = null, legalEntityId: UUID? = null): List<CostEventView>

    /** Journal zu einem Ereignis (Zeilen sortiert nach Konto und Seite); 404, wenn es keins gibt. Scope `costs:read`. */
    fun journal(ctx: RequestContext, eventId: UUID): JournalView

    /** Summe der Kostenbasis aller gebuchten Ist-Ereignisse des Rechtsträgers in der Währung (nur `POSTED` zählt). Scope `costs:read`. */
    fun actualCostBasis(ctx: RequestContext, legalEntityId: UUID, currency: String): Long

    /** Kontosalden (Soll minus Haben) als Projektion der Zeilen gebuchter Journale des Rechtsträgers in der Währung. Scope `costs:read`. */
    fun accountBalances(ctx: RequestContext, legalEntityId: UUID, currency: String): Map<String, Long>
}

/** Kontierungsregel: Kostenart -> Aufwandskonto, Vorsteuerkonto, Verbindlichkeitskonto je Rechtsträger (Test-Kontenrahmen, D-06). */
data class AccountingMappingView(val legalEntityId: UUID, val category: String, val expenseAccount: String, val inputTaxAccount: String, val payableAccount: String, val revision: Long)

data class PutAccountingMapping(val legalEntityId: UUID, val category: String, val expenseAccount: String, val inputTaxAccount: String, val payableAccount: String)

/** Pflege der Kontierungsregeln (nur Finanzrolle, Scope `expenses:approve`; Audit `finance.accounting_mapping.changed`). Der Katalogparameter `invoice_accounting_schema` ist nicht gebaut: die Regeln liegen in `accounting_mapping`. */
interface AccountingMappings {
    fun put(ctx: RequestContext, cmd: PutAccountingMapping): AccountingMappingView
    fun get(ctx: RequestContext, legalEntityId: UUID, category: String): AccountingMappingView?
}
