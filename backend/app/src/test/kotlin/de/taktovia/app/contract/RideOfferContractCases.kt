package de.taktovia.app.contract

import de.taktovia.app.booking.RideOfferHttpWorld
import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle der Anfrageerfassung (WP-M2-11): `createRideOffer` positiv (200 mit Zeitfenster) und Fehlerfall (422). */
internal object RideOfferContractCases {
    private fun send(w: RideOfferHttpWorld, body: String) =
        ContractRequest("POST", "/v1/ride-offers", body, w.w.env.headers(w.w.passenger, extra = mapOf("Idempotency-Key" to "ct-" + UUID.randomUUID())))

    val cases: List<ContractCase> = listOf(
        ContractCase("createRideOffer",
            200, { val w = RideOfferHttpWorld(); send(w, w.body(w.later(3))) },
            422, { val w = RideOfferHttpWorld(); send(w, w.body(w.later(3), passengers = 0)) }, server = { IdentityEnv.server }),
    )
}
