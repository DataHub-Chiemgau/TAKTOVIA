package de.taktovia.servicegeo.internal

import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Prüfung von `stop_revision.accessibility` gegen das Schema `stop-accessibility.v1` (`docs/umsetzungsplan/vertraege/schemas/stop-accessibility.v1.schema.json`; ein Test hält
 * beide synchron): Pflichtfelder Schema-Version `1.0`, `step_free` und `wheelchair_boarding`, jedes Merkmal `YES`, `NO` oder `UNVERIFIED`; keine weiteren Felder.
 * `UNVERIFIED` ist ausdrücklich nicht `YES` (OP-41).
 */
internal object StopAccessibility {
    const val SCHEMA_VERSION = "1.0"
    val TRI = setOf("YES", "NO", "UNVERIFIED")
    val FEATURES = listOf("step_free", "wheelchair_boarding", "tactile_guidance", "shelter")
    private val REQUIRED = setOf("schema_version", "step_free", "wheelchair_boarding")
    private val KNOWN = REQUIRED + FEATURES + setOf("verified_at", "note")

    fun check(raw: Any?, pointer: String = "/accessibility"): List<Violation> {
        val m = raw as? Map<*, *> ?: return listOf(Violation(pointer, if (raw == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH))
        val v = mutableListOf<Violation>()
        m.keys.filter { it !in KNOWN }.forEach { v += Violation("$pointer/$it", ViolationCode.UNKNOWN_FIELD) }
        REQUIRED.filter { m[it] == null }.forEach { v += Violation("$pointer/$it", ViolationCode.REQUIRED) }
        if (m["schema_version"] != null && m["schema_version"] != SCHEMA_VERSION) v += Violation("$pointer/schema_version", ViolationCode.NOT_IN_ENUM)
        for (f in FEATURES) if (m[f] != null && m[f] !in TRI) v += Violation("$pointer/$f", ViolationCode.NOT_IN_ENUM)
        (m["verified_at"] as? String)?.let { try { OffsetDateTime.parse(it) } catch (_: DateTimeParseException) { v += Violation("$pointer/verified_at", ViolationCode.INVALID_FORMAT) } }
        if (m.containsKey("verified_at") && m["verified_at"] !is String) v += Violation("$pointer/verified_at", ViolationCode.TYPE_MISMATCH)
        (m["note"]).let { if (m.containsKey("note")) { if (it !is String) v += Violation("$pointer/note", ViolationCode.TYPE_MISMATCH) else if (it.length > 200) v += Violation("$pointer/note", ViolationCode.TOO_LONG) } }
        return v
    }
}
