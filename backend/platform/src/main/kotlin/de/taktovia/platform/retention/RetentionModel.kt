package de.taktovia.platform.retention

import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/** Aktion einer Aufbewahrungsregel (Spalte `retention_rule.action`, Enum im API-Vertrag `RetentionRule.action`). */
enum class RetentionAction { DELETE, ANONYMIZE, ARCHIVE }

enum class RuleStatus { DRAFT, APPROVED }

/** Zustände des Löschlaufs (vertraege/zustaende/erasure_job.md): läuft, fortsetzbar, abgeschlossen, abgebrochen. */
enum class JobStatus { RUNNING, RESUMABLE, COMPLETED, ABORTED }

data class RetentionRuleView(
    val id: UUID, val dataClass: String, val retentionDays: Int, val action: RetentionAction, val archiveDays: Int?,
    val status: RuleStatus, val revision: Long, val createdAt: Instant,
)

data class ErasureJobView(
    val id: UUID, val ruleId: UUID, val dataClass: String, val action: RetentionAction, val status: JobStatus,
    val processedCount: Int, val skippedCount: Int, val resultCode: String?, val operationId: UUID?, val createdAt: Instant, val finishedAt: Instant?, val revision: Long,
)

/** Löschprotokoll: je bearbeitetes Objekt Art und Kennung (UUID) und Aktion, nie Personendaten. */
data class JournalEntryView(val id: UUID, val jobId: UUID, val dataClass: String, val objectRef: String, val action: RetentionAction, val appliedAt: Instant)

/** Regelwerte, die ein Lauf festhält (Momentaufnahme der freigegebenen Regel beim Anlegen des Jobs). */
data class RuleSnapshot(val ruleId: UUID, val dataClass: String, val action: RetentionAction, val retentionDays: Int, val archiveDays: Int?)

/** Ein Objekt, das der Lauf bearbeiten soll, mit der für dieses Objekt wirksamen Aktion (Archivierte werden nach Ablauf des Archivs anonymisiert). */
data class Candidate(val id: UUID, val action: RetentionAction)

/**
 * Löschziel einer Datenklasse (WP-M1-33). Die Plattform kennt die Fachtabellen nicht: das besitzende Fachmodul implementiert das Ziel
 * (Tabellenbesitz bleibt gewahrt, Kap. 01 §1.3) und der Löschlauf in [ErasureEngine] ruft es auf. Alle Methoden laufen in der Transaktion des
 * Mandanten (Zeilen anderer Mandanten sind für RLS unsichtbar) und müssen **idempotent** sein: nach einem Abbruch wird dasselbe Objekt erneut angeboten.
 */
interface RetentionTarget {
    /** Datenklasse (Löschklasse des Dateninventars, z. B. `LK_PII`), für die dieses Ziel zuständig ist. */
    val dataClass: String
    /** Objektart im Journal (`<art>:<uuid>`), kleingeschrieben. */
    val objectType: String
    /** Aktionen, die das Ziel ausführen kann; eine Regel mit anderer Aktion lässt den Lauf abbrechen (nie stillschweigend eine andere Aktion). */
    val supportedActions: Set<RetentionAction>
    /** Fällige Objekte in aufsteigender Kennung nach [after], höchstens [limit]. */
    fun candidates(jdbc: JdbcOperations, rule: RuleSnapshot, now: Instant, after: UUID?, limit: Int): List<Candidate>
    /** Führt [Candidate.action] am Objekt aus. Ein Archiv liefert das Fristende des Archivzustands. */
    fun apply(jdbc: JdbcOperations, rule: RuleSnapshot, candidate: Candidate, now: Instant): ApplyResult
    /** Namen von Objekten im Objektspeicher, die mit dem Datensatz zu löschen sind (Präfixe, relativ zum Mandanten). */
    fun objectPrefixes(id: UUID): List<String> = emptyList()
}

/** Ergebnis von [RetentionTarget.apply]: `archiveUntil` nur bei [RetentionAction.ARCHIVE]; `effective` ist die tatsächlich ausgeführte Aktion. */
data class ApplyResult(val effective: RetentionAction, val archiveUntil: Instant? = null)

/**
 * Sitzungseinstellung für den Archivzugang. **Nur dieser Baustein setzt sie** (ein Test sucht den Quelltext danach): in einer Transaktion
 * mit `app.archive_access = on` sieht die Datenbank archivierte Datensätze, sonst bleiben sie für jede operative Abfrage unsichtbar.
 */
object ArchiveAccess {
    fun enable(jdbc: JdbcOperations) { jdbc.queryForObject("SELECT set_config('app.archive_access', 'on', true)", String::class.java) }
}
