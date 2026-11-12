package de.taktovia.stafftime.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Kopfdaten einer Dienstplanrevision: [revision] ist die lückenlose Nummer, [etag] der Stand der Revisionszeile. Veröffentlichungsdaten sind für den Entwurf `null`. */
data class RosterRevisionHead(
    val revision: Long, val state: RosterState, val author: String, val createdAt: Instant, val publishedAt: Instant?, val publishedBy: String?, val effectiveFrom: Instant?, val etag: Long,
)

/** Schichtstand einer Revision (Snapshot): die Zeile der Revision, unverändert seit ihrer Veröffentlichung. [lineageId] ist die revisionsübergreifend stabile Schichtkennung. */
data class HistoricShift(
    val shiftId: UUID, val lineageId: UUID, val operatingDay: LocalDate, val startsAt: Instant, val endsAt: Instant, val timezone: ZoneId, val function: String?, val requiredQualification: String?,
    val staffingNeed: Int, val driverRef: UUID?, val vehicleId: UUID?, val depotId: UUID?, val breakPlan: BreakPlan, val state: String,
)

/** Eine veröffentlichte (oder ersetzte) Fassung des Dienstplans mit ihren Schichten. */
data class RosterRevisionSnapshot(val head: RosterRevisionHead, val shifts: List<HistoricShift>)

enum class ShiftChangeKind { CREATED, CHANGED, PUBLISHED }

/** Eintrag des Änderungsprotokolls einer Schicht: [kind], betroffene Revision und Schichtzeile, Autor, Zeitstempel und Schichtstand zu diesem Zeitpunkt ([snapshot], JSON-Form). */
data class ShiftChange(
    val id: UUID, val kind: ShiftChangeKind, val rosterRevision: Long, val shiftId: UUID, val shiftRevision: Long, val actor: String, val occurredAt: Instant, val snapshot: Map<String, Any?>,
)

/**
 * Revisionen und Historie des Dienstplans (WP-M4-15, LH-6.23-SCH-010). Nur lesend; Zugriff mit dem Auditrecht `rosters:audit` **und** dem Depotrecht (sonst 404, nie 403 für fremde Depots, kein
 * Hinweis auf Existenz). Frühere veröffentlichte Fassungen bleiben unverändert abrufbar (die Revision ist nach der Publikation unveränderlich, die Schichtzeilen früherer Revisionen werden nie
 * angefasst); jede Anlage, Änderung und Veröffentlichung einer Schicht steht mit Autor und Zeitstempel im Änderungsprotokoll.
 */
interface RosterHistory {
    /** Alle Revisionen des Dienstplans aufsteigend (Entwurf nur sichtbar für Berechtigte: Auditrecht und Depotrecht genügen). */
    fun revisions(ctx: RequestContext, rosterId: UUID): List<RosterRevisionHead>

    /** Eine veröffentlichte oder ersetzte Fassung mit ihren Schichten; Entwurf oder unbekannte Revision: 404. */
    fun revision(ctx: RequestContext, rosterId: UUID, revision: Long): RosterRevisionSnapshot

    /** Änderungen und Veröffentlichungen der Schicht [shiftId] (jede Revisionszeile derselben Schicht): aufsteigend nach Zeit; unbekannte Schicht: 404. */
    fun shiftHistory(ctx: RequestContext, rosterId: UUID, shiftId: UUID): List<ShiftChange>
}
