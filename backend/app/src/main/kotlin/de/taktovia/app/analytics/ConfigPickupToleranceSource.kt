package de.taktovia.app.analytics

import de.taktovia.analytics.api.PickupTolerance
import de.taktovia.analytics.api.PickupToleranceSource
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
 * Grenzwert der Abholpünktlichkeit aus der wirksamen Konfiguration (WP-M4-53): `on_time_pickup_tolerance_min` zum Vergleichsstichtag für den Dienst; die Region des Dienstes ergibt das Gebiet, die spezifischste
 * Ebene gilt (Dienst vor Region vor Mandant vor Katalogstandard). Der Kontext der Anfrage dient nur dem Mandantenbezug: die Auflösung in der laufenden Transaktion prüft keinen Dienstscope, das Ergebnis ist für alle Berichtsleser gleich (KPI-010); sie läuft in
 * der Transaktion der Auswertung. Ein unbekannter Dienst oder ein fehlender Wert ist „nicht auflösbar“.
 */
class ConfigPickupToleranceSource(private val config: ConfigApi, private val ctx: RequestContext) : PickupToleranceSource {
    override fun resolve(j: JdbcOperations, tenant: TenantId, serviceId: UUID?, at: Instant): PickupTolerance? {
        val item = try {
            config.effective.resolveInTransaction(j, ctx, EffectiveRequest(parameter = PARAMETER, serviceId = serviceId, at = at)).items.singleOrNull()
        } catch (e: ProblemException) { return null }
        if (item == null || item.status != EffectiveStatus.EFFECTIVE) return null
        val minutes = (item.value as? Number)?.toInt() ?: return null
        return PickupTolerance(minutes, item.level!!.name, item.revisionId, item.revision)
    }

    companion object { const val PARAMETER = "on_time_pickup_tolerance_min" }
}
