package de.taktovia.app.payment

import de.taktovia.adapters.psp.api.PspAdapter
import de.taktovia.adapters.psp.api.PspAuthorize
import de.taktovia.adapters.psp.api.PspCommandRef
import de.taktovia.adapters.psp.api.PspOperation
import de.taktovia.adapters.psp.api.PspOutcome
import de.taktovia.farepayment.api.Payments
import de.taktovia.platform.operation.OperationConfig
import de.taktovia.platform.operation.OperationRunner
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M2-30: PSP-Benachrichtigung -> Abgleich -> Zahlstatus über die echte Anwendung (echter HTTP-Eingang mit PSP-Signatur) und den **echten PSP-Simulator**: der Eingang stellt in derselben
 * Transaktion einen Abgleich ein; der Zahlstatus folgt der Statusabfrage beim PSP, nie dem Inhalt des Ereignisses; 100 identische Zustellungen haben genau eine Wirkung.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentWebhookReconcileTest {
    private lateinit var sim: PspAppSimulator
    private lateinit var psp: PspAdapter
    private lateinit var provider: String
    private var tenantOrNull: TenantId? = null
    private val tenant: TenantId get() = tenantOrNull!!
    private lateinit var target: String
    private val server get() = PspEventEnv.server

    @BeforeAll fun up() {
        // eigener Seed (eigene Schlüssel und Ereignis-IDs) und eigener Mandant: sonst kollidieren die Ereignis-IDs mit denen anderer Testklassen (gleicher Provider `simpsp`, gleicher Seed)
        sim = PspAppSimulator.start(seed = 4711); tenantOrNull = PspEventEnv.tenant(); provider = "simpsp"; PspTestKeys.register(tenant, provider, sim.webhookSecret().toByteArray())
        target = "http://127.0.0.1:${server.port}/v1/payments/provider-events/$provider"; sim.configure(target, auto = false)
        psp = PspAdapter.http("simpsp", URI(sim.url), Duration.ofSeconds(10))
    }
    @AfterAll fun down() { if (::sim.isInitialized) sim.close() }

    private fun runner() = OperationRunner(server.ctx.getBean(TenantTransactions::class.java), server.ctx.getBean(Payments::class.java).handlers(PspPaymentGateway(psp)), server.ctx.getBean(Outbox::class.java),
        java.time.Clock.systemUTC(), OperationConfig(reconcileDelay = Duration.ZERO), "worker-webhook")

    /** Zahlung mit SCA beim PSP und die zugehörige Zeile (Testaufbau ohne Fachketten: Fremdschlüssel für diese Transaktion aus). */
    private class P(val id: UUID, val pspId: String, val ref: String)

    private fun scaPayment(amount: Long = 1250, state: String = "REQUIRES_ACTION"): P {
        val merchant = "mr-" + UUID.randomUUID()
        val a = psp.authorize(PspAuthorize(PspCommandRef.derive(merchant, PspOperation.AUTHORIZE), merchant, amount, "EUR", "pm_sca")) as PspOutcome.Ok
        val id = UUID.randomUUID(); val journey = UUID.randomUUID()
        adminExec(
            "INSERT INTO taktovia.journey(tenant_id, id, passenger_id, quote_id, state, created_at, currency) VALUES (?, ?, ?, ?, 'HELD', now(), 'EUR')", tenant.value, journey, UUID.randomUUID(), UUID.randomUUID(),
            "INSERT INTO taktovia.payment_intent(tenant_id, id, journey_id, provider, provider_ref, psp_payment_id, merchant_ref, state, currency, authorized_minor, requested_minor, authorize_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'EUR', 0, ?, now() + interval '1 day')", tenant.value, id, journey, provider, a.payment.providerRef, a.payment.paymentId, merchant, state, amount,
        )
        return P(id, a.payment.paymentId, a.payment.providerRef)
    }

    /** Journey-Hülle und Zahlung (zwei Anweisungen mit ihren Parametern, in einer Transaktion mit ausgeschalteten Fremdschlüsseln: Passagier, Quote und Buchung sind nicht Gegenstand des Tests). */
    private fun adminExec(journeySql: String, a1: Any?, a2: Any?, a3: Any?, a4: Any?, paymentSql: String, vararg args: Any?) {
        val ds = de.taktovia.TaktoviaTestSupport.adminDataSourceOrNull()!!
        ds.connection.use { c ->
            c.autoCommit = false
            c.createStatement().use { it.execute("SET LOCAL session_replication_role = replica") }
            c.prepareStatement(journeySql).use { ps -> listOf(a1, a2, a3, a4).forEachIndexed { i, v -> ps.setObject(i + 1, v) }; ps.executeUpdate() }
            c.prepareStatement(paymentSql).use { ps -> args.forEachIndexed { i, v -> ps.setObject(i + 1, v) }; ps.executeUpdate() }
            c.commit()
        }
    }

    private fun state(p: P) = PspEventEnv.admin("SELECT state FROM taktovia.payment_intent WHERE id = ?", p.id) { it.getString(1) }.single()
    private fun ops(p: P, status: String? = null) = PspEventEnv.count("SELECT count(*) FROM taktovia.payment_operation po JOIN taktovia.operation o ON o.id = po.operation_id WHERE po.payment_id = ? AND po.action = 'RECONCILE'" + (if (status != null) " AND o.status = '$status'" else ""), p.id)
    private fun lastEvent(p: P) = sim.events().last { it["data"]["payment_id"].asString() == p.pspId }["event_id"].asString()
    private fun statuses(result: tools.jackson.databind.JsonNode) = result["deliveries"].values().map { it["http_status"].asInt() }

    @Test
    fun hundert_identische_webhooks_stellen_genau_einen_abgleich_ein_und_der_status_folgt_dem_psp() {
        val p = scaPayment()
        sim.post("/control/sca/${p.pspId}/complete", """{"outcome":"success"}""")
        val eventId = lastEvent(p)
        val pool = Executors.newFixedThreadPool(5); val start = CountDownLatch(1)
        val futures = (1..5).map { pool.submit<List<Int>> { start.await(); statuses(sim.deliver(eventId, "valid", repeat = 20)) } }
        start.countDown(); val all = futures.flatMap { it.get(120, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, all.count { it == 202 }, "Zustellungen " + all.groupingBy { it }.eachCount() + " " + sim.deliveries().takeLast(2)); assertEquals(99, all.count { it == 200 })
        assertEquals(1, ops(p), "genau ein Abgleich: die Wiederholungen haben keine Wirkung; Zustellungen " + all.groupingBy { it }.eachCount() + " / " + sim.deliveries().takeLast(3).map { it.toString() }); assertEquals("REQUIRES_ACTION", state(p), "das Ereignis selbst ändert den Zahlstatus nicht")
        runner().let { r -> var n = 0; while (n++ < 5 && r.runNext(tenant) != null) Unit }
        assertEquals("AUTHORIZED", state(p), "der verifizierte Status der PSP-Abfrage löst den Übergang aus")
        assertEquals(1250, PspEventEnv.admin("SELECT authorized_minor FROM taktovia.payment_intent WHERE id = ?", p.id) { it.getLong(1) }.single())
        assertEquals(1, PspEventEnv.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'payment.authorized' AND aggregate_id = ?", tenant.value, p.id))
    }

    @Test
    fun ein_gefaelschter_ereignisinhalt_aendert_den_zahlstatus_nicht() {
        val p = scaPayment()   // beim PSP bleibt die Zahlung REQUIRES_ACTION
        // gültig signiert, aber der Inhalt behauptet eine abgelehnte Autorisierung: maßgeblich ist allein die Statusabfrage beim PSP
        val id = "evt_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val body = """{"event_id":"$id","sequence":1,"type":"payment.authorization_failed","created_at":"2026-11-01T10:00:00Z","data":{"provider_ref":"${p.ref}","state":"AUTHORIZATION_FAILED","currency":"EUR"}}"""
        PspEventEnv.post(provider, body, sim.webhookSecret().toByteArray()).let { assertEquals(202, it.status, it.text) }
        runner().let { r -> var n = 0; while (n++ < 5 && r.runNext(tenant) != null) Unit }
        assertEquals("REQUIRES_ACTION", state(p), "kein Übergang aus dem Payload"); assertEquals(0, ops(p, "QUEUED"))
        // unbekannte PSP-Referenz: gespeichert und quittiert, aber ohne Zahlung, Operation oder Wirkung
        val before = PspEventEnv.count("SELECT count(*) FROM taktovia.operation WHERE tenant_id = ?", tenant.value)
        val unknown = """{"event_id":"evt_${UUID.randomUUID().toString().replace("-", "").take(16)}","type":"payment.authorized","created_at":"2026-11-01T10:00:00Z","data":{"provider_ref":"psp_unbekannt","state":"AUTHORIZED"}}"""
        assertEquals(202, PspEventEnv.post(provider, unknown, sim.webhookSecret().toByteArray()).status)
        assertEquals(before, PspEventEnv.count("SELECT count(*) FROM taktovia.operation WHERE tenant_id = ?", tenant.value))
    }
}
