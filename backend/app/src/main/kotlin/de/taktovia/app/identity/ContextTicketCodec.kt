package de.taktovia.app.identity

import de.taktovia.app.security.AuthenticatedPrincipal
import de.taktovia.platform.tenant.TenantId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Die Mandantenbindung ist ungültig; [expired] unterscheidet bloßen Ablauf von Fälschung oder fremdem Konto. */
class ContextTicketException(val expired: Boolean) : RuntimeException(if (expired) "expired" else "invalid")

/**
 * Serverseitig ausgestellte, signierte **Kontextbindung** eines Tenantwechsels (Kap. 05 §5.1 „Mitgliedschaft/Tokenbindung“, WP-M1-06).
 * Sie hält nur die Auswahl „Mandant (und optional Dienst)“ fest, an Aussteller, Subject und IdP-Sitzung des Tokens gebunden
 * (HMAC-SHA256, Schlüssel `taktovia.identity.context-secret`), und verleiht **keine Rechte**: Bei jeder Anfrage wird die Mitgliedschaft
 * erneut aus der Datenbank geprüft. Jeder Tab/Client hält sein eigenes Ticket, daher sind mehrere Kontexte derselben Sitzung unabhängig.
 * Format `v1.<base64url(Mandant|Dienst|Ablauf)>.<base64url(MAC)>`; der MAC deckt zusätzlich Issuer, Subject und Sitzung ab.
 */
class ContextTicketCodec(secret: ByteArray, private val ttl: Duration, private val clock: Clock = Clock.systemUTC()) {
    init { require(secret.size >= 32) { "Kontext-Schlüssel muss mindestens 32 Byte lang sein" } }
    private val key = SecretKeySpec(secret.copyOf(), "HmacSHA256")
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    class Selection(val tenant: TenantId, val service: UUID?, val expiresAt: Instant)

    private fun mac(p: AuthenticatedPrincipal, issuer: String, payload: String): ByteArray =
        Mac.getInstance("HmacSHA256").also { it.init(key) }.doFinal("ctx-v1\n$issuer\n${p.subject}\n${p.sessionId}\n$payload".toByteArray(StandardCharsets.UTF_8))

    fun issue(p: AuthenticatedPrincipal, issuer: String, tenant: TenantId, service: UUID?): Pair<String, Instant> {
        val exp = clock.instant().plus(ttl)
        val payload = "$tenant|${service ?: "-"}|${exp.epochSecond}"
        return "v1.${enc.encodeToString(payload.toByteArray(StandardCharsets.UTF_8))}.${enc.encodeToString(mac(p, issuer, payload))}" to exp
    }

    fun verify(ticket: String, p: AuthenticatedPrincipal, issuer: String): Selection {
        val bad = ContextTicketException(false)
        if (ticket.length > 400) throw bad
        val parts = ticket.split('.')
        if (parts.size != 3 || parts[0] != "v1") throw bad
        val payload: String; val sig: ByteArray
        try { payload = String(dec.decode(parts[1]), StandardCharsets.UTF_8); sig = dec.decode(parts[2]) } catch (_: IllegalArgumentException) { throw bad }
        if (!MessageDigest.isEqual(mac(p, issuer, payload), sig)) throw bad
        val f = payload.split('|')
        if (f.size != 3) throw bad
        return try {
            val exp = Instant.ofEpochSecond(f[2].toLong())
            if (!clock.instant().isBefore(exp)) throw ContextTicketException(true)
            Selection(TenantId.parse(f[0]), if (f[1] == "-") null else UUID.fromString(f[1]), exp)
        } catch (e: ContextTicketException) { throw e } catch (_: RuntimeException) { throw bad }
    }
}
