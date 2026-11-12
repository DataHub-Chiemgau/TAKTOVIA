package de.taktovia.fleetsafety.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ViolationCode
import java.time.Instant
import java.util.UUID

/**
 * Prüflauf-Start (WP-M3-13, LH-6.21-CHK-002, Detailvertrag `docs/umsetzungsplan/vertraege/LH-6.21-CHK-002.md`).
 *
 * **Planungsannahme PA-08 (M3 OP-14, nicht freigegeben):** Fahrzeugklasse = `vehicle.vehicle_type` (Katalog PKW, KLEINBUS, BUS, RAMPE), Bedienart = `vehicle.operation_kind`
 * (Katalog STANDARD, ASSISTENZ); eine Vorlagenrevision gilt für das Paar (`vehicle_class`, `service_mode`). Der Katalog ist versionierte Konfiguration und wird hier nur als Format
 * (Großbuchstaben, Ziffern, Unterstrich) geprüft, nicht als feste Aufzählung.
 */
object InspectionCatalog {
    val KINDS: Set<String> = linkedSetOf("PRE_SHIFT", "FOLLOW_UP", "VEHICLE_CHANGE")
    /** Toleranz der Gerätezeit gegenüber der Serverzeit („Start nicht in der Zukunft“). */
    const val FUTURE_TOLERANCE_SECONDS = 60L
    /** Stabile Kennwörter am Anfang von `detail` des 409, wenn keine eindeutig passende Vorlage besteht (Vertrag `createInspection`). */
    const val TEMPLATE_UNAVAILABLE = "TEMPLATE_UNAVAILABLE"
    const val TEMPLATE_AMBIGUOUS = "TEMPLATE_AMBIGUOUS"
}

/**
 * Start eines Prüflaufs. Die Vorlage bestimmt allein der Server; ein mitgesendetes `template_revision_id` wird gelesen, aber **nie verwendet** (Originalabnahme CHK-002:
 * ein Client kann keine andere Vorlage wählen). Mandant und Fahrer kommen aus dem Kontext, nie aus dem Body.
 */
data class StartInspection(val shiftId: UUID, val vehicleId: UUID, val kind: String, val clientStartedAt: Instant) {
    companion object {
        private val FIELDS = setOf("shift_id", "vehicle_id", "template_revision_id", "kind", "odometer_km", "battery_soc_pct", "client_started_at")

        fun parse(raw: Map<String, Any?>): StartInspection {
            val v = Violations()
            v.unknown(raw, FIELDS, "")
            val shift = v.uuid(raw, "shift_id", true)
            val vehicle = v.uuid(raw, "vehicle_id", true)
            v.uuid(raw, "template_revision_id", false)   // wirkungslos (siehe oben), nur auf Format geprüft
            val kind = v.code(raw, "kind", InspectionCatalog.KINDS, true)
            val started = v.instant(raw, "client_started_at", true)
            // Schichtstartwerte (Kilometerstand, Ladezustand) prüft und speichert WP-M3-17; hier nur auf Typ und Bereich geprüft, nicht gespeichert.
            when (val x = raw["odometer_km"]) {
                null -> {}
                is Int, is Long -> (x as Number).toLong().let { if (it < 0) v.add("/odometer_km", ViolationCode.BELOW_MINIMUM) else if (it > 5_000_000) v.add("/odometer_km", ViolationCode.ABOVE_MAXIMUM) }
                else -> v.add("/odometer_km", ViolationCode.TYPE_MISMATCH)
            }
            when (val x = raw["battery_soc_pct"]) {
                null -> {}
                is Number -> x.toDouble().let { if (it < 0.0) v.add("/battery_soc_pct", ViolationCode.BELOW_MINIMUM) else if (it > 100.0) v.add("/battery_soc_pct", ViolationCode.ABOVE_MAXIMUM) }
                else -> v.add("/battery_soc_pct", ViolationCode.TYPE_MISMATCH)
            }
            v.throwIfAny()
            return StartInspection(shift!!, vehicle!!, kind!!, started!!)
        }
    }
}

/** Prüfschritt der festen Vorlagenrevision des Laufs (Sicht der Fahrer-App: „Prüfung n / [InspectionTemplateView.steps].size“). */
data class InspectionTemplateView(val revisionId: UUID, val templateId: UUID, val version: Int, val vehicleClass: String, val serviceMode: String, val steps: List<ChecklistStepView>)

data class InspectionAnswerView(val stepCode: String, val answer: String, val numberValue: java.math.BigDecimal?, val note: String?, val answeredAt: Instant)

data class InspectionReleaseView(val releaseId: UUID, val validUntil: Instant, val safetyRevision: Long)

data class InspectionView(
    val id: UUID, val shiftId: UUID, val vehicleId: UUID, val driverRef: UUID, val templateRevisionId: UUID, val kind: String, val status: String, val outcome: String?,
    val startedAt: Instant, val completedAt: Instant?, val receivedAt: Instant?, val revision: Long, val template: InspectionTemplateView,
    val answers: List<InspectionAnswerView> = emptyList(), val release: InspectionReleaseView? = null,
    /** Prüfer-ID aus der Sitzung beim Abschluss (`user:<kennung>`), Serverwert (WP-M3-14). */
    val completedBy: String? = null,
    /** Noch fehlende Voraussetzungen der Einsatzfreigabe (leer, wenn die Freigabe erteilt ist). */
    val missing: List<ReleaseBlocker> = emptyList(),
)

/** Antwort im Entwurf oder im Abschluss, wie der Client sie liefert (Rohwerte; die Prüfung gegen die Vorlagenrevision macht der Server). */
data class AnswerInput(val stepCode: String, val answer: String?, val numberValue: java.math.BigDecimal?, val note: String?, val answeredAt: Instant)

internal object AnswerParsing {
    private val FIELDS = setOf("step_code", "answer", "number_value", "note", "evidence_ids", "answered_at")
    private val CODE = Regex("^[A-Z][A-Z0-9_]{1,59}$")

    /** Liest die Antwortliste; jede Verletzung mit Pointer `/answers/<i>/…` (422). Unbekannte Felder (z. B. Prüfer, Mandant, Ergebnis) werden abgewiesen, nie übernommen. */
    fun parse(raw: Any?, max: Int, v: Violations, min: Int = 0): List<AnswerInput> {
        if (raw == null) { v.add("/answers", ViolationCode.REQUIRED); return emptyList() }
        if (raw !is List<*>) { v.add("/answers", ViolationCode.TYPE_MISMATCH); return emptyList() }
        if (raw.size < min) v.add("/answers", ViolationCode.TOO_FEW_ITEMS)
        if (raw.size > max) { v.add("/answers", ViolationCode.TOO_MANY_ITEMS); return emptyList() }
        val out = mutableListOf<AnswerInput>()
        raw.forEachIndexed { i, a ->
            val p = "/answers/$i"
            if (a !is Map<*, *>) { v.add(p, ViolationCode.TYPE_MISMATCH); return@forEachIndexed }
            val t = Violations()
            t.unknown(a, FIELDS, "")
            val code = (a["step_code"] as? String)?.also { if (!CODE.matches(it)) t.add("/step_code", ViolationCode.PATTERN_MISMATCH) } ?: run { t.add("/step_code", if (a["step_code"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH); null }
            val answer = (a["answer"] as? String) ?: run { t.add("/answer", if (a["answer"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH); null }
            val number = when (val n = a["number_value"]) {
                null -> null
                is Int, is Long, is Short, is Byte -> java.math.BigDecimal((n as Number).toLong())
                is Double -> if (n.isFinite()) java.math.BigDecimal.valueOf(n) else { t.add("/number_value", ViolationCode.TYPE_MISMATCH); null }
                is java.math.BigDecimal -> n
                is java.math.BigInteger -> java.math.BigDecimal(n)
                else -> { t.add("/number_value", ViolationCode.TYPE_MISMATCH); null }
            }
            val note = when (val n = a["note"]) { null -> null; is String -> n; else -> { t.add("/note", ViolationCode.TYPE_MISMATCH); null } }
            // Fotoevidenz an Prüfantworten gehört zu WP-M3-20 (Abweichung mit Foto); bis dahin werden Verweise nicht stillschweigend ignoriert
            if ((a["evidence_ids"] as? List<*>)?.isNotEmpty() == true || (a["evidence_ids"] != null && a["evidence_ids"] !is List<*>)) t.add("/evidence_ids", ViolationCode.INVALID_VALUE)
            val at = t.instant(a, "answered_at", true)
            t.list.forEach { v.add(p + it.pointer, it.code) }
            if (t.list.isEmpty()) out += AnswerInput(code!!, answer!!, number, note, at!!)
        }
        return out
    }
}

/** Entwurf der Antworten (WP-M3-15). */
data class SaveInspectionDraft(val answers: List<AnswerInput>) {
    companion object {
        fun parse(raw: Map<String, Any?>): SaveInspectionDraft {
            val v = Violations()
            v.unknown(raw, setOf("answers"), "")
            val answers = AnswerParsing.parse(raw["answers"], 200, v)
            v.throwIfAny()
            return SaveInspectionDraft(answers)
        }
    }
}

/**
 * Abschluss eines Prüflaufs (WP-M3-14). Nur Antworten, Gerätezeit und optional die Geräteereignis-ID sind Eingabe. Prüfer, Mandant, Fahrzeug, Schicht, Ergebnis und Freigabe
 * entscheidet allein der Server; ein mitgesendetes Feld dieser Art ist ein unbekanntes Feld (422), nie ein übernommener Wert.
 */
data class SubmitInspection(val answers: List<AnswerInput>, val clientSubmittedAt: Instant, val deviceEventId: UUID?) {
    companion object {
        fun parse(raw: Map<String, Any?>): SubmitInspection {
            val v = Violations()
            v.unknown(raw, setOf("answers", "client_submitted_at", "device_event_id"), "")
            val answers = AnswerParsing.parse(raw["answers"], 200, v, min = 1)
            val at = v.instant(raw, "client_submitted_at", true)
            val device = v.uuid(raw, "device_event_id", false)
            v.throwIfAny()
            return SubmitInspection(answers, at!!, device)
        }
    }
}

enum class InspectionOutcome { PASSED, PASSED_RESTRICTED, BLOCKED, CLARIFICATION_REQUIRED }

/**
 * Ergebnisregel (Detailvertrag LH-6.21-CHK-013, Annahmen OP-16/OP-17): eine negative Antwort (`DEFECT`) auf ein sicherheitskritisches Prüfelement ergibt `BLOCKED` (gesperrt); sonst
 * `PASSED`. Ein unkritischer Mangel sperrt nicht. `NOT_CHECKABLE` („technisch ungeklärt“, `CLARIFICATION_REQUIRED`) und `PASSED_RESTRICTED` (nur mit Ausstattungsprüfpunkten, WP-M3-12)
 * sind bis zur Entscheidung der offenen Punkte nicht erreichbar: die Antwortvalidierung weist den vierten Statuswert ab.
 */
object InspectionOutcomeRule {
    fun evaluate(steps: List<ChecklistStepView>, answers: List<AnswerInput>): InspectionOutcome {
        val critical = steps.filter { it.safetyCritical }.map { it.code }.toSet()
        return if (answers.any { it.answer == ChecklistAnswerStatus.DEFECT && it.stepCode in critical }) InspectionOutcome.BLOCKED else InspectionOutcome.PASSED
    }
}

/** Ergebnis des Starts: [created] ist `false`, wenn für (Schicht, Fahrzeug, Art) bereits ein offener Lauf bestand (dieser wird unverändert, mit seiner Vorlagenrevision, geliefert). */
data class StartedInspection(val inspection: InspectionView, val created: Boolean)

/**
 * Prüflauf (WP-M3-13). Start nur durch den Fahrer der eigenen, **angetretenen** Schicht (Dienstanmeldung, WP-M3-25); fremde Schicht oder fremder Mandant: 404. Die Vorlagenrevision
 * wählt der Server aus Fahrzeugklasse und Bedienart des Fahrzeugs: genau eine freigegebene Revision, sonst entsteht kein Lauf, es gibt 409 (`TEMPLATE_UNAVAILABLE` bzw.
 * `TEMPLATE_AMBIGUOUS`) und die Leitstelle erhält in derselben Transaktion ein Hinweisereignis (`inspection.template_unavailable`) samt Audit. Ein offener Lauf je
 * (Schicht, Fahrzeug, Art) wird per Teil-Unique-Index erzwungen: ein zweiter Start (anderes Gerät, anderer Schlüssel) liefert den bestehenden Lauf. Der Lauf behält seine Revision,
 * auch wenn danach eine neue Vorlagenrevision freigegeben wird.
 */
interface Inspections {
    fun start(ctx: RequestContext, cmd: StartInspection): StartedInspection

    /**
     * Schließt den Lauf ab (WP-M3-14): validiert alle Antworten (Entwurf plus Abschlussantworten, die Abschlussantwort gewinnt) gegen die **feste** Vorlagenrevision, berechnet das
     * Ergebnis, schreibt Antworten, Ergebnis, Einsatzfreigabe (über [Readiness]) und Ereignis in einer Transaktion. [expectedRevision] ist die Revision des Laufs (`If-Match`,
     * 412 wenn veraltet). Prüfer-ID und Serverzeit setzt der Server. Fehlende Pflichtantwort, falscher Wert, Mangel ohne Beschreibung: 422 mit Verweis auf das Element; Lauf nicht
     * offen: 409; fremder Lauf, fremde Schicht, fremder Mandant: 404; nicht quittierte Altschäden: 409 `KNOWN_DEFECTS_UNACKNOWLEDGED`.
     */
    fun submit(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: SubmitInspection): InspectionView

    /** Lesen mit fester Vorlage und gespeicherten Antworten. Fahrer sehen nur Läufe eigener Schichten (sonst 404), Disposition und Werkstatt alle Läufe des Mandanten. */
    fun get(ctx: RequestContext, id: UUID): InspectionView
}
