package de.taktovia.stafftime.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Eine geplante Pause: Versatz zum Schichtbeginn, Dauer, bezahlt (Schema `pausenplan.v1`). */
data class ShiftBreak(val offsetMin: Int, val durationMin: Int, val paid: Boolean)

/** Pausenplan einer Schicht (WP-M4-03). Pausen liegen vollständig in der Schicht und überlappen sich nicht; höchstens 6. */
data class BreakPlan(val breaks: List<ShiftBreak>) {
    fun toMap(): Map<String, Any?> = linkedMapOf("schema_version" to "1.0", "breaks" to breaks.map { linkedMapOf("offset_min" to it.offsetMin, "duration_min" to it.durationMin, "paid" to it.paid) })

    companion object {
        val EMPTY = BreakPlan(emptyList())

        /** Prüft die JSON-Form (Map) gegen das Schema und gegen die Schichtdauer [shiftMinutes]; Verletzungen mit Pointer unter [pointer]. */
        fun parse(raw: Any?, shiftMinutes: Long?, pointer: String = "/break_plan"): Pair<BreakPlan?, List<Violation>> {
            if (raw == null) return EMPTY to emptyList()
            val m = raw as? Map<*, *> ?: return null to listOf(Violation(pointer, ViolationCode.TYPE_MISMATCH))
            val v = mutableListOf<Violation>()
            m.keys.filter { it != "schema_version" && it != "breaks" }.forEach { v += Violation("$pointer/$it", ViolationCode.UNKNOWN_FIELD) }
            if (m["schema_version"] != "1.0") v += Violation("$pointer/schema_version", if (m["schema_version"] == null) ViolationCode.REQUIRED else ViolationCode.NOT_IN_ENUM)
            val list = m["breaks"] as? List<*>
            if (list == null) { v += Violation("$pointer/breaks", if (m["breaks"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH); return null to v }
            if (list.size > 6) v += Violation("$pointer/breaks", ViolationCode.TOO_MANY_ITEMS)
            val out = mutableListOf<ShiftBreak>()
            list.forEachIndexed { i, b ->
                val p = "$pointer/breaks/$i"; val bm = b as? Map<*, *>
                if (bm == null) { v += Violation(p, ViolationCode.TYPE_MISMATCH); return@forEachIndexed }
                bm.keys.filter { it !in setOf("offset_min", "duration_min", "paid") }.forEach { v += Violation("$p/$it", ViolationCode.UNKNOWN_FIELD) }
                val off = (bm["offset_min"] as? Number)?.takeIf { it.toDouble() == it.toLong().toDouble() }?.toInt()
                val dur = (bm["duration_min"] as? Number)?.takeIf { it.toDouble() == it.toLong().toDouble() }?.toInt()
                val paid = bm["paid"] as? Boolean
                if (off == null) v += Violation("$p/offset_min", if (bm["offset_min"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH) else if (off !in 0..1439) v += Violation("$p/offset_min", ViolationCode.INVALID_VALUE)
                if (dur == null) v += Violation("$p/duration_min", if (bm["duration_min"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH) else if (dur !in 1..240) v += Violation("$p/duration_min", ViolationCode.INVALID_VALUE)
                if (paid == null) v += Violation("$p/paid", if (bm["paid"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH)
                if (off != null && dur != null && paid != null && off in 0..1439 && dur in 1..240) out += ShiftBreak(off, dur, paid)
            }
            if (v.isEmpty()) {
                val sorted = out.sortedBy { it.offsetMin }
                if (shiftMinutes != null && sorted.any { it.offsetMin + it.durationMin > shiftMinutes }) v += Violation("$pointer/breaks", ViolationCode.INVALID_VALUE, "Pause liegt nicht vollständig in der Schicht")
                if (sorted.zipWithNext().any { (a, b) -> a.offsetMin + a.durationMin > b.offsetMin }) v += Violation("$pointer/breaks", ViolationCode.INVALID_VALUE, "Pausen überlappen sich")
            }
            return if (v.isEmpty()) BreakPlan(out.sortedBy { it.offsetMin }) to v else null to v
        }
    }
}

/**
 * Eingabe einer Schicht. Lokaler Beginn und Ende ([startsLocal], [endsLocal]: `yyyy-MM-ddTHH:mm[:ss]` ohne Offset) gelten in [timezone] (IANA); eine Ortszeit in der Frühjahrslücke wird abgelehnt,
 * eine doppelte Ortszeit (Herbst) verlangt den Offset ([startsOffset]/[endsOffset], z. B. `+02:00`). Betrieb ([operatorId]) und Depot ([depotId]) gehören zum Mandanten. [driverRef] und [vehicleId] fehlen bei offenen Schichten.
 */
data class RosterShiftInput(
    val startsLocal: String?, val startsOffset: String?, val endsLocal: String?, val endsOffset: String?, val timezone: String?, val breakPlan: Any?,
    val operatorId: UUID, val depotId: UUID, val function: String?, val requiredQualification: String?, val staffingNeed: Int, val driverRef: UUID? = null, val vehicleId: UUID? = null,
    /** Dienstbezug (geplanter Betrieb, WP-M4-17); optional. */
    val serviceId: UUID? = null,
)

/** Schicht mit allen Planungsmerkmalen. [operatingDay] ist das lokale Datum des Beginns (Mitternachtsschicht: Tag des Beginns); [durationMinutes] die reale Dauer (DST-fest). */
data class RosterShiftView(
    val id: UUID, val rosterId: UUID, val operatingDay: LocalDate, val timezone: ZoneId, val startsAt: Instant, val endsAt: Instant, val durationMinutes: Long, val breakPlan: BreakPlan,
    val operatorId: UUID, val depotId: UUID, val function: String, val requiredQualification: String?, val staffingNeed: Int, val driverRef: UUID?, val vehicleId: UUID?, val state: String,
    val revision: Long, val createdBy: String, val createdAt: Instant,
    /** Konflikte dieser Schicht im Entwurf (Hinweis beim Speichern, WP-M4-07); `null` = nicht geprüft (Lesen, Listen). */
    val conflicts: List<RosterConflict>? = null,
    /** Dienstbezug der Schicht (WP-M4-17). */
    val serviceId: UUID? = null,
) {
    val startsLocal get() = startsAt.atZone(timezone).toLocalDateTime()
    val endsLocal get() = endsAt.atZone(timezone).toLocalDateTime()
}

/**
 * Schichten eines Dienstplans (WP-M4-03, LH-6.23-SCH-002). Rechte: `rosters:write` (anlegen, ändern), `rosters:read` (lesen), als zweite Verteidigungslinie im Dienst geprüft. Mandant nur aus dem Kontext (RLS);
 * Depot, Betrieb und Fahrzeug eines anderen Mandanten sind nicht referenzierbar (404). Anlegen und Ändern stehen mit Akteur, Zeit und Objekt im `audit_event`. Neue Schichten sind Entwürfe (`DRAFT`);
 * nur Entwürfe sind änderbar, veröffentlichte Schichten ändert der Dienstplan (WP-M4-04): 409. Änderung mit der erwarteten Revision (`If-Match`): veraltet 412, ohne Zielzeile 404.
 * [rosterId] ist der Dienstplan (WP-M4-04, [Rosters]): eine neue Schicht gehört zu seinem Entwurf (ohne Entwurf: 409), Depot der Schicht = Depot des Dienstplans, der Betriebstag liegt in
 * seiner Woche. Jede Änderung der Schichten schreibt den Stand des Entwurfs (ETag des Dienstplans) fort; Entwurfsänderungen erzeugen keine Outbox-Ereignisse.
 */
interface RosterShifts {
    fun create(ctx: RequestContext, rosterId: UUID, input: RosterShiftInput): RosterShiftView
    fun get(ctx: RequestContext, rosterId: UUID, shiftId: UUID): RosterShiftView
    /**
     * Schichten des Dienstplans nach `(Beginn, ID)`, mit `limit + 1` Zeilen für die Folgeseite. [RosterViewKind.PLANNER]: die Arbeitsrevision (Entwurf, sonst die veröffentlichte),
     * [RosterViewKind.PUBLISHED]: nur die veröffentlichte Revision. [openOnly]: nur offene Schichten (ohne zugeordnete Person, WP-M4-05).
     */
    fun list(ctx: RequestContext, rosterId: UUID, limit: Int, after: Pair<Instant, UUID>?, view: RosterViewKind = RosterViewKind.PLANNER, openOnly: Boolean = false): List<RosterShiftView>
    fun update(ctx: RequestContext, rosterId: UUID, shiftId: UUID, expectedRevision: Long, input: RosterShiftInput): RosterShiftView
}
