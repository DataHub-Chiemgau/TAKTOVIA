package de.taktovia.app.payment

import de.taktovia.adapters.psp.api.PspAdapter
import de.taktovia.app.booking.BookingHttpSupport
import de.taktovia.app.booking.OfferHttpSupport
import de.taktovia.farepayment.api.Payments
import de.taktovia.platform.operation.OperationConfig
import de.taktovia.platform.operation.OperationRunner
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M2-46 (UI-04 Selbststorno, LH-6.3-PAX-009, AT-07 Ende zu Ende ohne Browser) über die echte Anwendung (HTTP, echte Datenbank, echte Operationen) gegen den **echten PSP-Simulator**:
 * Storno mit Void und mit Erstattung, Konsistenz von Status, `booking.state`, Historie, Kapazität, `payment_intent` und `refund_case`, Wiederbuchung desselben Platzes, Frist, Header- und
 * Berechtigungsprüfung, Storno in zwei Tabs und parallel zum Zahlungsabgleich. Simulator-Ergebnisse, kein Nachweis gegen einen realen PSP (D-03, D-05 offen). Die Oberfläche (Regelanzeige,
 * Bestätigung, Doppelklick) prüft `CancelBooking.test.tsx` gegen einen Ersatzserver; die Browser-Ende-zu-Ende-Läufe sind in dieser Umgebung nicht ausführbar.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SelfCancelFlowTest {
    private lateinit var sim: PspAppSimulator
    private lateinit var s: BookingHttpSupport

    @BeforeAll fun up() { sim = PspAppSimulator.start(seed = 46) }
    @AfterAll fun down() { if (::sim.isInitialized) sim.close() }
    @BeforeEach fun world() { s = BookingHttpSupport() }

    private val server get() = s.env.server
    private val payments: Payments get() = server.ctx.getBean(Payments::class.java)
    private val coupling: PaymentBookingCoupling get() = server.ctx.getBean(PaymentBookingCoupling::class.java)
    private fun runner() = OperationRunner(
        server.ctx.getBean(TenantTransactions::class.java), payments.handlers(PspPaymentGateway(PspAdapter.http("simpsp", URI(sim.url), Duration.ofSeconds(10)))), server.ctx.getBean(Outbox::class.java),
        java.time.Clock.systemUTC(), OperationConfig(reconcileDelay = Duration.ZERO), "worker-selfcancel",
    )
    private fun drain(r: OperationRunner = runner(), max: Int = 12) { var n = 0; while (n < max && r.runNext(s.tenant) != null) n++ }
    private fun effects(op: String) = sim.get("/control/log")["calls"].values().count { it["operation"].asString() == op && it["effect_applied"].asBoolean() }

    private fun pay(id: String, token: String, gross: Long) =
        server.send("POST", "/v1/bookings/$id/payment", """{"payment_method_ref":"pm_ok","amount":{"amount_minor":$gross,"currency":"EUR","exponent":2}}""", s.env.headers(token, extra = mapOf("Idempotency-Key" to "pay-" + UUID.randomUUID())))

    private class Checkout(val h: BookingHttpSupport.Held, val id: String)

    private fun confirmed(h: BookingHttpSupport.Held = s.held()): Checkout { val r = s.confirm(h); check(r.status == 201) { r.text }; return Checkout(h, r.json["id"].asString()) }
    private fun booking(c: Checkout) = s.get(c.id, c.h.account.token).json
    private fun paid(c: Checkout) { check(pay(c.id, c.h.account.token, c.h.grossMinor).status == 202); drain() }
    private fun captured(c: Checkout) { paid(c); coupling.runTenant(s.tenant); drain(); check(booking(c)["payment"]["status"].asString() == "CAPTURED") }
    private fun cancel(c: Checkout, token: String = c.h.account.token, key: String = "cancel-" + UUID.randomUUID(), ifMatch: String? = booking(c)["revision"].asString()) = s.cancel(c.id, token, "PASSENGER_REQUEST", ifMatch, key)

    /** Automatischer Konsistenzvergleich (AK 3): Status in der Oberfläche, `booking.state`, Historie, Kapazität (Hold), `payment_intent` und `refund_case` widersprechen sich nicht. */
    private fun assertKonsistent(c: Checkout, erwartetBuchung: String = "CANCELED") {
        val api = booking(c)
        assertEquals(erwartetBuchung, api["state"].asString()); assertEquals(erwartetBuchung, s.env.string("SELECT state FROM taktovia.booking WHERE id = ?::uuid", c.id).single())
        val hist = s.env.get("/v1/bookings/${c.id}/history", c.h.account.token).json["items"].values().asSequence().map { it["to_state"].asString() }.toList()
        assertEquals(erwartetBuchung, hist.last(), "Historie endet im Zustand der Buchung")
        assertEquals(if (erwartetBuchung == "CANCELED") "RELEASED" else "CONSUMED", s.env.string("SELECT state FROM taktovia.capacity_hold WHERE id = ?::uuid", c.h.holdId).single(), "Kapazität frei bzw. gebunden")
        val pay = api["payment"]
        if (pay != null) {
            // UI-Projektion (Kap. 05 §5.9): beauftragte Erstattung zeigt noch den Einzug (`refund_pending`), unbestimmte Zustände erscheinen als UNKNOWN
            val ui = s.env.string("SELECT state FROM taktovia.payment_intent WHERE booking_id = ?::uuid", c.id).single().let { if (it == "REFUND_PENDING") "CAPTURED" else if (it == "CAPTURE_UNKNOWN" || it == "RECONCILING") "UNKNOWN" else it }
            assertEquals(pay["status"].asString(), ui, "Zahlstatus der Oberfläche = payment_intent")
            val cases = s.env.string("SELECT state || ':' || refunded_minor FROM taktovia.refund_case WHERE case_key = ?::uuid", c.id)
            assertTrue(cases.size <= 1, "höchstens ein Erstattungsfall je Storno")
            pay["refund"]?.let { r -> assertEquals(r["status"].asString() + ":" + r["refunded"]["amount_minor"].asLong(), cases.single(), "Erstattungshinweis = refund_case") }
        }
    }

    /** Angebot eines zweiten Fahrgasts an denselben Umlauf binden (Testaufbau wie [OfferHttpSupport.bind], nur ohne neuen Umlauf; der Inhaltswächter wird dafür kurz abgeschaltet). */
    private fun bindTo(issued: OfferHttpSupport.Issued, run: UUID) {
        s.env.exec("ALTER TABLE taktovia.ride_offer DISABLE TRIGGER ride_offer_guard")
        try { s.env.exec("UPDATE taktovia.ride_offer SET run_id = ?, plan_revision = 1, interval_from = 0, interval_to = 2 WHERE id = ?::uuid", run, issued.id) }
        finally { s.env.exec("ALTER TABLE taktovia.ride_offer ENABLE TRIGGER ride_offer_guard") }
    }

    @Test
    fun storno_void_und_erstattung_hinweis_je_zahlungsstand() {
        // nur autorisiert: Void, Hinweis „Autorisierung wird aufgehoben“, nie ein Einzug
        val a = confirmed(); paid(a)
        val r1 = cancel(a)
        assertEquals(200, r1.status, r1.text); assertEquals("storniert", r1.json["bereich"].asString())
        assertEquals("VOID_REQUESTED", r1.json["refund_hint"]["status"].asString()); drain()
        assertEquals("VOIDED", booking(a)["payment"]["status"].asString()); assertKonsistent(a)
        // eingezogen: Erstattung „beauftragt“ (nie „erstattet“ vor der PSP-Bestätigung), danach bestätigt
        val b = confirmed(); captured(b)
        val r2 = cancel(b)
        assertEquals("REQUESTED", r2.json["refund_hint"]["status"].asString()); assertEquals("REQUESTED", r2.json["payment"]["refund"]["status"].asString()); assertEquals(0, r2.json["payment"]["refunded"]["amount_minor"].asLong())
        assertKonsistent(b)
        drain()
        assertEquals("REFUNDED", booking(b)["payment"]["status"].asString()); assertEquals("CONFIRMED", booking(b)["payment"]["refund"]["status"].asString()); assertKonsistent(b)
    }

    @Test
    fun storno_und_neubuchung_konsistent() {
        val first = confirmed(s.held(passengers = 2, seats = 2)); paid(first)         // der Wagen (2 Sitze) ist voll
        val account2 = s.newAccount(); val issued2 = s.offers.issue(account2.token, 3, 2); bindTo(issued2, first.h.run)
        val gross2 = issued2.offer["gross"]["amount_minor"].asLong()
        val full = s.offers.hold(issued2.id, account2.token, issued2.offer["revision"].asString(), gross2)
        assertEquals(409, full.status, full.text); assertEquals("CAPACITY_UNAVAILABLE", full.json["code"].asString())
        // Storno durch den Fahrgast: Status, Historie, Kapazität, Zahlung und Erstattungshinweis stimmen überein
        val r = cancel(first); assertEquals(200, r.status, r.text); drain(); assertKonsistent(first)
        // der Platz ist wieder buchbar: die zweite Testbuchung erhält dieselbe Kapazität
        val again = s.offers.hold(issued2.id, account2.token, s.offers.get(issued2, account2.token).json["revision"].asString(), gross2)
        assertEquals(201, again.status, again.text)
        val second = s.confirm(BookingHttpSupport.Held(account2, issued2.id, again.json["id"].asString(), again.header("ETag")!!.trim('"'), gross2, first.h.run, issued2))
        assertEquals(201, second.status, second.text); assertEquals("CONFIRMING", second.json["state"].asString())
        assertKonsistent(first)
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.capacity_hold WHERE run_id = ? AND state = 'CONSUMED'", first.h.run), "nur die zweite Buchung belegt den Wagen")
    }

    @Test
    fun storno_zwei_tabs_parallel() {
        // zwei Tabs mit verschiedenen Schlüsseln: genau ein Erfolg, der andere wird abgewiesen (veraltete Revision bzw. Zustand), eine Erstattung, einmal Kapazität frei
        val c = confirmed().also { captured(it) }
        val rev = booking(c)["revision"].asString(); val refunds = effects("refund")
        val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        val results = (1..2).map { pool.submit<Int> { go.await(); cancel(c, ifMatch = rev).status } }
        go.countDown(); val codes = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, codes.count { it == 200 }, codes.toString()); assertTrue(codes.single { it != 200 } in setOf(409, 412), codes.toString())
        drain(); assertEquals(refunds + 1, effects("refund"), "genau eine Erstattung"); assertKonsistent(c)
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.capacity_hold WHERE id = ?::uuid AND state = 'RELEASED'", c.h.holdId))
        // Doppelklick (derselbe Schlüssel zweimal): dasselbe Ergebnis, kein zweiter Storno
        val d = confirmed(); paid(d); val key = "dbl-" + UUID.randomUUID(); val rev2 = booking(d)["revision"].asString()
        val r1 = cancel(d, key = key, ifMatch = rev2); val r2 = cancel(d, key = key, ifMatch = rev2)
        assertEquals(200, r1.status, r1.text); assertEquals(200, r2.status, r2.text); assertEquals(r1.json["revision"].asLong(), r2.json["revision"].asLong())
        val voids = effects("void"); drain(); assertEquals(voids + 1, effects("void"), "genau ein Void"); assertKonsistent(d)
    }

    @Test
    fun storno_ausserhalb_frist_nicht_angeboten_und_abgewiesen() {
        val c = confirmed(); paid(c)
        assertTrue(booking(c)["cancellation"]["allowed"].asBoolean(), "innerhalb der Frist wird der Storno angeboten")
        // Testaufbau: das zugesagte Abholfenster rückt in die Frist (Planungsdefault 60 min); die Unveränderlichkeitswächter der Zusage werden dafür kurz abgeschaltet
        for (t in listOf("booking_promise_snapshot_immutable", "booking_guard")) s.env.exec("ALTER TABLE taktovia.booking DISABLE TRIGGER $t")
        try { s.env.exec("UPDATE taktovia.booking SET promise_snapshot = jsonb_set(promise_snapshot, '{pickup_earliest}', to_jsonb(to_char(now() + interval '30 minutes', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'))) WHERE id = ?::uuid", c.id) }
        finally { for (t in listOf("booking_promise_snapshot_immutable", "booking_guard")) s.env.exec("ALTER TABLE taktovia.booking ENABLE TRIGGER $t") }
        val b = booking(c)
        assertFalse(b["cancellation"]["allowed"].asBoolean(), "außerhalb der Frist wird der Storno nicht angeboten"); assertEquals(60, b["cancellation"]["deadline_min"].asInt())
        val r = cancel(c)
        assertEquals(409, r.status, r.text); assertEquals("CANCEL_DEADLINE_EXPIRED", r.json["code"].asString())
        assertEquals("CONFIRMING", booking(c)["state"].asString()); assertEquals("AUTHORIZED", booking(c)["payment"]["status"].asString())
        assertEquals(0, s.count("SELECT count(*) FROM taktovia.refund_case WHERE case_key = ?::uuid", c.id))
        // bereits storniert: nicht angeboten und abgewiesen
        val d = confirmed(); assertEquals(200, cancel(d).status)
        assertFalse(booking(d)["cancellation"]["allowed"].asBoolean()); assertEquals(409, cancel(d, ifMatch = booking(d)["revision"].asString()).status)
    }

    @Test
    fun fremde_buchung_404_und_header_pflicht() {
        val c = confirmed(); val rev = booking(c)["revision"].asString(); val other = s.newAccount()
        assertEquals(404, cancel(c, token = other.token, ifMatch = rev).status, "fremde Buchung")
        assertEquals(404, cancel(c, token = BookingHttpSupport().newAccount().token, ifMatch = rev).status, "fremder Mandant")
        assertEquals(428, cancel(c, ifMatch = null).status, "If-Match fehlt")
        assertEquals(412, cancel(c, ifMatch = "9").status, "veralteter ETag")
        val noKey = server.send("POST", "/v1/bookings/${c.id}/cancel", """{"reason":"PASSENGER_REQUEST"}""", s.env.headers(c.h.account.token, extra = mapOf("If-Match" to "\"$rev\"")))
        assertTrue(noKey.status in 400..499 && noKey.status != 404, "Idempotency-Key fehlt: ${noKey.status}")
        assertEquals("CONFIRMING", booking(c)["state"].asString(), "nichts hat sich geändert")
        // Fahrgäste dürfen nur den Kundenstorno setzen
        assertEquals(403, s.cancel(c.id, c.h.account.token, "OPERATOR_CANCELLATION", rev).status)
    }

    @Test
    fun storno_parallel_zum_zahlungsabgleich_keine_zweite_erstattung() {
        // Storno einer eingezogenen Buchung parallel zu Operations-Worker und Kopplung (die Abgleichswege, die auch Webhooks auslösen): genau eine Erstattung, refunded wächst nie über den Einzug
        val c = confirmed(); captured(c); val refunds = effects("refund"); val rev = booking(c)["revision"].asString()
        val pool = Executors.newFixedThreadPool(3); val go = CountDownLatch(1)
        val cancelF = pool.submit<Int> { go.await(); cancel(c, ifMatch = rev).status }
        val workers = (1..2).map { pool.submit { go.await(); repeat(3) { drain(max = 6); coupling.runTenant(s.tenant) } } }
        go.countDown(); assertEquals(200, cancelF.get(60, TimeUnit.SECONDS)); workers.forEach { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        drain(); drain()
        assertEquals(refunds + 1, effects("refund"), "genau eine Erstattung"); assertEquals(c.h.grossMinor, booking(c)["payment"]["refunded"]["amount_minor"].asLong()); assertKonsistent(c)
    }
}
