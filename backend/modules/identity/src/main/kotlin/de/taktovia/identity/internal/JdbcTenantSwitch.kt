package de.taktovia.identity.internal

import de.taktovia.identity.api.MembershipLookup
import de.taktovia.identity.api.SubjectRef
import de.taktovia.identity.api.SwitchOutcome
import de.taktovia.identity.api.TenantSwitch
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.audit.SecurityEvent
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.util.UUID

internal class JdbcTenantSwitch(
    private val tx: TenantTransactions, private val lookup: MembershipLookup, private val audit: AuditPort,
    private val security: SecurityEventPort, @Suppress("unused") private val clock: Clock,
) : TenantSwitch {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun switch(current: RequestContext, target: TenantId, service: UUID?): SwitchOutcome {
        val who = SubjectRef(current.actor.issuer, current.actor.subject)
        val m = lookup.activeIn(who, target)
        val denial = when {
            m == null -> "tenant_switch_no_membership"
            service != null && (m.serviceScope == null || service !in m.serviceScope) -> "tenant_switch_service_not_allowed"
            else -> null
        }
        if (denial != null) {
            reportDenied(current, denial)
            return if (m == null) SwitchOutcome.NoMembership else SwitchOutcome.ServiceNotAllowed
        }
        tx.run(target) { j -> recordSwitch(j, current, target, service) }
        return SwitchOutcome.Switched(m!!, service)
    }

    @AuditRequired
    private fun recordSwitch(j: JdbcOperations, current: RequestContext, target: TenantId, service: UUID?) {
        audit.record(j, AuditEntry(
            actor = auditActor(current), action = AuditActions.TENANT_SWITCHED, obj = ObjectRef("tenant", target.value),
            correlationId = current.correlationId, changes = buildMap { if (service != null) put("service_id", service) },
        ))
    }

    /** Sicherheitsereignis im Mandanten, in dem der Akteur gerade angemeldet ist; der Zielmandant bleibt unerwähnt (kein Aufzählen). */
    private fun reportDenied(current: RequestContext, reason: String) {
        val event = SecurityEvent(auditActor(current), AuditActions.SECURITY_ACCESS_DENIED, ObjectRef("tenant", current.tenant.value),
            current.correlationId, mapOf("reason" to reason))
        runCatching { tx.run(current.tenant) { j -> security.report(j, event) } }
            .onFailure { log.warn("Sicherheitsereignis nicht geschrieben ({})", it.javaClass.simpleName); security.reportWithoutTenant(event) }
    }
}
