package de.taktovia.app.identity

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.identity.api.AccountAccessView
import de.taktovia.identity.api.IdentityApi
import de.taktovia.identity.api.IdpOutcome
import de.taktovia.identity.api.LockOutcome
import de.taktovia.identity.api.RecoveryOutcome
import de.taktovia.identity.api.RevokeSessionOutcome
import de.taktovia.identity.api.SessionView
import de.taktovia.identity.api.UnlockOutcome
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Paging
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

class AccountAccessResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/** Anfrage-DTO „Konto sperren“; der Mandant steht nicht darin, er folgt aus dem Kontext. */
class AccountLockRequest(val subject: UUID, val reason: String) {
    companion object { fun from(b: Map<String, Any?>) = AccountLockRequest(UUID.fromString(b["subject"] as String), (b["reason"] as String).trim()) }
}

/**
 * Sitzungsverwaltung und Sitzungssperre (WP-M1-10, LH-6.2-IAM-009): eigene Sitzungen, „alle Geräte abmelden“ (UI-05 Basis), Sitzungen und
 * Konten sperren, Wiederherstellung (UI-18 Basis). Berechtigung entscheidet die Rechtematrix im Anfragefilter; die Sitzungsprüfung selbst
 * läuft in [RequestContextFilter] vor jeder Operation. Lokal gilt jede Sperre sofort; erreicht sie den Identitätsanbieter nicht, antwortet
 * die Operation 503 (bzw. meldet `idp_synced=false` bei der Kontosperre) und ist wiederholbar.
 */
@RestController
class SessionController(private val identity: IdentityApi, private val codec: CursorCodec) {

    @GetMapping("/v1/me/sessions")
    fun listMine(@RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "my-sessions")
        val lim = Paging.limit(limit); val pos = Paging.position(cursor, codec, scope)
        val rows = identity.sessions.listOwn(ctx, lim, pos?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { json(it) }
    }

    @GetMapping("/v1/sessions")
    fun list(@RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "sessions")
        val lim = Paging.limit(limit); val pos = Paging.position(cursor, codec, scope)
        val rows = identity.sessions.list(ctx, lim, pos?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { json(it) }
    }

    @DeleteMapping("/v1/me/sessions/{session_id}")
    fun revokeMine(@PathVariable("session_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): ResponseEntity<Void> =
        revoked(identity.sessions.revokeOwn(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch)))

    @DeleteMapping("/v1/sessions/{session_id}")
    fun revoke(@PathVariable("session_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): ResponseEntity<Void> =
        revoked(identity.sessions.revoke(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch)))

    @PostMapping("/v1/me/sessions/revoke-all")
    fun revokeAll(request: HttpServletRequest): ResponseEntity<Void> {
        val idp = identity.sessions.revokeAllOwn(RequestContexts.require(request))
        requireIdp(idp)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/v1/account-locks")
    fun lock(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<AccountAccessResource> {
        val ctx = RequestContexts.require(request); val req = AccountLockRequest.from(body)
        return when (val out = identity.sessions.lock(ctx, req.subject, req.reason)) {
            is LockOutcome.Locked -> ResponseEntity.status(HttpStatus.CREATED).body(AccountAccessResource(access(out.view, out.idp), out.view.revision))
            LockOutcome.AlreadyLocked -> throw ProblemException(ErrorCode.CONFLICT, "Das Konto ist bereits gesperrt.")
            LockOutcome.SelfLock -> throw ProblemException(ErrorCode.CONFLICT, "Das eigene Konto kann nicht gesperrt werden.")
            LockOutcome.UnknownAccount -> throw ProblemException.notFound()
        }
    }

    @DeleteMapping("/v1/account-locks/{subject}")
    fun unlock(@PathVariable("subject") subject: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): ResponseEntity<Void> =
        when (identity.sessions.unlock(RequestContexts.require(request), subject, Etag.parseIfMatch(ifMatch))) {
            is UnlockOutcome.Unlocked -> ResponseEntity.noContent().build()
            UnlockOutcome.NotLocked -> throw ProblemException(ErrorCode.CONFLICT, "Das Konto ist nicht gesperrt.")
            UnlockOutcome.StaleRevision -> throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Sperre wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
            UnlockOutcome.UnknownAccount -> throw ProblemException.notFound()
        }

    @PostMapping("/v1/account-recoveries")
    fun recover(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val subject = UUID.fromString(body["subject"] as String)
        return when (val out = identity.sessions.startRecovery(RequestContexts.require(request), subject)) {
            is RecoveryOutcome.Started -> { requireIdp(out.idp); ResponseEntity.status(HttpStatus.CREATED).body(linkedMapOf("subject" to subject.toString(), "started_at" to out.at.toString())) }
            RecoveryOutcome.ContactNotVerified -> throw ProblemException(ErrorCode.CONFLICT, "Im Identitätsanbieter ist kein verifizierter Kontakt hinterlegt; keine Wiederherstellung.")
            RecoveryOutcome.UnknownAccount -> throw ProblemException.notFound()
        }
    }

    private fun revoked(o: RevokeSessionOutcome): ResponseEntity<Void> = when (o) {
        is RevokeSessionOutcome.Revoked -> { requireIdp(o.idp); ResponseEntity.noContent().build() }
        RevokeSessionOutcome.NotFound -> throw ProblemException.notFound()
        RevokeSessionOutcome.AlreadyRevoked -> throw ProblemException(ErrorCode.CONFLICT, "Die Sitzung ist bereits gesperrt.")
        RevokeSessionOutcome.StaleRevision -> throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Sitzung wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
    }

    private fun requireIdp(o: IdpOutcome) {
        if (!o.idpSynced) throw ProblemException(ErrorCode.PROVIDER_UNAVAILABLE, "Lokal gesperrt; der Identitätsanbieter hat die Sitzungen noch nicht beendet. Bitte wiederholen (\"alle Geräte abmelden\").", headers = mapOf("Retry-After" to "5"))
    }

    private fun access(v: AccountAccessView, idp: IdpOutcome): Map<String, Any?> = linkedMapOf(
        "subject" to v.subject, "status" to if (v.locked) "LOCKED" else "ACTIVE", "status_changed_at" to v.statusChangedAt.toString(), "revision" to v.revision, "idp_synced" to idp.idpSynced,
    )

    private fun json(v: SessionView): Map<String, Any?> = linkedMapOf(
        "id" to v.id.toString(), "subject" to v.subject, "created_at" to v.createdAt.toString(), "last_seen_at" to v.lastSeenAt.toString(),
        "revoked_at" to v.revokedAt?.toString(), "revision" to v.revision,
    )
}
