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
    val ALL: Set<String> = setOf(SERVICE_ID, CHANNEL)
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

/** Filter einer Abfrage. Jede gesetzte Dimension muss in der Definition der Kennzahl zulässig sein. */
data class MetricFilter(val serviceId: UUID? = null, val channel: String? = null) {
    init { require(channel == null || channel in MetricDimensions.CHANNELS) { "Kanal unbekannt: $channel" } }
    fun dimensions(): Set<String> = buildSet { if (serviceId != null) add(MetricDimensions.SERVICE_ID); if (channel != null) add(MetricDimensions.CHANNEL) }
    /** Kanonische Form (sortierte Schlüssel) für Snapshot und Hash. */
    fun toMap(): Map<String, String> = buildMap { channel?.let { put(MetricDimensions.CHANNEL, it) }; serviceId?.let { put(MetricDimensions.SERVICE_ID, it.toString()) } }
}

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

    /** Werte der Kennzahl(en) für Zeitraum und Filter, nach Kennzahl-ID sortiert. */
    fun evaluate(tenant: TenantId, query: MetricQuery): List<MetricValue>

    /** Ablehnungen nach Grund und Gruppe: Primär getrennt von Sekundär; [reasonCode] filtert auf einen Katalogcode (unbekannt: 400). */
    fun rejections(tenant: TenantId, period: ReportingPeriod, filter: MetricFilter = MetricFilter(), reasonCode: String? = null): RejectionReport

    /** Legt eine Änderung als Entwurf der nächsten Version an; die Definition gilt erst nach [approve]. Liefert die neue Versionsnummer. */
    fun propose(tenant: TenantId, metricId: String, change: DefinitionChange): Int

    /** Gibt einen Entwurf frei (danach unveränderlich). Fehlt ein Pflichtfeld, scheitert die Freigabe mit 422. */
    fun approve(tenant: TenantId, metricId: String, version: Int, approvedBy: String)

    /** Schwelle, unter der Gruppen nicht ausgewiesen werden (Testwert bis zum Beschluss, WP-M4-46 Offene Punkte). */
    val smallGroupThreshold: Int
}
