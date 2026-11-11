package de.taktovia.identity.internal

import de.taktovia.identity.api.CreatedTenant
import de.taktovia.identity.api.GrantMembership
import de.taktovia.identity.api.NewTenant
import de.taktovia.identity.api.PlatformActor
import de.taktovia.identity.api.PlatformAdmin
import de.taktovia.identity.api.TenantStatusChange
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.statemachine.StateMachines
import de.taktovia.platform.statemachine.StateTransitioner
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.util.UUID

/**
 * Plattformverwaltung (WP-M1-05). Die Anlage eines Mandanten läuft in einer Transaktion, deren Tenantkontext die **neue** Mandanten-ID ist
 * (Policy `own_tenant`, spaltenweises INSERT-Recht); es gibt weder Definer-Funktion noch Leserecht auf fremde Mandanten. Betreiberkonten
 * stehen in `platform_operator_account` und werden nur mit dem Subject-Schlüssel des geprüften Tokens gelesen (kein Aufzählen).
 * Betreiberkonten haben keine Mitgliedschaft; ihre Rolle verleiht keine Fachdatensicht (Kap. 02 §4.3).
 */
internal class JdbcPlatformAdmin(
    private val tx: TenantTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock,
    private val memberships: JdbcMembershipAdmin,
) : PlatformAdmin {
    private val transitioner = StateTransitioner(outbox, clock)

    override fun isOperator(issuer: String, subject: String): Boolean = tx.runForSubject(issuer, subject) { j ->
        j.queryForObject("SELECT count(*) FROM taktovia.platform_operator_account WHERE issuer = ? AND subject = ? AND revoked_at IS NULL", Int::class.java, issuer, subject) == 1
    }

    private fun requireOperator(actor: PlatformActor) {
        // Betreiberkonten sind privilegiert: zweiter Faktor Pflicht. Ohne Konto oder Faktor immer dieselbe Antwort 403.
        if (!actor.multiFactor || !isOperator(actor.issuer, actor.subject)) throw ProblemException(ErrorCode.FORBIDDEN)
    }

    override fun createTenant(actor: PlatformActor, cmd: NewTenant, seed: (JdbcOperations, TenantId) -> Unit): CreatedTenant {
        requireOperator(actor)
        validate(cmd)
        val tenant = TenantId(UUID.randomUUID())
        return tx.run(tenant) { j -> doCreate(j, actor, tenant, cmd, seed) }
    }

    @AuditRequired
    private fun doCreate(j: JdbcOperations, actor: PlatformActor, tenant: TenantId, cmd: NewTenant, seed: (JdbcOperations, TenantId) -> Unit): CreatedTenant {
        val who = ActorRef("user:" + actor.subject.take(100))
        j.update("INSERT INTO taktovia.tenant (id, name, status) VALUES (?, ?, 'ACTIVE')", tenant.value, cmd.name)
        val legalEntity = UUID.randomUUID()
        j.update("INSERT INTO taktovia.legal_entity (tenant_id, id, name, country, tax_reference_token, created_by) VALUES (?, ?, ?, ?, ?, ?)",
            tenant.value, legalEntity, cmd.legalEntityName, cmd.legalEntityCountry, cmd.taxReferenceToken, who.value)
        audit.record(j, AuditEntry(who, AuditActions.TENANT_CREATED, ObjectRef("tenant", tenant.value), actor.correlationId,
            mapOf("name" to cmd.name, "legal_entity_id" to legalEntity.toString(), "status" to "ACTIVE")))
        val admin = memberships.grantIn(j, who, actor.correlationId, GrantMembership(cmd.firstAdminIssuer, cmd.firstAdminSubject, "tenant_admin", null))
        seed(j, tenant)
        return CreatedTenant(tenant.value, cmd.name, "ACTIVE", legalEntity, admin.id)
    }

    override fun changeTenantStatus(actor: PlatformActor, tenant: TenantId, to: String): TenantStatusChange {
        requireOperator(actor)
        if (StateMachines.TENANT.parse(to) == null) throw ProblemException.invalid(Violation("/status", ViolationCode.NOT_IN_ENUM))
        return tx.run(tenant) { j -> doChangeStatus(j, actor, tenant, to) }
    }

    @AuditRequired
    private fun doChangeStatus(j: JdbcOperations, actor: PlatformActor, tenant: TenantId, to: String): TenantStatusChange {
        // Mandant unbekannt: kein Treffer im Kontext dieser ID -> 404; unzulässiger Übergang -> 409 (Automat `tenant`, auch in der Datenbank erzwungen)
        val t = transitioner.transition(j, StateMachines.TENANT, tenant.value, to, null, actor.correlationId)
        audit.record(j, AuditEntry(ActorRef("user:" + actor.subject.take(100)), AuditActions.TENANT_STATUS_CHANGED, ObjectRef("tenant", tenant.value), actor.correlationId,
            mapOf("from" to t.from.name, "to" to t.to.name)))
        return TenantStatusChange(tenant.value, t.from.name, t.to.name)
    }

    private fun validate(c: NewTenant) {
        val v = mutableListOf<Violation>()
        if (c.name.isEmpty() || c.name.length > 200) v += Violation("/name", ViolationCode.INVALID_VALUE)
        if (c.legalEntityName.isEmpty() || c.legalEntityName.length > 200) v += Violation("/legal_entity/name", ViolationCode.INVALID_VALUE)
        if (!Regex("[A-Z]{2}").matches(c.legalEntityCountry)) v += Violation("/legal_entity/country", ViolationCode.PATTERN_MISMATCH)
        if (c.firstAdminIssuer.isEmpty() || c.firstAdminIssuer.length > 512) v += Violation("/first_admin/issuer", ViolationCode.INVALID_VALUE)
        if (c.firstAdminSubject.isEmpty() || c.firstAdminSubject.length > 200) v += Violation("/first_admin/subject", ViolationCode.INVALID_VALUE)
        if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray())
    }
}
