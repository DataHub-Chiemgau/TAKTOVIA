package de.taktovia.identity

import de.taktovia.identity.api.SubjectRef
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/**
 * LH-I.4-MUL-006 / OP-06: Der Lookup „Mandanten eines Subjects“ vor gesetztem Tenantkontext liefert ausschließlich Mandanten des
 * angemeldeten Subjects und nur aktive Mitgliedschaften. Unter der echten Runtime-Rolle, ohne Allzweck-SECURITY-DEFINER.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MembershipLookupTest {
    private lateinit var fx: IdentityFixture
    @BeforeAll fun up() { fx = IdentityFixture() }
    @AfterAll fun down() = fx.close()

    private fun who(subject: String, issuer: String = fx.issuer) = SubjectRef(issuer, subject)

    @Test
    fun lookup_fremdes_subject_liefert_nichts() {
        val a = fx.tenant("Mandant A"); val b = fx.tenant("Mandant B"); val c = fx.tenant("Mandant C")
        val me = "u-" + UUID.randomUUID(); val other = "u-" + UUID.randomUUID()
        fx.seedMembership(a, me, "dispatcher"); fx.seedMembership(b, me, "support"); fx.seedMembership(c, other, "tenant_admin")

        val mine = fx.identity.lookup.selectableTenants(who(me))
        assertEquals(setOf(a, b), mine.map { it.tenant }.toSet(), "nur Mandanten des Subjects")
        assertEquals(mine.map { it.tenant.toString() }.sorted(), mine.map { it.tenant.toString() }, "nach Mandanten-ID sortiert (Textform = Byte-Reihenfolge von uuid)")
        assertEquals(mine.first().tenant, fx.identity.lookup.defaultTenant(who(me))!!.tenant, "Standardmandant = kleinste Mandanten-ID")
        assertNull(fx.identity.lookup.defaultTenant(who("u-" + UUID.randomUUID())))
        assertEquals(setOf("Mandant A", "Mandant B"), mine.map { it.tenantName }.toSet())
        assertEquals(listOf(c), fx.identity.lookup.selectableTenants(who(other)).map { it.tenant })
        // fremdes, unbekanntes Subject, anderer Issuer, Präfix: nichts, kein Fehler, keine Aufzählung
        assertEquals(emptyList<Any>(), fx.identity.lookup.selectableTenants(who("u-" + UUID.randomUUID())))
        assertEquals(emptyList<Any>(), fx.identity.lookup.selectableTenants(who(me, "https://anderer-issuer")))
        assertEquals(emptyList<Any>(), fx.identity.lookup.selectableTenants(who(me.dropLast(1))))
        // Mitgliedschaft im Mandanten eines anderen: keine Treffer, auch nicht über activeIn
        assertNull(fx.identity.lookup.activeIn(who(me), c))
        assertNull(fx.identity.lookup.activeIn(who(other), a))
    }

    @Test
    fun nur_aktive_mitgliedschaften_zaehlen() {
        val t = fx.tenant(); val suspended = fx.tenant(status = "SUSPENDED")
        val s = "u-" + UUID.randomUUID()
        val revoked = fx.seedMembership(t, s, "dispatcher")
        fx.seedMembership(suspended, s, "dispatcher")
        assertEquals(setOf(t), fx.identity.lookup.selectableTenants(who(s)).map { it.tenant }.toSet(), "gesperrter Mandant ist nicht wählbar")

        fx.tx.run(t) { j -> j.update("UPDATE taktovia.membership SET revoked_at = clock_timestamp() WHERE id = ?", revoked) }
        assertEquals(emptyList<Any>(), fx.identity.lookup.selectableTenants(who(s)), "widerrufen")
        assertNull(fx.identity.lookup.activeIn(who(s), t))

        // zeitlich begrenzt: abgelaufen bzw. noch nicht gültig
        val expired = "u-" + UUID.randomUUID(); val future = "u-" + UUID.randomUUID()
        fx.tx.run(t) { j ->
            j.update("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code,valid_from,valid_to) VALUES (?,?,?,?,'support', now()-interval '2 days', now()-interval '1 day')", t.value, UUID.randomUUID(), fx.issuer, expired)
            j.update("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code,valid_from) VALUES (?,?,?,?,'support', now()+interval '1 day')", t.value, UUID.randomUUID(), fx.issuer, future)
        }
        assertNull(fx.identity.lookup.activeIn(who(expired), t))
        assertNull(fx.identity.lookup.activeIn(who(future), t))
    }

    @Test
    fun rollen_und_dienstumfang_werden_je_mandant_zusammengefasst_fail_closed() {
        val t = fx.tenant(); val s = "u-" + UUID.randomUUID()
        val d1 = UUID.randomUUID(); val d2 = UUID.randomUUID()
        fx.seedMembership(t, s, "support", null)
        assertNull(fx.identity.lookup.activeIn(who(s), t)!!.serviceScope, "NULL = keine Dienstfreigabe")
        fx.seedMembership(t, s, "dispatcher", listOf(d1, d2))
        val m = fx.identity.lookup.activeIn(who(s), t)
        assertNotNull(m)
        assertEquals(setOf("support", "dispatcher"), m!!.roles)
        assertEquals(setOf(d1, d2), m.serviceScope)
        assertEquals(2, m.membershipIds.size)
    }
}
