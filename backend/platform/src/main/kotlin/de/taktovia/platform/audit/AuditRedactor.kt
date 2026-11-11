package de.taktovia.platform.audit

import java.util.UUID

/**
 * Maskierung für `audit_event.redacted_changes` (WP-M1-46 AK 3). Schema: docs/umsetzungsplan/vertraege/schemas/audit-redacted-changes.v1.schema.json.
 * - Schlüssel für Geheimnisse (Token, Passwort, Schlüssel, Autorisierung …) und Personendaten (Mail, Telefon, Name, Adresse, Geburtsdatum …):
 *   Wert wird durch `[redacted]` ersetzt;
 * - Werte, die wie Token, Kartennummern (Luhn), IBAN oder Mailadressen aussehen, werden ersetzt, auch unter harmlosen Schlüsseln;
 * - Mandantenkennungen (`tenant_id`, `*_tenant_id`) anderer Mandanten werden entfernt (`[foreign-tenant]`);
 * - alle maskierten Pfade stehen sortiert unter `_redacted` (Nachvollziehbarkeit, ohne Werte).
 */
object AuditRedactor {
    const val MASK = "[redacted]"
    const val FOREIGN = "[foreign-tenant]"
    private const val MAX_DEPTH = 6; private const val MAX_ENTRIES = 50; private const val MAX_STRING = 500

    private val SECRET_KEY = Regex("(?i)(pass(word|wd)?|secret|token|authorization|auth|api[_-]?key|credential|cookie|signature|private[_-]?key|otp|pin|cvc|cvv|bearer|session)")
    private val PERSONAL_KEY = Regex("(?i)(e-?mail|phone|mobile|telefon|first[_-]?name|last[_-]?name|full[_-]?name|vorname|nachname|birth|geburt|address|adresse|street|strasse|postal|iban|card|pan)")
    private val TENANT_KEY = Regex("(?i)(^|_)tenant_id$")
    private val EMAIL = Regex("[^\\s@\"]+@[^\\s@\"]+\\.[^\\s@\"]+")
    private val JWT = Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}")
    private val BEARER = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val IBAN = Regex("\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b")
    private val CARD = Regex("\\b(?:\\d[ -]?){13,19}\\b")
    private val KEY_FORMAT = Regex("[a-z_][a-z0-9_.]{0,62}")

    class Result(val json: Map<String, Any?>, val redactedPaths: List<String>)

    fun redact(changes: Map<String, Any?>, currentTenant: UUID): Result {
        val paths = sortedSetOf<String>()
        val out = LinkedHashMap<String, Any?>()
        var n = 0
        for ((k, v) in changes) {
            if (++n > MAX_ENTRIES) { paths.add("_truncated"); break }
            out[normKey(k)] = node(normKey(k), v, 1, currentTenant, paths)
        }
        if (paths.isNotEmpty()) out["_redacted"] = paths.toList()
        return Result(out, paths.toList())
    }

    private fun normKey(k: String): String = k.lowercase().replace(Regex("[^a-z0-9_.]"), "_").let { if (KEY_FORMAT.matches(it)) it else "key_" + it.take(55).ifEmpty { "x" } }

    private fun node(path: String, v: Any?, depth: Int, tenant: UUID, paths: MutableSet<String>): Any? {
        val key = path.substringAfterLast('.')
        return when {
            v == null -> null
            TENANT_KEY.containsMatchIn(key) -> {
                val other = runCatching { UUID.fromString(v.toString()) }.getOrNull()
                if (other == tenant) v.toString() else { paths.add(path); FOREIGN }
            }
            SECRET_KEY.containsMatchIn(key) || PERSONAL_KEY.containsMatchIn(key) -> { paths.add(path); MASK }
            depth > MAX_DEPTH -> { paths.add("$path(zu tief)"); MASK }
            v is Map<*, *> -> LinkedHashMap<String, Any?>().also { m ->
                var n = 0
                for ((ck, cv) in v) { if (++n > MAX_ENTRIES) { paths.add("$path._truncated"); break }; val nk = normKey(ck.toString()); m[nk] = node("$path.$nk", cv, depth + 1, tenant, paths) }
            }
            v is Iterable<*> -> v.take(MAX_ENTRIES).mapIndexed { i, e -> node("$path[$i]", e, depth + 1, tenant, paths) }
            v is Boolean || v is Number -> v
            v is UUID || v is Enum<*> || v is java.time.temporal.TemporalAccessor -> v.toString()
            else -> maskString(path, v.toString(), paths)
        }
    }

    private fun maskString(path: String, s: String, paths: MutableSet<String>): String {
        if (EMAIL.containsMatchIn(s) || JWT.containsMatchIn(s) || BEARER.containsMatchIn(s) || IBAN.containsMatchIn(s) || cardLike(s)) { paths.add(path); return MASK }
        return if (s.length > MAX_STRING) s.take(MAX_STRING) + "…" else s
    }

    private fun cardLike(s: String) = CARD.findAll(s).any { luhn(it.value.filter(Char::isDigit)) }

    private fun luhn(d: String): Boolean {
        var sum = 0; var alt = false
        for (c in d.reversed()) { var n = c - '0'; if (alt) { n *= 2; if (n > 9) n -= 9 }; sum += n; alt = !alt }
        return d.length in 13..19 && sum % 10 == 0
    }
}
