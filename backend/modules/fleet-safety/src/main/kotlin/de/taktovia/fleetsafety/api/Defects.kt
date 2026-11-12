package de.taktovia.fleetsafety.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Kataloge und Grenzen der Schadenakte (Detailvertrag `docs/umsetzungsplan/vertraege/m3-schadenakte.md`). Das Pflichtenheft legt keinen Katalog fest (LH-6.22-DMG-002,
 * DMG-004): die Werte sind **Planungsannahmen, nicht freigegeben**; Erweiterungen sind additiv (neue Codes), nie umbenennend.
 */
object DefectCatalog {
    /** Kategorie (DMG-002): Bauteilgruppe, in der der Schaden liegt. */
    val CATEGORIES: Set<String> = linkedSetOf("BODY", "GLASS", "TIRES_WHEELS", "LIGHTS", "BRAKES", "DRIVETRAIN", "STEERING_SUSPENSION", "ELECTRICAL_HV", "INTERIOR",
        "ACCESSIBILITY_EQUIPMENT", "SAFETY_EQUIPMENT", "CLEANLINESS", "OTHER")
    /** Art des Schadens (DMG-004). */
    val KINDS: Set<String> = linkedSetOf("SCRATCH", "DENT", "CRACK_BREAK", "MALFUNCTION", "MISSING_PART", "LEAK", "WEAR", "CONTAMINATION", "OTHER")
    /** Fahrzeugbereich (DMG-004). */
    val AREAS: Set<String> = linkedSetOf("FRONT", "REAR", "LEFT_SIDE", "RIGHT_SIDE", "ROOF", "UNDERBODY", "WHEELS_TIRES", "INTERIOR_PASSENGER", "INTERIOR_DRIVER", "LUGGAGE",
        "WHEELCHAIR_AREA", "ENGINE_BATTERY", "OTHER")
    /** Beobachtete Auswirkung (DMG-004). */
    val EFFECTS: Set<String> = linkedSetOf("NONE_VISIBLE", "COSMETIC_ONLY", "FUNCTION_LIMITED", "NOT_ROADWORTHY", "FLUID_LEAK", "WARNING_LIGHT", "NOISE_VIBRATION", "OTHER")
    /** Form des Ereignisorts (OP-22). */
    val LOCATION_KINDS: Set<String> = linkedSetOf("COORDINATE", "ADDRESS_TEXT", "FREE_TEXT", "DEPOT")
    val SEVERITIES: Set<String> = linkedSetOf("SAFETY_CRITICAL", "OPERATIONALLY_RELEVANT", "COSMETIC", "OTHER")
    val STATUSES: List<String> = listOf("NEW", "REVIEWED", "IN_PROGRESS", "REPAIR", "CLOSED", "DISMISSED")

    const val MAX_DESCRIPTION = 2000
    const val MAX_LOCATION = 200
    const val MAX_REASON = 500
    const val MAX_EVIDENCE = 10
    /** Toleranz der Gerätezeit gegenüber der Serverzeit für „Ereigniszeit nicht in der Zukunft“ (wie der Datenbanktrigger). */
    const val FUTURE_TOLERANCE_SECONDS = 60L
    /** Fahrer-Bezug: Schicht für das Fahrzeug läuft, beginnt in höchstens [DRIVER_PRE_SHIFT_HOURS] Stunden oder endete vor höchstens [DRIVER_POST_SHIFT_HOURS] Stunden (Annahme, OP-34). */
    const val DRIVER_PRE_SHIFT_HOURS = 2L
    const val DRIVER_POST_SHIFT_HOURS = 24L
}

/** Erfassungsweg: [STANDARD] Mitarbeiter (UI-11), [GUIDED] geführte mobile Erfassung (UI-10, DMG-004) mit Pflichtangaben Art, Bereich, Auswirkung und mindestens einem Foto. */
enum class CaptureMode { STANDARD, GUIDED }

/**
 * Meldung eines Schadens. Mandant, Meldender, Meldezeitpunkt, Fallnummer und Anfangsstatus setzt der Server; sie sind keine Felder dieser Anfrage (unbekannte Felder: 422).
 * [parse] prüft eine untypisierte JSON-Eingabe vollständig und sammelt **alle** Feldfehler mit JSON-Pointer (422).
 */
data class ReportDefect(
    val vehicleId: UUID, val occurredAt: Instant, val locationKind: String?, val locationValue: String?, val categoryCode: String, val description: String,
    val kind: String?, val vehicleArea: String?, val observedEffect: String?, val sourceInspectionId: UUID?, val sourceStepCode: String?,
    val evidenceIds: List<UUID>, val deviceEventId: UUID?, val severityHint: String?, val captureMode: CaptureMode,
) {
    companion object {
        private val FIELDS = setOf("vehicle_id", "occurred_at", "location_kind", "location_value", "category_code", "description", "kind", "vehicle_area", "observed_effect",
            "source_inspection_id", "source_step_code", "evidence_ids", "device_event_id", "severity_hint", "capture_mode")

        fun parse(raw: Map<String, Any?>): ReportDefect {
            val v = Violations()
            v.unknown(raw, FIELDS, "")
            val vehicle = v.uuid(raw, "vehicle_id", true)
            val occurred = v.instant(raw, "occurred_at", true)
            val category = v.code(raw, "category_code", DefectCatalog.CATEGORIES, true)
            val description = v.text(raw, "description", DefectCatalog.MAX_DESCRIPTION, true)
            val locKind = v.code(raw, "location_kind", DefectCatalog.LOCATION_KINDS, false)
            val locValue = v.text(raw, "location_value", DefectCatalog.MAX_LOCATION, false)
            if ((locKind == null) != (locValue == null)) v.add(if (locKind == null) "/location_kind" else "/location_value", ViolationCode.REQUIRED)
            val mode = when (val m = raw["capture_mode"]) {
                null -> CaptureMode.STANDARD
                is String -> CaptureMode.entries.firstOrNull { it.name == m } ?: run { v.add("/capture_mode", ViolationCode.NOT_IN_ENUM); CaptureMode.STANDARD }
                else -> { v.add("/capture_mode", ViolationCode.TYPE_MISMATCH); CaptureMode.STANDARD }
            }
            val guided = mode == CaptureMode.GUIDED
            val kind = v.code(raw, "kind", DefectCatalog.KINDS, guided)
            val area = v.code(raw, "vehicle_area", DefectCatalog.AREAS, guided)
            val effect = v.code(raw, "observed_effect", DefectCatalog.EFFECTS, guided)
            val inspection = v.uuid(raw, "source_inspection_id", false)
            val step = v.text(raw, "source_step_code", 64, false)
            val evidence = v.uuidList(raw, "evidence_ids", DefectCatalog.MAX_EVIDENCE)
            if (guided && evidence.isEmpty() && !v.has("/evidence_ids")) v.add("/evidence_ids", ViolationCode.TOO_FEW_ITEMS)
            val deviceEvent = v.uuid(raw, "device_event_id", false)
            val hint = v.code(raw, "severity_hint", DefectCatalog.SEVERITIES, false)
            v.throwIfAny()
            return ReportDefect(vehicle!!, occurred!!, locKind, locValue, category!!, description!!, kind, area, effect, inspection, step, evidence, deviceEvent, hint, mode)
        }
    }
}

/** Anzeige eines Fotos an der Akte: nur freigegebene Fotos sind abrufbar; Fotos in Prüfung zeigt die Akte als Zähler (`evidencePending`). */
data class EvidenceView(
    val id: UUID, val mime: String, val sizeBytes: Long, val sha256: String, val capturedAt: Instant, val receivedAt: Instant, val addedInVersion: Int, val replacesId: UUID?,
)

/** Die Schadenakte, wie gespeichert (aktueller Stand des Meldungsinhalts, [contentVersion] = Version des Inhalts). */
data class DefectView(
    val id: UUID, val caseNumber: String, val vehicleId: UUID, val reportedBy: UUID, val reportedAt: Instant, val occurredAt: Instant, val locationKind: String?, val locationValue: String?,
    val categoryCode: String, val kind: String?, val vehicleArea: String?, val observedEffect: String?, val description: String?, val severity: String?, val status: String,
    val assigneeRef: UUID?, val sourceInspectionId: UUID?, val sourceStepCode: String?, val contentVersion: Int, val revision: Long, val updatedAt: Instant, val createdAt: Instant,
    val evidence: List<EvidenceView> = emptyList(), val evidencePending: Int = 0, val blocksVehicle: Boolean = false,
)

/** Ergebnis einer Meldung: [created] ist `false`, wenn dieselbe Geräteereignis-ID bereits eine Akte erzeugt hat (dieselbe Akte, keine zweite). */
data class ReportedDefect(val defect: DefectView, val created: Boolean)

data class DefectFilter(val vehicleId: UUID? = null, val status: String? = null, val severity: String? = null, val reportedFrom: Instant? = null, val reportedTo: Instant? = null)

/**
 * Bezug eines Fahrers zu einem Fahrzeug (WP-M3-43 Kriterium 7): die Meldung verlangt weder Buchung noch laufende Prüfung, aber einen Bezug des Fahrers zum Fahrzeug über
 * eine Schicht (laufend, in [DefectCatalog.DRIVER_PRE_SHIFT_HOURS] h beginnend oder vor höchstens [DefectCatalog.DRIVER_POST_SHIFT_HOURS] h beendet). Die Standardumsetzung liest
 * `staff_shift` lesend in der laufenden Mandantentransaktion (Annahme: `driver_ref` = IdP-Subject, wie in `staff-time`); sie schreibt nichts.
 */
fun interface DriverVehicleRelation {
    fun hasRelation(jdbc: JdbcOperations, driverSubject: String, vehicleId: UUID, now: Instant): Boolean
}

/**
 * Schadenakte (WP-M3-43). Fahrer und berechtigte Mitarbeiter (Rollen `driver`, `dispatcher`, `workshop`) melden unabhängig von Buchung, Schicht und Prüfung; ein Fahrer nur für
 * Fahrzeuge mit Bezug, sonst 404 (kein Hinweis auf die Existenz). Fremder Mandant, fremdes Fahrzeug, fremde Akte: 404. Anlage, Fallnummer, Audit `fleet.defect.reported` und Outbox
 * `defect.reported` in einer Transaktion. Fahrer sehen nur eigene Akten (DMG-011).
 */
interface Defects {
    fun report(ctx: RequestContext, cmd: ReportDefect): ReportedDefect
    fun get(ctx: RequestContext, id: UUID): DefectView
    fun list(ctx: RequestContext, filter: DefectFilter, limit: Int, after: Pair<Instant, UUID>?): List<DefectView>
}

/** Hilfen zum Lesen untypisierter JSON-Werte (Feldfehler mit Pointer). */
internal fun Violations.has(pointer: String) = list.any { it.pointer == pointer }

internal fun Violations.uuid(raw: Map<*, *>, key: String, required: Boolean): UUID? {
    val x = raw[key]
    if (x == null) { if (required) add("/$key", ViolationCode.REQUIRED); return null }
    return (x as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: run { add("/$key", if (x is String) ViolationCode.INVALID_FORMAT else ViolationCode.TYPE_MISMATCH); null }
}

internal fun Violations.uuidList(raw: Map<*, *>, key: String, max: Int): List<UUID> {
    val x = raw[key] ?: return emptyList()
    val l = x as? List<*> ?: run { add("/$key", ViolationCode.TYPE_MISMATCH); return emptyList() }
    if (l.size > max) { add("/$key", ViolationCode.TOO_MANY_ITEMS); return emptyList() }
    val out = linkedSetOf<UUID>()
    l.forEachIndexed { i, e ->
        val u = (e as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (u == null) add("/$key/$i", ViolationCode.INVALID_FORMAT) else if (!out.add(u)) add("/$key/$i", ViolationCode.NOT_UNIQUE)
    }
    return out.toList()
}

/** Zeitpunkt mit UTC-Offset (RFC 3339); ohne Offset oder ungültig: Feldfehler. */
internal fun Violations.instant(raw: Map<*, *>, key: String, required: Boolean): Instant? {
    val x = raw[key]
    if (x == null) { if (required) add("/$key", ViolationCode.REQUIRED); return null }
    if (x !is String) { add("/$key", ViolationCode.TYPE_MISMATCH); return null }
    return try { OffsetDateTime.parse(x).toInstant() } catch (e: DateTimeParseException) {
        add("/$key", if (Regex("^\\d{4}-\\d{2}-\\d{2}T[0-9:.]+$").matches(x)) ViolationCode.OFFSET_REQUIRED else ViolationCode.INVALID_FORMAT); null
    }
}

internal fun Violations.text(raw: Map<*, *>, key: String, max: Int, required: Boolean): String? {
    val x = raw[key]
    if (x == null) { if (required) add("/$key", ViolationCode.REQUIRED); return null }
    if (x !is String) { add("/$key", ViolationCode.TYPE_MISMATCH); return null }
    if (x.isBlank()) { add("/$key", ViolationCode.TOO_SHORT); return null }
    if (x.length > max) { add("/$key", ViolationCode.TOO_LONG); return null }
    return x.trim()
}

internal fun Violations.code(raw: Map<*, *>, key: String, allowed: Set<String>, required: Boolean): String? {
    val x = raw[key]
    if (x == null) { if (required) add("/$key", ViolationCode.REQUIRED); return null }
    if (x !is String) { add("/$key", ViolationCode.TYPE_MISMATCH); return null }
    if (x !in allowed) { add("/$key", ViolationCode.NOT_IN_ENUM); return null }
    return x
}
