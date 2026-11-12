package de.taktovia.documentsfinance.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Fiskalperiode eines Rechtsträgers: halboffenes Intervall `[startsOn, endsOn)`; `closedAt` gesetzt = abgeschlossen und endgültig unveränderlich. */
data class FiscalPeriodView(val id: UUID, val legalEntityId: UUID, val startsOn: LocalDate, val endsOn: LocalDate, val closedAt: Instant?, val closedBy: String?, val createdBy: String?) {
    val closed: Boolean get() = closedAt != null
}

data class CreateFiscalPeriod(val legalEntityId: UUID, val startsOn: LocalDate, val endsOn: LocalDate)

/**
 * Periodenverwaltung und Abschluss (WP-M4-36). Zustandsautomat der Periode: `OPEN` --`close`--> `CLOSED` (endgültig). Es gibt **keine** Wiederöffnung und keinen Pfad, der `closed_at` zurücksetzt
 * (Pflichtenheft sieht keine vor; die Datenbank hält es zusätzlich: Härtung V2). Perioden eines Rechtsträgers überlappen sich nie (Exclusion Constraint, auch bei parallelem Anlegen genau eine).
 *
 * Abschluss: Scope `periods:close`, Finanzrolle, Mensch; die Periodenzeile wird `FOR UPDATE` gesperrt, wie es auch Posting und Aufteilung tun: bei Konkurrenz gewinnt genau eine Transaktion, nach dem Abschluss
 * ist die Periode nicht mehr beschreibbar (Posting und Aufteilung: 409 `PERIOD_CLOSED`). Wer als Zweiter abschließt, erhält 409 `PERIOD_CLOSED` (kein zweiter Auditeintrag). Akteur und Zeit stehen im Audit
 * (`finance.period.closed`) und an der Periode. Die Regel, ab wann eine Periode abgeschlossen werden darf (`finance_close_day`), ist offen (D-06) und wird nicht erzwungen.
 */
interface FiscalPeriods {
    /** Legt eine offene Periode an (Scope `periods:manage`, Finanzrolle). Überlappung mit einer vorhandenen Periode desselben Rechtsträgers: 409 `CONFLICT`; fremder Rechtsträger: 404. */
    fun create(ctx: RequestContext, cmd: CreateFiscalPeriod): FiscalPeriodView

    fun close(ctx: RequestContext, periodId: UUID): FiscalPeriodView

    /** Perioden des Mandanten (optional eines Rechtsträgers), aufsteigend nach Beginn und Kennung. Scope `periods:read`. */
    fun list(ctx: RequestContext, legalEntityId: UUID?, limit: Int, after: Pair<LocalDate, UUID>?): List<FiscalPeriodView>

    fun get(ctx: RequestContext, periodId: UUID): FiscalPeriodView
}

/**
 * Gegenereignis zu einem gebuchten Kostenereignis. `creditGrossMinor` = Bruttobetrag der Korrektur (Gutschrift); `null` = der gesamte noch nicht korrigierte Rest. `accountingDate` = gewünschtes
 * Buchungsdatum (Vorgabe: heute, UTC); gilt die Periode dieses Datums als geschlossen, wird die **nächste offene Periode** des Rechtsträgers verwendet (Buchungsdatum dann deren Beginn).
 */
data class ReverseCostEvent(val reason: String, val creditGrossMinor: Long? = null, val accountingDate: LocalDate? = null)

/** Ergebnis: das Gegenereignis (negative Beträge, `reversesId` = Ursprung), sein gebuchtes Journal (Gegenbuchung mit getauschten Seiten, nur positive Zeilen) und ggf. die negierte Aufteilung. */
data class ReversalResult(val event: CostEventView, val journal: JournalView, val allocation: AllocationView?)

/**
 * Korrektur gebuchter Kostenereignisse (WP-M4-36, Leitlinie 5: Korrektur nur per Gegenereignis). Das Ursprungsereignis und sein Journal bleiben unverändert (auch in einer geschlossenen Periode); das Gegenereignis
 * ist selbst ein unveränderliches `economic_event` mit `reverses_id`, hat ein eigenes Journal in der nächsten offenen Periode und kehrt die gespeicherten Teilbeträge der aktiven Aufteilung des Ursprungs negativ um.
 * Ein zweites Gegenereignis zum selben Ursprung und Betrag ist eine Dublette (409 `ALREADY_REVERSED`, einmalige Wirkung); die Summe aller Gegenereignisse überschreitet den Ursprung nie (422).
 * Recht: Scope `costs:reverse` und Finanzrolle. 404 fremder Mandant oder unbekanntes Ereignis; 409 `CONFLICT` ohne offene Periode oder bei Gegenereignissen und nicht gebuchten Ereignissen als Ursprung.
 */
interface CostEventCorrections {
    fun reverse(ctx: RequestContext, eventId: UUID, cmd: ReverseCostEvent): ReversalResult
}
