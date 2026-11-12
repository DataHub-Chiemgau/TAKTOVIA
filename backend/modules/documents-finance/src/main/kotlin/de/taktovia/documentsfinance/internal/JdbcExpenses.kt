package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.CorrectExpense
import de.taktovia.documentsfinance.api.DocumentState
import de.taktovia.documentsfinance.api.ExpenseFields
import de.taktovia.documentsfinance.api.ExpenseView
import de.taktovia.documentsfinance.api.ExtractedFieldKind
import de.taktovia.documentsfinance.api.Expenses
import de.taktovia.documentsfinance.api.FieldChange
import de.taktovia.documentsfinance.api.IntegrityState
import de.taktovia.documentsfinance.api.RevisionView
import de.taktovia.documentsfinance.api.UploadPolicy
import de.taktovia.documentsfinance.api.Uploads
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_READ
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_WRITE
import de.taktovia.documentsfinance.internal.DocumentRows.require
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.ObjectRef
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
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/**
 * Belegstand, Revisionen und Korrektur (WP-M4-22, WP-M4-23). Eine Korrektur sperrt die Belegzeile (`FOR UPDATE`), prüft `If-Match` gegen die Revision und schreibt in einer Transaktion
 * den neuen Feldstand, die unveränderliche Revision (Alt- und Neuwert je Feld, Bearbeiter, Zeitpunkt, Grund), die Entscheidungen zu OCR-Vorschlägen, Audit und Ereignis. Das Original wird nie berührt:
 * es gibt in diesem Baustein keinen Befehl, der ein Originalobjekt schreibt oder löscht; die bearbeitete Ansicht ist ein eigenes Objekt.
 */
internal class JdbcExpenses(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val store: TenantObjectStore, private val uploads: Uploads,
    private val policy: UploadPolicy, private val clock: Clock,
) : Expenses {

    override fun get(ctx: RequestContext, id: UUID): ExpenseView {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j -> view(j, DocumentRows.visible(j, ctx, id)) }
    }

    override fun revisions(ctx: RequestContext, id: UUID): List<RevisionView> {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j ->
            DocumentRows.visible(j, ctx, id)
            j.query("SELECT revision, field_values::text AS fv, edited_by, edited_at, reason, edited_view_key FROM taktovia.document_revision WHERE document_id = ? ORDER BY revision", { rs, _ ->
                val tree = ExpenseFieldCodec.parseTree(rs.getString("fv"))
                RevisionView(rs.getLong("revision"), tree.entries.associate { (k, v) -> k to change(k, v) }, rs.getString("edited_by"), rs.getTimestamp("edited_at").toInstant(), rs.getString("reason"), rs.getString("edited_view_key"))
            }, id)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun change(key: String, v: Any?): FieldChange {
        val m = v as Map<String, Any?>
        fun n(x: Any?): Any? = if (key in ExpenseFields.ALL) x?.let { ExpenseFieldCodec.normalize(key, it) } else x   // z. B. `cost_assignment` (WP-M4-29): Kennungslisten unverändert
        return FieldChange(n(m["old"]), n(m["new"]), m["confirmed"] == true, n(m["ocr_proposal"]))
    }

    override fun original(ctx: RequestContext, id: UUID): ByteArray = uploads.openOriginal(ctx, id)

    private fun view(j: JdbcOperations, r: DocRow): ExpenseView {
        val objects = CostAssignmentRows.assigned(j, r.view.documentId)
        return ExpenseView(r.view, ExpenseFieldCodec.parseStored(r.fieldsJson), r.view.blockers + CostAssignmentRows.blockers(objects), objects)
    }

    override fun correct(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: CorrectExpense): ExpenseView {
        require(ctx, SCOPE_WRITE)
        val reason = cmd.reason.trim()
        if (reason.isEmpty()) throw ProblemException.invalid(Violation("/reason", ViolationCode.REQUIRED))
        if (reason.length > 500) throw ProblemException.invalid(Violation("/reason", ViolationCode.TOO_LONG))
        if (cmd.fields.isEmpty() && cmd.confirm.isEmpty()) throw ProblemException.invalid(Violation("/fields", ViolationCode.REQUIRED))
        cmd.confirm.firstOrNull { it !in ExpenseFields.ALL }?.let { throw ProblemException.invalid(Violation("/confirm", ViolationCode.UNKNOWN_FIELD)) }
        cmd.confirm.firstOrNull { it in cmd.fields }?.let { throw ProblemException.invalid(Violation("/confirm", ViolationCode.NOT_UNIQUE)) }
        val values = cmd.fields.entries.associate { (k, v) -> k to ExpenseFieldCodec.normalize(k, v) }   // prüft auch unbekannte Felder
        cmd.editedView?.let { ev ->
            if (ev.mime !in policy.allowedMime || ContentSniffer.detect(ev.bytes) != ev.mime) throw ProblemException.invalid(Violation("/edited_view", ViolationCode.INVALID_VALUE))
            if (ev.bytes.isEmpty() || ev.bytes.size.toLong() > policy.maxBytes) throw ProblemException.invalid(Violation("/edited_view", ViolationCode.ABOVE_MAXIMUM))
        }
        val viewKey = cmd.editedView?.let { "receipts/edited/$id/${UUID.randomUUID()}" }   // eigenes Objekt, nie unter `originals/`
        if (viewKey != null) store.putIfAbsent(ObjectKey(ctx.tenant, viewKey), cmd.editedView.bytes, cmd.editedView.mime)
        try {
            return ctxTx.run(ctx) { j -> apply(j, ctx, id, expectedRevision, reason, values, cmd.confirm, viewKey) }
        } catch (e: Throwable) {
            if (viewKey != null) store.delete(ObjectKey(ctx.tenant, viewKey))   // verworfene Korrektur hinterlässt keine verwaiste Ansicht
            throw e
        }
    }

    private fun apply(j: JdbcOperations, ctx: RequestContext, id: UUID, expectedRevision: Long, reason: String, values: Map<String, Any>, confirm: Set<String>, viewKey: String?): ExpenseView {
        val row = DocumentRows.visible(j, ctx, id, lock = true)
        if (row.view.revision != expectedRevision) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Beleg wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        when {
            row.stateRaw == "QUARANTINED" || row.stateRaw == "QUARANTINE_REJECTED" -> throw ProblemException(ErrorCode.CONFLICT, "Ein Beleg in Quarantäne ist nicht bearbeitbar.")
            row.stateRaw != "STORED" -> throw ProblemException(ErrorCode.IMMUTABLE_RECORD, "Der Beleg ist freigegeben oder gebucht; Korrekturen nur über ein Gegenereignis.")
            row.view.integrity == IntegrityState.VIOLATED -> throw IntegrityChecksSupport.violation()
        }
        val current = ExpenseFieldCodec.parseStored(row.fieldsJson)
        val proposals = Proposals.load(j, id)
        val changes = linkedMapOf<String, Map<String, Any?>>()
        val next = LinkedHashMap(current)
        for ((k, v) in values) {
            if (current[k] == v) throw ProblemException.invalid(Violation("/fields/$k", ViolationCode.INVALID_VALUE, "Wert entspricht dem aktuellen Stand"))
            changes[k] = changeJson(current[k], v, proposals.value(k), confirmed = false); next[k] = v
        }
        for (k in confirm) {
            val proposal = proposals.value(k) ?: throw ProblemException.invalid(Violation("/confirm", ViolationCode.INVALID_VALUE, "Kein OCR-Vorschlag zu bestätigen"))
            changes[k] = changeJson(current[k], proposal, proposal, confirmed = true); next[k] = proposal
        }
        val newRevision = expectedRevision + 1
        val updated = j.update("UPDATE taktovia.expense_document SET current_fields = ?::jsonb, revision = ? WHERE id = ? AND revision = ? AND state = 'STORED'", ExpenseFieldCodec.toJson(next), newRevision, id, expectedRevision)
        checkUpdated(updated)
        j.update("INSERT INTO taktovia.document_revision (tenant_id, id, document_id, revision, field_values, edited_view_key, edited_by, reason) VALUES (taktovia.current_tenant(),?,?,?,?::jsonb,?,?,?)",
            UUID.randomUUID(), id, newRevision, ExpenseFieldCodec.toJson(changes), viewKey, ctx.actor.subject.take(200), reason)
        proposals.recordDecisions(j, id, values, confirm, ctx.actor.subject.take(200), clock)
        audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_EXPENSE_REVISED, ObjectRef("expense", id), ctx.correlationId, mapOf("revision" to newRevision, "fields" to changes.keys.toList())))
        outbox.append(j, OutboxEvent(id, newRevision, "expense.revision_created", linkedMapOf("document_id" to id.toString(), "revision" to newRevision, "fields" to changes.keys.toList(),
            "edited_at" to clock.instant().toString()), clock.instant(), correlationId = ctx.correlationId))
        return view(j, DocumentRows.visible(j, ctx, id))
    }

    private fun changeJson(old: Any?, new: Any, proposal: Any?, confirmed: Boolean): Map<String, Any?> =
        linkedMapOf<String, Any?>("old" to old, "new" to new).also { if (confirmed) it["confirmed"] = true; if (proposal != null) it["ocr_proposal"] = proposal }

    /** Das bedingte UPDATE traf genau eine Zeile (die Zeile ist gesperrt, daher nur ein Selbsttest). */
    private fun checkUpdated(rows: Int) { check(rows == 1) { "bedingtes UPDATE traf $rows Zeilen" } }
}

/** OCR-Vorschläge eines Belegs (jüngster abgeschlossener Lauf) und das Festhalten menschlicher Entscheidungen als eigene Zeilen mit Bezug auf den Vorschlag. */
internal class Proposals private constructor(private val runId: UUID?, private val rows: List<ProposalRow>) {
    class ProposalRow(val id: UUID, val kind: ExtractedFieldKind, val occurrence: Int, val raw: String?, val normalized: String?, val qualifier: String?, val suspicious: Boolean)

    /** Vorschlagswert in der Normalform des Feldes oder `null`. */
    fun value(field: String): Any? {
        val kind = ExtractedFieldKind.entries.first { it.expenseField == field }
        val mine = rows.filter { it.kind == kind }
        if (mine.isEmpty()) return null
        return if (kind == ExtractedFieldKind.VAT_AMOUNT) mine.filter { it.normalized != null }.map { linkedMapOf<String, Any>("vat_rate_pct" to (it.qualifier ?: return null), "amount_minor" to it.normalized!!.toLong()) }.takeIf { it.isNotEmpty() }
        else mine.first().normalized?.let { if (kind == ExtractedFieldKind.AMOUNT) it.toLong() else it }
    }

    fun recordDecisions(j: JdbcOperations, documentId: UUID, values: Map<String, Any>, confirm: Set<String>, by: String, clock: Clock) {
        val run = runId ?: return
        fun insert(p: ProposalRow, status: String, normalized: String?, qualifier: String?) {
            j.update("INSERT INTO taktovia.extracted_field (tenant_id, id, document_id, run_id, field_kind, occurrence, origin, status, raw_value, normalized_value, display_value, qualifier, suspicious, corrects_field_id, decided_by, decided_at) " +
                "VALUES (taktovia.current_tenant(),?,?,?,?,?,'HUMAN',?,?,?,?,?,?,?,?,?)", UUID.randomUUID(), documentId, run, p.kind.name, p.occurrence, status, p.raw, normalized, normalized?.let { Neutralizer.display(it) }, qualifier, p.suspicious, p.id, by,
                Timestamp.from(clock.instant()))
        }
        for (k in values.keys + confirm) {
            val kind = ExtractedFieldKind.entries.first { it.expenseField == k }
            val mine = rows.filter { it.kind == kind }
            if (mine.isEmpty()) continue
            val isConfirm = k in confirm
            if (kind == ExtractedFieldKind.VAT_AMOUNT) {
                @Suppress("UNCHECKED_CAST") val list = (if (isConfirm) value(k) else values[k]) as List<Map<String, Any>>
                mine.forEachIndexed { i, p ->
                    val e = list.getOrNull(i)
                    val same = e != null && e["amount_minor"].toString() == p.normalized && e["vat_rate_pct"] == p.qualifier
                    insert(p, if (same) "CONFIRMED" else "CORRECTED", e?.get("amount_minor")?.toString(), e?.get("vat_rate_pct") as String?)
                }
            } else {
                val p = mine.first(); val v = (if (isConfirm) value(k) else values[k]).toString()
                insert(p, if (v == p.normalized) "CONFIRMED" else "CORRECTED", v, null)
            }
        }
    }

    companion object {
        fun load(j: JdbcOperations, documentId: UUID): Proposals {
            val run = j.query("SELECT id FROM taktovia.extraction_run WHERE document_id = ? AND status = 'COMPLETED' ORDER BY requested_at DESC, id LIMIT 1", { rs, _ -> rs.getObject(1, UUID::class.java) }, documentId).firstOrNull()
                ?: return Proposals(null, emptyList())
            val rows = j.query("SELECT id, field_kind, occurrence, raw_value, normalized_value, qualifier, suspicious FROM taktovia.extracted_field WHERE run_id = ? AND origin = 'OCR' ORDER BY field_kind, occurrence", { rs, _ ->
                ProposalRow(rs.getObject("id", UUID::class.java), ExtractedFieldKind.valueOf(rs.getString("field_kind")), rs.getInt("occurrence"), rs.getString("raw_value"), rs.getString("normalized_value"), rs.getString("qualifier"), rs.getBoolean("suspicious"))
            }, run)
            return Proposals(run, rows)
        }
    }
}
