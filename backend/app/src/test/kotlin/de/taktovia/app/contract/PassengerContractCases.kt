package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.identity.api.ContactChannel
import de.taktovia.identity.api.InMemoryVerificationSender
import de.taktovia.identity.api.VerificationSender
import de.taktovia.platform.tenant.TenantId
import java.util.UUID

/** Vertragsfälle des Fahrgastkontos (WP-M1-28, -29, -30): Registrierung, Betreiberliste, Profil, Kontaktverifikation, Datenschutz, eigene Unterstützungsauswahl. */
internal object PassengerContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private val env = IdentityEnv
    private val sender get() = env.server.ctx.getBean(VerificationSender::class.java) as InMemoryVerificationSender

    private fun enrolmentToken(subject: UUID, tenant: TenantId) =
        env.idp.sign(env.idp.humanClaims(sub = subject, roles = listOf("passenger"), acr = "loa1", tenants = listOf(tenant.value), azp = "passenger-web").also { it["sid"] = "sid-" + UUID.randomUUID() })

    /** Ein Mandant mit registriertem Fahrgast (E-Mail unverifiziert) und Callcenter; je Aufruf neu. */
    private class World {
        val tenant = env.tenant("Vertrag-Fahrgast-" + UUID.randomUUID())
        val subject = UUID.randomUUID(); val token = enrolmentToken(subject, tenant); val email = "vertrag.${UUID.randomUUID().toString().take(8)}@example.org"
        val callcenter = UUID.randomUUID(); val admin = UUID.randomUUID()
        lateinit var id: String
        init {
            env.grant(tenant, callcenter, "callcenter"); env.grant(tenant, admin, "tenant_admin")
            val r = env.post("/v1/passengers", """{"contact":{"channel":"EMAIL","value":"$email"},"locale":"de-DE"}""", token, extra = idem())
            check(r.status == 201) { r.text }
            id = r.json["id"].asString()
        }
        val ccToken get() = env.token(callcenter); val adminToken get() = env.token(admin)
        val revision get() = env.get("/v1/me/profile", token).json["revision"].asLong()
        val code get() = sender.last(ContactChannel.EMAIL, email)!!
    }

    private fun h(token: String, extra: Map<String, String> = emptyMap()) = env.headers(token, extra = extra)

    val cases: List<ContractCase> = listOf(
        ContractCase("registerPassenger",
            201, { val t = env.tenant("Vertrag-Reg-" + UUID.randomUUID()); ContractRequest("POST", "/v1/passengers", """{"contact":{"channel":"EMAIL","value":"neu.${UUID.randomUUID().toString().take(8)}@example.org"},"locale":"de-DE"}""", h(enrolmentToken(UUID.randomUUID(), t), idem())) },
            422, { val t = env.tenant("Vertrag-Reg-" + UUID.randomUUID()); ContractRequest("POST", "/v1/passengers", """{"contact":{"channel":"EMAIL","value":"kein-kontakt"},"locale":"de-DE"}""", h(enrolmentToken(UUID.randomUUID(), t), idem())) }, server = { env.server }),
        ContractCase("listPassengers",
            200, { val w = World(); ContractRequest("GET", "/v1/passengers?limit=10", headers = h(w.ccToken)) },
            403, { val w = World(); ContractRequest("GET", "/v1/passengers", headers = h(w.adminToken)) }, server = { env.server }),
        ContractCase("getPassenger",
            200, { val w = World(); ContractRequest("GET", "/v1/passengers/${w.id}", headers = h(w.token)) },
            404, { val w = World(); ContractRequest("GET", "/v1/passengers/${UUID.randomUUID()}", headers = h(w.token)) }, server = { env.server }),
        ContractCase("getPassengerSupportNeeds",
            200, { val w = World(); env.put("/v1/me/transport-support", """{"codes":["LOW_FLOOR"]}""", w.token, null); ContractRequest("GET", "/v1/passengers/${w.id}/support-needs", headers = h(w.ccToken, mapOf("X-Access-Purpose" to "BOOKING_ASSISTANCE"))) },
            404, { val w = World(); ContractRequest("GET", "/v1/passengers/${UUID.randomUUID()}/support-needs", headers = h(w.ccToken, mapOf("X-Access-Purpose" to "BOOKING_ASSISTANCE"))) }, server = { env.server }),
        ContractCase("getMyProfile",
            200, { val w = World(); ContractRequest("GET", "/v1/me/profile", headers = h(w.token)) },
            404, { val w = World(); ContractRequest("GET", "/v1/me/profile", headers = h(w.ccToken)) }, server = { env.server }),
        ContractCase("updateMyProfile",
            200, { val w = World(); ContractRequest("PATCH", "/v1/me/profile", """{"locale":"en-GB","notification_channels":["PUSH"]}""", h(w.token, idem() + ("If-Match" to "\"${w.revision}\""))) },
            412, { val w = World(); ContractRequest("PATCH", "/v1/me/profile", """{"locale":"en-GB"}""", h(w.token, idem() + ("If-Match" to "\"999\""))) }, server = { env.server }),
        ContractCase("startContactVerification",
            200, { val w = World(); ContractRequest("POST", "/v1/me/contact-verifications", """{"channel":"EMAIL"}""", h(w.token, idem())) },
            404, { val w = World(); ContractRequest("POST", "/v1/me/contact-verifications", """{"channel":"PHONE"}""", h(w.token, idem())) }, server = { env.server }),
        ContractCase("confirmContactVerification",
            200, { val w = World(); ContractRequest("POST", "/v1/me/contact-verifications/confirmations", """{"channel":"EMAIL","code":"${w.code}"}""", h(w.token, idem())) },
            422, { val w = World(); val c = w.code; ContractRequest("POST", "/v1/me/contact-verifications/confirmations", """{"channel":"EMAIL","code":"${if (c == "000000") "111111" else "000000"}"}""", h(w.token, idem())) }, server = { env.server }),
        ContractCase("getMyPrivacySettings",
            200, { val w = World(); ContractRequest("GET", "/v1/me/privacy", headers = h(w.token)) },
            404, { val w = World(); ContractRequest("GET", "/v1/me/privacy", headers = h(w.ccToken)) }, server = { env.server }),
        ContractCase("recordMyConsent",
            201, { val w = World(); val v = env.get("/v1/me/privacy", w.token).json["current_text_version"].asString(); ContractRequest("POST", "/v1/me/privacy/consents", """{"purpose":"MARKETING","granted":true,"text_version":"$v"}""", h(w.token, idem())) },
            422, { val w = World(); val v = env.get("/v1/me/privacy", w.token).json["current_text_version"].asString(); ContractRequest("POST", "/v1/me/privacy/consents", """{"purpose":"OPERATIONAL_SAFETY","granted":false,"text_version":"$v"}""", h(w.token, idem())) }, server = { env.server }),
        ContractCase("getMyTransportSupport",
            200, { val w = World(); ContractRequest("GET", "/v1/me/transport-support", headers = h(w.token)) },
            404, { val w = World(); ContractRequest("GET", "/v1/me/transport-support", headers = h(w.ccToken)) }, server = { env.server }),
        ContractCase("replaceMyTransportSupport",
            204, { val w = World(); ContractRequest("PUT", "/v1/me/transport-support", """{"codes":["BOARDING_HELP"]}""", h(w.token, idem())) },
            422, { val w = World(); ContractRequest("PUT", "/v1/me/transport-support", """{"codes":["Diagnose"]}""", h(w.token, idem())) }, server = { env.server }),
    )
}
