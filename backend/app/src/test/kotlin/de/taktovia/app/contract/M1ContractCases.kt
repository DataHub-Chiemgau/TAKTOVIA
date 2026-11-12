package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.operation.OperationService
import java.time.Instant
import java.util.UUID

/**
 * Vertragsfälle der produktiven Endpunkte. Wer einen Endpunkt aus `api/<modul>.yaml` baut, streicht seine `operationId` aus
 * `api/contract-pending.txt` und trägt hier einen [ContractCase] mit positivem Fall und Fehlerfall ein; ohne Eintrag
 * scheitert `ContractCoverageTest`. Stand: WP-M1-06 (Identität: getMe, switchTenant, listMemberships, createMembership, revokeMembership), WP-M1-05 (Rechtsträger, Organisationen, Plattformverwaltung), WP-M1-24 (Unternehmensprofil), WP-M1-34 (Fahrzeugrollen). Sitzungen (WP-M1-10, `sessionCases`). WP-M1-12 (listAuditEvents).
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
        ContractCase("listAuditEvents",
            200, { val w = World(); ContractRequest("GET", "/v1/audit-events?limit=10&purpose=security_review", headers = w.env.headers(w.adminToken)) },
            403, { val w = World(); ContractRequest("GET", "/v1/audit-events?purpose=security_review", headers = w.env.headers(w.passengerToken)) }, server = { IdentityEnv.server }),
    )

    private fun legalEntity(w: World): String =
        w.env.post("/v1/legal-entities", """{"name":"LE","country":"DE"}""", w.adminToken, null, idem()).json["id"].asString()
    private fun organization(w: World): String =
        w.env.post("/v1/organizations", """{"legal_entity_id":"${legalEntity(w)}","name":"Org","federation_ref":"fed-${UUID.randomUUID()}","roles":["BROKER"]}""", w.adminToken, null, idem()).json["id"].asString()

    /** Stammdaten (WP-M1-05): Rechtsträger, Organisationen, Plattformverwaltung. */
    private val organizationCases: List<ContractCase> = listOf(
        ContractCase("listLegalEntities",
            200, { val w = World(); ContractRequest("GET", "/v1/legal-entities?limit=10", headers = w.env.headers(w.adminToken)) },
            403, { val w = World(); ContractRequest("GET", "/v1/legal-entities", headers = w.env.headers(w.passengerToken)) }, server = { IdentityEnv.server }),
        ContractCase("createLegalEntity",
            201, { val w = World(); ContractRequest("POST", "/v1/legal-entities", """{"name":"Stadtwerke GmbH","country":"DE"}""", w.env.headers(w.adminToken, extra = idem())) },
            422, { val w = World(); ContractRequest("POST", "/v1/legal-entities", """{"name":"Stadtwerke GmbH","country":"Deutschland"}""", w.env.headers(w.adminToken, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("updateLegalEntity",
            200, { val w = World(); ContractRequest("PUT", "/v1/legal-entities/${legalEntity(w)}", """{"name":"Neu","country":"AT"}""", w.env.headers(w.adminToken, extra = idem() + ("If-Match" to "\"1\""))) },
            404, { val w = World(); ContractRequest("PUT", "/v1/legal-entities/${UUID.randomUUID()}", """{"name":"Neu","country":"AT"}""", w.env.headers(w.adminToken, extra = idem() + ("If-Match" to "\"1\""))) }, server = { IdentityEnv.server }),
        ContractCase("listOrganizations",
            200, { val w = World(); ContractRequest("GET", "/v1/organizations?limit=10", headers = w.env.headers(w.adminToken)) },
            403, { val w = World(); ContractRequest("GET", "/v1/organizations", headers = w.env.headers(w.passengerToken)) }, server = { IdentityEnv.server }),
        ContractCase("createOrganization",
            201, { val w = World(); ContractRequest("POST", "/v1/organizations", """{"legal_entity_id":"${legalEntity(w)}","name":"Org","federation_ref":"fed-${UUID.randomUUID()}","roles":["TRANSPORT_COMPANY","ACTUAL_CARRIER"]}""", w.env.headers(w.adminToken, extra = idem())) },
            422, { val w = World(); ContractRequest("POST", "/v1/organizations", """{"legal_entity_id":"${legalEntity(w)}","name":"Org","federation_ref":"f","roles":["UNBEKANNT"]}""", w.env.headers(w.adminToken, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("getOrganization",
            200, { val w = World(); ContractRequest("GET", "/v1/organizations/${organization(w)}", headers = w.env.headers(w.adminToken)) },
            404, { val w = World(); ContractRequest("GET", "/v1/organizations/${UUID.randomUUID()}", headers = w.env.headers(w.adminToken)) }, server = { IdentityEnv.server }),
        ContractCase("updateOrganization",
            200, { val w = World(); val le = legalEntity(w); val o = w.env.post("/v1/organizations", """{"legal_entity_id":"$le","name":"Org","federation_ref":"fed-${UUID.randomUUID()}","roles":["BROKER"]}""", w.adminToken, null, idem()).json["id"].asString()
                ContractRequest("PUT", "/v1/organizations/$o", """{"legal_entity_id":"$le","name":"Neu","roles":["BROKER","CLIENT"]}""", w.env.headers(w.adminToken, extra = idem() + ("If-Match" to "\"1\""))) },
            412, { val w = World(); val le = legalEntity(w); val o = w.env.post("/v1/organizations", """{"legal_entity_id":"$le","name":"Org","federation_ref":"fed-${UUID.randomUUID()}","roles":["BROKER"]}""", w.adminToken, null, idem()).json["id"].asString()
                ContractRequest("PUT", "/v1/organizations/$o", """{"legal_entity_id":"$le","name":"Neu","roles":["BROKER"]}""", w.env.headers(w.adminToken, extra = idem() + ("If-Match" to "\"7\""))) }, server = { IdentityEnv.server }),
        ContractCase("createTenant",
            201, { val op = IdentityEnv.operator(); ContractRequest("POST", "/v1/platform/tenants", """{"name":"Mandant ${UUID.randomUUID()}","legal_entity":{"name":"LE","country":"DE"},"first_admin":{"issuer":"${IdentityEnv.idp.issuer}","subject":"${UUID.randomUUID()}"},"contact_email":"info@example.org"}""", IdentityEnv.headers(IdentityEnv.token(op), extra = idem())) },
            403, { val w = World(); ContractRequest("POST", "/v1/platform/tenants", """{"name":"x","legal_entity":{"name":"LE","country":"DE"},"first_admin":{"issuer":"i","subject":"s"},"contact_email":"info@example.org"}""", w.env.headers(w.adminToken, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("changeTenantStatus",
            200, { val w = World(); val op = IdentityEnv.operator(); ContractRequest("POST", "/v1/platform/tenant-status-changes", """{"managed_tenant_id":"${w.tenant}","status":"SUSPENDED"}""", IdentityEnv.headers(IdentityEnv.token(op), extra = idem())) },
            404, { val op = IdentityEnv.operator(); ContractRequest("POST", "/v1/platform/tenant-status-changes", """{"managed_tenant_id":"${UUID.randomUUID()}","status":"SUSPENDED"}""", IdentityEnv.headers(IdentityEnv.token(op), extra = idem())) }, server = { IdentityEnv.server }),
    )

    /** Unternehmensprofil (WP-M1-24); der Mandant kommt wie in der Produktion aus der Plattformverwaltung (Profilrevision 1). */
    private fun profileTenant(): Pair<UUID, String> {
        val admin = UUID.randomUUID(); val op = IdentityEnv.operator()
        val r = IdentityEnv.post("/v1/platform/tenants", """{"name":"Profil-${UUID.randomUUID()}","legal_entity":{"name":"LE","country":"DE"},"first_admin":{"issuer":"${IdentityEnv.idp.issuer}","subject":"$admin"},"contact_email":"info@example.org"}""", IdentityEnv.token(op), null, idem())
        check(r.status == 201) { r.text }
        return admin to IdentityEnv.token(admin)
    }
    private val profileBody = """{"legal_name":"Stadtwerke GmbH","contact_email":"neu@example.org","default_currency":"CHF"}"""
    private val profileCases: List<ContractCase> = listOf(
        ContractCase("getCompanyProfile",
            200, { val (_, t) = profileTenant(); ContractRequest("GET", "/v1/company-profile", headers = IdentityEnv.headers(t)) },
            403, { val w = World(); ContractRequest("GET", "/v1/company-profile", headers = w.env.headers(w.passengerToken)) }, server = { IdentityEnv.server }),
        ContractCase("updateCompanyProfile",
            200, { val (_, t) = profileTenant(); ContractRequest("PUT", "/v1/company-profile", profileBody, IdentityEnv.headers(t, extra = idem() + ("If-Match" to "\"1\""))) },
            422, { val (_, t) = profileTenant(); ContractRequest("PUT", "/v1/company-profile", profileBody.replace("CHF", "XXY"), IdentityEnv.headers(t, extra = idem() + ("If-Match" to "\"1\""))) }, server = { IdentityEnv.server }),
    )

    /** Fahrzeugrollen (WP-M1-34): Mandant mit Disponent, Organisation und Fixture-Fahrzeug (Stammteil, vom Administrator angelegt). */
    private class FleetWorld {
        val env = IdentityEnv; val tenant = env.tenant("Flotte-" + UUID.randomUUID()); val dispatcher = UUID.randomUUID(); val passenger = UUID.randomUUID()
        val org = UUID.randomUUID(); val vehicle = UUID.randomUUID()
        init {
            env.grant(tenant, dispatcher, "dispatcher"); env.grant(tenant, passenger, "passenger")
            val le = UUID.randomUUID()
            env.tx.run(tenant) { j ->
                j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", tenant.value, le)
                j.update("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,'Org',?,CAST(? AS text[]))", tenant.value, org, le, "f-$org", "{TRANSPORT_COMPANY}")
                j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'fixture','{}')", tenant.value, vehicle)
            }
        }
        val dispatcherToken = env.token(dispatcher); val passengerToken = env.token(passenger)
        fun body(vehicleId: UUID = vehicle, from: String = "2026-01-01T00:00:00Z") = """{"vehicle_id":"$vehicleId","role_code":"OWNER","organization_id":"$org","valid_from":"$from"}"""
    }
    private val fleetCases: List<ContractCase> = listOf(
        ContractCase("listVehicleRoles",
            200, { val w = FleetWorld(); ContractRequest("GET", "/v1/vehicle-roles?vehicle_id=${w.vehicle}&as_of=2026-06-01T00:00:00Z", headers = w.env.headers(w.dispatcherToken)) },
            403, { val w = FleetWorld(); ContractRequest("GET", "/v1/vehicle-roles", headers = w.env.headers(w.passengerToken)) }, server = { IdentityEnv.server }),
        ContractCase("createVehicleRole",
            201, { val w = FleetWorld(); ContractRequest("POST", "/v1/vehicle-roles", w.body(), w.env.headers(w.dispatcherToken, extra = idem())) },
            404, { val w = FleetWorld(); ContractRequest("POST", "/v1/vehicle-roles", w.body(vehicleId = UUID.randomUUID()), w.env.headers(w.dispatcherToken, extra = idem())) }, server = { IdentityEnv.server }),
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

    val cases: Map<String, ContractCase> = (identityCases + organizationCases + profileCases + fleetCases + sessionCases + platformCases + webhookCases + ConfigContractCases.cases + StructureContractCases.cases + ImpactContractCases.cases + PassengerContractCases.cases + PublicationContractCases.cases + ServiceConfigContractCases.cases + DriverReleaseContractCases.cases + PaymentContractCases.cases + CommunicationContractCases.cases + DriverContractCases.cases + RetentionContractCases.cases + GeoContractCases.cases + TariffContractCases.cases + FleetMasterContractCases.cases + DriverMasterContractCases.cases + C5ContractCases.cases+ RideOfferContractCases.cases + OfferHoldContractCases.cases + RosterShiftContractCases.cases + PlaceSearchContractCases.cases + VehiclePoolContractCases.cases + ShiftContractCases.cases + DriverShiftContractCases.cases + PositionContractCases.cases + ChecklistContractCases.cases + DefectContractCases.cases + DefectEvidenceContractCases.cases + DefectStateContractCases.cases + DefectHistoryContractCases.cases + C10ContractCases.cases + C5ContractCases.cases + RideOfferContractCases.cases + InspectionContractCases.cases + RosterContractCases.cases + C5bContractCases.cases + C7ContractCases.cases + C9ContractCases.cases + BookingContractCases.cases + KnownDefectContractCases.cases + RosterPublishContractCases.cases + W10C5ContractCases.cases + W11C5ContractCases.cases).associateBy { it.operationId }
}
