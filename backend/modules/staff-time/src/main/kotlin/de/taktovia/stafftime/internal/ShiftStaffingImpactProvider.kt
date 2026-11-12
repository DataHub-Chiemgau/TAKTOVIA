package de.taktovia.stafftime.internal

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
 * ImpactProvider „Schichten“ (WP-M4-17, Zusage an WP-M1-23): berührt eine Konfigurationsrevision impact-pflichtige Parameter, nennt er die **veröffentlichten** Schichten, die ab dem Wirksamkeitsbeginn
 * der Revision noch laufen oder beginnen (`ends_at > effective_from`), als Objektverweise `SHIFT` mit Anzahl, höchstens 100 namentlich. Geltungsbereich der Revision: Mandant (alle Schichten), Depot (Schichten dieses Depots) oder Dienst
 * (Schichten mit Dienstbezug dieses Dienstes); Region und Fahrzeug: keine Aussage. Entwürfe sind per RLS-Policy nicht sichtbar und werden zusätzlich ausgeschlossen,
 * andere Mandanten sind durch RLS getrennt; es werden nie Personendaten (Fahrer, Fahrzeug) ausgegeben. Schweregrad `BLOCKING`: veröffentlichte Schichten wären berührt.
 */
internal class ShiftStaffingImpactProvider : ImpactProvider {
    override val name = "schichten"

    override fun analyze(j: JdbcOperations, ctx: RequestContext, subject: ImpactSubject): List<ImpactFinding> {
        val rev = subject.revision
        if (subject.impactRelevantChanges.isEmpty()) return emptyList()
        val from = Timestamp.from(rev.effectiveFrom)
        val ids = when (rev.scopeKind) {
            ScopeKind.TENANT -> j.query("SELECT s.id FROM taktovia.staff_shift s WHERE s.state = 'PUBLISHED' AND s.ends_at > ? ORDER BY s.starts_at, s.id", { rs, _ -> rs.getObject(1, UUID::class.java) }, from)
            ScopeKind.SERVICE -> j.query("SELECT s.id FROM taktovia.staff_shift s WHERE s.state = 'PUBLISHED' AND s.service_id = ? AND s.ends_at > ? ORDER BY s.starts_at, s.id", { rs, _ -> rs.getObject(1, UUID::class.java) }, rev.scopeId, from)
            ScopeKind.DEPOT -> j.query("SELECT s.id FROM taktovia.staff_shift s WHERE s.state = 'PUBLISHED' AND s.depot_id = ? AND s.ends_at > ? ORDER BY s.starts_at, s.id", { rs, _ -> rs.getObject(1, UUID::class.java) }, rev.scopeId, from)
            else -> return emptyList()
        }
        return if (ids.isEmpty()) emptyList() else listOf(ImpactFinding("SHIFT", ImpactSeverity.BLOCKING, ids.size.toLong(), ids.take(100).map { ImpactObjectRef("SHIFT", it) }))
    }
}
