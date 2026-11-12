package de.taktovia.stafftime

import de.taktovia.config.api.ChangeKind
import de.taktovia.config.api.ChangeLogEntry
import de.taktovia.config.api.ConfigChange
import de.taktovia.config.api.ConfigRevisionView
import de.taktovia.config.api.ImpactSeverity
import de.taktovia.config.api.ImpactSubject
import de.taktovia.config.api.RevisionState
import de.taktovia.config.api.ScopeKind
import de.taktovia.stafftime.api.StafftimeApi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID

/** WP-M4-17 (Zusage an WP-M1-23): ImpactProvider „Schichten“ nennt veröffentlichte Schichten des aktiven Mandanten ab Wirksamkeitsbeginn, nur als Verweise. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShiftStaffingImpactProviderTest {
    private lateinit var fx: StaffFixture
    @BeforeAll fun up() { fx = StaffFixture() }
    @AfterAll fun down() = fx.close()

    private fun subject(scope: ScopeKind, scopeId: UUID, from: Instant, relevant: Boolean = true, parameter: String = "/tenant_config/min_rest_hours"): ImpactSubject {
        val changes = if (relevant) listOf(ConfigChange(parameter, ChangeKind.CHANGED, "11", "12")) else emptyList()
        val log = ChangeLogEntry("user:x", from, "Test", from, null, null, null, changes)
        val rev = ConfigRevisionView(UUID.randomUUID(), scope, scopeId, 2, RevisionState.DRAFT, "1", emptyMap(), from, null, "user:x", from, null, null, "Test", 1, log)
        return ImpactSubject(rev, changes)
    }

    @Test
    fun nur_veroeffentlichte_schichten_aktiver_mandant_ohne_personendaten() {
        val a = ShiftStaffingTestWorld(fx); val b = ShiftStaffingTestWorld(fx)
        val pa = a.w.person(); val v1 = a.vehicle(); val v2 = a.vehicle(); val ra = a.w.roster(a.planner)
        val published = fx.staff.rosterShifts.create(a.planner, ra.rosterId, a.input("2026-12-01", "06:00", "14:00", pa.driverId, v1))
        a.publish(ra.rosterId)
        // Entwurf der Folgewoche (nicht veröffentlicht): zählt nicht
        val draftRoster = a.w.roster(a.planner, java.time.LocalDate.of(2026, 12, 8))
        fx.staff.rosterShifts.create(a.planner, draftRoster.rosterId, a.input("2026-12-08", "06:00", "14:00", pa.driverId, v2))
        // Schicht eines anderen Mandanten
        val pb = b.w.person(); val vb = b.vehicle(); val rb = b.w.roster(b.planner)
        fx.staff.rosterShifts.create(b.planner, rb.rosterId, b.input("2026-12-01", "06:00", "14:00", pb.driverId, vb)); b.publish(rb.rosterId)

        val provider = StafftimeApi.impactProvider()
        val effective = Instant.parse("2026-11-01T00:00:00Z")
        fun analyze(t: ShiftStaffingTestWorld, s: ImpactSubject) = fx.tx.run(t.tenant) { j -> provider.analyze(j, t.reader, s) }

        // Mandantenscope, Dienst und Depot nennen genau die eine veröffentlichte Schicht; Entwurf und fremder Mandant fehlen
        for (s in listOf(subject(ScopeKind.TENANT, a.tenant.value, effective), subject(ScopeKind.SERVICE, a.service, effective), subject(ScopeKind.DEPOT, a.w.depot, effective))) {
            val found = analyze(a, s)
            assertEquals(1, found.size, s.revision.scopeKind.toString()); assertEquals("SHIFT", found.single().kind); assertEquals(1L, found.single().count)
            assertEquals(ImpactSeverity.BLOCKING, found.single().severity); assertEquals(listOf(published.id), found.single().refs.map { it.id })
            assertTrue(found.single().refs.all { it.kind == "SHIFT" }, "nur Objektverweise (Art und ID), keine Personen oder Fahrzeuge")
            assertFalse(found.toString().contains(pa.driverId.toString()) || found.toString().contains(v1.toString()), "keine Personen- oder Fahrzeugdaten im Befund")
        }
        // aus Sicht des anderen Mandanten (Revision mit fremder Dienstkennung): eigene Schicht, nie die des Mandanten A
        val own = analyze(b, subject(ScopeKind.TENANT, b.tenant.value, effective)); assertEquals(1, own.single().refs.size)
        assertFalse(own.single().refs.any { it.id == published.id })
        assertEquals(emptyList<Any>(), analyze(b, subject(ScopeKind.SERVICE, a.service, effective)), "fremder Dienst im eigenen Mandanten: nichts")
        // Wirksamkeitsbeginn nach Schichtende, nicht impact-pflichtige Änderung und nicht unterstützte Scopes liefern nichts
        assertEquals(emptyList<Any>(), analyze(a, subject(ScopeKind.TENANT, a.tenant.value, Instant.parse("2026-12-02T00:00:00Z"))))
        assertEquals(emptyList<Any>(), analyze(a, subject(ScopeKind.TENANT, a.tenant.value, effective, relevant = false)))
        assertEquals(emptyList<Any>(), analyze(a, subject(ScopeKind.VEHICLE, v1, effective)))
        assertEquals("schichten", provider.name)
    }
}
