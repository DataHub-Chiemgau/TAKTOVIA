package de.taktovia.stafftime.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/** Art einer Depotvergabe: [DEPOT] = genau ein Depot, [ALL_DEPOTS] = das ausdrückliche depotübergreifende Recht für alle Depots des Mandanten. */
enum class DepotGrantKind { DEPOT, ALL_DEPOTS }

data class DepotGrantView(val id: UUID, val subject: String, val kind: DepotGrantKind, val depotId: UUID?, val grantedBy: String, val grantedAt: Instant, val revokedAt: Instant?)

/**
 * Depotberechtigung im Dienstplan (WP-M4-16, LH-6.23-SCH-009): Planer lesen und bearbeiten Dienstpläne nur der Depots, für die sie ausdrücklich berechtigt sind. Die Mandantentrennung bleibt
 * ausnahmslos RLS; dies ist die zweite Schicht. **Fail-closed:** ohne wirksame Vergabe ist der Depotscope leer (nichts lesbar, nichts änderbar, 404 wie bei nicht vorhandenen Objekten).
 * [DepotGrantKind.ALL_DEPOTS] ist das eigene, ausdrücklich vergebene depotübergreifende Recht; Bearbeitung, die allein darauf beruht, steht im `audit_event`
 * (`staff.roster.cross_depot_edit`). Mandantenübergreifende Bearbeitung gibt es nicht. Vergeben und Entziehen darf nur die Rolle mit `organization:write` (Administration), auditiert.
 * Ein Entzug wirkt ab seinem Commit auf jeden folgenden Schreibbefehl und wartet auf gerade laufende Schreibbefehle (`FOR SHARE` in deren Fachtransaktion).
 */
interface RosterDepotAccess {
    /** Vergibt das Recht für ein Depot des Mandanten (unbekanntes oder fremdes Depot: 404; gibt es die Vergabe schon: 409). */
    fun grantDepot(ctx: RequestContext, subject: String, depotId: UUID): DepotGrantView

    /** Vergibt das ausdrückliche depotübergreifende Recht (gibt es schon: 409). */
    fun grantAllDepots(ctx: RequestContext, subject: String): DepotGrantView

    /** Entzieht eine Vergabe endgültig (unbekannt: 404, schon entzogen: 409). */
    fun revoke(ctx: RequestContext, grantId: UUID): DepotGrantView

    /** Vergaben des Mandanten, optional einer Person; entzogene nur mit [includeRevoked]. */
    fun list(ctx: RequestContext, subject: String? = null, includeRevoked: Boolean = false): List<DepotGrantView>
}
