package de.taktovia.bookingdispatch.api

import de.taktovia.platform.context.RequestContext
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

/*
 * Regelkonforme Prognose (WP-M4-62, LH-I.3-ETA-002): Referenzfahrzeit + Haltezeiten + kalibrierter Unsicherheitspuffer, die drei Anteile je Prognose eigenständig ausgewiesen.
 */

/** Ausfallmodell (`eta_fallback_policy`; Werte im Pflichtenheft nicht festgelegt, Planungsannahme). Beide Werte sind fahrerneutral. */
enum class EtaFallbackPolicy { PLAN_TIME, REFERENCE_FORECAST }

/**
 * Wirksame Parameter einer Prognose. [safeSpeedToleranceKmh] (`eta_safe_speed_tolerance`) ist der Messtoleranzwert für den Tempovergleich mit GPS-Daten und **kein Freibrief**:
 * er erweitert die zulässige Geschwindigkeit der Prognose nie.
 */
data class EtaSettings(
    val stopDwellSec: Int, val wheelchairBoardingSec: Int, val quantile: EtaQuantile, val safeSpeedToleranceKmh: Int, val maxDataAgeSec: Int, val fallbackPolicy: EtaFallbackPolicy,
)

/**
 * Konsistenter Stand von Modellrevision und wirksamer Konfiguration, einmal je Prognose gelesen (eine Transaktion). [configVersion] benennt genau diesen Stand
 * (Modellversion und Hash der beteiligten Konfigurationsrevisionen); [sources] nennt je Parameter die Konfigurationsrevision (`revision:<id>`) oder `default`/`model`.
 */
data class EtaConfigSnapshot(val model: EtaModelRevisionView, val settings: EtaSettings, val configVersion: String, val sources: Map<String, String>, val resolvedAt: Instant)

/** Kein Snapshot möglich (heute nur: keine freigegebene Modellrevision). */
class EtaUnavailableException(val reason: EtaFallbackReason) : RuntimeException("ETA nicht verfügbar: $reason")

/** Ein Stopp der Prognosekette; [wheelchairHandling] verlängert den Aufenthalt um `wheelchair_boarding_sec`. */
data class EtaForecastStop(val stopKey: String, val wheelchairHandling: Boolean = false)

/** Prognose über eine Stoppkette. [startAt] ist die Ankunft am ersten Stopp. Keine Fahrer-Angabe. */
data class EtaForecastRequest(
    val stops: List<EtaForecastStop>, val vehicleProfile: String, val startAt: Instant, val serviceId: UUID? = null, val vehicleId: UUID? = null, val roadDataVersion: String? = null,
) { init { require(stops.size >= 2) { "mindestens zwei Stopps" } } }

data class EtaStopForecast(
    val seq: Int, val stopKey: String, val arrivalEarliest: Instant, val arrivalLatest: Instant, val dwellSeconds: Int, val wheelchairBoardingSeconds: Int,
    val departureEarliest: Instant, val departureLatest: Instant,
)

data class EtaLegForecast(val index: Int, val referenceSeconds: Int, val legalMinimumSeconds: Int, val bufferSeconds: Int, val uncertaintySeconds: Int, val distanceMeters: Int)

/**
 * Prognose mit den Anteilen [referenceSeconds], [dwellSeconds] und [bufferSeconds] (Summen über alle Abschnitte bzw. Stopps) sowie [uncertaintySeconds] (Spannweite der Reserve bis p90)
 * und dem verwendeten [quantile]. Das Zeitfenster eines Stopps reicht von der frühesten Ankunft (Referenz plus Haltezeiten, nie schneller als erlaubt) bis zur spätesten (plus Reserve).
 * Bei [fallbackReason] ist keine Prognose ausgegeben (kein Zeitfenster).
 */
data class EtaForecast(
    val stops: List<EtaStopForecast>, val legs: List<EtaLegForecast>, val referenceSeconds: Int, val dwellSeconds: Int, val bufferSeconds: Int, val uncertaintySeconds: Int,
    val quantile: EtaQuantile?, val modelVersion: Int?, val configVersion: String?, val roadDataVersion: String?, val settings: EtaSettings?, val fallbackReason: EtaFallbackReason?,
) { init { require((fallbackReason == null) == stops.isNotEmpty()) { "Zeitfenster und Fallbackgrund schließen sich aus" } } }

interface EtaForecaster {
    /** Liest Modellrevision und wirksame Konfiguration für Dienst/Fahrzeug zum Zeitpunkt [at] in einer Transaktion; [EtaUnavailableException] ohne freigegebene Revision. */
    fun snapshot(ctx: RequestContext, serviceId: UUID?, vehicleId: UUID?, at: Instant): EtaConfigSnapshot

    /** Prognose mit einem eigenen Snapshot (jede Prognose trägt genau eine Modell-/Konfigurationsversion). */
    fun forecast(ctx: RequestContext, request: EtaForecastRequest): EtaForecast

    /** Prognose mit einem vorab gelesenen Snapshot (der Aufrufer sieht dieselbe Version für Entscheidung und Prognose). */
    fun forecast(ctx: RequestContext, request: EtaForecastRequest, snapshot: EtaConfigSnapshot): EtaForecast
}

/**
 * Prüfroutine „keine Geschwindigkeitsüberschreitung“ (LH-I.3-ETA-002): die zulässige Geschwindigkeit je Abschnitt stammt aus den Routendaten; die Toleranz
 * `eta_safe_speed_tolerance` geht hier nie ein. Ein für die Strecke zugeteiltes Zeitfenster ist regelkonform, wenn es mindestens die Summe der Mindestfahrzeiten
 * `Länge / Limit` aller Abschnitte beträgt; verteilt man es proportional auf die Abschnitte, liegt dann auch jeder Abschnitt unter seinem Limit. Alles exakt in Brüchen gerechnet.
 */
object EtaSpeedRules {
    /** Exakte Mindestfahrzeit in Sekunden als Bruch `Zähler/Nenner`; `null`, wenn ein Abschnitt kein positives Limit trägt. */
    private fun exactMinimum(segments: List<EtaRoadSegment>): Pair<BigInteger, BigInteger>? {
        var num = BigInteger.ZERO; var den = BigInteger.ONE
        for (s in segments) {
            val limit = s.speedLimitKmh ?: return null
            if (limit <= 0 || s.lengthMeters < 0) return null
            // Länge m / (Limit km/h / 3,6) = Länge * 36 / (Limit * 10) Sekunden
            val n = BigInteger.valueOf(s.lengthMeters.toLong() * 36); val d = BigInteger.valueOf(limit.toLong() * 10)
            num = num * d + n * den; den *= d
            val g = num.gcd(den); if (g.signum() > 0) { num /= g; den /= g }
        }
        return num to den
    }

    /** Rechtmäßige Mindestfahrzeit (aufgerundet auf ganze Sekunden) oder `null`, wenn ein Abschnitt kein Limit trägt. */
    fun legalMinimumSeconds(segments: List<EtaRoadSegment>): Int? {
        val (n, d) = exactMinimum(segments) ?: return null
        return ((n + d - BigInteger.ONE) / d).toInt()
    }

    /** Indizes der Abschnitte, deren implizite Geschwindigkeit bei [allottedSeconds] für die ganze Strecke ihr Limit überschreiten würde (leer = regelkonform; ohne Limits: alle Abschnitte ohne Limit). */
    fun violations(segments: List<EtaRoadSegment>, allottedSeconds: Int): List<Int> {
        val missing = segments.indices.filter { (segments[it].speedLimitKmh ?: 0) <= 0 }
        if (missing.isNotEmpty()) return missing
        val (n, d) = exactMinimum(segments) ?: return segments.indices.toList()
        // allotted < n/d  <=>  allotted * d < n; dann gilt es für jeden (proportional bedienten) Abschnitt mit Länge > 0
        return if (BigInteger.valueOf(allottedSeconds.toLong()) * d < n) segments.indices.filter { segments[it].lengthMeters > 0 } else emptyList()
    }
}

/** Stopp-Schlüssel des Routings zu einer `stop_revision` des Fahrtenplans (der Plan trägt nur die Revisions-ID). */
fun interface EtaStopKeys {
    fun keyOf(ctx: RequestContext, stopRevisionId: UUID): String
}

/** Grundlage der ausgegebenen Zeit eines Stopps. */
enum class EtaBasis { LIVE_POSITION, PLAN_TIME, REFERENCE_FORECAST }

/** [promisedAt] ist die verbindliche Zusage (Planzeit) und bleibt unverändert; die Prognose [forecastEarliest]..[forecastLatest] ersetzt sie nie. */
data class EtaStopEta(val seq: Int, val stopRevisionId: UUID, val promisedAt: Instant, val forecastEarliest: Instant, val forecastLatest: Instant, val basis: EtaBasis)

/** ETA je Stopp einer laufenden Fahrt mit [dataStand] (Messzeit der letzten Position), Modell-/Konfigurationsversion und Fallbackgrund. */
data class EtaStopEtaReport(
    val runId: UUID, val planRevision: Long, val computedAt: Instant, val dataStand: Instant?, val stops: List<EtaStopEta>, val modelVersion: Int?, val configVersion: String?,
    val roadDataVersion: String?, val fallbackReason: EtaFallbackReason?,
)

/**
 * Leseport „ETA je Stopp“ für Fahrgastanzeige, Benachrichtigung und Leitstelle (WP-M2-34/-43, WP-M3-26/-38). Frische Position: Prognose ab dem ersten noch nicht erreichten Stopp [fromSeq]
 * (nicht vor der aktuellen Zeit). Fehlende oder ältere Position als `eta_max_data_age_sec`: Fallbackgrund und Rückfall laut `eta_fallback_policy`; die verbindliche Zusage bleibt unverändert.
 * Die Neuberechnung aus Live-Koordinaten (Routing von einer Fahrzeugposition zum nächsten Stopp) ist nicht gebaut (Offener Punkt 41, Routing kennt nur Haltepunkte).
 */
interface EtaStopEtaPort {
    fun etaPerStop(ctx: RequestContext, runId: UUID, vehicleProfile: String, serviceId: UUID?, fromSeq: Int = 0): EtaStopEtaReport
}
