package de.taktovia.config.internal

import de.taktovia.config.api.CompanyProfileInput
import de.taktovia.config.api.CompanyProfileService
import de.taktovia.config.api.CompanyProfileView
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
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
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

/**
 * Unternehmensprofil als versionierte Konfiguration (PA-11): je Änderung eine neue Zeile in `config_revision` mit `scope_kind = TENANT_PROFILE`
 * und `scope_id` = Mandant, Revision fortlaufend ab 1. Die Revision ist zugleich der ETag. Zwei gleichzeitige Änderungen derselben Revision:
 * die Eindeutigkeit `(tenant_id, scope_kind, scope_id, revision)` lässt genau eine durch, die andere erhält 412. Frühere Revisionen bleiben erhalten
 * (Nachvollziehbarkeit). Das Profil wird vom Mandantenadministrator selbst veröffentlicht (kein Vier-Augen-Prinzip für Profilfelder; `approved_by` = Urheber).
 */
internal class JdbcCompanyProfileService(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock,
) : CompanyProfileService {
    private val mapper = JsonMapper.builder().build()

    private class Stored(val revision: Long, val input: CompanyProfileInput)

    override fun get(ctx: RequestContext): CompanyProfileView = ctxTx.run(ctx, readOnly = true) { j ->
        current(j)?.let { CompanyProfileView(it.input, it.revision) } ?: throw ProblemException.notFound()
    }

    override fun update(ctx: RequestContext, expectedRevision: Long, input: CompanyProfileInput): CompanyProfileView {
        validateFields(input)
        try {
            return ctxTx.run(ctx) { j -> doUpdate(j, actor(ctx), ctx.correlationId, expectedRevision, input) }
        } catch (e: DuplicateKeyException) {
            // Eine gleichzeitige Änderung derselben Revision hat gewonnen.
            throw stale(e)
        }
    }

    override fun seed(jdbc: JdbcOperations, actor: ActorRef, correlationId: UUID, input: CompanyProfileInput): CompanyProfileView {
        validateFields(input)
        return doUpdate(jdbc, actor, correlationId, 0, input)
    }

    @AuditRequired
    private fun doUpdate(j: JdbcOperations, actor: ActorRef, correlationId: UUID, expected: Long, input: CompanyProfileInput): CompanyProfileView {
        val cur = current(j)
        val currentRevision = cur?.revision ?: 0L
        if (cur == null && expected != 0L) throw ProblemException.notFound()
        if (expected != currentRevision) throw stale(null)
        if (cur != null && cur.input == input) return CompanyProfileView(cur.input, cur.revision)   // ohne Wirkung: keine Revision, kein Audit, kein Ereignis
        // Standardwährung gegen den Katalog (CFG-001): unbekannt ist ein Feldfehler
        if (j.queryForObject("SELECT count(*) FROM taktovia.currency WHERE code = ?", Int::class.java, input.defaultCurrency) != 1)
            throw ProblemException.invalid(Violation("/default_currency", ViolationCode.CURRENCY_UNKNOWN))
        val tenant = j.queryForObject("SELECT taktovia.current_tenant()", UUID::class.java)!!
        val revision = currentRevision + 1
        j.update(
            "INSERT INTO taktovia.config_revision (tenant_id, id, scope_kind, scope_id, revision, schema_version, values_json, effective_from, created_by, approved_by, published_at) " +
                "VALUES (taktovia.current_tenant(), ?, ?, taktovia.current_tenant(), ?, ?, ?::jsonb, clock_timestamp(), ?, ?, clock_timestamp())",
            UUID.randomUUID(), SCOPE_KIND, revision, SCHEMA_VERSION, mapper.writeValueAsString(values(input)), actor.value, actor.value,
        )
        val changed = changedFields(cur?.input, input)
        audit.record(j, AuditEntry(
            actor = actor, action = AuditActions.TENANT_PROFILE_CHANGED, obj = ObjectRef("tenant", tenant), correlationId = correlationId,
            changes = mapOf("changed_fields" to changed, "default_currency" to input.defaultCurrency, "revision" to revision),
        ))
        // Das Ereignis nennt nur, welche Felder sich geändert haben (keine Personendaten), und die Standardwährung.
        outbox.append(j, OutboxEvent(tenant, revision, "tenant.profile_changed",
            mapOf("revision" to revision, "changed_fields" to changed, "default_currency" to input.defaultCurrency), clock.instant(), correlationId = correlationId))
        return CompanyProfileView(input, revision)
    }

    private fun current(j: JdbcOperations): Stored? = j.query(
        "SELECT revision, values_json::text FROM taktovia.config_revision WHERE scope_kind = ? AND scope_id = taktovia.current_tenant() ORDER BY revision DESC LIMIT 1",
        { rs, _ -> Stored(rs.getLong(1), parse(rs.getString(2))) }, SCOPE_KIND,
    ).firstOrNull()

    private fun stale(cause: Throwable?) = ProblemException(ErrorCode.REVISION_MISMATCH, "Das Profil wurde zwischenzeitlich geändert; aktuelle Revision lesen.", cause = cause)

    private fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    private fun values(i: CompanyProfileInput): Map<String, Any?> = linkedMapOf<String, Any?>(
        "legal_name" to i.legalName, "contact_email" to i.contactEmail, "default_currency" to i.defaultCurrency,
        "address" to i.address, "contact_phone" to i.contactPhone, "billing_address" to i.billingAddress, "billing_email" to i.billingEmail,
        "operating_mode" to i.operatingMode, "legal_notice" to i.legalNotice,
    ).filterValues { it != null }   // optionale Felder ohne Wert werden nicht gespeichert (Schema: additionalProperties false, keine null-Werte)

    private fun parse(json: String): CompanyProfileInput {
        val n = mapper.readTree(json)
        fun s(k: String) = n.get(k)?.takeIf { !it.isNull }?.asString()
        return CompanyProfileInput(s("legal_name")!!, s("contact_email")!!, s("default_currency")!!, s("address"), s("contact_phone"), s("billing_address"), s("billing_email"), s("operating_mode"), s("legal_notice"))
    }

    private fun changedFields(old: CompanyProfileInput?, new: CompanyProfileInput): List<String> {
        val a = old?.let(::values) ?: emptyMap(); val b = values(new)
        return (a.keys + b.keys).filter { a[it] != b[it] }.sorted()
    }

    private fun validateFields(i: CompanyProfileInput) {
        val v = mutableListOf<Violation>()
        fun len(s: String?, pointer: String, min: Int, max: Int) {
            if (s != null && (s.length < min || s.length > max)) v += Violation(pointer, if (s.length < min) ViolationCode.TOO_SHORT else ViolationCode.TOO_LONG)
        }
        len(i.legalName, "/legal_name", 1, 200); len(i.address, "/address", 1, 500); len(i.contactPhone, "/contact_phone", 3, 40)
        len(i.billingAddress, "/billing_address", 1, 500); len(i.legalNotice, "/legal_notice", 1, 4000)
        if (!EMAIL.matches(i.contactEmail) || i.contactEmail.length > 254) v += Violation("/contact_email", ViolationCode.INVALID_FORMAT)
        if (i.billingEmail != null && (!EMAIL.matches(i.billingEmail) || i.billingEmail.length > 254)) v += Violation("/billing_email", ViolationCode.INVALID_FORMAT)
        if (!Regex("[A-Z]{3}").matches(i.defaultCurrency)) v += Violation("/default_currency", ViolationCode.CURRENCY_UNKNOWN)
        if (i.operatingMode != null && !Regex("[A-Z][A-Z0-9_]{1,63}").matches(i.operatingMode)) v += Violation("/operating_mode", ViolationCode.PATTERN_MISMATCH)
        if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray())
    }

    private companion object {
        const val SCOPE_KIND = "TENANT_PROFILE"
        const val SCHEMA_VERSION = "tenant-profile.v1"
        val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
    }
}
