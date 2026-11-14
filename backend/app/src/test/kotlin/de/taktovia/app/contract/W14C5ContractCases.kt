package de.taktovia.app.contract

import de.taktovia.app.booking.BookingHttpSupport
import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/**
 * Vertragsfälle des Clusters wave14-c5 (WP-M3-37, -39, -42): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung, geprüft gegen `api/operator-bookings.yaml` und
 * `api/booking.yaml`. Eigene Datei des Clusters; in [M1ContractCases] steht nur die `cases`-Verkettung.
 */
internal object W14C5ContractCases {
    private fun headers(s: BookingHttpSupport, token: String) = s.env.headers(token)

    val cases: List<ContractCase> = listOf(
        ContractCase("searchOperatorBookings",
            200, {
                val s = BookingHttpSupport(); val h = s.held(); val b = s.confirm(h).json
                ContractRequest("GET", "/v1/operator/bookings?booking_number=${b["booking_number"].asString()}", null, headers(s, s.operator(listOf(UUID.fromString(b["service_id"].asString())))))
            },
            403, { val s = BookingHttpSupport(); ContractRequest("GET", "/v1/operator/bookings", null, headers(s, s.newAccount().token)) }, server = { IdentityEnv.server }),
    )
}
