package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.tenant.TenantId
import java.util.UUID

/** Test-Welt der Fahrerfreigabe (WP-M1-35): Eigentümer A mit Personalplanung und Fixture-Fahrer, Empfänger B mit Disponent und Dienst; je Aufruf neu. */
internal class DriverReleaseWorld {
    val env = IdentityEnv
    val owner: TenantId = env.tenant("Freigabe-A-" + UUID.randomUUID()); val recipient: TenantId = env.tenant("Freigabe-B-" + UUID.randomUUID())
    val planner = UUID.randomUUID(); val admin = UUID.randomUUID(); val dispatcher = UUID.randomUUID(); val recipientPlanner = UUID.randomUUID()
    val driver = UUID.randomUUID(); val service = UUID.randomUUID()
    init {
        env.grant(owner, planner, "staff_planning"); env.grant(owner, admin, "tenant_admin"); env.grant(recipient, dispatcher, "dispatcher"); env.grant(recipient, recipientPlanner, "staff_planning")
        env.exec("INSERT INTO taktovia.driver(tenant_id,id,display_name,contact_channel,contact_value) VALUES (?,?,'Erika Muster','PHONE','+491701234567')", owner.value, driver)
        env.exec("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,evidence_ref) VALUES (?,?,?,'PBEF',1,now()-interval '1 year','akte-1')", owner.value, UUID.randomUUID(), driver)
    }
    val plannerToken = env.token(planner); val adminToken = env.token(admin); val dispatcherToken = env.token(dispatcher); val recipientPlannerToken = env.token(recipientPlanner)

    fun body(from: String = "2026-01-01T00:00:00Z", to: String = "2099-01-01T00:00:00Z", driverId: UUID = driver, recipientId: UUID = recipient.value, services: List<UUID> = listOf(service)) =
        """{"driver_id":"$driverId","recipient_tenant_id":"$recipientId","service_ids":[${services.joinToString(",") { "\"$it\"" }}],"valid_from":"$from","valid_to":"$to"}"""

    /** Erteilt über die API und liefert Id und ETag. */
    fun grant(body: String = body()): Pair<String, String> {
        val r = env.post("/v1/driver-releases", body, plannerToken, null, mapOf("Idempotency-Key" to "grant-" + UUID.randomUUID()))
        check(r.status == 201) { r.text }
        return r.json["id"].asString() to r.header("ETag")!!
    }
}

/** Vertragsfälle der Fahrerfreigabe (WP-M1-35): Erteilen, Widerrufen, Projektion lesen. */
internal object DriverReleaseContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())

    val cases: List<ContractCase> = listOf(
        ContractCase("createDriverRelease",
            201, { val w = DriverReleaseWorld(); ContractRequest("POST", "/v1/driver-releases", w.body(), w.env.headers(w.plannerToken, extra = idem())) },
            403, { val w = DriverReleaseWorld(); ContractRequest("POST", "/v1/driver-releases", w.body(), w.env.headers(w.adminToken, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("revokeDriverRelease",
            204, { val w = DriverReleaseWorld(); val (id, _) = w.grant(); ContractRequest("DELETE", "/v1/driver-releases/$id", null, w.env.headers(w.plannerToken, extra = idem() + ("If-Match" to "\"1\""))) },
            404, { val w = DriverReleaseWorld(); ContractRequest("DELETE", "/v1/driver-releases/${UUID.randomUUID()}", null, w.env.headers(w.plannerToken, extra = idem() + ("If-Match" to "\"1\""))) }, server = { IdentityEnv.server }),
        ContractCase("getDriverReleaseProjection",
            200, { val w = DriverReleaseWorld(); val (id, _) = w.grant(); ContractRequest("GET", "/v1/driver-releases/$id/projection?service_id=${w.service}", headers = w.env.headers(w.dispatcherToken)) },
            404, { val w = DriverReleaseWorld(); val (id, _) = w.grant(); ContractRequest("GET", "/v1/driver-releases/$id/projection?service_id=${UUID.randomUUID()}", headers = w.env.headers(w.dispatcherToken)) }, server = { IdentityEnv.server }),
    )
}
