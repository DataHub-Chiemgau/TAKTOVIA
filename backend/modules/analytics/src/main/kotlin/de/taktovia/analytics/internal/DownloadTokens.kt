package de.taktovia.analytics.internal

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Kurzlebige, signierte Abruflinks (WP-M5-19): `<nutzlast>.<hmac>` mit Mandant, Auftrag und Ablaufzeit (HMAC-SHA256, Base64url). Der Link ist an genau einen Mandanten und einen Auftrag gebunden;
 * die Rechte des Einlösenden werden beim Abruf zusätzlich geprüft (ein weitergegebener Link öffnet nichts). Der Schlüssel kommt aus der Secret-Domäne der Umgebung, nie aus dem Repository.
 */
internal class DownloadTokens(private val secret: ByteArray) {
    init { require(secret.size >= 32) { "Abruf-Schlüssel muss mindestens 32 Byte lang sein" } }

    private fun mac(data: String): ByteArray = Mac.getInstance("HmacSHA256").also { it.init(SecretKeySpec(secret, "HmacSHA256")) }.doFinal(data.toByteArray(Charsets.UTF_8))
    private val enc = Base64.getUrlEncoder().withoutPadding(); private val dec = Base64.getUrlDecoder()

    fun issue(tenant: UUID, job: UUID, expires: Instant): String {
        val payload = enc.encodeToString("$tenant|$job|${expires.epochSecond}".toByteArray(Charsets.UTF_8))
        return payload + "." + enc.encodeToString(mac(payload))
    }

    /** `true` nur bei gültiger Signatur, passendem Mandanten und Auftrag und nicht abgelaufen. */
    fun valid(token: String, tenant: UUID, job: UUID, now: Instant): Boolean {
        val parts = token.split('.'); if (parts.size != 2) return false
        val sig = runCatching { dec.decode(parts[1]) }.getOrNull() ?: return false
        if (!MessageDigest.isEqual(sig, mac(parts[0]))) return false
        val fields = runCatching { String(dec.decode(parts[0]), Charsets.UTF_8).split('|') }.getOrNull() ?: return false
        if (fields.size != 3 || fields[0] != tenant.toString() || fields[1] != job.toString()) return false
        val exp = fields[2].toLongOrNull() ?: return false
        return now.epochSecond < exp
    }
}
