package de.taktovia.app.payment

import de.taktovia.app.booking.BookingHttpSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Zahlungsauswahl und SCA-Rückkehr über die echte Anwendung (WP-M2-30): Fehlerfälle und Objektberechtigung. Der PSP wird hier nie aufgerufen (die Operation bleibt `QUEUED`); die Abläufe
 * mit PSP prüft [PaymentSimulatorFlowTest].
 */
class PaymentCheckoutHttpTest {
    private val s = BookingHttpSupport()
    private fun pay(id: String, token: String, gross: Long, currency: String = "EUR", key: String = "k-" + UUID.randomUUID(), body: String? = null) =
        s.env.server.send("POST", "/v1/bookings/$id/payment", body ?: """{"payment_method_ref":"pm_ok","amount":{"amount_minor":$gross,"currency":"$currency","exponent":2}}""", s.env.headers(token, extra = mapOf("Idempotency-Key" to key)))

    private fun checkout(): Triple<BookingHttpSupport.Held, String, String> { val h = s.held(); val id = s.confirm(h).json["id"].asString(); return Triple(h, id, h.account.token) }

    @Test
    fun abweichender_betrag_ist_requote_und_schreibt_nichts() {
        val (h, id, token) = checkout()
        for (amount in listOf(h.grossMinor + 1, h.grossMinor - 1)) {
            val r = pay(id, token, amount); assertEquals(409, r.status, r.text); assertEquals("REQUOTE_REQUIRED", r.json["code"].asString())
        }
        assertEquals(409, pay(id, token, h.grossMinor, currency = "USD").status)
        assertEquals(0, s.count("SELECT count(*) FROM taktovia.payment_intent WHERE booking_id = ?", UUID.fromString(id)))
        assertEquals(0, s.count("SELECT count(*) FROM taktovia.operation WHERE tenant_id = ? AND kind LIKE '%PAYMENT'", s.tenant.value))
    }

    @Test
    fun beginn_ist_idempotent_und_hoechstens_eine_zahlung_je_buchung() {
        val (h, id, token) = checkout()
        val a = pay(id, token, h.grossMinor); val b = pay(id, token, h.grossMinor, key = "anderer-schluessel")
        assertEquals(202, a.status, a.text); assertEquals(202, b.status, b.text)
        assertEquals(a.json["operation_id"].asString(), b.json["operation_id"].asString(), "dieselbe laufende Operation, kein zweiter Einzug")
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.payment_intent WHERE booking_id = ?", UUID.fromString(id)))
        assertTrue(a.header("Location")!!.endsWith(a.json["operation_id"].asString())); assertEquals("QUEUED", a.json["status"].asString())
        val get = s.get(id, token).json
        assertEquals("CREATED", get["payment"]["status"].asString()); assertEquals("CONFIRMING", get["state"].asString(), "Zahl- und Buchungsstatus getrennt")
    }

    @Test
    fun fremde_buchung_ist_404_und_ungueltige_eingaben_sind_422_bzw_403() {
        val (h, id, token) = checkout()
        val other = s.newAccount()
        assertEquals(404, pay(id, other.token, h.grossMinor).status); assertEquals(404, pay(UUID.randomUUID().toString(), token, h.grossMinor).status)
        assertEquals(404, s.env.server.send("POST", "/v1/bookings/$id/payment/return", null, s.env.headers(other.token, extra = mapOf("Idempotency-Key" to "r-" + UUID.randomUUID()))).status)
        assertEquals(404, s.env.server.send("POST", "/v1/bookings/$id/payment/return", null, s.env.headers(token, extra = mapOf("Idempotency-Key" to "r-" + UUID.randomUUID()))).status, "ohne begonnene Zahlung")
        assertEquals(422, pay(id, token, 0, body = """{"payment_method_ref":"","amount":{"amount_minor":${h.grossMinor},"currency":"EUR","exponent":2}}""").status)
        assertEquals(422, pay(id, token, 0, body = """{"payment_method_ref":"pm_ok","amount":{"amount_minor":${h.grossMinor},"currency":"EUR","exponent":2},"tenant_id":"${s.tenant.value}"}""").status, "kein Mandant im Body")
        val driver = s.operator(listOf(UUID.fromString(s.world.service)), role = "driver")
        assertEquals(403, pay(id, driver, h.grossMinor).status)
        // rohe Kartendaten erreichen die API nie (SEC-008)
        assertEquals(422, pay(id, token, 0, body = """{"payment_method_ref":"4111111111111111","amount":{"amount_minor":${h.grossMinor},"currency":"EUR","exponent":2}}""").status)
    }

    @Test
    fun fremder_mandant_sieht_weder_buchung_noch_zahlung() {
        val (h, id, token) = checkout(); assertEquals(202, pay(id, token, h.grossMinor).status)
        val foreign = BookingHttpSupport()
        val account = foreign.newAccount()
        assertEquals(404, foreign.env.server.send("POST", "/v1/bookings/$id/payment", """{"payment_method_ref":"pm_ok","amount":{"amount_minor":${h.grossMinor},"currency":"EUR","exponent":2}}""",
            foreign.env.headers(account.token, extra = mapOf("Idempotency-Key" to "x-" + UUID.randomUUID()))).status)
    }
}
