package de.taktovia.platform

import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.secret.FileSecretStore
import de.taktovia.platform.secret.SecretRef
import de.taktovia.platform.secret.SecretResolver
import de.taktovia.platform.security.AccountContainment
import de.taktovia.platform.security.ContainmentOutcome
import de.taktovia.platform.security.FileSecretRotation
import de.taktovia.platform.security.SecretRotation
import de.taktovia.platform.security.SecurityContainment
import de.taktovia.platform.security.TenantContainment
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.util.UUID

/**
 * WP-M1-41: Eindämmung im Sicherheitsvorfall. Berechtigung, Abweisung und Audit laufen gegen die echte Datenbank, Secret-Rotation gegen echte
 * Dateien. Die Wirkung auf Konto und Mandant liegt hinter den Ports der besitzenden Module; hier zeichnen Testports den Aufruf auf (die echten
 * Adapter prüft `SecurityContainmentAdaptersTest` im Modul `identity` gegen die Datenbank).
 */
class SecurityContainmentTest {
    companion object {
        private lateinit var s: AuditTestSupport
        @BeforeAll @JvmStatic fun setup() { s = AuditTestSupport() }
        @AfterAll @JvmStatic fun teardown() { if (::s.isInitialized) s.close() }
    }

    private class Calls : AccountContainment, TenantContainment {
        val locked = mutableListOf<UUID>(); val suspended = mutableListOf<TenantId>()
        override fun lockAccount(ctx: RequestContext, subject: UUID, reason: String) = ContainmentOutcome.EXECUTED.also { locked += subject }
        override fun suspendTenant(ctx: RequestContext, tenant: TenantId) = ContainmentOutcome.EXECUTED.also { suspended += tenant }
    }

    private val calls = Calls()
    private val root = Files.createTempDirectory("containment")
    private val domain = "taktovia-development-secrets"
    private val ref = SecretRef(domain, "webhook_signing_key")
    private val resolver = SecretResolver(domain, FileSecretStore(root))
    private val svc = SecurityContainment(s.tx, s.audit, calls, calls, FileSecretRotation(root))
    private fun ctx(scopes: Set<String>) = s.context(s.a, scopes = scopes)
    private val allowed get() = ctx(setOf(SecurityContainment.SCOPE))

    private fun count(action: String) = s.tx.run(s.a, readOnly = true) { j -> j.queryForObject("SELECT count(*) FROM taktovia.audit_event WHERE action = ?", Int::class.java, action)!! }

    @Test fun `eindaemmung_berechtigte_rolle_sperrt_konto_mandant_und_rotiert_secret_auditiert`() {
        Files.createDirectories(root.resolve(domain)); Files.writeString(root.resolve(domain).resolve(ref.name), "alt-wert\n")
        val before = resolver.resolve(ref).reveal()
        val acct = UUID.randomUUID(); val c = allowed
        val executed = count(AuditActions.SECURITY_CONTAINMENT_EXECUTED)
        assertEquals(ContainmentOutcome.EXECUTED, svc.lockAccount(c, acct, "Verdacht auf Kompromittierung"))
        assertEquals(ContainmentOutcome.EXECUTED, svc.suspendTenant(c, s.b))
        assertEquals(ContainmentOutcome.EXECUTED, svc.rotateSecret(c, ref))
        assertEquals(listOf(acct), calls.locked); assertEquals(listOf(s.b), calls.suspended)
        val after = resolver.resolve(ref).reveal()
        assertNotEquals(before, after); assertTrue(after.length >= 40)
        assertEquals(executed + 3, count(AuditActions.SECURITY_CONTAINMENT_EXECUTED))
        val rows = s.tx.run(s.a, readOnly = true) { j -> j.queryForList("SELECT object_ref || ' ' || redacted_changes::text FROM taktovia.audit_event WHERE action = ? AND correlation_id = ?",
            String::class.java, AuditActions.SECURITY_CONTAINMENT_EXECUTED, c.correlationId) }.map { it!! }
        assertEquals(3, rows.size, rows.toString())
        assertTrue(rows.any { it.contains("user:$acct") && it.contains("account_lock") })
        assertTrue(rows.any { it.contains("tenant:${s.b}") && it.contains("tenant_suspend") })
        assertTrue(rows.any { it.contains("secret:${ref.name}") && it.contains("secret_rotate") })
        assertFalse(rows.joinToString().contains(after) || rows.joinToString().contains("Verdacht"), "weder Secretwert noch Freitext im Audit")
    }

    @Test fun `eindaemmung_durch_unberechtigte_rolle_wird_abgewiesen`() {
        val c = ctx(setOf("audit:read", "sessions:read")); val acct = UUID.randomUUID()
        val denied = count(AuditActions.SECURITY_ACCESS_DENIED); val executed = count(AuditActions.SECURITY_CONTAINMENT_EXECUTED)
        Files.createDirectories(root.resolve(domain)); Files.writeString(root.resolve(domain).resolve(ref.name), "unveraendert\n")
        val ex = listOf(
            assertThrows<ProblemException> { svc.lockAccount(c, acct, "x") },
            assertThrows<ProblemException> { svc.suspendTenant(c, s.b) },
            assertThrows<ProblemException> { svc.rotateSecret(c, ref) },
        )
        assertTrue(ex.all { it.code == ErrorCode.FORBIDDEN })
        assertTrue(calls.locked.isEmpty() && calls.suspended.isEmpty(), "keine Wirkung")
        assertEquals("unveraendert", resolver.resolve(ref).reveal())
        assertEquals(denied + 3, count(AuditActions.SECURITY_ACCESS_DENIED), "Abweisung ist auditiert")
        assertEquals(executed, count(AuditActions.SECURITY_CONTAINMENT_EXECUTED))
    }
}
