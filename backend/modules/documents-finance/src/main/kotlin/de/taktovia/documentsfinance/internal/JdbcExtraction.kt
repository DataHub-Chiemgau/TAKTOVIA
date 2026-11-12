package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.ExtractedFieldKind
import de.taktovia.documentsfinance.api.ExtractedFieldView
import de.taktovia.documentsfinance.api.ExtractionDetail
import de.taktovia.documentsfinance.api.ExtractionPolicy
import de.taktovia.documentsfinance.api.ExtractionRunView
import de.taktovia.documentsfinance.api.FieldOrigin
import de.taktovia.documentsfinance.api.FieldStatus
import de.taktovia.documentsfinance.api.ReceiptExtraction
import de.taktovia.documentsfinance.api.ReceiptRecognizer
import de.taktovia.documentsfinance.api.RecognitionResult
import de.taktovia.documentsfinance.api.RecognitionTimeoutException
import de.taktovia.documentsfinance.api.RunFailure
import de.taktovia.documentsfinance.api.RunStatus
import de.taktovia.documentsfinance.api.SourceLocation
import de.taktovia.documentsfinance.api.UnsupportedContentException
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_READ
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_WRITE
import de.taktovia.documentsfinance.internal.DocumentRows.require
import de.taktovia.documentsfinance.internal.DocumentRows.sha256
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Feldextraktion mit Lease und Fencing (WP-M4-27). `extraction_run` ist je Beleg und OCR-Version eindeutig (kein Doppellauf). [process] beansprucht den Lauf atomar (`attempt` steigt: Fencing-Token),
 * liest das Original und ruft die Engine **außerhalb** jeder Transaktion mit Zeitgrenze, und schreibt das Ergebnis nur, solange der eigene Versuch noch der aktuelle ist
 * (`WHERE status = 'RUNNING' AND attempt = ?`): ein Worker mit verlorener Lease schreibt nichts. Das Original bleibt unabhängig vom Ergebnis unverändert im Originalbestand.
 */
internal class JdbcExtraction(
    private val ctxTx: ContextTransactions, private val outbox: Outbox, private val store: TenantObjectStore, private val recognizer: ReceiptRecognizer?, private val policy: ExtractionPolicy, private val clock: Clock,
) : ReceiptExtraction {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "ocr-call").also { it.isDaemon = true } }

    override fun request(ctx: RequestContext, documentId: UUID, expectedRevision: Long?): ExtractionRunView {
        require(ctx, SCOPE_WRITE)
        val rec = recognizer ?: throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Keine OCR-Engine konfiguriert; die Erfassung bleibt manuell möglich.")
        return ctxTx.run(ctx) { j ->
            val d = DocumentRows.visible(j, ctx, documentId)
            if (expectedRevision != null && d.view.revision != expectedRevision) throw ProblemException(ErrorCode.REVISION_MISMATCH)
            if (!JdbcDocumentSettings.load(j).receiptOcrEnabled) throw ProblemException(ErrorCode.CONFLICT, "Die Belegerkennung ist für diesen Mandanten abgeschaltet (receipt_ocr_enabled); manuelle Erfassung bleibt möglich.")
            if (d.stateRaw == "QUARANTINED" || d.stateRaw == "QUARANTINE_REJECTED") throw ProblemException(ErrorCode.CONFLICT, "Ein Beleg in Quarantäne wird nicht extrahiert.")
            j.query("INSERT INTO taktovia.extraction_run AS r (tenant_id, id, document_id, document_revision, engine, ocr_version, requested_by) VALUES (taktovia.current_tenant(),?,?,?,?,?,?) " +
                "ON CONFLICT (tenant_id, document_id, engine, ocr_version) DO NOTHING RETURNING $RUN_COLUMNS", RUN, UUID.randomUUID(), documentId, d.view.revision, rec.engine, rec.version, ctx.actor.subject.take(200)).firstOrNull()
                ?: j.query("SELECT $RUN_COLUMNS FROM taktovia.extraction_run r WHERE r.document_id = ? AND r.engine = ? AND r.ocr_version = ?", RUN, documentId, rec.engine, rec.version).single()
        }
    }

    override fun process(ctx: RequestContext, runId: UUID): ExtractionRunView {
        require(ctx, SCOPE_WRITE)
        val rec = recognizer ?: throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Keine OCR-Engine konfiguriert.")
        // (A) Lauf beanspruchen: QUEUED, FAILED (Wiederholung) oder RUNNING mit abgelaufener Lease; sonst gibt es nichts zu tun (kein Doppellauf)
        val claimed = ctxTx.run(ctx) { j ->
            val run = j.query("SELECT $RUN_COLUMNS FROM taktovia.extraction_run r WHERE r.id = ?", RUN, runId).firstOrNull() ?: throw ProblemException.notFound()
            DocumentRows.visible(j, ctx, run.documentId)
            val won = j.query("UPDATE taktovia.extraction_run r SET status = 'RUNNING', attempt = attempt + 1, lease_until = clock_timestamp() + (? * interval '1 millisecond'), started_at = COALESCE(started_at, clock_timestamp()), " +
                "failure_code = NULL, finished_at = NULL WHERE r.id = ? AND (r.status IN ('QUEUED', 'FAILED') OR (r.status = 'RUNNING' AND r.lease_until < clock_timestamp())) RETURNING $RUN_COLUMNS",
                RUN, policy.lease.toMillis(), runId).firstOrNull()
            if (won != null) won to true else j.query("SELECT $RUN_COLUMNS FROM taktovia.extraction_run r WHERE r.id = ?", RUN, runId).single() to false   // nicht beansprucht: läuft schon, ist fertig oder wartet auf den Ablauf der Lease
        }
        if (!claimed.second) return claimed.first
        val view = claimed.first
        val doc = ctxTx.run(ctx, readOnly = true) { j -> DocumentRows.visible(j, ctx, view.documentId) }
        // (B) Original lesen und Engine rufen, außerhalb jeder Transaktion
        val outcome = recognize(ctx, doc, rec)
        // (C) Ergebnis schreiben, nur wenn dieser Versuch noch der aktuelle ist (Fencing)
        return ctxTx.run(ctx) { j ->
            when (outcome) {
                is Outcome.Failed -> {
                    val n = j.update("UPDATE taktovia.extraction_run SET status = 'FAILED', failure_code = ?, finished_at = clock_timestamp(), lease_until = NULL WHERE id = ? AND status = 'RUNNING' AND attempt = ?",
                        outcome.failure.name, runId, view.attempt)
                    if (n == 1) outbox.append(j, OutboxEvent(runId, view.attempt.toLong(), "document.extraction_failed", linkedMapOf("document_id" to doc.view.documentId.toString(), "run_id" to runId.toString(),
                        "failure" to outcome.failure.name, "attempt" to view.attempt), clock.instant(), correlationId = ctx.correlationId))
                }
                is Outcome.Done -> {
                    val fields = ReceiptFieldParser.parse(outcome.result)
                    val n = j.update("UPDATE taktovia.extraction_run SET status = 'COMPLETED', field_count = ?, finished_at = clock_timestamp(), lease_until = NULL WHERE id = ? AND status = 'RUNNING' AND attempt = ?",
                        fields.count { it.normalized != null }, runId, view.attempt)
                    if (n == 1) {
                        for (f in fields) {
                            j.update("INSERT INTO taktovia.extracted_field (tenant_id, id, document_id, run_id, field_kind, occurrence, origin, status, raw_value, normalized_value, display_value, qualifier, confidence, source_ref, suspicious) " +
                                "VALUES (taktovia.current_tenant(),?,?,?,?,?,'OCR','PROPOSED',?,?,?,?,?,?::jsonb,?)", UUID.randomUUID(), doc.view.documentId, runId, f.kind.name, f.occurrence, f.raw, f.normalized,
                                (f.raw ?: f.normalized)?.let { Neutralizer.display(it) }, f.qualifier, f.confidence?.toBigDecimal(), f.source?.let { sourceJson(it) }, f.suspicious)
                        }
                        outbox.append(j, OutboxEvent(runId, view.attempt.toLong(), "document.extraction_completed", linkedMapOf("document_id" to doc.view.documentId.toString(), "run_id" to runId.toString(),
                            "fields_found" to fields.count { it.normalized != null }, "attempt" to view.attempt), clock.instant(), correlationId = ctx.correlationId))
                    }
                }
            }
            j.query("SELECT $RUN_COLUMNS FROM taktovia.extraction_run r WHERE r.id = ?", RUN, runId).single()
        }
    }

    private sealed interface Outcome {
        class Done(val result: RecognitionResult) : Outcome
        class Failed(val failure: RunFailure) : Outcome
    }

    private fun recognize(ctx: RequestContext, doc: DocRow, rec: ReceiptRecognizer): Outcome {
        val bytes = store.get(ObjectKey(ctx.tenant, doc.objectKey))
        if (bytes == null || sha256(bytes) != doc.view.sha256) return Outcome.Failed(RunFailure.ORIGINAL_UNAVAILABLE)   // verletztes Original wird nicht gelesen; die Integritätsprüfung (WP-M4-24) hält den Vorfall fest
        val f = pool.submit<RecognitionResult> { rec.recognize(bytes, doc.view.mime) }
        return try {
            Outcome.Done(f.get(policy.recognitionTimeout.toMillis(), TimeUnit.MILLISECONDS))
        } catch (e: TimeoutException) { f.cancel(true); Outcome.Failed(RunFailure.TIMEOUT) }
        catch (e: ExecutionException) {
            when (e.cause) { is RecognitionTimeoutException -> Outcome.Failed(RunFailure.TIMEOUT); is UnsupportedContentException -> Outcome.Failed(RunFailure.UNSUPPORTED_CONTENT); else -> Outcome.Failed(RunFailure.ENGINE_ERROR) }
        }
    }

    override fun latest(ctx: RequestContext, documentId: UUID): ExtractionDetail? {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j ->
            DocumentRows.visible(j, ctx, documentId)
            val run = j.query("SELECT $RUN_COLUMNS FROM taktovia.extraction_run r WHERE r.document_id = ? ORDER BY r.requested_at DESC, r.id LIMIT 1", RUN, documentId).firstOrNull() ?: return@run null
            val rows = j.query("SELECT $FIELD_COLUMNS FROM taktovia.extracted_field f WHERE f.run_id = ? ORDER BY f.seq", FIELD, run.id)
            val proposals = rows.filter { it.origin == FieldOrigin.OCR }
            // je Vorschlag der jüngste Stand: die letzte menschliche Entscheidung mit Bezug auf ihn, sonst der Vorschlag selbst
            val current = proposals.map { p -> rows.lastOrNull { it.origin == FieldOrigin.HUMAN && it.correctsFieldId == p.id } ?: p }
            ExtractionDetail(run, proposals, current)
        }
    }

    private fun sourceJson(s: SourceLocation) = """{"page":${s.page},"x":${s.x},"y":${s.y},"width":${s.width},"height":${s.height}}"""

    private companion object {
        const val RUN_COLUMNS = "r.id, r.document_id, r.status, r.engine, r.ocr_version, r.attempt, r.failure_code, r.field_count, r.requested_at, r.finished_at"
        val RUN = RowMapper { rs, _ ->
            val status = RunStatus.valueOf(rs.getString("status"))
            ExtractionRunView(rs.getObject("id", UUID::class.java), rs.getObject("document_id", UUID::class.java), status, rs.getString("engine"), rs.getString("ocr_version"), rs.getInt("attempt"),
                rs.getString("failure_code")?.let { RunFailure.valueOf(it) }, rs.getObject("field_count") as Int?, rs.getTimestamp("requested_at").toInstant(), rs.getTimestamp("finished_at")?.toInstant())
        }
        const val FIELD_COLUMNS = "f.id, f.field_kind, f.occurrence, f.origin, f.status, f.raw_value, f.normalized_value, f.display_value, f.qualifier, f.confidence, f.source_ref::text AS source_ref, f.suspicious, f.corrects_field_id, f.decided_by, f.decided_at"
        val FIELD = RowMapper { rs, _ ->
            val src = rs.getString("source_ref")?.let { ExpenseFieldCodec.parseTree(it) }
            ExtractedFieldView(rs.getObject("id", UUID::class.java), ExtractedFieldKind.valueOf(rs.getString("field_kind")), rs.getInt("occurrence"), FieldOrigin.valueOf(rs.getString("origin")),
                FieldStatus.valueOf(rs.getString("status")), rs.getString("raw_value"), rs.getString("normalized_value"), rs.getString("display_value"), rs.getString("qualifier"),
                rs.getBigDecimal("confidence")?.toDouble(), src?.let { SourceLocation((it["page"] as Number).toInt(), (it["x"] as Number).toDouble(), (it["y"] as Number).toDouble(), (it["width"] as Number).toDouble(), (it["height"] as Number).toDouble()) },
                rs.getBoolean("suspicious"), rs.getObject("corrects_field_id", UUID::class.java), rs.getString("decided_by"), rs.getTimestamp("decided_at")?.toInstant())
        }
    }
}
