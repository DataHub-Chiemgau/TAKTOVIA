package de.taktovia.analytics.api

import de.taktovia.platform.tenant.TenantId
import java.time.Instant
import java.util.UUID

/** Die fünf getrennt gespeicherten Zeitarten (LH-I.6-KPI-006). `ANNOUNCED` ist bis zur Festlegung von OP 28 nur über [TimeFacts.record] mit einem Testwert belegbar. */
enum class TimeKind { PLANNED, ANNOUNCED, PROMISED, FORECAST, ACTUAL }

/** Ereignis am Stopp einer Fahrgastfahrt: Abholung (Einstieg) oder Ankunft (Ausstieg am Ziel). */
enum class StopEvent { PICKUP, ARRIVAL }

/**
 * Ein anzuhängender Zeitfakt. Pflichtversion je Zeitart: `PROMISED` [promiseVersion] (Zusageversion; [value] = Zielzeit = Ende des Zusagefensters, [windowEarliest] = Beginn),
 * `PLANNED` [planRevision] (Revision des Plans; [sourceRef] = Umlauf), `FORECAST` [forecastVersion]; `ACTUAL` braucht [serverTime], und bei [offlineRecorded] zusätzlich [deviceTime].
 * Dieselbe Version derselben Zeitart derselben Fahrt wird nie ein zweites Mal gespeichert.
 */
data class TimeFactCommand(
    val bookingId: UUID, val stopEvent: StopEvent, val kind: TimeKind, val value: Instant, val source: String, val sourceRef: UUID,
    val windowEarliest: Instant? = null, val routeStopId: UUID? = null,
    val promiseVersion: Long? = null, val planRevision: Long? = null, val forecastVersion: Long? = null,
    val deviceTime: Instant? = null, val serverTime: Instant? = null, val offlineRecorded: Boolean = false,
) {
    init {
        require(source.matches(Regex("^[A-Z][A-Z0-9_]*$"))) { "source: GROSSBUCHSTABEN_UNTERSTRICH" }
        when (kind) {
            TimeKind.PROMISED -> require(promiseVersion != null && promiseVersion >= 1) { "PROMISED braucht promiseVersion" }
            TimeKind.PLANNED -> require(planRevision != null && planRevision >= 1) { "PLANNED braucht planRevision" }
            TimeKind.FORECAST -> require(forecastVersion != null && forecastVersion >= 1) { "FORECAST braucht forecastVersion" }
            TimeKind.ACTUAL -> require(serverTime != null && (!offlineRecorded || deviceTime != null)) { "ACTUAL braucht serverTime, offline auch deviceTime" }
            TimeKind.ANNOUNCED -> {}
        }
        require(kind == TimeKind.ACTUAL || (deviceTime == null && serverTime == null && !offlineRecorded)) { "Geräte- und Serverzeit nur bei ACTUAL" }
        require(kind == TimeKind.PROMISED || windowEarliest == null) { "windowEarliest nur bei PROMISED" }
        require(windowEarliest == null || !windowEarliest.isAfter(value)) { "windowEarliest nach value" }
        require(promiseVersion == null || kind == TimeKind.PROMISED || kind == TimeKind.ANNOUNCED) { "promiseVersion nur bei PROMISED und ANNOUNCED" }
        require(planRevision == null || kind == TimeKind.PLANNED) { "planRevision nur bei PLANNED" }
        require(forecastVersion == null || kind == TimeKind.FORECAST) { "forecastVersion nur bei FORECAST" }
    }
}

enum class RecordOutcome {
    /** Neue Zeile angehängt. */
    CREATED,
    /** Dieselbe Version mit demselben Wert lag schon vor: keine zweite Zeile. */
    DUPLICATE,
    /** Dieselbe Version liegt mit anderem Wert vor: nichts verändert (Zeitfakten werden nie überschrieben). */
    CONFLICT,
}

/** Gespeicherter Zeitfakt (Lesesicht). Bei `ACTUAL` sind [deviceTime] und [serverTime] getrennt erhalten; [value] ist die plausibilisierte Ist-Zeit. */
data class TimeFactView(
    val kind: TimeKind, val value: Instant, val windowEarliest: Instant?, val source: String, val sourceRef: UUID, val routeStopId: UUID?,
    val promiseVersion: Long?, val planRevision: Long?, val forecastVersion: Long?, val deviceTime: Instant?, val serverTime: Instant?, val offlineRecorded: Boolean, val recordedAt: Instant,
)

/** Grund, warum keine Verspätung bewertet werden kann (UI: „nicht bewertbar“; nie als 0 dargestellt). */
enum class NotAssessable { NO_ACTUAL, NO_PROMISE }

/**
 * Verspätung = Ist-Zeit minus Zielzeit der gebundenen Zusageversion ([promiseVersion], die höchste; eine einvernehmliche Änderung erzeugt eine neue Version). Geplante, angekündigte und prognostizierte
 * Zeiten gehen nie ein. Negativ = früher als zugesagt. [seconds] ist `null`, wenn [notAssessable] gesetzt ist.
 */
data class Delay(val seconds: Long?, val notAssessable: NotAssessable?, val promiseVersion: Long?, val targetAt: Instant?, val actualAt: Instant?)

/** Die fünf Zeitarten eines Stopps, je Zeitart alle Versionen in Anlagereihenfolge (die letzte ist die aktuelle). */
data class StopTimes(
    val stopEvent: StopEvent, val planned: List<TimeFactView>, val announced: List<TimeFactView>, val promised: List<TimeFactView>,
    val forecast: List<TimeFactView>, val actual: List<TimeFactView>, val delay: Delay,
)

/** Abruf je Fahrgastfahrt: beide Stopps, auch wenn noch nichts vorliegt (dann leer und „nicht bewertbar“, Grund `NO_PROMISE`). */
data class TripTimes(val bookingId: UUID, val pickup: StopTimes, val arrival: StopTimes)

/**
 * Zeitfakten (WP-M4-52, LH-I.6-KPI-006). Append-only (Tabelle `time_fact`, die Runtime-Rolle kann nur anhängen), je Mandant per RLS getrennt, ohne Fahrgast-Klartextdaten.
 * [consume] leitet Fakten aus Ereignissen ab (`booking.confirmed` -> Zusage Version aus der Aggregatrevision, `dispatch.run_revised` -> geplante Zeiten je Planrevision,
 * `driver.event_recorded` -> Ist-Abholung (Einstieg) und Ist-Ankunft (Ausstieg) mit Geräte- und Serverzeit); [record] nimmt die übrigen Quellen (Zusageänderung, Prognose, Ankündigung) auf.
 */
interface TimeFacts {
    fun consume(workerTenant: TenantId, envelopeJson: String): ConsumeOutcome

    fun record(tenant: TenantId, command: TimeFactCommand): RecordOutcome

    fun forBooking(tenant: TenantId, bookingId: UUID): TripTimes
}
