package de.taktovia.platform.tenant

import de.taktovia.platform.TestDatabase
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** WP-M1-01 (MUL-001, SEC-001): Kontext je Transaktion, kein Kontext danach, keine Vermischung bei Poolwiederverwendung. */
class TenantTransactionsTest {
    companion object {
        private val a = TenantId(UUID.randomUUID()); private val b = TenantId(UUID.randomUUID())
        private val va = UUID.randomUUID(); private val vb = UUID.randomUUID()
        private lateinit var pool: com.zaxxer.hikari.HikariDataSource
        private lateinit var tx: TenantTransactions

        @BeforeAll @JvmStatic fun setup() {
            TestDatabase.require()
            val admin = JdbcTemplate(TestDatabase.admin())
            for ((t, v) in listOf(a to va, b to vb)) {
                admin.update("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?, 'ACTIVE')", t.value, "t-$t")
                admin.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'x','{}'::jsonb)", t.value, v)
            }
            pool = TestDatabase.runtime(poolSize = 2); tx = TenantTransactions(pool)
        }
        @AfterAll @JvmStatic fun teardown() { if (::pool.isInitialized) pool.close() }
    }

    private fun vehicles(j: org.springframework.jdbc.core.JdbcOperations) =
        j.queryForList("SELECT id FROM taktovia.vehicle", UUID::class.java).toSet()

    @Test fun `A sieht nur A und B nur B`() {
        assertEquals(setOf(va), tx.run(a) { vehicles(it) })
        assertEquals(setOf(vb), tx.run(b) { vehicles(it) })
    }

    @Test fun `ohne Kontext keine Zeilen und Schreiben fuer fremden Mandanten scheitert`() {
        // direkt über den Pool, ohne TenantTransactions: kein Kontext
        assertEquals(0, JdbcTemplate(pool).queryForObject("SELECT count(*) FROM taktovia.vehicle", Int::class.java))
        assertThrows<DataAccessException> {
            tx.run(a) { it.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'x','{}'::jsonb)", b.value, UUID.randomUUID()) }
        }
    }

    @Test fun `nach Commit Rollback und Fehler wirkt kein Kontext auf der wiederverwendeten Verbindung`() {
        val raw = JdbcTemplate(pool)
        fun leak() = raw.queryForObject("SELECT coalesce(current_setting('app.tenant_id', true), '')", String::class.java)
        tx.run(a) { vehicles(it) }; assertEquals("", leak())
        assertThrows<IllegalStateException> { tx.run(a) { throw IllegalStateException("boom") } }; assertEquals("", leak())
        assertThrows<DataAccessException> { tx.run(a) { it.queryForObject("SELECT 1/0", Int::class.java) } }; assertEquals("", leak())
        assertEquals(0, raw.queryForObject("SELECT count(*) FROM taktovia.vehicle", Int::class.java))
    }

    @Test fun `tausend Wechsel auf demselben Pool ohne Kontextvermischung`() {
        val exec = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 1000).map { i -> Callable { val t = if (i % 2 == 0) a else b; t to tx.run(t) { vehicles(it) } } }
            val results = exec.invokeAll(tasks).map { it.get() }
            assertEquals(1000, results.size)
            results.forEach { (t, ids) -> assertEquals(setOf(if (t == a) va else vb), ids) }
        } finally { exec.shutdownNow() }
    }

    @Test fun `Batchlauf setzt Kontext je Mandant und hinterlaesst nach Abbruch keinen`() {
        val res = tx.forEachTenant(listOf(a, b, a)) { t, j ->
            if (t == b) throw IllegalStateException("Abbruch bei b")
            vehicles(j)
        }
        assertEquals(setOf(a, b), res.keys)
        assertEquals(setOf(va), res.getValue(a).getOrThrow())
        assertTrue(res.getValue(b).isFailure)
        assertEquals("", JdbcTemplate(pool).queryForObject("SELECT coalesce(current_setting('app.tenant_id', true), '')", String::class.java))
        assertThrows<IllegalArgumentException> { tx.forEachTenant((0..1000).map { TenantId(UUID.randomUUID()) }) { _, _ -> } }
    }

    @Test fun `schreibende Transaktion bleibt bei Fehler vollstaendig zurueckgerollt`() {
        val id = UUID.randomUUID()
        assertThrows<IllegalStateException> {
            tx.run(a) { it.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'x','{}'::jsonb)", a.value, id); error("nach Insert") }
        }
        assertEquals(setOf(va), tx.run(a) { vehicles(it) })
    }
}
