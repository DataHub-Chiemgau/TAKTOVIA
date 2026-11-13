package de.taktovia.fleetsafety.api

import de.taktovia.platform.alert.BusinessAlertResult
import java.time.Instant
import java.util.UUID

/** Die vier Klassen der Schadenakte (LH-6.22-DMG-005, OP-10); `SAFETY_CRITICAL` entspricht „S0“ aus PH-AT-020. Rang: höher = schwerer. */
object DefectSeverities {
    const val SAFETY_CRITICAL = "SAFETY_CRITICAL"
    const val OPERATIONALLY_RELEVANT = "OPERATIONALLY_RELEVANT"
    const val COSMETIC = "COSMETIC"
    const val OTHER = "OTHER"

    fun rank(severity: String?): Int = when (severity) {
        SAFETY_CRITICAL -> 3; OPERATIONALLY_RELEVANT -> 2; COSMETIC -> 1; OTHER -> 0; else -> -1
    }
}

/** Ergebnis der Regelklassifikation: Klasse und angewandte Regel ([ruleId]). */
data class RuleResult(val severity: String, val ruleId: String)

/**
 * Regelmatrix der Klassifikation (Kategorie/Art/Bereich/Auswirkung -> Klasse), **Planungsannahme, nicht freigegeben** (Detailvertrag `m3-schadenakte.md` §7, OP-10, OP-34). Die Regeln werden
 * der Reihe nach geprüft, die erste zutreffende gilt; unbekannte Angaben (fehlende Auswirkung in der Standarderfassung) werden **vorsichtig** gewertet (bei Bremse, Lenkung/Fahrwerk, Reifen/Räder,
 * Hochvolt: sicherheitskritisch). Eine Änderung der Matrix erhöht [VERSION]; jede Akte nennt die Version, nach der sie vorgeschlagen wurde. Reine Funktion, ohne Zugriff auf Daten.
 */
object DefectClassification {
    const val VERSION = "dmg-klassen-v1"

    private val UNAFFECTED = setOf("COSMETIC_ONLY", "NONE_VISIBLE")
    private val DEGRADING = setOf("FUNCTION_LIMITED", "WARNING_LIGHT", "FLUID_LEAK", "NOISE_VIBRATION")

    private class Rule(val id: String, val severity: String, val matches: (category: String, kind: String?, area: String?, effect: String?) -> Boolean)

    private val RULES: List<Rule> = listOf(
        Rule("R01", DefectSeverities.SAFETY_CRITICAL) { _, _, _, e -> e == "NOT_ROADWORTHY" },
        Rule("R02", DefectSeverities.SAFETY_CRITICAL) { c, _, _, e -> (c == "BRAKES" || c == "STEERING_SUSPENSION") && e !in UNAFFECTED },
        Rule("R03", DefectSeverities.SAFETY_CRITICAL) { c, k, _, e -> c == "TIRES_WHEELS" && (e == null || e in DEGRADING || k == "CRACK_BREAK" || k == "LEAK") },
        Rule("R04", DefectSeverities.SAFETY_CRITICAL) { c, _, _, e -> c == "ELECTRICAL_HV" && e !in UNAFFECTED },
        Rule("R05", DefectSeverities.SAFETY_CRITICAL) { c, k, _, e -> c == "SAFETY_EQUIPMENT" && (e == "FUNCTION_LIMITED" || k == "MISSING_PART" || k == "MALFUNCTION") },
        Rule("R06", DefectSeverities.SAFETY_CRITICAL) { c, k, a, _ -> c == "GLASS" && k == "CRACK_BREAK" && a == "FRONT" },
        Rule("R10", DefectSeverities.OPERATIONALLY_RELEVANT) { c, _, _, _ -> c == "BRAKES" || c == "STEERING_SUSPENSION" || c == "ELECTRICAL_HV" || c == "TIRES_WHEELS" || c == "SAFETY_EQUIPMENT" },
        Rule("R11", DefectSeverities.OPERATIONALLY_RELEVANT) { _, _, _, e -> e in DEGRADING },
        Rule("R12", DefectSeverities.OPERATIONALLY_RELEVANT) { c, _, _, _ -> c == "LIGHTS" || c == "DRIVETRAIN" || c == "ACCESSIBILITY_EQUIPMENT" },
        Rule("R13", DefectSeverities.OPERATIONALLY_RELEVANT) { c, k, _, _ -> c != "OTHER" && (k == "MISSING_PART" || k == "MALFUNCTION" || k == "CRACK_BREAK" || k == "LEAK") },
        Rule("R20", DefectSeverities.COSMETIC) { c, _, _, _ -> c == "BODY" || c == "INTERIOR" || c == "GLASS" || c == "CLEANLINESS" },
    )

    fun classify(category: String, kind: String?, area: String?, effect: String?): RuleResult {
        val r = RULES.firstOrNull { it.matches(category, kind, area, effect) } ?: return RuleResult(DefectSeverities.OTHER, "R90")
        return RuleResult(r.severity, r.id)
    }

    /** Regelmatrix als Anzeige für die Triageansicht: Regelkennung und Klasse in Prüfreihenfolge. */
    fun matrix(): List<Pair<String, String>> = RULES.map { it.id to it.severity } + ("R90" to DefectSeverities.OTHER)
}

/**
 * Parameter `workshop_auto_block_severity` (Ebene Mandant; Planungsdefault `SAFETY_CRITICAL`, OP-10): ab dieser Klasse sperrt die Klassifikation das Fahrzeug. Validierung „Kritischer
 * Sicherheitsschaden immer sperrend“: zulässig sind nur die vier Klassen; da `SAFETY_CRITICAL` die schwerste ist, sperrt sie bei **jedem** zulässigen Wert. Jeder andere Wert (leer, „NONE“, „OFF“,
 * unbekannte Klasse, `S1`) wird abgewiesen, der Parameter lässt sich also nicht so setzen, dass sicherheitskritische Schäden nicht sperren. Der Wert kommt aus der Konfiguration
 * (`taktovia.defects.workshop-auto-block-severity`); der Parameterkatalog ist eingefroren (wie bei [AlertSettings]).
 */
data class DefectSettings(val autoBlockFrom: String = DefectSeverities.SAFETY_CRITICAL) {
    init { require(DefectSeverities.rank(autoBlockFrom) >= 0) { "workshop_auto_block_severity: unzulässiger Wert (zulässig: SAFETY_CRITICAL, OPERATIONALLY_RELEVANT, COSMETIC, OTHER)" } }

    /** Sperrt die Klasse [severity] das Fahrzeug? */
    fun blocks(severity: String?): Boolean = DefectSeverities.rank(severity) >= DefectSeverities.rank(autoBlockFrom)

    companion object {
        fun parse(raw: String?): DefectSettings = DefectSettings(raw?.trim() ?: DefectSeverities.SAFETY_CRITICAL)
    }
}

/**
 * Triage: Bestätigung oder Korrektur der Klasse, Zuständiger und Folgeentscheidung (`DefectTriage` in `api/fleet-safety.yaml`). [assigneeGiven] unterscheidet „Zuständigen nicht angegeben“
 * (bleibt) von `assignee_ref: null` (wird entfernt). Eine Korrektur der Klasse verlangt [reason].
 */
data class TriageDefect(val severity: String, val decision: String, val assigneeGiven: Boolean, val assignee: UUID?, val reason: String?) {
    companion object {
        private val FIELDS = setOf("severity", "decision", "assignee_ref", "reason")
        val DECISIONS = setOf("REVIEWED", "IN_PROGRESS", "DISMISSED")

        fun parse(raw: Map<String, Any?>): TriageDefect {
            val v = Violations()
            v.unknown(raw, FIELDS, "")
            val severity = v.code(raw, "severity", DefectCatalog.SEVERITIES, true)
            val decision = v.code(raw, "decision", DECISIONS, true)
            val given = raw.containsKey("assignee_ref")
            val assignee = if (given && raw["assignee_ref"] != null) v.uuid(raw, "assignee_ref", false) else null
            val reason = if (raw["reason"] == null) null else v.text(raw, "reason", DefectCatalog.MAX_REASON, false)
            v.throwIfAny()
            return TriageDefect(severity!!, decision!!, given, assignee, reason)
        }
    }
}

/** Ergebnis der Triage: [classificationChanged] = die Klasse hat sich geändert; [block]/[alert] nur, wenn diese Einstufung die Sperre ausgelöst hat. */
data class TriageResult(val defect: DefectView, val classificationChanged: Boolean, val block: BlockOutcome?, val alert: BusinessAlertResult?)

/** Eintrag der Klassifikationshistorie (append-only): [fromSeverity] ist `null` bei der Erstklassifikation. */
data class ClassificationEntry(
    val id: UUID, val defectId: UUID, val fromSeverity: String?, val toSeverity: String, val ruleVersion: String, val ruleId: String?, val source: String, val reason: String?,
    val assigneeBefore: UUID?, val assigneeAfter: UUID?, val actor: String, val defectRevision: Long, val occurredAt: Instant,
)

/** Art eines Ereignisses der Aktenhistorie (AT-34: Klassifikation, Sperre und Zuweisung mit Akteur und Zeit). */
enum class TimelineKind { STATUS, CLASSIFICATION, ASSIGNMENT, VEHICLE_BLOCK }

/** Ein Ereignis der Aktenhistorie: [detail] nennt Codes (Statuswechsel `A>B`, Klassenwechsel `A>B`, Zuständiger, Sperrgrund), nie Freitext. */
data class TimelineEvent(val kind: TimelineKind, val occurredAt: Instant, val actor: String, val detail: String, val reason: String?)
