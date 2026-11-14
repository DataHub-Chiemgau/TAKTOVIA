package de.taktovia.bookingdispatch.api

import de.taktovia.config.api.EffectiveConfig
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Manuelle Umplanung mit Begründung und Plan-CAS (WP-M3-40, Detailvertrag m3-detailvertraege.md §5 „Port `PlanCommit`“). Jede Änderung wird als **neue Planrevision** des Umlaufs
 * geschrieben (Compare-and-Set auf die erwartete Revision, Sperrreihenfolge Umlauf, dann Fahrzeug), mit Pflichtbegründung, Sicherheits- und Einsatzprüfung des Zielfahrzeugs im Commit,
 * Kapazitätsprüfung je Ressourcendimension, Schutz zugesagter Zeitfenster und der Konfigurationsgrenzen `protected_stop_horizon_min` und `max_reassignments` (mit dem im Commit wirksamen Wert).
 * Die Verteilung an Fahrer und Fahrgäste leistet derselbe Pfad wie jede Revision (`dispatch.run_revised`, WP-M2-28).
 */
enum class StopChangeKind {
    /** Verschiebt den Stopp [StopChange.routeStopId] des Umlaufs an die Position [StopChange.newSequence] (1-basiert); Ein- und Ausstiege wandern mit. */
    MOVE,
    /** Nimmt die Buchung [StopChange.bookingId] (Einstieg und Ausstieg) aus dem Plan; Stopps ohne Bewegung entfallen. [StopChange.routeStopId] ist ein Stopp der Buchung im Plan. */
    REMOVE,
    /**
     * Übernimmt die Bewegung der Buchung [StopChange.bookingId] am Stopp [StopChange.routeStopId] **eines anderen Umlaufs** (Einstieg oder Ausstieg) als neuen Stopp an Position [StopChange.newSequence]
     * dieses Umlaufs. Einstieg **und** Ausstieg werden je mit einer eigenen Änderung übernommen; die Buchung verlässt den Quellumlauf (Umbuchung, eine Transaktion, beide Umläufe unter Sperre).
     */
    INSERT,
}

data class StopChange(val kind: StopChangeKind, val routeStopId: UUID, val newSequence: Int? = null, val bookingId: UUID? = null)

/** Regeln, die der dokumentierte Ausnahmeweg überstimmen darf; Routeninvarianten, Kapazität, Sperre und Freigabe des Zielfahrzeugs nie. */
enum class ReplanRule { PROTECTED_STOP_HORIZON, PROMISE_WINDOW, MAX_REASSIGNMENTS }

/** Ausnahmeweg (Annahme, bis der Auftraggeber den Freigabeprozess nennt): ausdrückliche Liste der überstimmten Regeln und Begründung (10 bis 500 Zeichen); wird im Audit gesondert protokolliert. */
data class ReplanException(val rules: Set<ReplanRule>, val justification: String)

/** [baseRevision] ist die gelesene Planrevision (`If-Match`); [compareOnly] liefert nur den Planvergleich ohne Commit. */
data class ReplanCommand(val changes: List<StopChange>, val reason: String, val baseRevision: Long, val compareOnly: Boolean = false, val exception: ReplanException? = null)

enum class StopDiffKind { ADDED, REMOVED, MOVED, TIME_CHANGED }

/** Planvergleich je Stopp; Positionen 1-basiert. [routeStopId] ist die Stopp-ID der neuen Revision (bei [StopDiffKind.REMOVED] der alten); im Vergleich ohne Commit stabil abgeleitet. */
data class StopDiff(val routeStopId: UUID, val kind: StopDiffKind, val oldSequence: Int?, val newSequence: Int?)

/** Folge für eine betroffene Buchung (Folgenanzeige): Zeiten vor und nach der Änderung und die dadurch verletzten Zusagefenster (nur im Vergleich bzw. Ausnahmeweg gefüllt). */
data class BookingImpact(
    val bookingId: UUID, val kind: PlanBookingChangeKind, val pickupBefore: Instant?, val pickupAfter: Instant?, val dropoffBefore: Instant?, val dropoffAfter: Instant?,
    val windowViolations: List<WindowViolationCode> = emptyList(),
)

/** Eine Planrevision mit Ursache, Begründung, Akteur und Planvergleich zur Vorgängerrevision. [committed] ist `false` nur beim Vergleich ohne Commit (die Revision ist dann die vorgeschlagene). */
data class RunRevisionRecord(
    val runId: UUID, val planRevision: Long, val previousRevision: Long?, val cause: RevisionCause, val reason: String?, val createdAt: Instant, val createdBy: String,
    val diff: List<StopDiff>, val committed: Boolean, val impacts: List<BookingImpact> = emptyList(), val exception: ReplanException? = null,
)

/** Dienstberechtigung je betroffenem Fahrzeug (Aufrufer: Leitstellenfahrzeugsicht); wirft 404 für nicht sichtbare Fahrzeuge, bevor etwas gesperrt oder geschrieben wird. */
fun interface RunAccess {
    fun check(ctx: RequestContext, vehicleId: UUID)

    companion object { val ALL = RunAccess { _, _ -> } }
}

interface PlanCommit {
    /**
     * Wendet [cmd] auf den Umlauf [runId] an. Fehler: 404 (Umlauf unbekannt oder für den Kontext nicht sichtbar), 412 `REVISION_MISMATCH` mit aktueller Revision im `ETag`, 422 (Begründung fehlt, Änderung
     * ungültig, Planinvariante verletzt), 409 `CONFLICT` (Zielfahrzeug gesperrt/nicht freigegeben/nicht einsetzbar, Planänderung im Zustand des Umlaufs nicht möglich), 409 `CAPACITY_UNAVAILABLE`
     * (Überbuchung einer Dimension), 409 `PROTECTED_STOP_HORIZON`, `MAX_REASSIGNMENTS_EXCEEDED`, `PROMISE_WINDOW_VIOLATION` (ohne Ausnahmeweg). Bei Fehlern wurde nichts geschrieben.
     */
    fun replan(ctx: RequestContext, runId: UUID, cmd: ReplanCommand, access: RunAccess = RunAccess.ALL): RunRevisionRecord

    /** Planrevisionen absteigend nach Revision; [before] = höchste noch nicht gelieferte Revision (Cursor). Revision 1 (leerer Anfangsplan) wird nicht aufgeführt. Fremder Umlauf: 404. */
    fun revisions(ctx: RequestContext, runId: UUID, limit: Int, before: Long? = null, access: RunAccess = RunAccess.ALL): List<RunRevisionRecord>

    companion object {
        /** Planungsdefaults der Grenzen, wenn der Konfigurationskatalog keinen wirksamen Wert liefert (Planungsannahme): Horizont 10 Minuten, höchstens 2 Umbuchungen je Buchung. */
        const val DEFAULT_PROTECTED_STOP_HORIZON_MIN = 10
        const val DEFAULT_MAX_REASSIGNMENTS = 2

        fun create(
            ctxTx: ContextTransactions, outbox: Outbox, audit: AuditPort, config: EffectiveConfig, clock: Clock = Clock.systemUTC(), staffing: de.taktovia.platform.readports.ShiftStaffingReadPort? = null,
        ): PlanCommit = de.taktovia.bookingdispatch.internal.JdbcPlanCommit.create(ctxTx, outbox, audit, config, clock, staffing)
    }
}
