package de.taktovia.platform.webhook

import de.taktovia.platform.TestDatabase
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.net.SsrfGuard
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** Abonnements verwalten (WP-M1-16): Anlegen, Seiten, Deaktivieren mit Revision, Audit in derselben Transaktion, Rollback ohne Audit. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookSubscriptionServiceTest {
    private lateinit var s: WebhookTestSupport
    private val actor = ActorRef.user(UUID.randomUUID())
    private fun service() = WebhookSubscriptionService(s.base.audit, SsrfGuard(), "taktovia-test-secrets")

    @BeforeAll fun up() { TestDatabase.require(); s = WebhookTestSupport() }
    @AfterAll fun down() = s.close()

    @Test fun anlegen_vergibt_secret_referenz_und_audit_in_derselben_transaktion() {
        val t = s.base.newTenant(); val svc = service()
        val sub = s.tx.run(t) { svc.create(it, actor, UUID.randomUUID(), "https://93.184.216.34/h", listOf("membership.granted", "operation.state_changed"), null) }
        assertTrue(Regex("secret://taktovia-test-secrets/webhook_[0-9a-f]{32}").matches(sub.secretRef)); assertEquals(1, sub.revision); assertTrue(sub.active)
        assertEquals(1, s.base.read(t) { it.queryForObject("SELECT count(*) FROM taktovia.audit_event WHERE action = 'webhook.subscription.created' AND object_ref = ?", Int::class.java, "webhook_subscription:${sub.id}") })
        // schlägt die Transaktion fehl, gibt es weder Abonnement noch Audit
        val tx2 = s.base.newTenant()
        assertThrows<IllegalStateException> { s.tx.run(tx2) { svc.create(it, actor, UUID.randomUUID(), "https://93.184.216.34/h", listOf("membership.granted"), null); error("Fachfehler danach") } }
        assertEquals(0, s.base.read(tx2) { it.queryForObject("SELECT count(*) FROM taktovia.webhook_subscription", Int::class.java) })
        assertEquals(0, s.base.read(tx2) { it.queryForObject("SELECT count(*) FROM taktovia.audit_event WHERE action LIKE 'webhook.%'", Int::class.java) })
    }

    @Test fun ungueltige_eingaben_werden_mit_feldfehlern_abgewiesen_und_nichts_wird_gespeichert() {
        val t = s.base.newTenant(); val svc = service()
        val e = assertThrows<ProblemException> { s.tx.run(t) { svc.create(it, actor, UUID.randomUUID(), "https://10.0.0.1/h", listOf("unbekannt.typ"), "secret://anderer-bereich/webhook_x1") } }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code)
        assertEquals(setOf("/url", "/event_types/0", "/secret_ref"), e.violations.map { it.pointer }.toSet())
        assertEquals(0, s.base.read(t) { it.queryForObject("SELECT count(*) FROM taktovia.webhook_subscription", Int::class.java) })
    }

    @Test fun seiten_nach_created_at_und_id_ohne_luecke_und_ohne_fremde_mandanten() {
        val t = s.base.newTenant(); val other = s.base.newTenant(); val svc = service()
        val ids = (1..5).map { s.tx.run(t) { j -> svc.create(j, actor, UUID.randomUUID(), "https://93.184.216.34/h$it", listOf("membership.granted"), null).id } }
        s.tx.run(other) { svc.create(it, actor, UUID.randomUUID(), "https://93.184.216.34/x", listOf("membership.granted"), null) }
        val first = s.tx.run(t, readOnly = true) { svc.list(it, 2, null) }
        assertEquals(3, first.size, "limit + 1: es gibt weitere")
        val second = s.tx.run(t, readOnly = true) { svc.list(it, 2, CursorPosition(first[1].createdAt, first[1].id)) }
        val third = s.tx.run(t, readOnly = true) { svc.list(it, 2, CursorPosition(second[1].createdAt, second[1].id)) }
        assertEquals(ids.toSet(), (first.take(2) + second.take(2) + third).map { it.id }.toSet(), "alle fünf, keiner doppelt")
        assertTrue(first.none { it.url.endsWith("/x") })
    }

    @Test fun deaktivieren_prueft_revision_verwirft_offene_zustellungen_und_ist_auditiert() {
        val t = s.base.newTenant(); val svc = service()
        val sub = s.tx.run(t) { svc.create(it, actor, UUID.randomUUID(), "https://93.184.216.34/h", listOf("membership.granted"), null) }
        s.base.append(t, UUID.randomUUID(), 1, "membership.granted"); WebhookFanout(s.tx).fanOut(t)
        assertEquals(listOf("PENDING:0:-"), s.deliveries(t))
        assertEquals(ErrorCode.REVISION_MISMATCH, assertThrows<ProblemException> { s.tx.run(t) { svc.deactivate(it, actor, UUID.randomUUID(), sub.id, 5) } }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { s.tx.run(t) { svc.deactivate(it, actor, UUID.randomUUID(), UUID.randomUUID(), 1) } }.code)
        val done = s.tx.run(t) { svc.deactivate(it, actor, UUID.randomUUID(), sub.id, 1) }
        assertEquals(2, done.revision); assertEquals(false, done.active)
        assertEquals(listOf("CANCELLED:0:-"), s.deliveries(t), "offene Zustellung wird verworfen")
        assertEquals(ErrorCode.CONFLICT, assertThrows<ProblemException> { s.tx.run(t) { svc.deactivate(it, actor, UUID.randomUUID(), sub.id, 2) } }.code)
        assertEquals(1, s.base.read(t) { it.queryForObject("SELECT count(*) FROM taktovia.audit_event WHERE action = 'webhook.subscription.deactivated'", Int::class.java) })
        assertNull(s.tx.run(s.base.newTenant(), readOnly = true) { svc.get(it, sub.id) }, "fremder Mandant sieht es nicht")
        WebhookFanout(s.tx).fanOut(t); assertEquals(1, s.deliveries(t).size, "deaktiviertes Abonnement bekommt keine neuen Zustellungen")
    }
}
