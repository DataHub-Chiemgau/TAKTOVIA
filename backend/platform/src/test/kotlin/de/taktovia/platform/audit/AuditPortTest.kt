package de.taktovia.platform.audit

import de.taktovia.platform.TestDatabase
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** WP-M1-46: Audit-Port gegen echte Datenbank unter der Runtime-Rolle. Fachänderung = Fahrzeug anlegen. */
class AuditPortTest {
    companion object {
        private val a = TenantId(UUID.randomUUID()); private val b = TenantId(UUID.randomUUID())
        private lateinit var pool: com.zaxxer.hikari.HikariDataSource
        private lateinit var tx: TenantTransactions
        private val port = JdbcAuditPort()

        @BeforeAll @JvmStatic fun setup() {
            TestDatabase.require()
            val admin = JdbcTemplate(TestDatabase.admin())
            for (t in listOf(a, b)) admin.update("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?, 'ACTIVE')", t.value, "audit-$t")
            pool = TestDatabase.runtime(poolSize = 12); tx = TenantTransactions(pool)
        }
        @AfterAll @JvmStatic fun teardown() { if (::pool.isInitialized) pool.close() }
    }

    private fun newVehicle(j: JdbcOperations, id: UUID) =
        j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (taktovia.current_tenant(),?,'x','{}'::jsonb)", id)
    private fun entry(obj: UUID, changes: Map<String, Any?> = mapOf("status" to "NEW")) =
        AuditEntry(ActorRef.system("audit-test"), "identity.organization.changed", ObjectRef("vehicle", obj), UUID.randomUUID(), changes)
    private fun audits(t: TenantId, obj: UUID) = tx.run(t, readOnly = true) { j ->
        j.queryForList("SELECT object_ref FROM taktovia.audit_event WHERE object_ref = ?", String::class.java, "vehicle:$obj") }

    @Test fun `Auditeintrag zu einer Aenderung mit Akteur, Aktion, Objekt und Zeitpunkt`() {
        val v = UUID.randomUUID(); val e = entry(v, mapOf("status" to "NEW", "token" to "geheim"))
        val id = tx.run(a) { j -> newVehicle(j, v); port.record(j, e) }
        val row = tx.run(a, readOnly = true) { j -> j.queryForMap("SELECT * FROM taktovia.audit_event WHERE id = ?", id) }
        assertEquals("system:audit-test", row["actor_ref"]); assertEquals("identity.organization.changed", row["action"])
        assertEquals("vehicle:$v", row["object_ref"]); assertEquals(e.correlationId, row["correlation_id"]); assertEquals(a.value, row["tenant_id"])
        assertTrue(row["occurred_at"] != null)
        val changes = row["redacted_changes"].toString()
        assertTrue(changes.contains("NEW") && !changes.contains("geheim"), changes)
    }

    @Test fun `fehlgeschlagene Fachaenderung hinterlaesst keinen verwaisten Auditeintrag`() {
        val v = UUID.randomUUID()
        assertThrows<IllegalStateException> { tx.run(a) { j -> newVehicle(j, v); port.record(j, entry(v)); error("Fachfehler nach Audit") } }
        assertThrows<DataAccessException> { tx.run(a) { j -> port.record(j, entry(v)); newVehicle(j, v); newVehicle(j, v) } }   // PK-Verletzung nach dem Audit
        assertEquals(emptyList<String>(), audits(a, v))
    }

    @Test fun `Audit ohne Mandantenkontext und Insert mit fremder tenant_id scheitern`() {
        val v = UUID.randomUUID()
        assertThrows<Exception> { port.record(JdbcTemplate(pool), entry(v)) }   // kein TenantTransactions -> kein Kontext
        assertThrows<DataAccessException> { tx.run(a) { j ->
            j.update("INSERT INTO taktovia.audit_event(tenant_id,id,actor_ref,action,object_ref,occurred_at,correlation_id,redacted_changes) VALUES (?,?,?,?,?,now(),?,'{}'::jsonb)",
                b.value, UUID.randomUUID(), "system:x", "a.b", "vehicle:$v", UUID.randomUUID()) } }
        assertEquals(emptyList<String>(), audits(b, v))
    }

    @Test fun `UPDATE DELETE und TRUNCATE auf audit_event scheitern fuer die Runtime-Rolle, nur INSERT und SELECT sind vergeben`() {
        val v = UUID.randomUUID(); tx.run(a) { j -> port.record(j, entry(v)) }
        for (sql in listOf("UPDATE taktovia.audit_event SET action='x.y'", "DELETE FROM taktovia.audit_event", "TRUNCATE taktovia.audit_event"))
            assertThrows<DataAccessException>(sql) { tx.run(a) { it.update(sql) } }
        val privs = JdbcTemplate(TestDatabase.admin()).queryForList(
            "SELECT privilege_type FROM information_schema.role_table_grants WHERE table_schema='taktovia' AND table_name='audit_event' AND grantee='taktovia_runtime'", String::class.java).toSet()
        assertEquals(setOf("INSERT", "SELECT"), privs)
        assertEquals(1, audits(a, v).size)
    }

    @Test fun `mit Kontext A ist keine Auditzeile von B lesbar`() {
        val vb = UUID.randomUUID(); tx.run(b) { j -> port.record(j, entry(vb)) }
        assertEquals(emptyList<String>(), audits(a, vb)); assertEquals(1, audits(b, vb).size)
        assertEquals(0, tx.run(a, readOnly = true) { it.queryForObject("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ?", Int::class.java, b.value) })
    }

    @Test fun `hundert parallele Aenderungen ergeben hundert Eintraege ohne Luecke`() {
        val ids = (1..100).map { UUID.randomUUID() }; val start = CountDownLatch(1); val exec = Executors.newFixedThreadPool(10)
        try {
            val fs = ids.map { v -> exec.submit(Callable { start.await(); tx.run(a) { j -> newVehicle(j, v); port.record(j, entry(v)) } }) }
            start.countDown(); fs.forEach { it.get() }
        } finally { exec.shutdownNow() }
        val found = tx.run(a, readOnly = true) { j -> j.queryForList("SELECT object_ref FROM taktovia.audit_event WHERE object_ref = ANY(?)", String::class.java,
            ids.map { "vehicle:$it" }.toTypedArray()) }
        assertEquals(ids.map { "vehicle:$it" }.toSet(), found.toSet()); assertEquals(100, found.size)
    }

    @Test fun `Aenderungsversuch an einem Eintrag parallel zu neuen Eintraegen scheitert und die Eintraege sind vollstaendig (Paragraph 4_5)`() {
        val base = UUID.randomUUID(); val baseId = tx.run(a) { j -> port.record(j, entry(base)) }
        val news = (1..30).map { UUID.randomUUID() }; val start = CountDownLatch(1); val exec = Executors.newFixedThreadPool(8)
        try {
            val writers = news.map { v -> exec.submit(Callable { start.await(); tx.run(a) { j -> port.record(j, entry(v)) } }) }
            val tamper = (1..10).map { exec.submit(Callable { start.await(); runCatching { tx.run(a) { it.update("UPDATE taktovia.audit_event SET action='x.hacked' WHERE id=?", baseId) } }.isFailure }) }
            start.countDown(); writers.forEach { it.get() }
            assertTrue(tamper.all { it.get() }, "jeder Änderungsversuch scheitert")
        } finally { exec.shutdownNow() }
        assertEquals("identity.organization.changed", tx.run(a, readOnly = true) { it.queryForObject("SELECT action FROM taktovia.audit_event WHERE id=?", String::class.java, baseId) })
        assertEquals(30, tx.run(a, readOnly = true) { j -> j.queryForObject("SELECT count(*) FROM taktovia.audit_event WHERE object_ref = ANY(?)", Int::class.java, news.map { "vehicle:$it" }.toTypedArray()) })
    }

    @Test fun `Sicherheitsereignis wird mit security-Aktion auditiert, ohne Mandantenkontext nur protokolliert`() {
        val sp = SecurityEventPort(port); val obj = UUID.randomUUID()
        tx.run(a) { j -> sp.report(j, SecurityEvent(ActorRef.service("inbox"), AuditActions.SECURITY_PAYLOAD_HASH_MISMATCH, ObjectRef("event", obj), UUID.randomUUID(), mapOf("consumer" to "c1"))) }
        assertEquals(1, tx.run(a, readOnly = true) { it.queryForObject("SELECT count(*) FROM taktovia.audit_event WHERE object_ref=? AND action LIKE 'security.%'", Int::class.java, "event:$obj") })
        sp.reportWithoutTenant(SecurityEvent(ActorRef.system("login"), AuditActions.SECURITY_ACCESS_DENIED, ObjectRef("login", "x"), UUID.randomUUID()))
    }
}
