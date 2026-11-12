package de.taktovia.fleetsafety.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Eingabetypen eines Prüfelements (Schema `checklist-input-types.v1`, WP-M3-10): Status OK/Mangel/Nicht anwendbar, Zahl mit Grenzen, kurzer Text. */
enum class ChecklistInputKind(val code: String) {
    OK_DEFECT_NA("OK_DEFECT_NA"), NUMBER("NUMBER"), TEXT_SHORT("TEXT_SHORT"),
    /** Schichtstartwerte (WP-M3-17, CHK-008): Kilometerstand (ganze Zahl), Energie/Tank in Prozent 0 bis 100, Fahrzeugidentität (Eingabe) mit Abgleich gegen das zugewiesene Fahrzeug. */
    ODOMETER_KM("ODOMETER_KM"), ENERGY_PCT("ENERGY_PCT"), VEHICLE_IDENTITY("VEHICLE_IDENTITY");
    /** Schichtstartwert: Statuswert Mangel ist hier nicht sinnvoll, der Wert selbst wird geprüft. */
    val isStartValue: Boolean get() = this == ODOMETER_KM || this == ENERGY_PCT || this == VEHICLE_IDENTITY
    companion object { fun parse(code: String?): ChecklistInputKind? = entries.firstOrNull { it.code == code } }
}

/** Zustand einer Vorlagenrevision: Entwurf, freigegeben (wirksam), abgelöst. */
enum class ChecklistTemplateState { DRAFT, PUBLISHED, RETIRED }

/** Zulässige Antwortstatus je Prüfelement (LH-6.21-CHK-003). `NOT_CHECKABLE` ist durch OP-16 nur vorgehalten und wird bis zur Entscheidung abgewiesen. */
object ChecklistAnswerStatus {
    const val OK = "OK"; const val DEFECT = "DEFECT"; const val NOT_APPLICABLE = "NOT_APPLICABLE"
    val ALLOWED = setOf(OK, DEFECT, NOT_APPLICABLE)
}

object ChecklistLimits { const val MAX_STEPS = 60; const val MAX_TITLE = 200; const val MAX_HELP = 1000; const val MAX_TEXT_ANSWER = 200; const val MAX_NAME = 120 }

/** Ein Prüfelement in der Eingabe: Titel, Eingabetyp und Pflichtstatus sind Pflicht; Hilfetext optional (OP, leer zulässig). */
data class ChecklistStepInput(
    val code: String, val title: String, val helpText: String?, val kind: ChecklistInputKind, val mandatory: Boolean, val safetyCritical: Boolean,
    val naRequiresReason: Boolean, val photoRequiredOnDefect: Boolean, val numberMin: BigDecimal?, val numberMax: BigDecimal?, val numberUnit: String?,
)

/** Eingabe einer Vorlage (Entwurf): Name, Fahrzeugklasse, Bedienart und mindestens ein Prüfelement. Die Reihenfolge der Liste ist die Anzeigereihenfolge. */
data class ChecklistTemplateInput(val name: String, val vehicleClass: String, val serviceMode: String, val steps: List<ChecklistStepInput>) {
    companion object {
        private val FIELDS = setOf("name", "vehicle_class", "service_mode", "steps")
        private val STEP_FIELDS = setOf("step_code", "title", "help_text", "input_kind", "mandatory", "safety_critical", "na_requires_reason", "photo_required_on_defect", "number_min", "number_max", "number_unit")
        private val CLASS = Regex("^[A-Z][A-Z0-9_]{1,39}$")
        private val CODE = Regex("^[A-Z][A-Z0-9_]{1,59}$")

        /** Prüft eine untypisierte Eingabe vollständig (422 mit Feldverweis): ein Prüfelement ohne Titel, Eingabetyp oder Pflichtstatus ist nicht speicherbar (CHK-003). */
        fun parse(raw: Map<String, Any?>): ChecklistTemplateInput {
            val v = Violations()
            v.unknown(raw, FIELDS, "")
            val name = (raw["name"] as? String)?.also { if (it.isBlank()) v.add("/name", ViolationCode.TOO_SHORT) else if (it.length > ChecklistLimits.MAX_NAME) v.add("/name", ViolationCode.TOO_LONG) }
                ?: run { v.add("/name", if (raw["name"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH); null }
            val cls = (raw["vehicle_class"] as? String)?.also { if (!CLASS.matches(it)) v.add("/vehicle_class", ViolationCode.PATTERN_MISMATCH) }
                ?: run { v.add("/vehicle_class", if (raw["vehicle_class"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH); null }
            val mode = (raw["service_mode"] as? String)?.also { if (!CLASS.matches(it)) v.add("/service_mode", ViolationCode.PATTERN_MISMATCH) }
                ?: run { v.add("/service_mode", if (raw["service_mode"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH); null }
            val steps = mutableListOf<ChecklistStepInput>()
            val rawSteps = raw["steps"]
            if (rawSteps == null) v.add("/steps", ViolationCode.REQUIRED)
            else if (rawSteps !is List<*>) v.add("/steps", ViolationCode.TYPE_MISMATCH)
            else {
                if (rawSteps.isEmpty()) v.add("/steps", ViolationCode.TOO_FEW_ITEMS)
                if (rawSteps.size > ChecklistLimits.MAX_STEPS) v.add("/steps", ViolationCode.TOO_MANY_ITEMS)
                val seen = HashSet<String>()
                rawSteps.take(ChecklistLimits.MAX_STEPS).forEachIndexed { i, s -> step(s, "/steps/$i", v, seen)?.let(steps::add) }
            }
            v.throwIfAny()
            return ChecklistTemplateInput(name!!, cls!!, mode!!, steps)
        }

        private fun step(raw: Any?, p: String, v: Violations, seen: MutableSet<String>): ChecklistStepInput? {
            if (raw !is Map<*, *>) { v.add(p, ViolationCode.TYPE_MISMATCH); return null }
            val before = v.list.size
            v.unknown(raw, STEP_FIELDS, p)
            val code = (raw["step_code"] as? String)?.also {
                if (!CODE.matches(it)) v.add("$p/step_code", ViolationCode.PATTERN_MISMATCH) else if (!seen.add(it)) v.add("$p/step_code", ViolationCode.NOT_UNIQUE)
            } ?: run { v.add("$p/step_code", if (raw["step_code"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH); null }
            val title = (raw["title"] as? String)?.also { if (it.isBlank()) v.add("$p/title", ViolationCode.TOO_SHORT) else if (it.length > ChecklistLimits.MAX_TITLE) v.add("$p/title", ViolationCode.TOO_LONG) }
                ?: run { v.add("$p/title", if (raw["title"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH); null }
            val help = when (val h = raw["help_text"]) {
                null -> null
                is String -> { if (h.length > ChecklistLimits.MAX_HELP) v.add("$p/help_text", ViolationCode.TOO_LONG); h }
                else -> { v.add("$p/help_text", ViolationCode.TYPE_MISMATCH); null }
            }
            val kind = when (val k = raw["input_kind"]) {
                null -> { v.add("$p/input_kind", ViolationCode.REQUIRED); null }
                is String -> ChecklistInputKind.parse(k) ?: run { v.add("$p/input_kind", ViolationCode.NOT_IN_ENUM); null }
                else -> { v.add("$p/input_kind", ViolationCode.TYPE_MISMATCH); null }
            }
            fun bool(key: String, required: Boolean): Boolean? = when (val b = raw[key]) {
                null -> { if (required) v.add("$p/$key", ViolationCode.REQUIRED); if (required) null else false }
                is Boolean -> b
                else -> { v.add("$p/$key", ViolationCode.TYPE_MISMATCH); null }
            }
            val mandatory = bool("mandatory", true)
            val critical = bool("safety_critical", false)
            val na = bool("na_requires_reason", false)
            val photo = bool("photo_required_on_defect", false)
            val min = decimal(raw, "number_min", p, v); val max = decimal(raw, "number_max", p, v)
            val unit = when (val u = raw["number_unit"]) {
                null -> null
                is String -> { if (u.isBlank() || u.length > 20) v.add("$p/number_unit", if (u.isBlank()) ViolationCode.TOO_SHORT else ViolationCode.TOO_LONG); u }
                else -> { v.add("$p/number_unit", ViolationCode.TYPE_MISMATCH); null }
            }
            if (kind != null && kind != ChecklistInputKind.NUMBER) {
                listOf("number_min", "number_max", "number_unit").forEach { if (raw[it] != null) v.add("$p/$it", ViolationCode.INVALID_VALUE) }
            }
            if (min != null && max != null && min > max) v.add("$p/number_max", ViolationCode.INTERVAL_INVALID)
            if (v.list.size > before) return null
            return ChecklistStepInput(code!!, title!!, help?.takeIf { it.isNotEmpty() }, kind!!, mandatory!!, critical!!, na!!, photo!!, min, max, unit)
        }

        private fun decimal(raw: Map<*, *>, key: String, p: String, v: Violations): BigDecimal? = when (val n = raw[key]) {
            null -> null
            is Int, is Long, is Short, is Byte -> BigDecimal((n as Number).toLong())
            is Double -> if (n.isFinite()) BigDecimal.valueOf(n) else { v.add("$p/$key", ViolationCode.TYPE_MISMATCH); null }
            is BigDecimal -> n
            is java.math.BigInteger -> BigDecimal(n)
            else -> { v.add("$p/$key", ViolationCode.TYPE_MISMATCH); null }
        }
    }
}

/** Prüfelement einer gespeicherten Revision. [exampleImageName] ist der Name im mandantengetrennten Objektspeicher (nie eine URL). */
data class ChecklistStepView(
    val code: String, val position: Int, val title: String, val helpText: String?, val kind: ChecklistInputKind, val mandatory: Boolean, val safetyCritical: Boolean,
    val naRequiresReason: Boolean, val photoRequiredOnDefect: Boolean, val numberMin: BigDecimal?, val numberMax: BigDecimal?, val numberUnit: String?, val exampleImageName: String?,
) {
    fun rule() = ChecklistStepRule(code, kind, mandatory, naRequiresReason, numberMin, numberMax)
}

data class ChecklistTemplateView(
    val id: UUID, val templateId: UUID, val version: Int, val name: String?, val vehicleClass: String, val serviceMode: String, val state: ChecklistTemplateState,
    val steps: List<ChecklistStepView>, val createdBy: String, val approvedBy: String?, val publishedAt: Instant?, val revision: Long, val createdAt: Instant,
)

/** Beispielbild eines Prüfelements, aus dem Objektspeicher des eigenen Mandanten geliefert. */
class ChecklistImage(val bytes: ByteArray, val contentType: String)

/**
 * Checklistenvorlagen als unveränderliche Revisionen (WP-M3-10, LH-6.21-CHK-003): Entwurf anlegen und ändern, neue Revision aus einer freigegebenen, Freigabe mit Vier-Augen-Prinzip
 * (Freigebender ist nicht der Ersteller; die vorherige wirksame Revision wird abgelöst). Fremde Mandanten sind nicht sichtbar (404). Nebenläufigkeit: die Revisionszeile wird für jede
 * Änderung gesperrt; bei gleichzeitigen Freigaben derselben Revision gewinnt genau eine, die andere erhält 409; bei Änderung und Freigabe entscheidet die Sperrreihenfolge.
 */
interface ChecklistTemplates {
    fun create(ctx: RequestContext, input: ChecklistTemplateInput): ChecklistTemplateView
    fun get(ctx: RequestContext, revisionId: UUID): ChecklistTemplateView
    fun list(ctx: RequestContext, state: ChecklistTemplateState?, vehicleClass: String?, serviceMode: String?, limit: Int, after: Pair<Instant, UUID>?): List<ChecklistTemplateView>
    /** Ersetzt Inhalt des Entwurfs (If-Match-Revision); eine nicht mehr als Entwurf vorliegende Revision ist nicht änderbar (409). */
    fun updateDraft(ctx: RequestContext, revisionId: UUID, expectedRevision: Long, input: ChecklistTemplateInput): ChecklistTemplateView
    /** Neue Revision (Entwurf, Version + 1) aus einer freigegebenen oder abgelösten Revision; 409, wenn für die Vorlage schon ein Entwurf besteht. */
    fun newRevision(ctx: RequestContext, sourceRevisionId: UUID, expectedRevision: Long): ChecklistTemplateView
    /** Gibt den Entwurf frei: 403 für den Ersteller, 409 für Nicht-Entwürfe, 422 ohne Prüfelement. */
    fun publish(ctx: RequestContext, revisionId: UUID, expectedRevision: Long): ChecklistTemplateView
    fun putExampleImage(ctx: RequestContext, revisionId: UUID, stepCode: String, expectedRevision: Long, bytes: ByteArray, contentType: String): ChecklistTemplateView
    fun exampleImage(ctx: RequestContext, revisionId: UUID, stepCode: String): ChecklistImage
    /** Prüfelemente einer Revision innerhalb der laufenden Mandantentransaktion (für Prüflauf und Abschluss, WP-M3-13/-14); unbekannte Revision: 404. */
    fun steps(jdbc: JdbcOperations, revisionId: UUID): List<ChecklistStepView>
}

/** Regel eines Prüfelements für die Antwortvalidierung. */
data class ChecklistStepRule(val code: String, val kind: ChecklistInputKind, val mandatory: Boolean, val naRequiresReason: Boolean, val numberMin: BigDecimal?, val numberMax: BigDecimal?)

/** Antwort eines Fahrers auf ein Prüfelement (Rohwerte, noch ungeprüft). */
data class ChecklistAnswer(val stepCode: String, val status: String?, val numberValue: BigDecimal?, val note: String?, val textValue: String? = null)

/**
 * Validierung der Antworten gegen die **feste** Vorlagenrevision (LH-6.21-CHK-003, Originalabnahme): fehlende Pflichtantwort, Wert außerhalb des Eingabetyps, „Nicht anwendbar“
 * ohne Begründung bei markierten Elementen, vierter oder unbekannter Statuswert und Antwort auf ein Element einer anderen Revision ergeben Verstöße mit Verweis auf das Element
 * (`/answers/<step_code>/…`). Reine Funktion; der Abschluss (`submit`, WP-M3-14) ruft sie mit `complete = true` auf, Entwürfe mit `false` (fehlende Antworten erlaubt).
 */
object ChecklistAnswerValidator {
    fun validate(steps: List<ChecklistStepRule>, answers: List<ChecklistAnswer>, complete: Boolean): List<Violation> {
        val out = mutableListOf<Violation>()
        val byCode = steps.associateBy { it.code }
        val seen = HashSet<String>()
        for (a in answers) {
            val base = "/answers/" + a.stepCode.replace("~", "~0").replace("/", "~1")
            val rule = byCode[a.stepCode]
            if (rule == null) { out += Violation(base, ViolationCode.NOT_IN_ENUM); continue }   // Element gehört nicht zur Vorlagenrevision des Laufs
            if (!seen.add(a.stepCode)) { out += Violation(base, ViolationCode.NOT_UNIQUE); continue }
            val status = a.status
            if (status == null) { out += Violation("$base/answer", ViolationCode.REQUIRED); continue }
            if (status !in ChecklistAnswerStatus.ALLOWED) { out += Violation("$base/answer", ViolationCode.NOT_IN_ENUM); continue }
            if (status == ChecklistAnswerStatus.NOT_APPLICABLE && rule.naRequiresReason && a.note.isNullOrBlank()) out += Violation("$base/note", ViolationCode.REQUIRED)
            if (a.note != null && a.note.length > 500) out += Violation("$base/note", ViolationCode.TOO_LONG)
            when (rule.kind) {
                ChecklistInputKind.NUMBER -> if (status == ChecklistAnswerStatus.OK || a.numberValue != null) {
                    val n = a.numberValue
                    if (n == null) out += Violation("$base/number_value", ViolationCode.REQUIRED)
                    else if (rule.numberMin != null && n < rule.numberMin) out += Violation("$base/number_value", ViolationCode.BELOW_MINIMUM)
                    else if (rule.numberMax != null && n > rule.numberMax) out += Violation("$base/number_value", ViolationCode.ABOVE_MAXIMUM)
                }
                ChecklistInputKind.TEXT_SHORT -> {
                    if (a.numberValue != null) out += Violation("$base/number_value", ViolationCode.INVALID_VALUE)
                    if (status == ChecklistAnswerStatus.OK && a.note.isNullOrBlank()) out += Violation("$base/note", ViolationCode.REQUIRED)
                    else if (status == ChecklistAnswerStatus.OK && a.note!!.length > ChecklistLimits.MAX_TEXT_ANSWER) out += Violation("$base/note", ViolationCode.TOO_LONG)
                }
                ChecklistInputKind.OK_DEFECT_NA -> if (a.numberValue != null) out += Violation("$base/number_value", ViolationCode.INVALID_VALUE)
                ChecklistInputKind.ODOMETER_KM, ChecklistInputKind.ENERGY_PCT, ChecklistInputKind.VEHICLE_IDENTITY -> startValue(rule.kind, status, a, base, out)
            }
            if (!rule.kind.isStartValue && a.textValue != null) out += Violation("$base/text_value", ViolationCode.INVALID_VALUE)
        }
        if (complete) steps.filter { it.mandatory && it.code !in seen && answers.none { a -> a.stepCode == it.code } }.forEach { out += Violation("/answers/${it.code}", ViolationCode.REQUIRED) }
        return out
    }

    /** Obergrenze des Kilometerstands (Plausibilitätsgrenze der Eingabe, nicht des Fahrzeugs). */
    const val MAX_ODOMETER_KM = 5_000_000L

    private fun startValue(kind: ChecklistInputKind, status: String, a: ChecklistAnswer, base: String, out: MutableList<Violation>) {
        if (status == ChecklistAnswerStatus.DEFECT) { out += Violation("$base/answer", ViolationCode.NOT_IN_ENUM); return }   // ein Startwert ist kein Mangelbefund
        if (status != ChecklistAnswerStatus.OK) { if (a.numberValue != null) out += Violation("$base/number_value", ViolationCode.INVALID_VALUE); if (a.textValue != null) out += Violation("$base/text_value", ViolationCode.INVALID_VALUE); return }
        when (kind) {
            ChecklistInputKind.ODOMETER_KM -> {
                val n = a.numberValue
                if (n == null) out += Violation("$base/number_value", ViolationCode.REQUIRED)
                else if (n.stripTrailingZeros().scale() > 0) out += Violation("$base/number_value", ViolationCode.TYPE_MISMATCH)
                else if (n.signum() < 0) out += Violation("$base/number_value", ViolationCode.BELOW_MINIMUM)
                else if (n > BigDecimal(MAX_ODOMETER_KM)) out += Violation("$base/number_value", ViolationCode.ABOVE_MAXIMUM)
                if (a.textValue != null) out += Violation("$base/text_value", ViolationCode.INVALID_VALUE)
            }
            ChecklistInputKind.ENERGY_PCT -> {
                val n = a.numberValue
                if (n == null) out += Violation("$base/number_value", ViolationCode.REQUIRED)
                else if (n.signum() < 0) out += Violation("$base/number_value", ViolationCode.BELOW_MINIMUM)
                else if (n > BigDecimal(100)) out += Violation("$base/number_value", ViolationCode.ABOVE_MAXIMUM)
                if (a.textValue != null) out += Violation("$base/text_value", ViolationCode.INVALID_VALUE)
            }
            else -> {
                if (a.textValue.isNullOrBlank()) out += Violation("$base/text_value", ViolationCode.REQUIRED)
                else if (a.textValue.length > 100) out += Violation("$base/text_value", ViolationCode.TOO_LONG)
                if (a.numberValue != null) out += Violation("$base/number_value", ViolationCode.INVALID_VALUE)
            }
        }
    }

    /** Wie [validate], wirft aber 422 mit allen Verstößen. */
    fun require(steps: List<ChecklistStepRule>, answers: List<ChecklistAnswer>, complete: Boolean) {
        val v = validate(steps, answers, complete)
        if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray())
    }
}
