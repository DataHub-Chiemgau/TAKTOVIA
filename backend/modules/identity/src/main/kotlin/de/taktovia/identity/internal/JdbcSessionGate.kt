package de.taktovia.identity.internal

import de.taktovia.identity.api.SessionCheckUnavailableException
import de.taktovia.identity.api.SessionGate
import de.taktovia.identity.api.SessionRef
import de.taktovia.identity.api.SessionSettings
import de.taktovia.identity.api.SessionIds
import de.taktovia.identity.api.SessionVerdict
import de.taktovia.platform.cache.SessionBlockCache
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import java.security.MessageDigest
import java.time.Instant

/** Schlüssel der Sperrliste im Cache: gehasht (keine Kennungen im Klartext in Redis) und nach Mandant getrennt. */
internal object SessionKeys {
    private fun h(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)
    fun session(tenant: TenantId, issuer: String, idpSessionId: String) = "s:$tenant:" + h(issuer + "\n" + idpSessionId)
    fun account(tenant: TenantId, issuer: String, subject: String) = "a:$tenant:" + h(issuer + "\n" + subject)
}

/**
 * Prüfung „session status“ je Anfrage (WP-M1-10). PostgreSQL führt: Konto-Zeile und Sitzungs-Zeile werden mit `FOR SHARE` gelesen, ein
 * gleichzeitiges Sperren (UPDATE) wartet auf laufende Prüfungen, eine nach dem Commit der Sperre beginnende Prüfung sieht sie. Damit ist
 * „keine nach Commit begonnene Anfrage erfolgreich“ linearisiert, auch ohne Cache. Der Cache ([SessionBlockCache]) kennt nur Sperren.
 */
internal class JdbcSessionGate(
    private val tx: TenantTransactions, private val cache: SessionBlockCache, private val settings: de.taktovia.identity.api.SessionSettings,
) : SessionGate {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun check(tenant: TenantId, ref: SessionRef): SessionVerdict {
        val sessionKey = SessionKeys.session(tenant, ref.issuer, ref.idpSessionId)
        val accountKey = SessionKeys.account(tenant, ref.issuer, ref.subject)
        // Cache: Treffer = gesperrt. Ausfall oder Fehlen heißt nur „Datenbank fragen“, nie „erlaubt“.
        val cached = try {
            when { cache.isBlocked(accountKey) -> SessionVerdict.ACCOUNT_LOCKED; cache.isBlocked(sessionKey) -> SessionVerdict.REVOKED; else -> null }
        } catch (e: Exception) { log.warn("Sperrlisten-Cache nicht erreichbar, Datenbank entscheidet ({})", e.javaClass.simpleName); null }
        if (cached != null) return cached

        val verdict = try { tx.run(tenant) { j -> decide(j, ref) } } catch (e: Exception) { throw SessionCheckUnavailableException(e) }  // fail-closed
        runCatching {
            when (verdict) {
                SessionVerdict.REVOKED -> cache.block(sessionKey, settings.sessionBlockTtl)
                SessionVerdict.ACCOUNT_LOCKED -> cache.block(accountKey, settings.accountBlockTtl)
                SessionVerdict.ACTIVE -> Unit
            }
        }
        return verdict
    }

    private class Account(val locked: Boolean, val revokedBefore: Instant?)
    private class Row(val subject: String, val revoked: Boolean, val staleSeen: Boolean)

    private fun decide(j: JdbcOperations, ref: SessionRef): SessionVerdict {
        var account = readAccount(j, ref)
        if (account == null) {
            // Zeile entsteht beim ersten Zugriff des Kontos im Mandanten; danach serialisiert sie Sperren gegen laufende Prüfungen
            j.update("INSERT INTO taktovia.account_access (tenant_id, issuer, subject) VALUES (taktovia.current_tenant(), ?, ?) ON CONFLICT DO NOTHING", ref.issuer, ref.subject)
            account = readAccount(j, ref) ?: throw IllegalStateException("account_access nicht lesbar")
        }
        if (account.locked) return SessionVerdict.ACCOUNT_LOCKED
        var row = readSession(j, ref)
        if (row == null) {
            val predatesLogout = account.revokedBefore != null && ref.issuedAt.epochSecond <= account.revokedBefore.epochSecond
            j.update(
                "INSERT INTO taktovia.session_register (tenant_id, id, issuer, subject, idp_session_id, expires_at, revoked_at, revoke_reason) " +
                    "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, clock_timestamp() + (? * interval '1 second'), " +
                    (if (predatesLogout) "clock_timestamp(), 'LOGOUT_ALL'" else "NULL, NULL") + ") ON CONFLICT (tenant_id, issuer, idp_session_id) DO NOTHING",
                SessionIds.of(ref.idpSessionId), ref.issuer, ref.subject, ref.idpSessionId, settings.maxSessionAge.seconds,
            )
            row = readSession(j, ref) ?: throw IllegalStateException("session_register nicht lesbar")
        }
        if (row.subject != ref.subject || row.revoked) return SessionVerdict.REVOKED   // fremdes Subject unter derselben sid: nie vertrauen
        if (row.staleSeen) j.update("UPDATE taktovia.session_register SET last_seen_at = clock_timestamp() WHERE issuer = ? AND idp_session_id = ?", ref.issuer, ref.idpSessionId)
        return SessionVerdict.ACTIVE
    }

    private fun readAccount(j: JdbcOperations, ref: SessionRef): Account? = j.query(
        "SELECT status, sessions_revoked_before FROM taktovia.account_access WHERE issuer = ? AND subject = ? FOR SHARE",
        { rs, _ -> Account(rs.getString(1) == "LOCKED", rs.getTimestamp(2)?.toInstant()) }, ref.issuer, ref.subject,
    ).firstOrNull()

    private fun readSession(j: JdbcOperations, ref: SessionRef): Row? = j.query(
        "SELECT subject, revoked_at IS NOT NULL, last_seen_at < clock_timestamp() - interval '60 seconds' FROM taktovia.session_register " +
            "WHERE issuer = ? AND idp_session_id = ? FOR SHARE",
        { rs, _ -> Row(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3)) }, ref.issuer, ref.idpSessionId,
    ).firstOrNull()
}
