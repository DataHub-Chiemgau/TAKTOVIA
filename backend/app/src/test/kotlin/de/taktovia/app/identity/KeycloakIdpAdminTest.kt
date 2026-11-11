package de.taktovia.app.identity

import de.taktovia.app.security.KeycloakIdpAdmin
import de.taktovia.identity.api.IdpAdmin
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** HTTP-Verträge des Keycloak-Adapters gegen den lokalen Ersatzserver (echter Keycloak: nicht ausgeführt). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeycloakIdpAdminTest {
    private val kc = FakeKeycloakAdmin("realm-x")
    private val issuer = "https://idp.example/realms/realm-x"
    private fun admin(secret: String = "test-admin-secret") = KeycloakIdpAdmin(kc.baseUrl, "realm-x", issuer, "taktovia-idp-admin", { secret })
    @AfterAll fun close() = kc.close()

    @Test
    fun operationen_nutzen_das_dienstkonto_token_und_die_admin_pfade() {
        val a = admin(); val u = UUID.randomUUID().toString()
        a.endAllSessions(issuer, u); a.endSession(issuer, "sid-abc12345"); a.setAccountEnabled(issuer, u, false); a.startRecovery(issuer, u)
        assertEquals(listOf("POST /users/$u/logout", "DELETE /sessions/sid-abc12345", "PUT /users/$u enabled=false", "PUT /users/$u/execute-actions-email actions=UPDATE_PASSWORD"), kc.calls.toList())
        assertEquals(1, kc.tokensIssued, "das Dienstkonto-Token wird wiederverwendet")
    }

    @Test
    fun kontakt_gilt_nur_mit_verifizierter_adresse() {
        val a = admin(); val u = UUID.randomUUID().toString()
        assertTrue(a.contactVerified(issuer, u))
        kc.user(u).emailVerified = false; assertFalse(a.contactVerified(issuer, u))
        kc.user(u).emailVerified = true; kc.user(u).email = null; assertFalse(a.contactVerified(issuer, u), "ohne Adresse kein Kontakt")
    }

    @Test
    fun fehler_sind_nie_stille_erfolge() {
        val u = UUID.randomUUID().toString()
        assertThrows<IdpAdmin.Unavailable> { admin("falsches-secret").endAllSessions(issuer, u) }
        assertThrows<IdpAdmin.Unavailable> { admin().endAllSessions("https://fremder.idp/realms/x", u) }
        assertThrows<IdpAdmin.Unavailable> { admin().endAllSessions(issuer, "kein-uuid") }
        kc.down = true
        try { assertThrows<IdpAdmin.Unavailable> { admin().contactVerified(issuer, u) } } finally { kc.down = false }
        assertThrows<IdpAdmin.Unavailable> { KeycloakIdpAdmin("http://127.0.0.1:1", "realm-x", issuer, "taktovia-idp-admin", { "s" }).endAllSessions(issuer, u) }
    }
}
