package de.taktovia.documentsfinance

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.documentsfinance.api.DocumentState
import de.taktovia.documentsfinance.api.EicarSignatureScanner
import de.taktovia.documentsfinance.api.RequestUploadTicket
import de.taktovia.documentsfinance.api.ScanResult
import de.taktovia.documentsfinance.api.TicketState
import de.taktovia.documentsfinance.api.UnavailableScanner
import de.taktovia.documentsfinance.api.UploadPolicy
import de.taktovia.documentsfinance.api.UploadPurpose
import de.taktovia.documentsfinance.api.Uploads
import de.taktovia.documentsfinance.api.MalwareScanner
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.storage.FileObjectStore
import de.taktovia.platform.storage.ObjectKey
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
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
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-21 (Uploadticket, Quarantäne, Malware-Prüfung) gegen die echte Datenbank (Runtime-Rolle) und einen Dateisystem-Objektspeicher: Positiv (JPEG, PNG, PDF), Negativ
 * (Malware, falscher MIME, falsche Größe/Hash, abgelaufenes und verbrauchtes Ticket, fremder Mandant, fehlende Rechte), Konkurrierend (parallele Abschlüsse desselben Tickets).
 * Prüfer: die EICAR-Testsignatur ([EicarSignatureScanner]); der clamd-Anschluss ist in `ClamdScannerTest` gegen einen Testserver geprüft.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UploadTicketTest {
    private lateinit var fx: UploadFixture
    private lateinit var up: Uploads
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val registry = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012())

    @BeforeAll fun start(@TempDir dir: Path) { fx = UploadFixture(FileObjectStore(dir)); up = fx.uploads(EicarSignatureScanner()) }
    @AfterAll fun stop() { fx.close() }

    private fun problem(block: () -> Unit): ProblemException = try { block(); throw AssertionError("ProblemException erwartet") } catch (e: ProblemException) { e }

    /** Ticket anfordern und die Datei (wie der Client per vorsigniertem Upload) in die Quarantäne legen. */
    private fun ticket(w: UploadFixture.World, ctx: de.taktovia.platform.context.RequestContext, bytes: ByteArray, mime: String, uploaded: ByteArray? = bytes, declaredHash: String = Samples.sha256(bytes),
                       declaredSize: Long = bytes.size.toLong(), purpose: UploadPurpose = UploadPurpose.RECEIPT, uploads: Uploads = up): de.taktovia.documentsfinance.api.UploadTicketView {
        val t = uploads.requestTicket(ctx, RequestUploadTicket(w.legalEntity, purpose, mime, declaredSize, declaredHash))
        if (uploaded != null) fx.store.put(ObjectKey(w.tenant, t.uploadKey), uploaded, mime)
        return t
    }

    @Test
    fun upload_jpeg_png_und_pdf_wird_original_nach_bestandener_pruefung() {
        val w = fx.world(); val ctx = fx.ctx(w.tenant)
        for ((bytes, mime) in listOf(Samples.jpeg() to "image/jpeg", Samples.png() to "image/png", Samples.pdf() to "application/pdf")) {
            val t = ticket(w, ctx, bytes, mime)
            assertEquals(TicketState.ISSUED, t.state); assertEquals(1, t.revision); assertEquals(64 * 1024, t.maxBytes)
            assertTrue(t.uploadKey.startsWith("receipts/quarantine/"))
            val d = up.complete(ctx, t.ticketId, t.revision)
            assertEquals(DocumentState.STORED, d.state); assertEquals(mime, d.mime); assertEquals(bytes.size.toLong(), d.sizeBytes)
            assertEquals(Samples.sha256(bytes), d.sha256, "gespeicherter Hash = serverseitig berechneter Hash der Datei")
            assertEquals(Samples.sha256(bytes), fx.strings("SELECT original_sha256 FROM taktovia.expense_document WHERE id=?", d.documentId).single())
            assertNotNull(d.scannedAt)
            assertArrayEquals(bytes, up.openOriginal(ctx, d.documentId), "Original ausgeliefert")
            assertNull(fx.store.get(ObjectKey(w.tenant, t.uploadKey)), "Quarantänekopie entfernt")
            assertEquals("tenants/${w.tenant.value}/receipts/originals/${d.documentId}", "tenants/${w.tenant.value}/" + fx.strings("SELECT object_key FROM taktovia.expense_document WHERE id=?", d.documentId).single(), "mandantengetrenntes Präfix")
            assertEquals(TicketState.CONSUMED.name, fx.strings("SELECT state FROM taktovia.upload_ticket WHERE id=?", t.ticketId).single())
            assertEquals("eicar-signature", fx.strings("SELECT scan_engine FROM taktovia.expense_document WHERE id=?", d.documentId).single())
        }
        // Outbox-Ereignisse gegen die Schemas
        val accepted = fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND event_type='document.upload_accepted'", w.tenant.value)
        val passed = fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND event_type='document.quarantine_passed'", w.tenant.value)
        assertEquals(3, accepted.size); assertEquals(3, passed.size)
        for ((type, payloads) in listOf("document.upload_accepted" to accepted, "document.quarantine_passed" to passed)) {
            val schema = registry.getSchema(Files.readString(repo.resolve("api/events/$type.v1.schema.json")))
            payloads.forEach { assertEquals(emptyList<Any>(), schema.validate(it, InputFormat.JSON).toList().map { m -> m.message }, "$type: $it") }
        }
    }

    @Test
    fun malware_bleibt_in_quarantaene_wird_nicht_ausgeliefert_und_erzeugt_ein_sicherheitsereignis() {
        val w = fx.world(); val ctx = fx.ctx(w.tenant)
        val infected = Samples.pdf(EicarSignatureScanner.EICAR)
        val t = ticket(w, ctx, infected, "application/pdf")
        val d = up.complete(ctx, t.ticketId, t.revision)
        assertEquals(DocumentState.QUARANTINE_REJECTED, d.state)
        assertNotNull(fx.store.get(ObjectKey(w.tenant, t.uploadKey)), "bleibt in Quarantäne")
        assertEquals(emptyList<String>(), fx.store.list(w.tenant, "receipts/originals/"), "kein Original entsteht")
        assertEquals(404, problem { up.openOriginal(ctx, d.documentId) }.code.status, "wird nicht ausgeliefert")
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='security.document.malware_detected' AND object_ref=?", w.tenant.value, "document:${d.documentId}"), "Sicherheitsereignis")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='document.quarantine_passed'", w.tenant.value), "nie an OCR/Weiterverarbeitung übergeben")
        val rejected = fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND event_type='document.quarantine_rejected'", w.tenant.value).single()
        val schema = registry.getSchema(Files.readString(repo.resolve("api/events/document.quarantine_rejected.v1.schema.json")))
        assertEquals(emptyList<Any>(), schema.validate(rejected, InputFormat.JSON).toList().map { it.message })
        assertFalse(fx.strings("SELECT redacted_changes::text FROM taktovia.audit_event WHERE tenant_id=? AND action='security.document.malware_detected'", w.tenant.value).single().contains("Eicar"), "Sicherheitsereignis ohne Signaturname")
        // endgültig: Wiederholung der Prüfung ändert nichts
        assertEquals(DocumentState.QUARANTINE_REJECTED, up.rescan(ctx, d.documentId).state)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='security.document.malware_detected'", w.tenant.value))
        assertEquals("QUARANTINE_REJECTED", fx.strings("SELECT state FROM taktovia.expense_document WHERE id=?", d.documentId).single())
    }

    @Test
    fun falscher_mime_falsche_groesse_falscher_hash_und_fehlendes_objekt_werden_abgewiesen() {
        val w = fx.world(); val ctx = fx.ctx(w.tenant); val pdf = Samples.pdf()
        // deklariert JPEG, tatsächlich PDF
        val t1 = ticket(w, ctx, pdf, "image/jpeg", declaredHash = Samples.sha256(pdf))
        assertEquals("/mime", problem { up.complete(ctx, t1.ticketId, 1) }.violations.single().pointer)
        assertEquals("MIME_MISMATCH", fx.strings("SELECT reject_reason FROM taktovia.upload_ticket WHERE id=?", t1.ticketId).single())
        assertNull(fx.store.get(ObjectKey(w.tenant, t1.uploadKey)), "nicht passendes Objekt wird nicht aufbewahrt")
        // abweichender Hash
        val jpg = Samples.jpeg(); val t2 = ticket(w, ctx, jpg, "image/jpeg", declaredHash = Samples.sha256(Samples.jpeg(seed = 9)))
        assertEquals("/sha256", problem { up.complete(ctx, t2.ticketId, 1) }.violations.single().pointer)
        // abweichende Größe (Datei ist größer als angekündigt)
        val t3 = ticket(w, ctx, jpg, "image/jpeg", declaredSize = jpg.size - 1L)
        assertEquals("/size_bytes", problem { up.complete(ctx, t3.ticketId, 1) }.violations.single().pointer)
        // Objekt nie hochgeladen
        val t4 = ticket(w, ctx, jpg, "image/jpeg", uploaded = null)
        assertEquals("/ticket", problem { up.complete(ctx, t4.ticketId, 1) }.violations.single().pointer)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=?", w.tenant.value), "in keinem Fall entsteht ein Dokument")
        // ein abgewiesenes Ticket ist verbraucht
        assertEquals(ErrorCode.CONFLICT, problem { up.complete(ctx, t1.ticketId, 1) }.code)
        assertEquals(setOf("MIME_MISMATCH", "HASH_MISMATCH", "SIZE_MISMATCH", "OBJECT_MISSING"), fx.strings("SELECT reject_reason FROM taktovia.upload_ticket WHERE tenant_id=?", w.tenant.value).toSet())
    }

    @Test
    fun ticketanforderung_prueft_typ_groesse_hash_und_rechtstraeger() {
        val w = fx.world(); val ctx = fx.ctx(w.tenant)
        fun req(mime: String = "image/jpeg", size: Long = 100, sha: String = "a".repeat(64), le: UUID = w.legalEntity) = RequestUploadTicket(le, UploadPurpose.RECEIPT, mime, size, sha)
        assertEquals("/mime", problem { up.requestTicket(ctx, req(mime = "application/x-msdownload")) }.violations.single().pointer)
        assertEquals("/size_bytes", problem { up.requestTicket(ctx, req(size = 64 * 1024 + 1)) }.violations.single().pointer, "übergroße Datei")
        assertEquals("/size_bytes", problem { up.requestTicket(ctx, req(size = 0)) }.violations.single().pointer)
        assertEquals("/sha256", problem { up.requestTicket(ctx, req(sha = "ABC")) }.violations.single().pointer)
        assertEquals(404, problem { up.requestTicket(ctx, req(le = UUID.randomUUID())) }.code.status)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.upload_ticket WHERE tenant_id=?", w.tenant.value))
        assertEquals(403, problem { up.requestTicket(fx.ctx(w.tenant, scopes = emptySet()), req()) }.code.status, "ohne Funktionsscope")
        assertEquals(403, problem { up.complete(fx.ctx(w.tenant, scopes = setOf("receipts:read")), UUID.randomUUID(), 1) }.code.status)
    }

    @Test
    fun abgelaufenes_und_verbrauchtes_ticket_ist_nicht_wiederverwendbar() {
        val w = fx.world(); val ctx = fx.ctx(w.tenant); val short = fx.uploads(EicarSignatureScanner(), UploadPolicy(maxBytes = 64 * 1024, ticketTtl = Duration.ofSeconds(1)))
        val expired = ticket(w, ctx, Samples.jpeg(), "image/jpeg", uploads = short)
        Thread.sleep(1500)
        val e = problem { short.complete(ctx, expired.ticketId, 1) }
        assertEquals(ErrorCode.CONFLICT, e.code); assertTrue(e.detail!!.contains("abgelaufen"), e.detail)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=?", w.tenant.value))
        // verbraucht
        val t = ticket(w, ctx, Samples.jpeg(seed = 3), "image/jpeg"); up.complete(ctx, t.ticketId, 1)
        val again = problem { up.complete(ctx, t.ticketId, 1) }
        assertEquals(ErrorCode.CONFLICT, again.code); assertTrue(again.detail!!.contains("verbraucht"))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=?", w.tenant.value))
        // veraltete Revision: 412, das Ticket bleibt benutzbar
        val t2 = ticket(w, ctx, Samples.jpeg(seed = 4), "image/jpeg")
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { up.complete(ctx, t2.ticketId, 5) }.code)
        assertEquals(DocumentState.STORED, up.complete(ctx, t2.ticketId, 1).state)
    }

    @Test
    fun zwei_parallele_abschluesse_desselben_tickets_ergeben_genau_ein_dokument() {
        val w = fx.world(); val ctx = fx.ctx(w.tenant); val bytes = Samples.pdf(n = 4096)
        val t = ticket(w, ctx, bytes, "application/pdf")
        val pool = Executors.newFixedThreadPool(12); val go = CountDownLatch(1)
        val futures = (1..12).map { pool.submit<Any> { go.await(); try { up.complete(ctx, t.ticketId, t.revision) } catch (e: ProblemException) { e.code } } }
        go.countDown(); val results = futures.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, results.count { it is de.taktovia.documentsfinance.api.StoredDocumentView }, results.toString())
        assertTrue(results.filterIsInstance<ErrorCode>().all { it == ErrorCode.CONFLICT }, results.toString())
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=? AND upload_ticket_id=?", w.tenant.value, t.ticketId))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='document.upload_accepted'", w.tenant.value), "genau ein Ereignis")
        assertEquals(listOf("receipts/originals/" + fx.strings("SELECT id::text FROM taktovia.expense_document WHERE tenant_id=?", w.tenant.value).single()), fx.store.list(w.tenant))
    }

    @Test
    fun fremder_mandant_und_fremder_akteur_erreichen_weder_ticket_noch_dokument_noch_objekt() {
        val a = fx.world(); val b = fx.world(); val ctxA = fx.ctx(a.tenant); val ctxB = fx.ctx(b.tenant)
        val bytes = Samples.jpeg(); val t = ticket(a, ctxA, bytes, "image/jpeg")
        assertEquals(404, problem { up.complete(ctxB, t.ticketId, 1) }.code.status, "Ticket des Mandanten A im Mandanten B")
        assertEquals(404, problem { up.complete(fx.ctx(a.tenant), t.ticketId, 1) }.code.status, "anderer Akteur im selben Mandanten")
        assertEquals(404, problem { up.requestTicket(ctxB, RequestUploadTicket(a.legalEntity, UploadPurpose.RECEIPT, "image/jpeg", 10, "a".repeat(64))) }.code.status, "Rechtsträger von A")
        val d = up.complete(ctxA, t.ticketId, 1)
        assertEquals(404, problem { up.document(ctxB, d.documentId) }.code.status); assertEquals(404, problem { up.openOriginal(ctxB, d.documentId) }.code.status)
        assertEquals(404, problem { up.document(fx.ctx(a.tenant), d.documentId) }.code.status, "anderer Akteur ohne Finanzrolle")
        assertEquals(d.documentId, up.document(fx.ctx(a.tenant, roles = setOf("finance")), d.documentId).documentId, "Finanzrolle sieht den Beleg")
        // Objektschlüssel eines anderen Mandanten sind nicht erreichbar
        val original = fx.strings("SELECT object_key FROM taktovia.expense_document WHERE id=?", d.documentId).single()
        assertNotNull(fx.store.get(ObjectKey(a.tenant, original))); assertNull(fx.store.get(ObjectKey(b.tenant, original))); assertEquals(emptyList<String>(), fx.store.list(b.tenant))
        assertTrue(fx.store.list(a.tenant).all { !it.contains(b.tenant.value.toString()) })
    }

    @Test
    fun ausgefallener_pruefer_laesst_das_dokument_in_quarantaene_und_rescan_schliesst_ab() {
        val w = fx.world(); val ctx = fx.ctx(w.tenant); val bytes = Samples.png()
        val down = fx.uploads(UnavailableScanner(), UploadPolicy(maxBytes = 64 * 1024))
        val t = ticket(w, ctx, bytes, "image/png", uploads = down)
        val d = down.complete(ctx, t.ticketId, 1)
        assertEquals(DocumentState.QUARANTINED, d.state, "fail-closed: ohne Prüfung nie sauber")
        assertEquals(404, problem { down.openOriginal(ctx, d.documentId) }.code.status)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='document.quarantine_passed'", w.tenant.value))
        val fixed = fx.uploads(EicarSignatureScanner())
        assertEquals(DocumentState.STORED, fixed.rescan(ctx, d.documentId).state)
        assertArrayEquals(bytes, fixed.openOriginal(ctx, d.documentId))
        assertEquals(DocumentState.STORED, fixed.rescan(ctx, d.documentId).state, "Wiederholung ändert nichts")
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='document.quarantine_passed'", w.tenant.value))
        // ein Prüfer, der wirft, wird wie „nicht verfügbar“ behandelt
        val boom = object : MalwareScanner { override val engine = "boom"; override fun scan(bytes: ByteArray): ScanResult = throw IllegalStateException("kaputt") }
        val up2 = fx.uploads(boom); val t2 = ticket(w, ctx, Samples.jpeg(seed = 7), "image/jpeg", uploads = up2)
        assertEquals(DocumentState.QUARANTINED, up2.complete(ctx, t2.ticketId, 1).state)
    }

    @Test
    fun das_ticket_dient_auch_anderen_modulen_zweck_schadenfoto() {
        val w = fx.world(); val ctx = fx.ctx(w.tenant); val bytes = Samples.jpeg(seed = 5)
        val t = ticket(w, ctx, bytes, "image/jpeg", purpose = UploadPurpose.DAMAGE_PHOTO)
        assertTrue(t.uploadKey.startsWith("evidence/quarantine/"))
        val d = up.complete(ctx, t.ticketId, 1)
        assertEquals(UploadPurpose.DAMAGE_PHOTO, d.purpose); assertEquals(DocumentState.STORED, d.state)
        assertEquals(listOf("evidence/originals/${d.documentId}"), fx.store.list(w.tenant))
    }
}
