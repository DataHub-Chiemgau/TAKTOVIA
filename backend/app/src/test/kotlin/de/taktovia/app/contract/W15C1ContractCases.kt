package de.taktovia.app.contract

import de.taktovia.app.fleet.OutageHttpScene
import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle des Clusters wave15-c1 (WP-M3-54 Störungsablauf): je neuer Operation ein positiver Fall und ein Fehlerfall. Eigene Klasse je Cluster. */
internal object W15C1ContractCases {
    private fun hdr(sc: OutageHttpScene, token: String = sc.dispatcher, ifMatch: String? = null) =
        sc.env.headers(token, extra = mapOf("Idempotency-Key" to "ct-" + UUID.randomUUID()) + (if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap()))

    private fun first(sc: OutageHttpScene) = sc.outage()["bookings"][0]
    private fun rebookBody(sc: OutageHttpScene): String {
        val p = first(sc)["proposals"][0]
        return """{"proposal_id":"${p["id"].asString()}","reason_code":"VEHICLE_OUTAGE","accept_window_effect":${p["window_effect"].asString() == "OUTSIDE_WINDOW"}}"""
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("checkOperatorIncidentOutage",
            200, { val sc = OutageHttpScene(); ContractRequest("POST", "/v1/operator/incidents/${sc.incident}/outage/check", null, hdr(sc)) },
            404, { val sc = OutageHttpScene(); ContractRequest("POST", "/v1/operator/incidents/${UUID.randomUUID()}/outage/check", null, hdr(sc)) }, server = { IdentityEnv.server }),
        ContractCase("rebookOutageBooking",
            200, { val sc = OutageHttpScene(); ContractRequest("POST", "/v1/operator/incidents/${sc.incident}/outage/bookings/${sc.bookingId}/rebook", rebookBody(sc), hdr(sc, ifMatch = first(sc)["revision"].asString())) },
            428, { val sc = OutageHttpScene(); ContractRequest("POST", "/v1/operator/incidents/${sc.incident}/outage/bookings/${sc.bookingId}/rebook", rebookBody(sc), hdr(sc)) }, server = { IdentityEnv.server }),
        ContractCase("cancelOutageBooking",
            200, { val sc = OutageHttpScene(); ContractRequest("POST", "/v1/operator/incidents/${sc.incident}/outage/bookings/${sc.bookingId}/cancel", """{"reason_code":"VEHICLE_OUTAGE"}""", hdr(sc, ifMatch = first(sc)["revision"].asString())) },
            403, { val sc = OutageHttpScene(); ContractRequest("POST", "/v1/operator/incidents/${sc.incident}/outage/bookings/${sc.bookingId}/cancel", """{"reason_code":"VEHICLE_OUTAGE"}""",
                hdr(sc, token = sc.s.operator(listOf(sc.service), "callcenter"), ifMatch = first(sc)["revision"].asString())) }, server = { IdentityEnv.server }),
        ContractCase("informOutageBooking",
            200, { val sc = OutageHttpScene(); val rev = first(sc)["revision"].asString().toLong()
                check(sc.post("${sc.path}/rebook", rebookBody(sc), "$rev").status == 200); ContractRequest("POST", "/v1/operator/incidents/${sc.incident}/outage/bookings/${sc.bookingId}/inform", null, hdr(sc, ifMatch = "${rev + 1}")) },
            409, { val sc = OutageHttpScene(); ContractRequest("POST", "/v1/operator/incidents/${sc.incident}/outage/bookings/${sc.bookingId}/inform", null, hdr(sc, ifMatch = first(sc)["revision"].asString())) }, server = { IdentityEnv.server }),
    )
}
