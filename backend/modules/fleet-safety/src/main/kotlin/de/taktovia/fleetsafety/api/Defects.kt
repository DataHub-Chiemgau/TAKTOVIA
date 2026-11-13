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
    /** Bestätigung „trotz offener Fälle desselben Fahrzeugs neu anlegen“ (WP-M3-21, CHK-012): Fahrer ohne diese Bestätigung erhalten bei offenen Fällen 409 [KnownDefectCatalog.OPEN_DEFECTS_PENDING]. */
    val confirmNewDespiteKnown: Boolean = false,
    /** Herkunft Störung (WP-M3-20, DMG-003): der Incident, aus dem die Akte erzeugt wird; schließt die Herkunft Prüfschritt aus. */
    val sourceIncidentId: UUID? = null,
) {
    companion object {
        private val FIELDS = setOf("vehicle_id", "occurred_at", "location_kind", "location_value", "category_code", "description", "kind", "vehicle_area", "observed_effect",
            "source_inspection_id", "source_step_code", "source_incident_id", "evidence_ids", "device_event_id", "severity_hint", "capture_mode", "confirm_new_despite_known")

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
            val incident = v.uuid(raw, "source_incident_id", false)
            // Herkunft Prüfschritt: Prüflauf und Prüfschritt gehören zusammen (CHK-011); Prüfschritt und Störung zugleich gibt es nicht (DMG-003)
            if ((inspection == null) != (step == null) && !v.has("/source_inspection_id") && !v.has("/source_step_code")) v.add(if (inspection == null) "/source_inspection_id" else "/source_step_code", ViolationCode.REQUIRED)
            if (incident != null && inspection != null) v.add("/source_incident_id", ViolationCode.INVALID_VALUE)
            val evidence = v.uuidList(raw, "evidence_ids", DefectCatalog.MAX_EVIDENCE)
            if (guided && evidence.isEmpty() && !v.has("/evidence_ids")) v.add("/evidence_ids", ViolationCode.TOO_FEW_ITEMS)
            val deviceEvent = v.uuid(raw, "device_event_id", false)
            val hint = v.code(raw, "severity_hint", DefectCatalog.SEVERITIES, false)
            val confirm = when (val c = raw["confirm_new_despite_known"]) { null -> false; is Boolean -> c; else -> { v.add("/confirm_new_despite_known", ViolationCode.TYPE_MISMATCH); false } }
            v.throwIfAny()
            return ReportDefect(vehicle!!, occurred!!, locKind, locValue, category!!, description!!, kind, area, effect, inspection, step, evidence, deviceEvent, hint, mode, confirm, incident)
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
    /** Regelversion der Klassifikation (WP-M3-45), Herkunft der Klasse (`RULE`, `REPORTER_HINT`, `TRIAGE`) und Zeitpunkt der letzten Einstufung. */
    val severityRuleVersion: String? = null, val severitySource: String? = null, val classifiedAt: Instant? = null,
    /** Herkunft Störung (WP-M3-20): der Incident, aus dem die Akte erzeugt wurde; vom Incident aus ist die Akte über [IncidentView.defectIds] erreichbar. */
    val sourceIncidentId: UUID? = null,
) {
    /** Vorschlag der Regelmatrix für den aktuellen Inhalt (Triageansicht: vorgeschlagene Klasse neben der geltenden). */
    val suggestedSeverity: String get() = DefectClassification.classify(categoryCode, kind, vehicleArea, observedEffect).severity
}

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

    /**
     * Schadenliste der Leitstelle (WP-M3-49, DMG-009) mit den sechs Filtern, Sortierung nach jeder angezeigten Spalte und stabiler Paginierung: Sortierung `(Sortierwert, id)`, der Cursor trägt den Sortierwert und den
     * Stand der Momentaufnahme (`as_of`). Status, Klasse und Zuständiger werden für alle Seiten derselben Paginierung **zum Stand der ersten Seite** gelesen (aus den unveränderlichen Historien), eine Akte, die während
     * der Paginierung den Status wechselt, erscheint daher weder doppelt noch geht sie verloren; später angelegte Akten gehören nicht zur Momentaufnahme. Nur Akten, die der Aufrufer nach Rolle und Dienst sehen darf;
     * Fahrer sehen nur eigene Meldungen. Die Listenprojektion enthält weder Beschreibungstext noch Fotos noch vertrauliche Inhalte.
     */
    fun listPage(ctx: RequestContext, query: DefectListQuery): DefectListPage

    /**
     * Statusübergang (WP-M3-47, DMG-007) nach der Tabelle in [DefectStateMachine]; `expectedRevision` = `If-Match` (fehlt: 428 im Anfragefilter, veraltet: 412). Fremder Mandant oder fremde Akte: 404;
     * ohne Rolle für den Übergang: 403; nicht in der Tabelle (auch aus einem Endzustand ohne dokumentierte Wiedereröffnung) oder Vorbedingung verletzt: 409 `INVALID_STATE_TRANSITION`;
     * `DISMISSED` und Wiedereröffnung ohne Begründung: 422 mit Feldverweis. Bei zwei gleichzeitigen Übergängen derselben Revision gelingt genau einer. `CLOSED` und `DISMISSED` heben nie eine
     * Sperre des Fahrzeugs auf (nur die Wiederfreigabe, WP-M3-51). Statushistorie, Audit und Ereignis `defect.state_changed` entstehen in derselben Transaktion.
     */
    fun transition(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: DefectTransition): DefectView

    /** Statushistorie der Akte (Disposition, Werkstatt, Betriebsleitung); Fahrer: 403. */
    fun statusHistory(ctx: RequestContext, id: UUID): List<StatusHistoryEntry>

    /**
     * Triage (WP-M3-45, DMG-005): bestätigt oder korrigiert die Klasse (Korrektur nur mit Grund, 422), speichert den Zuständigen und führt die Akte in den Folgestatus (`decision`). Rollen: Disposition und
     * Werkstatt; **Herabstufung** von `SAFETY_CRITICAL` und `DISMISSED` nur die Werkstatt (403). `expectedRevision` = `If-Match` (428 im Filter, 412 bei veralteter Revision, auch für den zweiten von zwei
     * gleichzeitigen Schreibern). Fremder Mandant oder fremde Akte: 404; nicht mehr triagierbarer Status (REPAIR, Endzustand): 409. Eine Hochstufung auf eine sperrende Klasse sperrt das Fahrzeug,
     * widerruft die Einsatzfreigaben, löst den Alarm nach der wirksamen `alert_policy_revision` aus und überführt bestehende Zuweisungen in den Störungsablauf: alles **im selben Commit**.
     * Eine Herabstufung hebt keine Sperre auf (nur die Wiederfreigabe, WP-M3-51).
     */
    fun triage(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: TriageDefect): TriageResult

    /** Klassifikationshistorie (Erstklassifikation, Bestätigungen, Korrekturen mit Akteur, Zeit, Grund); Disposition, Werkstatt, Betriebsleitung. */
    fun classificationHistory(ctx: RequestContext, id: UUID): List<ClassificationEntry>

    /** Aktenhistorie aus Statuswechseln, Klassifikation, Zuweisung und Sperre in zeitlicher Folge, mit Akteur und Zeit (AT-34, P1-Anteil); Disposition, Werkstatt, Betriebsleitung. */
    fun timeline(ctx: RequestContext, id: UUID): List<TimelineEvent>

    /**
     * Nachtrag zur Meldung (WP-M3-48, DMG-008): ändert nie eine Zeile der Historie, sondern legt eine neue Version mit Autor, Zeit und Änderungsgrund (Pflicht, sonst 422) an und trägt die geänderten
     * Felder in die aktuelle Fassung ein. Ein Nachtrag kann Anhänge hinzufügen und ersetzen (der ersetzte bleibt als frühere Version erhalten). Gleichzeitige Nachträge werden unter Zeilensperre
     * nacheinander versioniert (fortlaufend, ohne Verlust). Nur Meldeberechtigte (Fahrer nur eigene Akten); fremde Akte: 404. Audit `fleet.defect.amended`, Ereignis `defect.amended`.
     */
    fun amend(ctx: RequestContext, id: UUID, cmd: DefectAmendment): DefectView

    /** Alle Fassungen der Meldung samt Anhängen, älteste zuerst; nur Prüfer (Werkstatt, Betriebsleitung), sonst 403; der Lesezugriff wird protokolliert (`fleet.defect.history_read`). */
    fun history(ctx: RequestContext, id: UUID): List<DefectRevision>

    /** Stand der Meldung zum Zeitpunkt [at] (Fassung, Anhänge, Status); vor der Meldung: 404. Nur Prüfer; protokolliert wie [history]. */
    fun stateAt(ctx: RequestContext, id: UUID, at: Instant): DefectStateAt
}

/** Ersetzt den freigegebenen Anhang [evidenceId] durch das (bereits freigegebene) Foto des Uploadtickets [ticketId]. */
data class EvidenceReplacement(val evidenceId: UUID, val ticketId: UUID)

/**
 * Nachtrag zur Meldung. Nur gesetzte Felder ändern sich; [changeReason] ist Pflicht. Der Ort wird nur als Paar geändert. Unbekannte Felder (Mandant, Meldender, Status, Fallnummer): 422.
 */
data class DefectAmendment(
    val changeReason: String, val occurredAt: Instant?, val locationKind: String?, val locationValue: String?, val categoryCode: String?, val kind: String?, val vehicleArea: String?,
    val observedEffect: String?, val description: String?, val addEvidenceIds: List<UUID>, val replaceEvidence: List<EvidenceReplacement>,
) {
    companion object {
        private val FIELDS = setOf("change_reason", "occurred_at", "location_kind", "location_value", "category_code", "kind", "vehicle_area", "observed_effect", "description", "add_evidence_ids", "replace_evidence")

        fun parse(raw: Map<String, Any?>): DefectAmendment {
            val v = Violations()
            v.unknown(raw, FIELDS, "")
            val reason = v.text(raw, "change_reason", DefectCatalog.MAX_REASON, true)
            val occurred = v.instant(raw, "occurred_at", false)
            val locKind = v.code(raw, "location_kind", DefectCatalog.LOCATION_KINDS, false)
            val locValue = v.text(raw, "location_value", DefectCatalog.MAX_LOCATION, false)
            if ((locKind == null) != (locValue == null)) v.add(if (locKind == null) "/location_kind" else "/location_value", ViolationCode.REQUIRED)
            val category = v.code(raw, "category_code", DefectCatalog.CATEGORIES, false)
            val kind = v.code(raw, "kind", DefectCatalog.KINDS, false)
            val area = v.code(raw, "vehicle_area", DefectCatalog.AREAS, false)
            val effect = v.code(raw, "observed_effect", DefectCatalog.EFFECTS, false)
            val description = v.text(raw, "description", DefectCatalog.MAX_DESCRIPTION, false)
            val add = v.uuidList(raw, "add_evidence_ids", DefectCatalog.MAX_EVIDENCE)
            val replace = mutableListOf<EvidenceReplacement>()
            when (val r = raw["replace_evidence"]) {
                null -> {}
                is List<*> -> {
                    if (r.size > DefectCatalog.MAX_EVIDENCE) v.add("/replace_evidence", ViolationCode.TOO_MANY_ITEMS)
                    r.forEachIndexed { i, e ->
                        val m = e as? Map<*, *>
                        if (m == null) v.add("/replace_evidence/$i", ViolationCode.TYPE_MISMATCH) else {
                            v.unknown(m, setOf("evidence_id", "ticket_id"), "/replace_evidence/$i")
                            val old = v.uuid(m, "evidence_id", true)
                            val new = v.uuid(m, "ticket_id", true)
                            if (old != null && new != null) replace += EvidenceReplacement(old, new)
                        }
                    }
                }
                else -> v.add("/replace_evidence", ViolationCode.TYPE_MISMATCH)
            }
            v.throwIfAny()
            return DefectAmendment(reason!!, occurred, locKind, locValue, category, kind, area, effect, description, add, replace)
        }
    }
}

/** Eine Fassung der Meldung (unveränderlich): [content] ist der volle Inhalt dieser Version, [evidence] die mit ihr hinzugekommenen Anhänge (auch später ersetzte). */
data class DefectRevision(val version: Int, val author: String, val changeReason: String, val createdAt: Instant, val content: Map<String, Any?>, val evidence: List<EvidenceView>)

/** Stand der Meldung zum Zeitpunkt [asOf]: gültige Fassung, damals sichtbare Anhänge und Status. */
data class DefectStateAt(val asOf: Instant, val version: Int, val versionCreatedAt: Instant, val status: String?, val content: Map<String, Any?>, val evidence: List<EvidenceView>)

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
