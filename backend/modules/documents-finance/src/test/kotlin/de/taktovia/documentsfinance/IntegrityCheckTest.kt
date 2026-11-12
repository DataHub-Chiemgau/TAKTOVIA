package de.taktovia.documentsfinance

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.documentsfinance.api.IntegrityFailure
import de.taktovia.documentsfinance.api.IntegrityState
import de.taktovia.documentsfinance.api.IntegrityTrigger
import de.taktovia.documentsfinance.api.ReleaseBlocker
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.storage.FileObjectStore
import de.taktovia.platform.storage.ObjectKey
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-24 (DOC-008): ein im Objektspeicher manipuliertes oder fehlendes Original wird erkannt und als Vorfall protokolliert; ein Beleg mit Verletzung ist nicht freigabefähig und
 * wird nie gebucht; unveränderte Originale bestehen wiederholt ohne Vorfall; der Prüfjob ist fortsetzbar und läuft im Tenantkontext. Echte Datenbank unter der Runtime-Rolle,
 * Manipulation direkt im (ungeschützten) Dateisystem-Objektspeicher.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntegrityCheckTest {
    private lateinit var bf: BelegFixture
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val registry = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012())

    @BeforeAll fun start(@TempDir dir: Path) { bf = BelegFixture(FileObjectStore(dir)) }
    @AfterAll fun stop() { bf.close() }

    private fun key(w: UploadFixture.World, id: UUID) = ObjectKey(w.tenant, bf.fx.strings("SELECT object_key FROM taktovia.expense_document WHERE id=?", id).single())
    private fun tamper(w: UploadFixture.World, id: UUID) { val k = key(w, id); bf.store.put(k, bf.store.get(k)!! + byteArrayOf(1, 2, 3), "application/pdf") }
    private fun postable(w: UploadFixture.World, id: UUID) = bf.fx.ctxTx.run(bf.serviceCtx(w.tenant)) { j -> bf.svc.integrity.requirePostable(j, id) }

    @Test
    fun manipuliertes_original_wird_erkannt_und_protokolliert() {
        val w = bf.fx.world(); val fin = bf.financeCtx(w); val d = bf.upload(w, fin, Samples.pdf("echt", 700))
        assertEquals(IntegrityState.VERIFIED, bf.svc.integrity.check(fin, d.documentId, IntegrityTrigger.MANUAL).state)
        tamper(w, d.documentId)
        val r = bf.svc.integrity.check(fin, d.documentId, IntegrityTrigger.SCHEDULED)
        assertEquals(IntegrityState.VIOLATED, r.state); assertEquals(IntegrityFailure.HASH_MISMATCH, r.failure); assertNotNull(r.incidentId)
        // Vorfall mit erwartetem und tatsächlichem Hash, Prüfanlass; Sicherheitsereignis; Ereignis ohne Hashwerte
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.document_integrity_incident WHERE document_id=? AND failure='HASH_MISMATCH' AND trigger_kind='SCHEDULED' AND expected_sha256=? AND actual_sha256 <> expected_sha256", d.documentId, d.sha256))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='security.document.integrity_violated' AND object_ref=?", w.tenant.value, "document:${d.documentId}"))
        val payload = bf.fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND event_type='document.integrity_violated' AND aggregate_id=?", w.tenant.value, d.documentId).single()
        val schema = registry.getSchema(Files.readString(repo.resolve("api/events/document.integrity_violated.v1.schema.json")))
        assertEquals(emptyList<Any>(), schema.validate(payload, InputFormat.JSON).toList().map { it.message }, payload)
        // der Beleg ist gesperrt: keine Auslieferung, keine Freigabe, kein Posting, nicht freigabefähig
        val view = bf.svc.expenses.get(fin, d.documentId)
        assertEquals(IntegrityState.VIOLATED, view.document.integrity); assertTrue(ReleaseBlocker.INTEGRITY_VIOLATION in view.blockers); assertFalse(view.document.approvable)
        assertEquals(ErrorCode.DOCUMENT_INTEGRITY_VIOLATION, bf.problem { bf.svc.expenses.original(fin, d.documentId) }.code)
        assertEquals(ErrorCode.DOCUMENT_INTEGRITY_VIOLATION, bf.problem { bf.svc.integrity.clearForApproval(fin, d.documentId, 1) }.code)
        assertEquals(ErrorCode.DOCUMENT_INTEGRITY_VIOLATION, bf.problem { postable(w, d.documentId) }.code)
        // endgültig: weitere Prüfungen erzeugen keinen zweiten Vorfall; die Datenbank verweigert die Rücknahme
        assertEquals(IntegrityState.VIOLATED, bf.svc.integrity.check(fin, d.documentId).state)
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.document_integrity_incident WHERE document_id=?", d.documentId))
        org.junit.jupiter.api.assertThrows<Exception> { bf.fx.exec("UPDATE taktovia.expense_document SET integrity_state='VERIFIED' WHERE id=?", d.documentId) }
    }

    @Test
    fun fehlendes_objekt_wird_erkannt_und_die_auslieferung_loest_den_vorfall_aus() {
        val w = bf.fx.world(); val fin = bf.financeCtx(w)
        val gone = bf.upload(w, fin, Samples.pdf("weg"))
        bf.store.delete(key(w, gone.documentId))
        val r = bf.svc.integrity.check(fin, gone.documentId, IntegrityTrigger.MANUAL)
        assertEquals(IntegrityFailure.OBJECT_MISSING, r.failure)
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.document_integrity_incident WHERE document_id=? AND failure='OBJECT_MISSING' AND actual_sha256 IS NULL", gone.documentId))
        // Auslieferung: Abweichung wird nie ausgeliefert, sondern als Vorfall mit Anlass DELIVERY festgehalten
        val bad = bf.upload(w, fin, Samples.pdf("manipuliert"))
        tamper(w, bad.documentId)
        assertEquals(ErrorCode.DOCUMENT_INTEGRITY_VIOLATION, bf.problem { bf.svc.uploads.openOriginal(fin, bad.documentId) }.code)
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.document_integrity_incident WHERE document_id=? AND trigger_kind='DELIVERY'", bad.documentId))
        assertEquals("VIOLATED", bf.fx.strings("SELECT integrity_state FROM taktovia.expense_document WHERE id=?", bad.documentId).single())
    }

    @Test
    fun unveraenderte_originale_bestehen_wiederholt_ohne_vorfall_und_der_pruefjob_ist_fortsetzbar() {
        val w = bf.fx.world(); val fin = bf.financeCtx(w); val svc = bf.serviceCtx(w.tenant)
        val docs = (1..5).map { bf.upload(w, fin, Samples.pdf("beleg $it")) }
        repeat(3) { for (d in docs) assertEquals(IntegrityState.VERIFIED, bf.svc.integrity.check(fin, d.documentId).state) }
        assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.document_integrity_incident WHERE tenant_id=?", w.tenant.value))
        // Prüfjob in Stücken: 2 + 2 + 1, danach fertig
        tamper(w, docs[3].documentId)
        val first = bf.svc.integrity.sweep(svc, 2); assertEquals(2, first.checked); assertNotNull(first.nextCursor)
        val second = bf.svc.integrity.sweep(svc, 2, first.nextCursor); assertEquals(2, second.checked); assertNotNull(second.nextCursor)
        val third = bf.svc.integrity.sweep(svc, 2, second.nextCursor); assertEquals(1, third.checked); assertNull(third.nextCursor)
        assertEquals(1, first.violations + second.violations + third.violations, "genau der manipulierte Beleg")
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.document_integrity_incident WHERE tenant_id=? AND trigger_kind='SCHEDULED'", w.tenant.value))
        // ein weiterer Lauf überspringt den verletzten Beleg und findet nichts Neues
        val again = bf.svc.integrity.sweep(svc, 50); assertEquals(4, again.checked); assertEquals(0, again.violations)
    }

    @Test
    fun pruefung_laeuft_nur_im_tenantkontext_und_beruehrt_fremde_objekte_nicht() {
        val a = bf.fx.world(); val b = bf.fx.world()
        val da = bf.upload(a, bf.financeCtx(a), Samples.pdf("mandant a")); val db = bf.upload(b, bf.financeCtx(b), Samples.pdf("mandant b"))
        tamper(b, db.documentId)
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.integrity.check(bf.financeCtx(a), db.documentId) }.code, "fremder Mandant: 404")
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.integrity.check(bf.serviceCtx(a.tenant), db.documentId) }.code)
        val sweepA = bf.svc.integrity.sweep(bf.serviceCtx(a.tenant), 100)
        assertEquals(1, sweepA.checked); assertEquals(0, sweepA.violations)
        assertEquals("UNCHECKED", bf.fx.strings("SELECT integrity_state FROM taktovia.expense_document WHERE id=?", db.documentId).single(), "der Beleg des Mandanten B wurde nicht angefasst")
        assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.document_integrity_incident WHERE tenant_id=?", b.tenant.value))
        assertEquals(IntegrityState.VERIFIED, bf.svc.integrity.check(bf.financeCtx(a), da.documentId).state)
    }

    @Test
    fun freigabe_parallel_zur_verletzung_bucht_nicht() {
        val pool = Executors.newFixedThreadPool(2); var cleared = 0; var rejected = 0
        try {
            repeat(25) {
                val w = bf.fx.world(); val fin = bf.financeCtx(w); val d = bf.upload(w, fin, Samples.pdf("rennen $it", 400))
                val barrier = CyclicBarrier(2)
                val violation = pool.submit<Any> { barrier.await(); tamper(w, d.documentId); bf.svc.integrity.check(fin, d.documentId, IntegrityTrigger.SCHEDULED) }
                val approval = pool.submit<Any> { barrier.await(); try { bf.svc.integrity.clearForApproval(fin, d.documentId, 1) } catch (e: ProblemException) { e } }
                violation.get(60, TimeUnit.SECONDS); val a = approval.get(60, TimeUnit.SECONDS)
                if (a is ProblemException) { assertEquals(ErrorCode.DOCUMENT_INTEGRITY_VIOLATION, a.code); rejected++ } else cleared++
                // in jedem Ausgang: der Vorfall steht, der Beleg ist gesperrt und im Posting nicht zulässig
                assertEquals("VIOLATED", bf.fx.strings("SELECT integrity_state FROM taktovia.expense_document WHERE id=?", d.documentId).single())
                assertEquals(ErrorCode.DOCUMENT_INTEGRITY_VIOLATION, bf.problem { postable(w, d.documentId) }.code, "kein Posting auf verletztem Original")
                assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.document_integrity_incident WHERE document_id=?", d.documentId))
                assertFalse(bf.svc.expenses.get(fin, d.documentId).document.approvable)
            }
        } finally { pool.shutdown() }
        assertEquals(25, cleared + rejected)
    }
}
