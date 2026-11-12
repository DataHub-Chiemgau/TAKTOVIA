package de.taktovia.documentsfinance

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.documentsfinance.api.ConfirmLedgerImport
import de.taktovia.documentsfinance.api.ExportRequestOutcome
import de.taktovia.documentsfinance.api.ExportState
import de.taktovia.documentsfinance.api.ExpenseStates
import de.taktovia.documentsfinance.api.ImportConfirmation
import de.taktovia.documentsfinance.api.LedgerExportConfig
import de.taktovia.documentsfinance.api.LedgerExportState
import de.taktovia.documentsfinance.api.LedgerExportView
import de.taktovia.documentsfinance.api.RequestLedgerExport
import de.taktovia.documentsfinance.api.UpdateFinancePolicy
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.operation.OperationConfig
import de.taktovia.platform.operation.OperationRunner
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.storage.InMemoryObjectStore
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-37 (E2E-068, DOC-010 Exportstatus): ERP-Export als wiederholsicherer Job. Echte Datenbank unter der Runtime-Rolle und der echte Operation-Runner (Lease und Fencing): Wiederholung liefert denselben
 * Batch mit gleichen Kennungen und gleichem Hash, nur freigegebene Journale, je Journal genau ein Batch (auch parallel), Workerabsturz ohne zweiten Batch, eigenes Exportrecht, Exportstatus nur durch den Job,
 * Bestätigung mit Import-IDs, kurzlebiger mandantengebundener Download, Audit und Ereignisse.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LedgerExportTest {
    private lateinit var bf: BelegFixture
    private val sep = LocalDate.of(2026, 9, 1)
    private val oct = LocalDate.of(2026, 10, 1)

    @BeforeAll fun start() { bf = BelegFixture(InMemoryObjectStore(), ledger = LedgerExportConfig(LedgerTestFormat(), ByteArray(32) { (it * 5 + 1).toByte() }, Duration.ofMillis(700))) }
    @AfterAll fun stop() { bf.close() }

    private fun problem(f: () -> Unit): ProblemException = bf.problem(f)
    private fun runner(worker: String = "worker-a", lease: Duration = Duration.ofSeconds(30)) =
        OperationRunner(bf.fx.tx, listOf(bf.svc.exports.operationHandler(bf.fx.tx)), Outbox(), Clock.systemUTC(), OperationConfig(lease = lease), worker)

    /** Mandant mit Perioden und `n` vollständig freigegebenen, gebuchten Belegen im September 2026. */
    private fun world(n: Int = 3): LedgerWorld { val w = LedgerWorld(bf); w.period(sep, oct); w.period(oct, LocalDate.of(2026, 11, 1)); repeat(n) { w.approvedByWorkflow(10000 + it * 100L) }; return w }

    private fun LedgerWorld.request(from: LocalDate = sep, to: LocalDate = oct, by: de.taktovia.platform.context.RequestContext = exporter) = bf.svc.exports.request(by, RequestLedgerExport(w.legalEntity, from, to))
    private val LedgerWorld.exporter get() = person(setOf("finance"), setOf("ledger:export", "ledger:read"))

    private fun LedgerWorld.runAll(): Int = runner().runTenant(w.tenant)

    private fun lineCount(w: LedgerWorld) = bf.fx.count("SELECT count(*) FROM taktovia.ledger_export_line WHERE tenant_id=?", w.w.tenant.value)

    @Test
    fun wiederholung_liefert_denselben_batch_mit_denselben_ids_und_hash() {
        val w = world(3)
        val first = w.request(); assertTrue(first is ExportRequestOutcome.Queued, first.toString()); assertEquals(LedgerExportState.REQUESTED, first.export.state)
        // dieselbe Anforderung vor der Ausführung: derselbe Auftrag, kein zweiter
        val pending = w.request(); assertTrue(pending is ExportRequestOutcome.Existing); assertEquals(first.export.id, pending.export.id); assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.ledger_export WHERE tenant_id=?", w.w.tenant.value))
        assertEquals(1, w.runAll())
        val done = bf.svc.exports.get(w.exporter, first.export.id)
        assertEquals(LedgerExportState.PROVIDED, done.state); assertEquals(3, done.journalCount); assertNotEquals(null, done.contentSha256); assertTrue(done.batchRef!!.startsWith("EXP-")); assertEquals("test-lines", done.formatName)
        // Wiederholung nach der Bereitstellung: derselbe Batch mit denselben Kennungen und demselben Hash, ohne neue Operation
        val ops = bf.fx.count("SELECT count(*) FROM taktovia.operation WHERE tenant_id=?", w.w.tenant.value)
        val again = w.request(); assertTrue(again is ExportRequestOutcome.Existing); assertEquals(done, again.export); assertEquals(ops, bf.fx.count("SELECT count(*) FROM taktovia.operation WHERE tenant_id=?", w.w.tenant.value))
        // Inhalt: Hash = SHA-256 der Bytes, zweimal geladen identisch, Journal-IDs stabil und je Journal eine Zeile im Batch
        val l1 = bf.svc.exports.downloadLink(w.exporter, done.id); val d1 = download(w, done.id, l1.url); val d2 = download(w, done.id, bf.svc.exports.downloadLink(w.exporter, done.id).url)
        assertEquals(done.contentSha256, d1.sha256); assertEquals(done.contentSha256, MessageDigest.getInstance("SHA-256").digest(d1.bytes).joinToString("") { "%02x".format(it) }); assertArrayEquals(d1.bytes, d2.bytes)
        val journalIds = bf.svc.exports.lines(w.exporter, done.id).map { it.journalId }; assertEquals(3, journalIds.size)
        assertTrue(journalIds.all { String(d1.bytes).contains(it.toString()) }, "jede eindeutige Journal-ID steht im Inhalt")
        // ein neues Journal im selben Zeitraum: ein neuer Batch nur mit diesem Journal; der erste bleibt unverändert, kein Journal erscheint doppelt
        w.approvedByWorkflow(7000)
        val next = w.request(); assertTrue(next is ExportRequestOutcome.Queued); w.runAll()
        val second = bf.svc.exports.get(w.exporter, next.export.id)
        assertEquals(1, second.journalCount); assertNotEquals(done.id, second.id); assertNotEquals(done.batchRef, second.batchRef); assertEquals(done, bf.svc.exports.get(w.exporter, done.id))
        assertEquals(4, lineCount(w)); assertEquals(0, bf.fx.count("SELECT count(*) FROM (SELECT journal_id FROM taktovia.ledger_export_line WHERE tenant_id=? GROUP BY journal_id HAVING count(*) > 1) x", w.w.tenant.value))
    }

    private fun download(w: LedgerWorld, id: UUID, url: String): de.taktovia.documentsfinance.api.LedgerDownload {
        val q = url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        return bf.svc.exports.content(w.exporter, id, q.getValue("exp").toLong(), q.getValue("sig"))
    }

    @Test
    fun nur_gebuchte_vollstaendig_freigegebene_journale_beleg_ohne_zweite_freigabe_erscheint_nicht() {
        val w = LedgerWorld(bf); w.period(sep, oct)
        bf.svc.policies.update(w.fin, UpdateFinancePolicy(approvalSecondThresholdMinor = 10000))
        val (waiting, out) = w.approvedByWorkflow(11900)   // über der Schwelle: wartet auf die zweite Person, kein Posting, kein Journal
        assertEquals(ExpenseStates.WAITING_SECOND_APPROVAL, out.state)
        val (single, _) = w.approvedByWorkflow(9000)       // unter der Schwelle: gebucht
        val forced = w.approvedDocument(gross = 5000); w.post(forced)   // gebucht, aber ohne Freigabeschritte (Zustand per Test gesetzt)
        assertEquals(2, w.journals())
        w.request(); w.runAll()
        val batch = bf.svc.exports.list(w.exporter, w.w.legalEntity, 10, null).single()
        assertEquals(1, batch.journalCount, "nur das vollständig freigegebene Journal des Belegs unter der Schwelle")
        assertEquals(listOf(single), bf.svc.exports.lines(w.exporter, batch.id).map { it.documentId })
        assertEquals(ExportState.NOT_EXPORTED, bf.svc.expenses.get(w.fin, waiting).exportState); assertEquals(ExportState.NOT_EXPORTED, bf.svc.expenses.get(w.fin, forced).exportState)
        // nach der zweiten Freigabe durch eine dritte Person erscheint der Beleg im nächsten Batch
        val posted = bf.svc.approvals.approve(w.secondApprover, waiting, bf.svc.expenses.get(w.fin, waiting).document.revision, "Zweite Freigabe")
        assertEquals(ExpenseStates.POSTED, posted.state)
        w.request(); w.runAll()
        val second = bf.svc.exports.list(w.exporter, w.w.legalEntity, 10, null).last()
        assertEquals(listOf(waiting), bf.svc.exports.lines(w.exporter, second.id).map { it.documentId })
    }

    @Test
    fun export_braucht_eigenes_recht_fremden_rechtstraeger_und_nichtleeren_zeitraum() {
        val w = world(1)
        val approverOnly = w.person(setOf("finance"), setOf("expenses:approve", "expenses:review", "receipts:read", "receipts:write", "costs:read", "costs:allocate", "periods:close"))
        val reader = w.person(setOf("finance"), setOf("ledger:read")); val driver = w.person(setOf("driver"), setOf("ledger:export", "ledger:read")); val service = w.person(emptySet(), setOf("ledger:export"), ActorType.SERVICE)
        for (c in listOf(approverOnly, reader, driver)) assertEquals(ErrorCode.FORBIDDEN, problem { w.request(by = c) }.code)
        assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.ledger_export WHERE tenant_id=?", w.w.tenant.value), "ohne Recht entsteht kein Auftrag")
        // Freigaberechte allein genügen nie; ein Dienstkonto mit dem Exportrecht darf
        assertTrue(w.request(by = service) is ExportRequestOutcome.Queued); w.runAll()
        val view = bf.svc.exports.list(service.let { w.person(emptySet(), setOf("ledger:read"), ActorType.SERVICE) }, null, 10, null).single()
        assertEquals(ErrorCode.FORBIDDEN, problem { bf.svc.exports.get(approverOnly, view.id) }.code); assertEquals(ErrorCode.FORBIDDEN, problem { bf.svc.exports.confirm(reader, view.id, ConfirmLedgerImport(emptyList())) }.code)
        assertEquals(ErrorCode.FORBIDDEN, problem { bf.svc.exports.downloadLink(reader, view.id) }.code)
        // fremder Rechtsträger und fremder Mandant: nicht auffindbar; leerer Zeitraum: 409; Eingaben
        assertEquals(ErrorCode.NOT_FOUND, problem { bf.svc.exports.request(w.exporter, RequestLedgerExport(UUID.randomUUID(), sep, oct)) }.code)
        val other = world(1); assertEquals(ErrorCode.NOT_FOUND, problem { bf.svc.exports.request(w.exporter, RequestLedgerExport(other.w.legalEntity, sep, oct)) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { bf.svc.exports.get(other.exporter, view.id) }.code, "Batch eines anderen Mandanten")
        assertEquals(ErrorCode.CONFLICT, problem { w.request(LocalDate.of(2027, 5, 1), LocalDate.of(2027, 6, 1)) }.code, "leerer Zeitraum")
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { w.request(oct, sep) }.code); assertEquals(ErrorCode.VALIDATION_FAILED, problem { bf.svc.exports.request(w.exporter, RequestLedgerExport(w.w.legalEntity, sep, oct, "DATEV")) }.code)
        // ein Batch enthält nur Journale des Rechtsträgers und des aktiven Mandanten
        assertEquals(1, view.journalCount); assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.ledger_export_line l JOIN taktovia.journal j ON j.id=l.journal_id WHERE l.export_id=? AND j.legal_entity_id<>?", view.id, w.w.legalEntity))
    }

    @Test
    fun workerabsturz_fuehrt_nach_wiederanlauf_zu_keinem_zweiten_batch() {
        val w = world(2); val queued = w.request()
        // Worker A beansprucht den Auftrag (Lease 300 ms) und stürzt ab: er führt nichts aus
        val a = runner("worker-a", Duration.ofMillis(300)); val claimed = a.claimNext(w.w.tenant)!!
        assertEquals(0, a.runNext(w.w.tenant)?.let { 1 } ?: 0, "solange die Lease gilt, nimmt kein anderer Worker den Auftrag")
        Thread.sleep(450)
        // Worker B übernimmt nach Ablauf der Lease (höherer Fencing-Token) und führt den Auftrag zu Ende
        val b = runner("worker-b"); val result = b.runNext(w.w.tenant)!!
        assertEquals(false, result.leaseLost); assertEquals(LedgerExportState.PROVIDED, bf.svc.exports.get(w.exporter, queued.export.id).state)
        // der abgestürzte Worker wacht auf: mit dem alten Token schreibt er nichts
        val late = a.execute(claimed); assertTrue(late.leaseLost, late.toString())
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.ledger_export WHERE tenant_id=?", w.w.tenant.value)); assertEquals(2, lineCount(w))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='ledger_export.provided'", w.w.tenant.value))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.ledger_export.provided'", w.w.tenant.value))
        // ein weiterer Lauf findet nichts mehr
        assertEquals(0, runner("worker-c").runTenant(w.w.tenant))
    }

    @Test
    fun parallele_exportlaeufe_ueberschneidender_zeitraeume_kein_journal_doppelt() {
        repeat(3) {
            val w = world(6); val a = w.request(sep, oct); val b = w.request(sep, LocalDate.of(2026, 10, 2)); val c = w.request(LocalDate.of(2026, 9, 10), oct)
            assertEquals(3, setOf(a.export.id, b.export.id, c.export.id).size, "drei verschiedene Umfänge, drei Aufträge")
            val pool = Executors.newFixedThreadPool(3); val go = CountDownLatch(1)
            val fs = (1..3).map { i -> pool.submit<Int> { go.await(); runner("worker-$i").runTenant(w.w.tenant) } }
            go.countDown(); fs.forEach { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            val exports = bf.svc.exports.list(w.exporter, null, 10, null)
            assertEquals(3, exports.size); assertTrue(exports.all { it.state in setOf(LedgerExportState.PROVIDED, LedgerExportState.EMPTY) }, exports.map { it.state }.toString())
            assertEquals(6, lineCount(w), "jedes Journal in genau einem Batch")
            assertEquals(6, exports.sumOf { it.journalCount ?: 0 })
            assertEquals(0, bf.fx.count("SELECT count(*) FROM (SELECT journal_id FROM taktovia.ledger_export_line WHERE tenant_id=? GROUP BY journal_id HAVING count(*) > 1) x", w.w.tenant.value))
        }
    }

    @Test
    fun exportstatus_am_beleg_nur_durch_den_exportjob_und_bestaetigung_mit_import_ids() {
        val w = world(2); val docs = bf.fx.strings("SELECT id::text FROM taktovia.expense_document WHERE tenant_id=? ORDER BY id", w.w.tenant.value).map(UUID::fromString)
        assertTrue(docs.all { bf.svc.expenses.get(w.fin, it).exportState == ExportState.NOT_EXPORTED })
        // auch ein Finanzakteur setzt den Status an der Anwendung vorbei nicht (Schalter nur im Exportjob)
        assertTrue(runCatching { bf.fx.tx.run(w.w.tenant) { j -> j.update("UPDATE taktovia.expense_document SET export_state='EXPORTED', exported_at=now(), exported_by='user:x' WHERE id=?", docs[0]) } }.isFailure)
        val q = w.request(); w.runAll()
        assertTrue(docs.all { bf.svc.expenses.get(w.fin, it).exportState == ExportState.EXPORTED }, "bereitgestellt")
        // Bestätigung: alle Journale genau einmal; unvollständig, doppelt und falsche Import-ID sind 422, nichts wird verändert
        val lines = bf.svc.exports.lines(w.exporter, q.export.id)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { bf.svc.exports.confirm(w.exporter, q.export.id, ConfirmLedgerImport(listOf(ImportConfirmation(lines[0].journalId, "ERP-1")))) }.code, "unvollständig")
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { bf.svc.exports.confirm(w.exporter, q.export.id, ConfirmLedgerImport(lines.map { ImportConfirmation(it.journalId, "ERP-SAME") })) }.code, "doppelte Import-ID")
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { bf.svc.exports.confirm(w.exporter, q.export.id, ConfirmLedgerImport(lines.map { ImportConfirmation(it.journalId, "keine gültige ID!") })) }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { bf.svc.exports.confirm(w.exporter, q.export.id, ConfirmLedgerImport(lines.map { ImportConfirmation(UUID.randomUUID(), "ERP-X") })) }.code, "fremde Journale")
        assertEquals(LedgerExportState.PROVIDED, bf.svc.exports.get(w.exporter, q.export.id).state)
        val entries = lines.mapIndexed { i, l -> ImportConfirmation(l.journalId, "ERP-IMPORT-${i + 1}") }
        val confirmed = bf.svc.exports.confirm(w.exporter, q.export.id, ConfirmLedgerImport(entries))
        assertEquals(LedgerExportState.CONFIRMED, confirmed.state); assertNotEquals(null, confirmed.confirmedAt)
        assertEquals(entries.map { it.journalId to it.importRef }, bf.svc.exports.lines(w.exporter, q.export.id).map { it.journalId to it.importRef })
        assertTrue(docs.all { bf.svc.expenses.get(w.fin, it).exportState == ExportState.CONFIRMED }, "bestätigt")
        assertEquals(2, bf.fx.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=? AND export_state='CONFIRMED' AND export_confirmed_at IS NOT NULL", w.w.tenant.value))
        // wiederholt: unverändert; abweichend: 409
        assertEquals(confirmed, bf.svc.exports.confirm(w.exporter, q.export.id, ConfirmLedgerImport(entries)))
        assertEquals(ErrorCode.CONFLICT, problem { bf.svc.exports.confirm(w.exporter, q.export.id, ConfirmLedgerImport(entries.map { ImportConfirmation(it.journalId, it.importRef + "X") })) }.code)
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.ledger_export.confirmed'", w.w.tenant.value))
        // ein nicht bereitgestellter Batch ist nicht bestätigbar
        val w2 = world(1); val pending = w2.request()
        assertEquals(ErrorCode.CONFLICT, problem { bf.svc.exports.confirm(w2.exporter, pending.export.id, ConfirmLedgerImport(listOf(ImportConfirmation(UUID.randomUUID(), "ERP-1")))) }.code)
        assertEquals(ErrorCode.CONFLICT, problem { bf.svc.exports.downloadLink(w2.exporter, pending.export.id) }.code)
    }

    @Test
    fun download_ueber_kurzlebige_mandantengebundene_adresse_audit_und_ereignisse() {
        val w = world(2); val q = w.request(); w.runAll()
        val link = bf.svc.exports.downloadLink(w.exporter, q.export.id)
        assertTrue(link.url.startsWith("/v1/ledger/exports/${q.export.id}/content?exp=")); assertTrue(link.expiresAt.isBefore(java.time.Instant.now().plusSeconds(61)))
        val got = download(w, q.export.id, link.url); assertEquals("text/plain", got.mediaType)
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.ledger_export.downloaded' AND object_ref=?", w.w.tenant.value, "ledger_export:${q.export.id}"))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.ledger_export.created'", w.w.tenant.value))
        val q2 = link.url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        // manipulierte Signatur, anderer Mandant und anderer Batch: nicht von „nicht vorhanden“ zu unterscheiden
        assertEquals(ErrorCode.NOT_FOUND, problem { bf.svc.exports.content(w.exporter, q.export.id, q2.getValue("exp").toLong(), q2.getValue("sig").reversed()) }.code)
        val foreign = world(1)
        assertEquals(ErrorCode.NOT_FOUND, problem { bf.svc.exports.content(foreign.exporter, q.export.id, q2.getValue("exp").toLong(), q2.getValue("sig")) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { bf.svc.exports.content(w.exporter, UUID.randomUUID(), q2.getValue("exp").toLong(), q2.getValue("sig")) }.code)
        assertEquals(ErrorCode.FORBIDDEN, problem { bf.svc.exports.content(w.person(setOf("finance"), setOf("ledger:read")), q.export.id, q2.getValue("exp").toLong(), q2.getValue("sig")) }.code)
        // kurzlebig: nach Ablauf (Gültigkeit hier 0,7 s) ist die Adresse tot
        Thread.sleep(1900)
        assertEquals(ErrorCode.NOT_FOUND, problem { bf.svc.exports.content(w.exporter, q.export.id, q2.getValue("exp").toLong(), q2.getValue("sig")) }.code)
        // Ereignisse gegen das Schema; Bestätigung erzeugt ihr eigenes Ereignis
        val lines = bf.svc.exports.lines(w.exporter, q.export.id); bf.svc.exports.confirm(w.exporter, q.export.id, ConfirmLedgerImport(lines.mapIndexed { i, l -> ImportConfirmation(l.journalId, "ERP-$i") }))
        val repo = Path.of(System.getProperty("taktovia.repoRoot")); val registry = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012())
        for ((type, schemaFile) in listOf("ledger_export.provided" to "ledger_export.provided.v1.schema.json", "ledger_export.confirmed" to "ledger_export.confirmed.v1.schema.json")) {
            val schema = registry.getSchema(Files.readString(repo.resolve("api/events/$schemaFile")))
            val payloads = bf.fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND event_type=?", w.w.tenant.value, type); assertEquals(1, payloads.size, type)
            payloads.forEach { assertEquals(emptyList<Any>(), schema.validate(it, InputFormat.JSON).toList().map { m -> m.message }, it) }
        }
    }

    @Test
    fun ohne_zielformat_endet_der_auftrag_als_failed_und_blockiert_den_umfang_nicht() {
        BelegFixture(InMemoryObjectStore()).use { plain ->
            val w = LedgerWorld(plain); w.period(sep, oct); w.approvedByWorkflow()
            val ex = w.person(setOf("finance"), setOf("ledger:export", "ledger:read"))
            val q = plain.svc.exports.request(ex, RequestLedgerExport(w.w.legalEntity, sep, oct))
            OperationRunner(plain.fx.tx, listOf(plain.svc.exports.operationHandler(plain.fx.tx)), Outbox(), Clock.systemUTC(), OperationConfig(), "w").runTenant(w.w.tenant)
            val v = plain.svc.exports.get(ex, q.export.id); assertEquals(LedgerExportState.FAILED, v.state); assertEquals("NO_EXPORT_FORMAT", v.resultCode)
            assertEquals(0, plain.fx.count("SELECT count(*) FROM taktovia.ledger_export_line WHERE tenant_id=?", w.w.tenant.value))
            assertTrue(plain.svc.exports.request(ex, RequestLedgerExport(w.w.legalEntity, sep, oct)) is ExportRequestOutcome.Queued, "ein neuer Auftrag ist möglich")
        }
    }

    @Test
    fun gegenereignis_wird_in_einem_spaeteren_batch_exportiert() {
        val w = world(1); w.request(); w.runAll()
        val first = bf.svc.exports.list(w.exporter, null, 10, null).single()
        val orig = bf.fx.strings("SELECT id::text FROM taktovia.economic_event WHERE tenant_id=?", w.w.tenant.value).single().let(UUID::fromString)
        bf.svc.corrections.reverse(w.fin, orig, de.taktovia.documentsfinance.api.ReverseCostEvent("Gutschrift", creditGrossMinor = 2000, accountingDate = LocalDate.of(2026, 10, 5)))
        val q = w.request(oct, LocalDate.of(2026, 11, 1)); assertTrue(q is ExportRequestOutcome.Queued); w.runAll()
        val second = bf.svc.exports.get(w.exporter, q.export.id)
        assertEquals(1, second.journalCount); assertNotEquals(first.id, second.id)
        // der Beleg des Gegenereignisses ist derselbe: sein Exportstatus bleibt „bereitgestellt“, der erste Batch unverändert
        assertEquals(first, bf.svc.exports.get(w.exporter, first.id)); assertEquals(2, lineCount(w))
    }
}
