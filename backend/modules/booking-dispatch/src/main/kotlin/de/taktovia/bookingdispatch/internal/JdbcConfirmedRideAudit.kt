package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.ConfirmedRideAudit
import de.taktovia.bookingdispatch.api.PromiseWindow
import de.taktovia.bookingdispatch.api.PromiseWindowGuard
import de.taktovia.bookingdispatch.api.RideAuditFinding
import de.taktovia.bookingdispatch.api.RideAuditReport
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import java.util.UUID

internal class JdbcConfirmedRideAudit(private val ctxTx: ContextTransactions, private val plans: JdbcRunPlans) : ConfirmedRideAudit {
    override fun auditRun(ctx: RequestContext, runId: UUID): RideAuditReport = ctxTx.run(ctx, readOnly = true) { j ->
        val current = j.query("SELECT revision FROM taktovia.vehicle_run WHERE id = ?", { rs, _ -> rs.getLong(1) }, runId).firstOrNull() ?: throw ProblemException.notFound()
        val windows = j.query(
            "SELECT promise_window::text FROM taktovia.capacity_hold WHERE run_id = ? AND promise_window IS NOT NULL AND state IN ('CONSUMED', 'HELD')",
            { rs, _ -> PromiseWindow.parse(rs.getString(1)) }, runId,
        )
        val findings = mutableListOf<RideAuditFinding>()
        var checked = 0
        for (rev in 1..current) {
            val rides = PromiseWindowGuard.rides(plans.planIn(j, runId, rev).stops)
            windows.forEach { w ->
                val ride = rides[w.bookingId] ?: return@forEach
                checked++
                PromiseWindowGuard.check(w, ride).forEach { findings += RideAuditFinding(runId, rev, w.bookingId, it) }
            }
        }
        RideAuditReport(runId, windows.size, checked, current.toInt(), findings)
    }
}
