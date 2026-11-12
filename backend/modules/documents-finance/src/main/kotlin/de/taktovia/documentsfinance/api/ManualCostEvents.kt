package de.taktovia.documentsfinance.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** Die sechs Fahrzeugkostengruppen (FCO-002); die Schlüssel sind zugleich Kostenarten des Katalogs `cost_category`. */
object VehicleCostGroups {
    val ALL = listOf("ACQUISITION_LEASE", "INSURANCE", "VEHICLE_TAX", "ENERGY", "MAINTENANCE", "REPAIR")
}

/** Befehl: manuelles Kostenereignis zu einem Fahrzeug (ohne Beleg). `originKey` = fachlicher Ursprungsschlüssel (einmalig je Rechtsträger); Beträge in kleinster Einheit, `taxMinor` ist Teil von `grossMinor`. */
data class CreateManualCostEvent(
    val legalEntityId: UUID, val vehicleId: UUID, val category: String, val currency: String, val grossMinor: Long, val taxMinor: Long = 0, val taxTreatment: TaxTreatment = TaxTreatment.FULLY_DEDUCTIBLE,
    val accountingDate: LocalDate, val serviceFrom: LocalDate? = null, val serviceTo: LocalDate? = null, val description: String? = null, val originKey: String,
    /** Exponent der Beträge laut Anfrage (ISO 4217); `null` = nicht angegeben. Weicht er vom Währungskatalog ab, folgt 422 (Beträge nie in einer anderen Einheit als der kleinsten). */
    val exponent: Int? = null,
)

data class ManualCostStepView(val id: UUID, val step: ApprovalStep, val revision: Long, val decision: ApprovalDecision, val decidedBy: String, val reason: String, val decidedAt: Instant)

/** `state`: `IN_REVIEW`, `REVIEWED`, `WAITING_SECOND_APPROVAL`, `POSTED` (`eventId` gesetzt) oder `REJECTED`. */
data class ManualCostEventView(
    val id: UUID, val legalEntityId: UUID, val vehicleId: UUID, val costObjectId: UUID, val category: String, val currency: String, val netMinor: Long, val taxMinor: Long, val grossMinor: Long,
    val taxTreatment: TaxTreatment, val accountingDate: LocalDate, val serviceFrom: LocalDate?, val serviceTo: LocalDate?, val description: String?, val originKey: String, val state: String,
    val revision: Long, val createdBy: String, val approvedBy: String?, val eventId: UUID?, val createdAt: Instant, val steps: List<ManualCostStepView>,
)

/** `reused = true`: derselbe Ursprungsschlüssel mit gleichem Inhalt war schon erfasst; es wurde nichts angelegt. */
data class ManualCostCreated(val view: ManualCostEventView, val reused: Boolean)

/** Ergebnis der Freigabe: `state` = `WAITING_SECOND_APPROVAL` (zweite Person erforderlich) oder `POSTED`; `postingId` = Kostenereignis, sonst `null`. */
data class ManualCostOutcome(val view: ManualCostEventView, val postingId: UUID?)

/**
 * Manuelles, freigabepflichtiges Fahrzeugkostenereignis (WP-M4-40, FCO-002): Erfassung -> fachliche Prüfung -> Budgetfreigabe -> (zweite Freigabe) -> Posting, mit denselben Regeln wie der Beleg-Automat
 * ([ExpenseApprovals]): Rollentrennung (der Erfasser gibt nie selbst frei, die zweite Freigabe erteilt nie der Erstfreigeber, auch in der Datenbank gesichert), Vier-Augen-Schwelle
 * (`approval_second_threshold_minor`: Betrag **größer** als die Schwelle), exakte Revision (412), Zustandsprüfung (409) und Posting in derselben Transaktion (Ereignis, Journal, Audit, Outbox `cost_event.posted`).
 * Der fachliche Ursprungsschlüssel macht Anlegen und Buchen einmalig: ein zweites Anlegen mit gleichem Inhalt liefert den vorhandenen Vorgang, mit anderem Inhalt 409; ein Ursprung wird höchstens einmal gebucht.
 * Die Kostenart muss im Katalog stehen (422), das Fahrzeug im Mandanten existieren (404). Eine Ablehnung ist endgültig (neuer Vorgang mit neuem Ursprungsschlüssel).
 */
interface ManualCostEvents {
    /** Scope `costs:write`, Finanzrolle, Mensch (403 sonst). Eine Kontierungsregel für Rechtsträger und Kostenart muss existieren (409). */
    fun create(ctx: RequestContext, cmd: CreateManualCostEvent): ManualCostCreated

    /** Scope `costs:read`; 404 fremder Mandant. */
    fun get(ctx: RequestContext, id: UUID): ManualCostEventView

    /** Fachliche Prüfung (Scope `expenses:review`, Finanzrolle, Mensch); `REJECTED` beendet den Vorgang. Nur im Zustand `IN_REVIEW`. */
    fun review(ctx: RequestContext, id: UUID, expectedRevision: Long, decision: ApprovalDecision, reason: String): ManualCostEventView

    /** Budgetfreigabe bzw. zweite Freigabe (Scope `expenses:approve`, Finanzrolle, Mensch); die endgültige Freigabe bucht. */
    fun approve(ctx: RequestContext, id: UUID, validatedRevision: Long, reason: String): ManualCostOutcome

    /** Ablehnung in Budgetfreigabe oder zweiter Freigabe (Recht wie [approve]). */
    fun reject(ctx: RequestContext, id: UUID, expectedRevision: Long, reason: String): ManualCostEventView
}

/**
 * Auswertung der Fahrzeugkosten je Monat und Kostenart (`GET /v1/vehicles/{id}/total-cost`, P1-Ausschnitt, OP 20): nur gebuchte Ist-Ereignisse, keine Abschreibung und keine Verteilung der Anschaffung
 * (die Gruppe Anschaffung/Leasing erscheint mit der erfassten Position). Monatszuordnung: Beginn des Leistungszeitraums (`service_from`, UTC), sonst das Buchungsdatum ([VehicleTotalCost.assignmentRule]).
 */
data class VehicleCostQuery(val valuation: String?, val currency: String?, val monthFrom: YearMonth?, val monthTo: YearMonth?)

data class VehicleCostItem(
    val month: YearMonth, val category: String, val costGroup: String?, val label: String?, val amountMinor: Long, val eventCount: Long, val byServicePeriodEvents: Long, val byAccountingDateEvents: Long,
)

data class VehicleCostMonthTotal(val month: YearMonth, val amountMinor: Long)

data class VehicleCostCategoryTotal(val category: String, val costGroup: String?, val amountMinor: Long)

/** `monthTo` ist inklusive. Ein Zeitraum ohne Kosten liefert leere Listen (kein Fehler). */
data class VehicleTotalCost(
    val vehicleId: UUID, val valuation: String, val currency: String, val monthFrom: YearMonth, val monthTo: YearMonth, val assignmentRule: String, val asOf: Instant,
    val items: List<VehicleCostItem>, val monthTotals: List<VehicleCostMonthTotal>, val categoryTotals: List<VehicleCostCategoryTotal>, val totalMinor: Long,
)

interface VehicleCosts {
    /** Scope `costs:read`. 422 ohne/mit ungültiger Bewertungsart, Zeitraum oder Währung (Zeitraum höchstens 60 Monate); 404 fremdes oder unbekanntes Fahrzeug. */
    fun totalCost(ctx: RequestContext, vehicleId: UUID, query: VehicleCostQuery): VehicleTotalCost
}
