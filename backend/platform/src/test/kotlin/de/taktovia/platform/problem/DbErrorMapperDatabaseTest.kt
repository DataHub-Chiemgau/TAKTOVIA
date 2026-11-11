package de.taktovia.platform.problem

import de.taktovia.platform.TestDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID

/** Echte Datenbankfehler (kein Mock) werden ohne Datenbankmeldung auf Problem-Codes abgebildet (Kap. 02 §4.2). */
class DbErrorMapperDatabaseTest {
    @Test
    fun reject_mutation_sqlstate_23514_wird_zu_immutable_record_ohne_rohe_meldung() {
        TestDatabase.require()
        val tenant = UUID.randomUUID(); val id = UUID.randomUUID()
        val thrown = TestDatabase.admin().connection.use { c ->
            c.createStatement().use { st ->
                st.execute("INSERT INTO taktovia.tenant(id, name, status) VALUES ('$tenant', 'dbmapper', 'ACTIVE')")
                st.execute("INSERT INTO taktovia.audit_event(tenant_id, id, actor_ref, action, object_ref, occurred_at, correlation_id, redacted_changes) VALUES ('$tenant', '$id', 'user:test', 'test.mutation', 'booking:1', now(), '${UUID.randomUUID()}', '{}')")
                try { st.execute("UPDATE taktovia.audit_event SET action = 'test.other' WHERE id = '$id'"); null } catch (e: SQLException) { e }
            }
        }
        assertNotNull(thrown, "reject_mutation() muss das UPDATE verweigern")
        assertEquals("23514", thrown!!.sqlState)
        assertEquals(true, thrown.message!!.contains("immutable record"), "die rohe Meldung existiert in der Datenbank ...")
        val p = DbErrorMapper.map(thrown)
        assertEquals(ErrorCode.IMMUTABLE_RECORD, p.code)
        val text = Problem(p.code, UUID.randomUUID(), p.detail, p.violations).toMap().toString().lowercase()
        listOf("immutable record", "audit_event", "23514", "reject_mutation", "taktovia.audit").forEach { assertFalse(text.contains(it), "... gelangt aber nie zur API: $text") }
    }

    @Test
    fun rls_with_check_verstoss_wird_zu_forbidden_ohne_objektangabe() {
        TestDatabase.require()
        val tenant = UUID.randomUUID(); val other = UUID.randomUUID()
        TestDatabase.admin().connection.use { c -> c.createStatement().use {
            it.execute("INSERT INTO taktovia.tenant(id, name, status) VALUES ('$tenant', 'rls-a', 'ACTIVE'), ('$other', 'rls-b', 'ACTIVE')") } }
        val thrown = TestDatabase.runtime().use { ds -> ds.connection.use { c ->
            c.autoCommit = false
            c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)").use { it.setString(1, tenant.toString()); it.execute() }
            try {
                c.createStatement().use { it.execute("INSERT INTO taktovia.legal_entity(tenant_id, id, name, country) VALUES ('$other', '${UUID.randomUUID()}', 'fremd', 'DE')") }
                null
            } catch (e: SQLException) { c.rollback(); e }
        } }
        assertNotNull(thrown, "WITH CHECK muss den fremden Mandanten verweigern")
        assertEquals("42501", thrown!!.sqlState)
        val p = DbErrorMapper.map(thrown)
        assertEquals(ErrorCode.FORBIDDEN, p.code)
        assertEquals(null, p.detail)
    }
}
