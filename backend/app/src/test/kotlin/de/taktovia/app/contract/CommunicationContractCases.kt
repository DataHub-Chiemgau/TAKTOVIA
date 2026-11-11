package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.communicationsupport.api.CommunicationsupportApi
import de.taktovia.communicationsupport.api.NotificationChannel
import de.taktovia.communicationsupport.api.NotificationRequest
import java.util.UUID

/** Vertragsfälle der Nachrichten-Endpunkte (WP-M2-32): `listNotifications`, `getNotification`. */
internal object CommunicationContractCases {
    private val env = IdentityEnv

    /** Ein Mandant mit Administrator, Fahrgast-Mitglied und einem Auftrag; je Aufruf neu. */
    private class World {
        val tenant = env.tenant("Vertrag-Nachricht-" + UUID.randomUUID())
        val admin = UUID.randomUUID(); val passengerAccount = UUID.randomUUID(); val recipient = UUID.randomUUID()
        val notification: UUID
        init {
            env.grant(tenant, admin, "tenant_admin"); env.grant(tenant, passengerAccount, "passenger")
            notification = env.tx.run(tenant) { j ->
                j.update("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until) VALUES (taktovia.current_tenant(),?,?,now() + interval '1 year')", recipient, "tok-$recipient")
                env.server.ctx.getBean(CommunicationsupportApi::class.java).notifications
                    .send(j, NotificationRequest(UUID.randomUUID(), recipient, NotificationChannel.EMAIL, "booking.confirmed", null, mapOf("booking_reference" to "BK-1"))).notificationId
            }
        }
        val adminToken get() = env.token(admin); val passengerToken get() = env.token(passengerAccount)
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("listNotifications",
            200, { val w = World(); ContractRequest("GET", "/v1/notifications?limit=10", headers = env.headers(w.adminToken)) },
            403, { val w = World(); ContractRequest("GET", "/v1/notifications", headers = env.headers(w.passengerToken)) }, server = { IdentityEnv.server }),
        ContractCase("getNotification",
            200, { val w = World(); ContractRequest("GET", "/v1/notifications/${w.notification}", headers = env.headers(w.adminToken)) },
            404, { val w = World(); ContractRequest("GET", "/v1/notifications/${UUID.randomUUID()}", headers = env.headers(w.adminToken)) }, server = { IdentityEnv.server }),
    )
}
