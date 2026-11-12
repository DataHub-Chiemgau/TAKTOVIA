package de.taktovia.app.payment

import de.taktovia.adapters.psp.api.PspAdapter
import de.taktovia.app.booking.BookingHttpSupport
import de.taktovia.bookingdispatch.api.Bookings
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
 * WP-M2-30 / LH-6.10-PAY-004 über die echte Anwendung (HTTP, echte Datenbank, echte Operationen) gegen den **echten PSP-Simulator** (`tests/sandbox/psp`, Kindprozess, echtes HTTP, Fehlermodi
 * `lost_response`, `decline`): Zahlungsauswahl -> Autorisierung -> Bestätigung der Buchung -> Einzug; abgelehnte Autorisierung gibt Hold und Buchung frei; Timeout bei der Autorisierung bleibt
 * unbestimmt und führt zu genau einer Belastung; Einzugsfehler lässt die Buchung bestätigt. Simulator-Ergebnisse, kein Nachweis gegen einen realen PSP (D-03 offen).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentSimulatorFlowTest {
    private lateinit var sim: PspAppSimulator
    private lateinit var s: BookingHttpSupport
    private lateinit var ops: String   // Betriebsrolle mit `operations:read`: Fahrgäste lesen den Zahlstatus im Zahlungsblock der Buchung, nicht über die Operation

    @BeforeAll fun up() { sim = PspAppSimulator.start() }
    @BeforeEach fun world() { s = BookingHttpSupport(); ops = s.operator(listOf(UUID.fromString(s.world.service))) }
    @AfterAll fun down() { if (::sim.isInitialized) sim.close() }

    private val server get() = s.env.server
    private val payments: Payments get() = server.ctx.getBean(Payments::class.java)
    private val coupling: PaymentBookingCoupling get() = server.ctx.getBean(PaymentBookingCoupling::class.java)
    private fun runner(): OperationRunner = OperationRunner(
        server.ctx.getBean(TenantTransactions::class.java), payments.handlers(PspPaymentGateway(PspAdapter.http("simpsp", URI(sim.url), Duration.ofSeconds(10)))), server.ctx.getBean(Outbox::class.java),
        java.time.Clock.systemUTC(), OperationConfig(reconcileDelay = Duration.ZERO), "worker-sim",
    )

    private fun drain(r: OperationRunner = runner(), max: Int = 12) { var n = 0; while (n < max && r.runNext(s.tenant) != null) n++ }

    private fun pay(id: String, token: String, method: String = "pm_ok", gross: Long, key: String = "pay-" + UUID.randomUUID()) =
        server.send("POST", "/v1/bookings/$id/payment", """{"payment_method_ref":"$method","amount":{"amount_minor":$gross,"currency":"EUR","exponent":2}}""", s.env.headers(token, extra = mapOf("Idempotency-Key" to key)))

    private fun opStatus(id: String) = s.env.get("/v1/operations/$id", ops).json["status"].asString()

    private class Checkout(val h: BookingHttpSupport.Held, val bookingId: String)

    private fun confirmed(): Checkout { val h = s.held(); val r = s.confirm(h); check(r.status == 201) { r.text }; return Checkout(h, r.json["id"].asString()) }

    private fun booking(c: Checkout) = s.get(c.bookingId, c.h.account.token).json
    private fun effects(op: String) = sim.get("/control/log")["calls"].values().count { it["operation"].asString() == op && it["effect_applied"].asBoolean() }
    private fun faults(op: String, mode: String, times: Int = 1) { sim.post("/control/faults", """{"operation":"$op","mode":"$mode","times":$times}""") }

    @Test
    fun authorize_confirm_capture_gegen_psp_simulator() {
        val c = confirmed()
        assertEquals("CONFIRMING", booking(c)["state"].asString()); assertTrue(booking(c)["payment"] == null || booking(c)["payment"].isNull, "vor der Zahlungsauswahl gibt es keinen Zahlungsblock")
        val before = effects("capture")
        val r = pay(c.bookingId, c.h.account.token, gross = c.h.grossMinor)
        assertEquals(202, r.status, r.text); assertEquals("AUTHORIZE_PAYMENT", r.json["kind"].asString()); val opId = r.json["operation_id"].asString()
        assertEquals("QUEUED", opStatus(opId))
        // getrennt abrufbar: die Zahlung ist noch nicht entschieden, die Buchung bleibt CONFIRMING
        assertEquals("CREATED", booking(c)["payment"]["status"].asString()); assertEquals("CONFIRMING", booking(c)["state"].asString())
        drain()
        assertEquals("SUCCEEDED", opStatus(opId))
        assertEquals("AUTHORIZED", booking(c)["payment"]["status"].asString()); assertEquals("CONFIRMING", booking(c)["state"].asString(), "die Bestätigung ist eine eigene Fachoperation")
        // OP-5: Zahlung AUTHORIZED ist Voraussetzung für CONFIRMED; danach der Einzug
        assertEquals(1, coupling.runTenant(s.tenant))
        assertEquals("CONFIRMED", booking(c)["state"].asString()); assertEquals("CAPTURE_PENDING", booking(c)["payment"]["status"].asString())
        drain()
        val b = booking(c)
        assertEquals("CONFIRMED", b["state"].asString()); assertEquals("CAPTURED", b["payment"]["status"].asString())
        assertEquals(c.h.grossMinor, b["payment"]["authorized"]["amount_minor"].asLong()); assertEquals(c.h.grossMinor, b["payment"]["captured"]["amount_minor"].asLong())
        assertEquals(before + 1, effects("capture"), "genau eine Belastung")
        // die Kopplung ist vollzogen: ein weiterer Durchlauf tut nichts
        assertEquals(0, coupling.runTenant(s.tenant))
    }

    @Test
    fun autorisierung_abgelehnt_gibt_hold_frei_keine_buchung_bestaetigt_anderer_zahlungsweg_moeglich() {
        val c = confirmed(); val captures = effects("capture")
        assertEquals(202, pay(c.bookingId, c.h.account.token, "pm_decline", c.h.grossMinor).status)
        drain()
        assertEquals("AUTHORIZATION_FAILED", booking(c)["payment"]["status"].asString()); assertEquals("DECLINED", booking(c)["payment"]["failure_code"].asString())
        assertEquals("CONFIRMING", booking(c)["state"].asString(), "die Zahlung bestätigt nichts")
        assertEquals(1, coupling.runTenant(s.tenant))
        val b = booking(c)
        assertEquals("CANCELED", b["state"].asString()); assertEquals("PAYMENT_FAILED", b["cancel_reason"].asString()); assertEquals("FAHRGAST", b["cancel_cause_class"].asString())
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.capacity_hold WHERE id = ? AND state = 'RELEASED'", UUID.fromString(c.h.holdId)), "der Hold ist freigegeben")
        assertEquals(captures, effects("capture"), "nie eingezogen")
        // die abgelehnte Buchung nimmt keine neue Zahlung an; ein anderer Zahlungsweg läuft über einen neuen Checkout (neues Angebot, neuer Hold) mit demselben Fahrgast
        assertEquals(409, pay(c.bookingId, c.h.account.token, "pm_ok", c.h.grossMinor).status)
        val second = s.held(c.h.account); val b2 = s.confirm(second).json["id"].asString()
        assertEquals(202, pay(b2, second.account.token, "pm_ok", second.grossMinor).status); drain()
        assertEquals("AUTHORIZED", s.get(b2, second.account.token).json["payment"]["status"].asString())
    }

    @Test
    fun psp_timeout_bei_der_autorisierung_unbestimmt_genau_eine_belastung_at15() {
        val c = confirmed()
        val before = effects("authorize")
        faults("authorize", "lost_response")   // der PSP autorisiert, die Antwort geht verloren
        val opId = pay(c.bookingId, c.h.account.token, gross = c.h.grossMinor).json["operation_id"].asString()
        val r = runner(); r.runNext(s.tenant)
        val unknown = booking(c)
        assertEquals("UNKNOWN", unknown["payment"]["status"].asString(), "Zahlstatus unbestimmt, nie als Fehler verkürzt"); assertEquals("CONFIRMING", unknown["state"].asString())
        assertEquals("RECONCILING", opStatus(opId))
        assertEquals(before + 1, effects("authorize"))
        drain(r)   // Abgleich über die Händlerreferenz beim PSP
        assertEquals("AUTHORIZED", booking(c)["payment"]["status"].asString()); assertEquals("SUCCEEDED", opStatus(opId))
        assertEquals(before + 1, effects("authorize"), "genau eine Belastung: der Abgleich wiederholt nichts")
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.payment_intent WHERE booking_id = ?", UUID.fromString(c.bookingId)), "keine zweite Zahlung")
        // die Zahlung wird anschließend regulär eingezogen: weiterhin genau ein Einzug
        coupling.runTenant(s.tenant); drain()
        assertEquals("CAPTURED", booking(c)["payment"]["status"].asString())
    }

    @Test
    fun capture_fehler_nach_bestaetigter_buchung_laesst_die_buchung_bestaetigt() {
        val c = confirmed()
        pay(c.bookingId, c.h.account.token, gross = c.h.grossMinor); drain(); coupling.runTenant(s.tenant)
        assertEquals("CONFIRMED", booking(c)["state"].asString())
        faults("capture", "decline")
        drain()
        val b = booking(c)
        assertEquals("CONFIRMED", b["state"].asString(), "Buchung bleibt bestätigt, nicht storniert, nicht abgeschlossen")
        assertEquals("UNKNOWN", b["payment"]["status"].asString(), "Zahlstatus zeigt den Klärfall"); assertEquals("CAPTURE_DECLINED", b["payment"]["failure_code"].asString())
        assertEquals(0, b["payment"]["captured"]["amount_minor"].asLong(), "nicht als bezahlt markiert")
        assertEquals(0, coupling.runTenant(s.tenant), "die Buchung wird durch den Zahlungsfehler nicht angefasst")
        assertEquals("CONFIRMED", booking(c)["state"].asString())
    }

    @Test
    fun sca_laenger_als_hold_kein_abgelaufener_platz_bestaetigt_autorisierung_aufgehoben() {
        val c = confirmed()
        assertEquals(202, pay(c.bookingId, c.h.account.token, "pm_sca", c.h.grossMinor).status); drain()
        assertEquals("REQUIRES_ACTION", booking(c)["payment"]["status"].asString())
        // die Zahlungsfrist (Hold-Dauer) ist abgelaufen, bevor der Kunde SCA abschließt (Frist des Zahlungsvorgangs in der Vergangenheit; entspricht dem Verstreichen der 180 s)
        s.env.exec("UPDATE taktovia.payment_intent SET authorize_by = clock_timestamp() - interval '1 second' WHERE booking_id = ?", UUID.fromString(c.bookingId))
        val pid = sim.get("/control/log")["calls"].values().last { it["operation"].asString() == "authorize" }["payment_id"].asString()
        sim.post("/control/sca/$pid/complete", """{"outcome":"success"}""")
        // die Browser-Rückkehr allein ändert nichts; sie stellt nur den Abgleich ein
        val back = server.send("POST", "/v1/bookings/${c.bookingId}/payment/return", null, s.env.headers(c.h.account.token, extra = mapOf("Idempotency-Key" to "ret-" + UUID.randomUUID())))
        assertEquals(202, back.status, back.text)
        assertEquals("REQUIRES_ACTION", booking(c)["payment"]["status"].asString())
        drain()
        val b = booking(c)
        assertEquals("VOIDED", b["payment"]["status"].asString(), "die nachträglich erfolgreiche Autorisierung wird aufgehoben"); assertEquals("AUTHORIZATION_EXPIRED", b["payment"]["failure_code"].asString())
        assertEquals("VOIDED", sim.events().last { e -> e["data"]["payment_id"].asString() == pid }["data"]["state"].asString(), "beim PSP ist die Autorisierung aufgehoben")
        // die Buchung wird nicht bestätigt, sondern mit Grund PAYMENT_FAILED freigegeben
        assertEquals("CONFIRMING", b["state"].asString())
        assertEquals(1, coupling.runTenant(s.tenant))
        assertEquals("CANCELED", booking(c)["state"].asString()); assertEquals("PAYMENT_FAILED", booking(c)["cancel_reason"].asString())
    }
}
