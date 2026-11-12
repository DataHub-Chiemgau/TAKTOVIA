package de.taktovia.app.contract

import de.taktovia.app.booking.BookingHttpSupport
import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle der Zahlungsauswahl im Checkout (WP-M2-30, eigene Datei des Clusters wave11-c2): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung. */
internal object PaymentCheckoutContractCases {
    private fun headers(s: BookingHttpSupport, token: String) = s.env.headers(token, extra = mapOf("Idempotency-Key" to "ct-" + UUID.randomUUID()))
    private fun body(gross: Long) = """{"payment_method_ref":"pm_ok","amount":{"amount_minor":$gross,"currency":"EUR","exponent":2}}"""

    val cases: List<ContractCase> = listOf(
        ContractCase("startBookingPayment",
            202, { val s = BookingHttpSupport(); val h = s.held(); val id = s.confirm(h).json["id"].asString(); ContractRequest("POST", "/v1/bookings/$id/payment", body(h.grossMinor), headers(s, h.account.token)) },
            409, { val s = BookingHttpSupport(); val h = s.held(); val id = s.confirm(h).json["id"].asString(); ContractRequest("POST", "/v1/bookings/$id/payment", body(h.grossMinor + 1), headers(s, h.account.token)) }, server = { IdentityEnv.server }),
        ContractCase("returnBookingPayment",
            202, { val s = BookingHttpSupport(); val h = s.held(); val id = s.confirm(h).json["id"].asString()
                   check(s.env.server.send("POST", "/v1/bookings/$id/payment", body(h.grossMinor), headers(s, h.account.token)).status == 202); ContractRequest("POST", "/v1/bookings/$id/payment/return", null, headers(s, h.account.token)) },
            404, { val s = BookingHttpSupport(); val h = s.held(); val id = s.confirm(h).json["id"].asString(); ContractRequest("POST", "/v1/bookings/$id/payment/return", null, headers(s, h.account.token)) }, server = { IdentityEnv.server }),
    )
}
