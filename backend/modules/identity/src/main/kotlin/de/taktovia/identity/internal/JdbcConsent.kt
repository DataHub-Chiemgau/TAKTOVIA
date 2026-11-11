package de.taktovia.identity.internal

import de.taktovia.identity.api.ConsentPurpose
import de.taktovia.identity.api.ConsentRevisionView
import de.taktovia.identity.api.ConsentService
import de.taktovia.identity.api.ConsentState
import de.taktovia.identity.api.DeliveryPolicy
import de.taktovia.identity.api.PassengerSettings
import de.taktovia.identity.api.PrivacySettings
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.util.UUID

/**
 * Einwilligungsrevisionen je Zweck und der Zustellport (WP-M1-30, LH-6.2-IAM-011). Append-only: eine Änderung legt eine neue Revision an (Folgenummer je Fahrgast
 * und Zweck, unter der Zeilensperre des Fahrgasts vergeben, damit parallele Änderungen eine eindeutige Reihenfolge erhalten). `OPERATIONAL_SAFETY` ist nie
 * abwählbar und hängt nicht von Marketing ab; `MARKETING` gilt nur mit jüngster Revision `granted = true`, ohne Revision als nicht eingewilligt.
 */
internal class JdbcConsent(
    private val tx: TenantTransactions, private val ctxTx: ContextTransactions, private val audit: AuditPort, private val settings: PassengerSettings, private val clock: Clock,
) : ConsentService, DeliveryPolicy {

    override fun settings(ctx: RequestContext): PrivacySettings = ctxTx.run(ctx, readOnly = true) { j ->
        val pid = own(j, ctx)
        val latest = j.query("SELECT DISTINCT ON (purpose) purpose, granted, recorded_at, text_version FROM taktovia.consent_revision WHERE passenger_id = ? ORDER BY purpose, sequence DESC",
            { rs, _ -> ConsentState(ConsentPurpose.valueOf(rs.getString(1)), rs.getBoolean(2), false, rs.getTimestamp(3).toInstant(), rs.getString(4)) }, pid).associateBy { it.purpose }
        val states = listOf(
            // betrieblich/sicherheitsrelevant: immer zugestellt, nicht abwählbar; Marketing: Standard nicht eingewilligt
            ConsentState(ConsentPurpose.OPERATIONAL_SAFETY, true, false, null, null),
            latest[ConsentPurpose.MARKETING]?.let { ConsentState(it.purpose, it.granted, true, it.recordedAt, it.textVersion) } ?: ConsentState(ConsentPurpose.MARKETING, false, true, null, null),
        )
        val history = j.query("SELECT id, purpose, sequence, granted, recorded_at, text_version FROM taktovia.consent_revision WHERE passenger_id = ? ORDER BY recorded_at DESC, sequence DESC LIMIT 50",
            { rs, _ -> revision(rs) }, pid)
        PrivacySettings(settings.consentTextVersion, states, history)
    }

    override fun record(ctx: RequestContext, purpose: ConsentPurpose, granted: Boolean, textVersion: String): ConsentRevisionView {
        if (purpose != ConsentPurpose.MARKETING) throw ProblemException.invalid(Violation("/purpose", ViolationCode.INVALID_VALUE))
        if (textVersion != settings.consentTextVersion) throw ProblemException.invalid(Violation("/text_version", ViolationCode.INVALID_VALUE))
        return ctxTx.run(ctx) { j -> append(j, ctx, purpose, granted, textVersion) }
    }

    @AuditRequired
    private fun append(j: JdbcOperations, ctx: RequestContext, purpose: ConsentPurpose, granted: Boolean, textVersion: String): ConsentRevisionView {
        val pid = own(j, ctx)
        // Zeilensperre des Fahrgasts: serialisiert die Vergabe der Folgenummer
        j.queryForObject("SELECT id FROM taktovia.passenger_private WHERE id = ? FOR UPDATE", UUID::class.java, pid)
        val next = (j.queryForObject("SELECT max(sequence) FROM taktovia.consent_revision WHERE passenger_id = ? AND purpose = ?", Long::class.javaObjectType, pid, purpose.name) ?: 0L) + 1
        val v = j.query("INSERT INTO taktovia.consent_revision (tenant_id, id, passenger_id, purpose, sequence, granted, text_version) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?) " +
            "RETURNING id, purpose, sequence, granted, recorded_at, text_version", { rs, _ -> revision(rs) }, UUID.randomUUID(), pid, purpose.name, next, granted, textVersion).single()
        audit.record(j, AuditEntry(ActorRef("user:${ctx.actor.subject}"), AuditActions.CONSENT_RECORDED, ObjectRef("passenger", pid), ctx.correlationId,
            mapOf("purpose" to purpose.name, "granted" to granted, "sequence" to next, "text_version" to textVersion)))
        return v
    }

    override fun isDeliveryAllowed(tenant: TenantId, passengerId: UUID, purpose: ConsentPurpose): Boolean = when (purpose) {
        ConsentPurpose.OPERATIONAL_SAFETY -> true
        ConsentPurpose.MARKETING -> tx.run(tenant, readOnly = true) { j ->
            j.query("SELECT granted FROM taktovia.consent_revision WHERE passenger_id = ? AND purpose = 'MARKETING' ORDER BY sequence DESC LIMIT 1", { rs, _ -> rs.getBoolean(1) }, passengerId).firstOrNull() ?: false
        }
    }

    private fun own(j: JdbcOperations, ctx: RequestContext): UUID =
        j.query("SELECT id FROM taktovia.passenger_private WHERE idp_issuer = ? AND idp_subject = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, ctx.actor.issuer, ctx.actor.subject).firstOrNull() ?: throw ProblemException.notFound()

    private fun revision(rs: java.sql.ResultSet) = ConsentRevisionView(rs.getObject(1, UUID::class.java), ConsentPurpose.valueOf(rs.getString(2)), rs.getLong(3), rs.getBoolean(4), rs.getTimestamp(5).toInstant(), rs.getString(6))
}
