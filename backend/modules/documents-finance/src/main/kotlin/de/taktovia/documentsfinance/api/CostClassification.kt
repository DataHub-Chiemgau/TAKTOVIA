package de.taktovia.documentsfinance.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Achse 1 der Kostenklassifikation (Kap. 03 §1): Kostenverhalten. `MIXED` wird weder fix noch variabel zugeschlagen, sondern in einer eigenen Spalte „gemischt“ ausgewiesen (Detailvertrag FCO-001). */
enum class CostBehavior { FIXED, VARIABLE, MIXED }

/** Achse 2: Zurechnung. `DIRECT` = direkt gebucht, `ALLOCATED` = umgelegt/zugerechnet. Die Achsen sind orthogonal und werden nie addiert (Offener Punkt 19). */
enum class CostAttribution { DIRECT, ALLOCATED }

/**
 * Auswertung der Kostenklassifikation (WP-M4-39, FCO-001). **Bewertungsart (`valuation`), Periode und Währung sind Pflicht** (Kap. 03 §1.1): fehlt eine Angabe oder ist sie ungültig, folgt 422; für P1
 * gilt nur `ACTUAL` (Plan/Forecast ist FCO-017). Die Periode `[periodFrom, periodTo)` bezieht sich auf das Buchungsdatum (`journal.accounting_date`, [CostSummaryView.periodBasis]).
 */
data class CostSummaryQuery(
    val valuation: String?, val currency: String?, val periodFrom: LocalDate?, val periodTo: LocalDate?,
    val legalEntityId: UUID? = null, val vehicleId: UUID? = null,
)

/** Eine Zelle der Kreuztabelle; beide Achsen `null` = „nicht klassifiziert“. */
data class CostClassCell(val costBehavior: CostBehavior?, val attribution: CostAttribution?, val amountMinor: Long, val eventCount: Long)

/**
 * Summen je Achse und insgesamt. `totalMinor` ist die Summe der **wirksamen Ist-Kosten** (= Summe der Zellen), **nicht** die Addition der vier Klassen: `fixed + variable + mixed + unclassified = total`
 * und `direct + allocated + unclassified = total`; fix/variabel und direkt/umgelegt beschreiben dieselben Kosten aus zwei Blickwinkeln.
 */
data class CostClassTotals(
    val fixedMinor: Long, val variableMinor: Long, val mixedMinor: Long, val unclassifiedMinor: Long,
    val directMinor: Long, val allocatedMinor: Long, val totalMinor: Long,
)

/** Eine Zeile je Kostenobjekt; Kostenobjekt `null`/Art `UNALLOCATED` sammelt Kosten ohne eindeutiges Kostenobjekt. */
data class CostSummaryRow(
    val costObjectId: UUID?, val costObjectKind: String, val businessRef: String?, val vehicleId: UUID?, val cells: List<CostClassCell>, val totals: CostClassTotals,
)

/** Auswertung mit Bewertungsart, Periode, Währung und Datenstand (`asOf`: Stand der Buchungen, die die Auswertung sieht; alle Zeilen stammen aus einer konsistenten Momentaufnahme). */
data class CostSummaryView(
    val valuation: String, val currency: String, val periodFrom: LocalDate, val periodTo: LocalDate, val periodBasis: String, val asOf: Instant,
    val rows: List<CostSummaryRow>, val totals: CostClassTotals, val eventCount: Long,
)

/** Umklassifikation eines gebuchten Ereignisses: neue Klassifikation, Begründung, optional Buchungsdatum der Korrektur (Vorgabe heute, UTC; die erste offene Periode ab dem Datum zählt). */
data class ReclassifyCostEvent(val costBehavior: CostBehavior, val attribution: CostAttribution, val reason: String, val accountingDate: LocalDate? = null)

/** Ergebnis der Umklassifikation: Gegenereignis des Ursprungs und Neubuchung mit der neuen Klassifikation (jeweils mit gebuchtem Journal). */
data class ReclassificationResult(val reversal: CostEventView, val rebooking: CostEventView, val reversalJournal: JournalView, val rebookingJournal: JournalView)

/**
 * Kostenklassifikation (WP-M4-39). Die Klassifikation wird beim Posting aus dem Kostenartenkatalog (`cost_category`, Standardverhalten) gesetzt; ein Ereignis ohne Klassifikation ist „nicht klassifiziert“ und wird
 * separat ausgewiesen, nie verworfen. `economic_event` ist unveränderlich: eine **Umklassifikation** ist ein Gegenereignis plus Neubuchung in einer Transaktion (Recht wie [CostEventCorrections]); die Neubuchung
 * ist je Ursprung höchstens einmal möglich (zwei parallele Umklassifikationen: genau eine gewinnt, die andere 409). Original und Umlage derselben Kosten werden nie doppelt gezählt: je Ereignis zählt die aktive
 * Umlage oder, ohne Umlage, sein einziges zugeordnetes Kostenobjekt (sonst „nicht zugeordnet“).
 */
interface CostClassifications {
    /** Scope `costs:read`. 422 ohne/mit ungültiger Bewertungsart, Periode oder Währung; 404 fremdes bzw. unbekanntes Fahrzeug (`vehicleId`) oder fremder Rechtsträger. Nur gebuchte (`POSTED`) Ereignisse zählen. */
    fun summary(ctx: RequestContext, query: CostSummaryQuery): CostSummaryView

    /** Scope `costs:reverse` und Finanzrolle (Mensch). 404 fremdes Ereignis; 409 bei unveränderter Klassifikation, bereits (teil-)korrigiertem oder nicht gebuchtem Ereignis oder fehlender offener Periode. */
    fun reclassify(ctx: RequestContext, eventId: UUID, cmd: ReclassifyCostEvent): ReclassificationResult
}
