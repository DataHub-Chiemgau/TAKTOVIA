package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.CostCenterAdmin
import de.taktovia.documentsfinance.api.CostCenterView
import de.taktovia.documentsfinance.api.CreateCostCenter
import de.taktovia.documentsfinance.api.UpdateCostCenter
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
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Kostenstellen als Stammobjekt (WP-M1-09; OP-07-Vorgabe: nur Stammobjekt, `legal_entity_id` Pflicht, Finanzlogik ist M4). Die Kennung ist je Mandant
 * eindeutig (`UNIQUE(tenant_id, kind, business_ref)`): zwei gleichzeitige Anlagen derselben Kennung lassen genau eine zu, die andere erhält 409.
 */
internal class JdbcCostCenterAdmin(private val ctxTx: ContextTransactions, private val audit: AuditPort) : CostCenterAdmin {

    override fun create(ctx: RequestContext, cmd: CreateCostCenter): CostCenterView {
        throwIf(listOfNotNull(code(cmd.code), name(cmd.name)))
        try {
            return ctxTx.run(ctx) { j -> doCreate(j, ctx, cmd) }
        } catch (e: DuplicateKeyException) {
            throw ProblemException(ErrorCode.CONFLICT, "Die Kennung der Kostenstelle ist in diesem Mandanten bereits vergeben.", cause = e)
        }
    }

    @AuditRequired
    private fun doCreate(j: JdbcOperations, ctx: RequestContext, cmd: CreateCostCenter): CostCenterView {
        requireRef(j, "legal_entity", cmd.legalEntityId); cmd.regionId?.let { requireRef(j, "region", it) }; cmd.serviceId?.let { requireRef(j, "service", it) }
        val v = j.query(
            "INSERT INTO taktovia.cost_object AS c (tenant_id, id, legal_entity_id, kind, business_ref, name, region_id, service_id, created_by) " +
                "VALUES (taktovia.current_tenant(), ?, ?, 'COST_CENTER', ?, ?, ?, ?, ?) RETURNING $COLUMNS",
            MAPPER, UUID.randomUUID(), cmd.legalEntityId, cmd.code, cmd.name, cmd.regionId, cmd.serviceId, actor(ctx).value).single()
        record(j, ctx, v, "created")
        return v
    }

    override fun update(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: UpdateCostCenter): CostCenterView {
        throwIf(listOfNotNull(name(cmd.name)))
        return ctxTx.run(ctx) { j -> doUpdate(j, ctx, id, expectedRevision, cmd) }
    }

    @AuditRequired
    private fun doUpdate(j: JdbcOperations, ctx: RequestContext, id: UUID, expected: Long, cmd: UpdateCostCenter): CostCenterView {
        cmd.regionId?.let { requireRef(j, "region", it) }; cmd.serviceId?.let { requireRef(j, "service", it) }
        val v = j.query("UPDATE taktovia.cost_object c SET name = ?, region_id = ?, service_id = ? WHERE c.id = ? AND c.kind = 'COST_CENTER' AND c.revision = ? RETURNING $COLUMNS",
            MAPPER, cmd.name, cmd.regionId, cmd.serviceId, id, expected).singleOrNull()
            ?: throw (if (j.queryForObject("SELECT count(*) FROM taktovia.cost_object WHERE id = ? AND kind = 'COST_CENTER'", Int::class.java, id) == 1)
                ProblemException(ErrorCode.REVISION_MISMATCH, "Die Ressource wurde zwischenzeitlich geändert; aktuelle Revision lesen.") else ProblemException.notFound())
        if (v.revision != expected) record(j, ctx, v, "updated")
        return v
    }

    override fun list(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<CostCenterView> = ctxTx.run(ctx, readOnly = true) { j ->
        if (after == null) j.query("SELECT $COLUMNS FROM taktovia.cost_object c WHERE c.kind = 'COST_CENTER' ORDER BY c.created_at, c.id LIMIT ?", MAPPER, limit + 1)
        else j.query("SELECT $COLUMNS FROM taktovia.cost_object c WHERE c.kind = 'COST_CENTER' AND (c.created_at, c.id) > (?, ?) ORDER BY c.created_at, c.id LIMIT ?",
            MAPPER, Timestamp.from(after.first), after.second, limit + 1)
    }

    private fun record(j: JdbcOperations, ctx: RequestContext, v: CostCenterView, change: String) {
        audit.record(j, AuditEntry(actor(ctx), AuditActions.COST_CENTER_CHANGED, ObjectRef("cost_center", v.id), ctx.correlationId, mapOf(
            "change" to change, "revision" to v.revision, "code" to v.code, "name" to v.name, "region_id" to v.regionId?.toString(), "service_id" to v.serviceId?.toString())))
    }

    /** Ein Rechtsträger, eine Region oder ein Dienst eines anderen Mandanten ist als Verweis nicht verwendbar: wie „nicht vorhanden“ (404). */
    private fun requireRef(j: JdbcOperations, table: String, id: UUID) {
        val n = when (table) {
            "legal_entity" -> j.queryForObject("SELECT count(*) FROM taktovia.legal_entity WHERE id = ?", Int::class.java, id)
            "region" -> j.queryForObject("SELECT count(*) FROM taktovia.region WHERE id = ?", Int::class.java, id)
            "service" -> j.queryForObject("SELECT count(*) FROM taktovia.service WHERE id = ?", Int::class.java, id)
            else -> error("unbekannte Tabelle")
        }
        if (n != 1) throw ProblemException.notFound()
    }

    private fun code(s: String): Violation? = when { s.isEmpty() -> Violation("/code", ViolationCode.TOO_SHORT); s.length > 40 -> Violation("/code", ViolationCode.TOO_LONG); else -> null }
    private fun name(s: String): Violation? = when { s.isEmpty() -> Violation("/name", ViolationCode.TOO_SHORT); s.length > 200 -> Violation("/name", ViolationCode.TOO_LONG); else -> null }
    private fun throwIf(v: List<Violation>) { if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray()) }
    private fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    private companion object {
        const val COLUMNS = "c.id, c.business_ref, COALESCE(c.name, c.business_ref) AS name, c.legal_entity_id, c.region_id, c.service_id, c.revision, c.created_at"
        val MAPPER = RowMapper { rs, _ ->
            CostCenterView(rs.getObject("id", UUID::class.java), rs.getString("business_ref"), rs.getString("name"), rs.getObject("legal_entity_id", UUID::class.java),
                rs.getObject("region_id", UUID::class.java), rs.getObject("service_id", UUID::class.java), rs.getLong("revision"), rs.getTimestamp("created_at").toInstant())
        }
    }
}
