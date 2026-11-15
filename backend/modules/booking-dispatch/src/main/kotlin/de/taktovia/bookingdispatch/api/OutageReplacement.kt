package de.taktovia.bookingdispatch.api

import de.taktovia.config.api.EffectiveConfig
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantId
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Zustand einer vom Fahrzeugausfall betroffenen Buchung (Zustandsautomat `zustaende/stoerungsfall.md`, WP-M3-54). Jede betroffene Buchung endet in [REBOOKED] oder [CANCELLED] oder bleibt sichtbar als
 * offener Punkt ([OPEN], [PROPOSED], [OPEN_ISSUE]); [BOARDED] (Fahrgast ist eingestiegen) wird nie automatisch umgebucht oder storniert, [ONBOARD_DONE] ist die beendete Fahrt eines eingestiegenen Fahrgasts.
 */
enum class OutageBookingState {
    OPEN, PROPOSED, REBOOKED, CANCELLED, OPEN_ISSUE, BOARDED, ONBOARD_DONE;

    /** Zustände, in denen die Leitstelle noch entscheiden darf (Umbuchung oder Storno). */
    val decidable: Boolean get() = this == OPEN || this == PROPOSED || this == OPEN_ISSUE
    /** Zustände, die den Incident noch offen halten. */
    val blocksIncident: Boolean get() = this == OPEN || this == PROPOSED || this == OPEN_ISSUE || this == BOARDED
}

/** Wirkung eines Vorschlags auf das Zusagefenster der Buchung: eingehalten oder verletzt (die Übernahme einer Verletzung braucht die ausdrückliche Annahme der Leitstelle). */
enum class OutageWindowEffect { IN_WINDOW, OUTSIDE_WINDOW }

/** Ersatzvorschlag: Umlauf eines anderen, freigegebenen und geeigneten Fahrzeugs mit den Zeiten, die die Buchung dort bekäme. Nur Kennungen und Zeiten. */
data class OutageProposalView(
    val id: UUID, val vehicleId: UUID, val runId: UUID, val runRevision: Long, val pickupAt: Instant, val dropoffAt: Instant, val windowEffect: OutageWindowEffect, val windowViolations: List<String>,
)

/** Betroffene Buchung im Störungsfall. [actions] sind die in diesem Zustand zulässigen Aktionen (`REBOOK`, `CANCEL`, `INFORM_PASSENGER`, `CHECK_OPTIONS`). */
data class OutageBookingView(
    val id: UUID, val bookingId: UUID, val bookingNumber: String?, val runId: UUID, val vehicleId: UUID, val state: OutageBookingState, val issueCode: String?, val decision: String?, val decisionReason: String?,
    val decidedBy: String?, val decidedAt: Instant?, val replacementRunId: UUID?, val passengerNoticeRequested: Boolean, val revision: Long, val passengers: Int, val wheelchairSpaces: Int,
    val pickupEarliest: Instant?, val pickupLatest: Instant?, val proposals: List<OutageProposalView>, val actions: List<String>,
)

/** Sicht des Störungsfalls (UI-07): alle betroffenen Buchungen mit Vorschlägen und Aktionen, eingestiegene Fahrgäste gesondert in [boarded]. */
data class OutageOverview(val incidentId: UUID, val vehicleId: UUID?, val bookings: List<OutageBookingView>) {
    val boarded: List<OutageBookingView> get() = bookings.filter { it.state == OutageBookingState.BOARDED || it.state == OutageBookingState.ONBOARD_DONE }
    val open: List<OutageBookingView> get() = bookings.filter { it.state.blocksIncident }
}

/** Ergebnis der Ersatzsuche für den Workflow (übersetzt von der Anwendung in den Port des Moduls `fleet-safety`). */
sealed interface OutageSearchOutcome {
    /** Es gibt [count] Ersatzvorschläge über alle betroffenen Buchungen. */
    data class Proposed(val count: Int, val affected: Int) : OutageSearchOutcome
    /** Betroffene Buchungen gefunden, aber kein Ersatz für irgendeine: sie bleiben als offene Punkte im Incident. */
    data class NoneAvailable(val affected: Int) : OutageSearchOutcome
    /** Kein Fahrgast betroffen (keine offene Buchung auf dem gesperrten Fahrzeug). */
    data object NothingAffected : OutageSearchOutcome
}

/** Incident aus Sicht des Störungsablaufs (Quelle: Modul `fleet-safety` über die Anwendung): Zustand und Revision. */
data class IncidentRef(val id: UUID, val vehicleId: UUID?, val serviceId: UUID?, val status: String, val revision: Long, val kind: String)

/**
 * Port auf den Incident des Moduls `fleet-safety` (die Anwendung verdrahtet ihn): Berechtigung und Sichtbarkeit (fremder Mandant/Dienst 404, fehlende Rolle 403), Entscheidungsprotokoll im
 * Ablaufprotokoll des Incidents (Akteur und Zeit setzt der Server) und Abschluss des Incidents. Alle Aufrufe laufen **in der Transaktion** [j] des Störungsablaufs.
 */
interface IncidentGateway {
    /** [write] verlangt das Schreibrecht (Disposition); sonst reicht das Leserecht der Leitstelle. */
    fun access(j: JdbcOperations, ctx: RequestContext, incidentId: UUID, write: Boolean): IncidentRef
    /** Systemzugriff ohne Personenkontext (Ersatzsuche als Betriebsauftrag): `null` = Incident unbekannt. */
    fun accessSystem(j: JdbcOperations, incidentId: UUID): IncidentRef?
    fun recordDecision(j: JdbcOperations, ctx: RequestContext, incidentId: UUID, decision: String, reasonCode: String)
    /** Schließt den Incident: [resolved] = mindestens eine Buchung wurde umgebucht (`RESOLVED`), sonst `CLOSED_NO_REPLACEMENT`. Wirkungslos, wenn er schon abgeschlossen ist. */
    fun conclude(j: JdbcOperations, incidentId: UUID, resolved: Boolean, actor: String)
}

/** Umbuchung auf einen Vorschlag: [ifMatch] = Revision der betroffenen Buchung (Plan-CAS der Entscheidung), [reasonCode] ein Code (`[A-Z][A-Z0-9_]{1,59}`, nie Freitext). */
data class RebookOutageCommand(val ifMatch: Long, val proposalId: UUID, val reasonCode: String, val acceptWindowEffect: Boolean = false)

/** Betreiberstorno wegen Fahrzeugausfall (Stornoursache `VEHICLE_OUTAGE`, Erstattung und Benachrichtigung über die M2-Wege). */
data class CancelOutageCommand(val ifMatch: Long, val reasonCode: String)

/**
 * Störungsablauf bei Fahrzeugausfall (WP-M3-54, LH-6.22-DMG-010): verknüpft die offenen Fahrten des gesperrten Fahrzeugs, Ersatzvorschläge (vorhandene Planlogik, Prüfung wie bei der manuellen Umplanung) und die
 * Auswirkung auf Fahrgäste mit dem Incident. Die Leitstelle arbeitet jede betroffene Buchung bis zur Umbuchung oder zum Storno ab; nichts geschieht ohne ihre Entscheidung (keine automatische Umbuchung).
 *
 * - **Suche** ([search], Systemaufruf des Betriebsauftrags `REPLACEMENT_SEARCH`): registriert die betroffenen Buchungen (offene, nicht eingestiegene Fahrgäste) und berechnet je Buchung Vorschläge; wiederholbar,
 *   jede Berechnung ist eine neue Vorschlagsgeneration. Eingestiegene Fahrgäste werden gesondert geführt und nie umgebucht oder storniert.
 * - **Vorschläge** enthalten nur Umläufe anderer Fahrzeuge, die für die Buchung einsetzbar sind (nicht gesperrt, Pool, Schicht, gültige Einsatzfreigabe, Dienststatus, Ressourcenvektor inklusive Rollstuhlplatz,
 *   Routeninvarianten, Kapazität). Eine Ersatzfahrt gilt erst nach der bestätigten Umbuchung ([rebook]) als zugesagt.
 * - **Entscheidung** ([rebook], [cancel]): Rolle Disposition im Dienstscope; `If-Match` = Revision der betroffenen Buchung (`412 REVISION_MISMATCH`, der zweite Disponent mit überholtem Stand wird abgewiesen; ist
 *   die Buchung bereits entschieden: `409 INVALID_STATE_TRANSITION`). Umbuchung über denselben Commit wie die manuelle Umplanung (Plan-CAS, Sicherheits- und Kapazitätsprüfung, Hold-Wechsel, `booking_change`);
 *   Storno über den Betreiberstorno der Buchung mit Ursache `VEHICLE_OUTAGE` (einmalige Erstattung, Benachrichtigung über die M2-Wege). Jede Entscheidung steht mit Akteur, Code und Zeit im Ablaufprotokoll.
 * - **Fahrgast informieren** ([inform]): fordert die Mitteilung zur Umbuchung genau einmal je Änderung an (Ereignis `outage.passenger_notice_requested`); Vorlagen und Kanäle sind offen (OP-27).
 */
interface OutageReplacement {
    fun search(tenant: TenantId, incidentId: UUID, vehicleId: UUID?): OutageSearchOutcome

    /** Lesesicht (Leserecht der Leitstelle, Dienstscope); frisch berechnete Vorschläge entstehen nur über [search] und [checkOptions]. */
    fun overview(ctx: RequestContext, incidentId: UUID): OutageOverview

    /** „Ersatzoptionen prüfen“: berechnet die Vorschläge aller noch offenen Buchungen neu (eigene Schreibtransaktion, Recht Disposition) und liefert die neue Sicht. */
    fun checkOptions(ctx: RequestContext, incidentId: UUID): OutageOverview

    fun rebook(ctx: RequestContext, incidentId: UUID, bookingId: UUID, cmd: RebookOutageCommand): OutageBookingView

    fun cancel(ctx: RequestContext, incidentId: UUID, bookingId: UUID, cmd: CancelOutageCommand): OutageBookingView

    /** Fordert die Fahrgastmitteilung zur Umbuchung an; wiederholt für dieselbe Änderung ohne neue Wirkung. [ifMatch] = Revision der betroffenen Buchung. */
    fun inform(ctx: RequestContext, incidentId: UUID, bookingId: UUID, ifMatch: Long): OutageBookingView

    companion object {
        /** Höchstzahl der Vorschläge je Buchung (die besten nach Zusagefenster und Abholzeit). */
        const val MAX_PROPOSALS = 5

        fun create(ctxTx: ContextTransactions, outbox: Outbox, audit: AuditPort, config: EffectiveConfig, bookings: Bookings, gateway: IncidentGateway, clock: Clock = Clock.systemUTC(),
            staffing: de.taktovia.platform.readports.ShiftStaffingReadPort? = null): OutageReplacement =
            de.taktovia.bookingdispatch.internal.JdbcOutageReplacement.create(ctxTx, outbox, audit, config, bookings, gateway, clock, staffing)
    }
}
