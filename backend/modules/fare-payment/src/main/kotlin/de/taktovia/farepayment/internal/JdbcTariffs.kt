package de.taktovia.farepayment.internal

import de.taktovia.farepayment.api.CreateTariffRevision
import de.taktovia.farepayment.api.FareEngine
import de.taktovia.farepayment.api.FareRequest
import de.taktovia.farepayment.api.FareResult
import de.taktovia.farepayment.api.PriceResult
import de.taktovia.farepayment.api.TARIFF_REVISION_PUBLISHED_EVENT
import de.taktovia.farepayment.api.TariffRevisionView
import de.taktovia.farepayment.api.TariffRuleView
import de.taktovia.farepayment.api.TariffState
import de.taktovia.farepayment.api.Tariffs
import de.taktovia.farepayment.api.UpdateTariffRevision
import de.taktovia.platform.approval.FourEyes
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.authz.ObjectAuthorizer
import de.taktovia.platform.authz.ScopedObject
import de.taktovia.platform.authz.ServiceScoped
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.DbErrorMapper
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

private const val COLS = "r.id, r.service_id, r.tariff_class, r.revision_no, r.state, r.valid_from, r.valid_to, r.currency, r.reason, r.row_version, r.created_by, r.created_at, " +
    "r.reviewed_by, r.reviewed_at, r.published_by, r.published_at"
private val CLASS = Regex("^[A-Z][A-Z0-9_]{0,39}$")

/**
 * Tarifrevisionen (WP-M2-07). Jede schreibende Operation sperrt die Revisionszeile (`FOR UPDATE`) und prüft Zustand und erwartete Ressourcenrevision; die Nummerierung
 * je Dienst und Klasse serialisiert eine transaktionsbezogene Beratungssperre. Überlappungsverbot, Vier-Augen, Pflichtangaben und Unveränderlichkeit sichert zusätzlich
 * die Datenbank (Exclusion Constraint `tariff_revision_published_no_overlap`, CHECKs und Trigger `tariff_revision_guard`/`tariff_rule_guard`).
 */
@ServiceScoped
internal class JdbcTariffs(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock, private val authorizer: ObjectAuthorizer,
) : Tariffs {

    override fun create(ctx: RequestContext, cmd: CreateTariffRevision): TariffRevisionView {
        requireScope(ctx, "tariffs:write")
        return ctxTx.run(ctx) { j -> doCreate(j, ctx, cmd) }
    }

    @AuditRequired
    private fun doCreate(j: JdbcOperations, ctx: RequestContext, cmd: CreateTariffRevision): TariffRevisionView {
        val v = mutableListOf<Violation>()
        if (cmd.serviceId == null) v += Violation("/service_id", ViolationCode.REQUIRED)
        val cls = cmd.tariffClass
        if (cls == null) v += Violation("/tariff_class", ViolationCode.REQUIRED) else if (!CLASS.matches(cls)) v += Violation("/tariff_class", ViolationCode.PATTERN_MISMATCH)
        if (cmd.validFrom == null) v += Violation("/valid_from", ViolationCode.REQUIRED)
        if (cmd.validFrom != null && cmd.validTo != null && !cmd.validTo.isAfter(cmd.validFrom)) v += Violation("/valid_to", ViolationCode.INTERVAL_INVALID)
        reason(cmd.reason, v)
        val currency = cmd.currency
        if (currency == null) v += Violation("/currency", ViolationCode.REQUIRED)
        else if (j.queryForObject("SELECT count(*) FROM taktovia.currency WHERE code = ?", Int::class.java, currency) != 1) v += Violation("/currency", ViolationCode.CURRENCY_UNKNOWN)
        val rules = TariffRuleParser.parse(cmd.rules, v)
        if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray())
        val serviceId = cmd.serviceId!!
        requireService(j, ctx, serviceId)
        j.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "tariff:${ctx.tenant}:$serviceId:$cls")
        val no = j.queryForObject("SELECT COALESCE(max(revision_no), 0) + 1 FROM taktovia.tariff_revision WHERE service_id = ? AND tariff_class = ?", Int::class.java, serviceId, cls)!!
        val id = UUID.randomUUID()
        try {
            j.update("INSERT INTO taktovia.tariff_revision (tenant_id, id, service_id, tariff_class, revision_no, valid_from, valid_to, currency, reason, created_by) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, serviceId, cls, no, Timestamp.from(cmd.validFrom), cmd.validTo?.let { Timestamp.from(it) }, currency, cmd.reason!!.trim(), FourEyes.refOf(ctx).value)
            insertRules(j, id, rules)
        } catch (e: DataAccessException) { throw DbErrorMapper.map(e) }
        val view = read(j, id)
        audit.record(j, entry(ctx, AuditActions.TARIFF_REVISION_CHANGED, view, "created"))
        return view
    }

    override fun update(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: UpdateTariffRevision): TariffRevisionView {
        requireScope(ctx, "tariffs:write")
        return ctxTx.run(ctx) { j -> doUpdate(j, ctx, id, expectedRevision, cmd) }
    }

    @AuditRequired
    private fun doUpdate(j: JdbcOperations, ctx: RequestContext, id: UUID, expected: Long, cmd: UpdateTariffRevision): TariffRevisionView {
        val cur = lockForWrite(j, ctx, id, expected)
        if (cur.state != TariffState.ENTWURF) throw immutable()
        val v = mutableListOf<Violation>()
        if (cmd.validFrom == null) v += Violation("/valid_from", ViolationCode.REQUIRED)
        if (cmd.validFrom != null && cmd.validTo != null && !cmd.validTo.isAfter(cmd.validFrom)) v += Violation("/valid_to", ViolationCode.INTERVAL_INVALID)
        reason(cmd.reason, v)
        val rules = TariffRuleParser.parse(cmd.rules, v)
        if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray())
        try {
            j.update("DELETE FROM taktovia.tariff_rule WHERE revision_id = ?", id)
            insertRules(j, id, rules)
            j.update("UPDATE taktovia.tariff_revision SET valid_from = ?, valid_to = ?, reason = ? WHERE id = ?", Timestamp.from(cmd.validFrom), cmd.validTo?.let { Timestamp.from(it) }, cmd.reason!!.trim(), id)
        } catch (e: DataAccessException) { throw DbErrorMapper.map(e) }
        val view = read(j, id)
        audit.record(j, entry(ctx, AuditActions.TARIFF_REVISION_CHANGED, view, "updated"))
        return view
    }

    override fun review(ctx: RequestContext, id: UUID, expectedRevision: Long): TariffRevisionView {
        requireScope(ctx, "tariffs:publish")
        return ctxTx.run(ctx) { j -> doReview(j, ctx, id, expectedRevision) }
    }

    @AuditRequired
    private fun doReview(j: JdbcOperations, ctx: RequestContext, id: UUID, expected: Long): TariffRevisionView {
        val cur = lockForWrite(j, ctx, id, expected)
        if (cur.state != TariffState.ENTWURF) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Nur ein Entwurf kann geprüft werden.")
        val reviewer = FourEyes.requireDistinctReviewer(ctx, cur.createdBy)
        completeness(cur)
        try {
            j.update("UPDATE taktovia.tariff_revision SET state = 'GEPRUEFT', reviewed_by = ?, reviewed_at = ? WHERE id = ?", reviewer.value, Timestamp.from(clock.instant()), id)
        } catch (e: DataAccessException) { throw DbErrorMapper.map(e) }
        val view = read(j, id)
        audit.record(j, entry(ctx, AuditActions.TARIFF_REVISION_CHANGED, view, "reviewed"))
        return view
    }

    override fun publish(ctx: RequestContext, id: UUID, expectedRevision: Long): TariffRevisionView {
        requireScope(ctx, "tariffs:publish")
        return ctxTx.run(ctx) { j -> doPublish(j, ctx, id, expectedRevision) }
    }

    @AuditRequired
    private fun doPublish(j: JdbcOperations, ctx: RequestContext, id: UUID, expected: Long): TariffRevisionView {
        val cur = lockForWrite(j, ctx, id, expected)
        if (cur.state != TariffState.GEPRUEFT) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Nur eine geprüfte Revision kann veröffentlicht werden.")
        val publisher = FourEyes.requireDistinctReviewer(ctx, cur.createdBy)
        completeness(cur)
        try {
            // Überlappung mit einer anderen veröffentlichten Revision desselben Dienstes und derselben Klasse: Exclusion Constraint (409); bei zwei parallelen Veröffentlichungen gewinnt genau eine
            j.update("UPDATE taktovia.tariff_revision SET state = 'VEROEFFENTLICHT', published_by = ?, published_at = ? WHERE id = ?", publisher.value, Timestamp.from(clock.instant()), id)
        } catch (e: DataAccessException) { throw DbErrorMapper.map(e) }
        val view = read(j, id)
        audit.record(j, entry(ctx, AuditActions.TARIFF_REVISION_PUBLISHED, view, "published"))
        outbox.append(j, OutboxEvent(view.id, view.revision, TARIFF_REVISION_PUBLISHED_EVENT, linkedMapOf(
            "revision_id" to view.id.toString(), "service_id" to view.serviceId.toString(), "tariff_class" to view.tariffClass, "revision_no" to view.revisionNo,
            "valid_from" to view.validFrom.toString(), "valid_to" to view.validTo?.toString(), "currency" to view.currency,
            "rule_type" to view.rules.first().ruleType, "rule_count" to view.rules.size), clock.instant(), correlationId = ctx.correlationId))
        return view
    }

    override fun get(ctx: RequestContext, id: UUID): TariffRevisionView {
        requireScope(ctx, "tariffs:read")
        return ctxTx.run(ctx, readOnly = true) { j -> readOrNull(j, id) } ?: throw ProblemException.notFound()
    }

    override fun list(ctx: RequestContext, serviceId: UUID?, state: TariffState?, limit: Int, after: Pair<Instant, UUID>?): List<TariffRevisionView> {
        requireScope(ctx, "tariffs:read")
        return ctxTx.run(ctx, readOnly = true) { j ->
            val rows = j.query("SELECT $COLS FROM taktovia.tariff_revision r WHERE (CAST(? AS uuid) IS NULL OR r.service_id = CAST(? AS uuid)) AND (CAST(? AS text) IS NULL OR r.state = CAST(? AS text)) " +
                "AND (r.created_at, r.id) > (?, ?) ORDER BY r.created_at, r.id LIMIT ?", HEAD, serviceId, serviceId, state?.name, state?.name,
                Timestamp.from(after?.first ?: Instant.EPOCH), after?.second ?: UUID(0, 0), limit + 1)
            withRules(j, rows)
        }
    }

    override fun effective(ctx: RequestContext, serviceId: UUID, tariffClass: String, at: Instant): TariffRevisionView? = ctxTx.run(ctx, readOnly = true) { j -> effectiveIn(j, serviceId, tariffClass, at) }

    /** Wie [effective], aber in der laufenden Transaktion des Aufrufers (Quote-Bildung, WP-M2-09). */
    fun effectiveIn(j: JdbcOperations, serviceId: UUID, tariffClass: String, at: Instant): TariffRevisionView? {
        val head = j.query("SELECT $COLS FROM taktovia.tariff_revision r WHERE r.service_id = ? AND r.tariff_class = ? AND r.state = 'VEROEFFENTLICHT' " +
            "AND r.valid_from <= ? AND (r.valid_to IS NULL OR ? < r.valid_to) ORDER BY r.valid_from DESC LIMIT 1", HEAD, serviceId, tariffClass, Timestamp.from(at), Timestamp.from(at)).singleOrNull()
        return head?.let { withRules(j, listOf(it)).single() }
    }

    override fun price(ctx: RequestContext, serviceId: UUID, tariffClass: String, at: Instant, request: FareRequest): PriceResult {
        val revision = effective(ctx, serviceId, tariffClass, at) ?: return PriceResult.NoTariff("Kein Tarif zum Preisstichtag wirksam.")
        return when (val r = FareEngine.price(revision, request)) {
            is FareResult.Priced -> PriceResult.Priced(revision.id, revision.revisionNo, r.breakdown)
            is FareResult.Rejected -> PriceResult.Rejected(r.reason, r.detail)
        }
    }

    // ---- intern ------------------------------------------------------------------------------------------------------------------------

    private fun requireScope(ctx: RequestContext, scope: String) { if (!ctx.hasScope(scope)) throw ProblemException(ErrorCode.FORBIDDEN) }

    private fun reason(reason: String?, v: MutableList<Violation>) {
        if (reason == null) v += Violation("/reason", ViolationCode.REQUIRED)
        else if (reason.isBlank()) v += Violation("/reason", ViolationCode.TOO_SHORT) else if (reason.trim().length > 500) v += Violation("/reason", ViolationCode.TOO_LONG)
    }

    /** Dienst im Mandanten (RLS: fremde und unbekannte Dienste sind nicht sichtbar, 404) und Dienstberechtigung; verwaltende Rollen (`services:write`) wirken mandantenweit. */
    private fun requireService(j: JdbcOperations, ctx: RequestContext, serviceId: UUID) {
        if (j.queryForObject("SELECT count(*) FROM taktovia.service WHERE id = ?", Int::class.java, serviceId) != 1) throw ProblemException.notFound()
        if (!ctx.hasScope("services:write")) authorizer.requireWrite(j, ctx, ScopedObject("service", serviceId, ctx.tenant, serviceId))
    }

    /** Sperrt die Revision, prüft Sichtbarkeit (404), Dienstberechtigung und die erwartete Ressourcenrevision (412). */
    private fun lockForWrite(j: JdbcOperations, ctx: RequestContext, id: UUID, expected: Long): TariffRevisionView {
        val head = j.query("SELECT $COLS FROM taktovia.tariff_revision r WHERE r.id = ? FOR UPDATE OF r", HEAD, id).singleOrNull() ?: throw ProblemException.notFound()
        requireService(j, ctx, head.serviceId)
        if (head.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Ressource wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        return withRules(j, listOf(head)).single()
    }

    private fun completeness(v: TariffRevisionView) {
        if (v.rules.isEmpty()) throw ProblemException.invalid(Violation("/rules", ViolationCode.TOO_FEW_ITEMS))
    }

    private fun immutable() = ProblemException(ErrorCode.IMMUTABLE_RECORD, "Die Revision ist nicht mehr im Entwurf; eine Korrektur wird als neue Revision angelegt.")

    private fun insertRules(j: JdbcOperations, revisionId: UUID, rules: List<ParsedRule>) = rules.forEachIndexed { i, r ->
        j.update("INSERT INTO taktovia.tariff_rule (tenant_id, id, revision_id, rule_no, rule_type, person_mode, amount_minor, zone_from, zone_to, rate_per_km, booking_fee_minor, tax_rate, tax_mode, tax_rule_revision) " +
            "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), revisionId, i + 1, r.type.name, r.personMode.name, r.amountMinor, r.zoneFrom, r.zoneTo, r.ratePerKm,
            r.bookingFeeMinor, r.taxRate, r.taxMode.name, r.taxRuleRevision)
    }

    private fun readOrNull(j: JdbcOperations, id: UUID): TariffRevisionView? =
        j.query("SELECT $COLS FROM taktovia.tariff_revision r WHERE r.id = ?", HEAD, id).singleOrNull()?.let { withRules(j, listOf(it)).single() }

    private fun read(j: JdbcOperations, id: UUID): TariffRevisionView = readOrNull(j, id)!!

    private fun withRules(j: JdbcOperations, heads: List<TariffRevisionView>): List<TariffRevisionView> {
        if (heads.isEmpty()) return heads
        val byRevision = j.query("SELECT revision_id, id, rule_no, rule_type, person_mode, amount_minor, zone_from, zone_to, rate_per_km, booking_fee_minor, tax_rate, tax_mode, tax_rule_revision " +
            "FROM taktovia.tariff_rule WHERE revision_id = ANY (CAST(? AS uuid[])) ORDER BY revision_id, rule_no", { rs, _ ->
            rs.getObject(1, UUID::class.java) to TariffRuleView(rs.getObject(2, UUID::class.java), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getObject(7, UUID::class.java),
                rs.getObject(8, UUID::class.java), rs.getBigDecimal(9)?.toPlainString(), rs.getLong(10), rs.getBigDecimal(11).toPlainString(), rs.getString(12), rs.getString(13))
        }, heads.joinToString(",", "{", "}") { it.id.toString() }).groupBy({ it.first }, { it.second })
        return heads.map { it.copy(rules = byRevision[it.id].orEmpty()) }
    }

    private fun entry(ctx: RequestContext, action: String, v: TariffRevisionView, change: String) = AuditEntry(FourEyes.refOf(ctx), action, ObjectRef("tariff_revision", v.id), ctx.correlationId,
        linkedMapOf<String, Any?>("change" to change, "service_id" to v.serviceId.toString(), "tariff_class" to v.tariffClass, "revision_no" to v.revisionNo, "state" to v.state.name,
            "valid_from" to v.validFrom.toString(), "valid_to" to v.validTo?.toString(), "rule_count" to v.rules.size, "created_by" to v.createdBy, "reviewed_by" to v.reviewedBy,
            "published_by" to v.publishedBy, "revision" to v.revision))

    private companion object {
        val HEAD = RowMapper { rs, _ ->
            TariffRevisionView(rs.getObject("id", UUID::class.java), rs.getObject("service_id", UUID::class.java), rs.getString("tariff_class"), rs.getInt("revision_no"),
                TariffState.valueOf(rs.getString("state")), rs.getTimestamp("valid_from").toInstant(), rs.getTimestamp("valid_to")?.toInstant(), rs.getString("currency").trim(),
                rs.getString("reason"), rs.getLong("row_version"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(), rs.getString("reviewed_by"),
                rs.getTimestamp("reviewed_at")?.toInstant(), rs.getString("published_by"), rs.getTimestamp("published_at")?.toInstant(), emptyList())
        }
    }
}
