package de.taktovia.app.contract

import de.taktovia.app.probe.ProbeDb
import de.taktovia.app.probe.ProbeServer
import java.util.UUID

/** Vertragsfälle der Prüf-Endpunkte (`probe/probe-api.yaml`): zeigen, dass der Runner Routen, Status und Antwortschemas wirklich prüft. */
class ProbeContractCases(private val server: ProbeServer) {
    private val tenant = mapOf("X-Test-Tenant" to ProbeDb.tenantA.toString(), "X-Test-Actor" to "contract-runner")

    private fun newItem(): Pair<String, String> {
        val r = server.post("/v1/probe/items", """{"name":"vertrag"}""", tenant)
        return r.json["id"].asString() to r.header("ETag")!!
    }

    private val payer = tenant + ("X-Test-Scopes" to "probe:payments:transition")
    private fun newPayment(): Pair<String, String> {
        val r = server.post("/v1/probe/payments", "{}", tenant)
        return r.json["id"].asString() to r.header("ETag")!!
    }

    val cases: Map<String, ContractCase> = listOf(
        ContractCase("listProbeItems", 200, { ContractRequest("GET", "/v1/probe/items?limit=2", headers = tenant) }, 422, { ContractRequest("GET", "/v1/probe/items?limit=0", headers = tenant) }),
        ContractCase("createProbeItem", 201, { ContractRequest("POST", "/v1/probe/items", """{"name":"Alpha","price":{"amount_minor":100,"currency":"EUR","exponent":2}}""", tenant) },
            422, { ContractRequest("POST", "/v1/probe/items", """{"name":"Alpha","unbekannt":1}""", tenant) }),
        ContractCase("getProbeItem", 200, { ContractRequest("GET", "/v1/probe/items/${newItem().first}", headers = tenant) },
            404, { ContractRequest("GET", "/v1/probe/items/${UUID.randomUUID()}", headers = tenant) }),
        ContractCase("updateProbeItem", 200, { val (id, etag) = newItem(); ContractRequest("PUT", "/v1/probe/items/$id", """{"value":"neu"}""", tenant + ("If-Match" to etag)) },
            428, { ContractRequest("PUT", "/v1/probe/items/${UUID.randomUUID()}", """{"value":"neu"}""", tenant) }),
        ContractCase("getProbeAdmin", 200, { ContractRequest("GET", "/v1/probe/admin", headers = tenant + ("X-Test-Scopes" to "probe:admin")) }, 403, { ContractRequest("GET", "/v1/probe/admin", headers = tenant) }),
        ContractCase("postProbeFree", 200, { ContractRequest("POST", "/v1/probe/free", """{"a":1}""", tenant) }, 415, { ContractRequest("POST", "/v1/probe/free", """{"a":1}""", tenant, contentType = "text/plain") }),
        ContractCase("createProbeAppointment", 201, { ContractRequest("POST", "/v1/probe/appointments", """{"at":{"desired_at":"2026-10-25T02:30:00+02:00","time_zone":"Europe/Berlin"}}""", tenant) },
            422, { ContractRequest("POST", "/v1/probe/appointments", """{"at":{"desired_at":"2026-10-25T02:30:00","time_zone":"Europe/Berlin"}}""", tenant) }),
        ContractCase("createProbePayment", 201, { ContractRequest("POST", "/v1/probe/payments", "{}", tenant) }, 415, { ContractRequest("POST", "/v1/probe/payments", "{}", tenant, contentType = "text/plain") }),
        ContractCase("getProbePayment", 200, { ContractRequest("GET", "/v1/probe/payments/${newPayment().first}", headers = tenant) },
            404, { ContractRequest("GET", "/v1/probe/payments/${UUID.randomUUID()}", headers = tenant) }),
        ContractCase("transitionProbePayment", 200, { val (id, etag) = newPayment(); ContractRequest("POST", "/v1/probe/payments/$id/transitions", """{"state":"AUTHORIZED"}""", payer + ("If-Match" to etag)) },
            409, { val (id, etag) = newPayment(); ContractRequest("POST", "/v1/probe/payments/$id/transitions", """{"state":"CAPTURED"}""", payer + ("If-Match" to etag)) }),
        ContractCase("getProbeLeaky", 200, { ContractRequest("GET", "/v1/probe/leaky", headers = tenant) }, 403, { ContractRequest("GET", "/v1/probe/leaky?fail=1", headers = tenant) }),
    ).associateBy { it.operationId }

    /** Fehlerpfad-Sonden, die ProblemModelTest abdeckt und die kein Vertragsfall im Sinn „positiv“ haben. */
    val exempt = mapOf("getProbeBoom" to "provoziert 500 für ProblemModelTest", "postProbeImmutable" to "provoziert 409 aus der Datenbank für ProblemModelTest")
}
