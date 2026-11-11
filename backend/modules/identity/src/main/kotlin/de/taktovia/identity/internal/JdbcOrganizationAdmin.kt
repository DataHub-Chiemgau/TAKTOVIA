package de.taktovia.identity.internal

import de.taktovia.identity.api.CreateLegalEntity
import de.taktovia.identity.api.CreateOrganization
import de.taktovia.identity.api.LegalEntityView
import de.taktovia.identity.api.OrganizationAdmin
import de.taktovia.identity.api.OrganizationRole
import de.taktovia.identity.api.OrganizationView
import de.taktovia.identity.api.UpdateLegalEntity
import de.taktovia.identity.api.UpdateOrganization
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Rechtsträger und Organisationen (WP-M1-05, LH-I.4-MUL-003): Fachzeile, Audit und Outbox in **einer** Transaktion im Mandanten des Kontexts.
 * Mandantentrennung durch RLS und zusammengesetzte Schlüssel: ein Rechtsträger oder eine Organisation eines anderen Mandanten ist weder lesbar
 * noch als Verweis im Body verwendbar (404). Eine Änderung ohne Wirkung (gleiche Werte) erhöht die Revision nicht und erzeugt weder Audit noch Ereignis.
 */
internal class JdbcOrganizationAdmin(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock,
) : OrganizationAdmin {

    // ---- Rechtsträger -----------------------------------------------------------------------------------------------------------------

    override fun createLegalEntity(ctx: RequestContext, cmd: CreateLegalEntity): LegalEntityView {
        val v = mutableListOf<Violation>()
        legalEntityFields(cmd.name, cmd.country, v)
        if (cmd.taxReferenceToken != null && cmd.taxReferenceToken.length !in 1..200) v += Violation("/tax_reference_token", ViolationCode.INVALID_VALUE)
        throwIf(v)
        return ctxTx.run(ctx) { j -> doCreateLegalEntity(j, ctx, cmd) }
    }

    override fun updateLegalEntity(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: UpdateLegalEntity): LegalEntityView {
        val v = mutableListOf<Violation>()
        legalEntityFields(cmd.name, cmd.country, v)
        throwIf(v)
        return ctxTx.run(ctx) { j -> doUpdateLegalEntity(j, ctx, id, expectedRevision, cmd) }
    }

    override fun listLegalEntities(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<LegalEntityView> = ctxTx.run(ctx, readOnly = true) { j ->
        if (after == null) j.query("SELECT $LE_COLUMNS FROM taktovia.legal_entity e ORDER BY e.created_at, e.id LIMIT ?", LE_MAPPER, limit + 1)
        else j.query("SELECT $LE_COLUMNS FROM taktovia.legal_entity e WHERE (e.created_at, e.id) > (?, ?) ORDER BY e.created_at, e.id LIMIT ?", LE_MAPPER,
            Timestamp.from(after.first), after.second, limit + 1)
    }

    @AuditRequired
    private fun doCreateLegalEntity(j: JdbcOperations, ctx: RequestContext, cmd: CreateLegalEntity): LegalEntityView {
        val id = UUID.randomUUID()
        val view = j.query(
            "INSERT INTO taktovia.legal_entity AS e (tenant_id, id, name, country, tax_reference_token, created_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?) RETURNING $LE_COLUMNS",
            LE_MAPPER, id, cmd.name, cmd.country, cmd.taxReferenceToken, auditActor(ctx).value,
        ).single()
        record(j, ctx, "legal_entity", "created", view.id, view.revision, mapOf("name" to view.name, "country" to view.country), legalEntityPayload("created", view))
        return view
    }

    @AuditRequired
    private fun doUpdateLegalEntity(j: JdbcOperations, ctx: RequestContext, id: UUID, expected: Long, cmd: UpdateLegalEntity): LegalEntityView {
        val view = j.query("UPDATE taktovia.legal_entity e SET name = ?, country = ? WHERE e.id = ? AND e.revision = ? RETURNING $LE_COLUMNS",
            LE_MAPPER, cmd.name, cmd.country, id, expected).singleOrNull()
            ?: throw missing(j, "legal_entity", id)
        if (view.revision != expected) record(j, ctx, "legal_entity", "updated", view.id, view.revision, mapOf("name" to view.name, "country" to view.country), legalEntityPayload("updated", view))
        return view
    }

    // ---- Organisationen ---------------------------------------------------------------------------------------------------------------

    override fun createOrganization(ctx: RequestContext, cmd: CreateOrganization): OrganizationView {
        val v = mutableListOf<Violation>()
        text(cmd.name, "/name", v); text(cmd.federationRef, "/federation_ref", v)
        roles(cmd.roles, v); interval(cmd.rolesValidFrom, cmd.rolesValidTo, v)
        throwIf(v)
        try {
            return ctxTx.run(ctx) { j -> doCreateOrganization(j, ctx, cmd) }
        } catch (e: DuplicateKeyException) {
            throw ProblemException(ErrorCode.CONFLICT, "Die federation_ref ist in diesem Mandanten bereits vergeben.", cause = e)
        }
    }

    override fun updateOrganization(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: UpdateOrganization): OrganizationView {
        val v = mutableListOf<Violation>()
        text(cmd.name, "/name", v); roles(cmd.roles, v); interval(cmd.rolesValidFrom, cmd.rolesValidTo, v)
        throwIf(v)
        return ctxTx.run(ctx) { j -> doUpdateOrganization(j, ctx, id, expectedRevision, cmd) }
    }

    override fun getOrganization(ctx: RequestContext, id: UUID): OrganizationView = ctxTx.run(ctx, readOnly = true) { j ->
        j.query("SELECT $ORG_COLUMNS FROM taktovia.organization o WHERE o.id = ?", ORG_MAPPER, id).singleOrNull() ?: throw ProblemException.notFound()
    }

    override fun listOrganizations(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<OrganizationView> = ctxTx.run(ctx, readOnly = true) { j ->
        if (after == null) j.query("SELECT $ORG_COLUMNS FROM taktovia.organization o ORDER BY o.created_at, o.id LIMIT ?", ORG_MAPPER, limit + 1)
        else j.query("SELECT $ORG_COLUMNS FROM taktovia.organization o WHERE (o.created_at, o.id) > (?, ?) ORDER BY o.created_at, o.id LIMIT ?", ORG_MAPPER,
            Timestamp.from(after.first), after.second, limit + 1)
    }

    @AuditRequired
    private fun doCreateOrganization(j: JdbcOperations, ctx: RequestContext, cmd: CreateOrganization): OrganizationView {
        requireLegalEntity(j, cmd.legalEntityId)
        val id = UUID.randomUUID()
        val view = j.query(
            "INSERT INTO taktovia.organization AS o (tenant_id, id, legal_entity_id, name, federation_ref, roles, roles_valid_from, roles_valid_to, created_by) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, CAST(? AS text[]), COALESCE(?, clock_timestamp()), ?, ?) RETURNING $ORG_COLUMNS",
            ORG_MAPPER, id, cmd.legalEntityId, cmd.name, cmd.federationRef, arrayLiteral(cmd.roles),
            cmd.rolesValidFrom?.let(Timestamp::from), cmd.rolesValidTo?.let(Timestamp::from), auditActor(ctx).value,
        ).single()
        record(j, ctx, "organization", "created", view.id, view.revision, orgChanges(view), organizationPayload("created", view))
        return view
    }

    @AuditRequired
    private fun doUpdateOrganization(j: JdbcOperations, ctx: RequestContext, id: UUID, expected: Long, cmd: UpdateOrganization): OrganizationView {
        requireLegalEntity(j, cmd.legalEntityId)
        val view = j.query(
            "UPDATE taktovia.organization o SET legal_entity_id = ?, name = ?, roles = CAST(? AS text[]), roles_valid_from = COALESCE(?, o.roles_valid_from), roles_valid_to = ? " +
                "WHERE o.id = ? AND o.revision = ? RETURNING $ORG_COLUMNS",
            ORG_MAPPER, cmd.legalEntityId, cmd.name, arrayLiteral(cmd.roles), cmd.rolesValidFrom?.let(Timestamp::from), cmd.rolesValidTo?.let(Timestamp::from), id, expected,
        ).singleOrNull() ?: throw missing(j, "organization", id)
        if (view.revision != expected) record(j, ctx, "organization", "updated", view.id, view.revision, orgChanges(view), organizationPayload("updated", view))
        return view
    }

    // ---- gemeinsam --------------------------------------------------------------------------------------------------------------------

    /** Genau ein Auditeintrag und genau ein Outbox-Ereignis je Änderung. */
    private fun record(j: JdbcOperations, ctx: RequestContext, entity: String, change: String, id: UUID, revision: Long, changes: Map<String, Any?>, payload: Map<String, Any?>) {
        audit.record(j, AuditEntry(
            actor = auditActor(ctx), action = AuditActions.ORGANIZATION_CHANGED, obj = ObjectRef(entity, id), correlationId = ctx.correlationId,
            changes = changes + mapOf("change" to change, "revision" to revision),
        ))
        outbox.append(j, OutboxEvent(id, revision, "organization.changed", payload, clock.instant(), correlationId = ctx.correlationId))
    }

    /** Warum keine Zeile: unbekannt oder fremder Mandant (RLS) 404, sonst veraltete Revision 412. */
    private fun missing(j: JdbcOperations, table: String, id: UUID): ProblemException {
        val exists = when (table) {
            "legal_entity" -> j.queryForObject("SELECT count(*) FROM taktovia.legal_entity WHERE id = ?", Int::class.java, id)
            else -> j.queryForObject("SELECT count(*) FROM taktovia.organization WHERE id = ?", Int::class.java, id)
        } == 1
        return if (exists) ProblemException(ErrorCode.REVISION_MISMATCH, "Die Ressource wurde zwischenzeitlich geändert; aktuelle Revision lesen.") else ProblemException.notFound()
    }

    /** Ein Rechtsträger eines anderen Mandanten ist als Verweis nicht verwendbar: für den Aufrufer wie „nicht vorhanden“ (404). */
    private fun requireLegalEntity(j: JdbcOperations, id: UUID) {
        if (j.queryForObject("SELECT count(*) FROM taktovia.legal_entity WHERE id = ?", Int::class.java, id) != 1) throw ProblemException.notFound()
    }

    private fun legalEntityFields(name: String, country: String, v: MutableList<Violation>) {
        text(name, "/name", v)
        if (!Regex("[A-Z]{2}").matches(country)) v += Violation("/country", ViolationCode.PATTERN_MISMATCH)
    }

    private fun text(s: String, pointer: String, v: MutableList<Violation>) {
        if (s.isEmpty() || s.length > 200) v += Violation(pointer, if (s.isEmpty()) ViolationCode.TOO_SHORT else ViolationCode.TOO_LONG)
    }

    private fun roles(roles: List<String>, v: MutableList<Violation>) {
        if (roles.isEmpty()) v += Violation("/roles", ViolationCode.TOO_FEW_ITEMS)
        if (roles.size > OrganizationRole.ALL.size) v += Violation("/roles", ViolationCode.TOO_MANY_ITEMS)
        roles.forEachIndexed { i, r -> if (!OrganizationRole.isValid(r)) v += Violation("/roles/$i", ViolationCode.NOT_IN_ENUM) }
        if (roles.toSet().size != roles.size) v += Violation("/roles", ViolationCode.NOT_UNIQUE)
    }

    private fun interval(from: Instant?, to: Instant?, v: MutableList<Violation>) {
        if (from != null && to != null && !to.isAfter(from)) v += Violation("/roles_valid_to", ViolationCode.INTERVAL_INVALID)
    }

    private fun throwIf(v: List<Violation>) { if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray()) }

    private fun arrayLiteral(items: List<String>) = items.joinToString(",", "{", "}") { "\"" + it + "\"" }   // nur geprüfte Katalogwerte

    private fun orgChanges(o: OrganizationView) = mapOf("name" to o.name, "federation_ref" to o.federationRef, "roles" to o.roles, "legal_entity_id" to o.legalEntityId.toString())

    private fun legalEntityPayload(change: String, e: LegalEntityView): Map<String, Any?> = linkedMapOf(
        "entity" to "legal_entity", "id" to e.id.toString(), "change" to change, "name" to e.name, "country" to e.country, "legal_entity_id" to null,
        "federation_ref" to null, "roles" to null, "roles_valid_from" to null, "roles_valid_to" to null, "revision" to e.revision,
    )

    private fun organizationPayload(change: String, o: OrganizationView): Map<String, Any?> = linkedMapOf(
        "entity" to "organization", "id" to o.id.toString(), "change" to change, "name" to o.name, "country" to null, "legal_entity_id" to o.legalEntityId.toString(),
        "federation_ref" to o.federationRef, "roles" to o.roles, "roles_valid_from" to o.rolesValidFrom.toString(), "roles_valid_to" to o.rolesValidTo?.toString(), "revision" to o.revision,
    )

    private companion object {
        const val LE_COLUMNS = "e.id, e.name, e.country, e.tax_reference_token, e.revision, e.created_at"
        val LE_MAPPER = RowMapper { rs, _ ->
            LegalEntityView(rs.getObject("id", UUID::class.java), rs.getString("name"), rs.getString("country"), rs.getString("tax_reference_token"), rs.getLong("revision"), rs.getTimestamp("created_at").toInstant())
        }
        const val ORG_COLUMNS = "o.id, o.legal_entity_id, o.name, o.federation_ref, o.roles, o.roles_valid_from, o.roles_valid_to, o.revision, o.created_at"
        val ORG_MAPPER = RowMapper { rs, _ ->
            OrganizationView(
                rs.getObject("id", UUID::class.java), rs.getObject("legal_entity_id", UUID::class.java), rs.getString("name"), rs.getString("federation_ref"),
                (rs.getArray("roles").array as Array<*>).map { it as String }, rs.getTimestamp("roles_valid_from").toInstant(), rs.getTimestamp("roles_valid_to")?.toInstant(),
                rs.getLong("revision"), rs.getTimestamp("created_at").toInstant(),
            )
        }
    }
}
