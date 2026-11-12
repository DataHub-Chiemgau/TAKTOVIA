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
 * WP-M4-13 / LH-6.24-TIM-006 (Migration V40130) und WP-M4-14 (V40140): `min_rest_hours`, `max_daily_work_minutes` und `shift_publish_notice_hours` im Parameterregister. Echte Datenbank,
 * Runtime-Rolle. Rechtliche Vorgaben sind nicht überschreibbar: Werte jenseits der Systemgrenze (Ruhezeit unter 11 h, Tagesarbeitszeit über 600 min) weist das Register ab; die Grenzen
 * sind eine Planungsannahme (Regelmatrix, Offener Punkt 3).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArbeitszeitParameterTest {
    private lateinit var fx: ConfigFixture
    @BeforeAll fun up() { fx = ConfigFixture() }
    @AfterAll fun down() = fx.close()

    private fun validate(values: Map<String, Any?>, scope: ScopeKind = ScopeKind.TENANT) = fx.config.parameters.validate(fx.context(fx.tenant()), scope, values, null)
    private fun violations(values: Map<String, Any?>): List<Violation> = (validate(values) as? ValidationOutcome.Invalid)?.violations ?: emptyList()

    @Test
    fun parameter_im_register_mit_systemgrenzen_und_standardwerten() {
        val byName = fx.config.parameters.catalog(fx.context(fx.tenant())).parameters.associateBy { it.name }
        val rest = byName["min_rest_hours"] ?: throw AssertionError("min_rest_hours fehlt")
        assertEquals(BigDecimal(11), rest.minimum); assertEquals(11, (rest.default as Number).toInt()); assertEquals("HOURS", rest.unit)
        val daily = byName["max_daily_work_minutes"] ?: throw AssertionError("max_daily_work_minutes fehlt")
        assertEquals(BigDecimal(600), daily.maximum); assertEquals(600, (daily.default as Number).toInt()); assertEquals("MINUTES", daily.unit)
        val notice = byName["shift_publish_notice_hours"] ?: throw AssertionError("shift_publish_notice_hours fehlt")
        assertEquals("HOURS", notice.unit); assertEquals(setOf(ScopeKind.TENANT), notice.allowedLevels)
        listOf(rest, daily, notice).forEach { assertEquals("PLANUNGSANNAHME", it.limitSource, it.name) }
    }

    @Test
    fun wert_unter_rechtlicher_systemgrenze_abgewiesen() {
        // Ruhezeit: 11 h ist die gesetzliche Mindestdauer, darunter wird abgewiesen, darüber (bis 24 h) angenommen
        assertEquals(listOf(Violation("/min_rest_hours", ViolationCode.BELOW_MINIMUM)), violations(mapOf("min_rest_hours" to 10)))
        assertTrue(validate(mapOf("min_rest_hours" to 11)) is ValidationOutcome.Valid)
        assertTrue(validate(mapOf("min_rest_hours" to 12)) is ValidationOutcome.Valid)
        assertEquals(listOf(Violation("/min_rest_hours", ViolationCode.ABOVE_MAXIMUM)), violations(mapOf("min_rest_hours" to 25)))
        assertEquals(listOf(Violation("/min_rest_hours", ViolationCode.TYPE_MISMATCH)), violations(mapOf("min_rest_hours" to 11.5)))
        // Tagesarbeitszeit: 600 min ist die gesetzliche Höchstgrenze, mehr wird abgewiesen
        assertTrue(validate(mapOf("max_daily_work_minutes" to 600)) is ValidationOutcome.Valid)
        assertTrue(validate(mapOf("max_daily_work_minutes" to 480)) is ValidationOutcome.Valid)
        assertEquals(listOf(Violation("/max_daily_work_minutes", ViolationCode.ABOVE_MAXIMUM)), violations(mapOf("max_daily_work_minutes" to 601)))
        assertEquals(listOf(Violation("/max_daily_work_minutes", ViolationCode.BELOW_MINIMUM)), violations(mapOf("max_daily_work_minutes" to 59)))
        // Veröffentlichungsvorlauf (WP-M4-14): 0..720 Stunden, nur Mandantenebene
        assertTrue(validate(mapOf("shift_publish_notice_hours" to 48)) is ValidationOutcome.Valid)
        assertEquals(listOf(Violation("/shift_publish_notice_hours", ViolationCode.ABOVE_MAXIMUM)), violations(mapOf("shift_publish_notice_hours" to 721)))
        assertTrue(validate(mapOf("min_rest_hours" to 12), ScopeKind.REGION) !is ValidationOutcome.Valid, "Rechtsraum-Parameter gelten nur auf Mandantenebene")
    }
}
