package de.taktovia.booking

import de.taktovia.bookingdispatch.api.Bookings
import de.taktovia.config.api.ChangeKind
import de.taktovia.config.api.ChangeLogEntry
import de.taktovia.config.api.ConfigChange
import de.taktovia.config.api.ConfigRevisionView
import de.taktovia.config.api.ImpactSeverity
import de.taktovia.config.api.ImpactSubject
import de.taktovia.config.api.RevisionState
import de.taktovia.config.api.ScopeKind
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID

/** WP-M2-23: ImpactProvider „Buchungen“ (Rahmen WP-M1-23) nennt die bestätigten Buchungen des Dienstes ab Wirksamkeitsbeginn, nur als Verweise. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookingImpactProviderTest {
    private lateinit var cf: ConfirmFixture

    @BeforeAll fun up() { cf = ConfirmFixture() }
    @AfterAll fun down() = cf.close()

    private fun subject(scope: ScopeKind, scopeId: UUID, from: Instant, relevant: Boolean): ImpactSubject {
        val changes = if (relevant) listOf(ConfigChange("/service_config/operating_hours", ChangeKind.CHANGED, "a", "b")) else emptyList()
        val log = ChangeLogEntry("user:x", from, "Test", from, null, null, null, changes)
        val rev = ConfigRevisionView(UUID.randomUUID(), scope, scopeId, 2, RevisionState.DRAFT, "1", emptyMap(), from, null, "user:x", from, null, null, "Test", 1, log)
        return ImpactSubject(rev, changes)
    }

    @Test
    fun nennt_bestaetigte_buchungen_des_dienstes_ab_wirksamkeitsbeginn() {
        val w = cf.fx.world(); val run = cf.fx.run(w)
        val b = cf.booked(w, run).second                       // Abholung 2026-11-02 09:00Z
        val provider = Bookings.impactProvider()
        val ctx = cf.operator(w.tenant, setOf(w.serviceId))
        val found = cf.fx.base.tx.run(w.tenant) { j -> provider.analyze(j, ctx, subject(ScopeKind.SERVICE, w.serviceId, Instant.parse("2026-11-01T00:00:00Z"), true)) }
        assertEquals(1, found.size); assertEquals("BOOKING", found.single().kind); assertEquals(1, found.single().count)
        assertEquals(ImpactSeverity.WARNING, found.single().severity); assertEquals(listOf(b.id), found.single().refs.map { it.id })
        // nach dem Wirksamkeitsbeginn liegende Zusagen zählen nicht; andere Dienste, andere Scopes und nicht impact-pflichtige Änderungen liefern nichts
        fun count(s: ImpactSubject) = cf.fx.base.tx.run(w.tenant) { j -> provider.analyze(j, ctx, s) }.size
        assertEquals(0, count(subject(ScopeKind.SERVICE, w.serviceId, Instant.parse("2026-11-03T00:00:00Z"), true)))
        assertEquals(0, count(subject(ScopeKind.SERVICE, UUID.randomUUID(), Instant.parse("2026-11-01T00:00:00Z"), true)))
        assertEquals(0, count(subject(ScopeKind.TENANT, w.tenant.value, Instant.parse("2026-11-01T00:00:00Z"), true)))
        assertEquals(0, count(subject(ScopeKind.SERVICE, w.serviceId, Instant.parse("2026-11-01T00:00:00Z"), false)))
    }
}
