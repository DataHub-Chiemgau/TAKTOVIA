package de.taktovia.app.contract

import de.taktovia.app.booking.BookingHttpSupport
import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/**
 * Vertragsfälle des Clusters wave14-c5 (WP-M3-37, -39, -42): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung, geprüft gegen `api/operator-bookings.yaml` und
 * `api/booking.yaml`. Eigene Datei des Clusters; in [M1ContractCases] steht nur die `cases`-Verkettung.
 */
internal object W14C5ContractCases {
    private fun headers(s: BookingHttpSupport, token: String, extra: Map<String, String> = emptyMap()) = s.env.headers(token, extra = extra)

    /** Leitstellenfluss: Angebot für den Fahrgast (DELEGATED), an einen Umlauf gebunden, gehalten; liefert Hold, ETag und Betrag. */
    private class Flow(val holdId: String, val etag: String, val gross: Long)

    private fun held(s: BookingHttpSupport, op: String, passengerId: String): Flow {
        val extra = ""","booking_subject":{"kind":"DELEGATED","passenger_id":"$passengerId"}"""
        val r = s.world.request(s.world.body(s.world.later(3), extra = extra), op)
        check(r.status == 200) { r.text }
        val offer = r.json["offers"][0]; val issued = de.taktovia.app.booking.OfferHttpSupport.Issued(op, r, offer)
        s.offers.bind(issued, 4)
        val gross = offer["gross"]["amount_minor"].asLong()
        val h = s.offers.hold(issued.id, op, offer["revision"].asString(), gross); check(h.status == 201) { h.text }
        return Flow(h.json["id"].asString(), h.header("ETag")!!.trim('"'), gross)
    }

    private fun changeBody(f: Flow, reason: String = "PASSENGER_REQUEST") =
        """{"new_hold_id":"${f.holdId}","offer_revision":${f.etag},"max_gross":{"amount_minor":${f.gross},"currency":"EUR","exponent":2},"reason_code":"$reason"}"""

    private class Booked(val s: BookingHttpSupport, val op: String, val passengerId: String, val id: String, val revision: String)

    private fun booked(): Booked {
        val s = BookingHttpSupport(); val acc = s.newAccount()
        val pid = s.env.string("SELECT id::text FROM taktovia.passenger_private WHERE idp_subject = ?", acc.subject.toString()).single()
        val op = s.operator(listOf(UUID.fromString(s.world.service))); val f = held(s, op, pid)
        val b = s.env.server.send("POST", "/v1/bookings", """{"hold_id":"${f.holdId}","max_gross":{"amount_minor":${f.gross},"currency":"EUR","exponent":2},"reason_code":"PHONE_REQUEST"}""",
            headers(s, op, mapOf("Idempotency-Key" to "ct-" + UUID.randomUUID(), "If-Match" to "\"${f.etag}\"")))
        check(b.status == 201) { b.text }
        return Booked(s, op, pid, b.json["id"].asString(), b.json["revision"].asString())
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("searchOperatorBookings",
            200, {
                val s = BookingHttpSupport(); val h = s.held(); val b = s.confirm(h).json
                ContractRequest("GET", "/v1/operator/bookings?booking_number=${b["booking_number"].asString()}", null, headers(s, s.operator(listOf(UUID.fromString(b["service_id"].asString())))))
            },
            403, { val s = BookingHttpSupport(); ContractRequest("GET", "/v1/operator/bookings", null, headers(s, s.newAccount().token)) }, server = { IdentityEnv.server }),
        ContractCase("changeBooking",
            200, { val b = booked(); val f = held(b.s, b.op, b.passengerId); ContractRequest("POST", "/v1/bookings/${b.id}/changes", changeBody(f), headers(b.s, b.op, mapOf("Idempotency-Key" to "ct-" + UUID.randomUUID(), "If-Match" to "\"${b.revision}\""))) },
            428, { val b = booked(); val f = held(b.s, b.op, b.passengerId); ContractRequest("POST", "/v1/bookings/${b.id}/changes", changeBody(f), headers(b.s, b.op, mapOf("Idempotency-Key" to "ct-" + UUID.randomUUID()))) }, server = { IdentityEnv.server }),
    )
}
