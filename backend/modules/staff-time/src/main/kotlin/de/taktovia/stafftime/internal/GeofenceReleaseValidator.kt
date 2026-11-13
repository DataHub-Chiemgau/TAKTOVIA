package de.taktovia.stafftime.internal

import de.taktovia.config.api.ConfigRevisionView
import de.taktovia.config.api.RevisionValidator
import de.taktovia.config.api.ScopeKind
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations

/**
 * Standortminimierung beim Stempeln (WP-M5-20, AT-59, Katalogvorgabe „Nur mit legitimer Zweckbindung“): `clock_in_geofence_enabled` = `true` ist nur in derselben Revision zusammen mit
 * einem nicht leeren Freigabeverweis `clock_in_geofence_release_ref` zulässig (Betriebsvereinbarung, Zweckbindung; D-10/D-11). Ohne Verweis (fehlend oder nur Leerzeichen) ist die Revision
 * nicht validierbar und nicht publizierbar (422 `REQUIRED` am Verweis). Aus (`false` oder nicht gesetzt) braucht keinen Verweis. Reine Prüfung ohne Datenbankzugriff.
 */
internal class GeofenceReleaseValidator : RevisionValidator {
    override val name = "clock-in-geofence-release"

    override fun check(j: JdbcOperations, ctx: RequestContext, revision: ConfigRevisionView): List<Violation> {
        if (revision.scopeKind != ScopeKind.TENANT || revision.values[ENABLED] != true) return emptyList()
        val ref = revision.values[RELEASE_REF] as? String
        return if (ref.isNullOrBlank()) listOf(Violation("/values/$RELEASE_REF", ViolationCode.REQUIRED, "Die Aktivierung braucht den Verweis auf die dokumentierte Freigabe")) else emptyList()
    }

    companion object { const val ENABLED = "clock_in_geofence_enabled"; const val RELEASE_REF = "clock_in_geofence_release_ref" }
}
