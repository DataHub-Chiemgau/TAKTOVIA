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

    private fun square(x: Double) = """{"type":"MultiPolygon","coordinates":[[[[$x,51.0],[${"%.6f".format(java.util.Locale.ROOT, x + 0.1)},51.0],[${"%.6f".format(java.util.Locale.ROOT, x + 0.1)},51.1],[$x,51.1],[$x,51.0]]]]}"""
    private fun createArea(w: GeoWorld, service: String, x: Double = 13.7) =
        w.post("/v1/service-areas", """{"service_id":"$service","area":${square(x)},"valid_from":"2026-12-01T00:00:00+01:00","valid_to":null}""").json["id"].asString()

    private fun stopBody(name: String = "Marktplatz", lat: Int = 51) =
        """{"name":"$name","point":{"latitude":$lat,"longitude":13.74},"accessibility":{"schema_version":"1.0","step_free":"YES","wheelchair_boarding":"UNVERIFIED"},"traffic_products":["AREA_ON_DEMAND"],"active":true,"valid_from":"2026-01-01T00:00:00+01:00"}"""
    private fun createStop(w: GeoWorld) = w.post("/v1/stops", stopBody()).json["stop_id"].asString()

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
        ContractCase("createServiceArea",
            201, { val w = GeoWorld(); ContractRequest("POST", "/v1/service-areas", """{"service_id":"${w.service()}","area":${square(13.7)},"valid_from":"2026-12-01T00:00:00+01:00","valid_to":null}""", h(w)) },
            422, { val w = GeoWorld(); ContractRequest("POST", "/v1/service-areas", """{"service_id":"${w.service()}","area":{"type":"MultiPolygon","coordinates":[[[[0,0],[1,1],[1,0],[0,1],[0,0]]]]},"valid_from":"2026-12-01T00:00:00+01:00"}""", h(w)) }, server = { IdentityEnv.server }),
        ContractCase("listServiceAreas",
            200, { val w = GeoWorld(); val s = w.service(); createArea(w, s); ContractRequest("GET", "/v1/service-areas?service_id=$s", headers = hg(w)) },
            403, { val w = GeoWorld(); ContractRequest("GET", "/v1/service-areas", headers = hg(w, w.env.token(UUID.randomUUID()))) }, server = { IdentityEnv.server }),
        ContractCase("getServiceArea",
            200, { val w = GeoWorld(); val a = createArea(w, w.service()); ContractRequest("GET", "/v1/service-areas/$a", headers = hg(w)) },
            404, { val w = GeoWorld(); ContractRequest("GET", "/v1/service-areas/${UUID.randomUUID()}", headers = hg(w)) }, server = { IdentityEnv.server }),
        ContractCase("updateServiceArea",
            200, { val w = GeoWorld(); val a = createArea(w, w.service()); ContractRequest("PUT", "/v1/service-areas/$a", """{"area":${square(13.8)},"valid_from":"2027-01-01T00:00:00+01:00","valid_to":null}""", h(w, ifMatch = "1")) },
            412, { val w = GeoWorld(); val a = createArea(w, w.service()); ContractRequest("PUT", "/v1/service-areas/$a", """{"area":${square(13.8)},"valid_from":"2027-01-01T00:00:00+01:00","valid_to":null}""", h(w, ifMatch = "9")) }, server = { IdentityEnv.server }),
        ContractCase("createStop",
            201, { val w = GeoWorld(); ContractRequest("POST", "/v1/stops", stopBody(), h(w)) },
            422, { val w = GeoWorld(); ContractRequest("POST", "/v1/stops", stopBody(lat = 91), h(w)) }, server = { IdentityEnv.server }),
        ContractCase("listStops",
            200, { val w = GeoWorld(); createStop(w); ContractRequest("GET", "/v1/stops?active=true&near_latitude=51.0&near_longitude=13.74&near_radius_m=500", headers = hg(w, w.passenger)) },
            403, { val w = GeoWorld(); ContractRequest("GET", "/v1/stops", headers = hg(w, w.env.token(UUID.randomUUID()))) }, server = { IdentityEnv.server }),
        ContractCase("getStop",
            200, { val w = GeoWorld(); val s = createStop(w); ContractRequest("GET", "/v1/stops/$s", headers = hg(w)) },
            404, { val w = GeoWorld(); ContractRequest("GET", "/v1/stops/${UUID.randomUUID()}", headers = hg(w)) }, server = { IdentityEnv.server }),
        ContractCase("updateStop",
            200, { val w = GeoWorld(); val s = createStop(w); ContractRequest("PUT", "/v1/stops/$s", stopBody("Neu").replace("\"valid_from\":\"2026-01-01", "\"valid_from\":\"2027-01-01"), h(w, ifMatch = "1")) },
            428, { val w = GeoWorld(); val s = createStop(w); ContractRequest("PUT", "/v1/stops/$s", stopBody("Neu"), h(w)) }, server = { IdentityEnv.server }),
    )
}
