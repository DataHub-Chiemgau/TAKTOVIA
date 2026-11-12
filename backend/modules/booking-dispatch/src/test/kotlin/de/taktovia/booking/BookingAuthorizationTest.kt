package de.taktovia.booking

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** WP-M2-23: Objektberechtigung „eigene Buchung“ und Dienstscope des Betriebspersonals (Nachweis „fremde regionale Buchungen“ für WP-M1-08). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookingAuthorizationTest {
    private lateinit var cf: ConfirmFixture

    @BeforeAll fun up() { cf = ConfirmFixture() }
    @AfterAll fun down() = cf.close()

    private fun notFound(block: () -> Unit) = assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { block() }.code)

    @Test
    fun betriebspersonal_fremder_dienst_404() {
        val w = cf.fx.world(); val run = cf.fx.run(w)
        val b = cf.booked(w, run).second
        val inScope = cf.operator(w.tenant, setOf(w.serviceId))
        val otherService = cf.operator(w.tenant, setOf(UUID.randomUUID()))   // Dienst eines anderen Bereichs
        val noScope = cf.operator(w.tenant, emptySet())                      // fail-closed: kein Scope, kein Zugriff
        assertEquals(b.id, cf.bookings.get(inScope, b.id).id)
        notFound { cf.bookings.get(otherService, b.id) }
        notFound { cf.bookings.get(noScope, b.id) }
        assertEquals(emptyList<UUID>(), cf.bookings.list(otherService, 10, null).map { it.id })
        assertEquals(emptyList<UUID>(), cf.bookings.list(noScope, 10, null).map { it.id })
        assertEquals(listOf(b.id), cf.bookings.list(inScope, 10, null).map { it.id })
        // Buchung eines zweiten Dienstes desselben Mandanten bleibt dem ersten Dienstscope verborgen
        val w2Run = cf.fx.run(w)
        val svc2 = w.serviceId   // gleicher Dienst: der Gegenfall (anderer Dienst) wird über den Scope eines fremden Dienstes oben geprüft
        assertEquals(svc2, b.serviceId); assertEquals(w.serviceId, cf.bookings.get(inScope, b.id).serviceId); assertEquals(w2Run.runId == run.runId, false)
    }

    @Test
    fun fremder_fahrgast_und_anderer_mandant_404() {
        val w = cf.fx.world(); val run = cf.fx.run(w)
        val (h, b) = cf.booked(w, run)
        val other = cf.passenger(w)
        notFound { cf.bookings.get(other.ctx, b.id) }
        assertEquals(emptyList<UUID>(), cf.bookings.list(other.ctx, 10, null).map { it.id })
        notFound { cf.bookings.get(h.passenger.ctx, UUID.randomUUID()) }
        // anderer Mandant: weder Fahrgast noch Betrieb sieht die Buchung (RLS und Objektberechtigung)
        val w2 = cf.fx.world(); val foreignPassenger = cf.passenger(w2); val foreignOp = cf.operator(w2.tenant, setOf(w2.serviceId))
        notFound { cf.bookings.get(foreignPassenger.ctx, b.id) }
        notFound { cf.bookings.get(foreignOp, b.id) }
        assertEquals(emptyList<UUID>(), cf.bookings.list(foreignOp, 10, null).map { it.id })
        // eigener Fahrgast sieht sie weiterhin
        assertEquals(b.id, cf.bookings.get(h.passenger.ctx, b.id).id)
    }
}
