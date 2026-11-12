package de.taktovia.bookingdispatch.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/**
 * Verteilung der Planrevisionen an Fahrgäste und Fahrer (WP-M2-28, LH-6.5-OPT-010). Jede Planänderung ([RunPlans.appendRevision]) schreibt in derselben Transaktion eine Zeile
 * `plan_change` (je Umlauf und Revision genau eine) und je betroffener Buchung eine Zeile `plan_change_booking` sowie das Outbox-Ereignis `dispatch.run_revised`. Der Änderungsstrom
 * ist die **adressierte Projektion** darauf: der Fahrgast sieht nur Änderungen seiner eigenen Buchungen, der Fahrer nur die Änderungen des Umlaufs seiner eigenen Schicht (Kap. 02 §4.3);
 * Mandant und Empfänger stammen ausschließlich aus dem [RequestContext].
 *
 * Protokoll (Vorschlag bis OP-25, SSE oder WebSocket sind nur ein Transport darüber): Der Client liest `feed(after = letzter Cursor)`; jeder Eintrag trägt eine Revision je Aggregat
 * (Fahrgast: Buchung, `sequence` und `previousSequence`; Fahrer: Umlauf, `revision` und `previousRevision`). Der [RevisionTracker] entscheidet je Eintrag, ob er angewendet, als
 * Duplikat oder veraltet verworfen wird oder ob eine Lücke vorliegt; bei einer Lücke und nach jedem Verbindungsabbruch ruft der Client [snapshot] ab und setzt seinen Stand darauf.
 * Der Cursor ist monoton, aber wegen paralleler Commits nicht lückenlos: ein Eintrag mit kleinerem Cursor kann nach einem größeren sichtbar werden, die Revisionsprüfung fängt das ab.
 */
enum class PlanChannel { PASSENGER, DRIVER }

/** Art der Änderung einer Buchung im Plan; Auslöser einer Nachricht „ETA geändert“ ist nur [TIME_CHANGED] (Regel in `communication-support`, OP 30). */
enum class PlanBookingChangeKind { ADDED, REMOVED, TIME_CHANGED, STOP_CHANGED, TOUCHED }

/** Änderung einer Buchung in einer Planrevision; Zeiten sind die geplanten Ankunftszeiten am Einstiegs- bzw. Ausstiegsstopp (Plan-ETA, solange es kein ETA-Grundmodell gibt, OP-20). */
data class BookingPlanChange(
    val bookingId: UUID, val kind: PlanBookingChangeKind, val sequence: Long,
    val pickupBefore: Instant?, val pickupAfter: Instant?, val dropoffBefore: Instant?, val dropoffAfter: Instant?,
)

sealed interface PlanChangeEntry {
    /** Position im Strom des Mandanten (monoton). */
    val cursor: Long
    val cause: RevisionCause
    val recordedAt: Instant
}

/** Eintrag des Fahrgastkanals: nur die eigenen Buchungen, höchstens ein Eintrag je Planänderung, auch bei mehreren eigenen Buchungen. */
data class PassengerPlanChange(override val cursor: Long, override val cause: RevisionCause, override val recordedAt: Instant, val bookings: List<BookingPlanChange>) : PlanChangeEntry

/** Eintrag des Fahrerkanals: jede Revision des Umlaufs der eigenen Schicht, lückenlos über `previousRevision` verkettet. */
data class DriverPlanChange(
    override val cursor: Long, override val cause: RevisionCause, override val recordedAt: Instant,
    val shiftId: UUID, val runId: UUID, val revision: Long, val previousRevision: Long, val bookingIds: List<UUID>,
) : PlanChangeEntry

data class PlanChangeFeed(val items: List<PlanChangeEntry>, val nextCursor: Long, val hasMore: Boolean)

/** Aktueller Stand einer eigenen Buchung im Plan (Fahrgast); `sequence` = Zahl der Planänderungen der Buchung, `pickupAt`/`dropoffAt` fehlen, wenn die Buchung nicht (mehr) im Plan steht. */
data class PassengerBookingPlan(val bookingId: UUID, val bookingRevision: Long, val sequence: Long, val pickupAt: Instant?, val dropoffAt: Instant?)

/** Aktueller Plan eines eigenen Umlaufs (Fahrer). */
data class DriverRunPlan(val shiftId: UUID, val runId: UUID, val revision: Long, val state: String, val stopCount: Int)

sealed interface PlanSnapshot {
    val asOf: Instant
    /** Höchster Cursor des Mandanten zum Zeitpunkt des Snapshots: ab hier liest der Client den Strom weiter. */
    val cursor: Long
}
data class PassengerPlanSnapshot(override val asOf: Instant, override val cursor: Long, val bookings: List<PassengerBookingPlan>) : PlanSnapshot
data class DriverPlanSnapshot(override val asOf: Instant, override val cursor: Long, val runs: List<DriverRunPlan>) : PlanSnapshot

/**
 * Änderungsstrom und Snapshot je Kanal. Fahrgastkanal: Rolle `passenger` mit Fahrgastkonto; Fahrerkanal: Rolle `driver`. Jeder andere Zugriff (Betriebspersonal, fremder Kanal) ist
 * 403 `FORBIDDEN`; ein fremder Mandant sieht nie Zeilen eines anderen (RLS). [limit] zählt Planänderungen, höchstens 200.
 */
interface PlanChanges {
    fun feed(ctx: RequestContext, channel: PlanChannel, after: Long, limit: Int): PlanChangeFeed

    fun snapshot(ctx: RequestContext, channel: PlanChannel): PlanSnapshot

    companion object {
        fun create(ctxTx: de.taktovia.platform.context.ContextTransactions, clock: java.time.Clock = java.time.Clock.systemUTC()): PlanChanges =
            de.taktovia.bookingdispatch.internal.BookingServicesFactory.planChanges(ctxTx, clock)
    }
}

/** Entscheidung des Clients zu einem eingetroffenen Eintrag (Referenzalgorithmus für Web und Fahrer-App). */
enum class SyncDecision {
    /** Revision folgt unmittelbar auf den bekannten Stand: übernehmen. */
    APPLY,
    /** Gleiche oder ältere Revision (Duplikat, Nachzügler): verwerfen, der Stand wird nie zurückgesetzt. */
    IGNORE,
    /** Lücke (oder noch kein Stand): Snapshot abrufen, danach [RevisionTracker.resync]. */
    NEEDS_SNAPSHOT,
}

/**
 * Clientseitige Revisionsprüfung je Aggregat (Buchung oder Umlauf): kennt je Schlüssel die höchste übernommene Revision und setzt sie **nie** auf eine ältere zurück, egal ob ein
 * Ereignis doppelt, ungeordnet oder spät eintrifft (analog zur Inbox, WP-M1-14, PH-AT-017).
 */
class RevisionTracker {
    private val known = HashMap<UUID, Long>()

    @Synchronized fun known(key: UUID): Long? = known[key]

    /** Bewertet ein Ereignis mit der Revision [revision] und der unmittelbar vorhergehenden Revision [previous] (0 = es gab keine frühere). */
    @Synchronized fun offer(key: UUID, revision: Long, previous: Long): SyncDecision {
        val k = known[key]
        return when {
            k != null && revision <= k -> SyncDecision.IGNORE
            k != null && previous == k -> { known[key] = revision; SyncDecision.APPLY }
            k == null && previous == 0L -> { known[key] = revision; SyncDecision.APPLY }
            else -> SyncDecision.NEEDS_SNAPSHOT
        }
    }

    /** Setzt den Stand nach einem Snapshot; ein älterer Snapshotstand ändert nichts. */
    @Synchronized fun resync(key: UUID, revision: Long) { known.merge(key, revision) { a, b -> maxOf(a, b) } }
}
