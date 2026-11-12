package de.taktovia.stafftime.internal

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.stafftime.api.Absences
import de.taktovia.stafftime.api.Drivers
import de.taktovia.stafftime.api.RosterConflict
import de.taktovia.stafftime.api.RosterConflictKind
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/**
 * Konfliktprüfung des Entwurfs (WP-M4-07, LH-6.23-SCH-006): Doppelbelegung derselben Person, fehlende oder zum Schichtzeitpunkt nicht gültige Qualifikation, Schicht im Zeitraum einer
 * genehmigten Abwesenheit. Überlappung ist links geschlossen, rechts offen (Ende = Beginn überlappt nicht). Die Prüfung liest nur im Mandanten des Kontexts (RLS) und gibt
 * nie Art oder Kommentar einer Abwesenheit weiter. Eine Schicht des Entwurfs wird gegen die anderen Schichten des Entwurfs und gegen **verbindliche** Schichten anderer Dienstpläne
 * und der Schichtzuweisung (M3) geprüft, nicht gegen frühere Revisionen desselben Dienstplans (der Entwurf ersetzt sie). Zeilen eines nicht sichtbaren Depots ([canSee], WP-M4-16)
 * erscheinen nie mit ihrer Kennung in einer Meldung.
 */
internal class RosterConflictChecker(private val drivers: Drivers, private val absences: Absences) {

    private class DraftShift(val id: UUID, val driver: UUID, val start: Instant, val end: Instant, val qualification: String?)

    /** Alle Konflikte der Entwurfsrevision [revision] des Dienstplans, deterministisch nach Art und Beginn sortiert. */
    fun check(j: JdbcOperations, rosterId: UUID, revision: Long, canSee: (UUID?) -> Boolean = { true }): List<RosterConflict> {
        val shifts = j.query(
            "SELECT s.id, s.driver_ref, s.starts_at, s.ends_at, s.required_qualification FROM taktovia.staff_shift s WHERE s.roster_id = ? AND s.roster_revision = ? AND s.state = 'DRAFT' " +
                "AND s.driver_ref IS NOT NULL ORDER BY s.starts_at, s.id",
            { rs, _ -> DraftShift(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant(), rs.getString(5)) }, rosterId, revision,
        )
        if (shifts.isEmpty()) return emptyList()
        val out = mutableListOf<RosterConflict>()
        out += doubleBookings(j, rosterId, revision, canSee)
        val subjects = HashMap<UUID, List<String>>()
        for (s in shifts) {
            s.qualification?.let { code -> qualification(j, s, code)?.let { out += it } }
            if (absent(j, s, subjects)) out += RosterConflict(RosterConflictKind.ABSENCE_OVERLAP, s.driver, listOf(s.id),
                "Schicht ${s.id}: Die Person ist im Schichtzeitraum nicht verfügbar (genehmigte Abwesenheit).")
        }
        return out.sortedWith(compareBy({ it.kind.ordinal }, { it.shiftIds.first().toString() }))
    }

    /** Wirft 409 mit stabilen Codes, wenn der Entwurf Konflikte hat (Sperre der Veröffentlichung, AT-39). */
    fun requireNone(conflicts: List<RosterConflict>) {
        if (conflicts.isEmpty()) return
        throw ProblemException(ErrorCode.CONFLICT, "Der Dienstplan enthält ${conflicts.size} Konflikt(e) und ist nicht veröffentlichbar.", conflicts.map {
            Violation("/shifts/${it.shiftIds.first()}", code(it.kind), it.cause)
        })
    }

    private fun code(k: RosterConflictKind) = when (k) {
        RosterConflictKind.DOUBLE_BOOKING -> ViolationCode.ROSTER_DOUBLE_BOOKING
        RosterConflictKind.QUALIFICATION_MISSING -> ViolationCode.ROSTER_QUALIFICATION_MISSING
        RosterConflictKind.ABSENCE_OVERLAP -> ViolationCode.ROSTER_ABSENCE_OVERLAP
    }

    private fun doubleBookings(j: JdbcOperations, rosterId: UUID, revision: Long, canSee: (UUID?) -> Boolean): List<RosterConflict> {
        class Pair(val a: UUID, val b: UUID, val person: UUID, val bDepot: UUID?, val inDraft: Boolean)
        val sql = "SELECT a.id, b.id, a.driver_ref, b.depot_id, b.roster_id = a.roster_id AND b.roster_revision = a.roster_revision FROM taktovia.staff_shift a " +
            "JOIN taktovia.staff_shift b ON b.tenant_id = a.tenant_id AND b.driver_ref = a.driver_ref AND b.id <> a.id AND tstzrange(b.starts_at, b.ends_at, '[)') && tstzrange(a.starts_at, a.ends_at, '[)') " +
            "WHERE a.roster_id = ? AND a.roster_revision = ? AND a.state = 'DRAFT' AND ((b.roster_id = a.roster_id AND b.roster_revision = a.roster_revision AND b.state = 'DRAFT' AND a.id < b.id) " +
            "OR (b.state IN ('PLANNED', 'STARTED', 'ENDED', 'PUBLISHED') AND b.roster_id IS DISTINCT FROM a.roster_id)) ORDER BY a.starts_at, a.id, b.starts_at, b.id"
        return j.query(sql, { rs, _ -> Pair(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getObject(4, UUID::class.java), rs.getBoolean(5)) }, rosterId, revision)
            .map { p ->
                if (p.inDraft || canSee(p.bDepot)) RosterConflict(RosterConflictKind.DOUBLE_BOOKING, p.person, listOf(p.a, p.b),
                    "Doppelbelegung: Schicht ${p.a} und Schicht ${p.b} überlappen sich für dieselbe Person.")
                else RosterConflict(RosterConflictKind.DOUBLE_BOOKING, p.person, listOf(p.a), "Doppelbelegung: Schicht ${p.a} überlappt eine verbindliche Schicht außerhalb Ihrer Depots.")
            }
    }

    /** Qualifikation muss den ganzen Schichtzeitraum abdecken: gültig zum Beginn (freigegeben, höchste Revision) und nicht vor dem Ende ablaufend. */
    private fun qualification(j: JdbcOperations, s: DraftShift, code: String): RosterConflict? {
        val valid = if (isDriver(j, s.driver)) drivers.validQualifications(j, s.driver, s.start).firstOrNull { it.code == code } else null
        if (valid != null && (valid.validTo == null || !valid.validTo.isBefore(s.end))) return null
        return RosterConflict(RosterConflictKind.QUALIFICATION_MISSING, s.driver, listOf(s.id), "Schicht ${s.id}: Die Qualifikation $code fehlt oder gilt nicht für den ganzen Schichtzeitraum.", code)
    }

    private fun isDriver(j: JdbcOperations, id: UUID) = j.queryForObject("SELECT count(*) FROM taktovia.driver WHERE id = ?", Int::class.java, id) == 1

    /** Die Person ist per Stamm (`driver.id` → Konto-Subject der Mitgliedschaft) oder im Altbestand direkt als Subject (`driver_ref`) geplant; beide Schreibweisen werden geprüft. */
    private fun absent(j: JdbcOperations, s: DraftShift, cache: MutableMap<UUID, List<String>>): Boolean {
        val refs = cache.getOrPut(s.driver) {
            listOf(s.driver.toString()) + j.query("SELECT m.subject FROM taktovia.driver d JOIN taktovia.membership m ON m.tenant_id = d.tenant_id AND m.id = d.membership_id WHERE d.id = ?", { rs, _ -> rs.getString(1) }, s.driver)
        }
        return refs.any { ref -> absences.restrictionsIn(j, ref, s.start, s.end).isNotEmpty() || (ref != ref.lowercase() && absences.restrictionsIn(j, ref.lowercase(), s.start, s.end).isNotEmpty()) }
    }
}
