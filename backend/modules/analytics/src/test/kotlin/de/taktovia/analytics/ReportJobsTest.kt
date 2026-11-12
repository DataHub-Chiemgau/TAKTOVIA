package de.taktovia.analytics

import de.taktovia.analytics.api.CreateExport
import de.taktovia.analytics.api.ExportCategories
import de.taktovia.analytics.api.ExportKind
import de.taktovia.analytics.api.ExportScope
import de.taktovia.analytics.api.ExportState
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * WP-M5-19 (AT-25, AT-53, AT-61) gegen die echte Datenbank unter der Runtime-Rolle: berechtigter Export mit Nachweisen, Empfängerrechte, Idempotenz, Mandantentrennung, kurzlebiger Abruflink, Datenstand
 * (As-of), Kategorien (fehlend wird ausgewiesen) und Vertraulichkeit der Schadenakte. Der Inhalt eines Exports wird aus `export_job.result` gelesen; die Freigabecheckliste des PBefG-Exports ist eine
 * Arbeitsannahme (OP-13).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportJobsTest {
    private lateinit var fx: ExportFixture
    @BeforeAll fun up() { fx = ExportFixture() }
    @AfterAll fun down() = fx.close()

    private val all = setOf("reports:write", "reports:read", "inspections:read", "defects:read", "defects:history", "time-events:manage", "rosters:audit", "fleet:read")
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun create(ctx: de.taktovia.platform.context.RequestContext, kind: ExportKind, scope: ExportScope = ExportScope(), key: String = UUID.randomUUID().toString()) = fx.api.reportJobs.create(ctx, CreateExport(kind, scope), key)
    private fun run(w: ExportFixture.World) = fx.api.reportJobs.runPending(w.tenant)
    private fun result(job: UUID, path: String) = fx.strings("SELECT result #>> CAST(? AS text[]) FROM taktovia.export_job WHERE id = ?", path, job).single()

    @Test
    fun berechtigter_export_pruefnachweis_mit_hash_revision_zeitstempel_und_audit() {
        val w = fx.world(); val run = fx.inspection(w, answers = 3); val ctx = fx.ctx(w.tenant, all)
        val created = create(ctx, ExportKind.INSPECTION_PROOF, ExportScope(vehicleId = w.vehicle))
        assertEquals(ExportState.QUEUED, created.job.state); assertFalse(created.replayed); assertNull(created.job.downloadToken)
        assertEquals(1, run(w))
        val job = fx.api.reportJobs.get(ctx, created.job.id)
        assertEquals(ExportState.SUCCEEDED, job.state); assertEquals(listOf(ExportCategories.INSPECTION_PROOF to "PRESENT"), job.categories!!.map { it.code to it.status }); assertEquals(1, job.categories!!.single().rows)
        val file = fx.api.reportJobs.download(ctx, job.id, job.downloadToken!!)
        assertEquals(sha(file.content), file.sha256, "Hash des Exports stimmt mit dem Inhalt überein"); assertEquals(job.resultSha256, file.sha256); assertEquals(file.content.toByteArray().size, job.resultBytes)
        assertTrue(file.content.contains(run.toString()) && file.content.contains("\"proof_sha256\"") && file.content.contains("\"revision\"") && file.content.contains("\"completed_at\""))
        assertEquals(3, result(job.id, "{data,inspection_proof,0,answers}").let { fx.count("SELECT jsonb_array_length(result #> '{data,inspection_proof,0,answers}') FROM taktovia.export_job WHERE id = ?", job.id) })
        // Audit: wer, wann, welcher Umfang, ohne Inhalt
        val audits = fx.strings("SELECT action || '|' || coalesce(redacted_changes::text, '') FROM taktovia.audit_event WHERE tenant_id = ? AND object_ref = ? ORDER BY occurred_at", w.tenant.value, "export_job:${job.id}")
        assertEquals(listOf("analytics.export.requested", "analytics.export.downloaded"), audits.map { it.substringBefore('|') })
        assertTrue(audits.none { it.contains(run.toString()) || it.contains("proof_sha256") }, "kein Exportinhalt im Audit"); assertTrue(audits[0].contains("vehicle_id"))
    }

    @Test
    fun export_ohne_recht_wird_verweigert() {
        val w = fx.world()
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { create(fx.ctx(w.tenant, setOf("inspections:read")), ExportKind.INSPECTION_PROOF) }.code, "ohne reports:write")
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { create(fx.ctx(w.tenant, setOf("reports:write")), ExportKind.INSPECTION_PROOF) }.code, "ohne Fachrecht der Art")
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { create(fx.ctx(w.tenant, setOf("reports:write", "inspections:read")), ExportKind.TIME_PROOF, ExportScope(from = Instant.parse("2026-10-01T00:00:00Z"), to = Instant.parse("2026-10-02T00:00:00Z"))) }.code, "HR-nahe Zeitdaten ohne Feldrecht")
        val ctx = fx.ctx(w.tenant, all); val job = create(ctx, ExportKind.INSPECTION_PROOF).job
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { fx.api.reportJobs.get(fx.ctx(w.tenant, setOf("reports:write")), job.id) }.code, "Lesen braucht reports:read")
    }

    @Test
    fun fremde_objekt_id_und_manipulierter_mandant_im_umfang_werden_abgewiesen() {
        val a = fx.world(); val b = fx.world(); val ctx = fx.ctx(a.tenant, all)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { create(ctx, ExportKind.INSPECTION_PROOF, ExportScope(vehicleId = b.vehicle)) }.code, "Fahrzeug eines fremden Mandanten")
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { create(ctx, ExportKind.ROSTER_ARCHIVE, ExportScope(depotId = b.depot)) }.code, "Depot eines fremden Mandanten")
        val (scope, violations) = ExportScope.parse(ExportKind.INSPECTION_PROOF, mapOf("vehicle_id" to a.vehicle.toString(), "tenant_id" to b.tenant.toString()))
        assertNull(scope); assertEquals(listOf("/scope/tenant_id" to ViolationCode.UNKNOWN_FIELD), violations.map { it.pointer to it.code })
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.export_job WHERE tenant_id = ?", a.tenant.value), "nichts angelegt")
    }

    @Test
    fun gleicher_idempotenzschluessel_liefert_denselben_auftrag_anderer_umfang_ist_konflikt_auch_parallel() {
        val w = fx.world(); val ctx = fx.ctx(w.tenant, all)
        val first = create(ctx, ExportKind.DEFECT_CASE, ExportScope(vehicleId = w.vehicle), "key-1")
        val again = create(ctx, ExportKind.DEFECT_CASE, ExportScope(vehicleId = w.vehicle), "key-1")
        assertEquals(first.job.id, again.job.id); assertTrue(again.replayed)
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, assertThrows<ProblemException> { create(ctx, ExportKind.DEFECT_CASE, ExportScope(), "key-1") }.code, "anderer Exportumfang")
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, assertThrows<ProblemException> { create(ctx, ExportKind.INSPECTION_PROOF, ExportScope(vehicleId = w.vehicle), "key-1") }.code, "andere Art")
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.export_job WHERE tenant_id = ?", w.tenant.value))
        val pool = Executors.newFixedThreadPool(6)
        try {
            val ids = pool.invokeAll(List(6) { Callable { create(ctx, ExportKind.TIME_PROOF, ExportScope(from = Instant.parse("2026-10-01T00:00:00Z"), to = Instant.parse("2026-10-02T00:00:00Z")), "parallel").job.id } }).map { it.get() }
            assertEquals(1, ids.toSet().size, "parallele Wiederholungen ergeben genau einen Auftrag"); assertEquals(2, fx.count("SELECT count(*) FROM taktovia.export_job WHERE tenant_id = ?", w.tenant.value))
        } finally { pool.shutdownNow() }
    }

    @Test
    fun abruflink_ist_kurzlebig_auftragsgebunden_und_nur_fuer_den_auftraggeber() {
        val w = fx.world(); val ctx = fx.ctx(w.tenant, all); fx.inspection(w)
        val job = create(ctx, ExportKind.INSPECTION_PROOF).job; run(w)
        val ready = fx.api.reportJobs.get(ctx, job.id); val token = ready.downloadToken!!
        assertTrue(ready.downloadExpiresAt!!.isAfter(fx.clock.instant()) && ready.downloadExpiresAt!!.isBefore(fx.clock.instant().plusSeconds(600)), "kurzlebig")
        // anderer Auftraggeber im selben Mandanten, anderer Mandant: 404, kein Hinweis
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.api.reportJobs.download(fx.ctx(w.tenant, all), job.id, token) }.code)
        val other = fx.world()
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.api.reportJobs.download(fx.ctx(other.tenant, all, subject = ctx.actor.subject), job.id, token) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.api.reportJobs.get(fx.ctx(other.tenant, all), job.id) }.code)
        // manipulierter Link
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.api.reportJobs.download(ctx, job.id, token.dropLast(2) + "xx") }.code)
        // nach Ablauf
        fx.clock.now = fx.clock.now.plusSeconds(301)
        try {
            assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.api.reportJobs.download(ctx, job.id, token) }.code, "abgelaufen")
        } finally { fx.clock.now = fx.clock.now.minusSeconds(301) }
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'analytics.export.downloaded'", w.tenant.value), "kein Abruf ohne gültigen Link")
        assertTrue(fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'security.export.download_denied'", w.tenant.value) >= 3, "verweigerte Abrufe stehen im Sicherheitsprotokoll")
        // der gültige Link funktioniert weiterhin für den Auftraggeber
        assertNotNull(fx.api.reportJobs.download(ctx, job.id, token).content)
    }

    @Test
    fun zwei_mandanten_parallel_exportieren_ohne_vermischung() {
        val a = fx.world(); val b = fx.world(); val ra = fx.inspection(a); val rb = fx.inspection(b)
        val ja = create(fx.ctx(a.tenant, all), ExportKind.INSPECTION_PROOF).job.id; val jb = create(fx.ctx(b.tenant, all), ExportKind.INSPECTION_PROOF).job.id
        val pool = Executors.newFixedThreadPool(2)
        try { pool.invokeAll(listOf(Callable { run(a) }, Callable { run(b) })).forEach { it.get() } } finally { pool.shutdownNow() }
        val ca = fx.strings("SELECT result::text FROM taktovia.export_job WHERE id = ?", ja).single(); val cb = fx.strings("SELECT result::text FROM taktovia.export_job WHERE id = ?", jb).single()
        assertTrue(ca.contains(ra.toString()) && !ca.contains(rb.toString()) && !ca.contains(b.vehicle.toString()))
        assertTrue(cb.contains(rb.toString()) && !cb.contains(ra.toString()) && !cb.contains(a.vehicle.toString()))
    }

    @Test
    fun datenstand_as_of_spaetere_daten_fehlen_und_alter_plan_bleibt_unveraendert_lesbar() {
        val w = fx.world(); val ctx = fx.ctx(w.tenant, all); fx.inspection(w); val roster = fx.rosters(w)
        val j1 = create(ctx, ExportKind.INSPECTION_PROOF).job.id
        val j2 = create(ctx, ExportKind.ROSTER_ARCHIVE, ExportScope(depotId = w.depot)).job.id
        fx.clock.now = fx.clock.now.plusSeconds(60)
        val late = fx.inspection(w, at = "2026-11-01T05:00:00Z")       // Entstehungszeit nach dem Datenstand des Auftrags
        run(w)
        assertFalse(result(j1, "{}").contains(late.toString()), "Prüfung nach as_of fehlt im Export")
        assertEquals(1, fx.count("SELECT jsonb_array_length(result #> '{data,inspection_proof}') FROM taktovia.export_job WHERE id = ?", j1))
        // Dienstplan: alte, ersetzte Revision 1 und die gültige Revision 2 sind lesbar; die Revision 1 ist unverändert gegenüber einem späteren Export
        assertEquals(2, fx.count("SELECT jsonb_array_length(result #> '{data,roster_archive}') FROM taktovia.export_job WHERE id = ?", j2))
        assertEquals(listOf("SUPERSEDED", "PUBLISHED"), fx.strings("SELECT e ->> 'state' FROM taktovia.export_job j, jsonb_array_elements(j.result #> '{data,roster_archive}') e WHERE j.id = ? ORDER BY (e ->> 'revision')::int", j2))
        val j3 = create(ctx, ExportKind.ROSTER_ARCHIVE, ExportScope(depotId = w.depot)).job.id; run(w)
        assertEquals(fx.strings("SELECT (e - 'state')::text FROM taktovia.export_job j, jsonb_array_elements(j.result #> '{data,roster_archive}') e WHERE j.id = ? AND (e ->> 'revision') = '1'", j2),
            fx.strings("SELECT (e - 'state')::text FROM taktovia.export_job j, jsonb_array_elements(j.result #> '{data,roster_archive}') e WHERE j.id = ? AND (e ->> 'revision') = '1'", j3), "alter veröffentlichter Plan unverändert lesbar")
        assertNotNull(roster)
    }

    @Test
    fun pbefg_export_weist_fehlende_und_nicht_berechtigte_kategorien_aus() {
        val w = fx.world(); fx.inspection(w); fx.defect(w); fx.rosters(w)
        val scopes = all - "time-events:manage"
        val ctx = fx.ctx(w.tenant, scopes); val job = create(ctx, ExportKind.PBEFG_DATA).job; run(w)
        val done = fx.api.reportJobs.get(ctx, job.id)
        val byCat = done.categories!!.associate { it.code to it.status }
        assertEquals(ExportCategories.DEFAULT_PBEFG_CHECKLIST, done.categories!!.map { it.code }, "alle Kategorien der Checkliste sind im Ergebnis, keine stillschweigend weggelassen")
        assertEquals(ExportCategories.PRESENT, byCat[ExportCategories.INSPECTION_PROOF]); assertEquals(ExportCategories.PRESENT, byCat[ExportCategories.DEFECT_CASE]); assertEquals(ExportCategories.PRESENT, byCat[ExportCategories.ROSTER_ARCHIVE])
        assertEquals(ExportCategories.NOT_AUTHORIZED, byCat[ExportCategories.TIME_PROOF], "Zeitnachweis ohne Feldrecht"); assertEquals(ExportCategories.MISSING, byCat[ExportCategories.OPERATING_PERMIT], "ohne Quelle: fehlt, wird ausgewiesen")
        assertEquals(setOf("vehicle_register", "inspection_proof", "defect_case", "roster_archive"), fx.strings("SELECT jsonb_object_keys(result -> 'data') FROM taktovia.export_job WHERE id = ?", job.id).toSet(), "keine Daten außerhalb der Berechtigung")
        assertEquals(1, fx.count("SELECT jsonb_array_length(result #> '{data,vehicle_register}') FROM taktovia.export_job WHERE id = ?", job.id))
    }

    @Test
    fun vertrauliche_teile_der_schadenakte_und_hr_zeitdaten_nur_mit_feldrecht() {
        val w = fx.world(); fx.defect(w, confidentialEvidence = true); fx.timeEvent(w, "user:p1", "2026-10-02T05:00:00Z")
        val plain = fx.ctx(w.tenant, setOf("reports:write", "reports:read", "defects:read"), roles = setOf("dispatcher"))
        val privileged = fx.ctx(w.tenant, setOf("reports:write", "reports:read", "defects:read", "defects:history"), roles = setOf("workshop"))
        val j1 = create(plain, ExportKind.DEFECT_CASE).job.id; val j2 = create(privileged, ExportKind.DEFECT_CASE).job.id; run(w)
        assertEquals(1, fx.count("SELECT jsonb_array_length(result #> '{data,defect_case,0,evidence}') FROM taktovia.export_job WHERE id = ?", j1)); assertEquals(1, fx.count("SELECT (result #>> '{data,defect_case,0,confidential_evidence_withheld}')::int FROM taktovia.export_job WHERE id = ?", j1))
        assertEquals("false", result(j1, "{data,defect_case,0,history_authorized}"))
        assertEquals(2, fx.count("SELECT jsonb_array_length(result #> '{data,defect_case,0,evidence}') FROM taktovia.export_job WHERE id = ?", j2)); assertEquals("true", result(j2, "{data,defect_case,0,history_authorized}"))
        val hr = fx.ctx(w.tenant, setOf("reports:write", "reports:read", "time-events:manage"), roles = setOf("staff_planning"))
        val jt = create(hr, ExportKind.TIME_PROOF, ExportScope(from = Instant.parse("2026-10-01T00:00:00Z"), to = Instant.parse("2026-10-03T00:00:00Z"))).job.id; run(w)
        assertEquals(1, fx.count("SELECT jsonb_array_length(result #> '{data,time_proof}') FROM taktovia.export_job WHERE id = ?", jt)); assertEquals("user:p1", result(jt, "{data,time_proof,0,person_ref}"))
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { create(plain, ExportKind.TIME_PROOF, ExportScope(from = Instant.parse("2026-10-01T00:00:00Z"), to = Instant.parse("2026-10-03T00:00:00Z"))) }.code)
    }

    @Test
    fun beendeter_auftrag_ist_unveraenderlich_und_nicht_loeschbar() {
        val w = fx.world(); val ctx = fx.ctx(w.tenant, all); fx.inspection(w)
        val job = create(ctx, ExportKind.INSPECTION_PROOF).job; run(w)
        assertTrue(runCatching { fx.exec("UPDATE taktovia.export_job SET result = '{}'::jsonb WHERE id = ?", job.id) }.isFailure, "Ergebnis eines beendeten Auftrags ändert sich nie")
        assertTrue(runCatching { fx.exec("UPDATE taktovia.export_job SET as_of = now() WHERE id = ?", job.id) }.isFailure)
        assertTrue(runCatching { fx.exec("DELETE FROM taktovia.export_job WHERE id = ?", job.id) }.isFailure, "Nachweis wird nicht gelöscht")
        assertEquals(0, run(w), "nichts mehr zu tun")
    }
}
