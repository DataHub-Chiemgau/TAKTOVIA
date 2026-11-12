package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.DocumentKind
import de.taktovia.documentsfinance.api.EmailIntakeResult
import de.taktovia.documentsfinance.api.ImportFile
import de.taktovia.documentsfinance.api.IngestFile
import de.taktovia.documentsfinance.api.IngestResult
import de.taktovia.documentsfinance.api.IntakeOutcome
import de.taktovia.documentsfinance.api.IntakeRejection
import de.taktovia.documentsfinance.api.InboundEmail
import de.taktovia.documentsfinance.api.ReceiptIntake
import de.taktovia.documentsfinance.api.SourceChannel
import de.taktovia.documentsfinance.api.UploadPolicy
import de.taktovia.documentsfinance.api.Uploads
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode

/**
 * E-Mail-Eingang und Rechnungsimport (WP-M4-22). Beide Kanäle rufen [Uploads.ingest], denselben Anlagebefehl, der auch die Kanäle Web und Fahrer-App über das Ticket bedient; damit durchläuft
 * jede Datei Hashbildung, Typprüfung und Quarantäne. Die Technische Form des Eingangs in P1 (Offener Punkt 12) ist hier als bereits geparste Nachricht bzw. Datei festgelegt: Ein Adapter
 * (z. B. IMAP-Abruf oder Webhook eines Mail-Dienstes) übergibt [InboundEmail]; Domänenverifikation: die Domäne steht in `document_settings.verified_sender_domains`.
 */
internal class JdbcIntake(private val ctxTx: ContextTransactions, private val uploads: Uploads, private val policy: UploadPolicy) : ReceiptIntake {

    override fun receiveEmail(ctx: RequestContext, mail: InboundEmail): EmailIntakeResult {
        val s = ctxTx.run(ctx, readOnly = true) { j -> JdbcDocumentSettings.load(j) }
        val messageId = mail.messageId.trim()
        val senderDomain = mail.from.trim().lowercase().substringAfterLast('@', "")
        val verified = senderDomain.isNotEmpty() && senderDomain in s.verifiedSenderDomains
        fun rejected(r: IntakeRejection) = EmailIntakeResult(messageId, r, verified, emptyList())
        if (messageId.isEmpty() || messageId.length > 250) throw ProblemException.invalid(de.taktovia.platform.problem.Violation("/message_id", ViolationCode.INVALID_VALUE))
        if (s.invoiceEmailInbound == null || s.inboundLegalEntityId == null) return rejected(IntakeRejection.NOT_CONFIGURED)
        if (mail.to.trim().lowercase() != s.invoiceEmailInbound) return rejected(IntakeRejection.WRONG_RECIPIENT)
        if (mail.attachments.isEmpty()) return rejected(IntakeRejection.NO_ATTACHMENT)
        val outcomes = mail.attachments.mapIndexed { i, a ->
            ingest(ctx, a.filename, IngestFile(s.inboundLegalEntityId, SourceChannel.EMAIL_INBOUND, DocumentKind.INVOICE, a.bytes, a.mime, "$messageId#${i + 1}", verified))
        }
        return EmailIntakeResult(messageId, null, verified, outcomes)
    }

    override fun importFile(ctx: RequestContext, file: ImportFile): IntakeOutcome =
        ingest(ctx, file.filename, IngestFile(file.legalEntityId, SourceChannel.INVOICE_IMPORT, file.kind, file.bytes, file.mime, file.sourceRef))

    private fun ingest(ctx: RequestContext, filename: String, cmd: IngestFile): IntakeOutcome = try {
        when (val r = uploads.ingest(ctx, cmd)) {
            is IngestResult.Created -> IntakeOutcome.Created(filename, r.document)
            is IngestResult.Duplicate -> IntakeOutcome.Duplicate(filename, r.document)
        }
    } catch (e: ProblemException) {
        if (e.code != ErrorCode.VALIDATION_FAILED) throw e   // nur eine nicht verwendbare Datei wird abgewiesen; Berechtigungs- und Systemfehler bleiben Fehler
        val pointer = e.violations.firstOrNull()?.pointer
        IntakeOutcome.Rejected(filename, when {
            pointer == "/file" && cmd.bytes.size.toLong() > policy.maxBytes -> IntakeRejection.TOO_LARGE
            pointer == "/mime" && ContentSniffer.detect(cmd.bytes) == null -> IntakeRejection.UNSUPPORTED_TYPE
            pointer == "/mime" && cmd.mime == null -> IntakeRejection.UNSUPPORTED_TYPE
            else -> IntakeRejection.INVALID_FILE
        })
    }
}
