package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle der Dienstkonfiguration (WP-M1-25): wirksame Konfiguration lesen und Regeltabelle der Angebotsmodelle. */
internal object ServiceConfigContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())

    private class World {
        val env = IdentityEnv
        val tenant = env.tenant("Vertrag-Dienstkonfig-" + UUID.randomUUID())
        val admin = UUID.randomUUID(); val passenger = UUID.randomUUID()
        init { env.grant(tenant, admin, "tenant_admin"); env.grant(tenant, passenger, "passenger") }
        val adminToken = env.token(admin); val passengerToken = env.token(passenger)
        fun service(): String {
            val le = env.post("/v1/legal-entities", """{"name":"LE","country":"DE"}""", adminToken, extra = idem()).json["id"].asString()
            val op = env.post("/v1/organizations", """{"legal_entity_id":"$le","name":"Betreiber","federation_ref":"f-${UUID.randomUUID()}","roles":["TRANSPORT_COMPANY"]}""", adminToken, extra = idem()).json["id"].asString()
            return env.post("/v1/services", """{"operator_id":"$op","name":"Rufbus","timezone":"Europe/Berlin"}""", adminToken, extra = idem()).json["id"].asString()
        }
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("getServiceConfiguration",
            200, { val w = World(); ContractRequest("GET", "/v1/services/${w.service()}/configuration", headers = w.env.headers(w.adminToken)) },
            404, { val w = World(); ContractRequest("GET", "/v1/services/${UUID.randomUUID()}/configuration", headers = w.env.headers(w.adminToken)) }, server = { IdentityEnv.server }),
        ContractCase("listServiceOfferModels",
            200, { val w = World(); ContractRequest("GET", "/v1/service-offer-models", headers = w.env.headers(w.adminToken)) },
            403, { val w = World(); ContractRequest("GET", "/v1/service-offer-models", headers = w.env.headers(w.passengerToken)) }, server = { IdentityEnv.server }),
    )
}
