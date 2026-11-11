package de.taktovia.platform.webhook

import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signaturvertrag für eingehende und ausgehende Webhooks (WP-M1-16, Kap. 05 §5.6; Detailvertrag `webhooks.md`).
 * Header `Taktovia-Signature: t=<Unix-Sekunden>,kid=<Schlüssel-ID>,v1=<hex>`; `v1` = HMAC-SHA-256 über `"<t>."` gefolgt vom **exakten Rohbody**
 * (nicht vom geparsten oder neu serialisierten JSON). Der Zeitstempel steht in der Signatur, ein Replay außerhalb des Zeitfensters (±300 s) wird abgewiesen.
 */
object WebhookSignature {
    const val HEADER = "Taktovia-Signature"
    private val FORMAT = Regex("^t=(\\d{1,12}),kid=([A-Za-z0-9._-]{1,64}),v1=([0-9a-f]{64})$")

    data class Parsed(val timestamp: Instant, val keyId: String, val signature: String)

    /** Schlüssel-ID aus dem Schlüssel selbst (Ausgang): erste 12 Hex-Zeichen von SHA-256; nennt weder Wert noch erlaubt Rückschluss darauf. */
    fun keyId(secret: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(secret).joinToString("") { "%02x".format(it) }.take(12)

    fun hmacHex(secret: ByteArray, timestamp: Instant, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256").also { it.init(SecretKeySpec(secret, "HmacSHA256")) }
        mac.update("${timestamp.epochSecond}.".toByteArray(Charsets.US_ASCII)) // sql-lint: ok Mac.update (HMAC), kein SQL
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    fun header(secret: ByteArray, keyId: String, timestamp: Instant, body: ByteArray) = "t=${timestamp.epochSecond},kid=$keyId,v1=${hmacHex(secret, timestamp, body)}"

    fun parse(header: String?): Parsed? {
        val m = header?.trim()?.let { FORMAT.matchEntire(it) } ?: return null
        return Parsed(Instant.ofEpochSecond(m.groupValues[1].toLong()), m.groupValues[2], m.groupValues[3])
    }

    /** Konstantzeitlicher Vergleich der hex-kodierten Signaturen (kein vorzeitiger Abbruch beim ersten Unterschied). */
    fun matches(expectedHex: String, presentedHex: String): Boolean =
        MessageDigest.isEqual(expectedHex.toByteArray(Charsets.US_ASCII), presentedHex.toByteArray(Charsets.US_ASCII))
}
