package de.taktovia.app

import de.taktovia.TaktoviaTestSupport
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.app.probe.ProbeDb
import de.taktovia.app.probe.ProbeServer
import de.taktovia.platform.operation.OperationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * `GET /v1/operations/{operation_id}` und `OperationAccepted` (WP-M1-15) über die vollständige Kette aus Token-Prüfung, Mandantenkontext und Rechtematrix
 * sowie die Anbindung an das Idempotenzregister (`operation_id` im Register, Wiederholung liefert dieselbe Operation).
 */
class OperationApiTest {
    private val env = IdentityEnv
    private val svc = OperationService()
    private fun enqueue(t: de.taktovia.platform.tenant.TenantId, kind: String = "TEST_COMMAND"): UUID = env.tx.run(t) { svc.enqueue(it, kind, Instant.now().plusSeconds(3600)) }

    @Test
    fun getOperation_liefert_den_persistierten_stand_mit_retry_after_seconds() {
        val t = env.tenant(); val user = UUID.randomUUID(); env.grant(t, user, "dispatcher")
        val id = enqueue(t, "CONFIRM")
        val r = env.get("/v1/operations/$id", env.token(user))
        assertEquals(200, r.status, r.text)
        assertEquals(id.toString(), r.json["operation_id"].asString()); assertEquals("CONFIRM", r.json["kind"].asString()); assertEquals("QUEUED", r.json["status"].asString())
        assertTrue(r.json["journey_id"].isNull); assertTrue(r.json["result_code"].isNull)
        assertEquals(3L, r.json["retry_after_seconds"].asLong(), "Default ohne next_attempt_at")
        // next_attempt_at bestimmt den Hinweis
        env.tx.run(t) { it.update("UPDATE taktovia.operation SET status='RUNNING' WHERE id=?", id) }
        env.tx.run(t) { it.update("UPDATE taktovia.operation SET status='RECONCILING', result_code='PARTNER_RESULT_UNKNOWN', next_attempt_at = now() + interval '30 seconds' WHERE id=?", id) }
        val rec = env.get("/v1/operations/$id", env.token(user)).json
        assertEquals("RECONCILING", rec["status"].asString()); assertEquals("PARTNER_RESULT_UNKNOWN", rec["result_code"].asString())
        assertTrue(rec["retry_after_seconds"].asLong() in 25..30, rec["retry_after_seconds"].toString())
    }

    @Test
    fun fremde_operation_ist_404_ohne_scope_403_ohne_anmeldung_401() {
        val a = env.tenant(); val b = env.tenant(); val userA = UUID.randomUUID(); val userB = UUID.randomUUID(); val passenger = UUID.randomUUID()
        env.grant(a, userA, "dispatcher"); env.grant(b, userB, "dispatcher"); env.grant(a, passenger, "passenger")
        val id = enqueue(a)
        assertEquals(200, env.get("/v1/operations/$id", env.token(userA)).status)
        val foreign = env.get("/v1/operations/$id", env.token(userB))
        assertEquals(404, foreign.status, "fremder Mandant: wie nicht vorhanden"); assertEquals("NOT_FOUND", foreign.json["code"].asString())
        assertEquals(404, env.get("/v1/operations/${UUID.randomUUID()}", env.token(userA)).status)
        val noScope = env.get("/v1/operations/$id", env.token(passenger))
        assertEquals(403, noScope.status, "Scope operations:read fehlt"); assertTrue(!noScope.text.contains("operations:read"), "keine Details in der Verweigerung")
        assertEquals(401, env.server.get("/v1/operations/$id").status)
        assertEquals(422, env.get("/v1/operations/kein-uuid", env.token(userA)).status)
    }

    private val props = arrayOf("taktovia.probe.idempotency=true", "taktovia.api.extra-specs=classpath:probe/idempotency-probe-api.yaml", "taktovia.api.rate-limit.requests=1000000")
    private fun probe(key: String, headers: Map<String, String> = mapOf("X-Test-Tenant" to ProbeDb.tenantA.toString(), "X-Test-Actor" to "alice")) =
        ProbeServer.get(*props).post("/v1/probe/operations", """{"name":"op"}""", headers + ("Idempotency-Key" to key))

    @Test
    fun operation_accepted_202_mit_location_und_retry_after_und_wiederholung_liefert_dieselbe_operation() {
        val key = "key-" + UUID.randomUUID()
        val first = probe(key)
        assertEquals(202, first.status, first.text)
        val id = first.json["operation_id"].asString()
        assertEquals("/v1/operations/$id", first.header("Location")); assertEquals("3", first.header("Retry-After"))
        assertEquals("QUEUED", first.json["status"].asString())
        val retry = probe(key)
        assertEquals(202, retry.status); assertEquals(id, retry.json["operation_id"].asString(), "dieselbe Operation"); assertEquals("true", retry.header("Idempotent-Replayed"))
        val admin = TaktoviaTestSupport.adminDataSourceOrNull()!!
        admin.connection.use { c ->
            c.prepareStatement("SELECT count(*) FROM taktovia.operation WHERE tenant_id=? AND kind='TEST_COMMAND' AND id=?").use { ps -> ps.setObject(1, ProbeDb.tenantA); ps.setObject(2, UUID.fromString(id)); ps.executeQuery().use { it.next(); assertEquals(1, it.getInt(1)) } }
            c.prepareStatement("SELECT operation_id FROM taktovia.idempotency_record WHERE tenant_id=? AND key=?").use { ps -> ps.setObject(1, ProbeDb.tenantA); ps.setString(2, key); ps.executeQuery().use { it.next(); assertEquals(UUID.fromString(id), it.getObject(1, UUID::class.java), "Register verweist auf die Operation") } }
        }
        assertNotNull(first.header("Location"))
    }
}
