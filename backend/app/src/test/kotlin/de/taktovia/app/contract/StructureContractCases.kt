package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle der Organisationsstruktur (WP-M1-09: Regionen, Betriebshöfe, Kostenstellen, Dienste) und der wirksamen Konfiguration (WP-M1-21). */
internal object StructureContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())

    private class World {
        val env = IdentityEnv
        val tenant = env.tenant("Vertrag-Struktur-" + UUID.randomUUID())
        val admin = UUID.randomUUID(); val passenger = UUID.randomUUID()
        init { env.grant(tenant, admin, "tenant_admin"); env.grant(tenant, passenger, "passenger") }
        val adminToken = env.token(admin); val passengerToken = env.token(passenger)
        fun h(extra: Map<String, String> = idem()) = env.headers(adminToken, extra = extra)
        fun id(path: String, body: String) = env.post(path, body, adminToken, extra = idem()).json["id"].asString()
        fun region() = id("/v1/regions", """{"name":"Region"}""")
        fun legalEntity() = id("/v1/legal-entities", """{"name":"LE","country":"DE"}""")
        fun operator() = id("/v1/organizations", """{"legal_entity_id":"${legalEntity()}","name":"Betreiber","federation_ref":"f-${UUID.randomUUID()}","roles":["TRANSPORT_COMPANY"]}""")
        fun depot() = id("/v1/depots", """{"region_id":"${region()}","name":"Hof"}""")
        fun costCenter() = id("/v1/cost-centers", """{"code":"K-${UUID.randomUUID().toString().take(8)}","name":"KST","legal_entity_id":"${legalEntity()}"}""")
        fun service() = id("/v1/services", """{"operator_id":"${operator()}","name":"Rufbus","timezone":"Europe/Berlin"}""")
    }

    private fun ifMatch(v: String) = idem() + ("If-Match" to "\"$v\"")
    private fun list(op: String, path: String) = ContractCase(op,
        200, { val w = World(); ContractRequest("GET", "$path?limit=10", headers = w.h(emptyMap())) },
        403, { val w = World(); ContractRequest("GET", path, headers = w.env.headers(w.passengerToken)) }, server = { IdentityEnv.server })

    val cases: List<ContractCase> = listOf(
        list("listRegions", "/v1/regions"), list("listDepots", "/v1/depots"), list("listCostCenters", "/v1/cost-centers"), list("listServices", "/v1/services"),
        ContractCase("createRegion",
            201, { val w = World(); ContractRequest("POST", "/v1/regions", """{"name":"Region Nord"}""", w.h()) },
            422, { val w = World(); ContractRequest("POST", "/v1/regions", """{"name":""}""", w.h()) }, server = { IdentityEnv.server }),
        ContractCase("updateRegion",
            200, { val w = World(); ContractRequest("PUT", "/v1/regions/${w.region()}", """{"name":"Neu"}""", w.h(ifMatch("1"))) },
            412, { val w = World(); ContractRequest("PUT", "/v1/regions/${w.region()}", """{"name":"Neu"}""", w.h(ifMatch("9"))) }, server = { IdentityEnv.server }),
        ContractCase("createDepot",
            201, { val w = World(); ContractRequest("POST", "/v1/depots", """{"region_id":"${w.region()}","name":"Hof Mitte"}""", w.h()) },
            404, { val w = World(); ContractRequest("POST", "/v1/depots", """{"region_id":"${UUID.randomUUID()}","name":"Hof"}""", w.h()) }, server = { IdentityEnv.server }),
        ContractCase("updateDepot",
            200, { val w = World(); val d = w.depot(); val r = w.region(); ContractRequest("PUT", "/v1/depots/$d", """{"region_id":"$r","name":"Neu"}""", w.h(ifMatch("1"))) },
            412, { val w = World(); val d = w.depot(); val r = w.region(); ContractRequest("PUT", "/v1/depots/$d", """{"region_id":"$r","name":"Neu"}""", w.h(ifMatch("9"))) }, server = { IdentityEnv.server }),
        ContractCase("createCostCenter",
            201, { val w = World(); ContractRequest("POST", "/v1/cost-centers", """{"code":"KST-100","name":"Linienverkehr","legal_entity_id":"${w.legalEntity()}"}""", w.h()) },
            409, { val w = World(); val le = w.legalEntity(); w.env.post("/v1/cost-centers", """{"code":"DUP","name":"A","legal_entity_id":"$le"}""", w.adminToken, extra = idem())
                ContractRequest("POST", "/v1/cost-centers", """{"code":"DUP","name":"B","legal_entity_id":"$le"}""", w.h()) }, server = { IdentityEnv.server }),
        ContractCase("updateCostCenter",
            200, { val w = World(); ContractRequest("PUT", "/v1/cost-centers/${w.costCenter()}", """{"name":"Neu"}""", w.h(ifMatch("1"))) },
            428, { val w = World(); ContractRequest("PUT", "/v1/cost-centers/${w.costCenter()}", """{"name":"Neu"}""", w.h()) }, server = { IdentityEnv.server }),
        ContractCase("createService",
            201, { val w = World(); ContractRequest("POST", "/v1/services", """{"operator_id":"${w.operator()}","name":"Rufbus Nord","timezone":"Europe/Berlin"}""", w.h()) },
            422, { val w = World(); ContractRequest("POST", "/v1/services", """{"operator_id":"${w.operator()}","name":"Rufbus","timezone":"Mars/Olympus"}""", w.h()) }, server = { IdentityEnv.server }),
        ContractCase("getEffectiveConfig",
            200, { val w = World(); ContractRequest("GET", "/v1/config/effective?parameter=max_wait_min", headers = w.h(emptyMap())) },
            404, { val w = World(); ContractRequest("GET", "/v1/config/effective?parameter=gibt_es_nicht", headers = w.h(emptyMap())) }, server = { IdentityEnv.server }),
        ContractCase("updateService",
            200, { val w = World(); ContractRequest("PUT", "/v1/services/${w.service()}", """{"name":"Neu","timezone":"Europe/Berlin"}""", w.h(ifMatch("1"))) },
            412, { val w = World(); ContractRequest("PUT", "/v1/services/${w.service()}", """{"name":"Neu","timezone":"Europe/Berlin"}""", w.h(ifMatch("9"))) }, server = { IdentityEnv.server }),
    )
}
