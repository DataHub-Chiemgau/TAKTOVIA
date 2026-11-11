package de.taktovia.platform.fieldprotection

import de.taktovia.platform.secret.SecretRef
import de.taktovia.platform.secret.SecretResolver
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Liefert Schlüsselverschlüsselungsschlüssel (KEK) zu einer Schlüsselkennung. Der Wert kommt aus der Secret-Verwaltung (WP-M1-36). */
interface KekProvider {
    /** Kennung des Schlüssels, mit dem neu verschlüsselt wird. */
    val currentKeyId: String
    /** 32-Byte-AES-Schlüssel zur Kennung; unbekannte Kennung wirft [IllegalStateException]. */
    fun kek(keyId: String): ByteArray
}

/**
 * KEKs als Secret-Referenzen (`secret://<domäne>/<name>`, Wert = Base64 von 32 Byte). Jeder Zugriff löst die Referenz neu auf: Rotation des
 * Secrets wirkt ohne Neustart; eine neue Schlüsselkennung (`v2`) wird für neue Werte aktiv, ältere Kennungen bleiben zum Entschlüsseln
 * bis zum Umschlüsseln ([EnvelopeCipher.rewrap]) in [refs].
 */
class SecretKekProvider(private val resolver: SecretResolver, private val refs: Map<String, SecretRef>, override val currentKeyId: String) : KekProvider {
    init { require(currentKeyId in refs) { "aktuelle Schlüsselkennung ohne Referenz" } }
    override fun kek(keyId: String): ByteArray {
        val ref = refs[keyId] ?: error("unbekannte Schlüsselkennung")
        val key = Base64.getDecoder().decode(resolver.resolve(ref).reveal().trim())
        check(key.size == 32) { "KEK muss 32 Byte lang sein" }
        return key
    }
}

/**
 * Envelope-Verschlüsselung für Spaltenwerte im Ruhezustand (WP-M1-31). Je Wert ein zufälliger Datenschlüssel (DEK, AES-256-GCM), der mit dem
 * KEK der Secret-Verwaltung (AES-256-GCM) umhüllt im Wert mitgespeichert wird. Der [context] (Mandant, Spalte, Zeile) ist als zusätzliche
 * authentifizierte Daten (AAD) gebunden: ein in eine andere Zeile oder einen anderen Mandanten kopierter Wert lässt sich nicht entschlüsseln.
 *
 * Format: `0x01 | len(keyId) | keyId | wrapNonce(12) | wrappedDek(48) | nonce(12) | ciphertext+tag`. Klartext, Schlüssel und Secretwerte
 * erscheinen nie in Ausnahmetexten.
 */
class EnvelopeCipher(private val keys: KekProvider, private val random: SecureRandom = SecureRandom()) {
    fun encrypt(plaintext: ByteArray, context: String): ByteArray {
        val keyId = keys.currentKeyId
        val dek = ByteArray(32).also(random::nextBytes)
        val wrapNonce = ByteArray(NONCE).also(random::nextBytes)
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val wrapped = gcm(Cipher.ENCRYPT_MODE, keys.kek(keyId), wrapNonce, "kek|$keyId|$context", dek)
        val data = gcm(Cipher.ENCRYPT_MODE, dek, nonce, context, plaintext)
        val id = keyId.toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(2 + id.size + NONCE + wrapped.size + NONCE + data.size)
            .put(VERSION).put(id.size.toByte()).put(id).put(wrapNonce).put(wrapped).put(nonce).put(data).array()
    }

    fun decrypt(blob: ByteArray, context: String): ByteArray {
        val p = parse(blob)
        val dek = gcm(Cipher.DECRYPT_MODE, kekFor(p.keyId), p.wrapNonce, "kek|${p.keyId}|$context", p.wrapped)
        return gcm(Cipher.DECRYPT_MODE, dek, p.nonce, context, p.data)
    }

    /** Schlüsselkennung, mit der [blob] umhüllt ist (für Rotation und Umschlüsselung). */
    fun keyIdOf(blob: ByteArray): String = parse(blob).keyId

    /** Umschlüsseln auf den aktuellen KEK, ohne dass der Klartext den Baustein verlässt; DEK und Daten bleiben unverändert verschlüsselt. */
    fun rewrap(blob: ByteArray, context: String): ByteArray {
        val p = parse(blob)
        if (p.keyId == keys.currentKeyId) return blob
        val dek = gcm(Cipher.DECRYPT_MODE, kekFor(p.keyId), p.wrapNonce, "kek|${p.keyId}|$context", p.wrapped)
        val newId = keys.currentKeyId
        val wrapNonce = ByteArray(NONCE).also(random::nextBytes)
        val wrapped = gcm(Cipher.ENCRYPT_MODE, keys.kek(newId), wrapNonce, "kek|$newId|$context", dek)
        val id = newId.toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(2 + id.size + NONCE + wrapped.size + NONCE + p.data.size)
            .put(VERSION).put(id.size.toByte()).put(id).put(wrapNonce).put(wrapped).put(p.nonce).put(p.data).array()
    }

    /** Unbekannte oder nicht auflösbare Schlüsselkennung beim Entschlüsseln: einheitlicher Fehler ohne Hinweis auf Schlüssel oder Ursache. */
    private fun kekFor(keyId: String): ByteArray = try { keys.kek(keyId) } catch (e: RuntimeException) { throw FieldDecryptionException() }

    private class Parsed(val keyId: String, val wrapNonce: ByteArray, val wrapped: ByteArray, val nonce: ByteArray, val data: ByteArray)

    private fun parse(blob: ByteArray): Parsed {
        try {
            val b = ByteBuffer.wrap(blob)
            require(b.get() == VERSION) { "unbekannte Version" }
            val idLen = b.get().toInt() and 0xff
            val id = ByteArray(idLen).also(b::get)
            val wrapNonce = ByteArray(NONCE).also(b::get)
            val wrapped = ByteArray(DEK_WRAPPED).also(b::get)
            val nonce = ByteArray(NONCE).also(b::get)
            val data = ByteArray(b.remaining()).also(b::get)
            require(data.size >= TAG) { "zu kurz" }
            return Parsed(String(id, Charsets.US_ASCII), wrapNonce, wrapped, nonce, data)
        } catch (e: RuntimeException) {
            throw FieldDecryptionException()
        }
    }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: String, input: ByteArray): ByteArray = try {
        Cipher.getInstance("AES/GCM/NoPadding").also {
            it.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce))
            it.updateAAD(aad.toByteArray(Charsets.UTF_8))
        }.doFinal(input)
    } catch (e: java.security.GeneralSecurityException) {
        throw FieldDecryptionException()
    }

    companion object {
        private const val VERSION: Byte = 1
        private const val NONCE = 12
        private const val TAG = 16
        private const val DEK_WRAPPED = 32 + TAG
        /** AAD-Kontext eines Spaltenwerts. */
        fun context(tenant: java.util.UUID, column: ProtectedColumn, rowId: java.util.UUID) = "$tenant|${column.table}.${column.column}|$rowId"
    }
}

/** Entschlüsselung fehlgeschlagen (falscher Schlüssel, manipulierte oder fremde Daten). Enthält weder Schlüssel noch Daten. */
class FieldDecryptionException : RuntimeException("Entschlüsselung fehlgeschlagen")
