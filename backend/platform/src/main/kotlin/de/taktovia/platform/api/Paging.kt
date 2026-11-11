package de.taktovia.platform.api

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Position in der stabilen Sortierung `(created_at, id)`: die Liste setzt strikt nach dieser Position fort. */
data class CursorPosition(val createdAt: Instant, val id: UUID)

/** Bindung des Cursors an Mandant, Akteur und Ressource: ein fremder Cursor liefert keinen Datenzugriff (Kap. 05 §5.1). */
data class CursorScope(val tenant: String, val actor: String, val resource: String)

/**
 * Opaker, signierter Cursor. Format `v1.<base64url(Nutzdaten)>.<base64url(HMAC-SHA256)>`; Nutzdaten sind
 * `micros|uuid|sha256(scope)`. Der Signaturschlüssel kommt aus der Konfiguration (`taktovia.api.cursor-secret`),
 * nie aus dem Repository. Jede Abweichung (Format, Signatur, Scope) ergibt dieselbe Antwort 400 `INVALID_CURSOR`.
 */
class CursorCodec(secret: ByteArray) {
    init { require(secret.size >= 32) { "Cursor-Schlüssel muss mindestens 32 Byte lang sein" } }
    private val key = SecretKeySpec(secret.copyOf(), "HmacSHA256")
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val d64 = Base64.getUrlDecoder()

    private fun mac(data: ByteArray) = Mac.getInstance("HmacSHA256").also { it.init(key) }.doFinal(data)
    private fun scopeHash(s: CursorScope) =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest("${s.tenant}\u0000${s.actor}\u0000${s.resource}".toByteArray()))

    fun encode(pos: CursorPosition, scope: CursorScope): String {
        val micros = pos.createdAt.epochSecond * 1_000_000 + pos.createdAt.nano / 1_000
        val payload = "$micros|${pos.id}|${scopeHash(scope)}".toByteArray()
        return "v1.${b64.encodeToString(payload)}.${b64.encodeToString(mac(payload))}"
    }

    fun decode(token: String, scope: CursorScope): CursorPosition {
        val bad = ProblemException(ErrorCode.INVALID_CURSOR, "Der Cursor ist ungültig oder abgelaufen; Liste ohne Cursor neu beginnen.")
        if (token.length > 512) throw bad
        val parts = token.split('.')
        if (parts.size != 3 || parts[0] != "v1") throw bad
        val payload: ByteArray; val sig: ByteArray
        try { payload = d64.decode(parts[1]); sig = d64.decode(parts[2]) } catch (_: IllegalArgumentException) { throw bad }
        if (!MessageDigest.isEqual(mac(payload), sig)) throw bad
        val f = String(payload).split('|')
        if (f.size != 3 || !MessageDigest.isEqual(f[2].toByteArray(), scopeHash(scope).toByteArray())) throw bad
        return try {
            val micros = f[0].toLong()
            CursorPosition(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000), UUID.fromString(f[1]))
        } catch (_: RuntimeException) { throw bad }
    }
}

/** Antwortform aller Listen: `{items, next_cursor, as_of}`. */
data class Page<T>(val items: List<T>, val nextCursor: String?, val asOf: Instant) {
    fun toMap(render: (T) -> Any?): Map<String, Any?> = mapOf("items" to items.map(render), "next_cursor" to nextCursor, "as_of" to asOf.toString())
}

/** Listenbaustein: `limit` 1–100 (Standard 50), Sortierung `(created_at, id)` aufsteigend, Cursor signiert. */
object Paging {
    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 100

    fun limit(raw: String?): Int {
        if (raw == null) return DEFAULT_LIMIT
        val n = raw.toIntOrNull()
        if (n == null || n < 1 || n > MAX_LIMIT) throw ProblemException.invalid(Violation("/\$query/limit", if (n == null) ViolationCode.TYPE_MISMATCH else if (n < 1) ViolationCode.BELOW_MINIMUM else ViolationCode.ABOVE_MAXIMUM))
        return n
    }

    /** Position aus dem Cursor; `null` = Anfang. */
    fun position(cursor: String?, codec: CursorCodec, scope: CursorScope): CursorPosition? = cursor?.let { codec.decode(it, scope) }

    /**
     * Baut die Seite aus [rows], die mit `limit + 1` Zeilen ab der Position geladen wurden (sortiert nach
     * `(created_at, id)`). Eine Zeile mehr als [limit] bedeutet: es gibt eine Folgeseite.
     */
    fun <T> page(rows: List<T>, limit: Int, key: (T) -> CursorPosition, codec: CursorCodec, scope: CursorScope, asOf: Instant): Page<T> {
        val items = rows.take(limit)
        val next = if (rows.size > limit) codec.encode(key(items.last()), scope) else null
        return Page(items, next, asOf)
    }
}
