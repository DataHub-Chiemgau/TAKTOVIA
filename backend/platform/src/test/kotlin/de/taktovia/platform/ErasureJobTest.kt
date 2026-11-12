package de.taktovia.platform

import com.zaxxer.hikari.HikariDataSource
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.retention.ApplyResult
import de.taktovia.platform.retention.ArchiveAccess
import de.taktovia.platform.retention.Candidate
import de.taktovia.platform.retention.ErasureEngine
import de.taktovia.platform.retention.JobStatus
import de.taktovia.platform.retention.RetentionAction
import de.taktovia.platform.retention.RetentionRuleView
import de.taktovia.platform.retention.RetentionRules
import de.taktovia.platform.retention.RetentionTarget
import de.taktovia.platform.retention.RuleSnapshot
import de.taktovia.platform.storage.InMemoryObjectStore
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * WP-M1-33 / LH-6.18-SEC-007: Aufbewahrungsregeln und Löschlauf gegen die echte Datenbank unter der Runtime-Rolle. Die Plattform kennt die Fachtabellen nicht; als
 * Löschziel dient hier ein Test-Ziel für `passenger_private` (derselbe Zugriff wie das Ziel des Moduls identity, das im Modul selbst gegen die echten Fahrgastbestände
 * getestet wird: `PassengerRetentionTest`). Der Löschzustand der Zeile (Leeren der Personenspalten, keine wiederauflebende PII) ist Sache des Datenbank-Triggers.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ErasureJobTest {
    /** Wie das Ziel des Moduls identity (Test-Nachbau: die Plattform darf kein Fachmodul kennen). */
    private open class PassengerTarget : RetentionTarget {
        override val dataClass = "LK_PII"
        override val objectType = "passenger"
        override val supportedActions = setOf(RetentionAction.ANONYMIZE, RetentionAction.ARCHIVE, RetentionAction.DELETE)
        override fun candidates(jdbc: JdbcOperations, rule: RuleSnapshot, now: Instant, after: UUID?, limit: Int): List<Candidate> {
            val ts = Timestamp.from(now)
            return jdbc.query(
                "SELECT id, archived_at IS NOT NULL FROM taktovia.passenger_private WHERE erased_at IS NULL " +
                    "AND ((archived_at IS NULL AND retention_until + make_interval(days => ?) <= ?) OR (archived_at IS NOT NULL AND archive_until <= ?)) " +
                    "AND (CAST(? AS uuid) IS NULL OR id > CAST(? AS uuid)) ORDER BY id LIMIT ?",
                { rs, _ -> Candidate(rs.getObject(1, UUID::class.java), if (rs.getBoolean(2)) RetentionAction.ANONYMIZE else rule.action) }, rule.retentionDays, ts, ts, after, after, limit)
        }
        override fun apply(jdbc: JdbcOperations, rule: RuleSnapshot, candidate: Candidate, now: Instant): ApplyResult = when (candidate.action) {
            RetentionAction.ARCHIVE -> {
                val until = now.plus(rule.archiveDays!!.toLong(), ChronoUnit.DAYS)
                jdbc.update("UPDATE taktovia.passenger_private SET archived_at = ?, archive_until = ? WHERE id = ? AND erased_at IS NULL AND archived_at IS NULL", Timestamp.from(now), Timestamp.from(until), candidate.id)
                ApplyResult(RetentionAction.ARCHIVE, until)
            }
            else -> {
                jdbc.update("UPDATE taktovia.passenger_private SET erased_at = ? WHERE id = ? AND erased_at IS NULL", Timestamp.from(now), candidate.id)
                jdbc.update("DELETE FROM taktovia.passenger_contact WHERE passenger_id = ?", candidate.id)
                ApplyResult(RetentionAction.ANONYMIZE)
            }
        }
        override fun objectPrefixes(id: UUID) = listOf("passenger/$id/")
    }

    private object NoRevalidation : ContextRevalidator { override fun revalidate(jdbc: JdbcOperations, ctx: RequestContext, lock: Boolean) = Unit }

    private lateinit var runtime: HikariDataSource
    private lateinit var fieldPool: HikariDataSource
    private lateinit var admin: JdbcTemplate
    private lateinit var tx: TenantTransactions
    private lateinit var rules: RetentionRules
    private val objects = InMemoryObjectStore()
    private val audit = JdbcAuditPort()
    private val tenantA = TenantId(UUID.randomUUID()); private val tenantB = TenantId(UUID.randomUUID())

    @BeforeAll fun setup() {
        TestDatabase.require()
        admin = JdbcTemplate(TestDatabase.admin())
        for (t in listOf(tenantA, tenantB)) admin.update("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?, 'ACTIVE')", t.value, "aufbewahrung-$t")
        runtime = TestDatabase.runtime(poolSize = 24); fieldPool = TestDatabase.fieldAccess(poolSize = 4)
        tx = TenantTransactions(runtime)
        rules = RetentionRules(ContextTransactions(tx, NoRevalidation), audit)
    }

    @AfterAll fun teardown() { if (::runtime.isInitialized) runtime.close(); if (::fieldPool.isInitialized) fieldPool.close() }

    private fun ctx(t: TenantId, subject: String) = RequestContext(Actor(ActorType.SERVICE, "https://idp.test", subject, null, false), t, emptySet(), setOf("retention:read", "retention:write"), emptySet(), UUID.randomUUID())

    /** Freigegebene Regel (zwei verschiedene Personen: Änderung und Freigabe). Je Test ein eigener Mandant, damit höchstens eine Regel je Klasse freigegeben ist. */
    private fun approvedRule(t: TenantId, days: Int = 30, action: RetentionAction = RetentionAction.ANONYMIZE, archiveDays: Int? = null): RetentionRuleView {
        val r = rules.create(ctx(t, "editor-1"), "LK_PII", days, action, archiveDays)
        return rules.approve(ctx(t, "approver-1"), r.id, r.revision)
    }

    private fun newTenant(): TenantId = TenantId(UUID.randomUUID()).also { admin.update("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?, 'ACTIVE')", it.value, "aufbewahrung-$it") }

    /** Fahrgastkonto mit Kontakt- und Unterstützungsdaten (Ciphertext, vom Administrator angelegt), Aufbewahrungsende vor [daysAgo] Tagen. */
    private fun passenger(t: TenantId, daysAgo: Long = 400): UUID {
        val id = UUID.randomUUID()
        admin.update("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,contact_ciphertext,support_ciphertext,retention_until,idp_issuer,idp_subject) VALUES (?,?,?,?,?,now() - make_interval(days => ?),'https://idp.test',?)",
            t.value, id, "tok-$id", "KONTAKT".toByteArray(), "UNTERSTUETZUNG".toByteArray(), daysAgo.toInt(), "sub-$id")
        admin.update("INSERT INTO taktovia.passenger_contact(tenant_id,id,passenger_id,channel,contact_token) VALUES (?,?,?,'EMAIL',?)", t.value, UUID.randomUUID(), id, id.toString().replace("-", "") + id.toString().replace("-", ""))
        return id
    }

    private fun engine(vararg targets: RetentionTarget = arrayOf(PassengerTarget()), clock: java.time.Clock = java.time.Clock.systemUTC()) = ErasureEngine(tx, targets.toList(), audit, objects, clock, batchSize = 7)
    private fun count(sql: String, vararg a: Any?) = admin.queryForObject(sql, Int::class.java, *a)!!
    private fun erased(id: UUID) = count("SELECT count(*) FROM taktovia.passenger_private WHERE id = ? AND erased_at IS NOT NULL AND contact_ciphertext IS NULL AND support_ciphertext IS NULL AND idp_subject IS NULL AND subject_token = ?", id, "erased:$id") == 1
    private fun intact(id: UUID) = count("SELECT count(*) FROM taktovia.passenger_private WHERE id = ? AND erased_at IS NULL AND contact_ciphertext IS NOT NULL AND support_ciphertext IS NOT NULL AND idp_subject IS NOT NULL", id) == 1

    @Test fun testdatensatz_wird_nach_freigegebener_regel_entfernt() {
        val t = newTenant()
        val expired = passenger(t); val running = passenger(t, daysAgo = -90)   // Frist läuft noch 90 Tage
        val rule = approvedRule(t)
        val job = rules.requestRun(ctx(t, "editor-1"), rule.id)
        assertEquals(JobStatus.RESUMABLE, job.status)
        assertNotNull(job.operationId)
        assertEquals(1, count("SELECT count(*) FROM taktovia.operation WHERE id = ? AND kind = 'RETENTION_RUN'", job.operationId))
        val done = engine().run(t, job.id)
        assertEquals(JobStatus.COMPLETED, done.status); assertEquals(1, done.processedCount)
        // danach liefert keine Abfrage Personendaten: Ciphertexts, IdP-Bezug, Pseudonym und Kontaktkanal sind weg
        assertTrue(erased(expired))
        assertEquals(0, count("SELECT count(*) FROM taktovia.passenger_contact WHERE passenger_id = ?", expired))
        assertEquals(0, count("SELECT count(*) FROM taktovia.passenger_private WHERE id = ? AND (contact_ciphertext IS NOT NULL OR support_ciphertext IS NOT NULL OR idp_subject IS NOT NULL OR subject_token LIKE 'tok-%')", expired))
        // Datensatz mit noch laufender Frist bleibt unberührt
        assertTrue(intact(running)); assertEquals(1, count("SELECT count(*) FROM taktovia.passenger_contact WHERE passenger_id = ?", running))
        // Journal ohne Personendaten, Regel und Lauf auditiert
        val journal = admin.queryForList("SELECT object_ref, action FROM taktovia.erasure_journal WHERE tenant_id = ?", t.value)
        assertEquals(listOf(mapOf("object_ref" to "passenger:$expired", "action" to "ANONYMIZE")), journal)
        for (a in listOf("retention.rule.changed", "retention.rule.approved", "retention.erasure.started", "retention.erasure.finished"))
            assertEquals(1, count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = ?", t.value, a), a)
        assertEquals(0, count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND (redacted_changes::text LIKE '%KONTAKT%' OR redacted_changes::text LIKE '%sub-%')", t.value))
        // Wiederholung ändert nichts (Endzustand)
        assertEquals(1, engine().run(t, job.id).processedCount)
    }

    @Test fun archivierung_nach_regel_operative_rolle_ohne_zugriff() {
        val t = newTenant()
        val p = passenger(t)
        val rule = approvedRule(t, action = RetentionAction.ARCHIVE, archiveDays = 30)
        val job = engine().run(t, rules.requestRun(ctx(t, "editor-1"), rule.id).id)
        assertEquals(JobStatus.COMPLETED, job.status)
        // operative Abfrage (Runtime-Rolle ohne Archivzugang): Datensatz und Bestände sind nicht mehr lesbar und nicht änderbar
        tx.run(t) { j ->
            assertEquals(0, j.queryForObject("SELECT count(*) FROM taktovia.passenger_private WHERE id = ?", Int::class.java, p))
            assertEquals(0, j.queryForObject("SELECT count(*) FROM taktovia.passenger_contact WHERE passenger_id = ?", Int::class.java, p))
            assertEquals(0, j.update("UPDATE taktovia.passenger_private SET locale = 'en-GB' WHERE id = ?", p))
        }
        // berechtigter Zugang (Archivzugang, bis Fristende): lesbar, weiterhin mit den Personendaten (verschlüsselt, nur der Modulzugang entschlüsselt sie)
        tx.run(t) { j -> ArchiveAccess.enable(j); assertEquals(1, j.queryForObject("SELECT count(*) FROM taktovia.passenger_private WHERE id = ? AND archive_until > now()", Int::class.java, p)) }
        assertEquals(1, count("SELECT count(*) FROM taktovia.passenger_private WHERE id = ? AND contact_ciphertext IS NOT NULL", p))
        assertEquals("ARCHIVE", admin.queryForObject("SELECT action FROM taktovia.erasure_journal WHERE object_ref = ?", String::class.java, "passenger:$p"))
        // Nach Fristende des Archivs: der nächste Lauf anonymisiert (Archivzustand → Löschzustand)
        admin.update("UPDATE taktovia.passenger_private SET archive_until = now() - interval '1 minute', archived_at = now() - interval '31 days' WHERE id = ?", p)
        val second = engine().run(t, rules.requestRun(ctx(t, "editor-1"), rule.id).id)
        assertEquals(1, second.processedCount); assertTrue(erased(p))
        assertEquals(listOf("ANONYMIZE", "ARCHIVE"), admin.queryForList("SELECT action FROM taktovia.erasure_journal WHERE object_ref = ? ORDER BY action", String::class.java, "passenger:$p"))
    }

    @Test fun abbruch_und_fortsetzung_ohne_doppelverarbeitung() {
        val t = newTenant()
        val ids = (1..10).map { passenger(t) }
        val rule = approvedRule(t)
        val job = rules.requestRun(ctx(t, "editor-1"), rule.id)
        val applies = AtomicInteger(); val crash = AtomicInteger(1)
        val flaky = object : PassengerTarget() {
            override fun apply(jdbc: JdbcOperations, rule: RuleSnapshot, candidate: Candidate, now: Instant): ApplyResult {
                if (applies.incrementAndGet() == 4 && crash.getAndSet(0) == 1) throw IllegalStateException("Prozess abgebrochen")
                return super.apply(jdbc, rule, candidate, now)
            }
        }
        val first = engine(flaky).run(t, job.id)
        assertEquals(JobStatus.RESUMABLE, first.status); assertEquals(3, first.processedCount)
        assertEquals(3, count("SELECT count(*) FROM taktovia.erasure_journal WHERE tenant_id = ?", t.value))
        assertNotNull(admin.queryForObject("SELECT resume_after FROM taktovia.erasure_job WHERE id = ?", UUID::class.java, job.id))   // Fortsetzungsmarke
        assertEquals(7, ids.count { intact(it) })                // nichts Halbes: das unterbrochene Objekt ist vollständig unverändert
        val second = engine(flaky).run(t, job.id)
        assertEquals(JobStatus.COMPLETED, second.status); assertEquals(10, second.processedCount); assertEquals(0, second.skippedCount)
        assertTrue(ids.all { erased(it) })
        assertEquals(10, count("SELECT count(DISTINCT object_ref) FROM taktovia.erasure_journal WHERE tenant_id = ?", t.value))   // jedes Objekt genau einmal
        // Hart beendeter Worker: RUNNING mit abgelaufener Lease wird von einem anderen Lauf übernommen; mit gültiger Lease bleibt der Job unberührt
        val late = (1..3).map { passenger(t) }
        val job2 = rules.requestRun(ctx(t, "editor-1"), rule.id)
        admin.update("UPDATE taktovia.erasure_job SET status = 'RUNNING', lease_until = now() + interval '10 minutes' WHERE id = ?", job2.id)
        assertEquals(JobStatus.RUNNING, engine().run(t, job2.id).status); assertTrue(late.all { intact(it) })
        admin.update("UPDATE taktovia.erasure_job SET lease_until = now() - interval '1 minute' WHERE id = ?", job2.id)
        assertEquals(JobStatus.COMPLETED, engine().run(t, job2.id).status); assertTrue(late.all { erased(it) })
    }

    @Test fun lauf_in_mandant_a_beruehrt_b_nicht() {
        val a = newTenant(); val b = newTenant()
        val pa = passenger(a); val pb = passenger(b)
        objects.put(ObjectKey(b, "passenger/$pb/beleg.pdf"), byteArrayOf(1), "application/pdf")
        val ruleA = approvedRule(a)
        val job = rules.requestRun(ctx(a, "editor-1"), ruleA.id)
        assertEquals(JobStatus.COMPLETED, engine().run(a, job.id).status)
        assertTrue(erased(pa)); assertTrue(intact(pb))                               // Mandant B unberührt, auch wenn dort ebenfalls Fälliges liegt
        assertEquals(listOf("passenger/$pb/beleg.pdf"), objects.list(b))
        assertEquals(0, count("SELECT count(*) FROM taktovia.erasure_journal WHERE tenant_id = ?", b.value))
        // Der Lauf des Mandanten A ist im Kontext von B nicht auffindbar und nicht ausführbar
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { engine().run(b, job.id) }.code)
        assertTrue(intact(pb))
        // Fremde Regel im eigenen Kontext: nicht auffindbar
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { rules.requestRun(ctx(b, "editor-1"), ruleA.id) }.code)
    }

    @Test fun zwei_laeufe_parallel_ohne_doppelverarbeitung() {
        val t = newTenant()
        val ids = (1..40).map { passenger(t) }
        val rule = approvedRule(t)
        val j1 = rules.requestRun(ctx(t, "editor-1"), rule.id); val j2 = rules.requestRun(ctx(t, "editor-1"), rule.id)
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        val results = listOf(j1, j2).map { j -> pool.submit<de.taktovia.platform.retention.ErasureJobView> { start.await(); engine().run(t, j.id) } }
        start.countDown()
        val views = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertTrue(views.all { it.status == JobStatus.COMPLETED }, views.toString())
        assertEquals(40, views.sumOf { it.processedCount }, "jedes Objekt wird von genau einem der beiden Läufe bearbeitet")
        assertEquals(40, count("SELECT count(*) FROM taktovia.erasure_journal WHERE tenant_id = ?", t.value))
        assertEquals(40, count("SELECT count(DISTINCT object_ref) FROM taktovia.erasure_journal WHERE tenant_id = ?", t.value))
        assertTrue(ids.all { erased(it) })
    }

    @Test fun objektspeicher_objekt_wird_geloescht() {
        val t = newTenant()
        val gone = passenger(t); val kept = passenger(t, daysAgo = -10)
        objects.put(ObjectKey(t, "passenger/$gone/ausweis.pdf"), byteArrayOf(1, 2), "application/pdf")
        objects.put(ObjectKey(t, "passenger/$gone/foto.png"), byteArrayOf(3), "image/png")
        objects.put(ObjectKey(t, "passenger/$kept/ausweis.pdf"), byteArrayOf(4), "application/pdf")
        objects.put(ObjectKey(t, "belege/rechnung-1.pdf"), byteArrayOf(5), "application/pdf")   // anderer Bestand: nicht Teil des Fahrgastkontos
        val rule = approvedRule(t)
        assertEquals(JobStatus.COMPLETED, engine().run(t, rules.requestRun(ctx(t, "editor-1"), rule.id).id).status)
        assertEquals(listOf("belege/rechnung-1.pdf", "passenger/$kept/ausweis.pdf"), objects.list(t))
        assertNull(objects.get(ObjectKey(t, "passenger/$gone/ausweis.pdf")))
        // Archivieren löscht keine Objekte (Abrufbarkeit bis Fristende)
        val t2 = newTenant(); val p2 = passenger(t2)
        objects.put(ObjectKey(t2, "passenger/$p2/ausweis.pdf"), byteArrayOf(9), "application/pdf")
        engine().run(t2, rules.requestRun(ctx(t2, "editor-1"), approvedRule(t2, action = RetentionAction.ARCHIVE, archiveDays = 10).id).id)
        assertEquals(listOf("passenger/$p2/ausweis.pdf"), objects.list(t2))
    }

    @Test fun nicht_freigegebene_regel_wird_nicht_angewendet() {
        val t = newTenant()
        val p = passenger(t)
        val draft = rules.create(ctx(t, "editor-1"), "LK_PII", 30, RetentionAction.ANONYMIZE, null)
        assertEquals(ErrorCode.CONFLICT, assertThrows<ProblemException> { rules.requestRun(ctx(t, "editor-1"), draft.id) }.code)   // Entwurf: kein Lauf
        // Vier-Augen: der Urheber gibt nicht selbst frei
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { rules.approve(ctx(t, "editor-1"), draft.id, draft.revision) }.code)
        val approved = rules.approve(ctx(t, "approver-1"), draft.id, draft.revision)
        val job = rules.requestRun(ctx(t, "editor-1"), approved.id)
        // Die Regel wird nach dem Anlegen des Laufs geändert (zurück auf Entwurf): der Lauf wendet sie nicht an
        val changed = rules.update(ctx(t, "editor-1"), approved.id, approved.revision, 1, RetentionAction.DELETE, null)
        assertEquals("DRAFT", changed.status.name)
        val aborted = engine().run(t, job.id)
        assertEquals(JobStatus.ABORTED, aborted.status); assertEquals("RULE_NOT_APPROVED", aborted.resultCode); assertEquals(0, aborted.processedCount)
        assertTrue(intact(p)); assertEquals(0, count("SELECT count(*) FROM taktovia.erasure_journal WHERE tenant_id = ?", t.value))
        // Ziel für die Datenklasse fehlt oder unterstützt die Aktion nicht: Abbruch statt stiller Ersatzaktion
        val reapproved = rules.approve(ctx(t, "approver-1"), changed.id, changed.revision)
        assertEquals("NO_TARGET", engine(object : PassengerTarget() { override val dataClass = "LK_STANDORT" }).run(t, rules.requestRun(ctx(t, "editor-1"), reapproved.id).id).resultCode)
        assertEquals("ACTION_NOT_SUPPORTED", engine(object : PassengerTarget() { override val supportedActions = setOf(RetentionAction.ARCHIVE) }).run(t, rules.requestRun(ctx(t, "editor-1"), reapproved.id).id).resultCode)
        assertTrue(intact(p))
        // Regelwerte sind geprüft: ungültige Frist, Archiv ohne Archivdauer, unbekannte Datenklasse
        for ((cls, days, act, arch) in listOf(Quad("LK_PII", 0, RetentionAction.DELETE, null), Quad("LK_PII", 5, RetentionAction.ARCHIVE, null), Quad("LK_X", 5, RetentionAction.DELETE, null), Quad("LK_PII", 5, RetentionAction.DELETE, 3)))
            assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows<ProblemException> { rules.create(ctx(t, "editor-1"), cls, days, act, arch) }.code, "$cls/$days/$act/$arch")
        // Veraltete Revision: 412
        assertEquals(ErrorCode.REVISION_MISMATCH, assertThrows<ProblemException> { rules.update(ctx(t, "editor-1"), approved.id, 1, 5, RetentionAction.DELETE, null) }.code)
    }

    private data class Quad(val cls: String, val days: Int, val action: RetentionAction, val archive: Int?)

    @Test fun loeschlauf_parallel_zu_profilaenderung() {
        // Profiländerung (Modulzugang schreibt den Ciphertext) und Löschlauf desselben Datensatzes gleichzeitig: danach nie Personendaten im gelöschten Datensatz.
        val t = newTenant(); val fieldsTx = TenantTransactions(fieldPool)
        val rule = approvedRule(t)
        repeat(6) { round ->
            val ids = (1..5).map { passenger(t) }
            val job = rules.requestRun(ctx(t, "editor-1"), rule.id)
            val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(3)
            val update = pool.submit<Int> {
                start.await(); var rejected = 0
                for (id in ids) try { fieldsTx.run(t) { j -> j.update("UPDATE taktovia.passenger_private SET contact_ciphertext = ? WHERE id = ?", "NEU".toByteArray(), id) } } catch (e: org.springframework.dao.DataIntegrityViolationException) { rejected++ }
                rejected
            }
            val run = pool.submit<de.taktovia.platform.retention.ErasureJobView> { start.await(); engine().run(t, job.id) }
            start.countDown()
            val view = run.get(60, TimeUnit.SECONDS); update.get(60, TimeUnit.SECONDS); pool.shutdown()
            assertEquals(JobStatus.COMPLETED, view.status, "Runde $round")
            for (id in ids) assertTrue(erased(id), "Runde $round: Datensatz $id enthält wieder Personendaten")
        }
    }
}
