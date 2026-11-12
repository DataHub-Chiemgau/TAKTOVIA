package de.taktovia.app.contract

import de.taktovia.app.booking.BookingHttpSupport
import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/**
 * Vertragsfälle der Buchungsendpunkte (WP-M2-23 bis -26, eigene Datei des Clusters wave10-c1): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung.
 * Die Bindung des Angebots an eine Planrevision entsteht im Testaufbau ([BookingHttpSupport.held]).
 */
internal object BookingContractCases {
    private fun headers(s: BookingHttpSupport, token: String, ifMatch: String? = null, idem: Boolean = true) =
        s.env.headers(token, extra = (if (idem) mapOf("Idempotency-Key" to "ct-" + UUID.randomUUID()) else emptyMap()) + (if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap()))
    private fun body(holdId: String, gross: Long) = """{"hold_id":"$holdId","max_gross":{"amount_minor":$gross,"currency":"EUR","exponent":2}}"""

    val cases: List<ContractCase> = listOf(
        ContractCase("confirmBooking",
            201, { val s = BookingHttpSupport(); val h = s.held(); ContractRequest("POST", "/v1/bookings", body(h.holdId, h.grossMinor), headers(s, h.account.token, h.etag)) },
            428, { val s = BookingHttpSupport(); val h = s.held(); ContractRequest("POST", "/v1/bookings", body(h.holdId, h.grossMinor), headers(s, h.account.token)) }, server = { IdentityEnv.server }),
        ContractCase("getBooking",
            200, { val s = BookingHttpSupport(); val h = s.held(); val id = s.confirm(h).json["id"].asString(); ContractRequest("GET", "/v1/bookings/$id", null, headers(s, h.account.token, idem = false)) },
            404, { val s = BookingHttpSupport(); ContractRequest("GET", "/v1/bookings/${UUID.randomUUID()}", null, headers(s, s.newAccount().token, idem = false)) }, server = { IdentityEnv.server }),
        ContractCase("listBookingHistory",
            200, { val s = BookingHttpSupport(); val h = s.held(); val id = s.confirm(h).json["id"].asString(); ContractRequest("GET", "/v1/bookings/$id/history", null, headers(s, h.account.token, idem = false)) },
            404, { val s = BookingHttpSupport(); ContractRequest("GET", "/v1/bookings/${UUID.randomUUID()}/history", null, headers(s, s.newAccount().token, idem = false)) }, server = { IdentityEnv.server }),
        ContractCase("listBookings",
            200, { val s = BookingHttpSupport(); val h = s.held(); check(s.confirm(h).status == 201); ContractRequest("GET", "/v1/bookings?limit=10", null, headers(s, h.account.token, idem = false)) },
            403, { val s = BookingHttpSupport(); val subject = UUID.randomUUID(); s.env.grant(s.tenant, subject, "driver"); ContractRequest("GET", "/v1/bookings", null, headers(s, s.env.token(subject), idem = false)) }, server = { IdentityEnv.server }),
    )
}
