package de.taktovia.app.identity

import de.taktovia.app.security.AuthenticatedPrincipal
import de.taktovia.app.security.PrincipalType
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Unit-Tests der signierten Kontextbindung des Tenantwechsels (keine Datenbank). */
class ContextTicketCodecTest {
    private val iss = "https://idp.test/realms/x"
    private fun principal(sub: UUID = UUID.randomUUID(), sid: String? = "sid-0000001") =
        AuthenticatedPrincipal(iss, sub, PrincipalType.HUMAN, "operator-web", sid, "loa2", emptySet(), emptySet(), emptyList())
    private val secret = ByteArray(32) { it.toByte() }

    @Test
    fun ausstellen_und_pruefen_bindet_an_konto_sitzung_und_issuer() {
        val p = principal(); val t = TenantId(UUID.randomUUID()); val svc = UUID.randomUUID()
        val codec = ContextTicketCodec(secret, Duration.ofMinutes(5))
        val (ticket, exp) = codec.issue(p, iss, t, svc)
        val sel = codec.verify(ticket, p, iss)
        assertEquals(t, sel.tenant); assertEquals(svc, sel.service); assertEquals(exp.epochSecond, sel.expiresAt.epochSecond)
        assertNull(codec.verify(codec.issue(p, iss, t, null).first, p, iss).service)
        assertFalse(ticket.contains(p.subject.toString()), "das Ticket nennt das Subject nicht")

        fun invalid(ticket: String, who: AuthenticatedPrincipal = p, issuer: String = iss) = assertThrows(ContextTicketException::class.java) { codec.verify(ticket, who, issuer) }.also { assertFalse(it.expired) }
        invalid(ticket, principal(sub = UUID.randomUUID()))                       // anderes Konto
        invalid(ticket, principal(sub = p.subject, sid = "andere-sitzung-1"))     // andere Sitzung
        invalid(ticket, issuer = "https://anderer-issuer")                        // anderer Issuer
        val parts = ticket.split('.')
        // Signatur verändert: ein Zeichen in der Mitte (das letzte Base64-Zeichen trägt Füllbits; A/B dort kann dieselben Bytes ergeben)
        val sig = parts[2]; val mid = sig.length / 2
        invalid("${parts[0]}.${parts[1]}." + sig.substring(0, mid) + (if (sig[mid] == 'A') 'B' else 'A') + sig.substring(mid + 1))
        invalid("${parts[0]}.${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("${UUID.randomUUID()}|-|${exp.epochSecond}".toByteArray())}.${parts[2]}")  // Nutzdaten ausgetauscht
        invalid("garbage"); invalid(""); invalid("v2.${parts[1]}.${parts[2]}"); invalid("a".repeat(1000))
        // anderer Schlüssel (andere Instanz mit anderem Geheimnis)
        assertThrows(ContextTicketException::class.java) { ContextTicketCodec(ByteArray(32) { 7 }, Duration.ofMinutes(5)).verify(ticket, p, iss) }
    }

    @Test
    fun ablauf_ist_von_faelschung_unterscheidbar() {
        val p = principal(); val t = TenantId(UUID.randomUUID())
        val start = Instant.parse("2026-11-01T10:00:00Z")
        val issue = ContextTicketCodec(secret, Duration.ofMinutes(10), Clock.fixed(start, ZoneOffset.UTC)).issue(p, iss, t, null).first
        val later = ContextTicketCodec(secret, Duration.ofMinutes(10), Clock.fixed(start.plusSeconds(601), ZoneOffset.UTC))
        assertTrue(assertThrows(ContextTicketException::class.java) { later.verify(issue, p, iss) }.expired)
        assertEquals(t, ContextTicketCodec(secret, Duration.ofMinutes(10), Clock.fixed(start.plusSeconds(599), ZoneOffset.UTC)).verify(issue, p, iss).tenant)
    }

    @Test
    fun zu_kurzer_schluessel_wird_abgewiesen() {
        assertThrows(IllegalArgumentException::class.java) { ContextTicketCodec(ByteArray(16), Duration.ofMinutes(1)) }
    }

    @Test
    fun textformen_von_kontext_und_akteur_nennen_keine_personendaten() {
        val actor = de.taktovia.platform.context.Actor(de.taktovia.platform.context.ActorType.HUMAN, iss, "geheimes-subject-4711", "sid-geheim-1", true)
        val ctx = de.taktovia.platform.context.RequestContext(actor, TenantId(UUID.randomUUID()), setOf("tenant_admin"), setOf("memberships:read"), emptySet(), UUID.randomUUID())
        val text = "$actor $ctx ${de.taktovia.identity.api.SubjectRef(iss, "geheimes-subject-4711")}"
        listOf("geheimes-subject", "sid-geheim", "tenant_admin", "memberships:read").forEach { assertFalse(text.contains(it), "toString nennt '$it'") }
    }
}
