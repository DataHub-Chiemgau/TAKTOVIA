package de.taktovia.app.contract

import de.taktovia.app.geo.GeoWorld
import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle der Tarifpflege (WP-M2-07, eigene Datei des Clusters): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung. */
internal object TariffContractCases {
    private fun body(service: String, from: String = "2026-12-01T00:00:00+01:00") =
        """{"service_id":"$service","tariff_class":"STANDARD","valid_from":"$from","valid_to":null,"currency":"EUR","reason":"Wintertarif","rules":[{"rule_type":"FESTPREIS","amount_minor":450,"tax_rate":"0.07","tax_rule_revision":"DE-2026-1"}]}"""
    private const val UPDATE = """{"valid_from":"2026-12-01T00:00:00+01:00","valid_to":null,"reason":"Korrigiert","rules":[{"rule_type":"FESTPREIS","amount_minor":500,"tax_rate":"0.07","tax_rule_revision":"DE-2026-1"}]}"""

    private fun h(w: GeoWorld, token: String = w.admin, ifMatch: String? = null) =
        w.env.headers(token, extra = w.idem() + (if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap()))
    private fun hg(w: GeoWorld, token: String = w.admin) = w.env.headers(token)

    private fun draft(w: GeoWorld, service: String = w.service()) = w.post("/v1/tariffs", body(service)).json["id"].asString()
    private fun reviewed(w: GeoWorld): String { val t = draft(w); w.post("/v1/tariffs/$t/review", "{}", w.planner, "1"); return t }
    private fun published(w: GeoWorld, service: String = w.service()): String { val t = draft(w, service); w.post("/v1/tariffs/$t/review", "{}", w.planner, "1"); w.post("/v1/tariffs/$t/publish", "{}", w.planner, "2"); return t }

    val cases: List<ContractCase> = listOf(
        ContractCase("createTariffRevision",
            201, { val w = GeoWorld(); ContractRequest("POST", "/v1/tariffs", body(w.service()), h(w)) },
            422, { val w = GeoWorld(); ContractRequest("POST", "/v1/tariffs", body(w.service()).replace("\"tariff_class\":\"STANDARD\",", ""), h(w)) }, server = { IdentityEnv.server }),
        ContractCase("listTariffRevisions",
            200, { val w = GeoWorld(); val s = w.service(); draft(w, s); ContractRequest("GET", "/v1/tariffs?service_id=$s", headers = hg(w)) },
            403, { val w = GeoWorld(); ContractRequest("GET", "/v1/tariffs", headers = hg(w, w.passenger)) }, server = { IdentityEnv.server }),
        ContractCase("getTariffRevision",
            200, { val w = GeoWorld(); val t = draft(w); ContractRequest("GET", "/v1/tariffs/$t", headers = hg(w)) },
            404, { val w = GeoWorld(); ContractRequest("GET", "/v1/tariffs/${UUID.randomUUID()}", headers = hg(w)) }, server = { IdentityEnv.server }),
        ContractCase("updateTariffRevision",
            200, { val w = GeoWorld(); val t = draft(w); ContractRequest("PUT", "/v1/tariffs/$t", UPDATE, h(w, ifMatch = "1")) },
            412, { val w = GeoWorld(); val t = draft(w); ContractRequest("PUT", "/v1/tariffs/$t", UPDATE, h(w, ifMatch = "9")) }, server = { IdentityEnv.server }),
        ContractCase("reviewTariffRevision",
            200, { val w = GeoWorld(); val t = draft(w); ContractRequest("POST", "/v1/tariffs/$t/review", null, h(w, w.planner, "1")) },
            403, { val w = GeoWorld(); val t = draft(w); ContractRequest("POST", "/v1/tariffs/$t/review", null, h(w, w.admin, "1")) }, server = { IdentityEnv.server }),
        ContractCase("publishTariffRevision",
            200, { val w = GeoWorld(); val t = reviewed(w); ContractRequest("POST", "/v1/tariffs/$t/publish", null, h(w, w.planner, "2")) },
            409, { val w = GeoWorld(); val t = draft(w); ContractRequest("POST", "/v1/tariffs/$t/publish", null, h(w, w.planner, "1")) }, server = { IdentityEnv.server }),
        ContractCase("getEffectiveTariff",
            200, { val w = GeoWorld(); val s = w.service(); published(w, s); ContractRequest("GET", "/v1/services/$s/tariff?at=2027-01-01T00:00:00Z", headers = hg(w)) },
            404, { val w = GeoWorld(); ContractRequest("GET", "/v1/services/${w.service()}/tariff?at=2027-01-01T00:00:00Z", headers = hg(w)) }, server = { IdentityEnv.server }),
    )
}
