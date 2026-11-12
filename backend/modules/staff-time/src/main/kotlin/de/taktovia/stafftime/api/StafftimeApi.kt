package de.taktovia.stafftime.api

import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.stafftime.internal.StafftimeServicesFactory
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Öffentliche Ports des Moduls `staff-time`; wächst mit den Arbeitspaketen (bisher: Geräte-/Sitzungsbindung und Ereigniseingang der Fahrer-App, WP-M3-24). */
interface StafftimeApi {
    val driverDevices: DriverDevices
    val driverEvents: DriverEvents
    /** Abwesenheit als Verfügbarkeitsrestriktion (WP-M4-06). */
    val absences: Absences
    /** Ist-Arbeitszeit als Zeitereignisse (WP-M4-08). */
    val timeEvents: TimeEvents
    /** Soll/Ist je Person und Tag als Leseprojektion (WP-M4-09). */
    val timeTargetActual: TimeTargetActual

    /** Schichten eines Dienstplans mit Pausenplan, Qualifikation und Personalbedarf (WP-M4-03). */
    val rosterShifts: RosterShifts

    /** Dienstplan je Depot und Woche mit getrenntem Entwurf und veröffentlichtem Plan (WP-M4-04). */
    val rosters: Rosters

    /** Veröffentlichung des Dienstplans und Eigensicht der veröffentlichten Schichten (WP-M4-14). */
    val publishedRosters: PublishedRosters

    /** Revisionsliste, historische veröffentlichte Fassungen und Änderungsprotokoll je Schicht (WP-M4-15). */
    val rosterHistory: RosterHistory

    /** Depotberechtigung im Dienstplan (WP-M4-16): Vergabe, Entzug und Abfrage der Depotrechte. */
    val rosterDepotAccess: RosterDepotAccess

    /** Fahrerstamm und Qualifikationsrevisionen (WP-M3-05). */
    val drivers: Drivers

    /** Leseport Fahrer und Qualifikationen für M4 (WP-M4-02). */
    val driverReadPort: de.taktovia.platform.readports.DriverReadPort
    /** Schichtzuweisung der Leitstelle mit eindeutigen Schichtgrenzen (WP-M3-07). */
    val shifts: Shifts

    /** Dienstanmeldung und Schicht-/Fahrzeugzuordnung des Fahrers (WP-M3-25). */
    val driverShifts: DriverShifts

    /** Positionserfassung und Standortalter (WP-M3-34). */
    val positions: Positions

    companion object {
        fun create(
            ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, clock: Clock = Clock.systemUTC(), settings: StafftimeSettings = StafftimeSettings(), runSource: DriverRunSource? = null,
            workRules: WorkRuleSource = WorkRuleSource.DEFAULT,
        ): StafftimeApi = StafftimeServicesFactory.create(ctxTx, audit, outbox, clock, settings, runSource, workRules)
    }
}

enum class DevicePlatform { ANDROID, IOS }

data class RegisterDriverDevice(val deviceId: UUID, val platform: DevicePlatform, val appVersion: String, val pushTokenRef: String?)

/** Gerätebindung: [sessionBound] ist wahr, wenn die IdP-Sitzung der Anfrage der gebundenen Sitzung entspricht (sonst muss das Gerät neu registrieren). */
data class DriverDeviceView(
    val deviceId: UUID, val sessionId: UUID?, val platform: DevicePlatform, val appVersion: String, val registeredAt: Instant, val revokedAt: Instant?, val revision: Long,
)

/**
 * Geräte-/Sitzungsbindung der Fahrer-App (WP-M3-24). Ein Gerät gehört genau einem Konto; erneute Anmeldung desselben Kontos bindet es an die neue Sitzung (Revision +1),
 * ein anderes Konto bekommt 409; die Beendigung der Bindung ist endgültig. Fremde Geräte sind nie sichtbar (404).
 */
interface DriverDevices {
    /** Registriert oder bindet neu; wiederholt mit derselben Sitzung liefert dasselbe Gerät unverändert. */
    fun register(ctx: RequestContext, cmd: RegisterDriverDevice): DriverDeviceView

    /** Beendet die Bindung des eigenen Geräts; `expectedRevision` ist der `If-Match`-Wert (veraltet: 412). */
    fun revoke(ctx: RequestContext, deviceId: UUID, expectedRevision: Long)
}

/** Art des Fahrerereignisses (Server-Enum von `recordDriverEvent`, OP-03). */
enum class DriverEventType(val planRelative: Boolean, val needsBooking: Boolean) {
    CHECK_IN(true, true), CHECK_OUT(true, true), NO_SHOW(true, true), ARRIVAL(true, false), BREAK_START(false, false), BREAK_END(false, false), DISRUPTION(false, false), SHIFT_END(false, false);
    companion object { fun parse(code: String): DriverEventType? = entries.firstOrNull { it.name == code } }
}

enum class DriverEventOutcome { APPLIED, DUPLICATE, REVIEW_REQUIRED }

/** Nutzlast des Ereignisses; `deviceRecordedAt` ist die Gerätezeit und wird nie als Serverzeit übernommen. */
data class DriverEventCommand(
    val eventId: UUID, val deviceId: UUID?, val shiftId: UUID, val vehicleId: UUID, val planRevision: Long, val type: DriverEventType,
    val bookingId: UUID?, val routeStopId: UUID?, val occurredAt: Instant, val deviceRecordedAt: Instant, val offlineRecorded: Boolean,
) {
    /** Inhalt, über den „gleiche event_id, anderer Inhalt“ erkannt wird (feste Feldreihenfolge). */
    fun contentFingerprint(): String = listOf(eventId, deviceId, shiftId, vehicleId, planRevision, type, bookingId, routeStopId, occurredAt, deviceRecordedAt, offlineRecorded).joinToString("|")
}

data class DriverEventResult(
    val eventId: UUID, val outcome: DriverEventOutcome, val receivedAt: Instant, val currentPlanRevision: Long, val reasonCode: String?, val originalOutcome: DriverEventOutcome?,
)

/**
 * Ereigniseingang der Fahrer-App (WP-M3-24). Die `event_id` dedupliziert dauerhaft (Primärschlüssel): ein zweites identisches Ereignis wirkt nie, auch nicht bei
 * parallelem Eintreffen (DUPLICATE); dieselbe `event_id` mit anderem Inhalt ist 409 `IDEMPOTENCY_CONFLICT`. Gerät und Schicht müssen dem Akteur gehören
 * (fremd: 404, gesperrtes oder an eine andere Sitzung gebundenes Gerät: 403). Die Serverempfangszeit setzt allein der Server.
 * Die fachliche Wirkung auf Stopps und Buchungen (Statusübergänge, No-Show-Frist) hängt WP-M3-29 an; hier gilt: APPLIED = dauerhaft und genau einmal erfasst
 * (Journal + Ereignis `driver.event_recorded`); REVIEW_REQUIRED mit STALE_PLAN, wenn der Plan des Umlaufs der Schicht neuer ist als die dem Gerät bekannte Revision.
 */
interface DriverEvents {
    fun record(ctx: RequestContext, cmd: DriverEventCommand): DriverEventResult
}
