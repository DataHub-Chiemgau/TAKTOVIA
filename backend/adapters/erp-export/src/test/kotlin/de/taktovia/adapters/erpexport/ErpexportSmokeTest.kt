package de.taktovia.adapters.erpexport

import de.taktovia.adapters.erpexport.api.ErpBatch
import de.taktovia.adapters.erpexport.api.ErpExportAdapter
import de.taktovia.adapters.erpexport.api.ErpJournal
import de.taktovia.adapters.erpexport.api.ErpLine
import de.taktovia.adapters.erpexport.api.NeutralJsonExport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** WP-M4-37: das neutrale Testformat ist ein reiner, deterministischer Adapter ohne Gleitkomma; die Reihenfolge der Eingabe ändert die Ausgabe nicht. */
class ErpexportSmokeTest {
    private val j1 = ErpJournal("00000000-0000-4000-8000-000000000001", "e1", "d1", null, "2026-09-15", "EUR", listOf(ErpLine("6600", "D", 10000), ErpLine("1600", "C", 10000)))
    private val j2 = ErpJournal("00000000-0000-4000-8000-000000000002", "e2", "d1", "e1", "2026-10-05", "EUR", listOf(ErpLine("1600", "D", 2000), ErpLine("6600", "C", 2000)))
    private val batch = ErpBatch("EXP-ABCDEF123456", "x1", "le1", "2026-09-01", "2026-11-01", listOf(j1, j2))

    @Test
    fun `adapter-port ist ein interface`() = assertTrue(ErpExportAdapter::class.java.isInterface)

    @Test
    fun `gleiche eingabe ergibt gleiche bytes unabhaengig von der reihenfolge`() {
        val a = NeutralJsonExport().render(batch); val b = NeutralJsonExport().render(batch.copy(journals = listOf(j2.copy(lines = j2.lines.reversed()), j1)))
        assertEquals(String(a.bytes), String(b.bytes)); assertEquals("application/json", a.mediaType)
        val text = String(a.bytes)
        assertTrue(text.startsWith("{\"batch_ref\":\"EXP-ABCDEF123456\",\"export_id\":\"x1\",\"format\":\"taktovia-neutral-json\""), text)
        assertTrue(text.contains("\"amount_minor\":10000"), "Beträge als Ganzzahl in kleinster Einheit")
        assertFalse(Regex("[0-9]\\.[0-9]").containsMatchIn(text), "keine Dezimalzahlen")
        assertTrue(text.indexOf("000000000001") < text.indexOf("000000000002"), "Journale nach Buchungsdatum und Kennung")
    }

    @Test
    fun `sonderzeichen werden maskiert`() {
        val text = String(NeutralJsonExport().render(batch.copy(batchRef = "EXP-\"X\\\n", journals = emptyList())).bytes)
        assertTrue(text.contains("EXP-\\\"X\\\\\\u000a"), text)
    }
}
