package de.taktovia.config

import de.taktovia.config.api.CreateDraft
import de.taktovia.config.api.ScopeKind
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant

/** LH-I.7-CFG-004: Sicherheits- und Gesetzesgrenzen können durch Mandantenkonfiguration nicht überschritten werden (Originalabnahme). Echte Datenbank, Runtime-Rolle. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParameterRegisterTest {
    private lateinit var fx: ConfigFixture

    @BeforeAll fun up() { fx = ConfigFixture() }
    @AfterAll fun down() = fx.close()

    @Test
    fun wert_ueber_sicherheitsmaximum_wird_abgelehnt() {  // CFG-004 Originalabnahme, auch durch den Mandantenadministrator
        val t = fx.tenant()
        val admin = fx.context(t, roles = setOf("tenant_admin"))
        val e = assertThrows(ProblemException::class.java) {
            fx.config.revisions.createDraft(admin, CreateDraft(ScopeKind.TENANT, t.value, "1", mapOf("max_wait_min" to 241), Instant.parse("2026-12-01T00:00:00Z"), null, "Wartezeit erhöhen"))
        }
        assertEquals(listOf(Violation("/values/max_wait_min", ViolationCode.ABOVE_MAXIMUM)), e.violations)
        assertEquals(0, fx.revisionRows(t))
        val ok = fx.config.revisions.createDraft(admin, CreateDraft(ScopeKind.TENANT, t.value, "1", mapOf("max_wait_min" to 240), Instant.parse("2026-12-01T00:00:00Z"), null, "Maximum"))
        assertEquals(1, ok.revision, "das Maximum selbst ist zulässig")
    }
}
