package de.taktovia.stafftime.api

import de.taktovia.platform.context.RequestContext
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Planungsparameter der Fahrerschicht. Die Antrittstoleranz ist kein Katalogparameter (`kataloge.csv`), sondern eine Planungsannahme (WP-M3-25, OP-07). */
data class StafftimeSettings(
    /** Frühester Dienstantritt vor Schichtbeginn (Planungsdefault 30 Minuten). Ein Antritt ab `ends_at` ist nie möglich. */
    val shiftStartTolerance: Duration = Duration.ofMinutes(30),
    /** Schwelle des Standortalters: ab Überschreiten gilt die letzte Position als veraltet (`position_stale_after_sec`, Planungsdefault 30 s, OP-04, Kap. 08 §8.6). */
    val positionStaleAfter: Duration = Duration.ofSeconds(30),
    /** Toleranz für Messzeiten in der Zukunft (Uhrenabweichung des Geräts); weiter in der Zukunft liegende Messzeiten werden abgewiesen. */
    val positionFutureTolerance: Duration = Duration.ofSeconds(5),
    /** In Produktion wird nur erfasst, wenn für `LK_STANDORT` eine freigegebene Aufbewahrungsregel besteht (WP-M3-34 Kriterium 5, D-10, D-11). */
    val positionRequiresRetentionRule: Boolean = false,
    /** Zulässige Uhrabweichung zwischen Gerät und Server für Fahrerereignisse (WP-M3-29, Planungsannahme: das Pflichtenheft legt sie nicht fest; kein Katalogparameter). */
    val clockSkewTolerance: Duration = Duration.ofMinutes(2),
    /** Wartefrist des Nicht-Erscheinens, wenn die Konfiguration keinen Wert nennt (Planungsannahme 10 Minuten, `no_show_grace_min` ist 1 bis 120). */
    val noShowGraceDefault: Duration = Duration.ofMinutes(10),
    /** Boarding-Code (WP-M3-30, Planungsannahmen OP-18, kein Katalogparameter): Gültigkeit ab Ausstellung, Fehlversuche je Buchung bis zur Sperre und optionaler serverseitiger Pfeffer des Hashs (aus der Umgebung, nie im Repository). */
    val boardingCodeValidity: Duration = Duration.ofHours(24),
    val boardingCodeMaxAttempts: Int = 5,
    val boardingCodePepper: String = "",
    /** Ab dieser Zeitverschiebung eines unveränderten Stopps ist die Änderung bestätigungspflichtig, darunter wird sie angezeigt und automatisch übernommen (`plan_ack_time_shift_min`, Planungsdefault 5 min, WP-M3-27). */
    val planAckTimeShift: Duration = Duration.ofMinutes(5),
    /** Hinweis an die Leitstelle, wenn eine bestätigungspflichtige Revision so lange unbestätigt ist (`plan_ack_overdue_min`, Planungsdefault 5 min, WP-M3-27). */
    val planAckOverdue: Duration = Duration.ofMinutes(5),
) {
    init {
        require(!shiftStartTolerance.isNegative && shiftStartTolerance <= Duration.ofHours(12)) { "Antrittstoleranz außerhalb 0..12 h" }
        require(positionStaleAfter.seconds in 1..3600) { "Schwelle des Standortalters außerhalb 1..3600 s" }
        require(!positionFutureTolerance.isNegative && positionFutureTolerance <= Duration.ofMinutes(5)) { "Zukunftstoleranz außerhalb 0..5 min" }
        require(!clockSkewTolerance.isNegative && clockSkewTolerance <= Duration.ofMinutes(30)) { "Uhrabweichung außerhalb 0..30 min" }
        require(noShowGraceDefault.toMinutes() in 1..120) { "Wartefrist außerhalb 1..120 min" }
        require(boardingCodeValidity.toMinutes() in 1..(7 * 24 * 60)) { "Gültigkeit des Boarding-Codes außerhalb 1 min..7 Tage" }
        require(boardingCodeMaxAttempts in 1..100) { "Fehlversuchsgrenze außerhalb 1..100" }
        require(planAckTimeShift >= Duration.ofMinutes(1) && planAckTimeShift <= Duration.ofHours(1)) { "Zeitverschiebungsschwelle außerhalb 1..60 min" }
        require(!planAckOverdue.isNegative && planAckOverdue <= Duration.ofHours(1)) { "Überfälligkeit der Bestätigung außerhalb 0..60 min" }
    }
}

/** Die eigene Schicht des Fahrers, wie UI-09 sie zeigt („Nächster Dienst 06:00–14:00 · Fahrzeug A-12“). [valid] = angetreten und innerhalb von `[starts_at, ends_at)`. */
data class DriverShiftView(
    val shiftId: UUID, val vehicleId: UUID, val vehicleLabel: String?, val startsAt: Instant, val endsAt: Instant, val timezone: String, val state: String,
    val startableFrom: Instant, val startedAt: Instant?, val endedAt: Instant?, val valid: Boolean,
)

/** Dienstantritt: bestätigt die Zuordnung Fahrer, Schicht, Fahrzeug an einem registrierten Gerät. Der Mandant steht nie im Body. */
data class StartShiftCommand(val shiftId: UUID, val vehicleId: UUID, val deviceId: UUID)

data class EndShiftCommand(val shiftId: UUID, val deviceId: UUID)

/** [alreadyStarted] ist wahr, wenn die Schicht schon (auch von einem anderen Gerät desselben Fahrers) angetreten war: der Zustand bleibt unverändert, es entsteht kein zweiter Antritt. */
data class ShiftStartResult(val shift: DriverShiftView, val alreadyStarted: Boolean)

/** Freigabestand des Fahrzeugs für diese Schicht, wie ihn der Server aus `resource_release` und `vehicle` ableitet (nur Lesesicht; Entscheidung und Pflege gehören WP-M3-08/-09). */
enum class RunReleaseState { NOT_RELEASED, RELEASED, BLOCKED }

/**
 * Ressourcenbedarf der Einsteiger eines Stopps (Sitze, Rollstuhlplätze, Ausstattung); [equipment] sind Katalogcodes der Fahrzeugausstattung.
 */
data class DriverResourceNeed(val seats: Int, val wheelchair: Int, val equipment: List<String>)

/**
 * Notwendige Unterstützung an einem Stopp (WP-M3-32, LH-6.6-DRV-008): nur die transportrelevanten Merkmale Rollstuhlbedarf und Ein-/Ausstiegshilfe, keine Diagnosen. Die Werte stehen nie
 * in `toString` (Logs, Crashreports); sichtbar sind sie nur nach der Freigabe in `JdbcDriverShifts` (eigene, angetretene, freigegebene Schicht, aktueller Plan, Fenster offen).
 */
class DriverAssistance(val wheelchairRequired: Boolean, val boardingAssist: Boolean, val alightingAssist: Boolean) {
    override fun toString() = "DriverAssistance(redacted)"
    override fun equals(other: Any?) = other is DriverAssistance && other.wheelchairRequired == wheelchairRequired && other.boardingAssist == boardingAssist && other.alightingAssist == alightingAssist
    override fun hashCode() = listOf(wheelchairRequired, boardingAssist, alightingAssist).hashCode()
}

/**
 * Ein Stopp der Fahrerprojektion (Vertrag `DriverRunStop`, Schema `driver-run-projection.v1`): nur Haltepunkt, Zeiten, Personenzahl, Ressourcenbedarf und (freigegeben) Unterstützung.
 * [forecastAt] = `null` heißt „keine Prognose“ (es wird keine Zeit erfunden). [passengerCount] ist die Zahl der Ein- und Aussteiger zusammen.
 */
/** Erwarteter Fahrgast an einem Stopp (Fahrgastliste, WP-M3-30): Buchungskürzel (Buchungsnummer) und Personenzahl; keine Kontakt-, Zahlungs- oder Codedaten. */
data class ExpectedPassenger(val bookingId: UUID, val bookingRef: String?, val persons: Int)

data class DriverRunStopView(
    val routeStopId: UUID, val sequence: Int, val kind: String, val plannedAt: Instant, val forecastAt: Instant?, val stopName: String, val longitude: Double, val latitude: Double,
    val passengerCount: Int, val bookingIds: List<UUID>, val change: String?, val supportHints: List<String>,
    val stopRevisionId: UUID? = null, val boardingCount: Int = 0, val alightingCount: Int = 0, val resourceNeed: DriverResourceNeed? = null, val assistance: DriverAssistance? = null,
    val boardingPassengers: List<ExpectedPassenger> = emptyList(),
    /** Bei `change == "MOVED"`: die Planzeit der Vergleichsbasis (WP-M3-27). */
    val previousPlannedAt: Instant? = null,
)

/** [runState] ist der Zustand des Umlaufs (`COMPLETED`/`CANCELED` schließen das Sichtbarkeitsfenster der Unterstützungsmerkmale); `null` = unbekannt (Quelle ohne Zustand). */
data class DriverRunProjection(val planRevision: Long, val stops: List<DriverRunStopView>, val runState: String? = null)

/**
 * Quelle der Stoppliste (Run-Lesesicht über die Modul-API von `booking-dispatch`, WP-M3-26). Wird **nur** gefragt, wenn der Server die Schicht als gültig, angetreten und
 * freigegeben erkannt hat; sonst gibt es keine ausführbaren Aufträge (DRV-001). Ohne Quelle (Standard bis WP-M3-26) ist die Stoppliste leer.
 */
fun interface DriverRunSource {
    fun projection(ctx: RequestContext, shiftId: UUID, vehicleId: UUID): DriverRunProjection?

    /**
     * Plan einer bestimmten Revision (Vergleichsbasis der Änderungsanzeige und Überwachung unbestätigter Revisionen, WP-M3-27); `null` = unbekannt (dann gibt es keine Änderungsanzeige, nie eine erfundene).
     * Ohne Personenkontext und ohne Merkmale der Unterstützung: nur für den serverseitigen Vergleich, nie für die Auslieferung an den Client.
     */
    fun revision(tenant: de.taktovia.platform.tenant.TenantId, shiftId: UUID, vehicleId: UUID, revision: Long): DriverRunProjection? = null
}

data class DriverRunView(
    val shiftId: UUID, val vehicleId: UUID, val shiftState: String, val shiftValid: Boolean, val releaseState: RunReleaseState, val missingRequirements: List<String>,
    val planRevision: Long?, val planAsOf: Instant, val stops: List<DriverRunStopView>, val acknowledgedRevision: Long?,
    /** Status der Revision aus Sicht des Fahrers (WP-M3-27): abgeleitet aus Plan und bestätigter Revision. */
    val revisionState: PlanRevisionState = PlanRevisionState.CURRENT,
    /** Wahr, wenn die Änderung bestätigungspflichtig und noch nicht bestätigt ist; die App verlangt die Bestätigung beim nächsten Halt, nie während der Fahrbewegung. */
    val acknowledgementRequired: Boolean = false,
    /** Revision der Vergleichsbasis (zuletzt bestätigt, sonst die erste Planrevision), gegen die [stops] und [removedStops] verglichen wurden. */
    val baseRevision: Long? = null,
    val removedStops: List<DriverRemovedStop> = emptyList(),
)

/**
 * Dienstanmeldung und Schicht-/Fahrzeugzuordnung des Fahrers (WP-M3-25, LH-6.6-DRV-001, Modul `staff-time`). Nur der Fahrer (Rolle `driver`, Scope `driver:run`, menschlicher Akteur
 * mit Sitzung) handelt; „eigene Schicht“ heißt: die Schicht gehört dem Fahrer des Kontos ([de.taktovia.stafftime.internal] `ShiftOwnership`); fremde, unbekannte und Schichten anderer
 * Mandanten sind gleichermaßen 404. Der Antritt ist weder Arbeitszeiterfassung (M4) noch Einsatzfreigabe (WP-M3-09): er setzt `staff_shift.state` von geplant auf angetreten, mit
 * Gerät und Zeit in der Zeile und im `audit_event`.
 *
 * Antritt: nur für Schichten im Zustand `PLANNED`/`PUBLISHED`, ab `starts_at - Toleranz` bis vor `ends_at` (409 `INVALID_STATE_TRANSITION` sonst), mit dem Fahrzeug der Schicht
 * (abweichend: 422, bei bereits angetretener Schicht 409), an einem Gerät des Kontos, das an die aktuelle Sitzung gebunden und nicht beendet ist (403 sonst). Wiederholung und das
 * gleichzeitige Antreten eines zweiten Geräts liefern den bestehenden Zustand (`alreadyStarted`); es gibt genau einen Antritt.
 *
 * Run: liefert Aufträge nur, wenn die Schicht angetreten, innerhalb ihrer Zeit, nicht entzogen **und** das Fahrzeug nach `resource_release` freigegeben ist; sonst bleibt die Stoppliste
 * leer (mit Grund). Ein abgelaufener oder entzogener Dienst des Fahrers ist 200 mit Leerzustand, nicht 404.
 */
interface DriverShifts {
    /** Eigene, noch nicht beendete und nicht entzogene Schichten, deren Ende in der Zukunft liegt, nach Beginn. */
    fun mine(ctx: RequestContext): List<DriverShiftView>

    fun start(ctx: RequestContext, cmd: StartShiftCommand): ShiftStartResult

    fun end(ctx: RequestContext, cmd: EndShiftCommand): DriverShiftView

    fun run(ctx: RequestContext, shiftId: UUID): DriverRunView

    /**
     * Fahrgastliste (WP-M3-30, LH-6.6-DRV-006): die noch erwarteten Fahrgäste des aktuellen und des nächsten Stopps der eigenen Schicht. Aktuell ist der zuletzt per Ankunft gemeldete
     * Stopp (ohne Ankunft der erste); bereits eingestiegene oder als No-Show erfasste Buchungen fehlen. Nur Buchungskürzel und Personenzahl. Fremde Schicht: 404; Schicht ohne Aufträge
     * (nicht angetreten, nicht freigegeben): leere Liste.
     */
    fun expectedPassengers(ctx: RequestContext, shiftId: UUID): ExpectedPassengersView
     * Kennungen der eigenen, noch nicht beendeten Schichten (geplant, veröffentlicht, angetreten; Ende in der Zukunft): die Berechtigungsgrundlage des Live-Kanals des Fahrers
     * (WP-M3-35). Wird bei jedem Abruf des Kanals neu bestimmt; eine neu zugewiesene oder entzogene Schicht wirkt damit sofort.
     */
    fun liveShiftIds(ctx: RequestContext): Set<UUID>
}

/** [currentStopId] ist der aktuelle Stopp (`null`, wenn die Schicht keine Stopps hat); [entries] sind nach Stopp-Reihenfolge sortiert, [next] kennzeichnet den nächsten Stopp. */
data class ExpectedPassengersView(val shiftId: UUID, val planRevision: Long?, val currentStopId: UUID?, val entries: List<ExpectedPassengerAtStop>)

data class ExpectedPassengerAtStop(val routeStopId: UUID, val next: Boolean, val passenger: ExpectedPassenger)
