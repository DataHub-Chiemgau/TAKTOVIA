package de.taktovia.adapters.erpexport.api

import de.taktovia.adapters.erpexport.internal.CanonicalJson

/** Eine Journalzeile des Exportbatchs: Betrag in kleinster Einheit (immer positiv), Seite `D` (Soll) oder `C` (Haben). */
data class ErpLine(val account: String, val side: String, val amountMinor: Long)

/** Ein gebuchtes Journal mit stabiler Kennung; `reversesEventId` gesetzt = Gegenbuchung eines Gegenereignisses. */
data class ErpJournal(
    val journalId: String, val eventId: String, val documentId: String?, val reversesEventId: String?, val accountingDate: String, val currency: String, val lines: List<ErpLine>,
)

/** Exportbatch eines Rechtsträgers und Zeitraums (`periodTo` exklusiv). */
data class ErpBatch(val batchRef: String, val exportId: String, val legalEntityId: String, val periodFrom: String, val periodTo: String, val journals: List<ErpJournal>)

/** Ergebnis der Darstellung: Medientyp und Bytes; gleiche Eingabe ergibt immer dieselben Bytes (Grundlage des Inhaltshashs). */
class ErpRendering(val mediaType: String, val fileExtension: String, val bytes: ByteArray)

/**
 * Port des Adapters `erp-export` (WP-M4-37): stellt einen Exportbatch im Zielformat dar. Der Aufruf ist rein und deterministisch (kein Zeitstempel, keine Zufallswerte, stabile Reihenfolge),
 * damit die Wiederholung eines Exports denselben Inhaltshash liefert. Ziel-ERP, Format und Kontenrahmen sind offen (D-06, Offener Punkt 7); bis zum Beschluss gibt es nur das neutrale Testformat
 * [NeutralJsonExport] (kein DATEV, DOC-025 ist M8). Externe Aufrufe nie innerhalb einer offenen SQL-Transaktion (Leitlinie 3).
 */
interface ErpExportAdapter {
    /** Formatname, z. B. `taktovia-neutral-json`. */
    val format: String
    val version: String
    fun render(batch: ErpBatch): ErpRendering
}

/**
 * Neutrales Testformat (Vorschlag des Umsetzungsplans bis D-06): kanonisches JSON mit sortierten Schlüsseln, Journale nach Buchungsdatum und Kennung, Zeilen nach Konto, Seite und Betrag, UTF-8 ohne
 * Leerraum. Beträge sind Ganzzahlen in kleinster Einheit (nie Gleitkomma).
 */
class NeutralJsonExport : ErpExportAdapter {
    override val format = "taktovia-neutral-json"
    override val version = "1"

    override fun render(batch: ErpBatch): ErpRendering {
        val journals = batch.journals.sortedWith(compareBy({ it.accountingDate }, { it.journalId })).map { j ->
            sortedMapOf<String, Any?>(
                "accounting_date" to j.accountingDate, "currency" to j.currency, "document_id" to j.documentId, "event_id" to j.eventId, "journal_id" to j.journalId, "reverses_event_id" to j.reversesEventId,
                "lines" to j.lines.sortedWith(compareBy({ it.account }, { it.side }, { it.amountMinor })).map { l -> sortedMapOf<String, Any?>("account" to l.account, "amount_minor" to l.amountMinor, "side" to l.side) },
            )
        }
        val doc = sortedMapOf<String, Any?>(
            "batch_ref" to batch.batchRef, "export_id" to batch.exportId, "format" to format, "format_version" to version, "journals" to journals, "legal_entity_id" to batch.legalEntityId,
            "period_from" to batch.periodFrom, "period_to" to batch.periodTo,
        )
        return ErpRendering("application/json", "json", CanonicalJson.write(doc).toByteArray(Charsets.UTF_8))
    }
}
