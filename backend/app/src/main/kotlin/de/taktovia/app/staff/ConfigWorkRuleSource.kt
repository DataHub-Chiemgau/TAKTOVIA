package de.taktovia.app.staff

import de.taktovia.config.api.EffectiveConfig
import de.taktovia.config.api.EffectiveRequest
import de.taktovia.config.api.EffectiveStatus
import de.taktovia.platform.context.RequestContext
import de.taktovia.stafftime.api.WorkRuleSource
import de.taktovia.stafftime.api.WorkRules
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant

/**
 * Regelparameter der Arbeitszeit-Hinweise aus der wirksamen Konfiguration (WP-M4-13): `min_rest_hours` (Stunden) und `max_daily_work_minutes` (Minuten) je Mandant zum Zeitpunkt [rules]`.at`.
 * Ohne wirksamen Mandantenwert gelten die gesetzlichen Standardwerte (11 h, 600 min); das Register lässt Werte jenseits dieser Systemgrenzen nie zu. Die Pausenschwellen sind kein Katalogparameter
 * (Planungsannahme in [WorkRules]). [config] wird erst bei Gebrauch aufgelöst (kein Verdrahtungszyklus).
 */
class ConfigWorkRuleSource(private val config: () -> EffectiveConfig) : WorkRuleSource {
    override fun rules(jdbc: JdbcOperations, ctx: RequestContext, at: Instant): WorkRules {
        val view = config().resolveInTransaction(jdbc, ctx, EffectiveRequest(parameters = setOf(REST, DAILY), at = at))
        val byName = view.items.associateBy { it.parameter }
        fun value(name: String): Long? = byName[name]?.takeIf { it.status == EffectiveStatus.EFFECTIVE }?.value.let { (it as? Number)?.toLong() }
        val d = WorkRules()
        return d.copy(minRestMinutes = value(REST)?.let { it * 60L } ?: d.minRestMinutes, maxDailyWorkMinutes = value(DAILY) ?: d.maxDailyWorkMinutes)
    }

    private companion object { const val REST = "min_rest_hours"; const val DAILY = "max_daily_work_minutes" }
}
