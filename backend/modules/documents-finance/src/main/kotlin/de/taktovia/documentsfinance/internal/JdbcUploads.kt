package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.CaseKind
import de.taktovia.documentsfinance.api.CaseRef
import de.taktovia.documentsfinance.api.DocumentKind
import de.taktovia.documentsfinance.api.DocumentState
import de.taktovia.documentsfinance.api.IngestFile
import de.taktovia.documentsfinance.api.CompletedReceipt
import de.taktovia.documentsfinance.api.IngestResult
import de.taktovia.documentsfinance.api.ReceiptRegistration
import de.taktovia.documentsfinance.api.IntegrityTrigger
import de.taktovia.documentsfinance.api.MalwareScanner
import de.taktovia.documentsfinance.api.RequestUploadTicket
import de.taktovia.documentsfinance.api.ScanResult
import de.taktovia.documentsfinance.api.SourceChannel
import de.taktovia.documentsfinance.api.StoredDocumentView
import de.taktovia.documentsfinance.api.TicketState
import de.taktovia.documentsfinance.api.UploadPolicy
import de.taktovia.documentsfinance.api.UploadPurpose
import de.taktovia.documentsfinance.api.UploadTargetSigner
import de.taktovia.documentsfinance.api.UploadTicketView
import de.taktovia.documentsfinance.api.Uploads
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_READ
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_WRITE
import de.taktovia.documentsfinance.internal.DocumentRows.require
import de.taktovia.documentsfinance.internal.DocumentRows.requireHuman
import de.taktovia.documentsfinance.internal.DocumentRows.sha256
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
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
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Uploadticket, Quarantäne und Malware-Prüfung (WP-M4-21) und gemeinsamer Anlagebefehl aller vier Kanäle (WP-M4-22). Ablauf von [completeTicket]: (A) Ticket atomar von ISSUED nach COMPLETING
 * (`UPDATE … WHERE state = 'ISSUED' AND expires_at > now()`: bei parallelem Abschluss gewinnt genau einer); (B) Objekt aus der Quarantäne lesen und **serverseitig** Größe, SHA-256 und Typ prüfen
 * (außerhalb der Datenbanktransaktion); (C) Dokument in Quarantäne anlegen (mit Kanal, Belegart, Vorgangsbezug, Revision 1) und Ticket verbrauchen (eine Transaktion, Ereignisse
 * `document.upload_accepted` und `expense.created`); (D) Prüfung: sauber -> Original schreibgeschützt ablegen und `STORED`, infiziert -> `QUARANTINE_REJECTED` plus Sicherheitsereignis, Prüfer
 * nicht verfügbar -> bleibt `QUARANTINED`. Objektspeicher und Prüfer laufen nie innerhalb einer offenen Transaktion.
 */
internal class JdbcUploads(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val security: SecurityEventPort, private val outbox: Outbox, private val store: TenantObjectStore,
    private val scanner: MalwareScanner, private val policy: UploadPolicy, private val signer: UploadTargetSigner?, private val clock: Clock, private val integrity: JdbcIntegrity,
) : Uploads {

    override fun requestTicket(ctx: RequestContext, cmd: RequestUploadTicket): UploadTicketView {
        require(ctx, SCOPE_WRITE); requireHuman(ctx)
        requireClientChannel(cmd)
        return issue(ctx, cmd, sourceRef = null, senderVerified = null, idempotent = false).ticket
    }

    // E-Mail-Eingang und Rechnungsimport sind serverseitige Kanäle (ReceiptIntake); ein Client kann sie nicht für sich beanspruchen.
    private fun requireClientChannel(cmd: RequestUploadTicket) {
        if (cmd.channel != SourceChannel.DRIVER_APP && cmd.channel != SourceChannel.DISPATCH_WEB) throw ProblemException.invalid(Violation("/source_channel", ViolationCode.NOT_IN_ENUM))
    }

    override fun registerReceipt(ctx: RequestContext, cmd: RequestUploadTicket): ReceiptRegistration {
        require(ctx, SCOPE_WRITE); requireHuman(ctx)
        requireClientChannel(cmd)
        if (cmd.clientUuid == null) throw ProblemException.invalid(Violation("/client_uuid", ViolationCode.REQUIRED))
        return issue(ctx, cmd, sourceRef = null, senderVerified = null, idempotent = true)
    }

    /**
     * Stellt ein Ticket aus (ohne Akteurprüfung; die öffentlichen Wege prüfen vorher). Mit `idempotent` (Client-UUID, WP-M4-26) wird die Ausstellung je `(Mandant, UUID)` über eine
     * Transaktionssperre serialisiert und ein vorhandener Beleg bzw. ein noch gültiges Ticket derselben UUID zurückgegeben statt eines zweiten angelegt; ein abweichender Hash ist 409.
     */
    private fun issue(ctx: RequestContext, cmd: RequestUploadTicket, sourceRef: String?, senderVerified: Boolean?, idempotent: Boolean): ReceiptRegistration {
        if (cmd.mime !in policy.allowedMime) throw ProblemException.invalid(Violation("/mime", ViolationCode.NOT_IN_ENUM))
        if (cmd.sizeBytes <= 0) throw ProblemException.invalid(Violation("/size_bytes", ViolationCode.BELOW_MINIMUM))
        if (cmd.sizeBytes > policy.maxBytes) throw ProblemException.invalid(Violation("/size_bytes", ViolationCode.ABOVE_MAXIMUM))
        if (!HEX64.matches(cmd.sha256)) throw ProblemException.invalid(Violation("/sha256", ViolationCode.PATTERN_MISMATCH))
        val id = UUID.randomUUID(); val key = "${cmd.purpose.prefix}/quarantine/$id"; val expires = clock.instant().plus(policy.ticketTtl)
        return ctxTx.run(ctx) { j ->
            if (j.query("SELECT 1 FROM taktovia.legal_entity WHERE id = ?", { _, _ -> 1 }, cmd.legalEntityId).isEmpty()) throw ProblemException.notFound()   // fremder Mandant: nicht auffindbar
            val known = if (idempotent) lookup(j, ctx, cmd) else null
            if (known != null) return@run known
            cmd.caseRef?.let { requireCase(j, ctx, cmd.channel, it) }
            val t = j.query(
                "INSERT INTO taktovia.upload_ticket AS t (tenant_id, id, legal_entity_id, purpose, declared_mime, declared_size, declared_sha256, quarantine_key, issued_by, expires_at, " +
                    "source_channel, document_kind, case_ref_kind, case_ref_id, source_ref, sender_verified, client_uuid, captured_at) " +
                    "VALUES (taktovia.current_tenant(),?,?,?,?,?,?,?,?, CAST(? AS timestamptz), ?,?,?,?,?,?,?, CAST(? AS timestamptz)) RETURNING $TICKET_COLUMNS",
                TICKET, id, cmd.legalEntityId, cmd.purpose.name, cmd.mime, cmd.sizeBytes, cmd.sha256, key, ctx.actor.subject, Timestamp.from(expires),
                cmd.channel.name, cmd.kind.name, cmd.caseRef?.kind?.name, cmd.caseRef?.id, sourceRef, senderVerified, cmd.clientUuid, cmd.capturedAt?.let { Timestamp.from(it) },
            ).single()
            ReceiptRegistration(view(t, ctx), true)
        }
    }

    /** Vorhandenes zur Client-UUID (unter der Transaktionssperre): Beleg, noch gültiges Ticket oder nichts. Abweichender Hash/Typ/Größe: 409. */
    private fun lookup(j: JdbcOperations, ctx: RequestContext, cmd: RequestUploadTicket): ReceiptRegistration? {
        j.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", { _, _ -> 1 }, "receipt-client-uuid:${ctx.tenant.value}:${cmd.clientUuid}")
        val doc = j.query("SELECT $DOC_COLUMNS FROM taktovia.expense_document d WHERE d.client_uuid = ?", DocumentRows.MAPPER, cmd.clientUuid).firstOrNull()
        if (doc != null) {
            if (doc.createdBy != ctx.actor.subject && "finance" !in ctx.roles) throw ProblemException(ErrorCode.CONFLICT, "Die Client-UUID ist bereits vergeben.")
            if (doc.view.sha256 != cmd.sha256) throw hashConflict()
            val t = j.query("SELECT $TICKET_COLUMNS FROM taktovia.upload_ticket t WHERE t.id = ?", TICKET, doc.ticketId).single()
            return ReceiptRegistration(view(t, ctx), false)
        }
        val mine = j.query("SELECT $TICKET_COLUMNS FROM taktovia.upload_ticket t WHERE t.client_uuid = ? AND t.issued_by = ? ORDER BY t.issued_at DESC, t.id", TICKET, cmd.clientUuid, ctx.actor.subject)
        val now = clock.instant()
        for (t in mine) {
            val live = t.expiresAt.isAfter(now)
            when {
                t.state == TicketState.ISSUED && live -> {
                    if (t.declaredSha256 != cmd.sha256 || t.declaredSize != cmd.sizeBytes || t.declaredMime != cmd.mime) throw hashConflict()
                    return ReceiptRegistration(view(t, ctx), false)   // Wiederaufnahme: dasselbe Ticket, derselbe Schlüssel im Objektspeicher
                }
                t.state == TicketState.COMPLETING && live -> throw ProblemException(ErrorCode.CONFLICT, "Der Abschluss dieses Entwurfs läuft bereits; später erneut versuchen.")
            }
        }
        return null
    }

    private fun hashConflict() = ProblemException(ErrorCode.CONFLICT, "Die Client-UUID ist bereits mit einem anderen Inhalt (Hash) vergeben; es wird nichts überschrieben.")

    /**
     * Der Vorgang muss im Mandanten existieren; ein fremder gilt als nicht vorhanden (404). Nur lesend auf Tabellen anderer Module (RLS begrenzt auf den Mandanten). Aus der Fahrer-App
     * darf ein Fahrer (ohne Finanzrolle) nur Vorgänge wählen, denen er zugewiesen ist (Fahrzeug einer eigenen, nicht stornierten Schicht bzw. eigene Schicht; `driver_ref` = IdP-Subject wie in `fleet-safety`):
     * ein vorhandener, aber nicht zugewiesener Vorgang ist 403 (WP-M4-25, Kap. 03 §3 Nr. 1); der Entwurf bleibt im Gerät.
     */
    private fun requireCase(j: JdbcOperations, ctx: RequestContext, channel: SourceChannel, ref: CaseRef) {
        val exists = when (ref.kind) {
            CaseKind.VEHICLE -> j.query("SELECT 1 FROM taktovia.vehicle WHERE id = ?", { _, _ -> 1 }, ref.id)
            CaseKind.SHIFT -> j.query("SELECT 1 FROM taktovia.staff_shift WHERE id = ?", { _, _ -> 1 }, ref.id)
        }
        if (exists.isEmpty()) throw ProblemException.notFound()
        if (channel != SourceChannel.DRIVER_APP || ctx.actor.type != ActorType.HUMAN || "finance" in ctx.roles) return   // die Finanzrolle ist nicht an eine Fahrerzuweisung gebunden
        val own = when (ref.kind) {
            CaseKind.VEHICLE -> j.query("SELECT 1 FROM taktovia.staff_shift WHERE vehicle_id = ? AND driver_ref::text = lower(?) AND upper(state) NOT IN ('CANCELLED', 'CANCELED') LIMIT 1", { _, _ -> 1 }, ref.id, ctx.actor.subject)
            CaseKind.SHIFT -> j.query("SELECT 1 FROM taktovia.staff_shift WHERE id = ? AND driver_ref::text = lower(?) AND upper(state) NOT IN ('CANCELLED', 'CANCELED')", { _, _ -> 1 }, ref.id, ctx.actor.subject)
        }
        if (own.isEmpty()) throw ProblemException(ErrorCode.FORBIDDEN, "Der Vorgang ist dem Fahrer nicht zugewiesen.")
    }

    override fun complete(ctx: RequestContext, ticketId: UUID, expectedRevision: Long): StoredDocumentView {
        require(ctx, SCOPE_WRITE); requireHuman(ctx)
        return completeTicket(ctx, ticketId, expectedRevision, idempotent = false).first
    }

    override fun completeReceipt(ctx: RequestContext, ticketId: UUID, expectedRevision: Long): CompletedReceipt {
        require(ctx, SCOPE_WRITE); requireHuman(ctx)
        val r = completeTicket(ctx, ticketId, expectedRevision, idempotent = true)
        return CompletedReceipt(r.first, r.second)
    }

    override fun ingest(ctx: RequestContext, cmd: IngestFile): IngestResult {
        require(ctx, SCOPE_WRITE)
        val bytes = cmd.bytes
        if (bytes.isEmpty()) throw ProblemException.invalid(Violation("/file", ViolationCode.TOO_SHORT))
        if (bytes.size.toLong() > policy.maxBytes) throw ProblemException.invalid(Violation("/file", ViolationCode.ABOVE_MAXIMUM))
        val detected = ContentSniffer.detect(bytes)
        if (detected == null || detected !in policy.allowedMime) throw ProblemException.invalid(Violation("/mime", ViolationCode.NOT_IN_ENUM))
        if (cmd.mime != null && cmd.mime != detected) throw ProblemException.invalid(Violation("/mime", ViolationCode.INVALID_VALUE))
        val t = try {
            issue(ctx, RequestUploadTicket(cmd.legalEntityId, cmd.purpose, detected, bytes.size.toLong(), sha256(bytes), cmd.channel, cmd.kind, cmd.caseRef), cmd.sourceRef, cmd.senderVerified, idempotent = false).ticket
        } catch (e: DuplicateKeyException) {
            return IngestResult.Duplicate(existing(ctx, cmd.channel, cmd.sourceRef ?: throw e))   // Quelle schon bekannt: kein zweiter Beleg
        }
        store.put(ObjectKey(ctx.tenant, t.uploadKey), bytes, detected)
        return IngestResult.Created(completeTicket(ctx, t.ticketId, t.revision, idempotent = false).first)
    }

    private fun existing(ctx: RequestContext, channel: SourceChannel, sourceRef: String): StoredDocumentView? = ctxTx.run(ctx, readOnly = true) { j ->
        j.query("SELECT $DOC_COLUMNS FROM taktovia.expense_document d WHERE d.source_channel = ? AND d.source_ref = ?", DocumentRows.MAPPER, channel.name, sourceRef).firstOrNull()?.view
    }

    /**
     * Gibt Beleg und „neu angelegt“ zurück. Mit `idempotent` (Ticket mit Client-UUID) liefert ein bereits verbrauchtes Ticket den vorhandenen Beleg (`created` = false); sonst 409.
     */
    private fun completeTicket(ctx: RequestContext, ticketId: UUID, expectedRevision: Long, idempotent: Boolean): Pair<StoredDocumentView, Boolean> {
        // (A) Ticket beanspruchen: genau ein Abschluss darf weiter
        val claim = ctxTx.run(ctx) { j ->
            val won = j.query("UPDATE taktovia.upload_ticket t SET state = 'COMPLETING' WHERE t.id = ? AND t.state = 'ISSUED' AND t.expires_at > clock_timestamp() AND t.issued_by = ? AND t.revision = ? RETURNING $TICKET_COLUMNS",
                TICKET, ticketId, ctx.actor.subject, expectedRevision).firstOrNull()
            if (won != null) Claim(won, null)
            else {
                val done = if (idempotent) j.query("SELECT $DOC_COLUMNS FROM taktovia.expense_document d JOIN taktovia.upload_ticket t ON t.tenant_id = d.tenant_id AND t.id = d.upload_ticket_id " +
                    "WHERE t.id = ? AND t.issued_by = ? AND t.client_uuid IS NOT NULL", DocumentRows.MAPPER, ticketId, ctx.actor.subject).firstOrNull() else null
                if (done == null) throw refusal(j, ctx, ticketId, expectedRevision)
                Claim(null, done)
            }
        }
        if (claim.ticket == null) {
            val done = claim.existing!!
            // ein paralleler oder wiederholter Lauf hat den Beleg schon angelegt: übrig gebliebene Quarantänekopie des Wiederholers entfernen, der Beleg selbst bleibt unberührt
            store.delete(ObjectKey(ctx.tenant, "${done.view.purpose.prefix}/quarantine/$ticketId"))
            return done.view to false
        }
        val claimed = claim.ticket
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
        val doc = try { ctxTx.run(ctx) { j ->
            val d = j.query(
                "INSERT INTO taktovia.expense_document AS d (tenant_id, id, legal_entity_id, object_key, original_sha256, mime, state, revision, created_by, original_received_at, upload_ticket_id, size_bytes, " +
                    "source_channel, document_kind, case_ref_kind, case_ref_id, source_ref, sender_verified, client_uuid, captured_at) " +
                    "VALUES (taktovia.current_tenant(),?,?,?,?,?,'QUARANTINED',1,?,clock_timestamp(),?,?, ?,?,?,?,?,?,?, CAST(? AS timestamptz)) RETURNING $DOC_COLUMNS",
                DocumentRows.MAPPER, documentId, claimed.legalEntityId, objectKey, claimed.declaredSha256, claimed.declaredMime, ctx.actor.subject, ticketId, claimed.declaredSize,
                claimed.channel.name, claimed.kind.name, claimed.caseRef?.kind?.name, claimed.caseRef?.id, claimed.sourceRef, claimed.senderVerified, claimed.clientUuid, claimed.capturedAt?.let { Timestamp.from(it) },
            ).single()
            j.update("INSERT INTO taktovia.document_revision (tenant_id, id, document_id, revision, field_values, edited_by, reason) VALUES (taktovia.current_tenant(),?,?,1,'{}'::jsonb,?,'CREATED')",
                UUID.randomUUID(), documentId, ctx.actor.subject)
            j.update("UPDATE taktovia.upload_ticket SET state = 'CONSUMED', document_id = ? WHERE id = ? AND state = 'COMPLETING'", documentId, ticketId)
            audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_EXPENSE_CREATED, ObjectRef("expense", documentId), ctx.correlationId,
                mapOf("source_channel" to claimed.channel.name, "document_kind" to claimed.kind.name)))
            outbox.append(j, OutboxEvent(documentId, 1, "document.upload_accepted", linkedMapOf(
                "document_id" to documentId.toString(), "ticket_id" to ticketId.toString(), "purpose" to claimed.purpose.name, "mime" to claimed.declaredMime,
                "size_bytes" to claimed.declaredSize, "received_at" to d.view.receivedAt.toString(),   // ohne Hash: lange Hexfolgen können die Kartendatenerkennung (SEC-008) auslösen
            ), clock.instant(), correlationId = ctx.correlationId))
            outbox.append(j, OutboxEvent(documentId, 1, "expense.created", linkedMapOf(
                "document_id" to documentId.toString(), "source_channel" to claimed.channel.name, "document_kind" to claimed.kind.name, "received_at" to d.view.receivedAt.toString(),
                "case_ref" to claimed.caseRef?.let { linkedMapOf("kind" to it.kind.name, "id" to it.id.toString()) }, "sender_verified" to claimed.senderVerified,
            ).also { m -> if (claimed.clientUuid != null) { m["client_uuid"] = claimed.clientUuid.toString(); claimed.capturedAt?.let { m["captured_at"] = it.toString() } } }, clock.instant(), correlationId = ctx.correlationId))
            d
        } } catch (e: DuplicateKeyException) {
            // Zweites Ticket derselben Client-UUID (z. B. nach Ablauf des ersten) trifft auf den schon angelegten Beleg: kein zweiter Beleg, das Ticket endet als Dublette.
            val clientUuid = claimed.clientUuid ?: throw e
            val existing = ctxTx.run(ctx) { j ->
                j.update("UPDATE taktovia.upload_ticket SET state = 'REJECTED', reject_reason = 'DUPLICATE' WHERE id = ? AND state = 'COMPLETING'", ticketId)
                j.query("SELECT $DOC_COLUMNS FROM taktovia.expense_document d WHERE d.client_uuid = ?", DocumentRows.MAPPER, clientUuid).firstOrNull()
            } ?: throw e
            store.delete(key)
            if (existing.view.sha256 != claimed.declaredSha256) throw hashConflict()
            return existing.view to false
        }
        // (D) Prüfung
        return finishScan(ctx, doc, bytes!!, claimed.quarantineKey) to true
    }

    private class Claim(val ticket: Ticket?, val existing: DocRow?)

    override fun document(ctx: RequestContext, documentId: UUID): StoredDocumentView {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j -> DocumentRows.visible(j, ctx, documentId).view }
    }

    override fun rescan(ctx: RequestContext, documentId: UUID): StoredDocumentView {
        require(ctx, SCOPE_READ)
        val d = ctxTx.run(ctx, readOnly = true) { j -> DocumentRows.visible(j, ctx, documentId) }
        if (d.view.state != DocumentState.QUARANTINED) return d.view
        val quarantineKey = "${d.view.purpose.prefix}/quarantine/${d.ticketId}"
        val bytes = store.get(ObjectKey(ctx.tenant, quarantineKey)) ?: return d.view   // Objekt fehlt: bleibt in Quarantäne
        if (sha256(bytes) != d.view.sha256) throw ProblemException(ErrorCode.CONFLICT, "Das Objekt in der Quarantäne stimmt nicht mehr mit dem erfassten Hash überein.")
        return finishScan(ctx, d, bytes, quarantineKey)
    }

    override fun openOriginal(ctx: RequestContext, documentId: UUID): ByteArray {
        require(ctx, SCOPE_READ)
        val d = ctxTx.run(ctx, readOnly = true) { j -> DocumentRows.visible(j, ctx, documentId) }
        if (d.view.state != DocumentState.STORED) throw ProblemException.notFound()   // Quarantäne und Ablehnung: nie ausgeliefert, nicht unterscheidbar von „gibt es nicht“
        if (d.view.integrity == de.taktovia.documentsfinance.api.IntegrityState.VIOLATED) throw IntegrityChecksSupport.violation()   // einmal verletzt: nie wieder ausliefern
        val bytes = store.get(ObjectKey(ctx.tenant, d.objectKey))
        // Integritätsprüfung bei der Auslieferung (WP-M4-24): Abweichung oder fehlendes Objekt wird nie ausgeliefert, sondern als Vorfall festgehalten.
        if (bytes == null || sha256(bytes) != d.view.sha256) {
            integrity.check(ctx, documentId, IntegrityTrigger.DELIVERY)
            throw IntegrityChecksSupport.violation()
        }
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
                    val u = j.query("UPDATE taktovia.expense_document d SET state = 'STORED', scan_engine = ?, scanned_at = clock_timestamp() WHERE d.id = ? AND d.state = 'QUARANTINED' RETURNING $DOC_COLUMNS",
                        DocumentRows.MAPPER, scanner.engine, doc.view.documentId).firstOrNull()
                    if (u != null) {
                        outbox.append(j, OutboxEvent(doc.view.documentId, 2, "document.quarantine_passed", linkedMapOf(
                            "document_id" to u.view.documentId.toString(), "mime" to u.view.mime, "scanned_at" to u.view.scannedAt?.toString()), clock.instant(), correlationId = ctx.correlationId))
                        if (u.view.purpose == UploadPurpose.RECEIPT) DuplicateHook.afterChange(j, audit, outbox, clock, ctx, u.view.documentId)   // Dublettenerkennung (WP-M4-32): identische Datei im Mandanten
                    }
                    u ?: j.query("SELECT $DOC_COLUMNS FROM taktovia.expense_document d WHERE d.id = ?", DocumentRows.MAPPER, doc.view.documentId).single()
                }
                store.delete(ObjectKey(ctx.tenant, quarantineKey))   // Quarantänekopie entfernen; das Original liegt im Originalbestand
                updated.view
            }
            is ScanResult.Infected -> ctxTx.run(ctx) { j ->
                val u = j.query("UPDATE taktovia.expense_document d SET state = 'QUARANTINE_REJECTED', scan_engine = ?, scanned_at = clock_timestamp(), scan_signature = ? WHERE d.id = ? AND d.state = 'QUARANTINED' RETURNING $DOC_COLUMNS",
                    DocumentRows.MAPPER, scanner.engine, result.signature.take(200), doc.view.documentId).firstOrNull()
                if (u != null) {
                    security.report(j, SecurityEvent(ActorRef.system("malware-scan"), AuditActions.SECURITY_MALWARE_DETECTED, ObjectRef("document", doc.view.documentId), ctx.correlationId,
                        mapOf("engine" to scanner.engine, "purpose" to doc.view.purpose.name)))
                    outbox.append(j, OutboxEvent(doc.view.documentId, 2, "document.quarantine_rejected", linkedMapOf(
                        "document_id" to doc.view.documentId.toString(), "engine" to scanner.engine, "scanned_at" to u.view.scannedAt?.toString()), clock.instant(), correlationId = ctx.correlationId))
                }
                (u ?: j.query("SELECT $DOC_COLUMNS FROM taktovia.expense_document d WHERE d.id = ?", DocumentRows.MAPPER, doc.view.documentId).single()).view
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

    private fun view(t: Ticket, ctx: RequestContext): UploadTicketView = UploadTicketView(
        t.id, t.purpose, t.declaredMime, t.declaredSize, t.declaredSha256, policy.maxBytes, t.expiresAt, t.state, t.revision, t.quarantineKey,
        if (t.state == TicketState.ISSUED) signer?.target("tenants/${ctx.tenant.value}/${t.quarantineKey}", t.expiresAt, t.declaredMime, t.declaredSize) else null,
        t.clientUuid, t.capturedAt, t.documentId,
    )

    private class RejectReason(val code: String, val pointer: String)
    private class Ticket(
        val id: UUID, val purpose: UploadPurpose, val declaredMime: String, val declaredSize: Long, val declaredSha256: String, val quarantineKey: String, val state: TicketState,
        val expiresAt: Instant, val revision: Long, val legalEntityId: UUID, val channel: SourceChannel, val kind: DocumentKind, val caseRef: CaseRef?, val sourceRef: String?, val senderVerified: Boolean?,
        val clientUuid: UUID?, val capturedAt: Instant?, val documentId: UUID?,
    )

    private companion object {
        val HEX64 = Regex("[0-9a-f]{64}")
        const val TICKET_COLUMNS = "t.id, t.purpose, t.declared_mime, t.declared_size, t.declared_sha256, t.quarantine_key, t.state, t.expires_at, t.revision, t.legal_entity_id, " +
            "t.source_channel, t.document_kind, t.case_ref_kind, t.case_ref_id, t.source_ref, t.sender_verified, t.client_uuid, t.captured_at, t.document_id"
        val TICKET = RowMapper { rs, _ ->
            val caseKind = rs.getString("case_ref_kind")
            Ticket(rs.getObject("id", UUID::class.java), UploadPurpose.valueOf(rs.getString("purpose")), rs.getString("declared_mime"), rs.getLong("declared_size"), rs.getString("declared_sha256"),
                rs.getString("quarantine_key"), TicketState.valueOf(rs.getString("state")), rs.getTimestamp("expires_at").toInstant(), rs.getLong("revision"), rs.getObject("legal_entity_id", UUID::class.java),
                SourceChannel.valueOf(rs.getString("source_channel")), DocumentKind.valueOf(rs.getString("document_kind")), caseKind?.let { CaseRef(CaseKind.valueOf(it), rs.getObject("case_ref_id", UUID::class.java)) },
                rs.getString("source_ref"), rs.getObject("sender_verified") as Boolean?,
                rs.getObject("client_uuid", UUID::class.java), rs.getTimestamp("captured_at")?.toInstant(), rs.getObject("document_id", UUID::class.java))
        }
    }
}

/** Spaltenliste der Belegzeile (siehe [DocumentRows]); als Konstante, weil der Query-Lint nur Konstanten als Interpolation zulässt. */
private const val DOC_COLUMNS = DocumentRows.COLUMNS
