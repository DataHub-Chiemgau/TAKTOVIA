package de.taktovia.stafftime.api

import de.taktovia.platform.context.RequestContext
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Zustand einer Dienstplanrevision (`vertraege/zustaende/dienstplan.md`): `DRAFT` (nur Planer) → `PUBLISHED` → `SUPERSEDED`. */
enum class RosterState { DRAFT, PUBLISHED, SUPERSEDED }

/** Lesesicht (Sichtbarkeitsmatrix Rolle × Zustand): [PLANNER] = Entwurf und veröffentlicht, [PUBLISHED] = „verbindlich“, nur die veröffentlichte Revision. */
enum class RosterViewKind { PLANNER, PUBLISHED }

/** Personalbedarf eines Tages und einer Funktion im Depot des Dienstplans: Bedarf, zugeordnete Personen, unbesetzter Rest (WP-M4-05). */
data class NeedLine(val day: LocalDate, val function: String, val need: Int, val assigned: Int) {
    val unstaffed: Int get() = (need - assigned).coerceAtLeast(0)
}

/**
 * Eine Dienstplanrevision mit Zählern. [openShifts] = Schichten ohne zugeordnete Person (Zähler „unbesetzte Schichten“), [unstaffedNeed] = Personalbedarf minus zugeordnete Personen
 * (Summe der [needLines]). [etag] ist der Stand der Revision (steigt mit jeder Änderung ihrer Schichten).
 */
data class RosterSide(
    val revisionId: UUID, val revision: Long, val state: RosterState, val author: String, val createdAt: Instant, val publishedAt: Instant?, val etag: Long,
    val shiftCount: Int, val openShifts: Int, val unstaffedNeed: Int, val needLines: List<NeedLine>,
)

/** Konfliktarten der Konfliktprüfung (WP-M4-07); die Codes sind stabil und stehen in Problem-Details (`ViolationCode`). */
enum class RosterConflictKind { DOUBLE_BOOKING, QUALIFICATION_MISSING, ABSENCE_OVERLAP }

/**
 * Ein Konflikt im Entwurf: [personRef] ist die geplante Person (`driver_ref`), [shiftIds] die betroffenen Schichten (bei Doppelbelegung beide), [missingQualification] die
 * fehlende bzw. zum Schichtzeitpunkt nicht gültige Qualifikationsart. Eine Abwesenheitsmeldung nennt nie Art oder Kommentar der Abwesenheit.
 */
data class RosterConflict(val kind: RosterConflictKind, val personRef: UUID, val shiftIds: List<UUID>, val cause: String, val missingQualification: String? = null)

/** Dienstplan eines Depots für eine Kalenderwoche ([weekStart] = Montag). [conflicts] ist `null`, wo die Prüfung nicht Teil der Sicht ist (Listen, verbindliche Sicht). */
data class RosterView(
    val rosterId: UUID, val depotId: UUID, val weekStart: LocalDate, val draft: RosterSide?, val published: RosterSide?, val conflicts: List<RosterConflict>?, val etag: Long,
    /** Regelhinweise (Ruhezeit, Tagesarbeitszeit, Pflichtpause, WP-M4-13) der Arbeitsrevision (Entwurf, sonst veröffentlicht); `null` wie [conflicts]. Warnungen, nie eine Sperre. */
    val hints: List<RuleHint>? = null,
)

data class CreateRoster(val depotId: UUID, val weekStart: LocalDate)

/**
 * Dienstplan mit getrenntem Entwurf und veröffentlichtem Plan (WP-M4-04, LH-6.23-SCH-004). Rechte: `rosters:write` (anlegen, Entwurf eröffnen), `rosters:read` (lesen), als zweite
 * Verteidigungslinie im Dienst geprüft; Mandant nur aus dem Kontext. Die Entwurfssicht setzt nach der Rollenprüfung den Datenbankschalter für Entwurfszeilen (RLS, restriktive Policy):
 * ohne Planerrecht ist der Entwurf auch per Direktabfrage unsichtbar. Die verbindliche Sicht ([RosterViewKind.PUBLISHED]) liest ohne diesen Schalter nur die veröffentlichte Revision.
 * Entwurfsänderungen erzeugen keine Outbox-Ereignisse (Fahrer-App und Disposition erfahren nur von Veröffentlichungen, WP-M4-14).
 */
interface Rosters {
    /** Legt den Dienstplan (Depot und Woche, Revision 1 als Entwurf) an. Depot eines anderen Mandanten: 404; Woche beginnt nicht am Montag: 422; gibt es den Dienstplan schon: 409. */
    fun create(ctx: RequestContext, cmd: CreateRoster): RosterView

    /** Liest den Dienstplan; nicht sichtbar (fremder Mandant, kein Entwurf in der verbindlichen Sicht): 404. */
    fun get(ctx: RequestContext, rosterId: UUID, view: RosterViewKind = RosterViewKind.PLANNER): RosterView

    /** Dienstpläne nach `(Woche, ID)` aufsteigend, mit `limit + 1` Zeilen für die Folgeseite; Filter schränken nur ein. */
    fun list(ctx: RequestContext, depotId: UUID?, from: LocalDate?, to: LocalDate?, limit: Int, after: Pair<LocalDate, UUID>?): List<RosterView>

    /**
     * Eröffnet nach einer Veröffentlichung einen neuen Entwurf als Kopie der veröffentlichten Schichten; besteht schon ein Entwurf, oder es gibt keine veröffentlichte Revision: 409.
     * [expectedEtag] ist der Stand des Dienstplans (`If-Match`), veraltet: 412.
     */
    fun openDraft(ctx: RequestContext, rosterId: UUID, expectedEtag: Long): RosterView

    /**
     * Konfliktprüfung des Entwurfs in der **laufenden** Transaktion des Aufrufers (Veröffentlichung, WP-M4-14; Mandantenkontext gesetzt): nimmt die Absperrung gegen gleichzeitige
     * Abwesenheitsgenehmigungen (die Genehmigung wartet bis zum Ende der Transaktion, die Prüfung wertet den committeten Stand) und wirft bei mindestens einem Konflikt 409 mit
     * stabilen Konfliktcodes (`ViolationCode.ROSTER_*`), betroffenen Schichten und Ursache je Konflikt (AT-39); ohne Entwurf 404. Setzt die Rollenprüfung des Aufrufers voraus.
     */
    fun requirePublishable(jdbc: JdbcOperations, ctx: RequestContext, rosterId: UUID)
}
