package de.taktovia.identity

import de.taktovia.identity.api.IdentityAccountContainment
import de.taktovia.identity.api.IdentityApi
import de.taktovia.identity.api.IdentityTenantContainment
import de.taktovia.identity.api.SessionRef
import de.taktovia.identity.api.SessionVerdict
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.secret.SecretRef
import de.taktovia.platform.security.ContainmentOutcome
import de.taktovia.platform.security.FileSecretRotation
import de.taktovia.platform.security.SecurityContainment
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

/**
 * WP-M1-41: die echten Wirkungen der Eindämmung gegen die Datenbank (Runtime-Rolle): Kontosperre mit Sitzungssperre, Mandant `SUSPENDED`
 * nur für Betreiberkonten, Audit der Maßnahme. Der IdP ist ein Aufzeichnungsersatz (Keycloak nicht ausgeführt).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityContainmentAdaptersTest {
    private lateinit var fx: IdentityFixture
    private val idp = RecordingIdp()
    private lateinit var api: IdentityApi
    private lateinit var svc: SecurityContainment
    private val root = Files.createTempDirectory("contain-id")

    @BeforeAll fun up() {
        fx = IdentityFixture(8)
        api = IdentityApi.create(fx.tx, fx.audit, SecurityEventPort(fx.audit), fx.outbox, idp = idp, blockCache = FlakyBlockCache())
        svc = SecurityContainment(fx.tx, fx.audit, IdentityAccountContainment(api.sessions), IdentityTenantContainment(api.platform), FileSecretRotation(root))
    }
    @AfterAll fun down() = fx.close()

    private fun ctx(t: de.taktovia.platform.tenant.TenantId, subject: String, m: UUID, scopes: Set<String>) =
        RequestContext(Actor(ActorType.HUMAN, fx.issuer, subject, "sid-$subject", true), t, setOf("tenant_admin"), scopes, emptySet(), UUID.randomUUID(), membershipIds = setOf(m))

    @Test fun konto_sperre_und_mandant_suspended_wirken_und_sind_auditiert() {
        val t = fx.tenant(); val victim = UUID.randomUUID(); val adm = UUID.randomUUID().toString()
        fx.seedMembership(t, victim.toString(), "dispatcher"); val am = fx.seedMembership(t, adm, "tenant_admin")
        val opSubject = "op-" + UUID.randomUUID(); fx.adminExec("INSERT INTO taktovia.platform_operator_account(issuer, subject) VALUES (?, ?)", fx.issuer, opSubject)
        val scopes = api.scopePolicy.scopesFor(setOf("tenant_admin")) + SecurityContainment.SCOPE
        val session = SessionRef(fx.issuer, victim.toString(), "sid-" + UUID.randomUUID(), Instant.now())
        assertEquals(SessionVerdict.ACTIVE, api.sessionGate.check(t, session))

        assertEquals(ContainmentOutcome.EXECUTED, svc.lockAccount(ctx(t, adm, am, scopes), victim, "Verdacht"))
        assertEquals(SessionVerdict.ACCOUNT_LOCKED, api.sessionGate.check(t, session), "gesperrtes Konto erreicht keine geschützte API mehr")
        assertEquals(ContainmentOutcome.ALREADY_IN_STATE, svc.lockAccount(ctx(t, adm, am, scopes), victim, "Verdacht"))

        // Mandant sperren: nur Betreiberkonto; Administrator des Mandanten wird abgewiesen (403)
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { svc.suspendTenant(ctx(t, adm, am, scopes), t) }.code)
        assertEquals(ContainmentOutcome.EXECUTED, svc.suspendTenant(ctx(t, opSubject, am, scopes), t))
        assertEquals(listOf("SUSPENDED"), fx.adminStrings("SELECT status FROM taktovia.tenant WHERE id = ?", t.value))
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, assertThrows<ProblemException> { svc.suspendTenant(ctx(t, opSubject, am, scopes), t) }.code)

        val actions = fx.adminStrings("SELECT action FROM taktovia.audit_event WHERE tenant_id = ? ORDER BY occurred_at", t.value)
        assertTrue(AuditActions.SECURITY_ACCOUNT_LOCKED in actions && AuditActions.TENANT_STATUS_CHANGED in actions, actions.toString())
        assertEquals(3, actions.count { it == AuditActions.SECURITY_CONTAINMENT_EXECUTED }, actions.toString())
        assertEquals(1, actions.count { it == AuditActions.SECURITY_ACCESS_DENIED }, "403 des Besitzers ist auditiert: $actions")
    }
}
