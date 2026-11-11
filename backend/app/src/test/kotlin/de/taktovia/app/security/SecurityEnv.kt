package de.taktovia.app.security

import de.taktovia.app.probe.ProbeServer
import de.taktovia.platform.tenant.TenantId
import de.taktovia.app.probe.ProbeDb
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Nur Tests (`taktovia.test.mutable-roles=true`): veränderliche Rollenzuordnung, Ersatz für `membership.role_code` (WP-M1-07). */
@Component
@ConditionalOnProperty("taktovia.test.mutable-roles", havingValue = "true")
class MutableRoleDirectory : PrivilegedRoleDirectory {
    private val roles = ConcurrentHashMap<UUID, Set<String>>()
    fun assign(subject: UUID, vararg role: String) { roles[subject] = role.toSet() }
    fun revoke(subject: UUID) { roles.remove(subject) }
    override fun rolesOf(subject: UUID): Set<String> = roles[subject] ?: emptySet()
}

/** Anwendung (echte Datenbank, Runtime-Rolle) mit eingeschaltetem Ressourcenserver gegen einen lokalen Test-Issuer. */
class SecurityEnv(val idp: TestIdp, val server: ProbeServer) {
    val roles: MutableRoleDirectory get() = server.ctx.getBean(MutableRoleDirectory::class.java)
    val tenantA: UUID get() = ProbeDb.tenantA
    fun tenantTx() = server.ctx.getBean(de.taktovia.platform.tenant.TenantTransactions::class.java)

    fun human(token: String) = idp.bearer(token)

    /** Aufruf eines gewöhnlichen geschützten Endpunkts (Prüfroute mit Scope probe:read). */
    fun call(token: String?, path: String = "/v1/probe/leaky") = server.get(path, if (token == null) emptyMap() else idp.bearer(token))

    companion object {
        fun props(idp: TestIdp, vararg extra: String): Array<String> = arrayOf(
            "taktovia.idp.enabled=true", "taktovia.idp.issuer-uri=${idp.issuer}", "taktovia.idp.jwks-uri=${idp.jwksUri}",
            "taktovia.idp.audience=${idp.audience}", "taktovia.idp.jwks-min-refresh-seconds=0",
            "taktovia.idp.privileged-roles=tenant_admin,dispatcher,finance",
            "taktovia.idp.service-clients.adapter-psp=probe:read",
            "taktovia.test.mutable-roles=true", "taktovia.api.rate-limit.requests=1000000", *extra,
        )

        /** Jeder Aufruf startet einen eigenen Test-Issuer und eine eigene Anwendungsinstanz (andere Konfiguration = andere Instanz). */
        fun create(vararg extra: String): SecurityEnv {
            val idp = TestIdp()
            Runtime.getRuntime().addShutdownHook(Thread { idp.close() })
            return SecurityEnv(idp, ProbeServer.get(*props(idp, *extra)))
        }

        val shared: SecurityEnv by lazy { create() }
    }
}
