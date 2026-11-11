package de.taktovia.documentsfinance

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.taktovia.documentsfinance.api.CreateCostCenter
import de.taktovia.documentsfinance.api.DocumentsfinanceApi
import de.taktovia.documentsfinance.api.UpdateCostCenter
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** WP-M1-09: Kostenstellen unter der Runtime-Rolle: mehrere je Mandant, Zuordnung zu Gebiet/Dienst, getrennte Mandanten, genau ein Erfolg bei paralleler Anlage. */
class CostCenterAdminTest {
    private companion object {
        private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }
        init {
            if (env("TAKTOVIA_DB_RUNTIME_PASSWORD") == null || env("TAKTOVIA_DB_ADMIN_PASSWORD") == null) {
                if (env("TAKTOVIA_REQUIRE_DB") == "true") throw AssertionError("Datenbank erforderlich (TAKTOVIA_REQUIRE_DB=true)")
                assumeTrue(false, "Datenbank-Zugangsdaten nicht gesetzt")
            }
        }
        private val url = "jdbc:postgresql://${env("TAKTOVIA_DB_HOST") ?: "127.0.0.1"}:${env("TAKTOVIA_DB_PORT") ?: "5432"}/${env("TAKTOVIA_DB_NAME") ?: "taktovia"}"
        private fun pool(user: String, pw: String, n: Int) = HikariDataSource(HikariConfig().also { it.jdbcUrl = url; it.username = user; it.password = pw; it.maximumPoolSize = n; it.minimumIdle = 1 })
        val ds = pool("taktovia_runtime", env("TAKTOVIA_DB_RUNTIME_PASSWORD")!!, 10)
        val admin = pool(env("TAKTOVIA_DB_ADMIN_USER") ?: "dev_admin", env("TAKTOVIA_DB_ADMIN_PASSWORD")!!, 1)
        val tx = TenantTransactions(ds)
        val api = DocumentsfinanceApi.create(ContextTransactions(tx, object : ContextRevalidator { override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, lock: Boolean) {} }), JdbcAuditPort())
        @JvmStatic @AfterAll fun close() { ds.close(); admin.close() }

        class World(val tenant: TenantId, val le: UUID, val region: UUID, val service: UUID) {
            val ctx = RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/x", UUID.randomUUID().toString(), "sid", true), tenant, setOf("tenant_admin"), setOf("organization:write"), emptySet(), UUID.randomUUID())
        }
        fun world(): World {
            val t = UUID.randomUUID(); val le = UUID.randomUUID(); val org = UUID.randomUUID(); val region = UUID.randomUUID(); val service = UUID.randomUUID(); val rev = UUID.randomUUID()
            admin.connection.use { c -> c.prepareStatement("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,'ACTIVE')").use { it.setObject(1, t); it.setString(2, "t-$t"); it.executeUpdate() } }
            tx.run(TenantId(t)) { j ->
                j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", t, le)
                j.update("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,?,?,CAST(? AS text[]))", t, org, le, "Org", "f-$org", "{TRANSPORT_COMPANY}")
                j.update("INSERT INTO taktovia.region(tenant_id,id,name,created_by) VALUES (?,?,'R','user:test')", t, region)
                j.update("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'SERVICE',?,1,'1','{}','2026-01-01','user:test','Test')", t, rev, service)
                j.update("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,created_by) VALUES (?,?,?,'D','Europe/Berlin',?,'user:test')", t, service, org, rev)
            }
            return World(TenantId(t), le, region, service)
        }
        fun count(sql: String, vararg a: Any?) = admin.connection.use { c -> c.prepareStatement(sql).use { ps -> a.forEachIndexed { i, x -> ps.setObject(i + 1, x) }; ps.executeQuery().use { it.next(); it.getInt(1) } } }
    }

    @Test
    fun mehrere_kostenstellen_je_mandant_zuordenbar() {
        val w = world()
        val a = api.costCenters.create(w.ctx, CreateCostCenter("KST-1", "Linienverkehr", w.le, regionId = w.region))
        val b = api.costCenters.create(w.ctx, CreateCostCenter("KST-2", "Rufbus", w.le, serviceId = w.service))
        val c = api.costCenters.create(w.ctx, CreateCostCenter("KST-3", "Verwaltung", w.le))
        assertEquals(w.region, a.regionId); assertEquals(w.service, b.serviceId); assertEquals(null, c.regionId)
        assertEquals(setOf("KST-1", "KST-2", "KST-3"), api.costCenters.list(w.ctx, 10, null).map { it.code }.toSet())
        val moved = api.costCenters.update(w.ctx, c.id, 1, UpdateCostCenter("Verwaltung neu", w.region, w.service))
        assertEquals(2L, moved.revision); assertEquals(w.region, moved.regionId); assertEquals("KST-3", moved.code)
        assertEquals(4, count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'finance.cost_center.changed'", w.tenant.value))
    }

    @Test
    fun gleichnamige_kostenstellen_in_a_und_b_sind_getrennt() {
        val a = world(); val b = world()
        val ka = api.costCenters.create(a.ctx, CreateCostCenter("KST-100", "Gleich", a.le)); val kb = api.costCenters.create(b.ctx, CreateCostCenter("KST-100", "Gleich", b.le))
        assertNotEquals(ka.id, kb.id)
        assertEquals(listOf(ka.id), api.costCenters.list(a.ctx, 10, null).map { it.id }); assertEquals(listOf(kb.id), api.costCenters.list(b.ctx, 10, null).map { it.id })
        // fremde Kostenstelle ist nicht änderbar und fremder Rechtsträger/Region nicht referenzierbar (404)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { api.costCenters.update(a.ctx, kb.id, 1, UpdateCostCenter("x")) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { api.costCenters.create(a.ctx, CreateCostCenter("X-1", "x", b.le)) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { api.costCenters.create(a.ctx, CreateCostCenter("X-2", "x", a.le, regionId = b.region)) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { api.costCenters.create(a.ctx, CreateCostCenter("X-3", "x", a.le, serviceId = b.service)) }.code)
    }

    @Test
    fun doppelte_business_ref_je_kind_wird_abgewiesen_und_revisionen() {
        val w = world(); val k = api.costCenters.create(w.ctx, CreateCostCenter("KST-9", "Eins", w.le))
        assertEquals(ErrorCode.CONFLICT, assertThrows<ProblemException> { api.costCenters.create(w.ctx, CreateCostCenter("KST-9", "Zwei", w.le)) }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows<ProblemException> { api.costCenters.create(w.ctx, CreateCostCenter("", "Zwei", w.le)) }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows<ProblemException> { api.costCenters.create(w.ctx, CreateCostCenter("K".repeat(41), "Zwei", w.le)) }.code)
        assertEquals(k.revision, api.costCenters.update(w.ctx, k.id, 1, UpdateCostCenter("Eins")).revision)   // ohne Wirkung
        api.costCenters.update(w.ctx, k.id, 1, UpdateCostCenter("Neu"))
        assertEquals(ErrorCode.REVISION_MISMATCH, assertThrows<ProblemException> { api.costCenters.update(w.ctx, k.id, 1, UpdateCostCenter("Alt")) }.code)
    }

    @Test
    fun paralleles_anlegen_gleicher_kostenstelle_genau_ein_erfolg() {
        val w = world(); val pool = Executors.newFixedThreadPool(8); val start = CountDownLatch(1)
        val fut = (1..8).map { pool.submit<Result<Any>> { start.await(); runCatching { api.costCenters.create(w.ctx, CreateCostCenter("PAR-1", "Parallel", w.le)) } } }
        start.countDown()
        val out = fut.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, out.count { it.isSuccess }, out.toString())
        assertEquals(7, out.count { (it.exceptionOrNull() as? ProblemException)?.code == ErrorCode.CONFLICT }, out.toString())
        assertEquals(1, count("SELECT count(*) FROM taktovia.cost_object WHERE tenant_id = ? AND business_ref = 'PAR-1'", w.tenant.value))
        assertEquals(1, count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'finance.cost_center.changed'", w.tenant.value))
    }
}
