package de.taktovia.app.contract

import de.taktovia.app.booking.OfferHttpSupport
import de.taktovia.app.booking.RideOfferHttpWorld
import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/**
 * Vertragsfälle zu Angebotsabruf, Hold und Freigabe (WP-M2-21/-22, eigene Datei des Clusters): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung.
 * Die Bindung des Angebots an eine Planrevision entsteht im Testaufbau ([OfferHttpSupport.bind]).
 */
internal object OfferHoldContractCases {
    private fun headers(s: OfferHttpSupport, token: String, ifMatch: String? = null, idem: Boolean = true) =
        s.world.w.env.headers(token, extra = (if (idem) mapOf("Idempotency-Key" to "ct-" + UUID.randomUUID()) else emptyMap()) + (if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap()))
    private const val HOLD = """{"max_gross":{"amount_minor":900,"currency":"EUR","exponent":2}}"""

    val cases: List<ContractCase> = listOf(
        ContractCase("getRideOffer",
            200, { val s = OfferHttpSupport(RideOfferHttpWorld()); val i = s.issue(); ContractRequest("GET", "/v1/ride-offers/${i.id}", null, headers(s, i.token, idem = false)) },
            404, { val s = OfferHttpSupport(RideOfferHttpWorld()); ContractRequest("GET", "/v1/ride-offers/${UUID.randomUUID()}", null, headers(s, s.newPassenger(), idem = false)) }, server = { IdentityEnv.server }),
        ContractCase("holdRideOffer",
            201, { val s = OfferHttpSupport(RideOfferHttpWorld()); val i = s.issue(); s.bind(i); ContractRequest("POST", "/v1/ride-offers/${i.id}/hold", HOLD, headers(s, i.token, "1")) },
            428, { val s = OfferHttpSupport(RideOfferHttpWorld()); val i = s.issue(); s.bind(i); ContractRequest("POST", "/v1/ride-offers/${i.id}/hold", HOLD, headers(s, i.token)) }, server = { IdentityEnv.server }),
        ContractCase("releaseHold",
            200, { val s = OfferHttpSupport(RideOfferHttpWorld()); val i = s.issue(); s.bind(i); check(s.hold(i.id, i.token, "1").status == 201); ContractRequest("POST", "/v1/ride-offers/${i.id}/hold/release", null, headers(s, i.token, "2")) },
            404, { val s = OfferHttpSupport(RideOfferHttpWorld()); ContractRequest("POST", "/v1/ride-offers/${UUID.randomUUID()}/hold/release", null, headers(s, s.newPassenger(), "1")) }, server = { IdentityEnv.server }),
    )
}
