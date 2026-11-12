package de.taktovia.stafftime.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.time.IanaZones
import de.taktovia.platform.time.Rfc3339
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Zustände von `staff_shift.state` (`vertraege/zustaende/staff_shift.md`, WP-M3-07/-25). `PUBLISHED` gehört dem Dienstplan (M4, WP-M4-65) und wird in M3 weder gesetzt
 * noch gewertet; nur [CANCELLED] gilt nicht mehr und blockiert nichts.
 */
enum class ShiftState { PLANNED, STARTED, ENDED, CANCELLED }

/** Dokumentierte Ausnahme (Doppelschicht, Planungsannahme PA-03/OP-07): Genehmiger und Grund aus der Eingabe, der Zeitpunkt kommt vom Server. */
data class ShiftOverlapException(val approvedBy: String, val reason: String, val approvedAt: Instant)

/** Eingabe der Ausnahme; unvollständige Angaben (Genehmiger oder Grund fehlt oder leer) heben das Verbot nicht auf. */
data class OverlapExceptionInput(val approvedBy: String?, val reason: String?) {
    val complete: Boolean get() = !approvedBy.isNullOrBlank() && !reason.isNullOrBlank() && reason.trim().length >= 3
}

/**
 * Anlegen oder Ändern einer Schichtzuweisung der Leitstelle. `starts_at` und `ends_at` sind Pflicht mit Offset (RFC 3339), `ends_at` liegt nach `starts_at`; die Dauer
 * folgt aus den absoluten Zeitpunkten, `timezone` (IANA) dient nur der Anzeige. Der Mandant steht nie im Body. [state] folgt beim Ändern aus `withdrawn` (`CANCELLED` bzw. `PLANNED`); Antritt und Ende gehören dem Fahrer (WP-M3-25).
 */
data class ShiftInput(
    val driverId: UUID, val vehicleId: UUID, val startsAt: Instant, val endsAt: Instant, val timezone: String, val state: ShiftState?, val overlapException: OverlapExceptionInput?,
) {
    companion object {
        private val ALLOWED = setOf("driver_id", "vehicle_id", "starts_at", "ends_at", "timezone", "withdrawn", "overlap_exception")

        fun parse(raw: Map<String, Any?>, update: Boolean): ShiftInput {
            val v = mutableListOf<Violation>()
            raw.keys.forEach { if (it !in ALLOWED) v += Violation("/$it", ViolationCode.UNKNOWN_FIELD) }
            fun uuid(key: String): UUID? {
                val s = raw[key] ?: run { v += Violation("/$key", ViolationCode.REQUIRED); return null }
                val t = s as? String ?: run { v += Violation("/$key", ViolationCode.TYPE_MISMATCH); return null }
                return runCatching { UUID.fromString(t) }.getOrNull() ?: run { v += Violation("/$key", ViolationCode.INVALID_FORMAT); null }
            }
            fun instant(key: String): Instant? {
                val c = Rfc3339.parse(raw[key] as? String, "/$key")
                if (raw[key] != null && raw[key] !is String) { v += Violation("/$key", ViolationCode.TYPE_MISMATCH); return null }
                if (!c.ok) { v += c.violations; return null }
                return c.value!!.toInstant()
            }
            val driver = uuid("driver_id"); val vehicle = uuid("vehicle_id")
            val from = instant("starts_at"); val to = instant("ends_at")
            if (from != null && to != null && !to.isAfter(from)) v += Violation("/ends_at", ViolationCode.INTERVAL_INVALID)
            val zone = (raw["timezone"] as? String)?.let { z -> IanaZones.check(z, "/timezone").also { if (!it.ok) v += it.violations }.value?.id }
                ?: run { if (raw["timezone"] == null) v += Violation("/timezone", ViolationCode.REQUIRED) else if (raw["timezone"] !is String) v += Violation("/timezone", ViolationCode.TYPE_MISMATCH); null }
            // `withdrawn` entzieht (CANCELLED) bzw. erneuert (PLANNED) die Zuweisung; der Client setzt nie unmittelbar einen Zustand (SYS-011). Nur beim Ändern.
            var state: ShiftState? = null
            if (raw.containsKey("withdrawn")) {
                val w = raw["withdrawn"] as? Boolean
                if (w == null) v += Violation("/withdrawn", ViolationCode.TYPE_MISMATCH)
                else if (!update) v += Violation("/withdrawn", ViolationCode.UNKNOWN_FIELD)
                else state = if (w) ShiftState.CANCELLED else ShiftState.PLANNED
            }
            var exception: OverlapExceptionInput? = null
            if (raw.containsKey("overlap_exception") && raw["overlap_exception"] != null) {
                @Suppress("UNCHECKED_CAST") val o = raw["overlap_exception"] as? Map<String, Any?>
                if (o == null) v += Violation("/overlap_exception", ViolationCode.TYPE_MISMATCH)
                else {
                    o.keys.forEach { if (it != "approved_by" && it != "reason") v += Violation("/overlap_exception/$it", ViolationCode.UNKNOWN_FIELD) }
                    val by = o["approved_by"] as? String; val reason = o["reason"] as? String
                    if (by != null && by.length > 200) v += Violation("/overlap_exception/approved_by", ViolationCode.TOO_LONG)
                    if (reason != null && reason.length > 500) v += Violation("/overlap_exception/reason", ViolationCode.TOO_LONG)
                    exception = OverlapExceptionInput(by, reason)
                }
            }
            if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray())
            return ShiftInput(driver!!, vehicle!!, from!!, to!!, zone!!, state, exception)
        }
    }
}

/** Schichtzuweisung, wie sie die Leitstelle sieht. [durationMinutes] folgt aus den absoluten Zeitpunkten (nie aus der Wandzeit der [timezone]). */
data class ShiftView(
    val id: UUID, val driverId: UUID, val vehicleId: UUID, val startsAt: Instant, val endsAt: Instant, val timezone: String, val state: String, val revision: Long,
    val rosterRevision: Long, val overlapException: ShiftOverlapException?, val startedAt: Instant?, val endedAt: Instant?,
) {
    val durationMinutes: Long get() = Duration.between(startsAt, endsAt).toMinutes()
}

/**
 * Schichtzuweisung der Leitstelle (WP-M3-07, LH-6.8-FLT-003, Modul `staff-time`, OP-07: kein Dienstplan vor M4, `roster_revision` ist der Platzhalter 0).
 * Anlegen und Ändern prüfen Überlappung je Fahrer und je Fahrzeug über das halboffene Intervall `[starts_at, ends_at)` unter Sperre (Datenbank-Trigger, auch für jeden
 * anderen Schreibpfad); Ende = Beginn ist keine Überlappung. Überlappung ohne vollständige dokumentierte Ausnahme: 409 `CONFLICT` mit Ursache (`/driver_id` oder `/vehicle_id`)
 * und kollidierender Schicht im Detail; Änderung prüft gegen alle anderen Schichten, nie gegen sich selbst. Fremde Schicht, Fahrer oder Fahrzeug: 404. Jede Änderung hat
 * genau ein Audit; die Revision ist der ETag (`If-Match`, veraltet 412).
 */
interface Shifts {
    fun create(ctx: RequestContext, input: ShiftInput): ShiftView
    fun get(ctx: RequestContext, shiftId: UUID): ShiftView
    fun list(ctx: RequestContext, driverId: UUID?, vehicleId: UUID?, from: Instant?, to: Instant?, limit: Int, after: Pair<Instant, UUID>?): List<ShiftView>
    fun update(ctx: RequestContext, shiftId: UUID, expectedRevision: Long, input: ShiftInput): ShiftView
}
