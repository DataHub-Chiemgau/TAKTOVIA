package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/**
 * Vertragsfälle der produktiven Endpunkte. Wer einen Endpunkt aus `api/<modul>.yaml` baut, streicht seine `operationId` aus
 * `api/contract-pending.txt` und trägt hier einen [ContractCase] mit positivem Fall und Fehlerfall ein; ohne Eintrag
 * scheitert `ContractCoverageTest`. Stand: WP-M1-06 (Identität (Sitzungen: WP-M1-10, `sessionCases`): getMe, switchTenant, listMemberships, createMembership, revokeMembership).
 * Fälle, die eine Anmeldung brauchen, laufen gegen die Instanz mit Ressourcenserver und Test-Issuer ([IdentityEnv]).
 */
object M1ContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())

    /** Ein Mandant mit Administrator und Fahrgast; je Aufruf neu, damit Fälle unabhängig bleiben. */
    private class World {
        val env = IdentityEnv
        val tenant = env.tenant("Vertrag-" + UUID.randomUUID())
        val admin = UUID.randomUUID(); val passenger = UUID.randomUUID()
        init { env.grant(tenant, admin, "tenant_admin"); env.grant(tenant, passenger, "passenger") }
        val adminToken = env.token(admin); val passengerToken = env.token(passenger)
    }

    private val identityCases: List<ContractCase> = listOf(
        ContractCase("getMe",
            200, { val w = World(); ContractRequest("GET", "/v1/me", headers = w.env.headers(w.adminToken)) },
            401, { ContractRequest("GET", "/v1/me") }, server = { IdentityEnv.server }),
        ContractCase("switchTenant",
            200, { val w = World(); ContractRequest("POST", "/v1/me/tenant-switch", """{"tenant_id":"${w.tenant}"}""", w.env.headers(w.adminToken, extra = idem())) },
            403, { val w = World(); ContractRequest("POST", "/v1/me/tenant-switch", """{"tenant_id":"${w.env.tenant()}"}""", w.env.headers(w.adminToken, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("listMemberships",
            200, { val w = World(); ContractRequest("GET", "/v1/memberships?limit=10", headers = w.env.headers(w.adminToken)) },
            403, { val w = World(); ContractRequest("GET", "/v1/memberships", headers = w.env.headers(w.passengerToken)) }, server = { IdentityEnv.server }),
        ContractCase("createMembership",
            201, { val w = World(); ContractRequest("POST", "/v1/memberships", """{"issuer":"${w.env.idp.issuer}","subject":"user-${UUID.randomUUID()}","role_code":"dispatcher"}""", w.env.headers(w.adminToken, extra = idem())) },
            403, { val w = World(); ContractRequest("POST", "/v1/memberships", """{"issuer":"i","subject":"s","role_code":"dispatcher"}""", w.env.headers(w.passengerToken, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("revokeMembership",
            204, { val w = World(); val m = w.env.grant(w.tenant, UUID.randomUUID(), "support"); ContractRequest("DELETE", "/v1/memberships/$m", null, w.env.headers(w.adminToken, extra = idem() + ("If-Match" to "\"1\""))) },
            404, { val w = World(); ContractRequest("DELETE", "/v1/memberships/${UUID.randomUUID()}", null, w.env.headers(w.adminToken, extra = idem() + ("If-Match" to "\"1\""))) }, server = { IdentityEnv.server }),
    )

    /** WP-M1-10: Sitzungen, Kontosperre, Wiederherstellung. Das IdP-Admin-Verhalten steht im Ersatzserver [IdentityEnv.keycloak]. */
    private fun sessionHeaders(w: World, token: String, extra: Map<String, String> = emptyMap()) = w.env.headers(token, extra = idem() + extra)
    private fun mySessionId(w: World, token: String): Pair<String, Long> {
        val id = w.env.get("/v1/me", token).json["session_id"].asString()
        return id to w.env.get("/v1/me/sessions", token).json["items"].first { it["id"].asString() == id }["revision"].asLong()
    }

    private val sessionCases: List<ContractCase> = listOf(
        ContractCase("listMySessions",
            200, { val w = World(); w.env.get("/v1/me", w.passengerToken); ContractRequest("GET", "/v1/me/sessions?limit=10", headers = w.env.headers(w.passengerToken)) },
            401, { ContractRequest("GET", "/v1/me/sessions") }, server = { IdentityEnv.server }),
        ContractCase("revokeAllMySessions",
            204, { val w = World(); ContractRequest("POST", "/v1/me/sessions/revoke-all", "{}", sessionHeaders(w, w.passengerToken)) },
            401, { ContractRequest("POST", "/v1/me/sessions/revoke-all", "{}", idem()) }, server = { IdentityEnv.server }),
        ContractCase("revokeMySession",
            204, { val w = World(); val (id, rev) = mySessionId(w, w.passengerToken); ContractRequest("DELETE", "/v1/me/sessions/$id", null, sessionHeaders(w, w.passengerToken, mapOf("If-Match" to "\"$rev\""))) },
            404, { val w = World(); w.env.get("/v1/me", w.passengerToken); ContractRequest("DELETE", "/v1/me/sessions/${UUID.randomUUID()}", null, sessionHeaders(w, w.passengerToken, mapOf("If-Match" to "\"1\""))) }, server = { IdentityEnv.server }),
        ContractCase("listSessions",
            200, { val w = World(); w.env.get("/v1/me", w.passengerToken); ContractRequest("GET", "/v1/sessions?limit=10", headers = w.env.headers(w.adminToken)) },
            403, { val w = World(); ContractRequest("GET", "/v1/sessions", headers = w.env.headers(w.passengerToken)) }, server = { IdentityEnv.server }),
        ContractCase("revokeSession",
            204, { val w = World(); val (id, rev) = mySessionId(w, w.passengerToken); ContractRequest("DELETE", "/v1/sessions/$id", null, sessionHeaders(w, w.adminToken, mapOf("If-Match" to "\"$rev\""))) },
            404, { val w = World(); ContractRequest("DELETE", "/v1/sessions/${UUID.randomUUID()}", null, sessionHeaders(w, w.adminToken, mapOf("If-Match" to "\"1\""))) }, server = { IdentityEnv.server }),
        ContractCase("lockAccount",
            201, { val w = World(); ContractRequest("POST", "/v1/account-locks", """{"subject":"${w.passenger}","reason":"Vertragsfall"}""", sessionHeaders(w, w.adminToken)) },
            403, { val w = World(); ContractRequest("POST", "/v1/account-locks", """{"subject":"${w.admin}","reason":"x"}""", sessionHeaders(w, w.passengerToken)) }, server = { IdentityEnv.server }),
        ContractCase("unlockAccount",
            204, { val w = World(); val rev = w.env.postIdem("/v1/account-locks", """{"subject":"${w.passenger}","reason":"Vertragsfall"}""", w.adminToken).json["revision"].asLong()
                ContractRequest("DELETE", "/v1/account-locks/${w.passenger}", null, sessionHeaders(w, w.adminToken, mapOf("If-Match" to "\"$rev\""))) },
            404, { val w = World(); ContractRequest("DELETE", "/v1/account-locks/${UUID.randomUUID()}", null, sessionHeaders(w, w.adminToken, mapOf("If-Match" to "\"1\""))) }, server = { IdentityEnv.server }),
        ContractCase("startAccountRecovery",
            201, { val w = World(); ContractRequest("POST", "/v1/account-recoveries", """{"subject":"${w.passenger}"}""", sessionHeaders(w, w.adminToken)) },
            404, { val w = World(); ContractRequest("POST", "/v1/account-recoveries", """{"subject":"${UUID.randomUUID()}"}""", sessionHeaders(w, w.adminToken)) }, server = { IdentityEnv.server }),
    )

    val cases: Map<String, ContractCase> = (identityCases + sessionCases).associateBy { it.operationId }
}
