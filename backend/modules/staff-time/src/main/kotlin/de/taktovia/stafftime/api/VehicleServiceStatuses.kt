package de.taktovia.stafftime.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/**
 * Fahrzeug- und Dienststatus (WP-M3-31, LH-6.6-DRV-007, Automat `vertraege/zustaende/fahrzeug_dienststatus.md`). [dispatchable] sagt, ob ein Fahrzeug in diesem Status neu disponiert werden
 * darf: nur Verfügbarkeit, Anfahrt und Wartezeit; Pause, Störung, Ende und der Anfangszustand [NOT_READY] (keine Bereitmeldung ohne Einsatzfreigabe) nicht (Originalabnahme DRV-007).
 */
enum class ServiceStatus(val dispatchable: Boolean) {
    NOT_READY(false), AVAILABLE(true), EN_ROUTE(true), WAITING(true), BREAK(false), DISRUPTED(false), ENDED(false);

    companion object {
        fun parse(code: String): ServiceStatus? = entries.firstOrNull { it.name == code }
        /** Die für neue Zuweisungen zulässigen Status als SQL-Liste (nur feste Konstanten). */
        val DISPATCHABLE_SQL: String = entries.filter { it.dispatchable }.joinToString(", ") { "'${it.name}'" }
    }
}

/** Auslöser eines Statuswechsels: Fahrer (Meldung), System (abgeleitet aus Fahrerereignissen) oder Leitstelle. */
enum class StatusTrigger { DRIVER, SYSTEM, DISPATCH }

/** Kantentabelle des Automaten (eine Quelle für Dienst und Test; der Zustandswächter in `V30310` wiederholt sie für direktes SQL). */
object ServiceStatusAutomaton {
    private val EDGES: Map<Pair<ServiceStatus, ServiceStatus>, Set<StatusTrigger>> = buildMap {
        val d = StatusTrigger.DRIVER; val s = StatusTrigger.SYSTEM; val p = StatusTrigger.DISPATCH
        put(ServiceStatus.NOT_READY to ServiceStatus.AVAILABLE, setOf(d))
        put(ServiceStatus.NOT_READY to ServiceStatus.DISRUPTED, setOf(d, p))
        put(ServiceStatus.NOT_READY to ServiceStatus.ENDED, setOf(d, s))
        put(ServiceStatus.AVAILABLE to ServiceStatus.EN_ROUTE, setOf(d, s))
        put(ServiceStatus.EN_ROUTE to ServiceStatus.WAITING, setOf(d, s))
        put(ServiceStatus.WAITING to ServiceStatus.EN_ROUTE, setOf(d, s))
        put(ServiceStatus.EN_ROUTE to ServiceStatus.AVAILABLE, setOf(s))
        put(ServiceStatus.WAITING to ServiceStatus.AVAILABLE, setOf(d, s))
        put(ServiceStatus.AVAILABLE to ServiceStatus.BREAK, setOf(d))
        put(ServiceStatus.WAITING to ServiceStatus.BREAK, setOf(d))
        put(ServiceStatus.BREAK to ServiceStatus.AVAILABLE, setOf(d))
        put(ServiceStatus.AVAILABLE to ServiceStatus.DISRUPTED, setOf(d, p))
        put(ServiceStatus.EN_ROUTE to ServiceStatus.DISRUPTED, setOf(d, p, s))
        put(ServiceStatus.WAITING to ServiceStatus.DISRUPTED, setOf(d, p, s))
        put(ServiceStatus.BREAK to ServiceStatus.DISRUPTED, setOf(p, s))
        put(ServiceStatus.DISRUPTED to ServiceStatus.NOT_READY, setOf(s, p))
        put(ServiceStatus.AVAILABLE to ServiceStatus.ENDED, setOf(d, s))
        put(ServiceStatus.BREAK to ServiceStatus.ENDED, setOf(d, s))
        put(ServiceStatus.DISRUPTED to ServiceStatus.ENDED, setOf(d, p))
    }

    /** Alle Kanten (Test: Übereinstimmung mit dem Datenbankwächter und der Dokumentation). */
    val edges: Set<Pair<ServiceStatus, ServiceStatus>> get() = EDGES.keys
    fun allowed(from: ServiceStatus, to: ServiceStatus, by: StatusTrigger): Boolean = EDGES[from to to]?.contains(by) == true
    fun exists(from: ServiceStatus, to: ServiceStatus): Boolean = (from to to) in EDGES
}

/**
 * Statusmeldung des Fahrers für die eigene, angetretene Schicht. [eventId] dedupliziert dauerhaft (Offline-Sync, Wiederholung); [occurredAt] ist die Gerätezeit des Ereignisses,
 * [deviceRecordedAt] der Zeitpunkt der Erfassung auf dem Gerät. Der Mandant steht nie im Befehl.
 */
data class ServiceStatusReport(
    val eventId: UUID, val shiftId: UUID, val vehicleId: UUID?, val deviceId: UUID?, val status: ServiceStatus, val occurredAt: Instant, val deviceRecordedAt: Instant, val offlineRecorded: Boolean,
)

/** Ein Eintrag der Statushistorie: Vorstatus (`null` = Anlage), Neustatus, Auslöser, Gerätezeit (nur bei Meldungen), Serverzeit ([recordedAt]) und Offline-Kennzeichen. */
data class ServiceStatusChange(
    val id: UUID, val shiftId: UUID, val vehicleId: UUID, val from: ServiceStatus?, val to: ServiceStatus, val trigger: StatusTrigger, val occurredAt: Instant?, val recordedAt: Instant,
    val offlineRecorded: Boolean, val eventId: UUID?, val revision: Long,
)

/** Aktueller Status der Schicht mit Historie (älteste zuerst). Ohne bisherige Meldung: [NOT_READY], Revision 0, leere Historie. */
data class ServiceStatusView(val shiftId: UUID, val vehicleId: UUID, val status: ServiceStatus, val revision: Long, val changedAt: Instant?, val history: List<ServiceStatusChange>)

enum class StatusOutcome {
    /** Der Wechsel wurde durchgeführt. */
    APPLIED,
    /** Dieselbe Meldung war schon eingegangen; nichts geändert (genau einmal wirksam). */
    DUPLICATE,
    /** Offline erfasste Meldung, die zum Serverstand nicht mehr passt (unzulässig oder älter als der aktuelle Status): keine Änderung, Klärfall der Leitstelle. */
    REVIEW_REQUIRED,
}

data class ServiceStatusResult(val outcome: StatusOutcome, val status: ServiceStatusView, val receivedAt: Instant, val reasonCode: String?)

/**
 * Zustandsautomat und Historie des Dienststatus (Modul `staff-time`, Tabellen `vehicle_service_status`, `vehicle_status_history`).
 *
 * - **Fahrermeldung** ([report]): nur der Fahrer (Rolle `driver`, menschlicher Akteur mit Sitzung) für die eigene, **angetretene** Schicht und ihr Fahrzeug; fremde, unbekannte und Schichten
 *   anderer Mandanten sind einheitlich 404, eine eigene nicht angetretene oder beendete Schicht 409. Unzulässiger Übergang: 409 `INVALID_STATE_TRANSITION` (online); eine **offline** erfasste
 *   Meldung, die nicht mehr passt, ergibt [StatusOutcome.REVIEW_REQUIRED] ohne Änderung (sie überschreibt nie einen neueren Serverstand). Offline erfasste Meldungen wirken genau einmal und
 *   erst ab Serverempfang: Wirkung auf die Disponierbarkeit und `recordedAt` sind die Serverzeit, die Gerätezeit bleibt getrennt.
 * - **Ableitung** ([deriveFromDriverEvent]): Fahrerereignisse (DRV-005) führen Anfahrt, Wartezeit, Pause, Störung und Ende mit Auslöser `SYSTEM` nach; läuft in der Transaktion des Ereigniseingangs.
 * - **Leitstelle** ([byDispatch]): Rolle `dispatcher`, Auslöser `DISPATCH`.
 * - **Sperrreihenfolge:** jede Änderung sperrt zuerst die Fahrzeugzeile (`FOR UPDATE`), der Hold-Commit der Disposition liest sie unter `FOR SHARE`: ein Wechsel auf Störung und eine parallele Zuweisung
 *   serialisieren, es entsteht nie eine Zusage auf ein Fahrzeug, das nach dem Wechsel nicht disponierbar ist (Kap. 01 §1.4).
 * - **Ereignis:** jeder Wechsel schreibt `vehicle.service_status_changed` in derselben Transaktion; mit `current = DISRUPTED` ist es das Ereignis „Störung gemeldet“, das den Störungsablauf (WP-M3-53) eröffnet.
 */
interface VehicleServiceStatuses {
    fun report(ctx: RequestContext, cmd: ServiceStatusReport): ServiceStatusResult

    /** Aktueller Status der eigenen Schicht (Fahrer); fremde Schicht: 404. */
    fun current(ctx: RequestContext, shiftId: UUID): ServiceStatusView

    /** Wechsel durch die Leitstelle (zulässige Kanten laut Automat, Auslöser `DISPATCH`); unbekannte Schicht: 404. */
    fun byDispatch(ctx: RequestContext, shiftId: UUID, target: ServiceStatus): ServiceStatusResult

    /** Aktueller Status je Fahrzeug (jüngster Wechsel über alle Schichten des Fahrzeugs); Fahrzeuge ohne Meldung fehlen in der Antwort. Lesend, Mandant per RLS. */
    fun latestForVehicles(ctx: RequestContext, vehicleIds: Collection<UUID>): Map<UUID, ServiceStatus>
}
