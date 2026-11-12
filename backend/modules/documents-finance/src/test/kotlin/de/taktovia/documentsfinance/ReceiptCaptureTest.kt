package de.taktovia.documentsfinance

import de.taktovia.documentsfinance.api.CaseKind
import de.taktovia.documentsfinance.api.CaseRef
import de.taktovia.documentsfinance.api.CorrectExpense
import de.taktovia.documentsfinance.api.DocumentKind
import de.taktovia.documentsfinance.api.DocumentState
import de.taktovia.documentsfinance.api.EditedView
import de.taktovia.documentsfinance.api.RequestUploadTicket
import de.taktovia.documentsfinance.api.SourceChannel
import de.taktovia.documentsfinance.api.StoredDocumentView
import de.taktovia.documentsfinance.api.UploadPurpose
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.storage.FileObjectStore
import de.taktovia.platform.storage.ObjectKey
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-25 (DOC-001, Belegerfassung per Kamera in der Fahrer-App) gegen die echte Datenbank und einen Dateisystem-Objektspeicher: Rohbild und bearbeitete Ansicht als getrennte Objekte,
 * Zuschnitt und Drehung lassen das Original unverändert (Hash), Zuordnung nur zu zugewiesenen Vorgängen (403, sonst 404; nichts wird angelegt), paralleler Speicherversuch mit
 * derselben Kennung ergibt genau einen Beleg, fremde Mandanten erreichen den Beleg nicht (RLS).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReceiptCaptureTest {
    private lateinit var bf: BelegFixture

    @BeforeAll fun start(@TempDir dir: Path) { bf = BelegFixture(FileObjectStore(dir)) }
    @AfterAll fun stop() { bf.close() }

    /** Mandant mit Rechtsträger, einem Fahrer und seinem Fahrzeug samt Schicht (Zuweisung), dazu ein Fahrzeug eines anderen Fahrers. */
    private class Scene(val w: UploadFixture.World, val driver: UUID, val ownVehicle: UUID, val ownShift: UUID, val foreignVehicle: UUID)

    private fun scene(): Scene {
        val w = bf.fx.world(); val driver = UUID.randomUUID(); val other = UUID.randomUUID()
        val own = vehicle(w); val foreign = vehicle(w); val shift = shift(w, driver, own); shift(w, other, foreign)
        return Scene(w, driver, own, shift, foreign)
    }

    private fun vehicle(w: UploadFixture.World): UUID = UUID.randomUUID().also { v ->
        bf.fx.tx.run(w.tenant) { j -> j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,active_block,safety_revision) VALUES (?,?,'org-m4-25',CAST(? AS jsonb),false,1)", w.tenant.value, v, """{"seats":4,"wheelchair":0}""") }
    }

    private fun shift(w: UploadFixture.World, driver: UUID, vehicle: UUID): UUID = UUID.randomUUID().also { s ->
        bf.fx.tx.run(w.tenant) { j -> j.update("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,'2026-12-01 04:00:00+00','2026-12-01 14:00:00+00','Europe/Berlin',1,'PUBLISHED')", w.tenant.value, s, driver, vehicle) }
    }

    private fun request(w: UploadFixture.World, bytes: ByteArray, clientUuid: UUID, caseRef: CaseRef?, kind: DocumentKind = DocumentKind.FUEL_RECEIPT) =
        RequestUploadTicket(w.legalEntity, UploadPurpose.RECEIPT, "image/jpeg", bytes.size.toLong(), Samples.sha256(bytes), SourceChannel.DRIVER_APP, kind, caseRef, clientUuid, Instant.parse("2026-12-01T07:15:00Z"))

    /** Der Ablauf der App in drei Aufrufen: Ticket, Upload in die Quarantäne, Abschluss. */
    private fun capture(w: UploadFixture.World, ctx: RequestContext, bytes: ByteArray, clientUuid: UUID, caseRef: CaseRef?): StoredDocumentView {
        val reg = bf.svc.uploads.registerReceipt(ctx, request(w, bytes, clientUuid, caseRef))
        bf.store.put(ObjectKey(w.tenant, reg.ticket.uploadKey), bytes, "image/jpeg")
        return bf.svc.uploads.completeReceipt(ctx, reg.ticket.ticketId, reg.ticket.revision).document
    }

    @Test
    fun rohbild_und_bearbeitete_ansicht_abrufbar() {
        val s = scene(); val ctx = bf.driverCtx(s.w, s.driver)
        val raw = Samples.jpeg(4096, seed = 5); val edited = Samples.jpeg(1800, seed = 6)
        val doc = capture(s.w, ctx, raw, UUID.randomUUID(), CaseRef(CaseKind.VEHICLE, s.ownVehicle))
        assertEquals(DocumentState.STORED, doc.state); assertEquals(SourceChannel.DRIVER_APP, doc.channel); assertEquals(DocumentKind.FUEL_RECEIPT, doc.kind)
        assertEquals(CaseRef(CaseKind.VEHICLE, s.ownVehicle), doc.caseRef)
        val after = bf.svc.expenses.correct(ctx, doc.documentId, doc.revision, CorrectExpense("Bearbeitete Ansicht der Kamera", editedView = EditedView("image/jpeg", edited)))
        assertEquals(doc.revision + 1, after.document.revision)
        // beide Ansichten sind abrufbar, getrennt und unterschiedlich
        assertArrayEquals(raw, bf.svc.expenses.original(ctx, doc.documentId))
        val v = bf.svc.expenses.editedView(ctx, doc.documentId)
        assertArrayEquals(edited, v.bytes); assertEquals("image/jpeg", v.mime); assertEquals(Samples.sha256(edited), v.sha256); assertEquals(after.document.revision, v.revision)
        assertNotEquals(Samples.sha256(raw), v.sha256)
        // getrennte Objekte: die Ansicht liegt nie unter dem Original-Schlüssel
        val originalKey = bf.fx.strings("SELECT object_key FROM taktovia.expense_document WHERE id=?", doc.documentId).single()
        val viewKey = bf.fx.strings("SELECT edited_view_key FROM taktovia.document_revision WHERE document_id=? AND edited_view_key IS NOT NULL", doc.documentId).single()
        assertNotEquals(originalKey, viewKey); assertTrue("/originals/" in originalKey && "/originals/" !in viewKey)
        // ohne bearbeitete Ansicht gibt es keine: 404
        val plain = capture(s.w, ctx, Samples.jpeg(1500, seed = 7), UUID.randomUUID(), null)
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.expenses.editedView(ctx, plain.documentId) }.code)
    }

    @Test
    fun zuschnitt_und_drehung_lassen_original_hash_unveraendert() {
        val s = scene(); val ctx = bf.driverCtx(s.w, s.driver)
        val raw = Samples.jpeg(4096, seed = 8)
        val doc = capture(s.w, ctx, raw, UUID.randomUUID(), CaseRef(CaseKind.SHIFT, s.ownShift))
        var rev = doc.revision
        for (seed in 9..10) {   // Zuschnitt, dann Drehung: je eine neue Revision mit eigener Ansicht
            rev = bf.svc.expenses.correct(ctx, doc.documentId, rev, CorrectExpense("Zuschnitt/Drehung", editedView = EditedView("image/jpeg", Samples.jpeg(1000 + seed, seed = seed)))).document.revision
        }
        assertEquals(doc.revision + 2, rev)
        assertEquals(Samples.sha256(raw), bf.fx.strings("SELECT original_sha256 FROM taktovia.expense_document WHERE id=?", doc.documentId).single())
        assertEquals(Samples.sha256(raw), Samples.sha256(bf.svc.expenses.original(ctx, doc.documentId)))
        assertEquals(Samples.sha256(raw), Samples.sha256(bf.store.get(ObjectKey(s.w.tenant, bf.fx.strings("SELECT object_key FROM taktovia.expense_document WHERE id=?", doc.documentId).single()))!!))
        assertEquals(Samples.sha256(Samples.jpeg(1010, seed = 10)), bf.svc.expenses.editedView(ctx, doc.documentId).sha256, "die jüngste Ansicht gilt")
        assertEquals(2, bf.fx.count("SELECT count(*) FROM taktovia.document_revision WHERE document_id=? AND edited_view_key IS NOT NULL", doc.documentId))
    }

    @Test
    fun fremdes_fahrzeug_403_entwurf_bleibt_erhalten() {
        val s = scene(); val ctx = bf.driverCtx(s.w, s.driver)
        val raw = Samples.jpeg(3000, seed = 11); val draft = UUID.randomUUID()
        // fremdes (vorhandenes, aber nicht zugewiesenes) Fahrzeug und fremde Schicht: 403
        assertEquals(ErrorCode.FORBIDDEN, bf.problem { bf.svc.uploads.registerReceipt(ctx, request(s.w, raw, draft, CaseRef(CaseKind.VEHICLE, s.foreignVehicle))) }.code)
        val foreignShift = bf.fx.strings("SELECT id::text FROM taktovia.staff_shift WHERE tenant_id=? AND vehicle_id=?", s.w.tenant.value, s.foreignVehicle).single()
        assertEquals(ErrorCode.FORBIDDEN, bf.problem { bf.svc.uploads.registerReceipt(ctx, request(s.w, raw, draft, CaseRef(CaseKind.SHIFT, UUID.fromString(foreignShift)))) }.code)
        // ein unbekanntes Fahrzeug (auch eines fremden Mandanten) ist nicht vorhanden: 404
        val otherTenant = scene()
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.uploads.registerReceipt(ctx, request(s.w, raw, draft, CaseRef(CaseKind.VEHICLE, otherTenant.ownVehicle))) }.code)
        // abgewiesen heißt: serverseitig nichts angelegt, der Entwurf (Kennung) bleibt unverbraucht und lässt sich mit dem eigenen Fahrzeug erneut speichern
        assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.upload_ticket WHERE tenant_id=? AND client_uuid=?", s.w.tenant.value, draft))
        assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=? AND client_uuid=?", s.w.tenant.value, draft))
        val doc = capture(s.w, ctx, raw, draft, CaseRef(CaseKind.VEHICLE, s.ownVehicle))
        assertEquals(draft, doc.clientUuid); assertEquals(CaseRef(CaseKind.VEHICLE, s.ownVehicle), doc.caseRef)
        // die Leitstelle (Web-Kanal) ist an die Fahrerzuweisung nicht gebunden
        val web = bf.svc.uploads.requestTicket(bf.financeCtx(s.w), RequestUploadTicket(s.w.legalEntity, UploadPurpose.RECEIPT, "image/jpeg", raw.size.toLong(), Samples.sha256(raw), SourceChannel.DISPATCH_WEB, DocumentKind.RECEIPT, CaseRef(CaseKind.VEHICLE, s.foreignVehicle)))
        assertEquals(CaseRef(CaseKind.VEHICLE, s.foreignVehicle), bf.fx.strings("SELECT case_ref_id::text FROM taktovia.upload_ticket WHERE id=?", web.ticketId).single().let { CaseRef(CaseKind.VEHICLE, UUID.fromString(it)) })
    }

    @Test
    fun paralleler_speicherversuch_gleicher_idempotency_key_genau_ein_beleg() {
        val s = scene(); val ctx = bf.driverCtx(s.w, s.driver)
        val raw = Samples.jpeg(5000, seed = 12); val draft = UUID.randomUUID(); val n = 6
        val pool = Executors.newFixedThreadPool(n); val start = CountDownLatch(1)
        try {
            val results = (1..n).map {
                pool.submit<StoredDocumentView> {
                    start.await()
                    var last: Throwable? = null
                    repeat(40) {
                        try { return@submit capture(s.w, ctx, raw, draft, CaseRef(CaseKind.VEHICLE, s.ownVehicle)) } catch (e: de.taktovia.platform.problem.ProblemException) { if (e.code != ErrorCode.CONFLICT) throw e; last = e; Thread.sleep(25) }
                    }
                    throw last!!
                }
            }
            start.countDown()
            val docs = results.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(1, docs.map { it.documentId }.toSet().size, "alle Läufe führen auf denselben Beleg")
        } finally { pool.shutdownNow() }
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=? AND client_uuid=?", s.w.tenant.value, draft))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='expense.created' AND payload->>'client_uuid'=?", s.w.tenant.value, draft.toString()))
    }

    @Test
    fun fremder_mandant_kein_zugriff_auf_beleg() {
        val a = scene(); val b = scene()
        val ctxA = bf.driverCtx(a.w, a.driver); val ctxB = bf.driverCtx(b.w, b.driver)
        val draft = UUID.randomUUID(); val raw = Samples.jpeg(2500, seed = 13)
        val docA = capture(a.w, ctxA, raw, draft, null)
        bf.svc.expenses.correct(ctxA, docA.documentId, docA.revision, CorrectExpense("Ansicht", editedView = EditedView("image/jpeg", Samples.jpeg(900, seed = 14))))
        // der Fahrer eines anderen Mandanten (und mit Finanzrolle) sieht weder Beleg noch Original noch Ansicht
        for (c in listOf(ctxB, bf.financeCtx(b.w))) {
            assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.expenses.get(c, docA.documentId) }.code)
            assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.expenses.original(c, docA.documentId) }.code)
            assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.expenses.editedView(c, docA.documentId) }.code)
        }
        // dieselbe Kennung im anderen Mandanten ist ein eigener Beleg (Unique nur je Mandant)
        val docB = capture(b.w, ctxB, raw, draft, null)
        assertNotEquals(docA.documentId, docB.documentId)
        // RLS in der Datenbank: unter dem Kontext von B ist die Zeile von A nicht lesbar
        val seen = bf.fx.tx.run(b.w.tenant) { j -> j.queryForObject("SELECT count(*) FROM taktovia.expense_document WHERE id = ?", Int::class.java, docA.documentId) }
        assertEquals(0, seen)
    }
}
