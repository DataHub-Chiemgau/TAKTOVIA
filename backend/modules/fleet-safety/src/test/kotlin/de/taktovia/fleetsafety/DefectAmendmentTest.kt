package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.DefectAmendment
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-48 / LH-6.22-DMG-008: unveränderliche Historie von Meldungen und Anhängen unter der Runtime-Rolle. Originalabnahme: vorherige Inhalte bleiben für berechtigte Prüfer lesbar; Nachträge
 * tragen Autor, Zeit, Änderungsgrund und Version. Positiv: zwei Nachträge, Version 1 bleibt lesbar, ersetzter Anhang bleibt erhalten, Stand zum Zeitpunkt t. Negativ: ohne Grund, ohne Prüferrolle,
 * fremder Mandant, direktes UPDATE/DELETE (auch `db/tests/test_defect_history.py`). Konkurrierend: gleichzeitige Nachträge, fortlaufende Versionen ohne Verlust.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefectAmendmentTest {
    private lateinit var fx: FleetFixture
    private lateinit var h: EvidenceHarness

    @BeforeAll fun up() { fx = FleetFixture(); h = EvidenceHarness(fx) }
    @AfterAll fun down() = fx.close()

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)
    private fun amend(ctx: de.taktovia.platform.context.RequestContext, id: UUID, vararg fields: Pair<String, Any?>, reason: String? = "Korrektur") =
        h.fleet.defects.amend(ctx, id, DefectAmendment.parse(buildMap { if (reason != null) put("change_reason", reason); putAll(fields) }))
    private fun report(w: DefectWorld, ctx: de.taktovia.platform.context.RequestContext = w.ctx("dispatcher")) = h.fleet.defects.report(ctx, ReportDefect.parse(w.raw())).defect
    private fun pause() = Thread.sleep(30)

    private fun releasedPhoto(w: DefectWorld, ctx: de.taktovia.platform.context.RequestContext, seed: Int): UUID {
        val b = h.jpeg(seed); val t = h.ticket(ctx, b); h.putQuarantine(w, t, b); h.complete(ctx, t); return t.id
    }

    @Test
    fun nachtrag_ohne_grund_wird_abgewiesen() {
        val w = DefectWorld(fx); val ctx = w.ctx("dispatcher"); val d = report(w, ctx)
        assertEquals(listOf("/change_reason"), problem { DefectAmendment.parse(mapOf("description" to "neu")) }.violations.map { it.pointer })
        assertEquals(listOf("/change_reason"), problem { DefectAmendment.parse(mapOf("change_reason" to "   ", "description" to "neu")) }.violations.map { it.pointer })
        // ohne jede Änderung kein Nachtrag; unbekannte Felder (Mandant, Meldender, Status) nie
        assertEquals(listOf("/"), problem { amend(ctx, d.id) }.violations.map { it.pointer })
        assertEquals(listOf("/"), problem { amend(ctx, d.id, "description" to d.description) }.violations.map { it.pointer }, "unveränderter Wert ist keine Änderung")
        for (k in listOf("tenant_id", "reported_by", "status", "case_number", "reported_at", "version")) {
            val p = problem { DefectAmendment.parse(mapOf("change_reason" to "x", k to "y")) }
            assertEquals(ViolationCode.UNKNOWN_FIELD, p.violations.single().code, k)
        }
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.defect_case_revision WHERE defect_id=?", d.id), "nur Version 1")
        assertEquals(1, h.fleet.defects.get(ctx, d.id).contentVersion)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { amend(ctx, d.id, "occurred_at" to Instant.now().plusSeconds(7200).toString()) }.code)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.defect_case_revision WHERE defect_id=?", d.id))
    }

    @Test
    fun version_eins_bleibt_fuer_pruefer_lesbar() {
        val w = DefectWorld(fx); val ctx = w.ctx("dispatcher"); val reviewer = w.ctx("workshop")
        val d = report(w, ctx); val t1 = Instant.now(); pause()
        val v2 = amend(ctx, d.id, "description" to "Kratzer an der Tür hinten links", reason = "Beschreibung präzisiert"); val t2 = Instant.now(); pause()
        val v3 = amend(reviewer, d.id, "category_code" to "GLASS", "kind" to "CRACK_BREAK", reason = "Fehlzuordnung korrigiert")
        assertEquals(2, v2.contentVersion); assertEquals(3, v3.contentVersion); assertEquals("GLASS", v3.categoryCode); assertEquals("Kratzer an der Tür hinten links", v3.description)
        assertEquals(d.revision + 2, v3.revision)
        // aktuelle Akte zeigt nur die letzte Fassung; Prüfer sehen alle Fassungen, die erste unverändert
        val history = h.fleet.defects.history(reviewer, d.id)
        assertEquals(listOf(1, 2, 3), history.map { it.version })
        assertEquals("Kratzer an der Beifahrertür", history[0].content["description"]); assertEquals("BODY", history[0].content["category_code"])
        assertEquals("Kratzer an der Tür hinten links", history[1].content["description"]); assertEquals("BODY", history[1].content["category_code"])
        assertEquals("GLASS", history[2].content["category_code"])
        assertEquals(listOf("Erstmeldung", "Beschreibung präzisiert", "Fehlzuordnung korrigiert"), history.map { it.changeReason })
        assertEquals(listOf("user:${ctx.actor.subject}", "user:${ctx.actor.subject}", "user:${reviewer.actor.subject}"), history.map { it.author })
        assertTrue(history.all { it.createdAt.isAfter(Instant.now().minusSeconds(300)) }); assertTrue(history[0].createdAt.isBefore(history[1].createdAt))
        // Stand zum Zeitpunkt t
        assertEquals("Kratzer an der Beifahrertür", h.fleet.defects.stateAt(reviewer, d.id, t1).content["description"])
        assertEquals(1, h.fleet.defects.stateAt(reviewer, d.id, t1).version)
        assertEquals("Kratzer an der Tür hinten links", h.fleet.defects.stateAt(reviewer, d.id, t2).content["description"])
        assertEquals(3, h.fleet.defects.stateAt(reviewer, d.id, Instant.now().plusSeconds(1)).version)
        assertEquals("NEW", h.fleet.defects.stateAt(reviewer, d.id, t2).status)
        assertEquals(ErrorCode.NOT_FOUND, problem { h.fleet.defects.stateAt(reviewer, d.id, d.reportedAt.minusSeconds(60)) }.code)
        // Nachtrag und Lesezugriff erzeugen Audit und Ereignis (nie mit Beschreibungstext)
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.defect.amended' AND object_ref=?", w.tenant.value, "defect:${d.id}"))
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.outbox WHERE aggregate_id=? AND event_type='defect.amended'", d.id))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.outbox WHERE aggregate_id=? AND payload::text LIKE '%Tür%'", d.id))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE object_ref=? AND redacted_changes::text LIKE '%Tür%'", "defect:${d.id}"))
        assertTrue(fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.defect.history_read' AND object_ref=?", w.tenant.value, "defect:${d.id}") >= 4)
        assertEquals(listOf("description"), fx.strings("SELECT payload->'changed_fields'->>0 FROM taktovia.outbox WHERE aggregate_id=? AND event_type='defect.amended' AND aggregate_revision=?", d.id, d.revision + 1))
        // Statusänderungen ändern die Fassungen nicht: die Historie bleibt, die Version steigt nicht
        h.fleet.defects.transition(reviewer, d.id, v3.revision, de.taktovia.fleetsafety.api.DefectTransition(de.taktovia.fleetsafety.api.DefectState.REVIEWED, null, false))
        assertEquals(3, h.fleet.defects.get(ctx, d.id).contentVersion); assertEquals(3, h.fleet.defects.history(reviewer, d.id).size)
        assertEquals("REVIEWED", h.fleet.defects.stateAt(reviewer, d.id, Instant.now().plusSeconds(1)).status)
    }

    @Test
    fun gleichzeitige_nachtraege_fortlaufende_versionen() {
        val w = DefectWorld(fx); val ctx = w.ctx("dispatcher"); val d = report(w, ctx); val n = 6
        val pool = Executors.newFixedThreadPool(n); val start = CountDownLatch(1)
        val fields = listOf("description" to "Beschreibung B", "category_code" to "GLASS", "kind" to "CRACK_BREAK", "vehicle_area" to "ROOF", "observed_effect" to "FLUID_LEAK", "location_kind" to "DEPOT")
        val rs = (0 until n).map { i -> pool.submit<Result<Int>> { start.await(); runCatching {
            val extra = if (fields[i].first == "location_kind") arrayOf(fields[i], "location_value" to "Hof Nord") else arrayOf(fields[i])
            amend(ctx, d.id, *extra, reason = "Nachtrag $i").contentVersion } } }
        start.countDown()
        val res = rs.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertTrue(res.all { it.isSuccess }, res.toString())
        assertEquals((2..n + 1).toList(), res.map { it.getOrThrow() }.sorted(), "jede Version genau einmal")
        val versions = fx.strings("SELECT version::text FROM taktovia.defect_case_revision WHERE defect_id=? ORDER BY version", d.id)
        assertEquals((1..n + 1).map { it.toString() }, versions, "fortlaufend ohne Lücke und ohne Dublette")
        assertEquals(n, fx.count("SELECT count(DISTINCT change_reason) FROM taktovia.defect_case_revision WHERE defect_id=? AND version > 1", d.id))
        // kein verlorenes Update: alle sechs Änderungen stehen in der aktuellen Fassung
        val now = h.fleet.defects.get(ctx, d.id)
        assertEquals("Beschreibung B", now.description); assertEquals("GLASS", now.categoryCode); assertEquals("CRACK_BREAK", now.kind); assertEquals("ROOF", now.vehicleArea)
        assertEquals("FLUID_LEAK", now.observedEffect); assertEquals("DEPOT", now.locationKind); assertEquals(n + 1, now.contentVersion); assertEquals(d.revision + n, now.revision)
        // die letzte Fassung der Historie entspricht der aktuellen Akte
        val last = h.fleet.defects.history(w.ctx("workshop"), d.id).last()
        assertEquals(now.description, last.content["description"]); assertEquals(now.vehicleArea, last.content["vehicle_area"])
    }

    @Test
    fun ersetzter_anhang_bleibt_als_fruehere_version() {
        val w = DefectWorld(fx); val ctx = w.ctx("dispatcher"); val reviewer = w.ctx("workshop")
        val e1 = releasedPhoto(w, ctx, 50)
        val d = h.report(ctx, w, e1).defect
        assertEquals(listOf(e1), d.evidence.map { it.id }); val t1 = Instant.now(); pause()
        val e2 = releasedPhoto(w, ctx, 51)
        val v2 = h.fleet.defects.amend(ctx, d.id, DefectAmendment.parse(mapOf("change_reason" to "Foto unscharf, neues Foto", "replace_evidence" to listOf(mapOf("evidence_id" to e1.toString(), "ticket_id" to e2.toString())))))
        assertEquals(2, v2.contentVersion)
        // aktuelle Akte: nur das neue Foto, mit Verweis auf den Vorgänger
        assertEquals(listOf(e2), v2.evidence.map { it.id }); assertEquals(e1, v2.evidence.single().replacesId); assertEquals(2, v2.evidence.single().addedInVersion)
        assertEquals(listOf(e2), h.fleet.defectEvidence.listForDefect(ctx, d.id).map { it.id })
        // der Vorgänger bleibt unverändert erhalten: Zeile, Objekt, Hash; für Prüfer in der Historie und per Link abrufbar, sonst nicht
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.defect_evidence WHERE defect_id=?", d.id))
        val ref1 = fx.strings("SELECT media_ref FROM taktovia.defect_evidence WHERE id=?", e1).single()
        assertNotNull(h.store.get(de.taktovia.platform.storage.ObjectKey(w.tenant, ref1)))
        val history = h.fleet.defects.history(reviewer, d.id)
        assertEquals(listOf(listOf(e1), listOf(e2)), history.map { v -> v.evidence.map { it.id } })
        assertEquals(e1, history[1].evidence.single().replacesId)
        assertEquals(ErrorCode.NOT_FOUND, problem { h.fleet.defectEvidence.downloadLink(ctx, d.id, e1) }.code)
        assertTrue(h.fleet.defectEvidence.openLinked(reviewer, h.fleet.defectEvidence.downloadLink(reviewer, d.id, e1).url).bytes.isNotEmpty())
        // Stand zum Zeitpunkt t: davor das alte Foto, danach das neue
        assertEquals(listOf(e1), h.fleet.defects.stateAt(reviewer, d.id, t1).evidence.map { it.id })
        assertEquals(listOf(e2), h.fleet.defects.stateAt(reviewer, d.id, Instant.now().plusSeconds(1)).evidence.map { it.id })
        // ein Anhang wird höchstens einmal ersetzt; Ersatz muss freigegeben sein; fremder Anhang nie
        val e3 = releasedPhoto(w, ctx, 52)
        assertEquals(listOf("/replace_evidence/0/evidence_id"), problem { h.fleet.defects.amend(ctx, d.id, DefectAmendment.parse(mapOf("change_reason" to "nochmal",
            "replace_evidence" to listOf(mapOf("evidence_id" to e1.toString(), "ticket_id" to e3.toString()))))) }.violations.map { it.pointer })
        h.inspector.available = false
        val b = h.jpeg(53); val open = h.ticket(ctx, b); h.putQuarantine(w, open, b); h.complete(ctx, open); h.inspector.available = true       // in Prüfung
        assertEquals(listOf("/replace_evidence/0"), problem { h.fleet.defects.amend(ctx, d.id, DefectAmendment.parse(mapOf("change_reason" to "x",
            "replace_evidence" to listOf(mapOf("evidence_id" to e2.toString(), "ticket_id" to open.id.toString()))))) }.violations.map { it.pointer })
        assertEquals(2, h.fleet.defects.get(ctx, d.id).contentVersion, "abgewiesene Nachträge ändern nichts")
        // zusätzlicher Anhang per Nachtrag (Version 3); in Prüfung bis zur Freigabe, danach mit der Version, in der er hinzukam
        val v3 = h.fleet.defects.amend(ctx, d.id, DefectAmendment.parse(mapOf("change_reason" to "weiteres Foto", "add_evidence_ids" to listOf(open.id.toString()))))
        assertEquals(3, v3.contentVersion); assertEquals(1, v3.evidencePending)
        assertEquals(1, h.fleet.defectEvidence.reviewPending(w.tenant))
        val v4 = h.fleet.defects.get(ctx, d.id)
        assertEquals(setOf(e2, open.id), v4.evidence.map { it.id }.toSet()); assertEquals(3, v4.evidence.single { it.id == open.id }.addedInVersion)
        assertEquals(3, h.fleet.defects.history(reviewer, d.id).size)
    }

    @Test
    fun historie_ohne_pruferrolle_abgewiesen_fremder_mandant_404() {
        val w = DefectWorld(fx); val ctx = w.ctx("dispatcher"); val d = report(w, ctx)
        amend(ctx, d.id, "description" to "neu")
        for (role in listOf("dispatcher", "driver", "passenger", "support", "callcenter")) {
            assertEquals(ErrorCode.FORBIDDEN, problem { h.fleet.defects.history(w.ctx(role), d.id) }.code, role)
            assertEquals(ErrorCode.FORBIDDEN, problem { h.fleet.defects.stateAt(w.ctx(role), d.id, Instant.now()) }.code, role)
        }
        for (role in listOf("workshop", "tenant_admin")) assertEquals(2, h.fleet.defects.history(w.ctx(role), d.id).size, role)
        // fremder Mandant: 404, auch für einen Prüfer
        val other = DefectWorld(fx)
        assertEquals(ErrorCode.NOT_FOUND, problem { h.fleet.defects.history(other.ctx("workshop"), d.id) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { h.fleet.defects.stateAt(other.ctx("workshop"), d.id, Instant.now()) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { amend(other.ctx("dispatcher"), d.id, "description" to "gekapert") }.code)
        // Nachtrag: Fahrgast und Support 403; Fahrer nur an eigener Akte
        assertEquals(ErrorCode.FORBIDDEN, problem { amend(w.ctx("passenger"), d.id, "description" to "x") }.code)
        assertEquals(ErrorCode.FORBIDDEN, problem { amend(w.ctx("support"), d.id, "description" to "x") }.code)
        val driver = UUID.randomUUID(); w.shift(driver)
        assertEquals(ErrorCode.NOT_FOUND, problem { amend(w.ctx("driver", subject = driver), d.id, "description" to "fremde Akte") }.code)
        val own = h.fleet.defects.report(w.ctx("driver", subject = driver), ReportDefect.parse(w.raw())).defect
        assertEquals(2, amend(w.ctx("driver", subject = driver), own.id, "description" to "eigene Ergänzung").contentVersion)
        assertEquals("Kratzer an der Beifahrertür", fx.strings("SELECT snapshot->>'description' FROM taktovia.defect_case_revision WHERE defect_id=? AND version=1", own.id).single())
        assertFalse(h.fleet.defects.get(ctx, d.id).description == "gekapert")
    }

    @Test
    fun direktes_update_und_delete_auf_meldung_revision_und_evidenz_scheitert() {
        val w = DefectWorld(fx); val ctx = w.ctx("dispatcher"); val e = releasedPhoto(w, ctx, 60); val d = h.report(ctx, w, e).defect
        amend(ctx, d.id, "description" to "Version zwei")
        // Administration: Trigger lehnen jede Änderung ab (SQLSTATE 23514 -> hier als Ausnahme der Testhilfe)
        for (sql in listOf("UPDATE taktovia.defect_case_revision SET change_reason='x' WHERE defect_id=?", "DELETE FROM taktovia.defect_case_revision WHERE defect_id=?",
            "UPDATE taktovia.defect_evidence SET media_ref='x' WHERE defect_id=?", "DELETE FROM taktovia.defect_evidence WHERE defect_id=?",
            "DELETE FROM taktovia.defect_case WHERE id=?", "UPDATE taktovia.defect_case SET description='ueberschrieben' WHERE id=?")) {
            val e = assertThrows(Exception::class.java, { fx.exec(sql, d.id) }, sql)
            assertEquals("23514", generateSequence<Throwable>(e) { it.cause }.filterIsInstance<java.sql.SQLException>().first().sqlState, "$sql: Trigger (check_violation), nicht ein anderer Fehler")
        }
        assertEquals("Version zwei", h.fleet.defects.get(ctx, d.id).description)
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.defect_case_revision WHERE defect_id=?", d.id)); assertEquals(1, fx.count("SELECT count(*) FROM taktovia.defect_evidence WHERE defect_id=?", d.id))
    }
}
