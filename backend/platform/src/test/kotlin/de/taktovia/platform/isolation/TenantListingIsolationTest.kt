package de.taktovia.platform.isolation

import de.taktovia.platform.TestDatabase
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** WP-M1-11: Listen-/Export-Baustein mit erzwungenem Tenantfilter (gegen die echte Datenbank unter der Runtime-Rolle). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantListingIsolationTest {
    private val pool = lazy { TestDatabase.runtime(4) }
    private lateinit var tx: TenantTransactions
    private val a = TenantId(UUID.randomUUID()); private val b = TenantId(UUID.randomUUID())
    private val source = ListSource("vehicle", listOf("id", "owner_org_ref"))

    @BeforeAll fun up() {
        TestDatabase.require(); tx = TenantTransactions(pool.value)
        val admin = JdbcTemplate(TestDatabase.admin())
        listOf(a, b).forEach { admin.update("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?, 'ACTIVE')", it.value, "list-$it") }
        for ((t, n) in listOf(a to 3, b to 2)) tx.run(t) { j -> repeat(n) { i -> j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (taktovia.current_tenant(),?,?,'{}')", UUID.randomUUID(), "o-$t-$i") } }
    }
    @AfterAll fun down() { if (pool.isInitialized()) pool.value.close() }

    @Test
    fun jede_abfrage_enthaelt_den_mandantenfilter_und_ist_nicht_umgehbar() {
        val l = TenantListing.of(source)
        assertTrue(l.sql().contains("WHERE tenant_id = taktovia.current_tenant()"))
        assertTrue(l.sql("owner_org_ref = ?").contains("WHERE tenant_id = taktovia.current_tenant() AND (owner_org_ref = ?)"))
        for (bad in listOf("tenant_id <> ?", "1=1 OR true", "x = 1; DROP TABLE y", "a = 1 -- ", "taktovia.current_tenant() IS NULL"))
            assertThrows(IllegalArgumentException::class.java, { tx.run(a) { j -> l.page(j, 10, { rs, _ -> rs.getString(1) }, bad) } }, bad)
        for (badName in listOf("vehicle; DROP", "Vehicle", "a b")) assertThrows(IllegalArgumentException::class.java) { ListSource(badName, listOf("id")) }
        assertThrows(IllegalArgumentException::class.java) { ListSource("vehicle", listOf("owner_org_ref")) }
    }

    @Test
    fun liste_und_export_zeigen_nur_den_eigenen_mandanten() {
        val l = TenantListing.of(source)
        val rowsA = tx.run(a) { j -> l.page(j, 100, { rs, _ -> rs.getString("owner_org_ref") }) }
        val rowsB = tx.run(b) { j -> l.page(j, 100, { rs, _ -> rs.getString("owner_org_ref") }) }
        assertEquals(3, rowsA.size); assertEquals(2, rowsB.size)
        assertTrue(rowsA.all { it.contains(a.toString()) }); assertTrue(rowsB.all { it.contains(b.toString()) })
        assertEquals(2, tx.run(a) { j -> l.page(j, 1, { rs, _ -> rs.getString(1) }) }.size, "limit + 1 Zeilen: es gibt eine Folgeseite")
    }
}
