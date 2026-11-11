package de.taktovia.servicegeo.internal

import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.authz.ObjectAuthorizer
import de.taktovia.platform.authz.ServiceScoped
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.servicegeo.api.OperatingSchedule
import de.taktovia.servicegeo.api.PublishServiceRevision
import de.taktovia.servicegeo.api.ServiceRevisionView
import de.taktovia.servicegeo.api.ServiceRevisions
import de.taktovia.platform.problem.DbErrorMapper
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

private const val COLS = ServiceRevisionRows.COLUMNS

/**
 * Dienstversionen (WP-M2-03). Die Änderung sperrt die Dienstzeile (`FOR UPDATE`, Scope-Sperre), prüft die erwartete Revision, schließt eine offene Vorgängerversion
 * und legt die neue Zeile an; den Rest sichert die Datenbank (Exclusion Constraint `service_revision_no_overlap`, UNIQUE `(service_id, revision)`).
 */
@ServiceScoped
internal class JdbcServiceRevisions(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock, private val authorizer: ObjectAuthorizer,
) : ServiceRevisions {

    override fun head(ctx: RequestContext, serviceId: UUID): ServiceRevisionView? = ctxTx.run(ctx, readOnly = true) { j ->
        readable(j, ctx, serviceId)
        headOf(j, serviceId)
    }

    override fun list(ctx: RequestContext, serviceId: UUID, limit: Int, before: Long?): List<ServiceRevisionView> = ctxTx.run(ctx, readOnly = true) { j ->
        readable(j, ctx, serviceId)
        j.query("SELECT $COLS FROM taktovia.service_revision r WHERE r.service_id = ? AND r.revision < ? ORDER BY r.revision DESC LIMIT ?",
            ServiceRevisionRows.VIEW, serviceId, before ?: Long.MAX_VALUE, limit + 1)
    }

    override fun effective(ctx: RequestContext, serviceId: UUID, at: Instant): ServiceRevisionView? = ctxTx.run(ctx, readOnly = true) { j ->
        readable(j, ctx, serviceId)
        effectiveOf(j, serviceId, at)
    }

    override fun publish(ctx: RequestContext, serviceId: UUID, expectedRevision: Long, cmd: PublishServiceRevision): ServiceRevisionView =
        ctxTx.run(ctx) { j -> doPublish(j, ctx, serviceId, expectedRevision, cmd) }

    @AuditRequired
    private fun doPublish(j: JdbcOperations, ctx: RequestContext, serviceId: UUID, expected: Long, cmd: PublishServiceRevision): ServiceRevisionView {
        val timezone = writable(j, ctx, serviceId)   // 404 vor 422; sperrt den Dienst
        val head = headOf(j, serviceId)
        if ((head?.revision ?: 0L) != expected) throw GeoSupport.revisionMismatch()
        val violations = mutableListOf<Violation>()
        if (!PRODUCT.matches(cmd.trafficProduct)) violations += Violation("/traffic_product", ViolationCode.PATTERN_MISMATCH)
        if (cmd.reason.isBlank()) violations += Violation("/reason", ViolationCode.REQUIRED) else if (cmd.reason.length > 500) violations += Violation("/reason", ViolationCode.TOO_LONG)
        if (cmd.validTo != null && !cmd.validTo.isAfter(cmd.validFrom)) violations += Violation("/valid_to", ViolationCode.INTERVAL_INVALID)
        val (schedule, scheduleViolations) = OperatingSchedule.parse(ZoneId.of(timezone), cmd.weeklyHours, cmd.exceptions)
        violations += scheduleViolations
        GeoSupport.throwIf(violations)
        checkNotNull(schedule)

        // Eine offene Vorgängerversion, die vor der neuen beginnt, endet mit deren Beginn; alles andere Überlappende weist die Datenbank ab (409).
        if (head != null && head.validTo == null && head.validFrom.isBefore(cmd.validFrom)) {
            j.update("UPDATE taktovia.service_revision SET valid_to = ? WHERE id = ? AND valid_to IS NULL", Timestamp.from(cmd.validFrom), head.id)
        }
        val revision = expected + 1
        val id = UUID.randomUUID()
        try {
            j.update("INSERT INTO taktovia.service_revision (tenant_id, id, service_id, revision, traffic_product, timezone, weekly_hours, exceptions, valid_from, valid_to, reason, created_by) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?)",
                id, serviceId, revision, cmd.trafficProduct, timezone, ServiceRevisionRows.write(cmd.weeklyHours), ServiceRevisionRows.write(cmd.exceptions ?: emptyList<Any>()),
                Timestamp.from(cmd.validFrom), cmd.validTo?.let { Timestamp.from(it) }, cmd.reason, GeoSupport.actor(ctx).value)
        } catch (_: DuplicateKeyException) { throw GeoSupport.revisionMismatch() }
        catch (e: DataAccessException) { throw DbErrorMapper.map(e) }   // Exclusion Constraint: überlappende Version, 409
        val created = j.query("SELECT $COLS FROM taktovia.service_revision r WHERE r.id = ?", ServiceRevisionRows.VIEW, id).single()
        audit.record(j, GeoSupport.entry(ctx, AuditActions.GEO_SERVICE_REVISION_PUBLISHED, "service", serviceId, revision, "published", linkedMapOf(
            "traffic_product" to created.trafficProduct, "valid_from" to created.validFrom.toString(), "valid_to" to created.validTo?.toString(), "reason" to created.reason)))
        outbox.append(j, OutboxEvent(serviceId, revision, "service.revision_published", linkedMapOf(
            "service_id" to serviceId.toString(), "revision" to revision, "traffic_product" to created.trafficProduct, "timezone" to created.timezone,
            "valid_from" to created.validFrom.toString(), "valid_to" to created.validTo?.toString()), clock.instant(), correlationId = ctx.correlationId))
        return created
    }

    /** Lesender Zugriff auf den Dienst: 404 für fremde, unbekannte und (ohne Verwaltungsscope) nicht im Dienstscope liegende Dienste. */
    private fun readable(j: JdbcOperations, ctx: RequestContext, serviceId: UUID): String {
        val zone = GeoSupport.loadService(j, serviceId, lock = false)
        if (GeoSupport.needsServiceScope(ctx)) authorizer.requireRead(j, ctx, GeoSupport.scoped(ctx, serviceId))
        return zone
    }

    /** Schreibender Zugriff: sperrt die Dienstzeile (Scope-Sperre) und prüft die Dienstberechtigung (`FOR SHARE` auf die Mitgliedschaft). */
    private fun writable(j: JdbcOperations, ctx: RequestContext, serviceId: UUID): String {
        val zone = GeoSupport.loadService(j, serviceId, lock = true)
        if (GeoSupport.needsServiceScope(ctx)) authorizer.requireWrite(j, ctx, GeoSupport.scoped(ctx, serviceId))
        return zone
    }

    private fun headOf(j: JdbcOperations, serviceId: UUID): ServiceRevisionView? =
        j.query("SELECT $COLS FROM taktovia.service_revision r WHERE r.service_id = ? ORDER BY r.revision DESC LIMIT 1", ServiceRevisionRows.VIEW, serviceId).singleOrNull()

    private companion object { val PRODUCT = Regex("^[A-Z][A-Z0-9_]{1,39}$") }
}

/** Wirksame Version zum Zeitpunkt [at]: `valid_from <= at < valid_to` (links geschlossen, rechts offen). Höchstens eine, dafür sorgt der Exclusion Constraint. */
internal fun effectiveOf(j: JdbcOperations, serviceId: UUID, at: Instant): ServiceRevisionView? =
    j.query("SELECT $COLS FROM taktovia.service_revision r WHERE r.service_id = ? AND r.valid_from <= ? AND (r.valid_to IS NULL OR ? < r.valid_to) ORDER BY r.revision DESC LIMIT 1",
        ServiceRevisionRows.VIEW, serviceId, Timestamp.from(at), Timestamp.from(at)).singleOrNull()
