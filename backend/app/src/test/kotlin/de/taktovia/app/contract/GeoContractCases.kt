package de.taktovia.app.contract

import de.taktovia.app.geo.GeoWorld
import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle des Clusters Dienste/Geo (WP-M2-03 bis -06): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung. */
internal object GeoContractCases {
    private fun hours(reason: String = "Fahrplan") =
        """{"traffic_product":"AREA_ON_DEMAND","weekly_hours":[{"weekdays":["MON","TUE"],"from":"06:00","to":"22:00"}],"exceptions":[],"valid_from":"2026-01-01T00:00:00+01:00","valid_to":null,"reason":"$reason"}"""

    private fun h(w: GeoWorld, token: String = w.admin, ifMatch: String? = null) =
        w.env.headers(token, extra = w.idem() + (if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap()))
    private fun hg(w: GeoWorld, token: String = w.admin) = w.env.headers(token)

    val cases: List<ContractCase> = listOf(
        ContractCase("getServiceOperatingHours",
            200, { val w = GeoWorld(); ContractRequest("GET", "/v1/services/${w.service()}/operating-hours", headers = hg(w)) },
            404, { val w = GeoWorld(); ContractRequest("GET", "/v1/services/${UUID.randomUUID()}/operating-hours", headers = hg(w)) }, server = { IdentityEnv.server }),
        ContractCase("putServiceOperatingHours",
            200, { val w = GeoWorld(); ContractRequest("PUT", "/v1/services/${w.service()}/operating-hours", hours(), h(w, ifMatch = "0")) },
            412, { val w = GeoWorld(); ContractRequest("PUT", "/v1/services/${w.service()}/operating-hours", hours(), h(w, ifMatch = "7")) }, server = { IdentityEnv.server }),
        ContractCase("listServiceOperatingHoursRevisions",
            200, { val w = GeoWorld(); val s = w.service(); w.put("/v1/services/$s/operating-hours", hours(), "0"); ContractRequest("GET", "/v1/services/$s/operating-hours/revisions?limit=10", headers = hg(w)) },
            404, { val w = GeoWorld(); ContractRequest("GET", "/v1/services/${UUID.randomUUID()}/operating-hours/revisions", headers = hg(w)) }, server = { IdentityEnv.server }),
        ContractCase("getServiceAvailability",
            200, { val w = GeoWorld(); ContractRequest("GET", "/v1/services/${w.service()}/availability", headers = hg(w, w.passenger)) },
            404, { val w = GeoWorld(); ContractRequest("GET", "/v1/services/${UUID.randomUUID()}/availability", headers = hg(w, w.passenger)) }, server = { IdentityEnv.server }),
    )
}
