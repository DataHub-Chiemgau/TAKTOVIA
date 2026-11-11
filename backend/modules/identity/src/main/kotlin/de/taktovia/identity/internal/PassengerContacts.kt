package de.taktovia.identity.internal

import de.taktovia.identity.api.ContactChannel
import de.taktovia.identity.api.ContactKey
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Normalisierung und Prüfung der Kontaktwerte (E-Mail klein geschrieben, Telefon als E.164). Fehler nennen den Wert nie. */
internal object Contacts {
    private val EMAIL = Regex("^[^@\\s]{1,64}@[^@\\s]{1,189}\\.[^@\\s.]{2,}$")
    private val PHONE = Regex("^\\+[1-9][0-9]{6,14}$")

    /** `pointer` ist der JSON-Pointer des Felds in der Anfrage (z. B. `/contact/value`). */
    fun normalize(channel: ContactChannel, raw: String, pointer: String): String {
        val v = when (channel) {
            ContactChannel.EMAIL -> raw.trim().lowercase()
            ContactChannel.PHONE -> raw.trim().replace(Regex("[\\s\\-()/.]"), "")
        }
        val ok = v.length <= 254 && when (channel) { ContactChannel.EMAIL -> EMAIL.matches(v); ContactChannel.PHONE -> PHONE.matches(v) }
        if (!ok) throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_FORMAT))
        return v
    }

    /** Lenient für die Wiederherstellung: ungültige Eingaben ergeben `null` (gleiche Antwort wie ein unbekanntes Konto). */
    fun normalizeOrNull(channel: ContactChannel, raw: String): String? = try { normalize(channel, raw, "/") } catch (e: ProblemException) { null }
}

/** HMACs über Kontakt und Code; der Schlüssel kommt aus der Secret-Verwaltung. Es gibt keine Umkehrfunktion. */
internal class ContactTokens(key: ContactKey) {
    private val spec = SecretKeySpec(key.bytes, "HmacSHA256")
    private val random = SecureRandom()

    private fun mac(input: String): String = Mac.getInstance("HmacSHA256").also { it.init(spec) }.doFinal(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Token des normalisierten Kontakts, je Mandant und Kanal verschieden (nie mandantenübergreifend vergleichbar). */
    fun contactToken(tenant: UUID, channel: ContactChannel, normalized: String): String = mac("contact|$tenant|${channel.name}|$normalized")

    fun codeMac(verificationId: UUID, code: String): String = mac("code|$verificationId|$code")

    fun newCode(length: Int): String = buildString { repeat(length) { append(random.nextInt(10)) } }

    /** Zeitkonstanter Vergleich zweier Hexwerte. */
    fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

/**
 * Klartextformat in `contact_ciphertext`: UTF-8, drei Zeilen `1`, E-Mail, Telefon (leer = nicht gesetzt). Die Werte enthalten nach der Normalisierung kein
 * Leerzeichen und keinen Zeilenumbruch. Wird nur im Feldschutzbaustein ver- und entschlüsselt.
 */
internal object ContactDocument {
    fun encode(email: String?, phone: String?): ByteArray = "1\n${email.orEmpty()}\n${phone.orEmpty()}".toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray?): Pair<String?, String?> {
        if (bytes == null) return null to null
        val lines = String(bytes, Charsets.UTF_8).split('\n')
        check(lines.size == 3 && lines[0] == "1") { "unbekanntes Kontaktformat" }
        return lines[1].ifEmpty { null } to lines[2].ifEmpty { null }
    }
}
