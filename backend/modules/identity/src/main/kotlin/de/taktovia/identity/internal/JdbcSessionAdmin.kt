package de.taktovia.identity.internal

import de.taktovia.identity.api.AccountAccessView
import de.taktovia.identity.api.IdpAdmin
import de.taktovia.identity.api.IdpOutcome
import de.taktovia.identity.api.LockOutcome
import de.taktovia.identity.api.MembershipLookup
import de.taktovia.identity.api.RecoveryOutcome
import de.taktovia.identity.api.RevokeSessionOutcome
import de.taktovia.identity.api.SessionAdmin
import de.taktovia.identity.api.SessionSettings
import de.taktovia.identity.api.SessionView
import de.taktovia.identity.api.SubjectRef
import de.taktovia.identity.api.UnlockOutcome
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.cache.SessionBlockCache
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Sitzungsverwaltung, Kontosperre und Wiederherstellung (WP-M1-10). Regeln:
 *  - Sperre, Auditereignis und Cache-Eintrag gehören zusammen: Zeilenänderung und Sicherheitsereignis in **einer** Transaktion; der Cache-
 *    Eintrag wird vor dem Commit gesetzt und bei Rollback entfernt.
 *  - Aufrufe des Identitätsanbieters nie innerhalb einer SQL-Transaktion. Lokal gilt jede Sperre sofort; ein Fehler im IdP macht sie nicht
 *    rückgängig, sondern wird als `idpSynced=false` gemeldet (die Operation ist wiederholbar).
 */
internal class JdbcSessionAdmin(
    private val tx: TenantTransactions, private val ctxTx: ContextTransactions, private val lookup: MembershipLookup,
    private val audit: AuditPort, private val idp: IdpAdmin, private val cache: SessionBlockCache, private val settings: SessionSettings,
) : SessionAdmin {
    private val log = LoggerFactory.getLogger(javaClass)

    // ---------------------------------------------------------------- Lesen

    override fun listOwn(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<SessionView> = ctxTx.run(ctx, readOnly = true) { j ->
        page(j, "WHERE s.issuer = ? AND s.subject = ?", listOf(ctx.actor.issuer, ctx.actor.subject), limit, after)
    }

    override fun list(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<SessionView> = ctxTx.run(ctx, readOnly = true) { j ->
        page(j, "WHERE true", emptyList(), limit, after)
    }

    private fun page(j: JdbcOperations, where: String, args: List<Any>, limit: Int, after: Pair<Instant, UUID>?): List<SessionView> {
        val sql = "SELECT s.id, s.subject, s.created_at, s.last_seen_at, s.revoked_at, s.revision FROM taktovia.session_register s $where" +
            (if (after != null) " AND (s.created_at, s.id) > (?, ?)" else "") + " ORDER BY s.created_at, s.id LIMIT ?"
        val params = args + (if (after != null) listOf(Timestamp.from(after.first), after.second) else emptyList()) + (limit + 1)
        return j.query(sql, VIEW, *params.toTypedArray()) // sql-lint: ok where nur feste Literale der Aufrufer (Zeilen 51, 55)
    }

    // ---------------------------------------------------------------- einzelne Sitzung

    override fun revokeOwn(ctx: RequestContext, sessionId: UUID, expectedRevision: Long) =
        revokeOne(ctx, sessionId, expectedRevision, own = true)

    override fun revoke(ctx: RequestContext, sessionId: UUID, expectedRevision: Long) =
        revokeOne(ctx, sessionId, expectedRevision, own = false)

    private class Revoked(val issuer: String, val sid: String)

    private fun revokeOne(ctx: RequestContext, id: UUID, expectedRevision: Long, own: Boolean): RevokeSessionOutcome {
        val keys = mutableListOf<String>()
        val result: Any = guarded(keys) {
            ctxTx.run(ctx) { j -> doRevokeOne(j, ctx, id, expectedRevision, own, keys) }
        }
        return when (result) {
            is Revoked -> RevokeSessionOutcome.Revoked(idpCall { idp.endSession(result.issuer, result.sid) })
            else -> result as RevokeSessionOutcome
        }
    }

    @AuditRequired
    private fun doRevokeOne(j: JdbcOperations, ctx: RequestContext, id: UUID, expectedRevision: Long, own: Boolean, keys: MutableList<String>): Any {
        val sql = "SELECT issuer, idp_session_id, revoked_at IS NOT NULL, revision FROM taktovia.session_register WHERE id = ?" +
            (if (own) " AND issuer = ? AND subject = ?" else "") + " FOR UPDATE"
        val args = if (own) arrayOf<Any>(id, ctx.actor.issuer, ctx.actor.subject) else arrayOf<Any>(id)
        val row = j.query(sql, { rs, _ -> Triple(rs.getString(1) to rs.getString(2), rs.getBoolean(3), rs.getLong(4)) }, *args).firstOrNull() // sql-lint: ok sql aus festen Literalen (Zeile 88), keine Werte
            ?: return RevokeSessionOutcome.NotFound
        if (row.second) return RevokeSessionOutcome.AlreadyRevoked
        if (row.third != expectedRevision) return RevokeSessionOutcome.StaleRevision
        blockSession(keys, ctx.tenant, row.first.first, row.first.second)   // vor dem Commit
        j.update("UPDATE taktovia.session_register SET revoked_at = clock_timestamp(), revoked_by = ?, revoke_reason = ? WHERE id = ?",
            auditActor(ctx).value, if (own) "LOGOUT" else "ADMIN", id)
        emit(j, ctx, if (own) AuditActions.SECURITY_SESSION_LOGOUT else AuditActions.SECURITY_SESSION_REVOKED, ObjectRef("session", id), mapOf("reason" to if (own) "LOGOUT" else "ADMIN"))
        return Revoked(row.first.first, row.first.second)
    }

    // ---------------------------------------------------------------- alle Geräte

    override fun revokeAllOwn(ctx: RequestContext): IdpOutcome {
        val who = SubjectRef(ctx.actor.issuer, ctx.actor.subject)
        val others = lookup.selectableTenants(who).map { it.tenant }.filter { it != ctx.tenant }.sortedBy { it.value }
        var failure: Throwable? = null
        // Aktiver Mandant zuerst (mit Wiederprüfung der Mitgliedschaft), danach jeder weitere Mandant des Kontos; ein Fehler stoppt die übrigen nicht.
        listOf<TenantId?>(null).plus(others).forEach { t ->
            runCatching {
                val keys = mutableListOf<String>()
                guarded(keys) {
                    if (t == null) ctxTx.run(ctx) { j -> doRevokeAll(j, ctx, ctx.tenant, who, AuditActions.SECURITY_SESSION_LOGOUT_ALL, "LOGOUT_ALL", keys) }
                    else tx.run(t) { j -> doRevokeAll(j, ctx, t, who, AuditActions.SECURITY_SESSION_LOGOUT_ALL, "LOGOUT_ALL", keys) }
                }
            }.onFailure { if (failure == null) failure = it; log.error("Alle-Geräte-Abmeldung in einem Mandanten fehlgeschlagen ({})", it.javaClass.simpleName) }
        }
        val idpOutcome = idpCall { idp.endAllSessions(who.issuer, who.subject) }
        failure?.let { if (it is ProblemException) throw it; throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Die Abmeldung konnte nicht in allen Mandanten gespeichert werden; bitte wiederholen.", cause = it) }
        return idpOutcome
    }

    /** Abmeldezeitmarke setzen und alle registrierten Sitzungen des Kontos im Mandanten sperren; liefert die Anzahl. Aufrufer: eigener Mandant per RLS. */
    @AuditRequired
    private fun doRevokeAll(j: JdbcOperations, ctx: RequestContext, tenant: TenantId, who: SubjectRef, action: String, reason: String, keys: MutableList<String>): Int {
        j.update(
            "INSERT INTO taktovia.account_access (tenant_id, issuer, subject, sessions_revoked_before) VALUES (taktovia.current_tenant(), ?, ?, date_trunc('second', clock_timestamp())) " +
                "ON CONFLICT (tenant_id, issuer, subject) DO UPDATE SET sessions_revoked_before = GREATEST(account_access.sessions_revoked_before, EXCLUDED.sessions_revoked_before)",
            who.issuer, who.subject,
        )
        val n = revokeRegistered(j, ctx, tenant, who, reason, keys)
        emit(j, ctx, action, ObjectRef("account", who.subject), mapOf("sessions" to n, "reason" to reason))
        return n
    }

    private fun revokeRegistered(j: JdbcOperations, ctx: RequestContext, tenant: TenantId, who: SubjectRef, reason: String, keys: MutableList<String>): Int {
        val sids = j.query(
            "UPDATE taktovia.session_register SET revoked_at = clock_timestamp(), revoked_by = ?, revoke_reason = ? WHERE issuer = ? AND subject = ? AND revoked_at IS NULL RETURNING idp_session_id",
            { rs, _ -> rs.getString(1) }, auditActor(ctx).value, reason, who.issuer, who.subject,
        )
        sids.forEach { blockSession(keys, tenant, who.issuer, it) }
        return sids.size
    }

    // ---------------------------------------------------------------- Kontosperre

    override fun lock(ctx: RequestContext, subject: UUID, reason: String): LockOutcome {
        val subj = subject.toString()
        if (subj == ctx.actor.subject) return LockOutcome.SelfLock
        val who = SubjectRef(ctx.actor.issuer, subj)
        val keys = mutableListOf<String>()
        val res: Any = guarded(keys) { ctxTx.run(ctx) { j -> doLock(j, ctx, who, reason, keys) } }
        if (res !is AccountAccessView) return res as LockOutcome
        // IdP nach dem Commit: Sitzungen beenden; das IdP-Konto nur sperren, wenn es in keinem anderen Mandanten gebraucht wird
        val idpOutcome = idpCall {
            idp.endAllSessions(who.issuer, who.subject)
            if (lookup.selectableTenants(who).none { it.tenant != ctx.tenant }) idp.setAccountEnabled(who.issuer, who.subject, false)
        }
        return LockOutcome.Locked(res, idpOutcome)
    }

    @AuditRequired
    private fun doLock(j: JdbcOperations, ctx: RequestContext, who: SubjectRef, reason: String, keys: MutableList<String>): Any {
        if (!hasMembership(j, who)) return LockOutcome.UnknownAccount
        blockAccount(keys, ctx.tenant, who)   // vor dem Commit
        val view = j.query(
            "INSERT INTO taktovia.account_access AS a (tenant_id, issuer, subject, status, status_changed_by, lock_reason, sessions_revoked_before) " +
                "VALUES (taktovia.current_tenant(), ?, ?, 'LOCKED', ?, ?, date_trunc('second', clock_timestamp())) " +
                "ON CONFLICT (tenant_id, issuer, subject) DO UPDATE SET status = 'LOCKED', status_changed_at = clock_timestamp(), status_changed_by = EXCLUDED.status_changed_by, " +
                "lock_reason = EXCLUDED.lock_reason, sessions_revoked_before = GREATEST(a.sessions_revoked_before, EXCLUDED.sessions_revoked_before) WHERE a.status <> 'LOCKED' " +
                "RETURNING a.subject, a.status_changed_at, a.revision",
            { rs, _ -> AccountAccessView(rs.getString(1), true, rs.getTimestamp(2).toInstant(), rs.getLong(3)) }, who.issuer, who.subject, auditActor(ctx).value, reason,
        ).firstOrNull() ?: return LockOutcome.AlreadyLocked
        val n = revokeRegistered(j, ctx, ctx.tenant, who, "ACCOUNT_LOCK", keys)
        emit(j, ctx, AuditActions.SECURITY_ACCOUNT_LOCKED, ObjectRef("account", who.subject), mapOf("sessions" to n, "reason" to "ADMIN_LOCK"))
        return view
    }

    override fun unlock(ctx: RequestContext, subject: UUID, expectedRevision: Long): UnlockOutcome {
        val who = SubjectRef(ctx.actor.issuer, subject.toString())
        // Vorprüfung ohne Nebenwirkung; danach IdP (Aufheben ist die risikoerhöhende Richtung, daher zuerst im IdP: scheitert es, bleibt alles gesperrt)
        val pre = ctxTx.run(ctx, readOnly = true) { j -> checkUnlock(j, who, expectedRevision) }
        if (pre != null) return pre
        try { idp.setAccountEnabled(who.issuer, who.subject, true) } catch (e: Exception) {
            throw ProblemException(ErrorCode.PROVIDER_UNAVAILABLE, "Die Entsperrung im Identitätsanbieter ist nicht möglich; die Sperre bleibt bestehen.", cause = e)
        }
        val res: UnlockOutcome = ctxTx.run(ctx) { j -> doUnlock(j, ctx, who, expectedRevision) }
        if (res is UnlockOutcome.Unlocked) runCatching { cache.clear(SessionKeys.account(ctx.tenant, who.issuer, who.subject)) }
        return res
    }

    private fun checkUnlock(j: JdbcOperations, who: SubjectRef, expectedRevision: Long): UnlockOutcome? {
        val acc = j.query("SELECT status, revision FROM taktovia.account_access WHERE issuer = ? AND subject = ?", { rs, _ -> rs.getString(1) to rs.getLong(2) }, who.issuer, who.subject).firstOrNull()
        return when {
            acc == null -> if (hasMembership(j, who)) UnlockOutcome.NotLocked else UnlockOutcome.UnknownAccount
            acc.first != "LOCKED" -> UnlockOutcome.NotLocked
            acc.second != expectedRevision -> UnlockOutcome.StaleRevision
            else -> null
        }
    }

    @AuditRequired
    private fun doUnlock(j: JdbcOperations, ctx: RequestContext, who: SubjectRef, expectedRevision: Long): UnlockOutcome {
        val acc = j.query("SELECT status, revision FROM taktovia.account_access WHERE issuer = ? AND subject = ? FOR UPDATE", { rs, _ -> rs.getString(1) to rs.getLong(2) }, who.issuer, who.subject).firstOrNull()
            ?: return if (hasMembership(j, who)) UnlockOutcome.NotLocked else UnlockOutcome.UnknownAccount
        if (acc.first != "LOCKED") return UnlockOutcome.NotLocked
        if (acc.second != expectedRevision) return UnlockOutcome.StaleRevision
        j.update("UPDATE taktovia.account_access SET status = 'ACTIVE', status_changed_at = clock_timestamp(), status_changed_by = ?, lock_reason = NULL WHERE issuer = ? AND subject = ?",
            auditActor(ctx).value, who.issuer, who.subject)
        emit(j, ctx, AuditActions.SECURITY_ACCOUNT_UNLOCKED, ObjectRef("account", who.subject), emptyMap())
        return UnlockOutcome.Unlocked(IdpOutcome(true))
    }

    // ---------------------------------------------------------------- Wiederherstellung

    override fun startRecovery(ctx: RequestContext, subject: UUID): RecoveryOutcome {
        val who = SubjectRef(ctx.actor.issuer, subject.toString())
        if (!ctxTx.run(ctx, readOnly = true) { j -> hasMembership(j, who) }) return RecoveryOutcome.UnknownAccount
        val verified = try { idp.contactVerified(who.issuer, who.subject) } catch (e: Exception) {
            throw ProblemException(ErrorCode.PROVIDER_UNAVAILABLE, "Der Identitätsanbieter ist nicht erreichbar; die Wiederherstellung wurde nicht angestoßen.", cause = e)
        }
        if (!verified) {
            ctxTx.run(ctx) { j -> recordRecoveryDenied(j, ctx, who) }
            return RecoveryOutcome.ContactNotVerified
        }
        val keys = mutableListOf<String>()
        guarded(keys) { ctxTx.run(ctx) { j -> doRevokeAll(j, ctx, ctx.tenant, who, AuditActions.SECURITY_ACCOUNT_RECOVERY_STARTED, "RECOVERY", keys) } }
        val outcome = idpCall { idp.endAllSessions(who.issuer, who.subject); idp.startRecovery(who.issuer, who.subject) }
        return RecoveryOutcome.Started(Instant.now(), outcome)
    }

    @AuditRequired
    private fun recordRecoveryDenied(j: JdbcOperations, ctx: RequestContext, who: SubjectRef) {
        emit(j, ctx, AuditActions.SECURITY_ACCOUNT_RECOVERY_DENIED, ObjectRef("account", who.subject), mapOf("reason" to "contact_not_verified"))
    }

    // ---------------------------------------------------------------- Hilfen

    /** Sicherheitsereignis (Aktion `security.*`) mit Akteur und Zeitpunkt in der laufenden Transaktion (AuditPort, WP-M1-46). */
    private fun emit(j: JdbcOperations, ctx: RequestContext, action: String, obj: ObjectRef, details: Map<String, Any?>) {
        audit.record(j, AuditEntry(auditActor(ctx), action, obj, ctx.correlationId, details))
    }

    private fun hasMembership(j: JdbcOperations, who: SubjectRef): Boolean =
        j.queryForObject("SELECT count(*) FROM (SELECT 1 FROM taktovia.membership WHERE issuer = ? AND subject = ? LIMIT 1) x", Int::class.java, who.issuer, who.subject) == 1

    private fun blockSession(keys: MutableList<String>, tenant: TenantId, issuer: String, sid: String) {
        val k = SessionKeys.session(tenant, issuer, sid); keys += k
        runCatching { cache.block(k, settings.sessionBlockTtl) }
    }

    private fun blockAccount(keys: MutableList<String>, tenant: TenantId, who: SubjectRef) {
        val k = SessionKeys.account(tenant, who.issuer, who.subject); keys += k
        runCatching { cache.block(k, settings.accountBlockTtl) }
    }

    /** Scheitert die Transaktion (auch beim Commit), werden die vorab gesetzten Sperreinträge entfernt. */
    private fun <T> guarded(keys: List<String>, block: () -> T): T = try { block() } catch (e: Throwable) {
        keys.forEach { runCatching { cache.clear(it) } }
        throw e
    }

    private fun idpCall(block: () -> Unit): IdpOutcome = try { block(); IdpOutcome(true) } catch (e: Exception) {
        log.error("IdP-Administration nicht erreichbar oder abgelehnt ({}); lokale Sperre gilt", e.javaClass.simpleName)
        IdpOutcome(false)
    }

    private companion object {
        val VIEW = RowMapper { rs, _ ->
            SessionView(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5)?.toInstant(), rs.getLong(6))
        }
    }
}
