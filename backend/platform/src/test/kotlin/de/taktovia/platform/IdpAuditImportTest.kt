package de.taktovia.platform

import de.taktovia.platform.audit.IdpAuditImporter
import de.taktovia.platform.audit.IdpEvent
import de.taktovia.platform.audit.IdpTenantResolver
import de.taktovia.platform.audit.KeycloakEvents
import de.taktovia.platform.tenant.PlatformTenants
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** WP-M1-12: Übernahme der IdP-Anmeldeereignisse (Keycloak-Ereignisschnittstelle; hier mit JSON-Fixtures, kein Keycloak-Dienst). */
class IdpAuditImportTest {
    companion object {
        private lateinit var s: AuditTestSupport
        private const val ISSUER = "https://idp.test/realms/taktovia"
        private val member = UUID.randomUUID()
        private lateinit var importer: IdpAuditImporter
        @BeforeAll @JvmStatic fun setup() {
            s = AuditTestSupport()
            importer = IdpAuditImporter(s.tx, s.audit, IdpTenantResolver { issuer, subject -> if (issuer == ISSUER && subject == member.toString()) listOf(s.a) else emptyList() })
        }
        @AfterAll @JvmStatic fun teardown() { if (::s.isInitialized) s.close() }
    }

    private fun rows(tenant: TenantId, action: String) = s.tx.run(tenant, readOnly = true) { j ->
        j.queryForList("SELECT actor_ref || '|' || object_ref || '|' || to_char(occurred_at AT TIME ZONE 'UTC','YYYY-MM-DD HH24:MI') || '|' || redacted_changes::text FROM taktovia.audit_event WHERE action = ? ORDER BY occurred_at", String::class.java, action).filterNotNull()
    }

    private fun event(id: String, type: String = "LOGIN", userId: String? = member.toString(), login: String? = "max.muster", error: String? = null, t: Instant = Instant.parse("2026-05-01T08:00:00Z"), details: Map<String, String> = emptyMap()) =
        IdpEvent(id, type, ISSUER, userId, login, "web", error, t, details)

    @Test fun `erfolgreicher_und_fehlgeschlagener_login_erscheinen_mit_akteur_kennung_zeitpunkt_und_ergebnis`() {
        val ok = "ok-" + UUID.randomUUID(); val bad = "bad-" + UUID.randomUUID()
        importer.import(event(ok, "LOGIN", t = Instant.parse("2026-05-01T08:00:00Z")))
        importer.import(event(bad, "LOGIN_ERROR", error = "invalid_user_credentials", t = Instant.parse("2026-05-01T08:01:00Z")))
        val success = rows(s.a, "security.auth.login_succeeded").single { it.contains("2026-05-01 08:00") }
        assertTrue(success.startsWith("user:$member|user:$member|"), success); assertTrue(success.contains("\"result\": \"success\""), success); assertTrue(success.contains("login_ref"), success)
        val failure = rows(s.a, "security.auth.login_failed").single { it.contains("2026-05-01 08:01") }
        assertTrue(failure.contains("\"result\": \"failure\"") && failure.contains("invalid_user_credentials") && failure.contains("login_ref"), failure)
        assertTrue(failure.contains("2026-05-01 08:01"), failure)
    }

    @Test fun `fehlgeschlagener_login_ohne_mandantenkontext_wird_im_systemmandanten_protokolliert`() {
        val unknown = "wer-" + UUID.randomUUID().toString().take(8)
        val r = importer.import(event("sys-" + UUID.randomUUID(), "LOGIN_ERROR", userId = null, login = unknown, error = "user_not_found"))
        assertEquals(listOf(PlatformTenants.SYSTEM), r.tenants)
        val sys = rows(PlatformTenants.SYSTEM, "security.auth.login_failed")
        assertTrue(sys.any { it.startsWith("user:unknown-") && it.contains("user_not_found") }, sys.toString())
        assertFalse(sys.any { it.contains(unknown) }, "Klartext-Anmeldename darf nicht im Eintrag stehen")
        assertTrue(rows(s.a, "security.auth.login_failed").none { it.contains("user_not_found") }, "Fachmandanten sehen Systemmandant-Ereignisse nicht")
    }

    @Test fun `mfa_fehlschlag_und_logout_werden_uebernommen`() {
        importer.import(event("mfa-" + UUID.randomUUID(), "LOGIN_ERROR", error = "invalid_user_credentials", details = mapOf("credential_type" to "otp"), t = Instant.parse("2026-05-02T08:00:00Z")))
        importer.import(event("out-" + UUID.randomUUID(), "LOGOUT", t = Instant.parse("2026-05-02T09:00:00Z")))
        assertEquals(1, rows(s.a, "security.auth.mfa_failed").count { it.contains("2026-05-02 08:00") })
        assertEquals(1, rows(s.a, "security.auth.logout").count { it.contains("2026-05-02 09:00") })
    }

    @Test fun `doppeltes_idp_ereignis_erzeugt_einen_eintrag_ohne_passwort`() {
        val id = "dup-" + UUID.randomUUID(); val t = Instant.parse("2026-06-01T12:00:00Z")
        val secretPw = "Passw0rt-" + UUID.randomUUID(); val secretCode = "483920"
        val e = event(id, "LOGIN_ERROR", error = "invalid_user_credentials", t = t, details = mapOf("password" to secretPw, "code" to secretCode, "otp" to secretCode, "authenticator" to "auth-username-password-form"))
        // sequenziell und parallel doppelt geliefert
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        val results = (0 until 8).map { pool.submit(Callable { go.await(); importer.import(e) }) }
        go.countDown(); val inserted = results.sumOf { it.get().inserted }; pool.shutdown()
        assertEquals(1, inserted, "genau ein Eintrag trotz mehrfacher Lieferung")
        assertEquals(0, importer.import(e).inserted)
        val all = s.tx.run(s.a, readOnly = true) { j -> j.queryForList("SELECT t::text FROM taktovia.audit_event t WHERE occurred_at = ?", String::class.java, java.sql.Timestamp.from(t)).filterNotNull() }
        assertEquals(1, all.size)
        assertFalse(all.single().contains(secretPw) || all.single().contains(secretCode), "Passwort/Code stehen nie im Eintrag: ${all.single()}")
    }

    @Test fun `keycloak_ereignis_json_wird_auf_die_whitelist_abgebildet`() {
        val json = """{"id":"kc-1","time":1780300800000,"type":"LOGIN_ERROR","realmId":"taktovia","clientId":"web","userId":"$member","ipAddress":"203.0.113.9","error":"invalid_user_credentials",
            "details":{"username":"max.muster@example.org","password":"geheim123","code":"123456","auth_method":"openid-connect"}}"""
        val e = KeycloakEvents.parse(json, ISSUER)!!
        assertEquals("kc-1", e.eventId); assertEquals("LOGIN_ERROR", e.type); assertEquals(member.toString(), e.userId); assertEquals("web", e.clientId)
        assertEquals(Instant.ofEpochMilli(1780300800000), e.occurredAt)
        assertFalse(e.toString().contains("geheim123") || e.toString().contains("123456") || e.toString().contains("203.0.113.9"))
        assertNull(KeycloakEvents.parse("""{"id":"x","time":1,"type":"REGISTER"}""", ISSUER), "nicht auditpflichtige Ereignistypen werden übergangen")
    }
}
