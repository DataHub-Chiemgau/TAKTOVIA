package de.taktovia.archfixtures.good.policy

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.policy.ConfirmingCommand
import de.taktovia.platform.policy.PolicyCheck
import de.taktovia.platform.policy.PolicyCommand
import de.taktovia.platform.policy.PolicyDecision
import org.springframework.jdbc.core.JdbcOperations

/** Gute Fixtures: bestätigende Befehle rufen die Regelprüfung auf, direkt bzw. über eine Methode derselben Klasse. */
class CheckedConfirmation(private val policy: PolicyCheck) {
    @ConfirmingCommand
    fun confirm(ctx: RequestContext, jdbc: JdbcOperations, command: PolicyCommand) = policy.check(ctx, jdbc, command)
}

class IndirectlyCheckedConfirmation(private val policy: PolicyCheck) {
    @ConfirmingCommand
    fun confirm(ctx: RequestContext, jdbc: JdbcOperations, command: PolicyCommand): Boolean = decide(ctx, jdbc, command) is PolicyDecision.Allowed
    private fun decide(ctx: RequestContext, jdbc: JdbcOperations, command: PolicyCommand) = policy.evaluate(ctx, jdbc, command)
}
