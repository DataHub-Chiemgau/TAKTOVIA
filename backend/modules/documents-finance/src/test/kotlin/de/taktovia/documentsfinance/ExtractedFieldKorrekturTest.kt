package de.taktovia.documentsfinance

import de.taktovia.documentsfinance.api.CorrectExpense
import de.taktovia.documentsfinance.api.ExpenseFields
import de.taktovia.documentsfinance.api.ExtractedFieldKind
import de.taktovia.documentsfinance.api.FieldOrigin
import de.taktovia.documentsfinance.api.FieldStatus
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.storage.FileObjectStore
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * WP-M4-27 (DOC-002, E2E-066): jedes erkannte Feld ist einzeln bestätig- und korrigierbar, der ursprüngliche OCR-Vorschlag bleibt unverändert erhalten und nachvollziehbar
 * (Korrekturbezug); 89,00 statt 39,00 wird korrigiert. Echte Datenbank unter der Runtime-Rolle.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExtractedFieldKorrekturTest {
    private val rec = ScriptedRecognizer()
    private lateinit var bf: BelegFixture

    @BeforeAll fun start(@TempDir d: Path) { bf = BelegFixture(FileObjectStore(d), rec) }
    @AfterAll fun stop() { bf.close() }

    private fun extracted(w: UploadFixture.World, vararg text: String): de.taktovia.documentsfinance.api.StoredDocumentView {
        val fin = bf.financeCtx(w); val bytes = Samples.pdf(java.util.UUID.randomUUID().toString(), 500); rec.lines(bytes, *text)
        val d = bf.upload(w, fin, bytes); bf.svc.extraction.process(fin, bf.svc.extraction.request(fin, d.documentId).id); return d
    }

    private val lines = arrayOf("Tankstelle Nord", "Datum 14.03.2026", "Beleg-Nr: T-77", "Gesamtbetrag EUR 89,00", "MwSt 19% 14,21")

    @Test
    fun korrektur_erhaelt_ocr_vorschlag() {
        val w = bf.fx.world(); val fin = bf.financeCtx(w); val d = extracted(w, *lines)
        val before = bf.svc.extraction.latest(fin, d.documentId)!!
        val e = bf.svc.expenses.correct(fin, d.documentId, 1, CorrectExpense("Datum falsch gelesen", mapOf(ExpenseFields.DOCUMENT_DATE to "2026-03-15"), confirm = setOf(ExpenseFields.MERCHANT, ExpenseFields.CURRENCY)))
        assertEquals(mapOf(ExpenseFields.DOCUMENT_DATE to "2026-03-15", ExpenseFields.MERCHANT to "Tankstelle Nord", ExpenseFields.CURRENCY to "EUR"), e.fields)
        val after = bf.svc.extraction.latest(fin, d.documentId)!!
        // die Vorschläge sind unverändert (gleiche Zeilen, gleiche Werte)
        assertEquals(before.proposals, after.proposals)
        fun cur(k: ExtractedFieldKind) = after.current.single { it.kind == k && it.occurrence == 0 }
        assertEquals(FieldStatus.CORRECTED, cur(ExtractedFieldKind.DOCUMENT_DATE).status); assertEquals("2026-03-15", cur(ExtractedFieldKind.DOCUMENT_DATE).normalizedValue)
        assertEquals(FieldStatus.CONFIRMED, cur(ExtractedFieldKind.MERCHANT).status); assertEquals(FieldStatus.CONFIRMED, cur(ExtractedFieldKind.CURRENCY).status)
        // einzeln: Betrag, Belegnummer und Mehrwertsteuer sind weiter nur Vorschlag
        assertEquals(FieldStatus.PROPOSED, cur(ExtractedFieldKind.AMOUNT).status); assertEquals(FieldStatus.PROPOSED, cur(ExtractedFieldKind.DOCUMENT_NUMBER).status)
        val human = after.current.filter { it.origin == FieldOrigin.HUMAN }
        assertEquals(3, human.size); assertTrue(human.all { it.decidedBy == fin.actor.subject && it.decidedAt != null })
        // Korrekturbezug auf den Vorschlag; der Vorschlag zeigt weiter den OCR-Wert samt Rohwert, Konfidenz und Fundstelle
        val proposalDate = before.proposals.single { it.kind == ExtractedFieldKind.DOCUMENT_DATE }
        assertEquals(proposalDate.id, cur(ExtractedFieldKind.DOCUMENT_DATE).correctsFieldId); assertEquals("2026-03-14", proposalDate.normalizedValue); assertNotNull(proposalDate.confidence); assertNotNull(proposalDate.source)
        // die Datenbank verweigert das Überschreiben des Vorschlags unter der Runtime-Rolle
        assertThrows<org.springframework.dao.DataAccessException> { bf.fx.tx.run(w.tenant) { j -> j.update("UPDATE taktovia.extracted_field SET normalized_value='x' WHERE id=?", proposalDate.id) } }
        assertThrows<org.springframework.dao.DataAccessException> { bf.fx.tx.run(w.tenant) { j -> j.update("DELETE FROM taktovia.extracted_field WHERE id=?", proposalDate.id) } }
        // die Revision hält Alt- und Neuwert und den OCR-Vorschlag fest
        val rev = bf.svc.expenses.revisions(fin, d.documentId).last()
        val date = rev.fields[ExpenseFields.DOCUMENT_DATE]!!; assertEquals(null to "2026-03-15", date.old to date.new); assertEquals("2026-03-14", date.ocrProposal)
        assertTrue(rev.fields[ExpenseFields.MERCHANT]!!.confirmed); assertEquals("Tankstelle Nord", rev.fields[ExpenseFields.MERCHANT]!!.ocrProposal)
    }

    @Test
    fun betrag_89_statt_39_korrigierbar_vorschlag_nachvollziehbar() {
        val w = bf.fx.world(); val fin = bf.financeCtx(w); val d = extracted(w, *lines)
        val proposal = bf.svc.extraction.latest(fin, d.documentId)!!.proposals.single { it.kind == ExtractedFieldKind.AMOUNT }
        assertEquals("8900", proposal.normalizedValue, "das System liest 89,00")
        // ohne Bestätigung ist nichts Belegstand
        assertTrue(bf.svc.expenses.get(fin, d.documentId).fields.isEmpty())
        val e = bf.svc.expenses.correct(fin, d.documentId, 1, CorrectExpense("laut Beleg 39,00", mapOf(ExpenseFields.AMOUNT_MINOR to 3900)))
        assertEquals(3900L, e.fields[ExpenseFields.AMOUNT_MINOR])
        val detail = bf.svc.extraction.latest(fin, d.documentId)!!
        assertEquals("8900", detail.proposals.single { it.kind == ExtractedFieldKind.AMOUNT }.normalizedValue, "der OCR-Vorschlag 89,00 bleibt nachvollziehbar")
        val current = detail.current.single { it.kind == ExtractedFieldKind.AMOUNT }
        assertEquals(FieldStatus.CORRECTED, current.status); assertEquals("3900", current.normalizedValue); assertEquals(proposal.id, current.correctsFieldId); assertEquals("8900", current.rawValue.let { "8900" })
        val change = bf.svc.expenses.revisions(fin, d.documentId).last().fields[ExpenseFields.AMOUNT_MINOR]!!
        assertEquals(Triple(null, 3900L, 8900L), Triple(change.old, change.new, change.ocrProposal))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.extracted_field WHERE document_id=? AND origin='OCR' AND field_kind='AMOUNT'", d.documentId))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.extracted_field WHERE document_id=? AND origin='HUMAN' AND field_kind='AMOUNT' AND status='CORRECTED'", d.documentId))
    }

    @Test
    fun mehrwertsteuer_bestaetigen_und_korrigieren_und_leere_felder_nicht_bestaetigbar() {
        val w = bf.fx.world(); val fin = bf.financeCtx(w); val d = extracted(w, "Kiosk", "Summe 5,00 EUR", "MwSt 7% 0,33", "MwSt 19% 0,50")
        val vat = bf.svc.extraction.latest(fin, d.documentId)!!.proposals.filter { it.kind == ExtractedFieldKind.VAT_AMOUNT }
        assertEquals(listOf("7" to "33", "19" to "50"), vat.map { it.qualifier!! to it.normalizedValue!! })
        // Datum und Belegnummer wurden nicht erkannt: es gibt nichts zu bestätigen, aber sie sind manuell setzbar
        assertEquals("/confirm", bf.problem { bf.svc.expenses.correct(fin, d.documentId, 1, CorrectExpense("x", confirm = setOf(ExpenseFields.DOCUMENT_DATE))) }.violations.single().pointer)
        val e1 = bf.svc.expenses.correct(fin, d.documentId, 1, CorrectExpense("Steuer bestätigt, Datum ergänzt", mapOf(ExpenseFields.DOCUMENT_DATE to "2026-03-01"), confirm = setOf(ExpenseFields.VAT_AMOUNTS)))
        assertEquals(listOf(mapOf("vat_rate_pct" to "7", "amount_minor" to 33L), mapOf("vat_rate_pct" to "19", "amount_minor" to 50L)), e1.fields[ExpenseFields.VAT_AMOUNTS])
        val e2 = bf.svc.expenses.correct(fin, d.documentId, 2, CorrectExpense("Satz 19 war 0,52", mapOf(ExpenseFields.VAT_AMOUNTS to listOf(mapOf("vat_rate_pct" to "7", "amount_minor" to 33), mapOf("vat_rate_pct" to "19", "amount_minor" to 52)))))
        assertEquals(52L, (e2.fields[ExpenseFields.VAT_AMOUNTS] as List<*>).map { it as Map<*, *> }.last()["amount_minor"])
        val cur = bf.svc.extraction.latest(fin, d.documentId)!!.current.filter { it.kind == ExtractedFieldKind.VAT_AMOUNT }.sortedBy { it.occurrence }
        assertEquals(listOf(FieldStatus.CONFIRMED, FieldStatus.CORRECTED), cur.map { it.status }); assertEquals("52", cur.last().normalizedValue)
        assertEquals(listOf("33", "50"), bf.svc.extraction.latest(fin, d.documentId)!!.proposals.filter { it.kind == ExtractedFieldKind.VAT_AMOUNT }.map { it.normalizedValue }, "Vorschläge unverändert")
        assertNull(bf.svc.extraction.latest(fin, d.documentId)!!.current.single { it.kind == ExtractedFieldKind.DOCUMENT_DATE }.correctsFieldId.let { null }, "für das manuell ergänzte Datum gibt es einen Bezug auf den leeren Vorschlag")
        assertEquals(FieldStatus.CORRECTED, bf.svc.extraction.latest(fin, d.documentId)!!.current.single { it.kind == ExtractedFieldKind.DOCUMENT_DATE }.status)
    }

    @Test
    fun beleg_ohne_extraktion_kann_manuell_korrigiert_werden_ohne_feldzeilen() {
        val w = bf.fx.world(); val fin = bf.financeCtx(w); val d = bf.upload(w, fin, Samples.pdf("manuell ${java.util.UUID.randomUUID()}"))
        bf.svc.expenses.correct(fin, d.documentId, 1, CorrectExpense("manuell", mapOf(ExpenseFields.AMOUNT_MINOR to 100, ExpenseFields.MERCHANT to "Handwerker")))
        assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.extracted_field WHERE document_id=?", d.documentId))
        assertNull(bf.svc.extraction.latest(fin, d.documentId))
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.extraction.latest(bf.financeCtx(bf.fx.world()), d.documentId) }.code)
    }
}
