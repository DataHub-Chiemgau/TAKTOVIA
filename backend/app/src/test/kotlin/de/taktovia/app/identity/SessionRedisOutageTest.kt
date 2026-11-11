package de.taktovia.app.identity

import de.taktovia.TaktoviaTestSupport
import de.taktovia.app.probe.ProbeServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** Sperre bei ausgefallenem Redis: die Datenbank führt und entscheidet (fail-closed); gegen die echte Anwendung und einen echten `redis-server`, der mitten im Test beendet wird. */
class SessionRedisOutageTest {
    @Test
    fun redis_ausfall_datenbank_entscheidet_gesperrt_bleibt_gesperrt() {
        TaktoviaTestSupport.requireDatabase()
        val redis = LocalRedis.startOrNull(); assumeTrue(redis != null, "redis-server nicht installiert")
        val env = IdentityEnv; val idp = env.idp
        val server = ProbeServer.production(
            "taktovia.idp.enabled=true", "taktovia.idp.issuer-uri=${idp.issuer}", "taktovia.idp.jwks-uri=${idp.jwksUri}", "taktovia.idp.audience=${idp.audience}",
            "taktovia.idp.jwks-min-refresh-seconds=0", "taktovia.idp.privileged-roles=tenant_admin,dispatcher,finance", "taktovia.api.rate-limit.requests=1000000",
            "taktovia.identity.context-secret=${IdentityEnv.CONTEXT_SECRET}", "taktovia.idp.admin.base-url=${env.keycloak.baseUrl}", "taktovia.idp.admin.client-secret=test-admin-secret",
            "taktovia.identity.session.redis-url=${redis!!.url}", "taktovia.test.variant=redis-outage",
        )
        val tenant = env.tenant(); val admin = UUID.randomUUID(); val u1 = UUID.randomUUID(); val u2 = UUID.randomUUID()
        env.grant(tenant, admin, "tenant_admin"); env.grant(tenant, u1, "dispatcher"); env.grant(tenant, u2, "dispatcher")
        fun get(token: String) = server.get("/v1/me", env.headers(token)).status
        val revokedToken = env.token(u1); val okToken = env.token(u2); val adminToken = env.token(admin)
        assertEquals(200, get(revokedToken)); assertEquals(200, get(okToken))
        redis.stop()   // Redis fällt aus
        val id = server.get("/v1/me", env.headers(revokedToken)).json["session_id"].asString()
        val rev = server.get("/v1/me/sessions", env.headers(revokedToken)).json["items"].first { it["id"].asString() == id }["revision"].asLong()
        val del = server.send("DELETE", "/v1/sessions/$id", null, env.headers(adminToken, extra = mapOf("Idempotency-Key" to "del-" + UUID.randomUUID(), "If-Match" to "\"$rev\"")))
        assertEquals(204, del.status, del.text)
        assertEquals(401, get(revokedToken), "ohne Redis entscheidet die Datenbank: gesperrt")
        assertEquals(200, get(okToken), "ohne Redis bleibt eine nicht gesperrte Sitzung über die Datenbank nutzbar")
    }
}
