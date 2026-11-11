package de.taktovia.identity.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Sitzungsverwaltung und Sitzungssperre (WP-M1-10, LH-6.2-IAM-009). Das Sitzungsregister und der Zugangsstatus des Kontos gehören dem Modul
 * `identity`; PostgreSQL führt, Redis ist höchstens Cache der Sperrliste ([de.taktovia.platform.cache.SessionBlockCache]).
 * Detailvertrag: docs/umsetzungsplan/vertraege/LH-6.2-IAM-009.md.
 */

/** Einstellungen (Planungswerte, OP-13): Sitzungshöchstdauer wie im Realm, TTL von Sperrlisteneinträgen. */
data class SessionSettings(
    /** Höchstdauer einer IdP-Sitzung; nur Ablauf des Registereintrags, keine Zugriffsentscheidung. */
    val maxSessionAge: Duration = Duration.ofSeconds(36_000),
    /** Lebensdauer eines Sperreintrags im Cache für Konten (Sperren können aufgehoben werden): höchstens 60 s. */
    val accountBlockTtl: Duration = Duration.ofSeconds(60),
    /** Lebensdauer eines Sperreintrags einer Sitzung (Sitzungssperren sind endgültig). */
    val sessionBlockTtl: Duration = Duration.ofHours(24),
) {
    init { require(!accountBlockTtl.isNegative && !accountBlockTtl.isZero && accountBlockTtl <= Duration.ofSeconds(60)) { "Sperrlisten-TTL für Konten höchstens 60 s (Detailvertrag)" } }
}

/** Die vom Token geprüfte Sitzung: Subject, IdP-Sitzungskennung (`sid`) und Ausstellungszeit (`iat`). Tokenwerte selbst werden nie gespeichert. */
data class SessionRef(val issuer: String, val subject: String, val idpSessionId: String, val issuedAt: Instant) {
    override fun toString() = "SessionRef"
}

/** Registerkennung (UUID) einer IdP-Sitzung: die `sid` selbst, wenn sie eine UUID ist, sonst eine daraus abgeleitete UUID. */
object SessionIds {
    fun of(idpSessionId: String): UUID = runCatching { UUID.fromString(idpSessionId) }.getOrNull() ?: UUID.nameUUIDFromBytes(idpSessionId.toByteArray())
}

enum class SessionVerdict { ACTIVE, REVOKED, ACCOUNT_LOCKED }

/** Die Prüfung der Sitzung konnte nicht entschieden werden (Datenbank nicht erreichbar): Zugriff verweigern (fail-closed). */
class SessionCheckUnavailableException(cause: Throwable) : RuntimeException("Sitzungsstatus nicht prüfbar", cause)

/** Prüfung „session status“ in **jeder** Anfrage (Kap. 05 §5.1), im bereits ermittelten Mandanten. */
interface SessionGate {
    /**
     * Entscheidet über die Sitzung im Mandanten und registriert sie beim ersten Zugriff. Reihenfolge: Cache der Sperrliste (Treffer =
     * gesperrt), sonst die Datenbank (führend). Fällt der Cache aus, entscheidet die Datenbank; ist auch sie nicht erreichbar, wirft die
     * Methode [SessionCheckUnavailableException] (fail-closed). Ein Token, das vor der letzten Abmeldung ausgestellt wurde (`iat` bis
     * einschließlich der Sekunde der Abmeldung), ist auch dann gesperrt, wenn seine Sitzung im Mandanten noch nie gesehen wurde.
     */
    fun check(tenant: TenantId, ref: SessionRef): SessionVerdict
}

data class SessionView(val id: UUID, val subject: String, val createdAt: Instant, val lastSeenAt: Instant, val revokedAt: Instant?, val revision: Long)

data class AccountAccessView(val subject: String, val locked: Boolean, val statusChangedAt: Instant, val revision: Long)

/** Ergebnis der Operationen, die den Identitätsanbieter beteiligen: lokal gilt die Sperre immer; [idpSynced] sagt, ob der IdP sie nachvollzogen hat. */
data class IdpOutcome(val idpSynced: Boolean)

sealed interface RecoveryOutcome {
    data class Started(val at: Instant, val idp: IdpOutcome) : RecoveryOutcome
    /** Der IdP führt keinen verifizierten Kontakt: keine Wiederherstellung, kein Sitzungsende. */
    data object ContactNotVerified : RecoveryOutcome
    /** Das Konto hat keine Mitgliedschaft im Mandanten (nie vorhanden/fremder Mandant: nicht unterscheidbar). */
    data object UnknownAccount : RecoveryOutcome
}

sealed interface LockOutcome {
    data class Locked(val view: AccountAccessView, val idp: IdpOutcome) : LockOutcome
    data object AlreadyLocked : LockOutcome
    data object UnknownAccount : LockOutcome
    /** Das eigene Konto sperren sich Administratoren nicht selbst. */
    data object SelfLock : LockOutcome
}

sealed interface UnlockOutcome {
    data class Unlocked(val idp: IdpOutcome) : UnlockOutcome
    data object NotLocked : UnlockOutcome
    data object StaleRevision : UnlockOutcome
    data object UnknownAccount : UnlockOutcome
}

sealed interface RevokeSessionOutcome {
    data class Revoked(val idp: IdpOutcome) : RevokeSessionOutcome
    data object NotFound : RevokeSessionOutcome
    data object AlreadyRevoked : RevokeSessionOutcome
    data object StaleRevision : RevokeSessionOutcome
}

interface SessionAdmin {
    /** Sitzungen des Kontos des Kontexts im aktiven Mandanten, sortiert nach (`created_at`, `id`), höchstens [limit]+1 Zeilen. */
    fun listOwn(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<SessionView>

    /** Alle Sitzungen des Mandanten (Administratorsicht, Rechtematrix `sessions:read`). */
    fun list(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<SessionView>

    /** Eigene Sitzung abmelden (Grund LOGOUT); fremde Sitzungen sind nicht auffindbar. */
    fun revokeOwn(ctx: RequestContext, sessionId: UUID, expectedRevision: Long): RevokeSessionOutcome

    /** Sitzung eines beliebigen Kontos im Mandanten sperren (Grund ADMIN). */
    fun revoke(ctx: RequestContext, sessionId: UUID, expectedRevision: Long): RevokeSessionOutcome

    /** „Alle Geräte abmelden“: sperrt alle Sitzungen des Kontos in **allen** seinen Mandanten, setzt die Abmeldezeitmarke und beendet die IdP-Sitzungen. */
    fun revokeAllOwn(ctx: RequestContext): IdpOutcome

    fun lock(ctx: RequestContext, subject: UUID, reason: String): LockOutcome
    fun unlock(ctx: RequestContext, subject: UUID, expectedRevision: Long): UnlockOutcome

    /** Wiederherstellung nur über einen im IdP als verifiziert geführten Kontakt; beendet alle Sitzungen des Kontos. */
    fun startRecovery(ctx: RequestContext, subject: UUID): RecoveryOutcome
}

/** Verbindung zur IdP-Administration (Keycloak Admin-REST) mit eigenem, eng berechtigtem Dienstkonto. Aufrufe nie innerhalb einer SQL-Transaktion. */
interface IdpAdmin {
    /** Beendet alle Sitzungen des Kontos im IdP (Refresh danach nicht mehr möglich). */
    fun endAllSessions(issuer: String, subject: String)

    /** Beendet eine einzelne IdP-Sitzung. */
    fun endSession(issuer: String, idpSessionId: String)

    /** Sperrt oder entsperrt das Konto im IdP (nur sinnvoll, wenn es in keinem anderen Mandanten benötigt wird). */
    fun setAccountEnabled(issuer: String, subject: String, enabled: Boolean)

    /** Ob der IdP einen **verifizierten** Kontakt des Kontos führt. Kein Nebeneffekt. */
    fun contactVerified(issuer: String, subject: String): Boolean

    /** Stößt die Wiederherstellung an (Aktions-Mail an den verifizierten Kontakt). */
    fun startRecovery(issuer: String, subject: String)

    /** Der IdP ist nicht konfiguriert oder nicht erreichbar. */
    class Unavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    /** Standard ohne Anbindung: jede Operation scheitert mit [Unavailable]; lokale Sperren gelten trotzdem. */
    object Unconfigured : IdpAdmin {
        private fun fail(): Nothing = throw Unavailable("IdP-Administration nicht konfiguriert")
        override fun endAllSessions(issuer: String, subject: String) = fail()
        override fun endSession(issuer: String, idpSessionId: String) = fail()
        override fun setAccountEnabled(issuer: String, subject: String, enabled: Boolean) = fail()
        override fun contactVerified(issuer: String, subject: String): Boolean = fail()
        override fun startRecovery(issuer: String, subject: String) = fail()
    }
}
