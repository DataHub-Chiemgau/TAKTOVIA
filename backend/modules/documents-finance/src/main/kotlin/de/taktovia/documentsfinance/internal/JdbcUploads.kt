package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.DocumentState
import de.taktovia.documentsfinance.api.MalwareScanner
import de.taktovia.documentsfinance.api.RequestUploadTicket
import de.taktovia.documentsfinance.api.ScanResult
import de.taktovia.documentsfinance.api.StoredDocumentView
import de.taktovia.documentsfinance.api.TicketState
import de.taktovia.documentsfinance.api.UploadPolicy
import de.taktovia.documentsfinance.api.UploadPurpose
import de.taktovia.documentsfinance.api.UploadTargetSigner
import de.taktovia.documentsfinance.api.UploadTicketView
import de.taktovia.documentsfinance.api.Uploads
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.audit.SecurityEvent
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Uploadticket, Quarantäne und Malware-Prüfung (WP-M4-21). Ablauf von [complete]: (A) Ticket atomar von ISSUED nach COMPLETING (`UPDATE … WHERE state = 'ISSUED' AND expires_at > now()`:
 * bei parallelem Abschluss gewinnt genau einer); (B) Objekt aus der Quarantäne lesen und **serverseitig** Größe, SHA-256 und Typ prüfen (außerhalb der Datenbanktransaktion);
 * (C) Dokument in Quarantäne anlegen und Ticket verbrauchen (eine Transaktion, Ereignis `document.upload_accepted`); (D) Prüfung: sauber -> Original schreibgeschützt ablegen und
 * `STORED`, infiziert -> `QUARANTINE_REJECTED` plus Sicherheitsereignis, Prüfer nicht verfügbar -> bleibt `QUARANTINED`. Objektspeicher und Prüfer laufen nie innerhalb einer offenen Transaktion.
 */
internal class JdbcUploads(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val security: SecurityEventPort, private val outbox: Outbox, private val store: TenantObjectStore,
    private val scanner: MalwareScanner, private val policy: UploadPolicy, private val signer: UploadTargetSigner?, private val clock: Clock,
) : Uploads {

    override fun requestTicket(ctx: RequestContext, cmd: RequestUploadTicket): UploadTicketView {
        require(ctx, SCOPE_WRITE); requireHuman(ctx)
        if (cmd.mime !in policy.allowedMime) throw ProblemException.invalid(Violation("/mime", ViolationCode.NOT_IN_ENUM))
        if (cmd.sizeBytes <= 0) throw ProblemException.invalid(Violation("/size_bytes", ViolationCode.BELOW_MINIMUM))
        if (cmd.sizeBytes > policy.maxBytes) throw ProblemException.invalid(Violation("/size_bytes", ViolationCode.ABOVE_MAXIMUM))
        if (!HEX64.matches(cmd.sha256)) throw ProblemException.invalid(Violation("/sha256", ViolationCode.PATTERN_MISMATCH))
        val id = UUID.randomUUID(); val key = "${cmd.purpose.prefix}/quarantine/$id"; val expires = clock.instant().plus(policy.ticketTtl)
        return ctxTx.run(ctx) { j ->
            if (j.query("SELECT 1 FROM taktovia.legal_entity WHERE id = ?", { _, _ -> 1 }, cmd.legalEntityId).isEmpty()) throw ProblemException.notFound()   // fremder Mandant: nicht auffindbar
            val t = j.query(
                "INSERT INTO taktovia.upload_ticket AS t (tenant_id, id, legal_entity_id, purpose, declared_mime, declared_size, declared_sha256, quarantine_key, issued_by, expires_at) " +
                    "VALUES (taktovia.current_tenant(),?,?,?,?,?,?,?,?, CAST(? AS timestamptz)) RETURNING $TICKET_COLUMNS",
                TICKET, id, cmd.legalEntityId, cmd.purpose.name, cmd.mime, cmd.sizeBytes, cmd.sha256, key, ctx.actor.subject, Timestamp.from(expires),
            ).single()
            view(t, ctx)
        }
    }

    override fun complete(ctx: RequestContext, ticketId: UUID, expectedRevision: Long): StoredDocumentView {
        require(ctx, SCOPE_WRITE); requireHuman(ctx)
        // (A) Ticket beanspruchen: genau ein Abschluss darf weiter
        val claimed = ctxTx.run(ctx) { j ->
            j.query("UPDATE taktovia.upload_ticket t SET state = 'COMPLETING' WHERE t.id = ? AND t.state = 'ISSUED' AND t.expires_at > clock_timestamp() AND t.issued_by = ? AND t.revision = ? RETURNING $TICKET_COLUMNS",
                TICKET, ticketId, ctx.actor.subject, expectedRevision).firstOrNull()
                ?: throw refusal(j, ctx, ticketId, expectedRevision)
        }
        val key = ObjectKey(ctx.tenant, claimed.quarantineKey)
        // (B) Objekt prüfen (außerhalb der Transaktion)
        val bytes = store.get(key)
        val reason = when {
            bytes == null -> RejectReason("OBJECT_MISSING", "/ticket")
            bytes.size.toLong() > policy.maxBytes -> RejectReason("SIZE_LIMIT", "/size_bytes")
            bytes.size.toLong() != claimed.declaredSize -> RejectReason("SIZE_MISMATCH", "/size_bytes")
            sha256(bytes) != claimed.declaredSha256 -> RejectReason("HASH_MISMATCH", "/sha256")
            ContentSniffer.detect(bytes) != claimed.declaredMime -> RejectReason("MIME_MISMATCH", "/mime")
            else -> null
        }
        if (reason != null) {
            ctxTx.run(ctx) { j -> j.update("UPDATE taktovia.upload_ticket SET state = 'REJECTED', reject_reason = ? WHERE id = ? AND state = 'COMPLETING'", reason.code, ticketId) }
            if (bytes != null) store.delete(key)   // nicht passendes Objekt wird nicht aufbewahrt
            throw ProblemException.invalid(Violation(reason.pointer, ViolationCode.INVALID_VALUE))
        }
        // (C) Dokument in Quarantäne anlegen, Ticket verbrauchen
        val documentId = UUID.randomUUID(); val objectKey = "${claimed.purpose.prefix}/originals/$documentId"
        val doc = ctxTx.run(ctx) { j ->
            val d = j.query(
                "INSERT INTO taktovia.expense_document AS d (tenant_id, id, legal_entity_id, object_key, original_sha256, mime, state, revision, created_by, original_received_at, upload_ticket_id, size_bytes) " +
                    "VALUES (taktovia.current_tenant(),?,?,?,?,?,'QUARANTINED',1,?,clock_timestamp(),?,?) RETURNING $DOC_COLUMNS",
                DOC, documentId, claimed.legalEntityId, objectKey, claimed.declaredSha256, claimed.declaredMime, ctx.actor.subject, ticketId, claimed.declaredSize,
            ).single()
            j.update("UPDATE taktovia.upload_ticket SET state = 'CONSUMED', document_id = ? WHERE id = ? AND state = 'COMPLETING'", documentId, ticketId)
            outbox.append(j, OutboxEvent(documentId, 1, "document.upload_accepted", linkedMapOf(
                "document_id" to documentId.toString(), "ticket_id" to ticketId.toString(), "purpose" to claimed.purpose.name, "mime" to claimed.declaredMime,
                "size_bytes" to claimed.declaredSize, "received_at" to d.view.receivedAt.toString(),   // ohne Hash: lange Hexfolgen können die Kartendatenerkennung (SEC-008) auslösen
            ), clock.instant(), correlationId = ctx.correlationId))
            d
        }
        // (D) Prüfung
        return finishScan(ctx, doc, bytes!!, claimed.quarantineKey)
    }

    override fun document(ctx: RequestContext, documentId: UUID): StoredDocumentView {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j -> visible(j, ctx, documentId).view }
    }

    override fun rescan(ctx: RequestContext, documentId: UUID): StoredDocumentView {
        require(ctx, SCOPE_READ)
        val d = ctxTx.run(ctx, readOnly = true) { j -> visible(j, ctx, documentId) }
        if (d.view.state != DocumentState.QUARANTINED) return d.view
        val quarantineKey = "${d.view.purpose.prefix}/quarantine/${d.ticketId}"
        val bytes = store.get(ObjectKey(ctx.tenant, quarantineKey)) ?: return d.view   // Objekt fehlt: bleibt in Quarantäne
        if (sha256(bytes) != d.view.sha256) throw ProblemException(ErrorCode.CONFLICT, "Das Objekt in der Quarantäne stimmt nicht mehr mit dem erfassten Hash überein.")
        return finishScan(ctx, DocRow(d.view, d.objectKey), bytes, quarantineKey)
    }

    override fun openOriginal(ctx: RequestContext, documentId: UUID): ByteArray {
        require(ctx, SCOPE_READ)
        val d = ctxTx.run(ctx, readOnly = true) { j -> visible(j, ctx, documentId) }
        if (d.view.state != DocumentState.STORED) throw ProblemException.notFound()   // Quarantäne und Ablehnung: nie ausgeliefert, nicht unterscheidbar von „gibt es nicht“
        val bytes = store.get(ObjectKey(ctx.tenant, d.objectKey)) ?: throw ProblemException.notFound()
        if (sha256(bytes) != d.view.sha256) throw ProblemException(ErrorCode.INTERNAL_ERROR)   // Integritätsverletzung des Originals: nie ausliefern
        return bytes
    }

    /** (D) Prüfergebnis anwenden; idempotent (nur aus QUARANTINED), das Dokument ist danach in genau einem Endzustand oder weiter in Quarantäne. */
    private fun finishScan(ctx: RequestContext, doc: DocRow, bytes: ByteArray, quarantineKey: String): StoredDocumentView {
        val result = try { scanner.scan(bytes) } catch (e: Exception) { ScanResult.Unavailable("Prüfer fehlgeschlagen: ${e.javaClass.simpleName}") }
        return when (result) {
            is ScanResult.Clean -> {
                val original = ObjectKey(ctx.tenant, doc.objectKey)
                if (!store.putIfAbsent(original, bytes, doc.view.mime) && sha256(store.get(original) ?: ByteArray(0)) != doc.view.sha256)
                    throw ProblemException(ErrorCode.CONFLICT, "Der Originalbestand enthält abweichende Daten für dieses Dokument.")
                val updated = ctxTx.run(ctx) { j ->
                    val u = j.query("UPDATE taktovia.expense_document d SET state = 'STORED', scan_engine = ?, scanned_at = clock_timestamp() WHERE d.id = ? AND d.state = 'QUARANTINED' RETURNING $DOC_COLUMNS", DOC, scanner.engine, doc.view.documentId).firstOrNull()
                    if (u != null) outbox.append(j, OutboxEvent(doc.view.documentId, 2, "document.quarantine_passed", linkedMapOf(
                        "document_id" to u.view.documentId.toString(), "mime" to u.view.mime, "scanned_at" to u.view.scannedAt?.toString()), clock.instant(), correlationId = ctx.correlationId))
                    u ?: j.query("SELECT $DOC_COLUMNS FROM taktovia.expense_document d WHERE d.id = ?", DOC, doc.view.documentId).single()
                }
                store.delete(ObjectKey(ctx.tenant, quarantineKey))   // Quarantänekopie entfernen; das Original liegt im Originalbestand
                updated.view
            }
            is ScanResult.Infected -> ctxTx.run(ctx) { j ->
                val u = j.query("UPDATE taktovia.expense_document d SET state = 'QUARANTINE_REJECTED', scan_engine = ?, scanned_at = clock_timestamp(), scan_signature = ? WHERE d.id = ? AND d.state = 'QUARANTINED' RETURNING $DOC_COLUMNS",
                    DOC, scanner.engine, result.signature.take(200), doc.view.documentId).firstOrNull()
                if (u != null) {
                    security.report(j, SecurityEvent(ActorRef.system("malware-scan"), AuditActions.SECURITY_MALWARE_DETECTED, ObjectRef("document", doc.view.documentId), ctx.correlationId,
                        mapOf("engine" to scanner.engine, "purpose" to doc.view.purpose.name)))
                    outbox.append(j, OutboxEvent(doc.view.documentId, 2, "document.quarantine_rejected", linkedMapOf(
                        "document_id" to doc.view.documentId.toString(), "engine" to scanner.engine, "scanned_at" to u.view.scannedAt?.toString()), clock.instant(), correlationId = ctx.correlationId))
                }
                (u ?: j.query("SELECT $DOC_COLUMNS FROM taktovia.expense_document d WHERE d.id = ?", DOC, doc.view.documentId).single()).view
            }
            is ScanResult.Unavailable -> doc.view   // bleibt in Quarantäne (fail-closed), `rescan` wiederholt die Prüfung
        }
    }

    /** Warum der Abschluss nicht möglich war: fremdes/unbekanntes Ticket 404, veraltete Revision im Zustand ISSUED 412, abgelaufen oder bereits verbraucht 409. */
    private fun refusal(j: JdbcOperations, ctx: RequestContext, ticketId: UUID, expectedRevision: Long): ProblemException {
        val t = j.query("SELECT $TICKET_COLUMNS, t.issued_by FROM taktovia.upload_ticket t WHERE t.id = ?", { rs, i -> TICKET.mapRow(rs, i)!! to rs.getString("issued_by") }, ticketId).firstOrNull()
        if (t == null || t.second != ctx.actor.subject) return ProblemException.notFound()
        val ticket = t.first
        return when {
            ticket.state != TicketState.ISSUED -> ProblemException(ErrorCode.CONFLICT, "Das Ticket ist bereits verbraucht und nicht wiederverwendbar.")
            !ticket.expiresAt.isAfter(clock.instant()) -> ProblemException(ErrorCode.CONFLICT, "Das Ticket ist abgelaufen; bitte ein neues anfordern.")
            ticket.revision != expectedRevision -> ProblemException(ErrorCode.REVISION_MISMATCH)
            else -> ProblemException(ErrorCode.CONFLICT)
        }
    }

    private class DocRow(val view: StoredDocumentView, val objectKey: String, val ticketId: UUID = view.ticketId)

    /** Sichtbarkeit: Besitzer oder Finanzrolle; sonst (und im fremden Mandanten durch RLS) nicht auffindbar. */
    private fun visible(j: JdbcOperations, ctx: RequestContext, id: UUID): DocRow {
        val r = j.query("SELECT $DOC_COLUMNS, d.created_by FROM taktovia.expense_document d WHERE d.id = ? AND d.upload_ticket_id IS NOT NULL", { rs, i -> DOC.mapRow(rs, i)!! to rs.getString("created_by") }, id).firstOrNull() ?: throw ProblemException.notFound()
        if (r.second != ctx.actor.subject && "finance" !in ctx.roles) throw ProblemException.notFound()
        return r.first
    }

    private fun view(t: Ticket, ctx: RequestContext): UploadTicketView = UploadTicketView(
        t.id, t.purpose, t.declaredMime, t.declaredSize, t.declaredSha256, policy.maxBytes, t.expiresAt, t.state, t.revision, t.quarantineKey,
        signer?.target("tenants/${ctx.tenant.value}/${t.quarantineKey}", t.expiresAt, t.declaredMime, t.declaredSize),
    )

    private fun require(ctx: RequestContext, scope: String) { if (!ctx.hasScope(scope)) throw ProblemException(ErrorCode.FORBIDDEN) }
    private fun requireHuman(ctx: RequestContext) { if (ctx.actor.type != ActorType.HUMAN) throw ProblemException(ErrorCode.FORBIDDEN) }
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private class RejectReason(val code: String, val pointer: String)
    private class Ticket(
        val id: UUID, val purpose: UploadPurpose, val declaredMime: String, val declaredSize: Long, val declaredSha256: String, val quarantineKey: String, val state: TicketState,
        val expiresAt: Instant, val revision: Long, val legalEntityId: UUID,
    )

    private companion object {
        const val SCOPE_WRITE = "receipts:write"
        const val SCOPE_READ = "receipts:read"
        val HEX64 = Regex("[0-9a-f]{64}")
        const val TICKET_COLUMNS = "t.id, t.purpose, t.declared_mime, t.declared_size, t.declared_sha256, t.quarantine_key, t.state, t.expires_at, t.revision, t.legal_entity_id"
        const val DOC_COLUMNS = "d.id, d.upload_ticket_id, d.object_key, d.original_sha256, d.mime, d.size_bytes, d.state, d.original_received_at, d.scanned_at, d.revision, d.legal_entity_id"
        val TICKET = RowMapper { rs, _ ->
            Ticket(rs.getObject("id", UUID::class.java), UploadPurpose.valueOf(rs.getString("purpose")), rs.getString("declared_mime"), rs.getLong("declared_size"), rs.getString("declared_sha256"),
                rs.getString("quarantine_key"), TicketState.valueOf(rs.getString("state")), rs.getTimestamp("expires_at").toInstant(), rs.getLong("revision"), rs.getObject("legal_entity_id", UUID::class.java))
        }
        val DOC = RowMapper { rs, _ ->
            val ticket = rs.getObject("upload_ticket_id", UUID::class.java)
            val key = rs.getString("object_key")
            DocRow(StoredDocumentView(rs.getObject("id", UUID::class.java), ticket, if (key.startsWith(UploadPurpose.DAMAGE_PHOTO.prefix + "/")) UploadPurpose.DAMAGE_PHOTO else UploadPurpose.RECEIPT,
                DocumentState.valueOf(rs.getString("state")), rs.getString("mime"), rs.getLong("size_bytes"), rs.getString("original_sha256"), rs.getTimestamp("original_received_at").toInstant(),
                rs.getTimestamp("scanned_at")?.toInstant(), rs.getLong("revision")), key)
        }
    }
}
