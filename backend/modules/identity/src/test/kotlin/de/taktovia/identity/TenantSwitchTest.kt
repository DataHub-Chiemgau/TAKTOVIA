package de.taktovia.identity

import de.taktovia.identity.api.SubjectRef
import de.taktovia.identity.api.SwitchOutcome
import de.taktovia.platform.context.ContextTransactions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** WP-M1-06: serverseitiger Tenantwechsel auf Modulebene (Mitgliedschaft, Dienstfreigabe, Auditspur) und unabhängige Kontexte. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantSwitchTest {
    private lateinit var fx: IdentityFixture
    @BeforeAll fun up() { fx = IdentityFixture() }
    @AfterAll fun down() = fx.close()

    @Test
    fun wechsel_verlangt_aktuelle_mitgliedschaft_und_ist_auditiert() {
        val a = fx.tenant(); val b = fx.tenant(); val foreign = fx.tenant()
        val s = "u-" + UUID.randomUUID()
        val ma = fx.seedMembership(a, s, "dispatcher"); fx.seedMembership(b, s, "support")
        val ctx = fx.context(a, s, setOf(ma), roles = setOf("dispatcher"))

        val ok = fx.identity.tenantSwitch.switch(ctx, b, null)
        assertTrue(ok is SwitchOutcome.Switched && ok.membership.tenant == b && ok.membership.roles == setOf("support"))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='identity.tenant.switched' AND actor_ref=?", b.value, "user:$s"), "Wechsel im Zielmandanten auditiert")

        // Mandant ohne Mitgliedschaft (existiert) und nicht existierender Mandant sind nicht unterscheidbar
        assertEquals(SwitchOutcome.NoMembership, fx.identity.tenantSwitch.switch(ctx, foreign, null))
        assertEquals(SwitchOutcome.NoMembership, fx.identity.tenantSwitch.switch(ctx, de.taktovia.platform.tenant.TenantId(UUID.randomUUID()), null))
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='identity.tenant.switched'", foreign.value), "kein Ereignis im fremden Mandanten")
        assertEquals(2, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='security.access.denied'", a.value), "Verweigerungen als Sicherheitsereignis im aktuellen Mandanten")
    }

    @Test
    fun wechsel_in_einen_dienst_ohne_freigabe_wird_abgewiesen() {
        val t = fx.tenant(); val s = "u-" + UUID.randomUUID()
        val allowed = UUID.randomUUID(); val notAllowed = UUID.randomUUID()
        val m = fx.seedMembership(t, s, "dispatcher", listOf(allowed))
        val ctx = fx.context(t, s, setOf(m), roles = setOf("dispatcher"))
        assertTrue(fx.identity.tenantSwitch.switch(ctx, t, allowed) is SwitchOutcome.Switched)
        assertEquals(SwitchOutcome.ServiceNotAllowed, fx.identity.tenantSwitch.switch(ctx, t, notAllowed))
        // ohne ausdrückliche Dienstliste (NULL) ist kein Dienst freigegeben
        val s2 = "u-" + UUID.randomUUID(); val m2 = fx.seedMembership(t, s2, "support", null)
        assertEquals(SwitchOutcome.ServiceNotAllowed, fx.identity.tenantSwitch.switch(fx.context(t, s2, setOf(m2), roles = setOf("support")), t, allowed))
        assertTrue(fx.identity.tenantSwitch.switch(fx.context(t, s2, setOf(m2), roles = setOf("support")), t, null) is SwitchOutcome.Switched, "Wechsel ohne Dienst bleibt möglich")
    }

    @Test
    fun zwei_kontexte_parallel_unabhaengig() {
        val a = fx.tenant(); val b = fx.tenant(); val s = "u-" + UUID.randomUUID()
        val ma = fx.seedMembership(a, s, "support"); val mb = fx.seedMembership(b, s, "support")
        repeat(3) { fx.seedMembership(a, "x$it-$s", "support") }
        val ctxTx = ContextTransactions(fx.tx, fx.identity.revalidator)
        val ctxA = fx.context(a, s, setOf(ma), roles = setOf("support")); val ctxB = fx.context(b, s, setOf(mb), roles = setOf("support"))
        val pool = Executors.newFixedThreadPool(8); val start = CountDownLatch(1)
        val tasks = (1..200).map { i ->
            pool.submit<Triple<String, Int, Int>> {
                start.await()
                val own = if (i % 2 == 0) ctxA else ctxB
                val otherTenant = if (own === ctxA) b else a
                ctxTx.run(own, readOnly = true) { j ->
                    // RLS im Kontext des Requests: nur der eigene Mandant ist sichtbar, der andere nie
                    Triple(if (own === ctxA) "A" else "B", j.queryForObject("SELECT count(*) FROM taktovia.membership", Int::class.java)!!,
                        j.queryForObject("SELECT count(*) FROM taktovia.membership WHERE tenant_id = ?", Int::class.java, otherTenant.value)!!)
                }
            }
        }
        start.countDown()
        val res = tasks.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(setOf(4), res.filter { it.first == "A" }.map { it.second }.toSet(), "A sieht genau seine 4 Mitgliedschaften")
        assertEquals(setOf(1), res.filter { it.first == "B" }.map { it.second }.toSet(), "B sieht genau seine 1 Mitgliedschaft")
        assertEquals(setOf(0), res.map { it.third }.toSet(), "nie Zeilen des anderen Mandanten (keine Kontextvermischung)")
    }
}
