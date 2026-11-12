package de.taktovia.stafftime.internal

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.springframework.jdbc.core.JdbcOperations

/** Funktionsscopes der Dienstplan-Operationen als zweite Verteidigungslinie im Dienst (die erste ist die Rechtematrix im Filter). */
internal object RosterAuthz {
    const val WRITE = "rosters:write"
    const val READ = "rosters:read"
    fun require(ctx: RequestContext, scope: String) { if (!ctx.hasScope(scope)) throw ProblemException(ErrorCode.FORBIDDEN) }
}

/**
 * Datenbankschalter für Entwurfszeilen (`app.roster_draft_access`, transaktionslokal): wird **nur nach** bestandener Rollenprüfung gesetzt. Die restriktiven Policies auf `roster_revision`
 * und `staff_shift` zeigen Entwurfszeilen sonst nicht, auch nicht bei Direktabfrage (SCH-004, „RLS plus Rollenprüfung für Entwurfszeilen“).
 */
internal object RosterDraftAccess {
    fun enable(jdbc: JdbcOperations) { jdbc.queryForObject("SELECT set_config('app.roster_draft_access', 'on', true)", String::class.java) }
}
