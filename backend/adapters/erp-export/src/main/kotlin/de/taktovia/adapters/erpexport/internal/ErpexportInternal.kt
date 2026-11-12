package de.taktovia.adapters.erpexport.internal

/** Nur innerhalb des Adapters sichtbar: kanonische JSON-Ausgabe ohne Bibliothek (Schlüsselreihenfolge, Escapes und Zahlen sind festgelegt). */
internal object CanonicalJson {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> string(sb, v)
            is Long, is Int -> sb.append(v.toString())
            is Boolean -> sb.append(v.toString())
            is Map<*, *> -> { sb.append('{'); v.entries.forEachIndexed { i, e -> if (i > 0) sb.append(','); string(sb, e.key as String); sb.append(':'); append(sb, e.value) }; sb.append('}') }
            is List<*> -> { sb.append('['); v.forEachIndexed { i, e -> if (i > 0) sb.append(','); append(sb, e) }; sb.append(']') }
            else -> throw IllegalArgumentException("nicht darstellbar: ${v.javaClass.simpleName}")   // insbesondere kein Double/Float
        }
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch < ' ' -> sb.append("\\u%04x".format(ch.code))
            else -> sb.append(ch)
        }
        sb.append('"')
    }
}
