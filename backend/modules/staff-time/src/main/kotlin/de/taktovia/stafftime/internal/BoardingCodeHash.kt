package de.taktovia.stafftime.internal

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hash des Boarding-Codes (WP-M3-30): PBKDF2-HMAC-SHA256 mit zufälligem Salz je Code und optionalem serverseitigem Pfeffer (nicht in der Datenbank). Format
 * `pbkdf2-sha256$<Iterationen>$<Salz>$<Hash>` (Base64 ohne Auffüllung). Abweichung vom Detailvertrag (Argon2id): das JDK bringt kein Argon2 mit, und eine neue Abhängigkeit
 * berührt die gesperrten Gradle-Lockfiles aller Module; PBKDF2 gehört zum JDK. Der Code ist kurz (10^6 Möglichkeiten): den Schutz bei einem Datenbankabfluss trägt der Pfeffer und die
 * Fehlversuchsgrenze, nicht die Iterationszahl.
 */
internal object BoardingCodeHash {
    private const val ITERATIONS = 100_000
    private val random = SecureRandom()
    private val b64 = Base64.getEncoder().withoutPadding()
    private val unb64 = Base64.getDecoder()

    fun hash(code: String, pepper: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return "pbkdf2-sha256$$ITERATIONS$${b64.encodeToString(salt)}$${b64.encodeToString(derive(code, pepper, salt, ITERATIONS))}"
    }

    fun verify(code: String, pepper: String, stored: String): Boolean {
        val p = stored.split('$')
        if (p.size != 4 || p[0] != "pbkdf2-sha256") return false
        val iterations = p[1].toIntOrNull()?.takeIf { it in 1_000..10_000_000 } ?: return false
        return MessageDigest.isEqual(derive(code, pepper, unb64.decode(p[2]), iterations), unb64.decode(p[3]))
    }

    /** Zufälliger Code aus 6 Ziffern (kryptografischer Zufall). */
    fun newCode(): String = "%06d".format(random.nextInt(1_000_000))

    private fun derive(code: String, pepper: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec((code + pepper).toCharArray(), salt, iterations, 256)).encoded
}
