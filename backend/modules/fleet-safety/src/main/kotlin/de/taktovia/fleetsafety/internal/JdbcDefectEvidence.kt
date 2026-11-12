package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.DefectEvidenceUploads
import de.taktovia.fleetsafety.api.DefectEvidenceWiring
import de.taktovia.fleetsafety.api.EvidenceConfidentiality
import de.taktovia.fleetsafety.api.EvidenceDetail
import de.taktovia.fleetsafety.api.EvidenceState
import de.taktovia.fleetsafety.api.EvidenceTicketView
import de.taktovia.fleetsafety.api.IntegrityReport
import de.taktovia.fleetsafety.api.LinkedObject
import de.taktovia.fleetsafety.api.MediaInspectors
import de.taktovia.fleetsafety.api.MediaVerdict
import de.taktovia.fleetsafety.api.RequestEvidenceTicket
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.ObjectPurposeCheck
import de.taktovia.platform.storage.SignedUrl
import de.taktovia.platform.tenant.TenantId
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.security.MessageDigest
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Uploadticket, Quarantäne, Medienprüfung und signierter Abruf der Schadenfotos (WP-M3-44), siehe [DefectEvidenceUploads]. Zugriffe auf den Objektspeicher und die Medienprüfung
 * laufen **nie innerhalb einer offenen SQL-Transaktion** (Leitlinie 3): lesen, außerhalb prüfen, in kurzer Transaktion unter Zeilensperre entscheiden.
 */
internal class JdbcDefectEvidence(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val clock: Clock, private val wiring: DefectEvidenceWiring,
) : DefectEvidenceUploads {
    private val store get() = wiring.store
    private val policy get() = wiring.policy

    // ---------------- Ticket ----------------

    override fun requestTicket(ctx: RequestContext, cmd: RequestEvidenceTicket): EvidenceTicketView {
        DefectRoles.reporterRole(ctx) ?: throw ProblemException(ErrorCode.FORBIDDEN, "Fotos zu Schäden laden Fahrer, Disposition und Werkstatt hoch.")
        val actor = actor(ctx)
        try {
            return ctxTx.run(ctx) { j -> doRequest(j, ctx, actor, cmd) }
        } catch (e: DataIntegrityViolationException) {
            // gleichzeitige Anforderung derselben Datei: genau ein Ticket
            if (sqlState(e) == "23505") return ctxTx.run(ctx, readOnly = true) { j -> findOwn(j, actor, cmd.sha256Hex)?.let { toView(it) } } ?: throw e
            throw e
        }
    }

    private fun doRequest(j: JdbcOperations, ctx: RequestContext, actor: ActorRef, cmd: RequestEvidenceTicket): EvidenceTicketView {
        findOwn(j, actor, cmd.sha256Hex)?.let { existing ->
            if (existing.state == EvidenceState.ISSUED && existing.expiresAt.isBefore(clock.instant())) {
                j.update("UPDATE taktovia.defect_evidence_upload SET state = 'REJECTED', reject_reason = 'EXPIRED' WHERE id = ? AND state = 'ISSUED'", existing.id)
            } else {
                if (existing.mime != cmd.mime || existing.sizeBytes != cmd.sizeBytes) throw ProblemException(ErrorCode.CONFLICT, "Zu dieser Datei besteht ein Ticket mit anderen Angaben.")
                return toView(existing)
            }
        }
        val id = UUID.randomUUID(); val now = clock.instant()
        return toView(j.query(
            "INSERT INTO taktovia.defect_evidence_upload AS u (tenant_id, id, uploaded_by, declared_mime, declared_size, declared_sha256, captured_at, quarantine_key, expires_at, created_at, confidentiality) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING $COLUMNS",
            MAPPER, id, actor.value, cmd.mime, cmd.sizeBytes, cmd.sha256Hex, Timestamp.from(cmd.capturedAt), "quarantine/defect-evidence/$id", Timestamp.from(now.plus(policy.ticketTtl)), Timestamp.from(now),
            cmd.confidentiality,
        ).single())
    }

    override fun get(ctx: RequestContext, ticketId: UUID): EvidenceTicketView = ctxTx.run(ctx, readOnly = true) { j ->
        val row = j.query("SELECT $COLUMNS FROM taktovia.defect_evidence_upload u WHERE u.id = ?", MAPPER, ticketId).firstOrNull() ?: throw ProblemException.notFound()
        // ein Ticket gehört dem Urheber; Disposition und Werkstatt sehen Tickets, die an eine Akte gebunden sind (Prüfansicht)
        if (row.uploadedBy != actor(ctx).value && (DefectRoles.driverOnly(ctx) || row.defectId == null)) throw ProblemException.notFound()
        if (row.confidentiality == EvidenceConfidentiality.CONFIDENTIAL && row.uploadedBy != actor(ctx).value && !DefectConfidentiality.privileged(ctx.roles)) throw ProblemException.notFound()
        toView(row)
    }

    // ---------------- Abschluss und Prüfung ----------------

    override fun complete(ctx: RequestContext, ticketId: UUID, expectedRevision: Long): EvidenceTicketView {
        val actor = actor(ctx)
        val pre = ctxTx.run(ctx, readOnly = true) { j -> j.query("SELECT $COLUMNS FROM taktovia.defect_evidence_upload u WHERE u.id = ? AND u.uploaded_by = ?", MAPPER, ticketId, actor.value).firstOrNull() }
            ?: throw ProblemException.notFound()
        if (pre.revision != expectedRevision) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Das Ticket wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        if (pre.state != EvidenceState.ISSUED) throw ProblemException(ErrorCode.CONFLICT, "Der Upload wurde bereits abgeschlossen.")
        // Prüfung am gespeicherten Objekt, außerhalb der Transaktion
        val reject: String? = if (clock.instant().isAfter(pre.expiresAt)) "EXPIRED" else {
            val bytes = store.get(ObjectKey(ctx.tenant, pre.uploadKey))
            when {
                bytes == null -> "OBJECT_MISSING"
                bytes.size > policy.maxBytes -> "SIZE_LIMIT"
                bytes.size.toLong() != pre.sizeBytes -> "SIZE_MISMATCH"
                sha256(bytes) != pre.sha256Hex -> "HASH_MISMATCH"
                MediaInspectors.sniff(bytes) != pre.mime -> "MIME_MISMATCH"
                else -> null
            }
        }
        val decided = ctxTx.run(ctx) { j -> decide(j, ctx, actor, ticketId, expectedRevision, reject) }
        if (decided.state == EvidenceState.REJECTED) throw rejected(decided.rejectReason)
        val result = reviewOne(ctx.tenant, ticketId)
        if (result.state == EvidenceState.REJECTED) throw rejected(result.rejectReason)
        return result
    }

    @AuditRequired
    private fun decide(j: JdbcOperations, ctx: RequestContext, actor: ActorRef, id: UUID, expected: Long, reject: String?): EvidenceTicketView {
        val row = j.query("SELECT $COLUMNS FROM taktovia.defect_evidence_upload u WHERE u.id = ? AND u.uploaded_by = ? FOR UPDATE", MAPPER, id, actor.value).firstOrNull() ?: throw ProblemException.notFound()
        if (row.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Das Ticket wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        if (row.state != EvidenceState.ISSUED) throw ProblemException(ErrorCode.CONFLICT, "Der Upload wurde bereits abgeschlossen.")
        val updated = if (reject != null) j.query("UPDATE taktovia.defect_evidence_upload u SET state = 'REJECTED', reject_reason = ? WHERE u.id = ? RETURNING $COLUMNS", MAPPER, reject, id).single()
        else j.query("UPDATE taktovia.defect_evidence_upload u SET state = 'IN_REVIEW' WHERE u.id = ? RETURNING $COLUMNS", MAPPER, id).single()
        if (reject != null) audit.record(j, AuditEntry(actor, AuditActions.DEFECT_EVIDENCE_REJECTED, ObjectRef("defect_evidence", id), ctx.correlationId, mapOf("reason" to reject, "stage" to "upload")))
        return toView(updated)
    }

    override fun reviewPending(tenant: TenantId, limit: Int): Int {
        val ids = ctxTx.runSystem(tenant, readOnly = true) { j -> j.queryForList("SELECT id FROM taktovia.defect_evidence_upload WHERE state = 'IN_REVIEW' ORDER BY created_at, id LIMIT ?", UUID::class.java, limit).filterNotNull() }
        return ids.count { reviewOne(tenant, it).state != EvidenceState.IN_REVIEW }
    }

    /** Prüft ein Ticket in Quarantäne: Objekt lesen, prüfen (außerhalb der Transaktion), Ergebnis unter Zeilensperre festhalten. Nicht verfügbarer Prüfer: bleibt in Prüfung. */
    private fun reviewOne(tenant: TenantId, id: UUID): EvidenceTicketView {
        val row = ctxTx.runSystem(tenant, readOnly = true) { j -> j.query("SELECT $COLUMNS FROM taktovia.defect_evidence_upload u WHERE u.id = ?", MAPPER, id).firstOrNull() } ?: throw ProblemException.notFound()
        if (row.state != EvidenceState.IN_REVIEW) return toView(row)
        val bytes = store.get(ObjectKey(tenant, row.uploadKey))
        val verdict: MediaVerdict = if (bytes == null) MediaVerdict.Invalid("OBJECT_MISSING") else wiring.inspector.inspect(bytes, row.mime)
        val finalKey = "defect-evidence-$id"
        var stored: ByteArray? = null
        when (verdict) {
            is MediaVerdict.Unavailable -> return toView(row)   // fail-closed: bleibt in Quarantäne, kein Urteil
            is MediaVerdict.Clean -> { stored = verdict.bytes; store.putIfAbsent(ObjectKey(tenant, finalKey), verdict.bytes, row.mime) }
            else -> {}
        }
        val engine = wiring.inspector.engine
        val out = ctxTx.runSystem(tenant) { j -> record(j, id, verdict, stored, finalKey, engine) }
        if (out.state == EvidenceState.RELEASED || out.state == EvidenceState.REJECTED) runCatching { store.delete(ObjectKey(tenant, row.uploadKey)) }   // Quarantäneobjekt entfällt nach dem Urteil
        return out
    }

    @AuditRequired
    private fun record(j: JdbcOperations, id: UUID, verdict: MediaVerdict, stored: ByteArray?, finalKey: String, engine: String): EvidenceTicketView {
        val row = j.query("SELECT $COLUMNS FROM taktovia.defect_evidence_upload u WHERE u.id = ? FOR UPDATE", MAPPER, id).firstOrNull() ?: throw ProblemException.notFound()
        if (row.state != EvidenceState.IN_REVIEW) return toView(row)   // gleichzeitig entschieden
        val actor = ActorRef.service("defect-evidence-review")
        return if (verdict is MediaVerdict.Clean && stored != null) {
            val hash = sha256(stored)
            val updated = j.query(
                "UPDATE taktovia.defect_evidence_upload u SET state = 'RELEASED', object_key = ?, stored_sha256 = ?, stored_size = ?, review_engine = ?, reviewed_at = clock_timestamp() WHERE u.id = ? RETURNING $COLUMNS",
                MAPPER, finalKey, hash, stored.size.toLong(), engine, id,
            ).single()
            if (updated.defectId != null) insertEvidence(j, updated, updated.attachedInVersion ?: 1, updated.replacesEvidenceId)
            audit.record(j, AuditEntry(actor, AuditActions.DEFECT_EVIDENCE_RELEASED, ObjectRef("defect_evidence", id), UUID.randomUUID(), mapOf("engine" to engine, "mime" to row.mime, "size_bytes" to stored.size)))
            toView(updated)
        } else {
            val reason = if (verdict is MediaVerdict.Infected) "MALWARE" else "MEDIA_INVALID"
            val updated = j.query("UPDATE taktovia.defect_evidence_upload u SET state = 'REJECTED', reject_reason = ?, review_engine = ?, reviewed_at = clock_timestamp() WHERE u.id = ? RETURNING $COLUMNS", MAPPER, reason, engine, id).single()
            audit.record(j, AuditEntry(actor, AuditActions.DEFECT_EVIDENCE_REJECTED, ObjectRef("defect_evidence", id), UUID.randomUUID(), mapOf("reason" to reason, "stage" to "review", "engine" to engine)))
            toView(updated)
        }
    }

    // ---------------- Bindung an die Akte ----------------

    /**
     * Bindet Tickets des Meldenden an die Akte (in der Transaktion der Meldung bzw. des Nachtrags). Ein Ticket muss dem Meldenden gehören, abgeschlossen (in Prüfung oder freigegeben)
     * und noch frei sein; sonst 422 mit Feldverweis. Bereits freigegebene Fotos werden sofort Evidenz, Fotos in Prüfung folgen mit der Freigabe.
     */
    fun attach(j: JdbcOperations, defectId: UUID, ids: List<UUID>, actor: ActorRef, version: Int, pointer: String = "/evidence_ids", replaces: Map<UUID, UUID> = emptyMap()) {
        ids.forEachIndexed { i, id ->
            val row = j.query("SELECT $COLUMNS FROM taktovia.defect_evidence_upload u WHERE u.id = ? FOR UPDATE", MAPPER, id).firstOrNull()
            val replacing = replaces[id]
            // Ein Ersatz-Foto muss schon freigegeben sein: die Ersetzung ist mit der Version wirksam, nicht erst nach einer späteren Prüfung
            val okState = if (replacing != null) setOf(EvidenceState.RELEASED) else setOf(EvidenceState.IN_REVIEW, EvidenceState.RELEASED)
            if (row == null || row.uploadedBy != actor.value || row.defectId != null || row.state !in okState) throw ProblemException.invalid(Violation("$pointer/$i", ViolationCode.INVALID_VALUE))
            j.update("UPDATE taktovia.defect_evidence_upload SET defect_id = ?, attached_in_version = ?, replaces_evidence_id = ? WHERE id = ?", defectId, version, replacing, id)
            if (row.state == EvidenceState.RELEASED) insertEvidence(j, row.copy(defectId = defectId), version, replacing)
        }
    }

    /** Anhang, der ersetzt werden soll: freigegeben, zur Akte gehörig und noch nicht ersetzt (sonst 422 mit Feldverweis). */
    fun requireReplaceable(j: JdbcOperations, defectId: UUID, evidenceId: UUID, pointer: String) {
        val n = j.queryForObject(
            "SELECT count(*) FROM taktovia.defect_evidence e WHERE e.id = ? AND e.defect_id = ? AND NOT EXISTS (SELECT 1 FROM taktovia.defect_evidence r WHERE r.replaces_evidence_id = e.id)", Int::class.java, evidenceId, defectId)
        if (n != 1) throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_VALUE))
    }

    private fun insertEvidence(j: JdbcOperations, u: TicketRow, version: Int, replaces: UUID?) {
        j.update(
            "INSERT INTO taktovia.defect_evidence (tenant_id, id, defect_id, media_ref, sha256, captured_at, created_by, mime, size_bytes, original_sha256, upload_id, defect_version, replaces_evidence_id, confidentiality) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            u.id, u.defectId, u.objectKey, u.storedSha256, Timestamp.from(u.capturedAt), u.uploadedBy, u.mime, u.storedSize, u.sha256Hex, u.id, version, replaces, u.confidentiality,
        )
    }

    // ---------------- Lesen, Link, Integrität ----------------

    override fun listForDefect(ctx: RequestContext, defectId: UUID): List<EvidenceDetail> =
        ctxTx.run(ctx) { j -> listOrDeny(j, ctx, defectId) } ?: throw ProblemException.notFound()

    /** Fotos der Akte in der Projektion der Rolle; `null` = nicht sichtbar (Akte unbekannt oder einem Fahrer fremd; Letzteres ist ein Sicherheitsereignis ohne Fallinhalt). */
    @AuditRequired
    private fun listOrDeny(j: JdbcOperations, ctx: RequestContext, defectId: UUID): List<EvidenceDetail>? {
        val reporter = reporterOf(j, defectId) ?: return null
        if (DefectRoles.driverOnly(ctx) && reporter.toString() != ctx.actor.subject.lowercase()) { deny(j, ctx, ObjectRef("defect", defectId), "NOT_VISIBLE", "list"); return null }
        return j.query("SELECT $EV_COLUMNS FROM taktovia.defect_evidence e WHERE e.defect_id = ? AND NOT EXISTS (SELECT 1 FROM taktovia.defect_evidence r WHERE r.replaces_evidence_id = e.id) ORDER BY e.created_at, e.id", EV_MAPPER, defectId)
            .filter { DefectConfidentiality.visibleTo(ctx, it) }
    }

    override fun downloadLink(ctx: RequestContext, defectId: UUID, evidenceId: UUID): SignedUrl {
        val urls = wiring.urls ?: throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Signierte Links sind nicht konfiguriert.")
        val granted = ctxTx.run(ctx) { j -> authorizeLink(j, ctx, defectId, evidenceId) } as? Access.Granted ?: throw ProblemException.notFound()
        return urls.sign(ctx, ObjectKey(ctx.tenant, granted.ref), PURPOSE, ObjectPurposeCheck { _, _, _ -> true })
    }

    override fun openLinked(ctx: RequestContext, url: String): LinkedObject {
        val urls = wiring.urls ?: throw ProblemException.notFound()
        val (key, purpose) = urls.verify(ctx, url)
        if (purpose != PURPOSE) throw ProblemException.notFound()
        val granted = ctxTx.run(ctx) { j -> authorizeDownload(j, ctx, key.name) } as? Access.Granted ?: throw ProblemException.notFound()
        return LinkedObject(store.get(key) ?: throw ProblemException.notFound(), granted.mime ?: "application/octet-stream")
    }

    private sealed interface Access {
        data class Granted(val ref: String, val mime: String?) : Access
        /** Nicht vorhanden oder nicht sichtbar, ohne Sicherheitsereignis (kein Fall betroffen). */
        data object Missing : Access
        /** Abgewiesen, das Sicherheitsereignis ist in derselben Transaktion geschrieben. */
        data object Denied : Access
    }

    private class EvidenceRow(val id: UUID, val defectId: UUID, val ref: String, val mime: String?, val confidentiality: String, val createdBy: String, val replaced: Boolean)

    private fun reporterOf(j: JdbcOperations, defectId: UUID): UUID? =
        j.query("SELECT reported_by FROM taktovia.defect_case WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, defectId).firstOrNull()

    private val rowMapper = RowMapper { rs, _ -> EvidenceRow(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getBoolean(7)) }
    private fun evidenceById(j: JdbcOperations, evidenceId: UUID, defectId: UUID): EvidenceRow? = j.query(ROW_BY_ID, rowMapper, evidenceId, defectId).firstOrNull()
    private fun evidenceByRef(j: JdbcOperations, mediaRef: String): EvidenceRow? = j.query(ROW_BY_REF, rowMapper, mediaRef).firstOrNull()

    /** Link für ein Dokument einer Akte: Sichtbarkeit der Akte, Dokument, Vertraulichkeit; Reihenfolge so, dass ein Fremdzugriff immer als abgewiesen erfasst wird. */
    @AuditRequired
    private fun authorizeLink(j: JdbcOperations, ctx: RequestContext, defectId: UUID, evidenceId: UUID): Access {
        val reporter = reporterOf(j, defectId) ?: return Access.Missing
        if (DefectRoles.driverOnly(ctx) && reporter.toString() != ctx.actor.subject.lowercase()) { deny(j, ctx, ObjectRef("defect_evidence", evidenceId), "NOT_VISIBLE", "link"); return Access.Denied }
        val row = evidenceById(j, evidenceId, defectId) ?: return Access.Missing
        return decide(j, ctx, row, "link")
    }

    /** Abruf über den signierten Link: die Rechte des **Einlösenden** werden jetzt geprüft, nicht die des Ausstellers (weitergegebener Link). */
    @AuditRequired
    private fun authorizeDownload(j: JdbcOperations, ctx: RequestContext, mediaRef: String): Access {
        val row = evidenceByRef(j, mediaRef) ?: return Access.Missing
        val reporter = reporterOf(j, row.defectId) ?: return Access.Missing
        if (DefectRoles.driverOnly(ctx) && reporter.toString() != ctx.actor.subject.lowercase()) { deny(j, ctx, ObjectRef("defect_evidence", row.id), "NOT_VISIBLE", "download"); return Access.Denied }
        return decide(j, ctx, row, "download")
    }

    @AuditRequired
    private fun decide(j: JdbcOperations, ctx: RequestContext, row: EvidenceRow, stage: String): Access {
        if (row.replaced && stage == "link" && ctx.roles.none { it in DefectRoles.REVIEWERS }) return Access.Missing
        if (row.confidentiality == EvidenceConfidentiality.CONFIDENTIAL) {
            val roles = DefectConfidentiality.currentRoles(j, ctx, lock = true)
            if (!DefectConfidentiality.mayRead(roles, DefectConfidentiality.actorRef(ctx), row.createdBy, row.confidentiality)) {
                deny(j, ctx, ObjectRef("defect_evidence", row.id), "ROLE_NOT_PERMITTED", stage); return Access.Denied
            }
            // Jeder Zugriff auf ein vertrauliches Dokument wird protokolliert (Ausstellung des Links und Abruf); nie mit Dateiinhalt, Beschreibung oder Fallnummer
            audit.record(j, AuditEntry(actor(ctx), AuditActions.DEFECT_CONFIDENTIAL_READ, ObjectRef("defect_evidence", row.id), ctx.correlationId, mapOf("stage" to stage)))
        }
        return Access.Granted(row.ref, row.mime)
    }

    /** Sicherheitsereignis eines abgewiesenen Zugriffs: nur Grund und Stufe, ohne Fallinhalt (kein Text, keine Fallnummer, kein Hash). */
    private fun deny(j: JdbcOperations, ctx: RequestContext, obj: ObjectRef, reason: String, stage: String) {
        audit.record(j, AuditEntry(actor(ctx), AuditActions.SECURITY_DEFECT_CONFIDENTIAL_DENIED, obj, ctx.correlationId, mapOf("reason" to reason, "stage" to stage)))
    }

    override fun verifyIntegrity(tenant: TenantId, limit: Int): IntegrityReport {
        val rows = ctxTx.runSystem(tenant, readOnly = true) { j -> j.query("SELECT e.id, e.defect_id, e.media_ref, e.sha256 FROM taktovia.defect_evidence e ORDER BY e.created_at, e.id LIMIT ?", { rs, _ ->
            arrayOf<Any>(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4)) }, limit) }
        val mismatched = mutableListOf<UUID>(); val missing = mutableListOf<UUID>()
        rows.forEach { r ->
            val bytes = store.get(ObjectKey(tenant, r[2] as String))
            when { bytes == null -> missing += r[0] as UUID; sha256(bytes) != r[3] -> mismatched += r[0] as UUID }
        }
        if (mismatched.isNotEmpty() || missing.isNotEmpty()) ctxTx.runSystem(tenant) { j -> reportViolations(j, rows.filter { (it[0] as UUID) in mismatched || (it[0] as UUID) in missing }, missing.toSet()) }
        return IntegrityReport(rows.size, mismatched, missing)
    }

    @AuditRequired
    private fun reportViolations(j: JdbcOperations, rows: List<Array<Any>>, missing: Set<UUID>) {
        rows.forEach { r ->
            audit.record(j, AuditEntry(ActorRef.service("defect-evidence-integrity"), AuditActions.SECURITY_DEFECT_EVIDENCE_TAMPERED, ObjectRef("defect_evidence", r[0] as UUID), UUID.randomUUID(),
                mapOf("defect_id" to (r[1] as UUID).toString(), "kind" to if ((r[0] as UUID) in missing) "MISSING" else "HASH_MISMATCH", "expected_sha256_prefix" to (r[3] as String).take(12))))
        }
    }

    // ---------------- Hilfen ----------------

    private fun findOwn(j: JdbcOperations, actor: ActorRef, sha: String): TicketRow? =
        j.query("SELECT $COLUMNS FROM taktovia.defect_evidence_upload u WHERE u.uploaded_by = ? AND u.declared_sha256 = ? AND u.state <> 'REJECTED'", MAPPER, actor.value, sha).firstOrNull()

    private fun rejected(reason: String?) = ProblemException(ErrorCode.VALIDATION_FAILED, "Die Datei wurde abgewiesen ($reason) und nicht an die Akte gehängt.",
        listOf(Violation(if (reason == "SIZE_LIMIT" || reason == "SIZE_MISMATCH") "/size_bytes" else if (reason == "HASH_MISMATCH") "/sha256" else if (reason == "MIME_MISMATCH") "/mime" else "/content",
            if (reason == "SIZE_LIMIT") ViolationCode.ABOVE_MAXIMUM else ViolationCode.INVALID_VALUE)))

    private fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    private fun sqlState(e: Throwable): String? = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    internal data class TicketRow(
        val id: UUID, val uploadedBy: String, val mime: String, val sizeBytes: Long, val sha256Hex: String, val capturedAt: Instant, val uploadKey: String, val state: EvidenceState,
        val rejectReason: String?, val objectKey: String?, val storedSha256: String?, val storedSize: Long?, val defectId: UUID?, val expiresAt: Instant, val revision: Long,
        val attachedInVersion: Int? = null, val replacesEvidenceId: UUID? = null, val confidentiality: String = EvidenceConfidentiality.STANDARD,
    )

    private fun toView(r: TicketRow) = EvidenceTicketView(r.id, r.state, r.mime, r.sizeBytes, r.sha256Hex, r.capturedAt, policy.maxBytes, r.expiresAt, r.uploadKey, r.rejectReason, r.defectId, r.revision, r.confidentiality)

    internal companion object {
        const val PURPOSE = "defect_evidence"
        private const val ROW_SELECT = "SELECT e.id, e.defect_id, e.media_ref, e.mime, e.confidentiality, e.created_by, EXISTS (SELECT 1 FROM taktovia.defect_evidence r WHERE r.replaces_evidence_id = e.id) " +
            "FROM taktovia.defect_evidence e WHERE "
        private const val ROW_BY_ID = ROW_SELECT + "e.id = ? AND e.defect_id = ?"
        private const val ROW_BY_REF = ROW_SELECT + "e.media_ref = ?"
        const val COLUMNS = "u.id, u.uploaded_by, u.declared_mime, u.declared_size, u.declared_sha256, u.captured_at, u.quarantine_key, u.state, u.reject_reason, u.object_key, u.stored_sha256, " +
            "u.stored_size, u.defect_id, u.expires_at, u.revision, u.attached_in_version, u.replaces_evidence_id, u.confidentiality"
        val MAPPER = RowMapper { rs, _ ->
            TicketRow(rs.getObject("id", UUID::class.java), rs.getString("uploaded_by"), rs.getString("declared_mime"), rs.getLong("declared_size"), rs.getString("declared_sha256"),
                rs.getTimestamp("captured_at").toInstant(), rs.getString("quarantine_key"), EvidenceState.valueOf(rs.getString("state")), rs.getString("reject_reason"), rs.getString("object_key"),
                rs.getString("stored_sha256"), rs.getObject("stored_size") as Long?, rs.getObject("defect_id", UUID::class.java), rs.getTimestamp("expires_at").toInstant(), rs.getLong("revision"),
                rs.getObject("attached_in_version") as Int?, rs.getObject("replaces_evidence_id", UUID::class.java), rs.getString("confidentiality"))
        }
        const val EV_COLUMNS = "e.id, e.defect_id, e.mime, e.size_bytes, e.sha256, e.captured_at, e.received_at, e.created_by, e.defect_version, e.replaces_evidence_id, e.confidentiality"
        val EV_MAPPER = RowMapper { rs, _ ->
            EvidenceDetail(rs.getObject("id", UUID::class.java), rs.getObject("defect_id", UUID::class.java), rs.getString("mime"), rs.getObject("size_bytes") as Long?, rs.getString("sha256"),
                rs.getTimestamp("captured_at").toInstant(), rs.getTimestamp("received_at").toInstant(), rs.getInt("defect_version"), rs.getObject("replaces_evidence_id", UUID::class.java), rs.getString("created_by"), rs.getString("confidentiality"))
        }
    }
}
