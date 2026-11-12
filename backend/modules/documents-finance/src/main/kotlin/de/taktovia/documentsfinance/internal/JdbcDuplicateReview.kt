package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.DuplicateCandidateView
import de.taktovia.documentsfinance.api.DuplicateCertainty
import de.taktovia.documentsfinance.api.DuplicateDecision
import de.taktovia.documentsfinance.api.DuplicateFacts
import de.taktovia.documentsfinance.api.DuplicateMatch
import de.taktovia.documentsfinance.api.DuplicateReview
import de.taktovia.documentsfinance.api.DuplicateRole
import de.taktovia.documentsfinance.api.DuplicateState
import de.taktovia.documentsfinance.api.ExpenseFields
import de.taktovia.documentsfinance.api.ExpenseView
import de.taktovia.documentsfinance.api.SourceChannel
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_READ
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_WRITE
import de.taktovia.documentsfinance.internal.DocumentRows.require
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Trefferregeln und Persistenz der Dublettenerkennung (WP-M4-32). Punkte der Fachdaten: Händler 20, Belegdatum 20, Betrag 25, Währung 5, Belegnummer bzw. Karten-/Kassentransaktion (gleiche Karten-ID und
 * Tankzeit) 30; ein identischer Dateihash zählt 100. Treffer ab `receipt_duplicate_threshold` (Vorgabe 60), sicher bei 100 Punkten. Ein Treffer braucht übereinstimmenden Betrag oder übereinstimmende
 * Transaktion; eine abweichende Währung ist nie ein Treffer. Vergleich nur innerhalb des Mandanten (RLS), nie gegen Belege in Quarantäne oder abgelehnte Belege.
 */
internal object DuplicateRows {
    private const val SYSTEM_ACTOR = "system:duplicate-check"
    private const val FACTS_COLUMNS = "d.id, d.original_sha256, d.original_received_at, d.current_fields::text AS cf, d.source_channel, d.state, f.card_id, f.fueled_at"
    private const val FACTS_FROM = "FROM taktovia.expense_document d LEFT JOIN taktovia.fuel_transaction f ON f.tenant_id = d.tenant_id AND f.document_id = d.id"
    private const val CANDIDATES_SQL = "SELECT $FACTS_COLUMNS $FACTS_FROM WHERE d.upload_ticket_id IS NOT NULL AND d.id <> ? AND d.object_key LIKE 'receipts/%' AND d.state NOT IN ('QUARANTINED', 'QUARANTINE_REJECTED') " +
        "AND (d.original_sha256 = ? OR (CAST(? AS text) IS NOT NULL AND d.current_fields ->> 'amount_minor' = CAST(? AS text) AND d.current_fields ->> 'currency' = CAST(? AS text)) " +
        "OR (CAST(? AS text) IS NOT NULL AND lower(d.current_fields ->> 'document_number') = CAST(? AS text)) " +
        "OR (CAST(? AS text) IS NOT NULL AND CAST(? AS text) IS NOT NULL AND d.current_fields ->> 'document_date' = CAST(? AS text) AND lower(trim(d.current_fields ->> 'merchant')) = CAST(? AS text))) LIMIT 50"

    class Facts(val id: UUID, val sha: String, val receivedAt: Instant, val merchant: String?, val date: String?, val currency: String?, val amount: Long?, val number: String?, val card: String?, val fueledAt: Instant?, val channel: SourceChannel) {
        fun summary() = DuplicateFacts(merchant, date, currency, amount, number, channel, receivedAt)
    }

    class Hit(val match: DuplicateMatch, val score: Int, val certainty: DuplicateCertainty, val basis: Map<String, Any?>)

    private fun norm(s: String?) = s?.trim()?.lowercase()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }

    private val FACTS_MAPPER = org.springframework.jdbc.core.RowMapper { rs, _ ->
        val f = ExpenseFieldCodec.parseStored(rs.getString("cf"))
        Facts(rs.getObject("id", UUID::class.java), rs.getString("original_sha256").trim(), rs.getTimestamp("original_received_at").toInstant(), norm(f[ExpenseFields.MERCHANT] as String?), f[ExpenseFields.DOCUMENT_DATE] as String?,
            f[ExpenseFields.CURRENCY] as String?, f[ExpenseFields.AMOUNT_MINOR] as Long?, norm(f[ExpenseFields.DOCUMENT_NUMBER] as String?), rs.getString("card_id"), rs.getTimestamp("fueled_at")?.toInstant(), SourceChannel.valueOf(rs.getString("source_channel")))
    }

    fun facts(j: JdbcOperations, id: UUID): Facts? = j.query("SELECT $FACTS_COLUMNS $FACTS_FROM WHERE d.id = ?", FACTS_MAPPER, id).firstOrNull()

    /** Punktzahl und Trefferart zweier Belege; `null`, wenn kein Treffer. Reine Funktion. */
    fun score(a: Facts, b: Facts, threshold: Int): Hit? {
        if (a.sha == b.sha) return Hit(DuplicateMatch.HASH, 100, DuplicateCertainty.SAFE, linkedMapOf("rule" to "HASH", "hash_equal" to true, "threshold" to threshold, "matched" to listOf("sha256"), "differing" to emptyList<Any>()))
        val matched = mutableListOf<String>(); val differing = mutableListOf<Map<String, Any?>>(); var score = 0
        fun cmp(name: String, x: Any?, y: Any?, points: Int) {
            if (x == null || y == null) return
            if (x == y) { score += points; matched += name } else differing += linkedMapOf("field" to name, "this" to x.toString(), "other" to y.toString())
        }
        cmp("merchant", a.merchant, b.merchant, 20); cmp("document_date", a.date, b.date, 20); cmp("amount_minor", a.amount, b.amount, 25); cmp("currency", a.currency, b.currency, 5)
        val txn = (a.number != null && a.number == b.number) || (a.card != null && a.card == b.card && a.fueledAt != null && a.fueledAt == b.fueledAt)
        if (txn) { score += 30; matched += "transaction" } else if (a.number != null && b.number != null) differing += linkedMapOf("field" to "document_number", "this" to a.number, "other" to b.number)
        if (differing.any { it["field"] == "currency" }) return null
        if ("amount_minor" !in matched && !txn) return null
        if (score < threshold) return null
        return Hit(DuplicateMatch.FACTS, score, if (score >= 100) DuplicateCertainty.SAFE else DuplicateCertainty.POSSIBLE,
            linkedMapOf("rule" to "FACTS", "hash_equal" to false, "threshold" to threshold, "matched" to matched, "differing" to differing))
    }

    private fun isNewer(a: Facts, b: Facts) = a.receivedAt.isAfter(b.receivedAt) || (a.receivedAt == b.receivedAt && a.id.compareTo(b.id) > 0)

    /**
     * Erkennung für einen Beleg (in der laufenden Transaktion): vergleicht ihn mit allen anderen Belegen des Mandanten und legt je Paar höchstens einen Treffer am neueren Beleg an
     * (`ON CONFLICT DO NOTHING`; beide Richtungen führen zum selben Treffer, auch bei parallelen Läufen). Sichere Treffer werden sofort als Evidenz verknüpft.
     */
    fun detect(j: JdbcOperations, audit: AuditPort, outbox: Outbox, clock: Clock, ctx: RequestContext, documentId: UUID) {
        val me = facts(j, documentId) ?: return
        val threshold = JdbcFinancePolicies.load(j).duplicateThreshold
        val amount = me.amount?.toString()
        val others = j.query(CANDIDATES_SQL, FACTS_MAPPER, documentId, me.sha, amount, amount, me.currency, me.number, me.number, me.merchant, me.date, me.date, me.merchant)
        for (o in others) {
            val hit = score(me, o, threshold) ?: continue
            val newer = if (isNewer(me, o)) me else o; val older = if (newer === me) o else me
            val safe = hit.certainty == DuplicateCertainty.SAFE
            val id = UUID.randomUUID()
            // Neu anlegen oder einen noch offenen Treffer mit neuen Fachdaten neu bewerten (auch zum sicheren Treffer); entschiedene Treffer bleiben unverändert
            val written = j.query("INSERT INTO taktovia.duplicate_candidate AS c (tenant_id, id, document_id, other_document_id, match_kind, certainty, score, basis, state, decided_by, decided_at, decision_reason) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, CASE WHEN ? THEN clock_timestamp() END, ?) ON CONFLICT (tenant_id, document_id, other_document_id) DO UPDATE SET " +
                "match_kind = EXCLUDED.match_kind, certainty = EXCLUDED.certainty, score = EXCLUDED.score, basis = EXCLUDED.basis, state = EXCLUDED.state, decided_by = EXCLUDED.decided_by, decided_at = EXCLUDED.decided_at, " +
                "decision_reason = EXCLUDED.decision_reason WHERE c.state = 'OFFEN' AND (c.certainty <> EXCLUDED.certainty OR c.score <> EXCLUDED.score OR c.basis <> EXCLUDED.basis) RETURNING c.id, (xmax = 0) AS inserted",
                { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getBoolean(2) },
                id, newer.id, older.id, hit.match.name, hit.certainty.name, hit.score, ExpenseFieldCodec.toJson(hit.basis), if (safe) "GLEICHE_EVIDENZ" else "OFFEN",
                if (safe) SYSTEM_ACTOR else null, safe, if (safe) "Sicherer Treffer (${hit.match}): weitere Evidenz desselben Vorgangs" else null).firstOrNull() ?: continue
            if (!written.second && !safe) continue   // Neubewertung eines offenen Treffers ohne Statuswechsel: leise fortgeschrieben
            audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_DUPLICATE_DETECTED, ObjectRef("expense", newer.id), ctx.correlationId, mapOf("certainty" to hit.certainty.name, "match" to hit.match.name, "score" to hit.score)))
            outbox.append(j, OutboxEvent(written.first, if (written.second) 1 else 2, "expense.duplicate_detected", linkedMapOf("document_id" to newer.id.toString(), "other_document_id" to older.id.toString(), "certainty" to hit.certainty.name,
                "match" to hit.match.name, "score" to hit.score, "state" to (if (safe) "GLEICHE_EVIDENZ" else "OFFEN"), "detected_at" to clock.instant().toString()), clock.instant(), correlationId = ctx.correlationId))
            if (safe) linkIfPrimaryPosted(j, newer.id, older.id, "AUTO_SAFE")
        }
    }

    /** Wurzel der Evidenzkette: der Beleg, dessen Vorgang gilt (folgt `GLEICHE_EVIDENZ`-Kanten vom neueren zum älteren Beleg). */
    private fun root(j: JdbcOperations, documentId: UUID): UUID {
        var cur = documentId
        repeat(20) {
            cur = j.query("SELECT other_document_id FROM taktovia.duplicate_candidate WHERE document_id = ? AND state = 'GLEICHE_EVIDENZ' ORDER BY detected_at, id LIMIT 1", { rs, _ -> rs.getObject(1, UUID::class.java) }, cur).firstOrNull() ?: return cur
        }
        return cur
    }

    private fun eventOf(j: JdbcOperations, documentId: UUID): UUID? =
        j.query("SELECT id FROM taktovia.economic_event WHERE document_id = ? AND reverses_id IS NULL AND scenario = 'ACTUAL'", { rs, _ -> rs.getObject(1, UUID::class.java) }, documentId).firstOrNull()

    /** Belege, die über `GLEICHE_EVIDENZ`-Kanten (auch mittelbar) Evidenz dieses Belegs sind, mit der Sicherheit ihrer Kante. */
    private fun descendants(j: JdbcOperations, documentId: UUID): List<Pair<UUID, String>> = j.query(
        "WITH RECURSIVE t(document_id, certainty) AS (SELECT c.document_id, c.certainty FROM taktovia.duplicate_candidate c WHERE c.other_document_id = ? AND c.state = 'GLEICHE_EVIDENZ' " +
            "UNION SELECT c.document_id, c.certainty FROM taktovia.duplicate_candidate c JOIN t ON c.other_document_id = t.document_id WHERE c.state = 'GLEICHE_EVIDENZ') SELECT document_id, certainty FROM t",
        { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }, documentId)

    private fun link(j: JdbcOperations, eventId: UUID, documentId: UUID, role: String, decision: String, by: String) {
        j.update("INSERT INTO taktovia.evidence_link (tenant_id, id, event_id, document_id, role, decision, decided_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            UUID.randomUUID(), eventId, documentId, role, decision, by)
    }

    /** Ist der Vorgang des älteren Belegs schon gebucht, wird der neuere Beleg (samt seiner eigenen Evidenzen) sofort weitere Evidenz des Kostenereignisses. */
    fun linkIfPrimaryPosted(j: JdbcOperations, newerId: UUID, olderId: UUID, decision: String, by: String = SYSTEM_ACTOR) {
        val event = eventOf(j, root(j, olderId)) ?: return
        link(j, event, newerId, "ADDITIONAL", decision, by)
        descendants(j, newerId).forEach { (d, certainty) -> link(j, event, d, "ADDITIONAL", if (certainty == "SAFE") "AUTO_SAFE" else "MANUAL_SAME", by) }
    }

    /** Mit dem Posting: der Beleg ist PRIMARY-Evidenz seines Ereignisses, bisher verknüpfte Dubletten werden weitere Evidenz desselben Ereignisses. */
    fun onPosted(j: JdbcOperations, ctx: RequestContext, documentId: UUID, eventId: UUID) {
        val by = DocumentRows.actor(ctx).value
        link(j, eventId, documentId, "PRIMARY", "POSTING", by)
        descendants(j, documentId).forEach { (d, certainty) -> link(j, eventId, d, "ADDITIONAL", if (certainty == "SAFE") "AUTO_SAFE" else "MANUAL_SAME", SYSTEM_ACTOR) }
    }

    /** Freigabe- und Postingsperre: ein Beleg, der Evidenz eines anderen Vorgangs ist, wird nie eigener Kostenfall (409 `DUPLICATE_EVIDENCE_LINKED`); ein offener Treffer sperrt bis zur Entscheidung (409 `DUPLICATE_REVIEW_REQUIRED`). */
    fun requireNoBlock(j: JdbcOperations, documentId: UUID, includeOpen: Boolean = true) {
        val states = j.query("SELECT state FROM taktovia.duplicate_candidate WHERE document_id = ?", { rs, _ -> rs.getString(1) }, documentId)
        if ("GLEICHE_EVIDENZ" in states) throw ProblemException(ErrorCode.DUPLICATE_EVIDENCE_LINKED, "Der Beleg ist weitere Evidenz eines anderen Vorgangs und wird nicht als eigener Kostenfall freigegeben.")
        if (includeOpen && "OFFEN" in states) throw ProblemException(ErrorCode.DUPLICATE_REVIEW_REQUIRED, "Ein möglicher Dublettentreffer ist offen; ein Mensch entscheidet zuerst, ob es sich um dieselbe Evidenz oder einen eigenen Vorgang handelt.")
    }

    /** Treffer des Belegs aus seiner Sicht; `other`, `otherDocumentId` und `basis` nur für die Finanzrolle. */
    fun candidates(j: JdbcOperations, documentId: UUID, finance: Boolean): List<DuplicateCandidateView> =
        j.query("SELECT id, document_id, other_document_id, match_kind, certainty, score, basis::text AS basis, state, detected_at, decided_by, decided_at, decision_reason FROM taktovia.duplicate_candidate " +
            "WHERE document_id = ? OR other_document_id = ? ORDER BY detected_at, id", { rs, _ ->
            val newer = rs.getObject("document_id", UUID::class.java) == documentId
            val counterpart = rs.getObject(if (newer) "other_document_id" else "document_id", UUID::class.java)
            DuplicateCandidateView(rs.getObject("id", UUID::class.java), if (newer) DuplicateRole.NEWER else DuplicateRole.OLDER, if (finance) counterpart else null, DuplicateMatch.valueOf(rs.getString("match_kind")),
                DuplicateCertainty.valueOf(rs.getString("certainty")), rs.getInt("score"), if (finance) ExpenseFieldCodec.parseTree(rs.getString("basis")) else null, DuplicateState.valueOf(rs.getString("state")),
                rs.getTimestamp("detected_at").toInstant(), rs.getString("decided_by"), rs.getTimestamp("decided_at")?.toInstant(), rs.getString("decision_reason"), if (finance) facts(j, counterpart)?.summary() else null)
        }, documentId, documentId)
}

/** Aufruf nach jeder Änderung, die Treffer verändern kann (Original geprüft, neue Revision, Tankwerte): ein Ort für die Einbindung. */
internal object DuplicateHook {
    fun afterChange(j: JdbcOperations, audit: AuditPort, outbox: Outbox, clock: Clock, ctx: RequestContext, documentId: UUID) = DuplicateRows.detect(j, audit, outbox, clock, ctx, documentId)
}

internal class JdbcDuplicateReview(private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock) : DuplicateReview {

    override fun check(ctx: RequestContext, documentId: UUID): List<DuplicateCandidateView> {
        require(ctx, SCOPE_WRITE)
        return ctxTx.run(ctx) { j ->
            DocumentRows.visible(j, ctx, documentId, lock = true)   // Belegsperre: Prüflauf, Entscheidung und Freigabe sind serialisiert
            DuplicateRows.detect(j, audit, outbox, clock, ctx, documentId)
            DuplicateRows.candidates(j, documentId, DocumentRows.isFinance(ctx))
        }
    }

    override fun list(ctx: RequestContext, documentId: UUID): List<DuplicateCandidateView> {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j -> DocumentRows.visible(j, ctx, documentId); DuplicateRows.candidates(j, documentId, DocumentRows.isFinance(ctx)) }
    }

    override fun decide(ctx: RequestContext, documentId: UUID, candidateId: UUID, expectedRevision: Long, decision: DuplicateDecision, reason: String): ExpenseView {
        if (!ctx.hasScope(SCOPE_REVIEW) || ctx.actor.type != ActorType.HUMAN || !DocumentRows.isFinance(ctx)) throw ProblemException(ErrorCode.FORBIDDEN)
        val text = reason.trim()
        if (text.length < 3) throw ProblemException.invalid(Violation("/reason", if (text.isEmpty()) ViolationCode.REQUIRED else ViolationCode.TOO_SHORT))
        if (text.length > 1000) throw ProblemException.invalid(Violation("/reason", ViolationCode.TOO_LONG))
        return ctxTx.run(ctx) { j ->
            val pair = j.query("SELECT document_id, other_document_id FROM taktovia.duplicate_candidate WHERE id = ? AND (document_id = ? OR other_document_id = ?)", { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) },
                candidateId, documentId, documentId).firstOrNull() ?: throw ProblemException.notFound()
            // beide Belege in fester Reihenfolge sperren (keine Zyklen); danach ist die Entscheidung gegen Freigabe und Korrektur serialisiert
            j.query("SELECT id FROM taktovia.expense_document WHERE id IN (?, ?) ORDER BY id FOR NO KEY UPDATE", { rs, _ -> rs.getObject(1, UUID::class.java) }, pair.first, pair.second)
            val row = DocumentRows.visible(j, ctx, documentId)
            if (row.view.revision != expectedRevision) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Beleg wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
            val current = j.queryForObject("SELECT state FROM taktovia.duplicate_candidate WHERE id = ?", String::class.java, candidateId)!!
            if (current != "OFFEN") {
                if (current == decision.state.name) return@run ExpenseViews.build(j, row, ctx)   // dieselbe Entscheidung wiederholt: unverändert
                throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Der Treffer ist bereits entschieden und bleibt unverändert.")
            }
            val newerState = j.queryForObject("SELECT state FROM taktovia.expense_document WHERE id = ?", String::class.java, pair.first)!!
            if (newerState in FINAL_STATES) throw ProblemException(ErrorCode.IMMUTABLE_RECORD, "Der Beleg ist freigegeben oder gebucht.")
            j.update("UPDATE taktovia.duplicate_candidate SET state = ?, decided_by = ?, decided_at = clock_timestamp(), decision_reason = ? WHERE id = ? AND state = 'OFFEN'", decision.state.name, ctx.actor.subject.take(200), text, candidateId)
            audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_DUPLICATE_DECIDED, ObjectRef("expense", pair.first), ctx.correlationId, mapOf("decision" to decision.name)))
            if (decision == DuplicateDecision.GLEICHE_EVIDENZ) DuplicateRows.linkIfPrimaryPosted(j, pair.first, pair.second, "MANUAL_SAME", DocumentRows.actor(ctx).value)
            ExpenseViews.build(j, DocumentRows.visible(j, ctx, documentId), ctx)
        }
    }

    private companion object {
        const val SCOPE_REVIEW = "expenses:review"
        val FINAL_STATES = setOf("APPROVED", "POSTED")
    }
}
