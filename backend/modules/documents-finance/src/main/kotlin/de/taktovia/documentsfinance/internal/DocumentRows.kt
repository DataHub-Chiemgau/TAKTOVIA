package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.CaseKind
import de.taktovia.documentsfinance.api.CaseRef
import de.taktovia.documentsfinance.api.DocumentKind
import de.taktovia.documentsfinance.api.DocumentState
import de.taktovia.documentsfinance.api.IntegrityState
import de.taktovia.documentsfinance.api.SourceChannel
import de.taktovia.documentsfinance.api.StoredDocumentView
import de.taktovia.documentsfinance.api.UploadPurpose
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.security.MessageDigest
import java.util.UUID

/** Eine Belegzeile mit allem, was die Bausteine des Moduls brauchen. `state` ist der Rohwert (spätere Pakete führen weitere Zustände), `view.state` nur der Quarantänezustand. */
internal class DocRow(val view: StoredDocumentView, val objectKey: String, val createdBy: String, val stateRaw: String, val fieldsJson: String) {
    val ticketId: UUID get() = view.ticketId
}

internal object DocumentRows {
    const val SCOPE_WRITE = "receipts:write"
    const val SCOPE_READ = "receipts:read"

    const val COLUMNS = "d.id, d.upload_ticket_id, d.object_key, d.original_sha256, d.mime, d.size_bytes, d.state, d.original_received_at, d.scanned_at, d.revision, d.legal_entity_id, " +
        "d.source_channel, d.document_kind, d.case_ref_kind, d.case_ref_id, d.sender_verified, d.integrity_state, d.created_by, d.current_fields::text AS current_fields_text, d.client_uuid, d.captured_at"

    val MAPPER = RowMapper { rs, _ ->
        val key = rs.getString("object_key")
        val raw = rs.getString("state")
        val caseKind = rs.getString("case_ref_kind")
        val view = StoredDocumentView(
            rs.getObject("id", UUID::class.java), rs.getObject("upload_ticket_id", UUID::class.java),
            if (key.startsWith(UploadPurpose.DAMAGE_PHOTO.prefix + "/")) UploadPurpose.DAMAGE_PHOTO else UploadPurpose.RECEIPT,
            DocumentState.entries.firstOrNull { it.name == raw } ?: DocumentState.STORED,   // spätere Belegzustände (Freigabe, Buchung) setzen STORED voraus
            rs.getString("mime"), rs.getLong("size_bytes"), rs.getString("original_sha256"), rs.getTimestamp("original_received_at").toInstant(), rs.getTimestamp("scanned_at")?.toInstant(),
            rs.getLong("revision"), SourceChannel.valueOf(rs.getString("source_channel")), DocumentKind.valueOf(rs.getString("document_kind")),
            caseKind?.let { CaseRef(CaseKind.valueOf(it), rs.getObject("case_ref_id", UUID::class.java)) }, rs.getObject("sender_verified") as Boolean?,
            IntegrityState.valueOf(rs.getString("integrity_state")), rs.getObject("legal_entity_id", UUID::class.java), raw,
            rs.getObject("client_uuid", UUID::class.java), rs.getTimestamp("captured_at")?.toInstant(),
        )
        DocRow(view, key, rs.getString("created_by"), raw, rs.getString("current_fields_text"))
    }

    /**
     * Beleg im Mandanten des Kontexts (RLS) und nur für Erfasser oder Finanzrolle; sonst (und im fremden Mandanten) nicht auffindbar (404). Nur über ein Ticket erfasste Belege
     * (`upload_ticket_id`). Mit `lock`: Zeilensperre `FOR UPDATE` (serialisiert Korrekturen und Integritätsfeststellung).
     */
    fun visible(j: JdbcOperations, ctx: RequestContext, id: UUID, lock: Boolean = false): DocRow {
        val r = (if (lock) j.query("SELECT $COLUMNS FROM taktovia.expense_document d WHERE d.id = ? AND d.upload_ticket_id IS NOT NULL FOR UPDATE", MAPPER, id)
            else j.query("SELECT $COLUMNS FROM taktovia.expense_document d WHERE d.id = ? AND d.upload_ticket_id IS NOT NULL", MAPPER, id)).firstOrNull() ?: throw ProblemException.notFound()
        if (r.createdBy != ctx.actor.subject && "finance" !in ctx.roles) throw ProblemException.notFound()
        return r
    }

    fun require(ctx: RequestContext, scope: String) { if (!ctx.hasScope(scope)) throw ProblemException(ErrorCode.FORBIDDEN) }
    fun requireHuman(ctx: RequestContext) { if (ctx.actor.type != ActorType.HUMAN) throw ProblemException(ErrorCode.FORBIDDEN) }
    fun isFinance(ctx: RequestContext) = "finance" in ctx.roles

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Akteur für Audit und Ereignisse: Mensch `user:<subject>` (Subject ist eine UUID), Dienstkonto `service:<subject>`. */
    fun actor(ctx: RequestContext): ActorRef =
        if (ctx.actor.type == ActorType.HUMAN) runCatching { ActorRef.user(UUID.fromString(ctx.actor.subject)) }.getOrElse { ActorRef("user:" + ctx.actor.subject.take(100).replace(Regex("[^A-Za-z0-9._:-]"), "-")) }
        else ActorRef.service(ctx.actor.subject.take(100).replace(Regex("[^A-Za-z0-9._:-]"), "-"))
}
