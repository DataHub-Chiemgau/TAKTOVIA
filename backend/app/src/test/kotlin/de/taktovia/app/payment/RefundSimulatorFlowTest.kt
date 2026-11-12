package de.taktovia.app.payment

import de.taktovia.adapters.psp.api.PspAdapter
import de.taktovia.app.booking.BookingHttpSupport
import de.taktovia.farepayment.api.Payments
import de.taktovia.platform.operation.OperationConfig
import de.taktovia.platform.operation.OperationRunner
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.time.Duration
import java.util.UUID

/**
 * WP-M2-31 / AT-07 (Erstattungsteil) über die echte Anwendung (HTTP, echte Datenbank, echte Operationen) gegen den **echten PSP-Simulator**: Storno einer autorisierten Buchung führt zu genau einem
 * Void (kein Einzug), Storno einer eingezogenen zu genau einer Erstattung, die Antwort nennt den Erstattungshinweis („beauftragt“, nie „erstattet“ vor der PSP-Bestätigung), eine abgelehnte
 * Erstattung lässt die Buchung storniert und den Betrag offen. Simulator-Ergebnisse, kein Nachweis gegen einen realen PSP (D-03, D-05 offen).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefundSimulatorFlowTest {
    private lateinit var sim: PspAppSimulator
    private lateinit var s: BookingHttpSupport

    @BeforeAll fun up() { sim = PspAppSimulator.start(seed = 9) }
    @AfterAll fun down() { if (::sim.isInitialized) sim.close() }
    @BeforeEach fun world() { s = BookingHttpSupport() }

    private val server get() = s.env.server
    private val payments: Payments get() = server.ctx.getBean(Payments::class.java)
    private val coupling: PaymentBookingCoupling get() = server.ctx.getBean(PaymentBookingCoupling::class.java)
    private fun runner() = OperationRunner(
        server.ctx.getBean(TenantTransactions::class.java), payments.handlers(PspPaymentGateway(PspAdapter.http("simpsp", URI(sim.url), Duration.ofSeconds(10)))), server.ctx.getBean(Outbox::class.java),
        java.time.Clock.systemUTC(), OperationConfig(reconcileDelay = Duration.ZERO), "worker-refund",
    )
    private fun drain(r: OperationRunner = runner(), max: Int = 12) { var n = 0; while (n < max && r.runNext(s.tenant) != null) n++ }
    private fun effects(op: String) = sim.get("/control/log")["calls"].values().count { it["operation"].asString() == op && it["effect_applied"].asBoolean() }

    private fun pay(id: String, token: String, gross: Long) =
        server.send("POST", "/v1/bookings/$id/payment", """{"payment_method_ref":"pm_ok","amount":{"amount_minor":$gross,"currency":"EUR","exponent":2}}""", s.env.headers(token, extra = mapOf("Idempotency-Key" to "pay-" + UUID.randomUUID())))

    private class Checkout(val h: BookingHttpSupport.Held, val id: String)

    private fun confirmed(): Checkout { val h = s.held(); val r = s.confirm(h); check(r.status == 201) { r.text }; return Checkout(h, r.json["id"].asString()) }
    private fun booking(c: Checkout) = s.get(c.id, c.h.account.token).json
    private fun paid(c: Checkout) { check(pay(c.id, c.h.account.token, c.h.grossMinor).status == 202); drain() }
    private fun captured(c: Checkout) { paid(c); coupling.runTenant(s.tenant); drain(); check(booking(c)["payment"]["status"].asString() == "CAPTURED") }
    private fun cancel(c: Checkout, token: String = c.h.account.token, reason: String = "PASSENGER_REQUEST") = s.cancel(c.id, token, reason, ifMatch = booking(c)["revision"].asString())

    @Test
    fun storno_einer_autorisierten_buchung_fuehrt_zu_genau_einem_void_ohne_einzug() {
        val c = confirmed(); paid(c)
        assertEquals("AUTHORIZED", booking(c)["payment"]["status"].asString())
        val captures = effects("capture"); val voids = effects("void"); val refunds = effects("refund")
        val r = cancel(c)
        assertEquals(200, r.status, r.text); assertEquals("CANCELED", r.json["state"].asString())
        assertEquals("VOID_REQUESTED", r.json["refund_hint"]["status"].asString(), "die Autorisierung wird aufgehoben"); assertEquals(c.h.grossMinor, r.json["refund_hint"]["amount"]["amount_minor"].asLong())
        assertEquals("VOID_PENDING", r.json["payment"]["status"].asString()); assertEquals(voids, effects("void"), "in der Stornotransaktion wird der PSP nicht aufgerufen")
        drain()
        assertEquals("VOIDED", booking(c)["payment"]["status"].asString()); assertEquals(0, booking(c)["payment"]["captured"]["amount_minor"].asLong())
        assertEquals(voids + 1, effects("void"), "genau ein Void"); assertEquals(captures, effects("capture"), "kein Einzug"); assertEquals(refunds, effects("refund"))
        // ein wiederholtes Storno ist abgewiesen und ändert nichts
        assertEquals(409, cancel(c).status); drain(); assertEquals(voids + 1, effects("void"))
    }

    @Test
    fun storno_einer_eingezogenen_buchung_fuehrt_zu_genau_einer_erstattung_beauftragt_vor_erstattet() {
        val c = confirmed(); captured(c)
        val refunds = effects("refund")
        val r = cancel(c)
        assertEquals(200, r.status, r.text)
        assertEquals("REQUESTED", r.json["refund_hint"]["status"].asString()); assertEquals(c.h.grossMinor, r.json["refund_hint"]["amount"]["amount_minor"].asLong())
        // beauftragt, nicht erstattet: der Hinweis in der Antwort nennt den beauftragten Betrag, die Zahlung zeigt noch den Einzug, nichts ist beim PSP erstattet
        val before = r.json["payment"]
        assertEquals("CAPTURED", before["status"].asString()); assertEquals(0, before["refunded"]["amount_minor"].asLong()); assertEquals(c.h.grossMinor, before["refund_pending"]["amount_minor"].asLong()); assertEquals("REQUESTED", before["refund"]["status"].asString())
        assertEquals(refunds, effects("refund"))
        drain()
        val after = booking(c)
        assertEquals("CANCELED", after["state"].asString()); assertEquals("REFUNDED", after["payment"]["status"].asString())
        assertEquals(c.h.grossMinor, after["payment"]["refunded"]["amount_minor"].asLong()); assertEquals("CONFIRMED", after["payment"]["refund"]["status"].asString()); assertTrue(after["payment"]["refund_pending"] == null || after["payment"]["refund_pending"].isNull)
        assertEquals(refunds + 1, effects("refund"), "genau eine Erstattung")
        // wiederholtes Storno und erneuter Abgleich erhöhen refunded nicht
        assertEquals(409, cancel(c).status); drain(); assertEquals(refunds + 1, effects("refund")); assertEquals(c.h.grossMinor, booking(c)["payment"]["refunded"]["amount_minor"].asLong())
    }

    @Test
    fun fahrzeugausfall_durch_den_betrieb_wird_nach_der_betreiberverursachten_regel_erstattet() {
        val c = confirmed(); captured(c)
        val operator = s.operator(listOf(UUID.fromString(s.world.service)))
        val r = cancel(c, operator, "VEHICLE_OUTAGE")
        assertEquals(200, r.status, r.text); assertEquals("FAHRZEUGAUSFALL", r.json["cancel_cause_class"].asString())
        assertEquals("REQUESTED", r.json["refund_hint"]["status"].asString()); drain()
        assertEquals("REFUNDED", booking(c)["payment"]["status"].asString()); assertEquals(c.h.grossMinor, booking(c)["payment"]["refunded"]["amount_minor"].asLong())
    }

    @Test
    fun scheitert_die_erstattung_bleibt_die_buchung_storniert_und_der_offene_betrag_sichtbar() {
        val c = confirmed(); captured(c)
        assertEquals(200, cancel(c).status)
        sim.post("/control/faults", """{"operation":"refund","mode":"decline","times":1}""")
        val refunds = effects("refund")
        drain()
        val b = booking(c)
        assertEquals("CANCELED", b["state"].asString(), "die Buchung bleibt storniert"); assertEquals("FAILED", b["payment"]["refund"]["status"].asString()); assertEquals("REFUND_DECLINED", b["payment"]["refund"]["failure_code"].asString())
        assertEquals(c.h.grossMinor, b["payment"]["refund_pending"]["amount_minor"].asLong(), "der offene Betrag bleibt sichtbar"); assertEquals(0, b["payment"]["refunded"]["amount_minor"].asLong())
        assertEquals(refunds, effects("refund"))
    }

    @Test
    fun storno_ohne_zahlung_hat_keinen_erstattungshinweis() {
        val c = confirmed()
        val r = cancel(c)
        assertEquals(200, r.status, r.text)
        assertTrue(r.json["refund_hint"] == null || r.json["refund_hint"].isNull); assertTrue(r.json["payment"] == null || r.json["payment"].isNull)
    }
}
