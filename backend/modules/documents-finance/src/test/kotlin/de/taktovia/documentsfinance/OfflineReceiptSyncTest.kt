package de.taktovia.documentsfinance

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.documentsfinance.api.DocumentState
import de.taktovia.documentsfinance.api.DocumentUploads
import de.taktovia.documentsfinance.api.EicarSignatureScanner
import de.taktovia.documentsfinance.api.RequestUploadTicket
import de.taktovia.documentsfinance.api.SourceChannel
import de.taktovia.documentsfinance.api.DocumentKind
import de.taktovia.documentsfinance.api.StoredDocumentView
import de.taktovia.documentsfinance.api.TicketState
import de.taktovia.documentsfinance.api.UploadPolicy
import de.taktovia.documentsfinance.api.UploadPurpose
import de.taktovia.documentsfinance.api.Uploads
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.storage.FileObjectStore
import de.taktovia.platform.storage.ObjectKey
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-26 (DOC-013, Offline-Belegerfassung mit einmaliger Synchronisation) gegen die echte Datenbank: Die App erzeugt die Client-UUID vor dem Upload; die Anlage ist darüber idempotent.
 * Positiv (genau einmal, Hash und Erfassungszeit), Negativ (Abbruch und Wiederaufnahme, Übertragungsfehler, abweichender Hash, fremder Mandant, SQL-Änderung des Originals) und
 * Konkurrierend (zwei Synchronisationsläufe, auch mit zwei verschiedenen Tickets derselben UUID).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OfflineReceiptSyncTest {
    private lateinit var bf: BelegFixture
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val registry = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012())

    @BeforeAll fun start(@TempDir dir: Path) { bf = BelegFixture(FileObjectStore(dir)) }
    @AfterAll fun stop() { bf.close() }

    private val capturedAt: Instant = Instant.now().minus(Duration.ofHours(2)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)   // offline erfasst, zwei Stunden vor der Synchronisation

    private fun req(w: UploadFixture.World, bytes: ByteArray, clientUuid: UUID, declared: ByteArray = bytes) =
        RequestUploadTicket(w.legalEntity, UploadPurpose.RECEIPT, "image/jpeg", declared.size.toLong(), Samples.sha256(declared), SourceChannel.DRIVER_APP, DocumentKind.RECEIPT, null, clientUuid, capturedAt)

    /** Ein vollständiger Synchronisationslauf der App: registrieren, hochladen (nur wenn noch kein Beleg), abschließen. */
    private fun sync(w: UploadFixture.World, ctx: RequestContext, bytes: ByteArray, clientUuid: UUID, uploads: Uploads = bf.svc.uploads): StoredDocumentView {
        val reg = uploads.registerReceipt(ctx, req(w, bytes, clientUuid))
        if (reg.ticket.documentId != null) return bf.svc.uploads.document(ctx, reg.ticket.documentId!!)
        bf.store.put(ObjectKey(w.tenant, reg.ticket.uploadKey), bytes, "image/jpeg")
        return uploads.completeReceipt(ctx, reg.ticket.ticketId, reg.ticket.revision).document
    }

    private fun docs(w: UploadFixture.World, clientUuid: UUID) = bf.fx.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=? AND client_uuid=?", w.tenant.value, clientUuid)

    @Test
    fun offline_beleg_erscheint_nach_sync_genau_einmal_mit_unveraendertem_hash() {
        val w = bf.fx.world(); val ctx = bf.driverCtx(w); val local = Samples.jpeg(6000, seed = 21); val draft = UUID.randomUUID()
        val first = sync(w, ctx, local, draft)
        assertEquals(DocumentState.STORED, first.state); assertEquals(Samples.sha256(local), first.sha256, "Server-Hash entspricht dem lokal berechneten")
        assertEquals(draft, first.clientUuid); assertEquals(capturedAt, first.capturedAt)
        assertTrue(first.receivedAt.isAfter(first.capturedAt!!), "Erfassungszeit am Gerät und Eingangszeit am Server sind getrennt")
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.expense_document WHERE client_uuid=? AND captured_at < original_received_at - interval ' 1 hour'", draft))
        // der Server liefert das Original byteweise
        assertArrayEquals(local, bf.svc.expenses.original(ctx, first.documentId))
        // erneute Läufe (App-Neustart, wiederholter Sync): derselbe Beleg, keiner zusätzlich
        val again = sync(w, ctx, local, draft); val third = sync(w, ctx, local, draft)
        assertEquals(setOf(first.documentId), setOf(again.documentId, third.documentId))
        val reg = bf.svc.uploads.registerReceipt(ctx, req(w, local, draft))
        assertFalse(reg.created); assertEquals(first.documentId, reg.ticket.documentId); assertEquals(TicketState.CONSUMED, reg.ticket.state)
        assertEquals(1, docs(w, draft))
        // das erste Complete wiederholt (Antwort ging verloren): derselbe Beleg, nicht neu
        val replay = bf.svc.uploads.completeReceipt(ctx, reg.ticket.ticketId, reg.ticket.revision)
        assertFalse(replay.created); assertEquals(first.documentId, replay.document.documentId)
        assertEquals(0, bf.store.list(w.tenant, "receipts/quarantine/").size, "keine übrig gebliebene Quarantänekopie")
    }

    @Test
    fun derselbe_entwurf_parallel_von_zwei_sync_laeufen_genau_ein_beleg() {
        val w = bf.fx.world(); val ctx = bf.driverCtx(w); val local = Samples.jpeg(7000, seed = 22); val draft = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        try {
            val runs = (1..2).map {
                pool.submit<StoredDocumentView> {
                    start.await()
                    repeat(40) { try { return@submit sync(w, ctx, local, draft) } catch (e: ProblemException) { if (e.code != ErrorCode.CONFLICT) throw e; Thread.sleep(20) } }
                    throw AssertionError("Sync-Lauf kam nicht zum Ende")
                }
            }
            start.countDown()
            assertEquals(1, runs.map { it.get(60, TimeUnit.SECONDS).documentId }.toSet().size)
        } finally { pool.shutdownNow() }
        assertEquals(1, docs(w, draft))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='expense.created' AND payload->>'client_uuid'=?", w.tenant.value, draft.toString()))
    }

    @Test
    fun derselbe_entwurf_mit_zwei_tickets_parallel_abgeschlossen_ergibt_einen_beleg() {
        // Das erste Ticket gilt nach Ablauf (Uhr der Anwendung) nicht mehr als wiederverwendbar: ein zweiter Lauf stellt ein zweites aus; beide werden gleichzeitig abgeschlossen.
        val w = bf.fx.world(); val ctx = bf.driverCtx(w); val local = Samples.jpeg(7500, seed = 23); val draft = UUID.randomUUID()
        val later = DocumentUploads.services(bf.fx.ctxTx, JdbcAuditPort(), bf.fx.security, Outbox(), bf.store, EicarSignatureScanner(), UploadPolicy(maxBytes = 64 * 1024),
            clock = Clock.offset(Clock.systemUTC(), Duration.ofMinutes(20))).uploads
        val t1 = bf.svc.uploads.registerReceipt(ctx, req(w, local, draft)).ticket
        val t2 = later.registerReceipt(ctx, req(w, local, draft)).ticket
        assertNotEquals(t1.ticketId, t2.ticketId)
        bf.store.put(ObjectKey(w.tenant, t1.uploadKey), local, "image/jpeg"); bf.store.put(ObjectKey(w.tenant, t2.uploadKey), local, "image/jpeg")
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        try {
            val results = listOf(t1, t2).map { t -> pool.submit<Pair<UUID, Boolean>> { start.await(); bf.svc.uploads.completeReceipt(ctx, t.ticketId, t.revision).let { it.document.documentId to it.created } } }
            start.countDown()
            val r = results.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(1, r.map { it.first }.toSet().size, "beide Läufe sehen denselben Beleg")
            assertEquals(1, r.count { it.second }, "genau ein Lauf hat ihn angelegt")
        } finally { pool.shutdownNow() }
        assertEquals(1, docs(w, draft))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.upload_ticket WHERE tenant_id=? AND client_uuid=? AND state='CONSUMED'", w.tenant.value, draft))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.upload_ticket WHERE tenant_id=? AND client_uuid=? AND state='REJECTED' AND reject_reason='DUPLICATE'", w.tenant.value, draft))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='expense.created' AND payload->>'client_uuid'=?", w.tenant.value, draft.toString()))
    }

    @Test
    fun abgebrochener_upload_wird_wiederaufgenommen_ohne_doppelbeleg() {
        val w = bf.fx.world(); val ctx = bf.driverCtx(w); val local = Samples.jpeg(8000, seed = 24)
        // (a) Abbruch nach 50 %: nur die Hälfte liegt im Speicher, der Abschluss scheitert, nichts entsteht; die Wiederholung (neues Ticket derselben UUID) gelingt
        val a = UUID.randomUUID()
        val t = bf.svc.uploads.registerReceipt(ctx, req(w, local, a)).ticket
        bf.store.put(ObjectKey(w.tenant, t.uploadKey), local.copyOf(local.size / 2), "image/jpeg")
        assertEquals(ErrorCode.VALIDATION_FAILED, bf.problem { bf.svc.uploads.completeReceipt(ctx, t.ticketId, t.revision) }.code)
        assertEquals(0, docs(w, a))
        val doc = sync(w, ctx, local, a)
        assertEquals(Samples.sha256(local), doc.sha256); assertEquals(1, docs(w, a))
        // (b) App beendet, bevor der Abschluss aufgerufen wurde: die Wiederaufnahme erhält dasselbe Ticket (derselbe Schlüssel), kein zweites
        val b = UUID.randomUUID()
        val first = bf.svc.uploads.registerReceipt(ctx, req(w, local, b))
        bf.store.put(ObjectKey(w.tenant, first.ticket.uploadKey), local.copyOf(100), "image/jpeg")   // abgebrochen
        val resumed = bf.svc.uploads.registerReceipt(ctx, req(w, local, b))
        assertTrue(first.created); assertFalse(resumed.created); assertEquals(first.ticket.ticketId, resumed.ticket.ticketId); assertEquals(first.ticket.uploadKey, resumed.ticket.uploadKey)
        bf.store.put(ObjectKey(w.tenant, resumed.ticket.uploadKey), local, "image/jpeg")
        val done = bf.svc.uploads.completeReceipt(ctx, resumed.ticket.ticketId, resumed.ticket.revision)
        assertTrue(done.created); assertEquals(1, docs(w, b))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.upload_ticket WHERE tenant_id=? AND client_uuid=?", w.tenant.value, b))
        // (c) Abbruch ohne jede Fortsetzung hinterlässt keinen halben Beleg
        val c = UUID.randomUUID()
        bf.svc.uploads.registerReceipt(ctx, req(w, local, c))
        assertEquals(0, docs(w, c))
    }

    @Test
    fun gleiche_uuid_anderer_hash_409_ohne_ueberschreiben_und_fremder_mandant_getrennt() {
        val w = bf.fx.world(); val ctx = bf.driverCtx(w); val local = Samples.jpeg(3000, seed = 25); val other = Samples.jpeg(3000, seed = 26); val draft = UUID.randomUUID()
        val doc = sync(w, ctx, local, draft)
        val conflict = bf.problem { bf.svc.uploads.registerReceipt(ctx, req(w, other, draft)) }
        assertEquals(ErrorCode.CONFLICT, conflict.code)
        assertEquals(Samples.sha256(local), bf.fx.strings("SELECT original_sha256 FROM taktovia.expense_document WHERE client_uuid=?", draft).single(), "nichts überschrieben")
        assertArrayEquals(local, bf.svc.expenses.original(ctx, doc.documentId))
        // auch gegen ein noch offenes Ticket mit anderem Inhalt
        val open = UUID.randomUUID()
        bf.svc.uploads.registerReceipt(ctx, req(w, local, open))
        assertEquals(ErrorCode.CONFLICT, bf.problem { bf.svc.uploads.registerReceipt(ctx, req(w, other, open)) }.code)
        // ein anderer Fahrer desselben Mandanten kann die vergebene UUID weder übernehmen noch den Beleg lesen
        val intruder = bf.driverCtx(w)
        assertEquals(ErrorCode.CONFLICT, bf.problem { bf.svc.uploads.registerReceipt(intruder, req(w, local, draft)) }.code)
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.uploads.document(intruder, doc.documentId) }.code)
        // anderer Mandant: dieselbe UUID ist ein eigener Beleg und nicht lesbar
        val w2 = bf.fx.world(); val ctx2 = bf.driverCtx(w2)
        val doc2 = sync(w2, ctx2, other, draft)
        assertNotEquals(doc.documentId, doc2.documentId); assertEquals(Samples.sha256(other), doc2.sha256)
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.uploads.document(ctx2, doc.documentId) }.code)
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.expenses.original(ctx, doc2.documentId) }.code)
        // das Original ist auch per SQL unter der Runtime-Rolle unveränderlich
        for (sql in listOf("UPDATE taktovia.expense_document SET object_key = 'receipts/originals/x' WHERE id = ?", "UPDATE taktovia.expense_document SET original_sha256 = repeat('b', 64) WHERE id = ?",
                "UPDATE taktovia.expense_document SET client_uuid = gen_random_uuid() WHERE id = ?")) {
            val failed = try { bf.fx.tx.run(w.tenant) { j -> j.update(sql, doc.documentId) }; false } catch (e: Exception) { true }
            assertTrue(failed, sql)
        }
        assertEquals(Samples.sha256(local), bf.fx.strings("SELECT original_sha256 FROM taktovia.expense_document WHERE id=?", doc.documentId).single())
    }

    @Test
    fun uebertragungsfehler_hash_legt_keinen_beleg_an() {
        val w = bf.fx.world(); val ctx = bf.driverCtx(w); val local = Samples.jpeg(3000, seed = 27); val corrupted = local.copyOf().also { it[1500] = (it[1500] + 1).toByte() }; val draft = UUID.randomUUID()
        val t = bf.svc.uploads.registerReceipt(ctx, req(w, local, draft)).ticket
        bf.store.put(ObjectKey(w.tenant, t.uploadKey), corrupted, "image/jpeg")   // gleiche Größe, andere Bytes: Übertragungsfehler
        assertEquals(ErrorCode.VALIDATION_FAILED, bf.problem { bf.svc.uploads.completeReceipt(ctx, t.ticketId, t.revision) }.code)
        assertEquals(0, docs(w, draft))
        assertEquals("HASH_MISMATCH", bf.fx.strings("SELECT reject_reason FROM taktovia.upload_ticket WHERE id=?", t.ticketId).single())
        assertEquals(0, bf.store.list(w.tenant, "receipts/").size, "das nicht passende Objekt wird nicht aufbewahrt")
        // Wiederholung mit korrektem Inhalt ist möglich und ergibt genau einen Beleg
        assertEquals(Samples.sha256(local), sync(w, ctx, local, draft).sha256); assertEquals(1, docs(w, draft))
    }

    @Test
    fun ereignis_beleg_eingegangen_genau_einmal() {
        val w = bf.fx.world(); val ctx = bf.driverCtx(w); val local = Samples.jpeg(3000, seed = 28); val draft = UUID.randomUUID()
        repeat(3) { sync(w, ctx, local, draft) }
        val payloads = bf.fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND event_type='expense.created' AND payload->>'client_uuid'=?", w.tenant.value, draft.toString())
        assertEquals(1, payloads.size)
        val schema = registry.getSchema(Files.readString(repo.resolve("api/events/expense.created.v1.schema.json")))
        assertEquals(emptyList<Any>(), schema.validate(payloads.single(), InputFormat.JSON).toList().map { it.message }, payloads.single())
        assertTrue("\"captured_at\"" in payloads.single() && "\"received_at\"" in payloads.single())
    }
}
