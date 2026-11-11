package de.taktovia.platform.approval

import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

/** Vier-Augen-Baustein „Prüfer ≠ Ersteller“ (WP-M1-22): ohne Datenbank, reine Regel. */
class FourEyesTest {
    private fun ctx(subject: String, type: ActorType = ActorType.HUMAN) =
        RequestContext(Actor(type, "https://idp.test", subject, "sid", true), TenantId(UUID.randomUUID()), setOf("tenant_admin"), setOf("config:publish"), emptySet(), UUID.randomUUID())

    @Test
    fun zweite_person_ist_zulaessig() {
        assertEquals("user:bob", FourEyes.requireDistinctReviewer(ctx("bob"), "user:alice").value)
    }

    @Test
    fun ersteller_kann_nicht_selbst_freigeben() {
        val e = assertThrows(ProblemException::class.java) { FourEyes.requireDistinctReviewer(ctx("alice"), "user:alice") }
        assertEquals(ErrorCode.FORBIDDEN, e.code)
        assertThrows(ProblemException::class.java) { FourEyes.requireDistinct("user:alice", "user:alice") }
    }

    @Test
    fun dienstkonto_ist_nie_die_zweite_person() {
        val e = assertThrows(ProblemException::class.java) { FourEyes.requireDistinctReviewer(ctx("bot", ActorType.SERVICE), "user:alice") }
        assertEquals(ErrorCode.FORBIDDEN, e.code)
    }

    @Test
    fun gleiche_subject_kennung_als_dienstkonto_und_mensch_sind_verschieden() {
        // "service:x" und "user:x" sind verschiedene Akteure; ein Mensch mit gleichem Subject wie ein Dienstkonto-Ersteller darf freigeben.
        assertEquals("user:x", FourEyes.requireDistinctReviewer(ctx("x"), "service:x").value)
    }
}
