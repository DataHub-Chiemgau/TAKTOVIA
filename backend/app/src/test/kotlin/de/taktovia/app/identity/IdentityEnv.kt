package de.taktovia.app.identity

import de.taktovia.TaktoviaTestSupport
import de.taktovia.app.probe.ProbeServer
import de.taktovia.app.probe.Resp
import de.taktovia.app.security.TestIdp
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import java.util.UUID

/**
 * Anwendung (echte Datenbank, Runtime-Rolle, nur die produktiven Routen) mit eingeschaltetem Ressourcenserver gegen einen lokalen
 * Test-Issuer (Ersatz für Keycloak; kein Docker) und echten Mitgliedschaften in der Datenbank. Mandanten legt der Administrator an
 * (Plattformbetrieb), alles Übrige läuft über die Anwendung bzw. die Runtime-Rolle.
 */
object IdentityEnv {
    val idp = TestIdp()
    const val CONTEXT_SECRET = "test-context-secret-test-context-secret-123456"

    /** Ersatz der Keycloak-Admin-REST-API (nicht ausgeführt: echter Keycloak) und lokaler Redis als Cache der Sperrliste (fehlt die Binärdatei: nur Datenbank). */
    val keycloak = FakeKeycloakAdmin(idp.realm)
    val redis: LocalRedis? by lazy { LocalRedis.startOrNull() }

    val server: ProbeServer by lazy {
        TaktoviaTestSupport.requireDatabase()
        Runtime.getRuntime().addShutdownHook(Thread { idp.close(); keycloak.close() })
        ProbeServer.production(
            "taktovia.idp.enabled=true", "taktovia.idp.issuer-uri=${idp.issuer}", "taktovia.idp.jwks-uri=${idp.jwksUri}", "taktovia.idp.audience=${idp.audience}",
            "taktovia.idp.jwks-min-refresh-seconds=0", "taktovia.idp.privileged-roles=tenant_admin,dispatcher,finance",
            "taktovia.idp.service-clients.adapter-psp=memberships:read", "taktovia.api.rate-limit.requests=1000000",
            "taktovia.identity.context-secret=$CONTEXT_SECRET",
            "taktovia.idp.admin.base-url=${keycloak.baseUrl}", "taktovia.idp.admin.client-secret=test-admin-secret",
            *(redis?.let { arrayOf("taktovia.identity.session.redis-url=${it.url}") } ?: emptyArray()),
        )
    }

    val tx: TenantTransactions get() = server.ctx.getBean(TenantTransactions::class.java)
    private val admin by lazy { TaktoviaTestSupport.adminDataSourceOrNull() ?: error("Administratorzugang nicht konfiguriert") }

    fun tenant(name: String = "t-" + UUID.randomUUID(), status: String = "ACTIVE"): TenantId {
        val id = UUID.randomUUID()
        admin.connection.use { c -> c.prepareStatement("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,?)").use { it.setObject(1, id); it.setString(2, name); it.setString(3, status); it.executeUpdate() } }
        return TenantId(id)
    }

    /** Erteilt eine Mitgliedschaft direkt (Testaufbau, nicht über die API); liefert ihre Id. */
    fun grant(tenant: TenantId, subject: UUID, role: String, scope: List<UUID>? = null): UUID {
        val id = UUID.randomUUID()
        tx.run(tenant) { j ->
            j.update("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code,service_scope) VALUES (?,?,?,?,?,CAST(? AS uuid[]))",
                tenant.value, id, idp.issuer, subject.toString(), role, scope?.joinToString(",", "{", "}"))
        }
        return id
    }

    fun revokeDirect(tenant: TenantId, membership: UUID) {
        tx.run(tenant) { j -> j.update("UPDATE taktovia.membership SET revoked_at = clock_timestamp() WHERE id = ?", membership) }
    }

    /** Menschen-Token mit zweitem Faktor (privilegierte Rollen verlangen ihn). */
    fun token(subject: UUID, sid: String = "sid-" + UUID.randomUUID(), acr: String = "loa2"): String =
        idp.sign(idp.humanClaims(sub = subject, acr = acr, amr = if (acr == "loa2") listOf("pwd", "otp") else null).also { it["sid"] = sid })

    fun headers(token: String, context: String? = null, extra: Map<String, String> = emptyMap()): Map<String, String> =
        buildMap { put("Authorization", "Bearer $token"); if (context != null) put(RequestContextFilter.CONTEXT_HEADER, context); putAll(extra) }

    fun get(path: String, token: String, context: String? = null, extra: Map<String, String> = emptyMap()): Resp = server.get(path, headers(token, context, extra))
    fun post(path: String, body: String, token: String, context: String? = null, extra: Map<String, String> = emptyMap()): Resp = server.post(path, body, headers(token, context, extra))

    fun delete(path: String, token: String, ifMatch: Long? = null, context: String? = null, extra: Map<String, String> = emptyMap()): Resp = server.send(
        "DELETE", path, null, headers(token, context, mapOf("Idempotency-Key" to "del-" + UUID.randomUUID()) + (if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap()) + extra))

    fun postIdem(path: String, body: String, token: String, context: String? = null): Resp = post(path, body, token, context, mapOf("Idempotency-Key" to "post-" + UUID.randomUUID()))

    fun switchTo(token: String, tenant: TenantId, service: UUID? = null, context: String? = null): Resp = post(
        "/v1/me/tenant-switch", """{"tenant_id":"$tenant"${if (service != null) ""","service_id":"$service"""" else ""}}""", token, context,
        mapOf("Idempotency-Key" to "switch-" + UUID.randomUUID()))

    fun count(sql: String, vararg args: Any?): Int = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { it.next(); it.getInt(1) } }
    }

    fun string(sql: String, vararg args: Any?): List<String> = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
    }

    /** Anzahl Sicherheits-/Auditereignisse eines Mandanten mit Aktion (Administratorsicht). */
    fun events(tenant: TenantId, action: String) = count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action=?", tenant.value, action)
}
