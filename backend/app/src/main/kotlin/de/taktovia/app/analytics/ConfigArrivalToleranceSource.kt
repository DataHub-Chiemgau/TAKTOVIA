package de.taktovia.app.analytics

import de.taktovia.analytics.api.ArrivalTolerance
import de.taktovia.analytics.api.ArrivalToleranceSource
import de.taktovia.analytics.api.PickupTolerance
import de.taktovia.config.api.ConfigApi
import de.taktovia.config.api.EffectiveRequest
import de.taktovia.config.api.EffectiveStatus
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/**
 * Ankunftstoleranz aus der wirksamen Konfiguration (WP-M4-54): `on_time_arrival_tolerance_min` (Katalogstandard 0) und `arrival_grace_min` (Ankunftstoleranz der Zusage, kein Katalogstandard) zum Vergleichsstichtag
 * für den Dienst, spezifischste Ebene gewinnt. Ohne wirksamen Wert für `arrival_grace_min` gilt keine Karenz (0, Ebene GLOBAL ohne Revision); ein unbekannter Dienst oder fehlender Grenzwert ist „nicht auflösbar“.
 * Der Kontext dient nur dem Mandantenbezug; die Auflösung läuft in der Transaktion der Auswertung.
 */
class ConfigArrivalToleranceSource(private val config: ConfigApi, private val ctx: RequestContext) : ArrivalToleranceSource {
    override fun resolve(j: JdbcOperations, tenant: TenantId, serviceId: UUID?, at: Instant): ArrivalTolerance? {
        val tolerance = one(j, serviceId, at, TOLERANCE) ?: return null
        val grace = try { one(j, serviceId, at, GRACE) } catch (e: IllegalArgumentException) { null } ?: PickupTolerance(0, "GLOBAL", null, null)
        return ArrivalTolerance(tolerance, grace)
    }

    private fun one(j: JdbcOperations, serviceId: UUID?, at: Instant, parameter: String): PickupTolerance? {
        val item = try {
            config.effective.resolveInTransaction(j, ctx, EffectiveRequest(parameter = parameter, serviceId = serviceId, at = at)).items.singleOrNull()
        } catch (e: ProblemException) { return null }
        if (item == null || item.status != EffectiveStatus.EFFECTIVE) return null
        val minutes = (item.value as? Number)?.toInt() ?: return null
        return PickupTolerance(minutes, item.level!!.name, item.revisionId, item.revision)
    }

    companion object { const val TOLERANCE = "on_time_arrival_tolerance_min"; const val GRACE = "arrival_grace_min" }
}
