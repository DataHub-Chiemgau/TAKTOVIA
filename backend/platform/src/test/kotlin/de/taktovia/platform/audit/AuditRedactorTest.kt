package de.taktovia.platform.audit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import tools.jackson.databind.json.JsonMapper

/** WP-M1-46 AK 3: `redacted_changes` enthält keine Token, Passwörter, Kartendaten, Personendaten oder Inhalte eines anderen Mandanten. */
class AuditRedactorTest {
    private val me = UUID.randomUUID(); private val other = UUID.randomUUID()
    private fun json(m: Map<String, Any?>) = JsonMapper.builder().build().writeValueAsString(AuditRedactor.redact(m, me).json)

    @Test fun `Geheimnisse und Personendaten unter ihren Schluesseln werden maskiert, fachliche Werte bleiben`() {
        val r = AuditRedactor.redact(mapOf("status" to "ACTIVE", "password" to "x1", "access_token" to "abc", "Authorization" to "Bearer abcdefghij",
            "email" to "a@b.example", "api_key" to "k", "role_code" to "dispatcher", "count" to 3), me)
        assertEquals(listOf("access_token", "api_key", "authorization", "email", "password"), r.redactedPaths)
        assertEquals("ACTIVE", r.json["status"]); assertEquals("dispatcher", r.json["role_code"]); assertEquals(3, r.json["count"])
        assertEquals(AuditRedactor.MASK, r.json["password"])
    }

    @Test fun `Werte die wie Token, Karte, IBAN oder Mail aussehen werden auch unter harmlosen Schluesseln maskiert`() {
        val jwt = "eyJhbGciOiJIUzI1" + "NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijk"
        val card = "4111 1111 " + "1111 1111"        // Testkartennummer (Luhn-gültig)
        val s = json(mapOf("note" to jwt, "memo" to "Karte $card bezahlt", "ref" to "DE89370400440532013000", "comment" to "mail an " + "x" + "@" + "y.example", "ok" to "Rechnung 12345"))
        assertFalse(s.contains(jwt) || s.contains("4111") || s.contains("DE8937") || s.contains("y.example"), s)
        assertTrue(s.contains("Rechnung 12345"))
        assertEquals(4, AuditRedactor.redact(mapOf("note" to jwt, "memo" to card, "ref" to "DE89370400440532013000", "comment" to "x" + "@" + "y.example"), me).redactedPaths.size)
    }

    @Test fun `verschachtelte Strukturen werden durchlaufen und Mandantenkennungen fremder Mandanten entfernt`() {
        val r = AuditRedactor.redact(mapOf("before" to mapOf("tenant_id" to other.toString(), "nested" to listOf(mapOf("secret" to "s", "v" to 1))),
            "owner_tenant_id" to me.toString(), "partner_tenant_id" to other.toString()), me)
        val s = JsonMapper.builder().build().writeValueAsString(r.json)
        assertFalse(s.contains(other.toString()), s); assertTrue(s.contains(me.toString()))
        assertTrue(s.contains(AuditRedactor.FOREIGN)); assertTrue(r.redactedPaths.contains("before.nested[0].secret"))
    }

    @Test fun `Format von Akteur, Objekt und Aktion wird erzwungen`() {
        assertThrows<IllegalArgumentException> { ActorRef("Max Mustermann") }
        assertThrows<IllegalArgumentException> { ActorRef("user:a@b.example") }
        assertThrows<IllegalArgumentException> { ObjectRef("Membership", "x") }
        assertThrows<IllegalArgumentException> { AuditEntry(ActorRef.system("t"), "Beliebig", ObjectRef("x_y", "1"), UUID.randomUUID()) }
        assertThrows<IllegalArgumentException> { SecurityEvent(ActorRef.system("t"), "identity.membership.granted", ObjectRef("x_y", "1"), UUID.randomUUID()) }
        AuditActions.CATALOG.forEach { assertTrue(AuditActions.FORMAT.matches(it), it) }
    }

    @Test fun `Schluessel werden normalisiert und Mengen begrenzt`() {
        val big = (1..80).associate { "k$it" to it }
        val r = AuditRedactor.redact(big, me)
        assertEquals(50 + 1, r.json.size); assertTrue(r.redactedPaths.contains("_truncated"))
        assertTrue(AuditRedactor.redact(mapOf("Ärger Schlüssel" to 1), me).json.keys.all { Regex("[a-z_][a-z0-9_.]*").matches(it) })
    }
}
