package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.AlertRule
import de.taktovia.fleetsafety.api.DefectClassification
import de.taktovia.fleetsafety.api.DefectSettings
import de.taktovia.fleetsafety.api.DefectSeverities
import de.taktovia.fleetsafety.api.DefectView
import de.taktovia.fleetsafety.api.DispatchVerdict
import de.taktovia.fleetsafety.api.ReleaseState
import de.taktovia.fleetsafety.api.TimelineKind
import de.taktovia.fleetsafety.api.TriageDefect
import de.taktovia.platform.alert.AlertPriority
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
import java.util.concurrent.atomic.AtomicReference

/**
 * WP-M3-45 / LH-6.22-DMG-005: Schadenklassifikation und Triage gegen die echte Datenbank unter der Runtime-Rolle. Originalabnahme: ein sicherheitskritischer Schaden sperrt die betroffene
 * Einsatzressource unmittelbar. Positiv: je Klasse ein Fall der Regelmatrix, Sperre im selben Commit (derselbe Transaktionsstempel `xmin` an Akte, Sperre und Fahrzeug), Korrektur mit Grund
 * behält die vorherige Einstufung in der Historie, Aktenhistorie mit Klassifikation, Sperre und Zuweisung (AT-34, P1-Anteil), Alarm nach wirksamer Alarmrichtlinie. Negativ: Triage ohne Rolle,
 * ohne Grund, mit veralteter Revision, fremder Mandant, Herabstufung hebt keine Sperre auf, Parameter lässt sich nicht gegen die Sperre stellen. Konkurrierend: Hochstufung gegen die
 * Dispositionsprüfung (keine Zusage auf das unsichere Fahrzeug) und gegen eine gleichzeitige Zuweisung des Zuständigen (genau ein Schreiber, 412 für den zweiten).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefectTriageTest {
    private lateinit var fx: FleetFixture
    private lateinit var w: TriageWorld

    @BeforeAll fun up() { fx = FleetFixture(); w = TriageWorld(fx) }
    @AfterAll fun down() = fx.close()

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)
    private fun triage(role: String, d: DefectView, severity: String, decision: String = "REVIEWED", reason: String? = null, assignee: UUID? = null, assigneeGiven: Boolean = assignee != null, rev: Long = d.revision) =
        fx.fleet.defects.triage(w.ctx(role), d.id, rev, TriageDefect(severity, decision, assigneeGiven, assignee, reason))

    @Test
    fun regelklassifikation_je_klasse_mit_regelversion() {
        data class Case(val category: String, val kind: String?, val area: String?, val effect: String?, val expected: String, val rule: String)
        val cases = listOf(
            Case("BRAKES", "MALFUNCTION", "WHEELS_TIRES", "NOT_ROADWORTHY", DefectSeverities.SAFETY_CRITICAL, "R01"),
            Case("TIRES_WHEELS", null, null, null, DefectSeverities.SAFETY_CRITICAL, "R03"),          // Standarderfassung ohne Auswirkung: vorsichtig
            Case("LIGHTS", "MALFUNCTION", "FRONT", "FUNCTION_LIMITED", DefectSeverities.OPERATIONALLY_RELEVANT, "R11"),
            Case("BODY", "SCRATCH", "LEFT_SIDE", "COSMETIC_ONLY", DefectSeverities.COSMETIC, "R20"),
            Case("OTHER", "OTHER", null, "OTHER", DefectSeverities.OTHER, "R90"),
        )
        for (c in cases) {
            val v = w.rw.vehicle()
            val d = w.report(w.ctx("dispatcher"), v, c.category, c.kind, c.area, c.effect)
            assertEquals(c.expected, d.severity, "${c.category}/${c.effect}")
            assertEquals(DefectClassification.VERSION, d.severityRuleVersion); assertEquals("RULE", d.severitySource)
            assertEquals(c.expected, DefectClassification.classify(c.category, c.kind, c.area, c.effect).severity); assertEquals(c.rule, DefectClassification.classify(c.category, c.kind, c.area, c.effect).ruleId)
            // genau eine Klasse und die Erstklassifikation steht mit Regelversion in der Historie
            val h = fx.strings("SELECT coalesce(from_severity, '-') || '>' || to_severity || '|' || rule_version || '|' || source FROM taktovia.defect_classification_history WHERE defect_id = ?", d.id)
            assertEquals(listOf("->${c.expected}|${DefectClassification.VERSION}|RULE"), h)
            assertEquals(c.expected == DefectSeverities.SAFETY_CRITICAL, w.activeBlocks(v).isNotEmpty(), "Sperre nur bei sicherheitskritisch (${c.category})")
        }
    }

    @Test
    fun sicherheitskritisch_sperrt_im_selben_commit() {
        val r = w.released()
        val d = w.report(w.ctx("dispatcher"), r.vehicle, "BODY", "SCRATCH", "LEFT_SIDE", "COSMETIC_ONLY")
        assertEquals(DefectSeverities.COSMETIC, d.severity)
        assertEquals(emptyList<String>(), w.activeBlocks(r.vehicle)); val before = w.safetyRevision(r.vehicle)
        assertTrue(fx.fleet.readiness.assertDispatchable(w.ctx("dispatcher"), r.vehicle, r.shift, before, Instant.now()) is DispatchVerdict.Dispatchable)

        val res = triage("dispatcher", d, DefectSeverities.SAFETY_CRITICAL, reason = "Riss im Rahmen festgestellt")
        assertEquals(DefectSeverities.SAFETY_CRITICAL, res.defect.severity); assertTrue(res.classificationChanged)
        assertEquals("TRIAGE", res.defect.severitySource); assertEquals("REVIEWED", res.defect.status)
        val block = res.block!!; assertTrue(block.created); assertEquals(before + 1, block.safetyRevision); assertEquals(1, block.revokedReleaseIds.size)
        assertEquals(listOf("SAFETY|DEFECT_CRITICAL|${d.caseNumber}"), w.activeBlocks(r.vehicle), "eigener Sperrgrund mit Quelle Schaden und Fallnummer als Bezug")
        assertTrue(w.activeBlockFlag(r.vehicle)); assertEquals(before + 1, w.safetyRevision(r.vehicle))
        assertEquals(ReleaseState.REVOKED, fx.fleet.readiness.release(w.ctx("dispatcher"), block.revokedReleaseIds.single()).state)
        assertTrue(fx.fleet.readiness.assertDispatchable(w.ctx("dispatcher"), r.vehicle, r.shift, null, Instant.now()) is DispatchVerdict.Refused)
        assertTrue(res.defect.blocksVehicle, "die Akte zeigt, dass sie das Fahrzeug sperrt")

        // derselbe Commit: Klasse, Sperrzeile, Fahrzeugzeile und Freigabewiderruf tragen denselben Transaktionsstempel
        val sameTx = fx.strings("SELECT (d.xmin::text = b.xmin::text AND d.xmin::text = v.xmin::text AND d.xmin::text = rr.xmin::text)::text FROM taktovia.defect_case d, taktovia.vehicle_block b, taktovia.vehicle v, " +
            "taktovia.resource_release rr WHERE d.id = ? AND d.vehicle_id = v.id AND b.vehicle_id = v.id AND b.reason_ref = d.case_number AND rr.vehicle_id = v.id AND rr.revoked_at IS NOT NULL", d.id)
        assertEquals(listOf("true"), sameTx)
        // Audit mit Fallnummer als Grund; Ereignis; Triage protokolliert (ohne Begründungstext)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.vehicle.blocked' AND object_ref=? AND redacted_changes->>'reason_ref'=?", w.tenant.value, "vehicle:${r.vehicle}", d.caseNumber))
        assertEquals(1, w.audit("fleet.defect.triaged", "defect:${d.id}"))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.defect.triaged' AND redacted_changes::text LIKE '%Riss im Rahmen%'", w.tenant.value))
    }

    @Test
    fun korrektur_ohne_grund_wird_abgewiesen() {
        val v = w.rw.vehicle(); val d = w.report(w.ctx("dispatcher"), v, "BODY", "SCRATCH", "LEFT_SIDE", "COSMETIC_ONLY")
        // ohne Grund: 422 mit Feldverweis, nichts geändert
        val e = problem { triage("dispatcher", d, DefectSeverities.OPERATIONALLY_RELEVANT) }
        assertEquals(422, e.code.status); assertEquals("/reason", e.violations.single().pointer)
        assertEquals(DefectSeverities.COSMETIC, fx.fleet.defects.get(w.ctx("dispatcher"), d.id).severity)
        // ohne Rolle (Fahrer, Fahrgast), fremder Mandant, veraltete Revision
        assertEquals(403, problem { fx.fleet.defects.triage(w.ctx("driver"), d.id, d.revision, TriageDefect("COSMETIC", "REVIEWED", false, null, null)) }.code.status)
        assertEquals(403, problem { fx.fleet.defects.triage(w.ctx("passenger"), d.id, d.revision, TriageDefect("COSMETIC", "REVIEWED", false, null, null)) }.code.status)
        val foreign = TriageWorld(fx)
        assertEquals(404, problem { fx.fleet.defects.triage(foreign.ctx("dispatcher"), d.id, d.revision, TriageDefect("COSMETIC", "REVIEWED", false, null, null)) }.code.status)
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { triage("dispatcher", d, "COSMETIC", rev = d.revision + 5) }.code)
        // Verwerfen verlangt die Werkstatt und einen Grund
        assertEquals(403, problem { triage("dispatcher", d, "OTHER", "DISMISSED", "Fehlmeldung") }.code.status)
        assertEquals("/reason", problem { triage("workshop", d, "COSMETIC", "DISMISSED") }.violations.single().pointer)

        // Bestätigung ohne Grund; Korrektur mit Grund und Zuständigem; die vorherige Einstufung bleibt in der Historie
        val assignee = UUID.randomUUID()
        val confirmed = triage("dispatcher", d, DefectSeverities.COSMETIC, assignee = assignee)
        assertFalse(confirmed.classificationChanged); assertEquals(assignee, confirmed.defect.assigneeRef); assertEquals("REVIEWED", confirmed.defect.status)
        val corrected = triage("dispatcher", confirmed.defect, DefectSeverities.OPERATIONALLY_RELEVANT, reason = "Tür schließt nicht mehr sauber")
        assertTrue(corrected.classificationChanged); assertEquals(assignee, corrected.defect.assigneeRef, "Zuständiger bleibt, wenn nicht angegeben")
        val history = fx.fleet.defects.classificationHistory(w.ctx("workshop"), d.id)
        assertEquals(listOf(null to "COSMETIC", "COSMETIC" to "COSMETIC", "COSMETIC" to "OPERATIONALLY_RELEVANT"), history.map { it.fromSeverity to it.toSeverity })
        assertEquals(listOf("RULE", "TRIAGE", "TRIAGE"), history.map { it.source }); assertEquals("Tür schließt nicht mehr sauber", history.last().reason)
        assertTrue(history.all { it.actor.startsWith("user:") && it.ruleVersion == DefectClassification.VERSION })
        // Zuständigen entfernen (assignee_ref: null); Folgeentscheidung IN_PROGRESS aus REVIEWED
        val cleared = triage("workshop", corrected.defect, DefectSeverities.OPERATIONALLY_RELEVANT, "IN_PROGRESS", assigneeGiven = true)
        assertNull(cleared.defect.assigneeRef); assertEquals("IN_PROGRESS", cleared.defect.status)
        // aus REPAIR oder einem Endzustand ist keine Triage mehr möglich
        val closed = w.rw.vehicle().let { vv -> w.report(w.ctx("dispatcher"), vv, "BODY") }
        triage("workshop", closed, "COSMETIC", "DISMISSED", "Fehlmeldung")
        assertEquals(409, problem { triage("workshop", fx.fleet.defects.get(w.ctx("workshop"), closed.id), "COSMETIC") }.code.status)
        // NEW -> IN_PROGRESS in einem Schritt läuft über REVIEWED (zwei Historienzeilen)
        val two = w.report(w.ctx("dispatcher"), w.rw.vehicle(), "BODY"); val twoRes = triage("dispatcher", two, "COSMETIC", "IN_PROGRESS")
        assertEquals("IN_PROGRESS", twoRes.defect.status)
        assertEquals(listOf("NEW>REVIEWED", "REVIEWED>IN_PROGRESS"), fx.strings("SELECT from_status || '>' || to_status FROM taktovia.defect_status_history WHERE defect_id = ? AND from_status IS NOT NULL ORDER BY revision", two.id))
    }

    @Test
    fun hochstufung_parallel_zu_zuweisung() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            // (a) Hochstufung gegen die Dispositionsprüfung (wie der Hold-Commit): in keiner Reihenfolge eine Zusage auf das unsichere Fahrzeug
            repeat(10) { i ->
                val r = w.released(); val d = w.report(w.ctx("dispatcher"), r.vehicle, "BODY", "SCRATCH", "LEFT_SIDE", "COSMETIC_ONLY")
                val rev = w.safetyRevision(r.vehicle); val start = CountDownLatch(1)
                val commit = AtomicReference<Instant>(); val blockDone = AtomicReference<Instant>(); val seen = AtomicReference<DispatchVerdict>()
                val dispo = pool.submit {
                    start.await()
                    val v = fx.ctxTx.run(w.ctx("dispatcher")) { j -> fx.fleet.readiness.assertDispatchable(j, r.vehicle, r.shift, rev, Instant.now()).also { Thread.sleep(35) } }
                    commit.set(Instant.now()); seen.set(v)
                }
                val up = pool.submit {
                    start.await(); Thread.sleep((i % 5) * 9L)
                    triage("dispatcher", d, DefectSeverities.SAFETY_CRITICAL, reason = "Riss festgestellt"); blockDone.set(Instant.now())
                }
                start.countDown(); dispo.get(20, TimeUnit.SECONDS); up.get(20, TimeUnit.SECONDS)
                when (val v = seen.get()) {
                    is DispatchVerdict.Dispatchable -> assertFalse(blockDone.get().isBefore(commit.get()), "Zusage nur vor Wirksamkeit der Sperre (Lauf $i)")
                    is DispatchVerdict.Refused -> assertTrue(v.reasons.isNotEmpty())
                    else -> throw AssertionError("kein Ergebnis")
                }
                assertTrue(fx.fleet.readiness.assertDispatchable(w.ctx("dispatcher"), r.vehicle, r.shift, null, Instant.now()) is DispatchVerdict.Refused, "danach nie disponierbar (Lauf $i)")
            }
            // (b) Hochstufung gegen die gleichzeitige Zuweisung des Zuständigen derselben Revision: genau einer gewinnt, der zweite erhält 412, nichts geht still verloren
            repeat(8) { i ->
                val v = w.rw.vehicle(); val d = w.report(w.ctx("dispatcher"), v, "BODY", "SCRATCH", "LEFT_SIDE", "COSMETIC_ONLY")
                val start = CountDownLatch(1); val assignee = UUID.randomUUID()
                val assign = pool.submit<Result<Unit>> { start.await(); runCatching { triage("dispatcher", d, DefectSeverities.COSMETIC, assignee = assignee); Unit } }
                val up = pool.submit<Result<Unit>> { start.await(); Thread.sleep((i % 3) * 5L); runCatching { triage("workshop", d, DefectSeverities.SAFETY_CRITICAL, reason = "Riss"); Unit } }
                start.countDown(); val results = listOf(assign.get(20, TimeUnit.SECONDS), up.get(20, TimeUnit.SECONDS))
                assertEquals(1, results.count { it.isSuccess }, "genau ein Schreiber (Lauf $i)")
                assertTrue(results.filter { it.isFailure }.all { (it.exceptionOrNull() as ProblemException).code == ErrorCode.REVISION_MISMATCH })
                val upgraded = results[1].isSuccess
                assertEquals(upgraded, w.activeBlocks(v).isNotEmpty(), "Sperre genau dann, wenn die Hochstufung gewann (Lauf $i)")
                val now = fx.fleet.defects.get(w.ctx("dispatcher"), d.id)
                assertEquals(if (upgraded) DefectSeverities.SAFETY_CRITICAL else DefectSeverities.COSMETIC, now.severity)
                assertEquals(if (upgraded) null else assignee, now.assigneeRef)
            }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun hochstufung_loest_alarm_nach_alert_policy_revision_aus() {
        val t = TriageWorld(fx)
        val ops = t.ctx("dispatcher")
        fx.fleet.alerts.publishPolicy(ops, listOf(AlertRule("VEHICLE_BLOCK_CRITICAL", AlertPriority.CRITICAL, "dispatcher")))
        val second = fx.fleet.alerts.publishPolicy(ops, listOf(AlertRule("VEHICLE_BLOCK_CRITICAL", AlertPriority.CRITICAL, "workshop"), AlertRule("*", AlertPriority.NORMAL, "dispatcher")))
        assertEquals(2, second.revisionNo)
        val v = t.rw.vehicle(); val d = t.report(ops, v, "BODY", "SCRATCH", "LEFT_SIDE", "COSMETIC_ONLY")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.business_alert WHERE tenant_id=? AND alert_kind='VEHICLE_BLOCK_CRITICAL'", t.tenant.value), "kosmetisch: kein Alarm")
        val res = fx.fleet.defects.triage(ops, d.id, d.revision, TriageDefect("SAFETY_CRITICAL", "REVIEWED", false, null, "Riss im Rahmen"))
        val alert = res.alert!!; assertTrue(alert.created); assertEquals(AlertPriority.CRITICAL, alert.priority); assertEquals(2, alert.policyRevisionNo, "nach der wirksamen Alarmrichtlinie")
        val view = fx.fleet.alerts.get(ops, alert.alertId)
        assertEquals("workshop", view.recipientRole); assertEquals(2, view.policyRevisionNo); assertEquals(v, view.vehicleId); assertEquals("defect_case", view.refType); assertEquals(d.id, view.refId)
        // Bestätigung derselben Klasse löst keinen zweiten Alarm und keinen zweiten Revisionssprung aus
        val rev = t.safetyRevision(v)
        val again = fx.fleet.defects.triage(ops, d.id, res.defect.revision, TriageDefect("SAFETY_CRITICAL", "IN_PROGRESS", false, null, null))
        assertNull(again.alert); assertNull(again.block); assertEquals(rev, t.safetyRevision(v))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.business_alert WHERE tenant_id=? AND alert_kind='VEHICLE_BLOCK_CRITICAL' AND ref_id=?", t.tenant.value, d.id))
    }

    @Test
    fun parameter_darf_sicherheitskritisch_nicht_ausnehmen() {
        val candidates = listOf("SAFETY_CRITICAL", "OPERATIONALLY_RELEVANT", "COSMETIC", "OTHER", "NONE", "NEVER", "OFF", "false", "", " ", "S1", "S0", "safety_critical", "KEINE", "null")
        for (c in candidates) {
            val s = runCatching { DefectSettings.parse(c) }
            if (s.isSuccess) assertTrue(s.getOrThrow().blocks(DefectSeverities.SAFETY_CRITICAL), "zulässiger Wert '$c' darf sicherheitskritische Schäden nie von der Sperre ausnehmen")
            else assertTrue(s.exceptionOrNull() is IllegalArgumentException, "unzulässiger Wert '$c' wird abgewiesen")
        }
        assertEquals(setOf("SAFETY_CRITICAL", "OPERATIONALLY_RELEVANT", "COSMETIC", "OTHER"), candidates.filter { runCatching { DefectSettings.parse(it) }.isSuccess }.toSet())
        assertTrue(DefectSettings.parse(null).blocks(DefectSeverities.SAFETY_CRITICAL)); assertFalse(DefectSettings.parse(null).blocks(DefectSeverities.OPERATIONALLY_RELEVANT))
        assertTrue(DefectSettings("OPERATIONALLY_RELEVANT").blocks(DefectSeverities.OPERATIONALLY_RELEVANT)); assertFalse(DefectSettings("OPERATIONALLY_RELEVANT").blocks(DefectSeverities.COSMETIC))
        // wirksam: mit niedrigerer Schwelle sperrt auch eine einsatzrelevante Akte; sicherheitskritische sperrt in jedem Fall
        val strict = FleetsafetyApiFor(DefectSettings("OPERATIONALLY_RELEVANT")); val t = TriageWorld(fx, strict)
        val v = t.rw.vehicle(); t.report(t.ctx("dispatcher"), v, "LIGHTS", "MALFUNCTION", "FRONT", "FUNCTION_LIMITED")
        assertEquals(1, t.activeBlocks(v).size)
        val v2 = t.rw.vehicle(); t.report(t.ctx("dispatcher"), v2, "BODY", "SCRATCH", "LEFT_SIDE", "COSMETIC_ONLY")
        assertEquals(0, t.activeBlocks(v2).size)
    }

    private fun FleetsafetyApiFor(settings: DefectSettings) = de.taktovia.fleetsafety.api.FleetsafetyApi.create(fx.ctxTx, de.taktovia.platform.audit.JdbcAuditPort(), de.taktovia.platform.outbox.Outbox(), defectSettings = settings)

    @Test
    fun herabstufung_hebt_sperre_nicht_auf() {
        val r = w.released(); val d = w.report(w.ctx("dispatcher"), r.vehicle, "BRAKES", "MALFUNCTION", "WHEELS_TIRES", "NOT_ROADWORTHY")
        assertEquals(DefectSeverities.SAFETY_CRITICAL, d.severity); assertEquals(1, w.activeBlocks(r.vehicle).size)
        val rev = w.safetyRevision(r.vehicle)
        // die Disposition darf nicht herabstufen (Rolle Werkstatt/technische Prüfung), die Werkstatt nur mit Grund
        assertEquals(403, problem { triage("dispatcher", d, DefectSeverities.COSMETIC, reason = "harmlos") }.code.status)
        assertEquals("/reason", problem { triage("workshop", d, DefectSeverities.COSMETIC) }.violations.single().pointer)
        val down = triage("workshop", d, DefectSeverities.COSMETIC, reason = "Bremsprobe in Ordnung, Fehlmeldung des Sensors")
        assertEquals(DefectSeverities.COSMETIC, down.defect.severity); assertTrue(down.classificationChanged); assertNull(down.block)
        assertEquals(1, w.activeBlocks(r.vehicle).size, "die Sperre bleibt"); assertTrue(w.activeBlockFlag(r.vehicle)); assertEquals(rev, w.safetyRevision(r.vehicle))
        assertTrue(fx.fleet.readiness.assertDispatchable(w.ctx("dispatcher"), r.vehicle, r.shift, null, Instant.now()) is DispatchVerdict.Refused)
        assertTrue(down.defect.blocksVehicle, "die Akte zeigt weiter, dass ihre Sperre besteht")
        // der Schadenautomat hebt sie ebenfalls nicht auf (CLOSED/DISMISSED, DMG-007)
        assertEquals(1, w.activeBlocks(r.vehicle).size)
    }

    @Test
    fun aktenhistorie_zeigt_klassifikation_sperre_und_zuweisung_mit_akteur_und_zeit() {
        val r = w.released(); val dispatcher = w.ctx("dispatcher"); val assignee = UUID.randomUUID()
        val d = w.report(dispatcher, r.vehicle, "BODY", "SCRATCH", "LEFT_SIDE", "COSMETIC_ONLY")
        fx.fleet.defects.triage(dispatcher, d.id, d.revision, TriageDefect("SAFETY_CRITICAL", "REVIEWED", true, assignee, "Riss im Rahmen"))
        val tl = fx.fleet.defects.timeline(w.ctx("workshop"), d.id)
        assertEquals(setOf(TimelineKind.STATUS, TimelineKind.CLASSIFICATION, TimelineKind.ASSIGNMENT, TimelineKind.VEHICLE_BLOCK), tl.map { it.kind }.toSet())
        assertTrue(tl.all { it.actor.startsWith("user:") && it.occurredAt.isBefore(Instant.now().plusSeconds(5)) })
        assertEquals(tl.sortedBy { it.occurredAt }.map { it.kind to it.detail }, tl.map { it.kind to it.detail }, "zeitliche Folge")
        assertTrue(tl.any { it.kind == TimelineKind.CLASSIFICATION && it.detail == "COSMETIC>SAFETY_CRITICAL" && it.reason == "Riss im Rahmen" })
        assertTrue(tl.any { it.kind == TimelineKind.ASSIGNMENT && it.detail.contains(assignee.toString()) })
        assertTrue(tl.any { it.kind == TimelineKind.VEHICLE_BLOCK && it.detail == "SAFETY|DEFECT_CRITICAL" })
        assertTrue(tl.any { it.kind == TimelineKind.STATUS && it.detail == "NEW>REVIEWED" })
        // Fahrer sehen die Aktenhistorie nicht
        assertEquals(403, problem { fx.fleet.defects.timeline(w.ctx("driver"), d.id) }.code.status)
        assertNotNull(tl.first())
    }
}
