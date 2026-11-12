package de.taktovia.platform.logging

import java.util.UUID

/** Mandanten- und Korrelationsbezug der aktuellen Ausführung (Thread-lokal; Fachcode setzt ihn nur über [mit]). */
object LogKontext {
    private class Kontext(val tenantId: UUID?, val correlationId: UUID?)
    private val aktuell = ThreadLocal<Kontext?>()

    fun <T> mit(tenantId: UUID?, correlationId: UUID?, block: () -> T): T {
        val vorher = aktuell.get()
        aktuell.set(Kontext(tenantId, correlationId))
        try { return block() } finally { if (vorher == null) aktuell.remove() else aktuell.set(vorher) }
    }

    fun tenantId(): UUID? = aktuell.get()?.tenantId
    fun correlationId(): UUID? = aktuell.get()?.correlationId
}

/**
 * Strukturierte, maskierte Logzeile (JSON) eines Moduls: nur Felder der [Positivliste], Werte maskiert, Mandanten- und
 * Korrelationsbezug aus dem [LogKontext] (nie aus Freitext). Nicht zugelassene Felder erscheinen nur als Name unter `dropped_fields`
 * (ohne Wert), damit ein vergessener Eintrag auffällt, ohne Daten preiszugeben.
 */
object LogZeile {
    fun bauen(modul: String, event: String, felder: Map<String, Any?> = emptyMap(), fehler: Throwable? = null): String {
        val ok = Positivliste.zugelassen(modul)
        val out = LinkedHashMap<String, Any?>()
        out["event"] = event
        out["module"] = modul
        LogKontext.tenantId()?.let { out["tenant_id"] = it.toString() }
        LogKontext.correlationId()?.let { out["correlation_id"] = it.toString() }
        val dropped = ArrayList<String>()
        for ((k, v) in felder) {
            if (k in ok && k != "tenant_id" && k != "correlation_id" && k != "event") out[k] = if (v is Number || v is Boolean) v else Maskierung.maskText(v?.toString()).take(256)
            else dropped += k.take(64).filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }
        }
        if (dropped.isNotEmpty()) out["dropped_fields"] = dropped
        if (fehler != null) out["error"] = Maskierung.beschreibe(fehler)
        return json(out)
    }

    private fun json(m: Map<String, Any?>): String = buildString {
        append('{')
        var first = true
        for ((k, v) in m) {
            if (!first) append(','); first = false
            append('"').append(esc(k)).append("\":")
            when (v) {
                null -> append("null")
                is Number, is Boolean -> append(v)
                is List<*> -> append(v.joinToString(",", "[", "]") { "\"" + esc(it.toString()) + "\"" })
                else -> append('"').append(esc(v.toString())).append('"')
            }
        }
        append('}')
    }

    private fun esc(s: String) = buildString { for (c in s) when { c == '\\' -> append("\\\\"); c == '"' -> append("\\\""); c < ' ' -> append(' '); else -> append(c) } }
}
