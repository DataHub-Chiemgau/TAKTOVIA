package de.taktovia.app.contract

import de.taktovia.app.geo.GeoWorld
import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle der Ortssuche (WP-M2-38): ein positiver Fall und ein Fehlerfall gegen die echte Anwendung. */
internal object PlaceSearchContractCases {
    private fun hg(w: GeoWorld, token: String = w.admin) = w.env.headers(token)

    val cases: List<ContractCase> = listOf(
        ContractCase("searchPlaces",
            200, { val w = GeoWorld(); ContractRequest("GET", "/v1/places?q=Zwinger", headers = hg(w, w.passenger)) },
            422, { val w = GeoWorld(); ContractRequest("GET", "/v1/places?q=a", headers = hg(w, w.passenger)) }, server = { IdentityEnv.server }),
    )
}
