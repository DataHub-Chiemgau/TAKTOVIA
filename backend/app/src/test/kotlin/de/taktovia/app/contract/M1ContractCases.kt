package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.operation.OperationService
import java.time.Instant
import java.util.UUID

/**
 * Vertragsfälle der produktiven Endpunkte. Wer einen Endpunkt aus `api/<modul>.yaml` baut, streicht seine `operationId` aus
 * `api/contract-pending.txt` und trägt hier einen [ContractCase] mit positivem Fall und Fehlerfall ein; ohne Eintrag
 * scheitert `ContractCoverageTest`. Stand: WP-M1-06 (Identität: getMe, switchTenant, listMemberships, createMembership, revokeMembership).
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
        /** Legt ein Abonnement über die API an und liefert seine ID. */
        fun create(): String = env.post("/v1/webhook-subscriptions", """{"url":"https://93.184.216.34/hook","event_types":["membership.granted"]}""", adminToken, extra = idem()).json["id"].asString()
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

    /** Plattformbausteine (WP-M1-15): `operation`-Ressource. */
    private val platformCases: List<ContractCase> = listOf(
        ContractCase("getOperation",
            200, { val w = World(); val id = w.env.tx.run(w.tenant) { OperationService().enqueue(it, "TEST_COMMAND", Instant.now().plusSeconds(3600)) }; ContractRequest("GET", "/v1/operations/$id", headers = w.env.headers(w.adminToken)) },
            404, { val w = World(); ContractRequest("GET", "/v1/operations/${UUID.randomUUID()}", headers = w.env.headers(w.adminToken)) }, server = { IdentityEnv.server }),
    )

    /** Webhook-Grundlage (WP-M1-16). */
    private val webhookCases: List<ContractCase> = listOf(
        ContractCase("createWebhookSubscription",
            201, { val w = World(); ContractRequest("POST", "/v1/webhook-subscriptions", """{"url":"https://93.184.216.34/hook","event_types":["membership.granted"]}""", w.env.headers(w.adminToken, extra = idem())) },
            403, { val w = World(); ContractRequest("POST", "/v1/webhook-subscriptions", """{"url":"https://93.184.216.34/hook","event_types":["membership.granted"]}""", w.env.headers(w.passengerToken, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("listWebhookSubscriptions",
            200, { val w = World(); ContractRequest("GET", "/v1/webhook-subscriptions?limit=10", headers = w.env.headers(w.adminToken)) },
            403, { val w = World(); ContractRequest("GET", "/v1/webhook-subscriptions", headers = w.env.headers(w.passengerToken)) }, server = { IdentityEnv.server }),
        ContractCase("getWebhookSubscription",
            200, { val w = World(); val id = w.create(); ContractRequest("GET", "/v1/webhook-subscriptions/$id", headers = w.env.headers(w.adminToken)) },
            404, { val w = World(); ContractRequest("GET", "/v1/webhook-subscriptions/${UUID.randomUUID()}", headers = w.env.headers(w.adminToken)) }, server = { IdentityEnv.server }),
        ContractCase("deactivateWebhookSubscription",
            200, { val w = World(); val id = w.create(); ContractRequest("POST", "/v1/webhook-subscriptions/$id/deactivate", null, w.env.headers(w.adminToken, extra = idem() + ("If-Match" to "\"1\""))) },
            404, { val w = World(); ContractRequest("POST", "/v1/webhook-subscriptions/${UUID.randomUUID()}/deactivate", null, w.env.headers(w.adminToken, extra = idem() + ("If-Match" to "\"1\""))) }, server = { IdentityEnv.server }),
    )

    val cases: Map<String, ContractCase> = (identityCases + platformCases + webhookCases).associateBy { it.operationId }
}
