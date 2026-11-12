package de.taktovia.analytics.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.tenant.TenantId
import java.time.Instant
import java.util.UUID

/** Art des Exports. [PBEFG_DATA] bündelt die Kategorien der Freigabecheckliste (Arbeitsannahme, OP-13); die übrigen Arten exportieren je einen Nachweis. */
enum class ExportKind(val code: String) {
    PBEFG_DATA("PBEFG_DATA"), INSPECTION_PROOF("INSPECTION_PROOF"), DEFECT_CASE("DEFECT_CASE"), TIME_PROOF("TIME_PROOF"), ROSTER_ARCHIVE("ROSTER_ARCHIVE");
    companion object { fun parse(s: String?): ExportKind? = entries.firstOrNull { it.code == s } }
}

/** Kategorien eines Exports und ihr Status im Ergebnis: [PRESENT] vollständig geliefert, [MISSING] ohne Quelle im P1-Umfang (ausgewiesen, nie weggelassen), [NOT_AUTHORIZED] ohne Recht des Auftraggebers. */
object ExportCategories {
    const val INSPECTION_PROOF = "INSPECTION_PROOF"
    const val DEFECT_CASE = "DEFECT_CASE"
    const val TIME_PROOF = "TIME_PROOF"
    const val ROSTER_ARCHIVE = "ROSTER_ARCHIVE"
    const val VEHICLE_REGISTER = "VEHICLE_REGISTER"
    /** Beispielkategorie ohne Quelle im P1-Umfang (Genehmigungsunterlagen): erscheint im Export als fehlend. */
    const val OPERATING_PERMIT = "OPERATING_PERMIT"
    const val PRESENT = "PRESENT"; const val MISSING = "MISSING"; const val NOT_AUTHORIZED = "NOT_AUTHORIZED"
    /** Arbeitsannahme der Freigabecheckliste (PBefG-Export, OP-13: die gesetzlich relevanten Kategorien sind im Pflichtenheft nicht enthalten). */
    val DEFAULT_PBEFG_CHECKLIST: List<String> = listOf(VEHICLE_REGISTER, INSPECTION_PROOF, DEFECT_CASE, TIME_PROOF, ROSTER_ARCHIVE, OPERATING_PERMIT)
    /** Kategorien, für die der Export eine Quelle hat. */
    val SUPPORTED: Set<String> = setOf(VEHICLE_REGISTER, INSPECTION_PROOF, DEFECT_CASE, TIME_PROOF, ROSTER_ARCHIVE)
    /** Funktionsscope je Kategorie (Empfängerrecht). */
    val SCOPE_OF = mapOf(VEHICLE_REGISTER to "fleet:read", INSPECTION_PROOF to "inspections:read", DEFECT_CASE to "defects:read", TIME_PROOF to "time-events:manage", ROSTER_ARCHIVE to "rosters:audit")
}

/** Umfang des Exports. Welche Felder gelten, hängt von der Art ab; alle sind optional außer [from] und [to] beim Zeitnachweis. Der Mandant steht nie im Umfang. */
data class ExportScope(val vehicleId: UUID? = null, val from: Instant? = null, val to: Instant? = null, val personRef: String? = null, val depotId: UUID? = null) {
    fun toMap(): Map<String, Any?> = buildMap {
        vehicleId?.let { put("vehicle_id", it.toString()) }; from?.let { put("from", it.toString()) }; to?.let { put("to", it.toString()) }
        personRef?.let { put("person_ref", it) }; depotId?.let { put("depot_id", it.toString()) }
    }

    companion object {
        private val FIELDS = setOf("vehicle_id", "from", "to", "person_ref", "depot_id")

        /** Prüft die rohe Eingabe; Verstöße mit Pointer unter `/scope`. */
        fun parse(kind: ExportKind, raw: Any?): Pair<ExportScope?, List<Violation>> {
            val v = mutableListOf<Violation>()
            val m = raw as? Map<*, *> ?: return null to listOf(Violation("/scope", if (raw == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH))
            m.keys.filter { it !in FIELDS }.forEach { v += Violation("/scope/$it", ViolationCode.UNKNOWN_FIELD) }
            fun uuid(k: String): UUID? = (m[k] as? String)?.let { s -> runCatching { UUID.fromString(s) }.getOrNull() ?: run { v += Violation("/scope/$k", ViolationCode.INVALID_FORMAT); null } }
                ?: run { if (m[k] != null) v += Violation("/scope/$k", ViolationCode.TYPE_MISMATCH); null }
            fun instant(k: String): Instant? = (m[k] as? String)?.let { s -> runCatching { java.time.OffsetDateTime.parse(s).toInstant() }.getOrNull() ?: run { v += Violation("/scope/$k", ViolationCode.INVALID_FORMAT); null } }
                ?: run { if (m[k] != null) v += Violation("/scope/$k", ViolationCode.TYPE_MISMATCH); null }
            val vehicle = uuid("vehicle_id"); val depot = uuid("depot_id"); val from = instant("from"); val to = instant("to")
            val person = (m["person_ref"] as? String)?.also { if (it.length !in 1..200) v += Violation("/scope/person_ref", ViolationCode.INVALID_VALUE) }
            if (m["person_ref"] != null && m["person_ref"] !is String) v += Violation("/scope/person_ref", ViolationCode.TYPE_MISMATCH)
            if (from != null && to != null && !to.isAfter(from)) v += Violation("/scope/to", ViolationCode.INTERVAL_INVALID)
            if (kind == ExportKind.TIME_PROOF) { if (m["from"] == null) v += Violation("/scope/from", ViolationCode.REQUIRED); if (m["to"] == null) v += Violation("/scope/to", ViolationCode.REQUIRED) }
            return if (v.isEmpty()) ExportScope(vehicle, from, to, person, depot) to v else null to v
        }
    }
}

data class CreateExport(val kind: ExportKind, val scope: ExportScope)

/** Eine Kategorie im Ergebnis: Status und Zeilenzahl (Nachweis der Vollständigkeit). */
data class CategoryResult(val code: String, val status: String, val rows: Int)

enum class ExportState { QUEUED, RUNNING, SUCCEEDED, FAILED }

/** Exportauftrag. [downloadToken] und [downloadExpiresAt] nur beim erfolgreichen Auftrag und nur für den Auftraggeber (kurzlebig, mandanten- und auftragsgebunden). */
data class ExportJobView(
    val id: UUID, val kind: ExportKind, val scope: ExportScope, val state: ExportState, val asOf: Instant, val createdAt: Instant, val finishedAt: Instant?,
    val resultSha256: String?, val resultBytes: Int?, val categories: List<CategoryResult>?, val failureCode: String?, val revision: Long,
    val downloadToken: String? = null, val downloadExpiresAt: Instant? = null,
)

data class ExportCreated(val job: ExportJobView, val replayed: Boolean)

/** Exportdatei: der Inhalt (JSON-Text) mit seinem SHA-256. */
data class ExportFile(val jobId: UUID, val kind: ExportKind, val sha256: String, val content: String)

/**
 * Exportaufträge (WP-M5-19, Kap. 05 §5.7 `/v1/reports/jobs`). **Empfängerrechte:** ein Auftrag hält die Funktionsrechte und Rollen des Auftraggebers zum Auftragszeitpunkt; der Export enthält nur,
 * wozu der Auftraggeber berechtigt war (eine Kategorie ohne Recht erscheint als `NOT_AUTHORIZED`, vertrauliche Schadenakteninhalte nur für Werkstatt und Mandantenadministration). **Datenstand:** `as_of` setzt
 * der Server beim Auftrag; das Ergebnis entsteht in **einem** Statement (ein Snapshot) und enthält nur Zeilen bis `as_of`. **Idempotenz:** derselbe Schlüssel desselben Auftraggebers mit gleichem Umfang liefert
 * denselben Auftrag, mit anderem Umfang 409 `IDEMPOTENCY_CONFLICT`. Auftrag und Abruf stehen im `audit_event` (wer, wann, welcher Umfang), nie mit Exportinhalt. Fremde Objekte oder fremde Aufträge
 * sind 404; ein Abruf braucht den kurzlebigen, signierten Link des eigenen Auftrags.
 */
interface ReportJobs {
    fun create(ctx: RequestContext, cmd: CreateExport, idempotencyKey: String): ExportCreated
    fun get(ctx: RequestContext, jobId: UUID): ExportJobView
    /** Liefert die Datei, wenn [token] zu Mandant, Auftrag und Auftraggeber passt und nicht abgelaufen ist; sonst 404 (Sicherheitsereignis, ohne Hinweis auf Existenz). */
    fun download(ctx: RequestContext, jobId: UUID, token: String): ExportFile
    /** Betriebsauftrag: verarbeitet bis zu [limit] wartende Aufträge des Mandanten; liefert die Zahl. */
    fun runPending(tenant: TenantId, limit: Int = 10): Int
}
