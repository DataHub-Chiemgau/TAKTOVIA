import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Öffentliche Ports des Moduls `analytics`: Faktenbasis und Datenstand (WP-M4-45), Exportaufträge `/v1/reports/jobs` (WP-M5-19). */
interface AnalyticsApi {
    /** Ereignisverbraucher der Faktenbasis, Wiederaufbau und Datenstand. */
    val facts: FactBase

    /** Exportaufträge mit Empfängerrechten und Datenstand (WP-M5-19). */
    val reportJobs: ReportJobs

    companion object {
        /**
         * [downloadSecret] signiert die kurzlebigen Abruflinks (HMAC-SHA256, mindestens 32 Byte, nie im Repository); [checklist] ist die Freigabecheckliste des PBefG-Exports
         * (im Pflichtenheft nicht enthalten, OP-13: Arbeitsannahme, konfigurierbar).
         */
        fun create(
            ctxTx: ContextTransactions, tx: TenantTransactions, audit: AuditPort, downloadSecret: ByteArray, clock: Clock = Clock.systemUTC(),
            downloadTtl: Duration = Duration.ofMinutes(5), checklist: List<String> = ExportCategories.DEFAULT_PBEFG_CHECKLIST,
        ): AnalyticsApi = AnalyticsServicesFactory.create(ctxTx, tx, audit, downloadSecret, clock, downloadTtl, checklist)

        /** Nur die Faktenbasis (Projektionsworker und Tests ohne Exportaufträge). */
        fun createFacts(tx: TenantTransactions, clock: Clock = Clock.systemUTC()): FactBase = AnalyticsServicesFactory.createFacts(tx, clock)
    }
}

/** Faktenquellen (Spalte `source` von `dataset_watermark`): je Quelle ein Datenstand mit Vollständigkeitsangabe. */
enum class FactSource { ATTEMPT, OFFER, BOOKING, DRIVER_EVENT, VEHICLE_STATE }

/** Ausgang der Verarbeitung eines Ereignisses; nur [PROCESSED] hat die Faktenbasis verändert. */
enum class ConsumeOutcome {
    /** Eingang gespeichert, Fakten neu abgeleitet (auch ein verspätetes oder ungeordnetes Ereignis: es wird nie verworfen, die Fakten hängen nur von der Menge der Ereignisse ab). */
    PROCESSED,
    /** Dasselbe Ereignis war schon verarbeitet (gleicher Inhalt): keine zweite Wirkung. */
    DUPLICATE,
    /** Dieselbe `event_id` mit anderem Inhalt: nicht verarbeitet. */
    REJECTED_PAYLOAD_MISMATCH,
    /** Der Empfänger der Ereignishülle ist nicht der Mandant des Verarbeitungskontexts (oder Eigentümer und Empfänger weichen ab): verworfen, nichts gespeichert. */
    REJECTED_TENANT,
    /** Hülle oder Nutzlast nicht lesbar oder Pflichtfelder fehlen, oder eine Hauptversion, die dieser Verbraucher nicht kennt. */
    REJECTED_SCHEMA,
    /** Ereignistyp ohne Faktenbezug: ignoriert. */
    UNSUPPORTED_EVENT,
}

enum class Completeness { COMPLETE, INCOMPLETE }

/**
 * Datenstand einer Faktenquelle (Tabelle `dataset_watermark`): [processedUntil] = jüngste Ereigniszeit der verarbeiteten Ereignisse, [completeness] `INCOMPLETE`, solange in der Aggregatrevision
 * eines Aggregats mit lückenloser Revisionsfolge Revisionen fehlen ([gapCount] = Zahl dieser Aggregate), [updatedAt] = Stand der letzten Verarbeitung.
 */
data class Watermark(val source: FactSource, val processedUntil: Instant?, val completeness: Completeness, val gapCount: Int, val eventCount: Long, val updatedAt: Instant)

/**
 * Faktenbasis (WP-M4-45). Der Verbraucher arbeitet **mit Tenantkontext je Transaktion** ([TenantId] des Workers): ein Ereignis, dessen Empfänger ein anderer Mandant ist, wird verworfen.
 * Die Ereignisse werden einmal je `(Mandant, event_id)` aufgenommen (Dedupe, auch bei parallelen Workern); die Fakten sind eine reine Funktion der Menge der aufgenommenen Ereignisse
 * (Aggregatrevision, Ereigniszeit, `event_id` als Ordnung) und damit unabhängig von Reihenfolge, Wiederholung und Verspätung; [rebuild] leitet alle Fakten eines Mandanten aus den
 * gespeicherten Ereignissen neu ab und liefert dieselben Zahlen. Der Verbraucher ruft nie einen Eigentümer zurück (kein Snapshotabruf): eine Revisionslücke macht die Quelle `INCOMPLETE`
 * statt sie zu raten; kommt die fehlende Revision später, wird die Quelle wieder `COMPLETE`. Die Nutzlast wird auf Fachfelder reduziert gespeichert (keine Fahrgast-Klartextdaten).
 */
interface FactBase {
    /** Verarbeitet eine Ereignishülle (JSON, Schema `platform.eventenvelope`) im Kontext von [workerTenant]. */
    fun consume(workerTenant: TenantId, envelopeJson: String): ConsumeOutcome

    /** Leitet alle Fakten und Datenstände von [tenant] aus den gespeicherten Ereignissen neu ab (eine Transaktion). */
    fun rebuild(tenant: TenantId)

    /** Datenstand je Quelle (nur Quellen mit mindestens einem Ereignis). */
    fun watermarks(tenant: TenantId): List<Watermark>
}
