package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.AckCause
import de.taktovia.fleetsafety.api.AlertAcknowledgementView
import de.taktovia.fleetsafety.api.AlertKinds
import de.taktovia.fleetsafety.api.AlertPolicyView
import de.taktovia.fleetsafety.api.AlertRule
import de.taktovia.fleetsafety.api.AlertSettings
import de.taktovia.fleetsafety.api.AlertState
import de.taktovia.fleetsafety.api.AlertView
import de.taktovia.fleetsafety.api.Alerts
import de.taktovia.platform.alert.AlertPriority
import de.taktovia.platform.alert.BusinessAlertPort
import de.taktovia.platform.alert.BusinessAlertRequest
import de.taktovia.platform.alert.BusinessAlertResult
import de.taktovia.platform.api.Etag
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.authz.ScopedObject
import de.taktovia.platform.authz.ServiceScopeAuthorizer
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.Purpose
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.tenant.TenantId
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Alarm-Grundlage auf PostgreSQL (WP-M3-52). Ereignisbasierte Alarme werden über den Unique-Index `(Art, Quellereignis, Empfänger)` dedupliziert (jeder Zustand, auch bei hundert
 * parallelen Zustellungen: `INSERT … ON CONFLICT DO NOTHING` wartet auf einen parallelen Einfüger und liest danach den Gewinner), bedingungsbasierte über den Index offener Schlüssel.
 * Die Quittierung sperrt die Alarmzeile (`FOR UPDATE`) und prüft `If-Match`: bei zwei gleichzeitigen Quittierungen wirkt genau eine (die zweite sieht die neue Revision: 412). Die Eskalation ist
 * ein einziges bedingtes UPDATE (nach dem Zeilenlock gilt die Bedingung neu): zwei parallele Läufe eskalieren einen Alarm nie zweimal.
 */
internal class JdbcAlerts(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock, private val settings: AlertSettings,
) : Alerts {
    private val json = JsonMapper.builder().build()
    private class Policy(val id: UUID, val revisionNo: Int, val rules: List<AlertRule>)

    override val port: BusinessAlertPort = object : BusinessAlertPort {
        override fun raise(jdbc: JdbcOperations, request: BusinessAlertRequest): BusinessAlertResult = doRaise(jdbc, request)
        override fun resolve(jdbc: JdbcOperations, dedupeKey: String): Int = doResolve(jdbc, dedupeKey)
    }

    // ---------------- Auslösen (in der Transaktion des Fachmoduls) ----------------

    private fun doRaise(j: JdbcOperations, req: BusinessAlertRequest): BusinessAlertResult {
        val now = clock.instant()
        val policy = activePolicy(j)
        val rule = policy.rules.firstOrNull { it.kind == req.kind } ?: policy.rules.firstOrNull { it.kind == "*" }
            ?: if (req.kind.startsWith(AlertKinds.TECH_PREFIX)) AlertRule("*", AlertPriority.HIGH, "dispatcher") else AlertKinds.FALLBACK
        val recipient = req.recipientRole ?: rule.recipientRole
        val priority = rule.priority
        val critical = priority == AlertPriority.CRITICAL
        val dedupeKey = req.dedupeKey ?: "${req.kind}|${req.sourceEventId}|$recipient".take(200)
        val level = if (critical) 1 else 0   // kritische Alarme umgehen die Eskalationsverzögerung: sie entstehen bereits eskaliert
        val escalateAt = if (level < settings.maxEscalationLevel) now.plus(settings.escalationDelay) else null   // weitere Stufen laufen ab der Eskalation, auch bei kritischen Alarmen
        val id = UUID.randomUUID()
        val n = j.update(
            "INSERT INTO taktovia.business_alert(tenant_id, id, alert_kind, priority, vehicle_id, dedupe_key, state, policy_revision_id, revision, created_by, created_at, updated_at, service_id, source_event_id, " +
                "recipient_role, escalation_level, escalate_at, escalated_at, reason_code, ref_type, ref_id) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, 'OPEN', ?, 1, 'system:alert-service', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            id, req.kind, priority.name, req.vehicleId, dedupeKey, policy.id, Timestamp.from(now), Timestamp.from(now), req.serviceId, req.sourceEventId, recipient, level,
            escalateAt?.let(Timestamp::from), if (critical) Timestamp.from(now) else null, req.reasonCode, req.refType, req.refId)
        if (n == 0) {
            val existing = if (req.sourceEventId != null)
                j.query("SELECT id, priority, escalation_level, (SELECT revision_no FROM taktovia.alert_policy_revision p WHERE p.id = a.policy_revision_id) FROM taktovia.business_alert a " +
                    "WHERE alert_kind = ? AND source_event_id = ? AND recipient_role = ?", { rs, _ -> arrayOf<Any?>(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getInt(3), rs.getObject(4)) }, req.kind, req.sourceEventId, recipient)
            else
                j.query("SELECT id, priority, escalation_level, (SELECT revision_no FROM taktovia.alert_policy_revision p WHERE p.id = a.policy_revision_id) FROM taktovia.business_alert a " +
                    "WHERE dedupe_key = ? AND state = 'OPEN'", { rs, _ -> arrayOf<Any?>(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getInt(3), rs.getObject(4)) }, dedupeKey)
            val e = existing.first()
            return BusinessAlertResult(e[0] as UUID, false, AlertPriority.valueOf(e[1] as String), (e[3] as Number?)?.toInt() ?: policy.revisionNo, e[2] as Int)
        }
        outbox.append(j, OutboxEvent(id, 1, "alert.raised", linkedMapOf(
            "alert_id" to id.toString(), "alert_kind" to req.kind, "priority" to priority.name, "vehicle_id" to req.vehicleId?.toString(), "dedupe_key" to dedupeKey,
            "service_id" to req.serviceId?.toString(), "recipient_role" to recipient, "escalation_level" to level, "policy_revision_no" to policy.revisionNo), now))
        return BusinessAlertResult(id, true, priority, policy.revisionNo, level)
    }

    private fun doResolve(j: JdbcOperations, dedupeKey: String): Int {
        val now = clock.instant()
        val rows = j.query("UPDATE taktovia.business_alert SET state = 'RESOLVED', resolved_at = ?, escalate_at = NULL, revision = revision + 1, updated_at = ? WHERE dedupe_key = ? AND state IN ('OPEN', 'ACKNOWLEDGED') AND source_event_id IS NULL " +
            "RETURNING id, revision", { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getLong(2) }, Timestamp.from(now), Timestamp.from(now), dedupeKey)
        rows.forEach { (id, rev) -> outbox.append(j, OutboxEvent(id, rev, "alert.resolved", linkedMapOf("alert_id" to id.toString(), "state" to "RESOLVED"), now)) }
        return rows.size
    }

    // ---------------- Richtlinie ----------------

    private fun policyOf(id: UUID, no: Int, rulesJson: String): Policy {
        val rules = json.readTree(rulesJson).let { root -> if (root.isArray) root else root.get("rules") }
        return Policy(id, no, (0 until (rules?.size() ?: 0)).map { i -> rules!!.get(i).let { AlertRule(it.get("kind").asString(), AlertPriority.valueOf(it.get("priority").asString()), it.get("recipient_role").asString()) } })
    }

    private fun activePolicy(j: JdbcOperations): Policy {
        fun latest() = j.query("SELECT id, revision_no, rules::text FROM taktovia.alert_policy_revision ORDER BY revision_no DESC LIMIT 1", { rs, _ -> policyOf(rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getString(3)) }).firstOrNull()
        latest()?.let { return it }
        // erste Richtlinie des Mandanten: Standardregeln als Revision 1 (parallele Erstanlage: der Unique-Index auf (Mandant, Revisionsnummer) lässt genau eine zu)
        j.update("INSERT INTO taktovia.alert_policy_revision(tenant_id, id, revision_no, rules, created_by, created_at) VALUES (taktovia.current_tenant(), ?, 1, ?::jsonb, 'system:alert-default', ?) ON CONFLICT DO NOTHING",
            UUID.randomUUID(), rulesJson(AlertKinds.DEFAULTS), Timestamp.from(clock.instant()))
        return latest()!!
    }

    private fun rulesJson(rules: List<AlertRule>): String = json.writeValueAsString(mapOf("rules" to rules.map { linkedMapOf("kind" to it.kind, "priority" to it.priority.name, "recipient_role" to it.recipientRole) }))

    override fun currentPolicy(ctx: RequestContext): AlertPolicyView? {
        guardHuman(ctx, "operator:read")
        return ctxTx.run(ctx, readOnly = true) { j ->
            j.query("SELECT id, revision_no, rules::text, created_at FROM taktovia.alert_policy_revision ORDER BY revision_no DESC LIMIT 1", { rs, _ ->
                val p = policyOf(rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getString(3)); AlertPolicyView(p.id, p.revisionNo, p.rules, rs.getTimestamp(4).toInstant())
            }).firstOrNull()
        }
    }

    override fun publishPolicy(ctx: RequestContext, rules: List<AlertRule>): AlertPolicyView {
        guardHuman(ctx, "operator:write")
        if (ctx.roles.none { it in POLICY_ROLES }) throw ProblemException(ErrorCode.FORBIDDEN)
        if (rules.isEmpty() || rules.size > 50 || rules.map { it.kind }.toSet().size != rules.size) throw ProblemException.invalid(Violation("/rules", ViolationCode.INVALID_VALUE))
        rules.forEachIndexed { i, r ->
            if (!(r.kind == "*" || Regex("^[A-Z][A-Z0-9_]{1,59}$").matches(r.kind))) throw ProblemException.invalid(Violation("/rules/$i/kind", ViolationCode.INVALID_FORMAT))
            if (r.recipientRole !in RECIPIENT_ROLES) throw ProblemException.invalid(Violation("/rules/$i/recipient_role", ViolationCode.NOT_IN_ENUM))
        }
        return ctxTx.run(ctx) { j -> doPublish(j, ctx, rules) }
    }

    @AuditRequired
    private fun doPublish(j: JdbcOperations, ctx: RequestContext, rules: List<AlertRule>): AlertPolicyView {
        val now = clock.instant()
        j.query("SELECT pg_advisory_xact_lock(hashtextextended('alert_policy|' || taktovia.current_tenant()::text, 0))", { _, _ -> 0 })
        val no = j.queryForObject("SELECT coalesce(max(revision_no), 0) + 1 FROM taktovia.alert_policy_revision", Int::class.java)!!
        val id = UUID.randomUUID()
        j.update("INSERT INTO taktovia.alert_policy_revision(tenant_id, id, revision_no, rules, created_by, created_at) VALUES (taktovia.current_tenant(), ?, ?, ?::jsonb, ?, ?)",
            id, no, rulesJson(rules), "user:" + ctx.actor.subject, Timestamp.from(now))
        audit.record(j, AuditEntry(ActorRef("user:" + ctx.actor.subject), AuditActions.ALERT_POLICY_PUBLISHED, ObjectRef("alert_policy_revision", id), ctx.correlationId, mapOf("revision_no" to no, "rule_count" to rules.size), now))
        return AlertPolicyView(id, no, rules, now)
    }

    // ---------------- Arbeitsliste ----------------

    private val mapper = RowMapper { rs, _ ->
        AlertView(rs.getObject(1, UUID::class.java), rs.getString(2), AlertPriority.valueOf(rs.getString(3)), AlertState.valueOf(rs.getString(4)), rs.getObject(5, UUID::class.java), rs.getObject(6, UUID::class.java),
            rs.getString(7), rs.getObject(8) as Int?, rs.getInt(9), rs.getTimestamp(10)?.toInstant(), rs.getTimestamp(11)?.toInstant(), rs.getString(12), rs.getString(13), rs.getObject(14, UUID::class.java),
            rs.getTimestamp(15).toInstant(), rs.getTimestamp(16).toInstant(), rs.getTimestamp(17)?.toInstant(), rs.getLong(18),
            rs.getString(19)?.let { AlertAcknowledgementView(it, rs.getTimestamp(20).toInstant(), rs.getString(21)?.let { c -> AckCause.valueOf(c) }, rs.getString(22)) })
    }

    override fun list(ctx: RequestContext, state: AlertState?, limit: Int, after: Pair<Instant, UUID>?): List<AlertView> {
        guardHuman(ctx, "operator:read")
        val services = ServiceScopeAuthorizer.effectiveServices(ctx, null, tenantWide = false).orEmpty().joinToString(",", "{", "}")   // nur UUIDs
        val st = (state ?: AlertState.OPEN).name
        val ts = after?.first?.let(Timestamp::from)
        return ctxTx.run(ctx, readOnly = true) { j ->
            j.query("SELECT $COLUMNS $FROM WHERE a.state = ? AND (a.service_id IS NULL OR a.service_id = ANY(CAST(? AS uuid[]))) AND (CAST(? AS timestamptz) IS NULL OR (a.created_at, a.id) > (CAST(? AS timestamptz), CAST(? AS uuid))) " +
                "ORDER BY a.created_at, a.id LIMIT ?", mapper, st, services, ts, ts, after?.second, limit + 1)
        }
    }

    override fun get(ctx: RequestContext, id: UUID): AlertView {
        guardHuman(ctx, "operator:read")
        return ctxTx.run(ctx, readOnly = true) { j -> load(j, ctx, id, lock = false) }
    }

    /** Alarm lesen; fremder Mandant (RLS), fremder Dienst und unbekannt sind gleichermaßen 404. */
    private fun load(j: JdbcOperations, ctx: RequestContext, id: UUID, lock: Boolean): AlertView {
        val v = (if (lock) j.query("SELECT $COLUMNS $FROM WHERE a.id = ? FOR UPDATE OF a", mapper, id) else j.query("SELECT $COLUMNS $FROM WHERE a.id = ?", mapper, id)).firstOrNull() ?: throw ProblemException.notFound()
        val ok = if (lock) ServiceScopeAuthorizer.canWrite(j, ctx, ScopedObject("business_alert", id, ctx.tenant, v.serviceId), Purpose.OPERATIONS)
        else ServiceScopeAuthorizer.canRead(j, ctx, ScopedObject("business_alert", id, ctx.tenant, v.serviceId), Purpose.OPERATIONS)
        if (!ok) throw ProblemException.notFound()
        return v
    }

    // ---------------- Quittierung ----------------

    override fun acknowledge(ctx: RequestContext, id: UUID, ifMatch: Long, cause: AckCause, note: String?): AlertView {
        guardHuman(ctx, "operator:write")
        val n = note?.trim()?.takeIf { it.isNotEmpty() }
        if (n != null && n.length > 500) throw ProblemException.invalid(Violation("/note", ViolationCode.TOO_LONG))
        return ctxTx.run(ctx) { j -> doAcknowledge(j, ctx, id, ifMatch, cause, n) }
    }

    @AuditRequired
    private fun doAcknowledge(j: JdbcOperations, ctx: RequestContext, id: UUID, ifMatch: Long, cause: AckCause, note: String?): AlertView {
        val now = clock.instant()
        val before = load(j, ctx, id, lock = true)
        if (before.revision != ifMatch) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Alarm wurde zwischenzeitlich geändert; aktuelle Revision lesen.", headers = mapOf("ETag" to Etag.format(before.revision)))
        if (before.state != AlertState.OPEN) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Der Alarm ist bereits quittiert oder gelöst.")
        j.update("INSERT INTO taktovia.alert_acknowledgement(tenant_id, id, alert_id, acknowledged_by, note, created_at, cause_code) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), id, "user:" + ctx.actor.subject, note, Timestamp.from(now), cause.name)
        j.update("UPDATE taktovia.business_alert SET state = 'ACKNOWLEDGED', escalate_at = NULL, revision = revision + 1, updated_at = ? WHERE id = ?", Timestamp.from(now), id)
        audit.record(j, AuditEntry(ActorRef("user:" + ctx.actor.subject), AuditActions.ALERT_ACKNOWLEDGED, ObjectRef("business_alert", id), ctx.correlationId, mapOf("cause" to cause.name, "revision" to before.revision + 1), now))
        outbox.append(j, OutboxEvent(id, before.revision + 1, "alert.acknowledged", linkedMapOf("alert_id" to id.toString(), "state" to "ACKNOWLEDGED", "service_id" to before.serviceId?.toString(), "cause" to cause.name), now, correlationId = ctx.correlationId))
        return load(j, ctx, id, lock = false)
    }

    // ---------------- Eskalation ----------------

    override fun escalateDue(tenant: TenantId): List<UUID> {
        val now = clock.instant(); val ts = Timestamp.from(now)
        return ctxTx.runSystem(tenant) { j ->
            // Lease: höchstens ein Lauf je Mandant zugleich; wer die Sperre nicht bekommt, tut nichts (der andere Lauf erledigt es)
            val locked = j.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended('alert_escalation|' || taktovia.current_tenant()::text, 0))", Boolean::class.java)!!
            if (!locked) return@runSystem emptyList()
            val rows = j.query(
                "UPDATE taktovia.business_alert SET escalation_level = escalation_level + 1, escalated_at = ?, escalate_at = CASE WHEN escalation_level + 1 < ? THEN CAST(? AS timestamptz) ELSE NULL END, " +
                    "revision = revision + 1, updated_at = ? WHERE state = 'OPEN' AND escalate_at IS NOT NULL AND escalate_at <= ? AND escalation_level < ? RETURNING id, revision, escalation_level, priority, service_id",
                { rs, _ -> Esc(rs.getObject(1, UUID::class.java), rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getObject(5, UUID::class.java)) },
                ts, settings.maxEscalationLevel, Timestamp.from(now.plus(settings.escalationDelay)), ts, ts, settings.maxEscalationLevel)
            rows.forEach { e ->
                outbox.append(j, OutboxEvent(e.id, e.revision, "alert.escalated", linkedMapOf("alert_id" to e.id.toString(), "priority" to e.priority, "service_id" to e.serviceId?.toString(), "escalation_level" to e.level, "state" to "OPEN"), now))
            }
            rows.map { it.id }
        }
    }

    private class Esc(val id: UUID, val revision: Long, val level: Int, val priority: String, val serviceId: UUID?)

    private fun guardHuman(ctx: RequestContext, scope: String) {
        if (ctx.actor.type != ActorType.HUMAN || ctx.actor.sessionId == null || !ctx.hasScope(scope)) throw ProblemException(ErrorCode.FORBIDDEN)
    }

    private companion object {
        const val COLUMNS = "a.id, a.alert_kind, a.priority, a.state, a.vehicle_id, a.service_id, a.recipient_role, p.revision_no, a.escalation_level, a.escalated_at, a.escalate_at, a.reason_code, a.ref_type, a.ref_id, " +
            "a.created_at, a.updated_at, a.resolved_at, a.revision, k.acknowledged_by, k.created_at, k.cause_code, k.note"
        const val FROM = "FROM taktovia.business_alert a LEFT JOIN taktovia.alert_policy_revision p ON p.tenant_id = a.tenant_id AND p.id = a.policy_revision_id " +
            "LEFT JOIN taktovia.alert_acknowledgement k ON k.tenant_id = a.tenant_id AND k.alert_id = a.id"
        val POLICY_ROLES = setOf("dispatcher", "tenant_admin")
        val RECIPIENT_ROLES = setOf("dispatcher", "callcenter", "support", "planner", "workshop", "tenant_admin")
    }
}
