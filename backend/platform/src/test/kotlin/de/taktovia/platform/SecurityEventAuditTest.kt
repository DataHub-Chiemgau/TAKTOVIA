package de.taktovia.platform

import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditFilter
import de.taktovia.platform.audit.AuditPurposes
import de.taktovia.platform.audit.AuditQueryService
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.audit.SecurityEvent
import de.taktovia.platform.tenant.PlatformTenants
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP-M1-12 AK 3 und 6: Katalog der Sicherheitsereignisse, Auffindbarkeit über die Auditabfrage, keine Fremdinhalte, Ereignisse ohne Mandant. */
class SecurityEventAuditTest {
    companion object {
        private lateinit var s: AuditTestSupport
        private lateinit var query: AuditQueryService
        @BeforeAll @JvmStatic fun setup() { s = AuditTestSupport(); query = AuditQueryService(s.ctxTx, s.audit) }
        @AfterAll @JvmStatic fun teardown() { if (::s.isInitialized) s.close() }
    }

    @Test fun `fremdzugriffsereignis_enthaelt_keine_inhalte_des_anderen_mandanten`() {
        val victim = "Erika Mustermann"; val mail = "erika.mustermann@example.org"
        s.tx.run(s.a) { j ->
            s.security.report(j, SecurityEvent(ActorRef.user(UUID.randomUUID()), AuditActions.SECURITY_TENANT_MISMATCH, ObjectRef("tenant", s.b.value), UUID.randomUUID(),
                mapOf("surface" to "header", "tenant_id" to s.b.value.toString(), "requested_tenant_id" to s.b.value.toString(), "customer_name" to victim, "contact_email" to mail,
                    "note" to "Beleg von $mail")))
        }
        val text = s.tx.run(s.a, readOnly = true) { j -> j.queryForList("SELECT redacted_changes::text FROM taktovia.audit_event WHERE action = ?", String::class.java, AuditActions.SECURITY_TENANT_MISMATCH).joinToString() }
        assertTrue(text.contains("header"), text)
        assertFalse(text.contains(victim) || text.contains(mail) || text.contains(s.b.value.toString()), "Fremdinhalt im Ereignis: $text")
    }

    @Test fun `gemeldete_sicherheitsereignisse_sind_ueber_die_auditabfrage_auffindbar`() {
        val ctx = s.context(s.a)
        val actions = listOf(AuditActions.SECURITY_MFA_MISSING, AuditActions.SECURITY_MFA_FAILED, AuditActions.SECURITY_TENANT_MISMATCH,
            AuditActions.SECURITY_ACCOUNT_LOCKED, AuditActions.SECURITY_PAYLOAD_HASH_MISMATCH, AuditActions.SECURITY_ACCESS_DENIED)
        val actor = UUID.randomUUID()
        s.tx.run(s.a) { j -> actions.forEach { s.security.report(j, SecurityEvent(ActorRef.user(actor), it, ObjectRef("user", actor), UUID.randomUUID(), mapOf("reason" to "test"))) } }
        for (a in actions) {
            val found = query.query(ctx, AuditPurposes.INCIDENT_RESPONSE, AuditFilter(actorRef = "user:$actor", action = a), 10, null)
            assertEquals(1, found.size, a); assertEquals("user:$actor", found.single().actorRef)
        }
        assertTrue(AuditActions.CATALOG.containsAll(actions))
    }

    @Test fun `ereignis_ohne_mandantenkontext_wird_im_systemmandanten_protokolliert_und_ist_fachmandanten_unsichtbar`() {
        val corr = UUID.randomUUID()
        s.security.reportWithoutTenant(SecurityEvent(ActorRef.system("login"), AuditActions.SECURITY_LOGIN_FAILED, ObjectRef("login", "abc123"), corr, mapOf("result" to "failure")))
        val sys = s.tx.run(PlatformTenants.SYSTEM, readOnly = true) { j -> j.queryForList("SELECT object_ref FROM taktovia.audit_event WHERE correlation_id = ?", String::class.java, corr) }
        assertEquals(listOf("login:abc123"), sys)
        assertEquals(0, s.tx.run(s.a, readOnly = true) { j -> j.queryForObject("SELECT count(*) FROM taktovia.audit_event WHERE correlation_id = ?", Int::class.java, corr) })
    }
}
