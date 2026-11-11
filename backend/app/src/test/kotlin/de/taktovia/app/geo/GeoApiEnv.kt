package de.taktovia.app.geo

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.app.probe.Resp
import de.taktovia.platform.tenant.TenantId
import java.util.UUID

/** Testwelt des Clusters Dienste/Geo (WP-M2-03 bis -06): Mandant mit Administrator, Planer, Fahrgast, Betreiber und Dienst über die echte Anwendung. */
internal class GeoWorld(name: String = "geo-" + UUID.randomUUID()) {
    val env = IdentityEnv
    val tenant: TenantId = env.tenant(name)
    val adminSubject: UUID = UUID.randomUUID(); val plannerSubject: UUID = UUID.randomUUID(); val passengerSubject: UUID = UUID.randomUUID()
    init { env.grant(tenant, adminSubject, "tenant_admin"); env.grant(tenant, plannerSubject, "planner"); env.grant(tenant, passengerSubject, "passenger") }
    val admin = env.token(adminSubject); val planner = env.token(plannerSubject); val passenger = env.token(passengerSubject)

    fun idem() = mapOf("Idempotency-Key" to "geo-" + UUID.randomUUID())
    fun id(path: String, body: String, token: String = admin): String {
        val r = env.post(path, body, token, extra = idem()); check(r.status == 201) { "$path: ${r.status} ${r.text}" }
        return r.json["id"].asString()
    }
    val operator: String by lazy {
        val le = id("/v1/legal-entities", """{"name":"LE","country":"DE"}""")
        id("/v1/organizations", """{"legal_entity_id":"$le","name":"Betreiber","federation_ref":"f-${UUID.randomUUID()}","roles":["TRANSPORT_COMPANY"]}""")
    }
    fun service(tz: String = "Europe/Berlin"): String = id("/v1/services", """{"operator_id":"$operator","name":"Rufbus","timezone":"$tz"}""")

    fun send(method: String, path: String, body: String? = null, token: String = admin, ifMatch: String? = null, extra: Map<String, String> = emptyMap()): Resp =
        env.server.send(method, path, body, env.headers(token, extra = (if (method == "GET") emptyMap() else idem()) + (if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap()) + extra))
    fun get(path: String, token: String = admin) = send("GET", path, token = token)
    fun put(path: String, body: String, ifMatch: String?, token: String = admin) = send("PUT", path, body, token, ifMatch)
    fun post(path: String, body: String, token: String = admin, ifMatch: String? = null) = send("POST", path, body, token, ifMatch)
    fun count(sql: String, vararg args: Any?) = env.count(sql, *args)
}
