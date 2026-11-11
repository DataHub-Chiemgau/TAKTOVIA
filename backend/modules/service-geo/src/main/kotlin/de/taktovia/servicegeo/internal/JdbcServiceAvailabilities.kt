package de.taktovia.servicegeo.internal

import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.servicegeo.api.ServiceAvailabilities
import de.taktovia.servicegeo.api.ServiceAvailability
import java.time.Instant
import java.util.UUID

private const val COLS = ServiceRevisionRows.COLUMNS

/**
 * Betriebsstatus eines Dienstes (WP-M2-03): zum Zeitpunkt wirksame Dienstversion, Betriebszeit in Ortszeit. Ohne wirksame Version oder ohne Fenster ist der Dienst
 * „außer Betrieb“ (kein stiller 24-h-Betrieb). Nur RLS und Mandantengrenze (Fahrgäste haben keinen Dienstscope); es werden keine internen Felder geliefert.
 */
internal class JdbcServiceAvailabilities(private val ctxTx: ContextTransactions) : ServiceAvailabilities {
    override fun at(ctx: RequestContext, serviceId: UUID, at: Instant): ServiceAvailability = ctxTx.run(ctx, readOnly = true) { j ->
        val zone = j.query("SELECT s.timezone FROM taktovia.service s WHERE s.id = ?", { rs, _ -> rs.getString(1) }, serviceId).singleOrNull() ?: throw ProblemException.notFound()
        val rev = effectiveOf(j, serviceId, at)
            ?: return@run ServiceAvailability(serviceId, at, inOperation = false, nextOpening = nextVersionOpening(j, serviceId, at), timezone = zone, trafficProduct = null)
        val schedule = ServiceRevisionRows.schedule(rev)
        val next = schedule.nextOpening(at, 60)?.instant?.takeIf { rev.validTo == null || it.isBefore(rev.validTo) }
        ServiceAvailability(serviceId, at, schedule.isOperating(at), next, rev.timezone, rev.trafficProduct)
    }

    /** Beginn der nächsten Version nach [at] (ohne laufende Version): erste Öffnung dieser Version ab ihrem Beginn. */
    private fun nextVersionOpening(j: org.springframework.jdbc.core.JdbcOperations, serviceId: UUID, at: Instant): Instant? {
        val future = j.query("SELECT $COLS FROM taktovia.service_revision r WHERE r.service_id = ? AND r.valid_from > ? ORDER BY r.valid_from LIMIT 1",
            ServiceRevisionRows.VIEW, serviceId, java.sql.Timestamp.from(at)).singleOrNull() ?: return null
        return ServiceRevisionRows.schedule(future).nextOpening(future.validFrom, 60)?.instant
    }
}
