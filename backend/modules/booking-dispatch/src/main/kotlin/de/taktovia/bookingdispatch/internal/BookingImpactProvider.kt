package de.taktovia.bookingdispatch.internal

import de.taktovia.config.api.ImpactFinding
import de.taktovia.config.api.ImpactObjectRef
import de.taktovia.config.api.ImpactProvider
import de.taktovia.config.api.ImpactSeverity
import de.taktovia.config.api.ImpactSubject
import de.taktovia.config.api.ScopeKind
import de.taktovia.platform.context.RequestContext
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.util.UUID

/**
 * ImpactProvider „Buchungen“ (WP-M2-23, Rahmen aus WP-M1-23): berührt eine Dienstkonfiguration impact-pflichtige Parameter, nennt er die bestätigten Buchungen des Dienstes
 * (`CONFIRMING`, `CONFIRMED`), deren Abholbeginn ab dem Wirksamkeitsbeginn der Revision liegt. Bestehende Zusagen bleiben unverändert (`promise_snapshot` ist unveränderlich); die Änderung wirkt
 * nur auf neue Angebote, deshalb der Schweregrad `WARNING`. Nur Verweise (Art und ID), nie Personendaten.
 */
internal class BookingImpactProvider : ImpactProvider {
    override val name = "buchungen"

    override fun analyze(j: JdbcOperations, ctx: RequestContext, subject: ImpactSubject): List<ImpactFinding> {
        val rev = subject.revision
        if (rev.scopeKind != ScopeKind.SERVICE || subject.impactRelevantChanges.isEmpty()) return emptyList()
        val ids = j.query(
            "SELECT id FROM taktovia.booking WHERE service_id = ? AND state IN ('CONFIRMING', 'CONFIRMED') AND (promise_snapshot ->> 'pickup_earliest')::timestamptz >= ? ORDER BY id",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, rev.scopeId, Timestamp.from(rev.effectiveFrom),
        )
        return if (ids.isEmpty()) emptyList() else listOf(ImpactFinding("BOOKING", ImpactSeverity.WARNING, ids.size.toLong(), ids.take(100).map { ImpactObjectRef("BOOKING", it) }))
    }
}
