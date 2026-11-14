package de.taktovia.analytics.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Verursachungskategorie (Arbeitsvorschlag, OP 30): Betreiber, Fahrgast, extern. Nie eine Einzelperson: es gibt keine Fahrerzuordnung in der Kategorie (keine automatische Schuldzuweisung). */
enum class CauseCategory { OPERATOR, PASSENGER, EXTERNAL }

/** Zustand der Ursache einer nicht durchgeführten Fahrt: `OPEN` = Ursache offen (sichtbar, nie ausgeschlossen), `SET` abgeleitet, `CORRECTED` korrigiert (Vermerke bleiben), `CONFLICT` widersprüchliche Ereignisse (keine stille Wahl). */
enum class CauseState { OPEN, SET, CORRECTED, CONFLICT }

/** Eintrag des Ursachenkatalogs (Tabelle `not_performed_cause`, gültig von/bis). */
data class NotPerformedCause(val code: String, val category: CauseCategory, val label: String, val derivedFrom: String, val validFrom: Instant?, val validTo: Instant?)

/** Korrekturvermerk (append-only): alter und neuer Wert mit Akteur, Grund und Zeit; [no] zählt je Fahrt ab 1. */
data class CauseCorrection(
    val no: Int, val oldCause: String?, val oldCategory: CauseCategory?, val newCause: String, val newCategory: CauseCategory, val reason: String, val actor: String, val at: Instant,
)

/**
 * Fakt „Fahrt nicht durchgeführt“: eine bestätigte Buchung, die storniert wurde oder bei der der Fahrgast nicht erschien. [cause]/[category] sind die **wirksame** Ursache (gesetzt oder korrigiert), [derivedCause] die aus
 * den Ereignissen abgeleitete. [revision] steigt mit jeder Änderung der Ursache (Versionsprüfung der Korrektur).
 */
data class NotPerformedTrip(
    val bookingId: UUID, val serviceId: UUID?, val confirmedAt: Instant, val notPerformedAt: Instant, val state: CauseState, val cause: String?, val category: CauseCategory?, val derivedCause: String?,
    val revision: Long, val corrections: List<CauseCorrection> = emptyList(),
)

/** Zähler einer Ursache im Bericht. */
data class CauseCount(val cause: String, val category: CauseCategory, val label: String, val count: Long)

/** Quote mit Zähler und Nenner; [value] ist `null` mit [reason] bei Nullnenner (nie 0 oder 100 %). */
data class Rate(val numerator: Long, val denominator: Long, val value: BigDecimal?, val reason: MetricReason?)

/**
 * Auswertung nicht durchgeführter Fahrten (KPI-009) für einen Zeitraum: nach Ursache und Kategorie, mit „Ursache offen“ und Konflikten sichtbar ([open], [conflict]), den Nennern und Quoten (`No-Show-Rate`, `Buchungsstornoquote
 * Betreiber` mit Fahrzeugausfall getrennt) und dem Datenstand. Zeitbezug: geplante Abholung (`pickup_earliest`, sonst Zeitpunkt der Nichtdurchführung), Betriebstag in der `reporting_timezone`.
 * [confirmedPickups] = bestätigte, nicht stornierte Buchungen mit geplanter Leistung (Nenner der No-Show-Rate), [confirmedBookings] = alle bestätigten Buchungen (Nenner der Betreiberstornoquote).
 */
data class NotPerformedReport(
    val period: ReportingPeriod, val filter: MetricFilter, val total: Long, val byCause: List<CauseCount>, val byCategory: Map<CauseCategory, Long>, val open: Long, val conflict: Long,
    val confirmedPickups: Long, val confirmedBookings: Long, val noShowRate: Rate, val operatorCancellationRate: Rate, val vehicleOutageRate: Rate,
    val state: MetricState, val coverage: BigDecimal?, val asOf: Instant?, val sourceEvents: Long, val definitionVersion: Int = 1,
)

/** Korrektur der Ursache: [newCause] aus dem Katalog, [reason] Pflicht (3 bis 500 Zeichen), [ifMatch] optional = erwartete Revision der Fahrt (veraltet: 412); ohne [ifMatch] werden gleichzeitige Korrekturen der Reihe nach vermerkt. */
data class CorrectCause(val newCause: String, val reason: String, val ifMatch: Long? = null)

/**
 * Nicht durchgeführte Fahrten mit Ursache und Verursachungskategorie (WP-M4-55, LH-I.6-KPI-009). Der Fakt entsteht mit der Faktenprojektion (jede Neuableitung einer Buchung leitet ihre Nichtdurchführung neu ab) und ist
 * aus den Ereignissen wiederaufbaubar; Korrekturvermerke bleiben beim Wiederaufbau erhalten.
 *
 * - **Katalog:** Fahrzeugausfall (Betreiber) und Fahrgast-No-Show (Fahrgast) sind verschiedene Ursachen mit verschiedener Kategorie; Storno nach Grund (Fahrgast, Betreiber, Sicherheit, Zahlung); extern nur per Korrektur.
 * - **Genau eine Ursache:** jede nicht durchgeführte bestätigte Fahrt hat eine wirksame Ursache oder steht als „Ursache offen“ bzw. „Konflikt“ in der Auswertung (nie ausgeschlossen).
 * - **Korrektur** ([correct], Rolle Disposition oder Administration mit Scope `analytics:write`, sonst 403; ohne Grund 422): der alte Wert bleibt als Vermerk mit Akteur und Zeit; zwei parallele Korrekturen werden unter der
 *   Zeilensperre der Fahrt nacheinander vermerkt (beide Vermerke bleiben, der letzte Stand ist eindeutig); mit [CorrectCause.ifMatch] weist die Versionsprüfung eine veraltete Korrektur mit 412 ab. Fahrt eines fremden Mandanten: 404.
 */
interface NotPerformedTrips {
    /** Ursachenkatalog (gültig von/bis). */
    fun causes(tenant: TenantId): List<NotPerformedCause>

    /** Auswertung nach Ursache und Kategorie mit Nennern und Datenstand. */
    fun report(tenant: TenantId, period: ReportingPeriod, filter: MetricFilter = MetricFilter()): NotPerformedReport

    /** Nicht durchgeführte Fahrten des Zeitraums (auch „Ursache offen“) nach Zeit; [onlyOpen] zeigt nur offene und widersprüchliche. */
    fun trips(tenant: TenantId, period: ReportingPeriod, filter: MetricFilter = MetricFilter(), onlyOpen: Boolean = false, limit: Int = 200): List<NotPerformedTrip>

    /** Eine Fahrt mit Korrekturhistorie; unbekannt oder nicht nicht-durchgeführt: 404. */
    fun get(tenant: TenantId, bookingId: UUID): NotPerformedTrip

    fun correct(ctx: RequestContext, bookingId: UUID, cmd: CorrectCause): NotPerformedTrip
}
