package de.taktovia.app.staff

import de.taktovia.config.api.ConfigLevel
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.config.api.EffectiveConfigView
import de.taktovia.config.api.EffectiveParameter
import de.taktovia.config.api.EffectiveRequest
import de.taktovia.config.api.EffectiveStatus
import de.taktovia.config.api.PrecedenceRule
import de.taktovia.platform.context.RequestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant

/** WP-M4-13: Regelparameter aus der wirksamen Konfiguration (ohne Datenbank; die Auflösung selbst prüft das Modul `config`). */
class ConfigWorkRuleSourceTest {
    private fun param(name: String, status: EffectiveStatus, value: Any?) =
        EffectiveParameter(name, "U", status, value, ConfigLevel.TENANT, null, null, null, null, null, "1", PrecedenceRule("MOST_SPECIFIC", "Test"), emptyList())

    private fun source(items: List<EffectiveParameter>, seen: MutableList<EffectiveRequest> = mutableListOf()) = ConfigWorkRuleSource {
        object : EffectiveConfig {
            override fun resolve(ctx: RequestContext, request: EffectiveRequest) = throw UnsupportedOperationException()
            override fun resolveInTransaction(jdbc: JdbcOperations, ctx: RequestContext, request: EffectiveRequest): EffectiveConfigView { seen += request; return EffectiveConfigView(Instant.EPOCH, "1", items) }
        }
    }

    @Test
    fun wirksame_werte_ersetzen_die_gesetzlichen_standardwerte() {
        val seen = mutableListOf<EffectiveRequest>()
        val rules = source(listOf(param("min_rest_hours", EffectiveStatus.EFFECTIVE, 12), param("max_daily_work_minutes", EffectiveStatus.EFFECTIVE, 480)), seen).rules(stub(), ctxStub(), Instant.EPOCH)
        assertEquals(12 * 60L, rules.minRestMinutes); assertEquals(480L, rules.maxDailyWorkMinutes)
        assertEquals(setOf("min_rest_hours", "max_daily_work_minutes"), seen.single().parameters)
    }

    @Test
    fun ohne_wirksamen_wert_gelten_die_standardwerte() {
        val rules = source(listOf(param("min_rest_hours", EffectiveStatus.NO_VALUE, null))).rules(stub(), ctxStub(), Instant.EPOCH)
        assertEquals(11 * 60L, rules.minRestMinutes); assertEquals(600L, rules.maxDailyWorkMinutes)
    }

    private fun stub(): JdbcOperations = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(JdbcOperations::class.java)) { _, _, _ -> throw UnsupportedOperationException() } as JdbcOperations
    private fun ctxStub(): RequestContext = de.taktovia.platform.context.RequestContext(
        de.taktovia.platform.context.Actor(de.taktovia.platform.context.ActorType.HUMAN, "i", "s", "sid", true), de.taktovia.platform.tenant.TenantId(java.util.UUID.randomUUID()), emptySet(), emptySet(), emptySet(), java.util.UUID.randomUUID())
}
