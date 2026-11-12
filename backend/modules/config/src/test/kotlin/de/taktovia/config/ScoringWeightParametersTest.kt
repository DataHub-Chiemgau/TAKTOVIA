package de.taktovia.config

import de.taktovia.config.api.ScopeKind
import de.taktovia.config.api.ValidationOutcome
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal

/**
 * WP-M2-20 / LH-6.5-OPT-008: die vier Gewichte der Mehrzielbewertung sind Dienstparameter im Konfigurationsregister (Migration V20200). Echte Datenbank, Runtime-Rolle:
 * Werte außerhalb 0..100 und falsche Typen weist das Register ab, Grenzwerte werden angenommen; Ebenen Mandant, Region und Dienst.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScoringWeightParametersTest {
    private lateinit var fx: ConfigFixture
    private val names = listOf("opt_weight_wait", "opt_weight_detour", "opt_weight_empty", "opt_weight_quality")

    @BeforeAll fun up() { fx = ConfigFixture() }
    @AfterAll fun down() = fx.close()

    private fun validate(values: Map<String, Any?>, scope: ScopeKind = ScopeKind.TENANT) = fx.config.parameters.validate(fx.context(fx.tenant()), scope, values, null)

    @Test
    fun gewichte_sind_im_register_mit_bereich_0_bis_100_und_standardwerten() {
        val byName = fx.config.parameters.catalog(fx.context(fx.tenant())).parameters.associateBy { it.name }
        val defaults = listOf(40, 20, 25, 15)
        names.forEachIndexed { i, n ->
            val p = byName[n] ?: throw AssertionError("$n fehlt im Register")
            assertEquals("integer", p.type); assertEquals(BigDecimal(0), p.minimum); assertEquals(BigDecimal(100), p.maximum)
            assertEquals("PLANUNGSANNAHME", p.limitSource); assertEquals(setOf(ScopeKind.TENANT, ScopeKind.REGION, ScopeKind.SERVICE), p.allowedLevels)
            assertEquals(defaults[i], (p.default as Number).toInt(), n)
        }
    }

    @Test
    fun grenzwerte_gueltig_ausserhalb_und_falscher_typ_abgelehnt() {
        for (n in names) {
            for (v in listOf(0L, 50L, 100L)) assertEquals(ValidationOutcome.Valid("1", mapOf(n to v)), validate(mapOf(n to v)), "$n=$v")
            assertEquals(listOf(Violation("/$n", ViolationCode.BELOW_MINIMUM)), violations(mapOf(n to -1)))
            assertEquals(listOf(Violation("/$n", ViolationCode.ABOVE_MAXIMUM)), violations(mapOf(n to 101)))
            assertEquals(listOf(Violation("/$n", ViolationCode.TYPE_MISMATCH)), violations(mapOf(n to 2.5)))
            assertEquals(listOf(Violation("/$n", ViolationCode.TYPE_MISMATCH)), violations(mapOf(n to "hoch")))
        }
        assertTrue(validate(mapOf("opt_weight_wait" to 10, "opt_weight_detour" to 20, "opt_weight_empty" to 30, "opt_weight_quality" to 40), ScopeKind.SERVICE) is ValidationOutcome.Valid)
        assertTrue(validate(mapOf("opt_weight_wait" to 10), ScopeKind.REGION) is ValidationOutcome.Valid)
    }

    private fun violations(values: Map<String, Any?>): List<Violation> = (validate(values) as? ValidationOutcome.Invalid)?.violations ?: emptyList()
}
