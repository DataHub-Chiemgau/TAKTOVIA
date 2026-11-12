package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.ChecklistAnswer
import de.taktovia.fleetsafety.api.ChecklistAnswerValidator
import de.taktovia.fleetsafety.api.ChecklistInputKind
import de.taktovia.fleetsafety.api.ChecklistStepRule
import de.taktovia.fleetsafety.api.ChecklistTemplateInput
import de.taktovia.fleetsafety.api.ChecklistTemplateState
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-10 / LH-6.21-CHK-003: Checklistenvorlagen und Prüfelemente. Originalabnahme: ungültige oder fehlende Antworten blockieren den Abschluss (hier die Validierungsfunktion mit
 * Elementverweis; die Blockade am Submit weist WP-M3-14 nach), „Nicht anwendbar“ erfordert bei definierten Punkten eine Begründung. Datenbankabhängige Fälle laufen gegen die echte
 * Datenbank unter der Runtime-Rolle.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChecklistTemplateTest {
    private lateinit var fx: FleetFixture

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private fun ctx(tenant: TenantId, who: String) = RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/fleet", who, "sid-$who", true), tenant, setOf("fleet_safety"), setOf("inspections:write"), emptySet(), UUID.randomUUID())
    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)

    private fun step(code: String, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        linkedMapOf<String, Any?>("step_code" to code, "title" to "Titel $code", "input_kind" to "OK_DEFECT_NA", "mandatory" to true) + extra

    private fun body(steps: List<Map<String, Any?>> = listOf(step("BRAKES", mapOf("safety_critical" to true, "help_text" to "Pedal treten"))), name: String = "Fahrzeugprüfung", cls: String = "PKW") =
        linkedMapOf("name" to name, "vehicle_class" to cls, "service_mode" to "ONDEMAND", "steps" to steps)

    private val allKinds = listOf(
        step("BRAKES", mapOf("safety_critical" to true, "na_requires_reason" to true, "help_text" to "Bremsen prüfen")),
        step("TYRE_DEPTH", mapOf("input_kind" to "NUMBER", "number_min" to 1.6, "number_max" to 10, "number_unit" to "mm")),
        step("REMARK", mapOf("input_kind" to "TEXT_SHORT", "mandatory" to false)),
    )

    @Test
    fun vorlage_mit_allen_eingabetypen_speichern_freigeben_und_hilfetext_unveraendert_lesen() {
        val w = fx.world(); val author = ctx(w.tenant, "author-" + UUID.randomUUID()); val reviewer = ctx(w.tenant, "reviewer-" + UUID.randomUUID())
        val d = fx.fleet.checklistTemplates.create(author, ChecklistTemplateInput.parse(body(allKinds)))
        assertEquals(ChecklistTemplateState.DRAFT, d.state)
        assertEquals(listOf(ChecklistInputKind.OK_DEFECT_NA, ChecklistInputKind.NUMBER, ChecklistInputKind.TEXT_SHORT), d.steps.map { it.kind })
        assertEquals("Bremsen prüfen", d.steps[0].helpText); assertTrue(d.steps[0].safetyCritical); assertTrue(d.steps[0].naRequiresReason)
        assertEquals(0, BigDecimal("1.6").compareTo(d.steps[1].numberMin)); assertEquals("mm", d.steps[1].numberUnit)
        val p = fx.fleet.checklistTemplates.publish(reviewer, d.id, d.revision)
        assertEquals(ChecklistTemplateState.PUBLISHED, p.state); assertEquals("user:" + reviewer.actor.subject, p.approvedBy)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.checklist_template.published' AND object_ref=?", w.tenant.value, "checklist_template:${d.id}"))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='checklist_template.published'", w.tenant.value, d.id))
        // Antworten auf alle Typen: gültig
        val rules = p.steps.map { it.rule() }
        assertEquals(emptyList<Any>(), ChecklistAnswerValidator.validate(rules, listOf(
            ChecklistAnswer("BRAKES", "NOT_APPLICABLE", null, "Fahrzeug ohne Bremse im Test"), ChecklistAnswer("TYRE_DEPTH", "OK", BigDecimal("3.2"), null), ChecklistAnswer("REMARK", "OK", null, "ohne Auffälligkeit")), true))
    }

    @Test
    fun pruefelement_ohne_pflichtfelder_nicht_speicherbar() {
        fun reject(name: String, pointer: String, code: ViolationCode, steps: List<Map<String, Any?>>) {
            val e = problem { ChecklistTemplateInput.parse(body(steps)) }
            assertEquals(ErrorCode.VALIDATION_FAILED, e.code, name)
            assertTrue(e.violations.any { it.pointer == pointer && it.code == code }, "$name: erwartet $pointer/$code, erhalten ${e.violations}")
        }
        reject("ohne Titel", "/steps/0/title", ViolationCode.REQUIRED, listOf(step("AA") - "title"))
        reject("leerer Titel", "/steps/0/title", ViolationCode.TOO_SHORT, listOf(step("AA", mapOf("title" to "   "))))
        reject("ohne Eingabetyp", "/steps/0/input_kind", ViolationCode.REQUIRED, listOf(step("AA") - "input_kind"))
        reject("unbekannter Eingabetyp", "/steps/0/input_kind", ViolationCode.NOT_IN_ENUM, listOf(step("AA", mapOf("input_kind" to "FREITEXT_LANG"))))
        reject("ohne Pflichtstatus", "/steps/0/mandatory", ViolationCode.REQUIRED, listOf(step("AA") - "mandatory"))
        reject("Pflichtstatus kein Boolean", "/steps/0/mandatory", ViolationCode.TYPE_MISMATCH, listOf(step("AA", mapOf("mandatory" to "ja"))))
        reject("Grenzen nur bei NUMBER", "/steps/0/number_min", ViolationCode.INVALID_VALUE, listOf(step("AA", mapOf("number_min" to 1))))
        reject("doppelter Schlüssel", "/steps/1/step_code", ViolationCode.NOT_UNIQUE, listOf(step("AA"), step("AA")))
        reject("keine Prüfelemente", "/steps", ViolationCode.TOO_FEW_ITEMS, emptyList())
        // und über die Datenbank (Persistenzpfad): ein ungültiges Element wird nicht gespeichert
        val w = fx.world()
        val before = fx.count("SELECT count(*) FROM taktovia.checklist_template_revision WHERE tenant_id=?", w.tenant.value)
        problem { fx.fleet.checklistTemplates.create(ctx(w.tenant, "a-" + UUID.randomUUID()), ChecklistTemplateInput.parse(body(listOf(step("AA") - "title")))) }
        assertEquals(before, fx.count("SELECT count(*) FROM taktovia.checklist_template_revision WHERE tenant_id=?", w.tenant.value))
    }

    private val rules = listOf(
        ChecklistStepRule("BRAKES", ChecklistInputKind.OK_DEFECT_NA, true, true, null, null),
        ChecklistStepRule("TYRE_DEPTH", ChecklistInputKind.NUMBER, true, false, BigDecimal("1.6"), BigDecimal("10")),
        ChecklistStepRule("CLEAN", ChecklistInputKind.OK_DEFECT_NA, false, false, null, null),
    )

    @Test
    fun nicht_anwendbar_ohne_begruendung_abgewiesen() {
        val ohne = ChecklistAnswerValidator.validate(rules, listOf(ChecklistAnswer("BRAKES", "NOT_APPLICABLE", null, null), ChecklistAnswer("TYRE_DEPTH", "OK", BigDecimal("3"), null)), true)
        assertEquals(listOf("/answers/BRAKES/note" to ViolationCode.REQUIRED), ohne.map { it.pointer to it.code })
        assertEquals(1, ChecklistAnswerValidator.validate(rules, listOf(ChecklistAnswer("BRAKES", "NOT_APPLICABLE", null, "  "), ChecklistAnswer("TYRE_DEPTH", "OK", BigDecimal("3"), null)), true).size)
        // bei nicht markiertem Punkt braucht „Nicht anwendbar“ keine Begründung
        assertEquals(emptyList<Any>(), ChecklistAnswerValidator.validate(rules, listOf(ChecklistAnswer("BRAKES", "OK", null, null), ChecklistAnswer("TYRE_DEPTH", "OK", BigDecimal("3"), null), ChecklistAnswer("CLEAN", "NOT_APPLICABLE", null, null)), true))
        // mit Begründung zulässig
        assertEquals(emptyList<Any>(), ChecklistAnswerValidator.validate(rules, listOf(ChecklistAnswer("BRAKES", "NOT_APPLICABLE", null, "nicht vorhanden"), ChecklistAnswer("TYRE_DEPTH", "OK", BigDecimal("3"), null)), true))
        val e = problem { ChecklistAnswerValidator.require(rules, listOf(ChecklistAnswer("BRAKES", "NOT_APPLICABLE", null, null), ChecklistAnswer("TYRE_DEPTH", "OK", BigDecimal("3"), null)), true) }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code)
    }

    @Test
    fun fehlende_oder_ungueltige_antwort_blockiert_mit_elementverweis() {
        fun v(vararg a: ChecklistAnswer, complete: Boolean = true) = ChecklistAnswerValidator.validate(rules, a.toList(), complete).map { it.pointer to it.code }
        val ok = ChecklistAnswer("BRAKES", "OK", null, null)
        val depth = ChecklistAnswer("TYRE_DEPTH", "OK", BigDecimal("3"), null)
        assertEquals(emptyList<Any>(), v(ok, depth))
        // fehlende Pflichtantwort: Verweis auf das Element (nicht Pflichtelement CLEAN bleibt zulässig leer)
        assertEquals(listOf("/answers/TYRE_DEPTH" to ViolationCode.REQUIRED), v(ok))
        assertEquals(listOf("/answers/BRAKES" to ViolationCode.REQUIRED, "/answers/TYRE_DEPTH" to ViolationCode.REQUIRED), v())
        assertEquals(emptyList<Any>(), v(complete = false))   // Entwurf: fehlende Antworten sind zulässig
        // Wert außerhalb des Eingabetyps
        assertEquals(listOf("/answers/TYRE_DEPTH/number_value" to ViolationCode.BELOW_MINIMUM), v(ok, ChecklistAnswer("TYRE_DEPTH", "OK", BigDecimal("1.0"), null)))
        assertEquals(listOf("/answers/TYRE_DEPTH/number_value" to ViolationCode.ABOVE_MAXIMUM), v(ok, ChecklistAnswer("TYRE_DEPTH", "OK", BigDecimal("11"), null)))
        assertEquals(listOf("/answers/TYRE_DEPTH/number_value" to ViolationCode.REQUIRED), v(ok, ChecklistAnswer("TYRE_DEPTH", "OK", null, null)))
        assertEquals(listOf("/answers/BRAKES/number_value" to ViolationCode.INVALID_VALUE), v(ChecklistAnswer("BRAKES", "OK", BigDecimal.ONE, null), depth))
        // vierter oder unbekannter Statuswert; „Nicht prüfbar“ (OP-16) gilt nicht als gültige Antwort
        assertEquals(listOf("/answers/BRAKES/answer" to ViolationCode.NOT_IN_ENUM), v(ChecklistAnswer("BRAKES", "NOT_CHECKABLE", null, null), depth))
        assertEquals(listOf("/answers/BRAKES/answer" to ViolationCode.NOT_IN_ENUM), v(ChecklistAnswer("BRAKES", "ok", null, null), depth))
        assertEquals(listOf("/answers/BRAKES/answer" to ViolationCode.REQUIRED), v(ChecklistAnswer("BRAKES", null, null, null), depth))
        // Antwort auf ein Element, das nicht zur Revision gehört; doppelte Antwort
        assertEquals(listOf("/answers/FREMD" to ViolationCode.NOT_IN_ENUM), v(ok, depth, ChecklistAnswer("FREMD", "OK", null, null)))
        assertEquals(listOf("/answers/~1~1" to ViolationCode.NOT_IN_ENUM), v(ok, depth, ChecklistAnswer("//", "OK", null, null)))
        assertEquals(listOf("/answers/BRAKES" to ViolationCode.NOT_UNIQUE), v(ok, ok, depth))
        assertEquals(422, problem { ChecklistAnswerValidator.require(rules, listOf(ok), true) }.code.status)
    }

    @Test
    fun gleichzeitige_freigaben_genau_eine_revision() {
        val w = fx.world(); val author = ctx(w.tenant, "author-" + UUID.randomUUID())
        val d = fx.fleet.checklistTemplates.create(author, ChecklistTemplateInput.parse(body(allKinds)))
        val n = 4; val pool = Executors.newFixedThreadPool(n); val start = CountDownLatch(1)
        try {
            val results = (1..n).map { i ->
                pool.submit<Any> {
                    start.await()
                    try { fx.fleet.checklistTemplates.publish(ctx(w.tenant, "reviewer-$i-" + UUID.randomUUID()), d.id, d.revision) } catch (e: ProblemException) { e.code }
                }
            }
            start.countDown()
            val out = results.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, out.count { it is de.taktovia.fleetsafety.api.ChecklistTemplateView }, out.toString())
            assertTrue(out.filterIsInstance<ErrorCode>().all { it == ErrorCode.IMMUTABLE_RECORD || it == ErrorCode.CONFLICT }, out.toString())
        } finally { pool.shutdownNow() }
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.checklist_template_revision WHERE tenant_id=? AND template_id=? AND state='PUBLISHED'", w.tenant.value, d.templateId))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.checklist_template.published' AND object_ref=?", w.tenant.value, "checklist_template:${d.id}"))
    }

    @Test
    fun freigabe_parallel_zu_aenderung_des_entwurfs_ergibt_einen_konsistenten_stand() {
        val w = fx.world(); val author = ctx(w.tenant, "author-" + UUID.randomUUID()); val reviewer = ctx(w.tenant, "reviewer-" + UUID.randomUUID())
        val d = fx.fleet.checklistTemplates.create(author, ChecklistTemplateInput.parse(body(allKinds)))
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        var pubResult: Any? = null
        try {
            val pub = pool.submit<Any> { start.await(); try { fx.fleet.checklistTemplates.publish(reviewer, d.id, d.revision) } catch (e: ProblemException) { e.code } }
            val upd = pool.submit<Any> { start.await(); try { fx.fleet.checklistTemplates.updateDraft(author, d.id, d.revision, ChecklistTemplateInput.parse(body(listOf(step("ONLY_ONE"))))) } catch (e: ProblemException) { e.code } }
            start.countDown(); pubResult = pub.get(30, TimeUnit.SECONDS); upd.get(30, TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
        val final = fx.fleet.checklistTemplates.get(author, d.id)
        // entweder wurde der geänderte Stand freigegeben (1 Element) oder der ursprüngliche (3 Elemente); nie ein Mischstand, und nach der Freigabe ist nichts mehr änderbar
        // Gewinnt die Änderung zuerst, ist die Revision veraltet: die Freigabe wird mit REVISION_MISMATCH abgewiesen und der geänderte Entwurf bleibt Entwurf.
        if (final.state == ChecklistTemplateState.DRAFT) { assertEquals(ErrorCode.REVISION_MISMATCH, pubResult); assertEquals(listOf("ONLY_ONE"), final.steps.map { it.code }) }
        else assertEquals(ChecklistTemplateState.PUBLISHED, final.state)
        assertTrue(final.steps.map { it.code } in listOf(listOf("ONLY_ONE"), listOf("BRAKES", "TYRE_DEPTH", "REMARK")), final.steps.toString())
        assertEquals(final.steps.size, fx.count("SELECT count(*) FROM taktovia.checklist_step WHERE tenant_id=? AND template_revision_id=?", w.tenant.value, d.id))
    }

    @Test
    fun freigabe_durch_den_ersteller_wird_abgewiesen_und_freigegebene_revision_ist_unveraenderlich() {
        val w = fx.world(); val author = ctx(w.tenant, "author-" + UUID.randomUUID()); val reviewer = ctx(w.tenant, "reviewer-" + UUID.randomUUID())
        val d = fx.fleet.checklistTemplates.create(author, ChecklistTemplateInput.parse(body(allKinds)))
        assertEquals(ErrorCode.FORBIDDEN, problem { fx.fleet.checklistTemplates.publish(author, d.id, d.revision) }.code)
        assertEquals(ChecklistTemplateState.DRAFT, fx.fleet.checklistTemplates.get(author, d.id).state)
        val svc = RequestContext(Actor(ActorType.SERVICE, "https://idp.test/realms/fleet", "svc", "sid", true), w.tenant, emptySet(), setOf("inspections:write"), emptySet(), UUID.randomUUID())
        assertEquals(ErrorCode.FORBIDDEN, problem { fx.fleet.checklistTemplates.publish(svc, d.id, d.revision) }.code)   // Dienstkonto nie zweite Person
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { fx.fleet.checklistTemplates.publish(reviewer, d.id, 99) }.code)
        val p = fx.fleet.checklistTemplates.publish(reviewer, d.id, d.revision)
        // Änderung der freigegebenen Revision abgewiesen; auch an der Anwendung vorbei (Datenbank)
        assertEquals(ErrorCode.IMMUTABLE_RECORD, problem { fx.fleet.checklistTemplates.updateDraft(author, d.id, p.revision, ChecklistTemplateInput.parse(body())) }.code)
        assertThrows(Exception::class.java) { fx.exec("UPDATE taktovia.checklist_step SET title='X' WHERE template_revision_id=?", d.id) }
        // Änderung = neue Revision: Version 2 als Entwurf, nur ein Entwurf je Vorlage
        val n = fx.fleet.checklistTemplates.newRevision(author, d.id, p.revision)
        assertEquals(2, n.version); assertEquals(d.templateId, n.templateId); assertEquals(ChecklistTemplateState.DRAFT, n.state); assertEquals(d.steps.map { it.code }, n.steps.map { it.code })
        assertEquals(ErrorCode.CONFLICT, problem { fx.fleet.checklistTemplates.newRevision(author, d.id, p.revision) }.code)
        val u = fx.fleet.checklistTemplates.updateDraft(author, n.id, n.revision, ChecklistTemplateInput.parse(body(listOf(step("NEU")))))
        assertEquals(listOf("NEU"), u.steps.map { it.code })
        assertEquals(listOf("BRAKES", "TYRE_DEPTH", "REMARK"), fx.fleet.checklistTemplates.get(author, d.id).steps.map { it.code })   // alte Revision unberührt
        val p2 = fx.fleet.checklistTemplates.publish(reviewer, n.id, u.revision)
        assertEquals(ChecklistTemplateState.RETIRED, fx.fleet.checklistTemplates.get(author, d.id).state)   // abgelöst
        assertEquals(ChecklistTemplateState.PUBLISHED, p2.state)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.checklist_template_revision WHERE tenant_id=? AND template_id=? AND state='PUBLISHED'", w.tenant.value, d.templateId))
    }

    @Test
    fun beispielbild_nur_ueber_den_objektspeicher_des_eigenen_mandanten() {
        val a = fx.world(); val b = fx.world(); val author = ctx(a.tenant, "author-" + UUID.randomUUID())
        val d = fx.fleet.checklistTemplates.create(author, ChecklistTemplateInput.parse(body(allKinds)))
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
        val withImage = fx.fleet.checklistTemplates.putExampleImage(author, d.id, "BRAKES", d.revision, png, "image/png")
        val name = withImage.steps.first { it.code == "BRAKES" }.exampleImageName!!
        assertTrue(name.startsWith("checklist-examples/${d.id}/BRAKES-") && name.endsWith(".png"), name)
        // gespeichert unter dem Mandantenpräfix; ausgeliefert nur an den eigenen Mandanten
        assertEquals(listOf(name), fx.store.list(a.tenant, "checklist-examples/"))
        assertEquals(emptyList<String>(), fx.store.list(b.tenant, "checklist-examples/"))
        assertEquals(png.toList(), fx.fleet.checklistTemplates.exampleImage(author, d.id, "BRAKES").bytes.toList())
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.checklistTemplates.exampleImage(ctx(b.tenant, "other-" + UUID.randomUUID()), d.id, "BRAKES") }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.checklistTemplates.get(ctx(b.tenant, "other-" + UUID.randomUUID()), d.id) }.code)   // Vorlage eines anderen Mandanten: 404
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.checklistTemplates.exampleImage(author, d.id, "REMARK") }.code)   // kein Bild gesetzt
        // ungültige Bilder
        assertEquals(ErrorCode.UNSUPPORTED_MEDIA_TYPE, problem { fx.fleet.checklistTemplates.putExampleImage(author, d.id, "BRAKES", withImage.revision, png, "image/svg+xml") }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { fx.fleet.checklistTemplates.putExampleImage(author, d.id, "BRAKES", withImage.revision, "kein bild".toByteArray(), "image/png") }.code)
        // Das Bild bleibt beim Bearbeiten des Entwurfs erhalten und wird in die neue Revision kopiert
        val upd = fx.fleet.checklistTemplates.updateDraft(author, d.id, withImage.revision, ChecklistTemplateInput.parse(body(allKinds)))
        assertEquals(name, upd.steps.first { it.code == "BRAKES" }.exampleImageName)
        val p = fx.fleet.checklistTemplates.publish(ctx(a.tenant, "reviewer-" + UUID.randomUUID()), d.id, upd.revision)
        assertEquals(ErrorCode.IMMUTABLE_RECORD, problem { fx.fleet.checklistTemplates.putExampleImage(author, d.id, "BRAKES", p.revision, png, "image/png") }.code)
        val n = fx.fleet.checklistTemplates.newRevision(author, p.id, p.revision)
        val copy = n.steps.first { it.code == "BRAKES" }.exampleImageName!!
        assertTrue(copy != name && copy.contains("/${n.id}/"), copy)
        assertEquals(png.toList(), fx.fleet.checklistTemplates.exampleImage(author, n.id, "BRAKES").bytes.toList())
    }

    @Test
    fun liste_filtert_nach_zustand_klasse_und_bedienart_und_trennt_mandanten() {
        val a = fx.world(); val b = fx.world(); val author = ctx(a.tenant, "author-" + UUID.randomUUID()); val reviewer = ctx(a.tenant, "reviewer-" + UUID.randomUUID())
        val pkw = fx.fleet.checklistTemplates.create(author, ChecklistTemplateInput.parse(body(allKinds, cls = "PKW")))
        fx.fleet.checklistTemplates.create(author, ChecklistTemplateInput.parse(body(allKinds, cls = "BUS")))
        fx.fleet.checklistTemplates.publish(reviewer, pkw.id, pkw.revision)
        val published = fx.fleet.checklistTemplates.list(author, ChecklistTemplateState.PUBLISHED, null, null, 10, null)
        assertEquals(listOf(pkw.id), published.map { it.id })
        assertEquals(1, fx.fleet.checklistTemplates.list(author, null, "BUS", "ONDEMAND", 10, null).size)
        assertEquals(2, fx.fleet.checklistTemplates.list(author, null, null, null, 10, null).size)
        assertEquals(emptyList<Any>(), fx.fleet.checklistTemplates.list(ctx(b.tenant, "other-" + UUID.randomUUID()), null, null, null, 10, null))
    }
}
