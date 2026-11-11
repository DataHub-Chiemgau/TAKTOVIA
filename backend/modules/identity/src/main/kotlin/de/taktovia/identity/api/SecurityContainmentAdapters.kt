package de.taktovia.identity.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.security.AccountContainment
import de.taktovia.platform.security.ContainmentOutcome
import de.taktovia.platform.security.TenantContainment
import de.taktovia.platform.tenant.TenantId

/**
 * Wirkung der Eindämmung im Sicherheitsvorfall (WP-M1-41) auf die Bausteine dieses Moduls. Die Berechtigung und das Audit der Maßnahme selbst
 * übernimmt [de.taktovia.platform.security.SecurityContainment]; Sperre und Statuswechsel behalten ihre eigene Auditspur (WP-M1-10, WP-M1-05).
 */
class IdentityAccountContainment(private val sessions: SessionAdmin) : AccountContainment {
    /** Sperrt das Konto im Mandanten des Kontexts und beendet seine Sitzungen; unbekanntes Konto: 404, eigenes Konto: 409. */
    override fun lockAccount(ctx: RequestContext, subject: java.util.UUID, reason: String): ContainmentOutcome = when (sessions.lock(ctx, subject, reason)) {
        is LockOutcome.Locked -> ContainmentOutcome.EXECUTED
        LockOutcome.AlreadyLocked -> ContainmentOutcome.ALREADY_IN_STATE
        LockOutcome.UnknownAccount -> throw ProblemException(ErrorCode.NOT_FOUND)
        LockOutcome.SelfLock -> throw ProblemException(ErrorCode.CONFLICT, "Das eigene Konto wird nicht gesperrt.")
    }
}

/** Setzt den Mandanten auf `SUSPENDED`; nur für Plattformbetreiberkonten mit zweitem Faktor (sonst 403); bereits gesperrt oder geschlossen: 409 des Zustandsautomaten. */
class IdentityTenantContainment(private val platform: PlatformAdmin) : TenantContainment {
    override fun suspendTenant(ctx: RequestContext, tenant: TenantId): ContainmentOutcome {
        platform.changeTenantStatus(PlatformActor(ctx.actor.issuer, ctx.actor.subject, ctx.correlationId, ctx.actor.multiFactor), tenant, "SUSPENDED")
        return ContainmentOutcome.EXECUTED
    }
}
