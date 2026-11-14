package de.taktovia.analytics.api

import de.taktovia.platform.tenant.TenantId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Art des Kennzahlwerts: Zähler (Absolutzahl), Quote (Zähler/Nenner), Mittelwert, Summe. */
enum class MetricValueKind { COUNT, RATIO, MEAN, SUM }

/** Zustand eines ausgewiesenen Werts: [OK] vollständig, [PROVISIONAL] unvollständige Quelle (mit Abdeckungsgrad), [NOT_COMPUTABLE] kein Wert (mit Grund), [SUPPRESSED] kleine Gruppe. */
enum class MetricState { OK, PROVISIONAL, NOT_COMPUTABLE, SUPPRESSED }

/** Grund, warum kein Wert ausgewiesen wird; nie ein Wert 0 oder 100 % an dessen Stelle. */
enum class MetricReason {
    ZERO_DENOMINATOR, MISSING_DENOMINATOR, NO_DATA, NOT_EXECUTABLE_IN_THIS_RELEASE, NO_PARTNERS_IN_P1, SMALL_GROUP,
}

/** Zulässige Filterdimensionen der Kennzahlen (Spalte `filter_dimensions` der Definition). */
object MetricDimensions {
    const val SERVICE_ID = "service_id"
    const val CHANNEL = "channel"
    /** Dimensionen der Energiekosten-Kennzahlen (WP-M4-43). */
    const val VEHICLE_ID = "vehicle_id"
    const val ENERGY = "energy"
    const val CURRENCY = "currency"
    val ALL: Set<String> = setOf(SERVICE_ID, CHANNEL, VEHICLE_ID, ENERGY, CURRENCY)
    val CHANNELS: Set<String> = setOf("WEB", "APP", "API")
}

/**
 * Freigegebene Definition einer Kennzahl in einer Version (Tabelle `metric_definition_revision`): [metricId] stabile technische ID, [numerator], [denominator], [exclusions], [timeBasis]
 * und [filterDimensions] sind Pflicht (eine freigegebene Definition hat sie alle), [executable] heißt: in diesem Stand berechenbar, sonst nennt [notExecutableReason] den Grund.
 */
data class MetricDefinition(
    val metricId: String, val version: Int, val name: String, val valueKind: MetricValueKind, val unit: String, val numerator: String, val denominator: String, val exclusions: String,
    val timeBasis: String, val periodBasis: String, val filterDimensions: List<String>, val source: FactSource?, val executable: Boolean, val notExecutableReason: MetricReason?, val approvedAt: Instant?,
)

/** Energieart der Energiekosten-Kennzahlen (WP-M4-43): Kraftstoff (Benzin, Diesel, Autogas; Einheit Liter) und Strom (kWh) werden nie zu einer Mengenkennzahl gemischt. */
enum class EnergyKind { FUEL, ELECTRIC }

/**
 * Filter einer Abfrage. Jede gesetzte Dimension muss in der Definition der Kennzahl zulässig sein. [vehicleId], [energy] und [currency] gelten nur für die Energiekosten-Kennzahlen (WP-M4-43):
 * ohne [energy] wird je Energieart ein eigener Wert ausgewiesen, ohne [currency] je vorkommender Währung.
 */
data class MetricFilter(val serviceId: UUID? = null, val channel: String? = null, val vehicleId: UUID? = null, val energy: EnergyKind? = null, val currency: String? = null) {
    init {
        require(channel == null || channel in MetricDimensions.CHANNELS) { "Kanal unbekannt: $channel" }
        require(currency == null || CURRENCY.matches(currency)) { "Währung ungültig: $currency" }
    }
    fun dimensions(): Set<String> = buildSet {
        if (serviceId != null) add(MetricDimensions.SERVICE_ID); if (channel != null) add(MetricDimensions.CHANNEL)
        if (vehicleId != null) add(MetricDimensions.VEHICLE_ID); if (energy != null) add(MetricDimensions.ENERGY); if (currency != null) add(MetricDimensions.CURRENCY)
    }
    /** Kanonische Form (sortierte Schlüssel) für Snapshot und Hash. */
    fun toMap(): Map<String, String> = buildMap {
        channel?.let { put(MetricDimensions.CHANNEL, it) }; currency?.let { put(MetricDimensions.CURRENCY, it) }; energy?.let { put(MetricDimensions.ENERGY, it.name) }
        serviceId?.let { put(MetricDimensions.SERVICE_ID, it.toString()) }; vehicleId?.let { put(MetricDimensions.VEHICLE_ID, it.toString()) }
    }
    private companion object { val CURRENCY = Regex("^[A-Z]{3}$") }
}

/** Grund, warum ein Tankvorgang nicht in eine Energiekosten-Kennzahl eingeht (WP-M4-43). [countsAgainstCoverage]: der Vorgang fehlt noch (senkt den Abdeckungsgrad), im Gegensatz zu storniert oder anderer Währung. */
enum class ExclusionReason(val countsAgainstCoverage: Boolean) {
    NOT_RELEASED(true), OPEN_HINT(true), MISSING_ENERGY_TYPE(true), MISSING_QUANTITY(true), MISSING_VEHICLE(true), MIXED_ENERGY_VEHICLE(true), REVERSED(false), OTHER_CURRENCY(false),
}

/** Ein nicht eingegangener Vorgang einer Energiekosten-Kennzahl: [documentId] des Tankbelegs, [vehicleId] (falls bekannt) und der [reason]. */
data class MetricExclusion(val documentId: UUID, val vehicleId: UUID?, val reason: ExclusionReason)

/** Kennzeichnung einer Näherung: [ACQUISITION_BASIS_P1] = Erwerbsnäherung ohne Bestandsführung und Ladeverluste (Kap. 03 §1.1). */
enum class MetricApproximation { ACQUISITION_BASIS_P1 }

/**
 * Berichtszeitraum als halboffenes Intervall [[from], [to]) in absoluten Zeitpunkten; entstanden aus Betriebstagen in der `reporting_timezone` ([zone]). Der Betriebstag ist lokal, Zeitpunkte sind absolut:
 * ein Tag mit Zeitumstellung hat 23 oder 25 Stunden (Europe/Berlin am 25.10.2026: 25), die Grenzen folgen der Zone, nie festen 24 Stunden.
 */
data class ReportingPeriod(val fromDay: LocalDate, val toDayInclusive: LocalDate, val zone: ZoneId, val from: Instant, val to: Instant) {
    companion object {
        fun ofDays(zone: ZoneId, fromDay: LocalDate, toDayInclusive: LocalDate): ReportingPeriod {
            require(!toDayInclusive.isBefore(fromDay)) { "Ende vor Beginn" }
            return ReportingPeriod(fromDay, toDayInclusive, zone, fromDay.atStartOfDay(zone).toInstant(), toDayInclusive.plusDays(1).atStartOfDay(zone).toInstant())
        }
    }
}

/** Abfrage von Kennzahlwerten: [metricId] `null` = alle registrierten Kennzahlen; [version] `null` = jüngste freigegebene Version. */
data class MetricQuery(val period: ReportingPeriod, val metricId: String? = null, val version: Int? = null, val filter: MetricFilter = MetricFilter())

/**
 * Ausgewiesener Kennzahlwert mit Definition und Version, Filter, Zeitraum, Zähler, Nenner, Datenstand und Vollständigkeit. [asOf] ist der Datenstand der Quelle (jüngste Ereigniszeit der verarbeiteten
 * Ereignisse), [sourceEvents] ihre Ereigniszahl; beide stammen aus demselben Lesestand wie der Wert. [coverage] ist der Abdeckungsgrad der Quelle (1 = vollständig). [snapshotId] verweist auf den
 * gespeicherten Snapshot (`null`, wenn die Kennzahl in diesem Stand nicht ausführbar ist).
 */
data class MetricValue(
    val definition: MetricDefinition, val state: MetricState, val reason: MetricReason?, val value: BigDecimal?, val numerator: BigDecimal?, val denominator: BigDecimal?, val coverage: BigDecimal?,
    val filter: MetricFilter, val period: ReportingPeriod, val asOf: Instant?, val sourceEvents: Long, val snapshotId: UUID?,
    /** Nur Energiekosten-Kennzahlen (WP-M4-43): nicht eingegangene Vorgänge mit Grund und die Kennzeichnung der Näherung. */
    val exclusions: List<MetricExclusion> = emptyList(), val approximation: MetricApproximation? = null,
)

/** Änderung einer Definition: nur die gesetzten Felder weichen von der jüngsten Version ab (neue Version im Entwurf, Freigabe getrennt). */
data class DefinitionChange(
    val name: String? = null, val numerator: String? = null, val denominator: String? = null, val exclusions: String? = null, val timeBasis: String? = null, val filterDimensions: List<String>? = null,
)

/** Eine Zeile der Ablehnungsstatistik: Katalogeintrag mit Zählern. [primaryCount] zählt Ablehnungen, [secondaryCount] nennt den Code als zusätzliches Label (keine zusätzliche Ablehnung). */
data class RejectionRow(
    val code: String, val group: String, val metric: String?, val text: String, val texts: Map<String, String>, val priority: Int, val primaryCount: Long, val secondaryCount: Long,
)

/**
 * Ablehnungsstatistik („Ablehnungsgrund“ als eine Berichtsspalte): [rejections] = Summe der Primärgründe = Zahl der abgelehnten Versuche, [technicalErrors] getrennt davon (keine fachliche Ablehnung),
 * [attempts] alle gültigen Versuche. [rows] nur Codes mit mindestens einem Treffer, nach Priorität. Datenstand und Abdeckung wie bei [MetricValue].
 */
data class RejectionReport(
    val period: ReportingPeriod, val filter: MetricFilter, val reasonCode: String?, val attempts: Long, val rejections: Long, val technicalErrors: Long, val rows: List<RejectionRow>,
    val state: MetricState, val coverage: BigDecimal?, val asOf: Instant?, val sourceEvents: Long,
)

/** Abgleich der Versuche (`travel_attempt`, Eigentümer booking-dispatch) gegen die Faktenbasis aus dem Ereignisprotokoll; leere Listen = kein Unterschied. */
data class AttemptReconciliation(
    val sourceAttempts: Long, val factAttempts: Long, val missingInFacts: List<UUID>, val missingInSource: List<UUID>, val mismatched: List<UUID>,
) { val consistent: Boolean get() = missingInFacts.isEmpty() && missingInSource.isEmpty() && mismatched.isEmpty() }

/**
 * Nachfragetrichter (WP-M4-51, KPI-005/ANA-001). Die **vier Zählgrößen** [requests], [offers], [confirmedBookings], [completedBookings] stehen getrennt nebeneinander und werden nie summiert (es gibt bewusst
 * kein Summenfeld). Zeitbezug je Größe ihr eigenes Ereignis: Anfrage (`requested_at`), Angebot angezeigt (`booking.offer_created`, OP 27), Buchung bestätigt (`booking.confirmed`), Buchung abgeschlossen
 * (Fahrtabschluss/Ausstieg, OP 27). Daneben die getrennten Endzustände: [requestRejected] (Anfrage abgelehnt), [requestTechnicalError], [offerNotBooked] (Angebot angezeigt, nicht gebucht),
 * [paymentFailed], [cancelledByPassenger], [cancelledByOperator], [cancelledOther] (Fahrzeugausfall, No-Show, System). Eine Buchung zählt je Endzustand genau einmal: ein Storno nach Abschluss ändert die
 * Stufe „abgeschlossen“ nicht, ein Zahlungsfehlschlag nie zusätzlich als Storno. [unlinkedBookings]: bestätigte Buchungen ohne Angebotsbezug (`offer_id` fehlt im Ereignis, M2-Erweiterung offen); solange es
 * sie gibt, ist [offerNotBooked] nur [MetricState.PROVISIONAL] belastbar. [asOf]/[sourceEvents]/[coverage] stammen aus demselben Lesestand wie die Zahlen.
 */
data class FunnelReport(
    val period: ReportingPeriod, val filter: MetricFilter,
    val requests: Long, val offers: Long, val confirmedBookings: Long, val completedBookings: Long,
    val requestRejected: Long, val requestTechnicalError: Long, val offerNotBooked: Long, val paymentFailed: Long, val cancelledByPassenger: Long, val cancelledByOperator: Long, val cancelledOther: Long,
    val unlinkedBookings: Long, val state: MetricState, val coverage: BigDecimal?, val asOf: Instant?, val sourceEvents: Long,
)

/** Eine Stufe des Abgleichs: Zahl der Fakten gegen die aus dem Ereignisprotokoll neu gezählte Zahl. */
data class FunnelStageDiff(val stage: String, val factCount: Long, val logCount: Long) { val difference: Long get() = factCount - logCount }

/** Abgleichbericht Trichter gegen Ereignisprotokoll; [consistent] heißt: Differenz je Stufe 0. */
data class FunnelReconciliation(val period: ReportingPeriod, val stages: List<FunnelStageDiff>) { val consistent: Boolean get() = stages.all { it.difference == 0L } }

/**
 * Kennzahlregister (WP-M4-46) mit den ausführbaren Versuchskennzahlen (WP-M4-47 bis -50). Die Berechnung liest Werte und Datenstand in **einem** Statement (ein Lesestand): parallele Abfragen während
 * der Projektion liefern je Antwort einen in sich stimmigen Datenstand, und dieselbe Abfrage auf demselben Datenstand dieselben Werte, unabhängig vom Benutzer (Rechte prüft der Aufrufer).
 * Jede Auswertung wird als Snapshot mit Definitionsversion festgehalten. Fehler: unbekannte Kennzahl oder Version [de.taktovia.platform.problem.ErrorCode.NOT_FOUND], nicht zulässige Filterdimension und unbekannter Code
 * [de.taktovia.platform.problem.ErrorCode.BAD_REQUEST].
 */
interface Metrics {
    /** Jüngste freigegebene Definition je Kennzahl (legt beim ersten Zugriff den Seed der P1-Kennzahlen an). */
    fun definitions(tenant: TenantId): List<MetricDefinition>

    /** Eine Definition in [version] (jüngste freigegebene, wenn `null`). */
    fun definition(tenant: TenantId, metricId: String, version: Int? = null): MetricDefinition

    /**
     * Werte der Kennzahl(en) für Zeitraum und Filter, nach Kennzahl-ID sortiert. [tolerances] löst den Grenzwert der Abholpünktlichkeit zum Vergleichsstichtag auf (WP-M4-53); ohne Quelle sind
     * Abholungen für die Pünktlichkeit nicht bewertbar (Abdeckung statt geratener Wert).
     */
    fun evaluate(tenant: TenantId, query: MetricQuery, tolerances: PickupToleranceSource = PickupToleranceSource.NONE): List<MetricValue>

    /** Ablehnungen nach Grund und Gruppe: Primär getrennt von Sekundär; [reasonCode] filtert auf einen Katalogcode (unbekannt: 400). */
    fun rejections(tenant: TenantId, period: ReportingPeriod, filter: MetricFilter = MetricFilter(), reasonCode: String? = null): RejectionReport

    /** Legt eine Änderung als Entwurf der nächsten Version an; die Definition gilt erst nach [approve]. Liefert die neue Versionsnummer. */
    fun propose(tenant: TenantId, metricId: String, change: DefinitionChange): Int

    /** Gibt einen Entwurf frei (danach unveränderlich). Fehlt ein Pflichtfeld, scheitert die Freigabe mit 422. */
    fun approve(tenant: TenantId, metricId: String, version: Int, approvedBy: String)

    /** Nachfragetrichter mit den vier getrennten Zählgrößen und den Endzuständen (WP-M4-51); nur der Filter `service_id` ist zulässig (Buchungen kennen keinen Kanal): `channel` ergibt 400. */
    fun funnel(tenant: TenantId, period: ReportingPeriod, filter: MetricFilter = MetricFilter()): FunnelReport

    /** Abgleich der Trichterzahlen gegen das Ereignisprotokoll des Zeitraums (WP-M4-51): meldet Unterschiede, ändert nichts. */
    fun reconcileFunnel(tenant: TenantId, period: ReportingPeriod): FunnelReconciliation

    /** Schwelle, unter der Gruppen nicht ausgewiesen werden (Testwert bis zum Beschluss, WP-M4-46 Offene Punkte). */
    val smallGroupThreshold: Int
}
