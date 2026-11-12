package de.taktovia.farepayment.internal

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest

/** Kanonische JSON-Form für den Inhaltshash der Quote (Beschreibung: [de.taktovia.farepayment.api.QuoteHashing]). Gleitkommazahlen sind nicht zulässig (Geld ist `bigint`). */
internal object CanonicalJson {
    private val mapper = JsonMapper.builder().build()

    fun canonicalize(json: String): String = StringBuilder().also { write(mapper.readTree(json), it) }.toString()

    fun sha256Hex(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun write(n: JsonNode, out: StringBuilder) {
        when {
            n.isObject -> {
                out.append('{')
                val entries = n.properties().sortedWith { a, b -> compareCodepoints(a.key, b.key) }
                entries.forEachIndexed { i, e -> if (i > 0) out.append(','); string(e.key, out); out.append(':'); write(e.value, out) }
                out.append('}')
            }
            n.isArray -> {
                out.append('[')
                n.values().forEachIndexed { i, v -> if (i > 0) out.append(','); write(v, out) }
                out.append(']')
            }
            n.isString -> string(n.asString(), out)
            n.isIntegralNumber -> out.append(n.bigIntegerValue().toString())
            n.isNumber -> throw IllegalArgumentException("Gleitkommazahlen sind in der Quote nicht zulässig")
            n.isBoolean -> out.append(if (n.asBoolean()) "true" else "false")
            n.isNull -> out.append("null")
            else -> throw IllegalArgumentException("Unbekannter JSON-Knoten")
        }
    }

    private fun compareCodepoints(a: String, b: String): Int {
        val x = a.codePoints().toArray(); val y = b.codePoints().toArray()
        for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return x[i].compareTo(y[i])
        return x.size.compareTo(y.size)
    }

    private fun string(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c.code < 0x20 -> out.append("\\u%04x".format(c.code))
            else -> out.append(c)
        }
        out.append('"')
    }
}
