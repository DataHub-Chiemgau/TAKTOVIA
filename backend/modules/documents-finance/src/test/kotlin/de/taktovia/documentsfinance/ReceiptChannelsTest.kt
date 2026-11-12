package de.taktovia.documentsfinance

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.documentsfinance.api.DocumentKind
import de.taktovia.documentsfinance.api.DocumentState
import de.taktovia.documentsfinance.api.EicarSignatureScanner
import de.taktovia.documentsfinance.api.ImportFile
import de.taktovia.documentsfinance.api.InboundAttachment
import de.taktovia.documentsfinance.api.InboundEmail
import de.taktovia.documentsfinance.api.IntakeOutcome
import de.taktovia.documentsfinance.api.IntakeRejection
import de.taktovia.documentsfinance.api.ReleaseBlocker
import de.taktovia.documentsfinance.api.RequestUploadTicket
import de.taktovia.documentsfinance.api.SourceChannel
import de.taktovia.documentsfinance.api.UploadPurpose
import de.taktovia.platform.problem.ErrorCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import de.taktovia.platform.storage.FileObjectStore

/**
 * WP-M4-22 (DOC-006, Belegaggregat mit vier Eingangskanälen) gegen die echte Datenbank: je ein Beleg aus Fahrer-App, Leitstelle (Web), E-Mail-Eingang und Rechnungsimport mit derselben Struktur,
 * Herkunftskanal und Eingangszeit; alle Kanäle durchlaufen Ticket und Quarantäne; Absenderprüfung; doppelte Zustellung; Mandantentrennung.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReceiptChannelsTest {
    private lateinit var bf: BelegFixture
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val registry = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012())

    @BeforeAll fun start(@TempDir dir: Path) { bf = BelegFixture(FileObjectStore(dir)) }
    @AfterAll fun stop() { bf.close() }

    private fun mail(id: String = "<${UUID.randomUUID()}@lieferant.test>", from: String = "buchhaltung@lieferant.test", to: String = "rechnung@firma.test", vararg att: ByteArray) =
        InboundEmail(id, from, to, att.mapIndexed { i, b -> InboundAttachment("rechnung-$i", null, b) })

    @Test
    fun vier_kanaele_erzeugen_dieselbe_belegstruktur() {
        val w = bf.fx.world(); bf.configureInbound(w)
        val fin = bf.financeCtx(w); val svc = bf.serviceCtx(w.tenant)
        val app = bf.upload(w, bf.driverCtx(w), Samples.jpeg(seed = 11), SourceChannel.DRIVER_APP)
        val web = bf.upload(w, fin, Samples.jpeg(seed = 12), SourceChannel.DISPATCH_WEB)
        val email = (bf.svc.intake.receiveEmail(svc, mail(att = arrayOf(Samples.pdf("mail"))))).outcomes.single() as IntakeOutcome.Created
        val import = bf.svc.intake.importFile(svc, ImportFile(w.legalEntity, "lauf1.pdf", "application/pdf", Samples.pdf("import"), sourceRef = "lauf-1/rechnung.pdf")) as IntakeOutcome.Created
        val docs = listOf(app, web, email.document, import.document)
        assertEquals(listOf(SourceChannel.DRIVER_APP, SourceChannel.DISPATCH_WEB, SourceChannel.EMAIL_INBOUND, SourceChannel.INVOICE_IMPORT), docs.map { it.channel })
        for (d in docs) {
            // dieselbe fachliche Struktur: Eingangszeit gesetzt, Original geprüft abgelegt, Revision 1, ein Revisionseintrag, keine Felder
            assertNotNull(d.receivedAt); assertEquals(DocumentState.STORED, d.state); assertEquals(1, d.revision); assertNotNull(d.scannedAt)
            val e = bf.svc.expenses.get(fin, d.documentId)
            assertEquals(d.channel, e.document.channel); assertTrue(e.fields.isEmpty())
            assertEquals(1, bf.svc.expenses.revisions(fin, d.documentId).size)
            // kein Kanal umgeht Ticket und Quarantäne
            assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.expense_document d JOIN taktovia.upload_ticket t ON t.tenant_id=d.tenant_id AND t.id=d.upload_ticket_id " +
                "WHERE d.id=? AND t.state='CONSUMED' AND d.scan_engine IS NOT NULL AND t.source_channel=d.source_channel", d.documentId))
            assertEquals(d.channel.name, bf.fx.strings("SELECT source_channel FROM taktovia.expense_document WHERE id=?", d.documentId).single())
            val payload = bf.fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND event_type='expense.created' AND aggregate_id=?", w.tenant.value, d.documentId).single()
            val schema = registry.getSchema(Files.readString(repo.resolve("api/events/expense.created.v1.schema.json")))
            assertEquals(emptyList<Any>(), schema.validate(payload, InputFormat.JSON).toList().map { it.message }, payload)
        }
        assertEquals(4, bf.fx.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=? AND upload_ticket_id IS NOT NULL", w.tenant.value))
        assertEquals(setOf(true), docs.map { it.blockers.isEmpty() }.toSet(), "alle vier sind freigabefähig (verifizierte Domäne)")
    }

    @Test
    fun doppelte_email_zustellung_ergibt_einen_beleg() {
        val w = bf.fx.world(); bf.configureInbound(w); val svc = bf.serviceCtx(w.tenant)
        val m = mail(att = arrayOf(Samples.pdf("einmalig")))
        val first = bf.svc.intake.receiveEmail(svc, m); val second = bf.svc.intake.receiveEmail(svc, m)
        assertTrue(first.outcomes.single() is IntakeOutcome.Created)
        val dup = second.outcomes.single() as IntakeOutcome.Duplicate
        assertEquals((first.outcomes.single() as IntakeOutcome.Created).document.documentId, dup.document?.documentId)
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=?", w.tenant.value))
        // konkurrierend: dieselbe Nachricht gleichzeitig zugestellt
        val m2 = mail(att = arrayOf(Samples.pdf("parallel")))
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        val results = (1..8).map { pool.submit<IntakeOutcome> { go.await(); bf.svc.intake.receiveEmail(svc, m2).outcomes.single() } }
        go.countDown(); val outs = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, outs.count { it is IntakeOutcome.Created }, "genau eine Zustellung legt den Beleg an")
        assertEquals(7, outs.count { it is IntakeOutcome.Duplicate })
        assertEquals(2, bf.fx.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=?", w.tenant.value), "ein Beleg je Nachricht")
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=? AND source_ref=?", w.tenant.value, m2.messageId + "#1"))
    }

    @Test
    fun email_ohne_anhang_falsche_adresse_und_fehlende_konfiguration_legen_nichts_an() {
        val w = bf.fx.world(); val svc = bf.serviceCtx(w.tenant)
        assertEquals(IntakeRejection.NOT_CONFIGURED, bf.svc.intake.receiveEmail(svc, mail(att = arrayOf(Samples.pdf()))).rejection)
        bf.configureInbound(w)
        assertEquals(IntakeRejection.NO_ATTACHMENT, bf.svc.intake.receiveEmail(svc, mail()).rejection)
        assertEquals(IntakeRejection.WRONG_RECIPIENT, bf.svc.intake.receiveEmail(svc, mail(to = "andere@firma.test", att = arrayOf(Samples.pdf()))).rejection)
        assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=?", w.tenant.value))
        assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.upload_ticket WHERE tenant_id=?", w.tenant.value))
    }

    @Test
    fun nicht_verifizierte_absenderdomaene_erzeugt_keinen_freigabefaehigen_beleg() {
        val w = bf.fx.world(); bf.configureInbound(w); val svc = bf.serviceCtx(w.tenant)
        val r = bf.svc.intake.receiveEmail(svc, mail(from = "kasse@fremde-firma.test", att = arrayOf(Samples.pdf("unbekannt"))))
        assertFalse(r.senderVerified)
        val d = (r.outcomes.single() as IntakeOutcome.Created).document
        assertEquals(false, d.senderVerified)
        assertEquals(listOf(ReleaseBlocker.UNVERIFIED_SENDER), d.blockers); assertFalse(d.approvable)
        val ok = bf.svc.intake.receiveEmail(svc, mail(att = arrayOf(Samples.pdf("bekannt")))).outcomes.single() as IntakeOutcome.Created
        assertTrue(ok.document.approvable)
        // Kanal und Absenderergebnis sind danach nicht mehr änderbar (Datenbank)
        val ex = org.junit.jupiter.api.assertThrows<Exception> { bf.fx.exec("UPDATE taktovia.expense_document SET sender_verified = true WHERE id = ?", d.documentId) }
        assertTrue(ex.message!!.contains("unveraenderlich"), ex.message)
    }

    @Test
    fun import_mit_defekter_datei_und_infizierte_anhaenge() {
        val w = bf.fx.world(); bf.configureInbound(w); val svc = bf.serviceCtx(w.tenant)
        val broken = bf.svc.intake.importFile(svc, ImportFile(w.legalEntity, "kaputt.pdf", "application/pdf", ByteArray(300) { 7 }, sourceRef = "lauf-2/kaputt.pdf"))
        assertEquals(IntakeRejection.UNSUPPORTED_TYPE, (broken as IntakeOutcome.Rejected).reason)
        assertEquals(IntakeRejection.INVALID_FILE, (bf.svc.intake.importFile(svc, ImportFile(w.legalEntity, "leer.pdf", null, ByteArray(0))) as IntakeOutcome.Rejected).reason)
        assertEquals(IntakeRejection.TOO_LARGE, (bf.svc.intake.importFile(svc, ImportFile(w.legalEntity, "gross.pdf", null, Samples.pdf(n = 70_000))) as IntakeOutcome.Rejected).reason)
        assertEquals(IntakeRejection.INVALID_FILE, (bf.svc.intake.importFile(svc, ImportFile(w.legalEntity, "falsch.pdf", "image/jpeg", Samples.pdf("x"))) as IntakeOutcome.Rejected).reason, "deklarierter Typ passt nicht zu den Bytes")
        assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=?", w.tenant.value))
        // Schadsoftware aus dem E-Mail-Kanal: bleibt in Quarantäne, kein Original, nie freigabefähig
        val inf = bf.svc.intake.receiveEmail(svc, mail(att = arrayOf(Samples.pdf(EicarSignatureScanner.EICAR)))).outcomes.single() as IntakeOutcome.Created
        assertEquals(DocumentState.QUARANTINE_REJECTED, inf.document.state); assertTrue(ReleaseBlocker.NOT_STORED in inf.document.blockers)
        assertEquals(emptyList<String>(), bf.store.list(w.tenant, "receipts/originals/"))
        // ein defekter Import ist nach Korrektur der Quelle erneut importierbar (das Ticket war nicht beansprucht)
        assertTrue(bf.svc.intake.importFile(svc, ImportFile(w.legalEntity, "ok.pdf", null, Samples.pdf("repariert"), sourceRef = "lauf-2/kaputt.pdf")) is IntakeOutcome.Created)
    }

    @Test
    fun fremder_mandant_sieht_den_beleg_nicht_und_clients_koennen_serverkanaele_nicht_beanspruchen() {
        val a = bf.fx.world(); val b = bf.fx.world()
        val d = bf.upload(a, bf.financeCtx(a), Samples.jpeg(seed = 21))
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.expenses.get(bf.financeCtx(b), d.documentId) }.code, "fremder Mandant: 404")
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.expenses.get(bf.driverCtx(a), d.documentId) }.code, "anderer Akteur ohne Finanzrolle: 404")
        val bytes = Samples.jpeg(seed = 22)
        val p = bf.problem { bf.svc.uploads.requestTicket(bf.financeCtx(a), RequestUploadTicket(a.legalEntity, UploadPurpose.RECEIPT, "image/jpeg", bytes.size.toLong(), Samples.sha256(bytes), SourceChannel.EMAIL_INBOUND)) }
        assertEquals("/source_channel", p.violations.single().pointer)
        // Vorgangsbezug eines anderen Mandanten (Fahrzeug) ist nicht zuordenbar
        val foreign = bf.problem { bf.svc.uploads.requestTicket(bf.financeCtx(a), RequestUploadTicket(a.legalEntity, UploadPurpose.RECEIPT, "image/jpeg", bytes.size.toLong(), Samples.sha256(bytes), SourceChannel.DISPATCH_WEB, DocumentKind.RECEIPT,
            de.taktovia.documentsfinance.api.CaseRef(de.taktovia.documentsfinance.api.CaseKind.VEHICLE, UUID.randomUUID()))) }
        assertEquals(ErrorCode.NOT_FOUND, foreign.code)
        assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=? AND case_ref_kind IS NOT NULL", a.tenant.value))
    }
}
