package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.ReportDefect
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-20 / LH-6.21-CHK-011, LH-6.22-DMG-003 unter der Runtime-Rolle gegen die echte Datenbank. Originalabnahmen: ein negativer Prüfschritt kann einen nachverfolgbaren Schaden-/Mangelvorgang
 * erzeugen (CHK-011); die Verknüpfung von der Checkliste zur Schadenakte und zurück funktioniert in beide Richtungen (DMG-003). Positiv, negativ (fremder Lauf, Fahrer ohne Bezug, Pflichtangaben,
 * Clientwerte, Foto ohne Medienprüfung, nicht negativer Schritt) und konkurrierend (doppelte Übermittlung, zwei Geräte gleichzeitig).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefectFromInspectionTest {
    private lateinit var fx: FleetFixture

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)

    @Test
    fun negativer_pruefschritt_erzeugt_schadenakte_mit_fotos() {
        val w = OriginWorld(fx); w.answer("STEP_2")
        val a = w.photo(1); val b = w.photo(2)
        val r = w.report(raw = w.raw(extra = mapOf("evidence_ids" to listOf(a.toString(), b.toString()))))
        val d = r.defect
        assertTrue(r.created); assertEquals("NEW", d.status); assertTrue(Regex("^SCH-\\d{4}-\\d{6}$").matches(d.caseNumber), d.caseNumber)
        assertEquals(w.run.id, d.sourceInspectionId); assertEquals("STEP_2", d.sourceStepCode); assertEquals("GLASS", d.categoryCode); assertEquals("Riss in der Windschutzscheibe", d.description)
        // zwei Fotos mit sichtbarem Aufnahmedatum (Gerätezeit) und Serverempfangszeit, beide freigegeben
        val full = w.view(d.id); assertEquals(2, full.evidence.size); assertEquals(0, full.evidencePending)
        full.evidence.forEach { e -> assertNotNull(e.capturedAt); assertNotNull(e.receivedAt); assertTrue(e.capturedAt.isBefore(e.receivedAt) || e.capturedAt == e.receivedAt) }
        // Ereignis mit Herkunft, ohne Beschreibungstext
        val payload = tools.jackson.databind.json.JsonMapper.builder().build().readTree(fx.strings("SELECT payload::text FROM taktovia.outbox WHERE aggregate_id=? AND event_type='defect.reported'", d.id).single())
        assertEquals(w.run.id.toString(), payload["source_inspection_id"].asString()); assertEquals("STEP_2", payload["source_step_code"].asString()); assertFalse(payload.has("description"))
    }

    @Test
    fun schadenakte_aus_pruefschritt_beidseitig_verknuepft() {
        val w = OriginWorld(fx); w.answer("STEP_2")
        val snapshot = "SELECT xmin::text || ':' || row_to_json(a)::text FROM taktovia.inspection_answer a WHERE inspection_id=? ORDER BY step_code"
        val before = fx.strings(snapshot, w.run.id)
        val d = w.report().defect
        // das Verknüpfen fasst die Antwort nicht an (auch nicht ihre Zeilenversion): die Gegenreferenz folgt aus der Herkunft der Akte
        assertEquals(before, fx.strings(snapshot, w.run.id))
        // Prüfung -> Akte
        val inspection = w.h.fleet.inspections.get(w.run.ctx, w.run.id)
        assertEquals(listOf("STEP_2" to d.id), inspection.defectRefs.map { it.stepCode to it.defectId })
        assertEquals(d.caseNumber, inspection.defectRefs.single().caseNumber)
        // Akte -> Prüfschritt
        val back = w.view(d.id); assertEquals(w.run.id, back.sourceInspectionId); assertEquals("STEP_2", back.sourceStepCode)
        // die Disposition sieht dieselbe Verknüpfung; auch nach dem Abschluss des Laufs bleibt sie bestehen
        assertEquals(inspection.defectRefs, w.h.fleet.inspections.get(w.rw.ctx("dispatcher"), w.run.id).defectRefs)
        val done = w.rw.submit(w.run, w.rw.allOk().map { if (it.stepCode == "STEP_2") w.rw.answer("STEP_2", "DEFECT", note = "Riss sichtbar") else it })
        assertEquals("COMPLETED", done.status); assertEquals(listOf("STEP_2" to d.id), done.defectRefs.map { it.stepCode to it.defectId })
        val closed = fx.strings(snapshot, w.run.id)
        // nach dem Abschluss: weiterer Auslöser ändert weder die Antwort noch erzeugt er eine zweite Akte
        val again = w.report(raw = w.raw()); assertFalse(again.created); assertEquals(d.id, again.defect.id)
        assertEquals(closed, fx.strings(snapshot, w.run.id)); assertEquals(1, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE source_inspection_id=?", w.run.id))
    }

    @Test
    fun abweichung_an_nicht_kritischem_negativem_schritt_moeglich() {
        val w = OriginWorld(fx)
        val steps = w.h.fleet.inspections.get(w.run.ctx, w.run.id).template.steps
        assertEquals(listOf(true, false, false), steps.map { it.safetyCritical }, "STEP_1 kritisch, STEP_2 und STEP_3 nicht")
        w.answer("STEP_3", "DEFECT", "Ablage lose")                       // nicht kritisch
        val d = w.report(raw = w.raw("STEP_3", extra = mapOf("category_code" to "INTERIOR", "description" to "Ablage lose"))).defect
        assertEquals("STEP_3", d.sourceStepCode)
        w.answer("STEP_1", "DEFECT", "Bremse weich")                       // kritisch ebenfalls
        assertEquals("STEP_1", w.report(raw = w.raw("STEP_1", extra = mapOf("category_code" to "BRAKES", "description" to "Bremse weich"))).defect.sourceStepCode)
    }

    @Test
    fun fremder_pruefschritt_oder_fahrer_ohne_bezug_ergibt_404() {
        val w = OriginWorld(fx); w.answer("STEP_2"); val d = w.report().defect
        val other = OriginWorld(fx)                                       // fremder Mandant mit eigenem Lauf
        assertEquals(ErrorCode.NOT_FOUND, problem { other.report(raw = other.raw(inspection = w.run.id, vehicle = other.run.vehicle)) }.code)
        // ein anderer Fahrer desselben Mandanten ohne Bezug zum Fahrzeug
        val stranger = w.rw.iw.ctx(UUID.randomUUID(), "driver")
        assertEquals(ErrorCode.NOT_FOUND, problem { w.report(stranger) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { w.view(d.id, stranger) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { w.h.fleet.inspections.get(stranger, w.run.id) }.code)
        // ein anderer Fahrer mit Schicht auf demselben Fahrzeug, aber ein Lauf eines anderen Fahrers: 404, die Antwort verrät nichts
        val d2 = UUID.randomUUID(); val shift2 = w.rw.iw.shift(d2, w.run.vehicle, fromHours = -10, toHours = -3)   // endete vor 3 h: Fahrzeugbezug besteht, der Lauf gehört aber dem anderen Fahrer
        assertEquals(ErrorCode.NOT_FOUND, problem { w.report(w.rw.iw.ctx(d2, "driver"), w.raw("STEP_3")) }.code)
        // unbekannte Kennung
        assertEquals(ErrorCode.NOT_FOUND, problem { w.report(raw = w.raw(inspection = UUID.randomUUID())) }.code)
        assertNotNull(shift2)
    }

    @Test
    fun fremder_pruflauf_und_fahrer_ohne_bezug_404() {
        val w = OriginWorld(fx); w.answer("STEP_2")
        val other = OriginWorld(fx)
        assertEquals(ErrorCode.NOT_FOUND, problem { w.report(raw = w.raw(inspection = other.run.id)) }.code, "Prüflauf eines anderen Mandanten")
        assertEquals(ErrorCode.NOT_FOUND, problem { w.report(w.rw.iw.ctx(UUID.randomUUID(), "driver")) }.code, "Fahrer ohne Bezug")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
    }

    @Test
    fun fehlende_pflichtangaben_text_kategorie_aufnahmedatum_ergeben_422() {
        val w = OriginWorld(fx); w.answer("STEP_2")
        fun pointers(raw: Map<String, Any?>) = problem { ReportDefect.parse(raw) }.violations.map { it.pointer to it.code }
        assertEquals(listOf("/description" to ViolationCode.REQUIRED), pointers(w.raw().filterKeys { it != "description" }))
        assertEquals(listOf("/category_code" to ViolationCode.REQUIRED), pointers(w.raw().filterKeys { it != "category_code" }))
        assertEquals(listOf("/source_step_code" to ViolationCode.REQUIRED), pointers(w.raw(step = null)))
        assertEquals(listOf("/source_inspection_id" to ViolationCode.REQUIRED), pointers(w.raw(inspection = null)))
        // Foto ohne Aufnahmedatum: das Uploadticket verlangt es (422 mit Feldverweis)
        val bytes = w.h.jpeg(7); val noDate = w.h.ticketRaw(bytes).filterKeys { it != "captured_at" }
        assertEquals(listOf("/captured_at" to ViolationCode.REQUIRED), problem { de.taktovia.fleetsafety.api.RequestEvidenceTicket.parse(noDate) }.violations.map { it.pointer to it.code })
        // Schritt nicht negativ beantwortet bzw. gar nicht beantwortet: 422 am Schritt, keine Akte
        w.answer("STEP_3", "OK", null)
        assertEquals("/source_step_code", problem { w.report(raw = w.raw("STEP_3")) }.violations.single().pointer)
        assertEquals("/source_step_code", problem { w.report(raw = w.raw("STEP_NICHT_DA")) }.violations.single().pointer)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
    }

    @Test
    fun clientwerte_zu_mandant_meldender_status_ignoriert() {
        val w = OriginWorld(fx); w.answer("STEP_2")
        for (field in listOf("tenant_id", "reported_by", "status", "case_number", "severity", "id"))
            assertEquals(ViolationCode.UNKNOWN_FIELD, problem { ReportDefect.parse(w.raw(extra = mapOf(field to "x"))) }.violations.single().code, field)
        val d = w.report().defect
        assertEquals("NEW", d.status); assertEquals(UUID.fromString(w.run.ctx.actor.subject), d.reportedBy)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE id=? AND tenant_id=?", d.id, w.tenant.value))
    }

    @Test
    fun foto_ohne_bestandene_medienpruefung_nicht_sichtbar() {
        val w = OriginWorld(fx); w.answer("STEP_2")
        w.h.inspector.available = false                                    // Prüfer ausgefallen: das Foto bleibt in Prüfung (fail-closed)
        val pending = w.photo(3)
        w.h.inspector.available = true
        val ok = w.photo(4)
        val d = w.report(raw = w.raw(extra = mapOf("evidence_ids" to listOf(pending.toString(), ok.toString())))).defect
        val view = w.view(d.id)
        assertEquals(1, view.evidence.size, "nur das geprüfte Foto ist an der Abweichung sichtbar"); assertEquals(1, view.evidencePending)
        assertFalse(view.evidence.any { it.id == pending })
    }

    @Test
    fun doppelte_uebermittlung_erzeugt_genau_eine_akte() {
        val w = OriginWorld(fx); w.answer("STEP_2")
        val first = w.report(); assertTrue(first.created)
        // Wiederholung nach Zeitüberschreitung: mit und ohne Geräteereignis-ID, mit anderem Text: dieselbe Akte, nichts Neues
        val again = w.report(raw = w.raw(extra = mapOf("device_event_id" to UUID.randomUUID().toString(), "description" to "anderer Text")))
        assertFalse(again.created); assertEquals(first.defect.id, again.defect.id); assertEquals("Riss in der Windschutzscheibe", again.defect.description)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value)); assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='defect.reported'", w.tenant.value))
    }

    @Test
    fun zwei_geraete_parallel_selber_pruefschritt_genau_eine_akte() {
        val w = OriginWorld(fx); w.answer("STEP_2")
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val results = (0 until 8).map { i -> pool.submit<Pair<UUID, Boolean>> { start.await(); w.report(raw = w.raw(extra = mapOf("device_event_id" to UUID.randomUUID().toString()))).let { it.defect.id to it.created } } }
            start.countDown()
            val out = results.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(1, out.map { it.first }.toSet().size, "alle Geräte erhalten dieselbe Akte"); assertEquals(1, out.count { it.second }, "genau eine Übermittlung hat angelegt")
        } finally { pool.shutdownNow() }
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", w.tenant.value))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='defect.reported'", w.tenant.value))
    }
}
