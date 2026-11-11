package de.taktovia.identity

import de.taktovia.identity.api.GrantMembership
import de.taktovia.platform.context.ContextRevokedException
import de.taktovia.platform.context.ContextTransactions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * LH-I.4-MUL-006 / WP-M1-06 AK 5: Nach dem Commit eines Widerrufs ist kein Zugriff mehr erfolgreich. Die Wiederprüfung geschieht in der
 * Fachtransaktion mit `FOR SHARE`; ein Widerruf wartet auf laufende schreibende Anfragen und wird von später beginnenden gesehen.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RevocationTest {
    private lateinit var fx: IdentityFixture
    @BeforeAll fun up() { fx = IdentityFixture(16) }
    @AfterAll fun down() = fx.close()

    private fun setup(): Triple<de.taktovia.platform.tenant.TenantId, de.taktovia.platform.context.RequestContext, Pair<de.taktovia.platform.context.RequestContext, UUID>> {
        val t = fx.tenant()
        val admin = fx.context(t, "adm-" + UUID.randomUUID(), emptySet()).let { c -> fx.context(t, c.actor.subject, setOf(fx.seedMembership(t, c.actor.subject, "tenant_admin"))) }
        val victimSubject = "u-" + UUID.randomUUID()
        val granted = fx.identity.admin.grant(admin, GrantMembership(fx.issuer, victimSubject, "dispatcher", null))
        val victim = fx.context(t, victimSubject, setOf(granted.id), roles = setOf("dispatcher"))
        return Triple(t, admin, victim to granted.id)
    }

    @Test
    fun nach_dem_widerruf_scheitert_die_naechste_anfrage() {
        val (_, admin, v) = setup(); val (victim, mid) = v
        val ctxTx = ContextTransactions(fx.tx, fx.identity.revalidator)
        assertEquals(1, ctxTx.run(victim) { 1 })
        assertEquals(1, ctxTx.run(victim, readOnly = true) { 1 })
        fx.identity.admin.revoke(admin, mid, 1)
        assertThrows(ContextRevokedException::class.java) { ctxTx.run(victim) { 1 } }
        assertThrows(ContextRevokedException::class.java) { ctxTx.run(victim, readOnly = true) { 1 } }
        assertTrue(fx.identity.lookup.selectableTenants(de.taktovia.identity.api.SubjectRef(fx.issuer, victim.actor.subject)).isEmpty())
    }

    @Test
    fun widerruf_wartet_auf_laufende_schreibende_anfrage_und_danach_scheitert_alles() {
        val (_, admin, v) = setup(); val (victim, mid) = v
        val ctxTx = ContextTransactions(fx.tx, fx.identity.revalidator)
        val inside = CountDownLatch(1); val release = CountDownLatch(1); val revokeDone = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(2)
        val request = pool.submit<String> { ctxTx.run(victim) { inside.countDown(); release.await(20, TimeUnit.SECONDS); "fertig" } }
        assertTrue(inside.await(10, TimeUnit.SECONDS))
        val revoke = pool.submit { fx.identity.admin.revoke(admin, mid, 1); revokeDone.set(true) }
        Thread.sleep(500)
        assertFalse(revokeDone.get(), "der Widerruf wartet, bis die laufende (bereits geprüfte) Anfrage endet")
        release.countDown()
        assertEquals("fertig", request.get(10, TimeUnit.SECONDS)); revoke.get(10, TimeUnit.SECONDS); pool.shutdown()
        assertThrows(ContextRevokedException::class.java) { ctxTx.run(victim) { 1 } }
    }

    @Test
    fun widerruf_waehrend_anfragen_keine_erfolgreiche_anfrage_nach_commit() {
        val (_, admin, v) = setup(); val (victim, mid) = v
        val ctxTx = ContextTransactions(fx.tx, fx.identity.revalidator)
        val n = 8
        val pool = Executors.newFixedThreadPool(n + 1)
        val revokedAtNanos = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
        val stop = AtomicBoolean(false); val okBefore = AtomicInteger(); val violations = AtomicInteger(); val failedAfter = AtomicInteger()
        val ready = CountDownLatch(n)
        val workers = (1..n).map {
            pool.submit {
                ready.countDown()
                while (!stop.get()) {
                    val startedAt = System.nanoTime()
                    val ok = try { ctxTx.run(victim) { 1 }; true } catch (e: ContextRevokedException) { false }
                    // Eine Anfrage, die nach dem Commit des Widerrufs begann, darf nie erfolgreich sein.
                    if (startedAt > revokedAtNanos.get()) { if (ok) violations.incrementAndGet() else failedAfter.incrementAndGet() } else if (ok) okBefore.incrementAndGet()
                }
            }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        Thread.sleep(300)
        fx.identity.admin.revoke(admin, mid, 1)
        revokedAtNanos.set(System.nanoTime())
        Thread.sleep(500); stop.set(true)
        workers.forEach { it.get(20, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(0, violations.get(), "keine erfolgreiche Anfrage nach dem Commit des Widerrufs")
        assertTrue(okBefore.get() > 0, "vor dem Widerruf liefen Anfragen erfolgreich (der Test belastet wirklich)")
        assertTrue(failedAfter.get() > 0, "nach dem Widerruf wurden Anfragen verweigert")
    }
}
