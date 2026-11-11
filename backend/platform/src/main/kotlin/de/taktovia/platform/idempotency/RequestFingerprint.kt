package de.taktovia.platform.idempotency

import de.taktovia.platform.outbox.EventEnvelope
import tools.jackson.databind.json.JsonMapper
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Kanonischer Request-Fingerprint (SHA-256, Hex) für das Idempotenzregister, Version 1 (Detailvertrag `idempotenz-register.md`):
 * eingehen Methode, Operationsname, Pfad (inkl. Ressourcen-ID), Query (nach Name und Wert sortiert), die fachlich relevanten Header
 * ([RELEVANT_HEADERS]) und der Body in kanonischer JSON-Form (Schlüssel sortiert, Zahlen ohne Formatierungsunterschied, Arrayreihenfolge
 * bleibt). Nicht eingehen: `Idempotency-Key` selbst, `traceparent`, Authentisierung, Content-Type und Transportheader.
 * Ein Body, der kein JSON ist, geht als Hash der Rohbytes ein.
 */
object RequestFingerprint {
    /** Fachlich relevante Header: die erwartete Ressourcenrevision. (Mandant und Akteur stehen im Scope des Registers, nicht im Fingerprint.) */
    val RELEVANT_HEADERS = listOf("If-Match")
    private val mapper = JsonMapper.builder().build()

    fun of(method: String, operation: String, path: String, rawQuery: String?, headers: Map<String, String?>, body: ByteArray?): String {
        val sb = StringBuilder("v1\n").append(method.uppercase()).append('\n').append(operation).append('\n').append(path.trimEnd('/').ifEmpty { "/" }).append('\n')
        sb.append(canonicalQuery(rawQuery)).append('\n')
        for (name in RELEVANT_HEADERS) {
            val value = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            sb.append(name.lowercase()).append('=').append(value?.trim() ?: "-").append('\n')
        }
        sb.append(canonicalBody(body))
        return sha256(sb.toString().toByteArray(Charsets.UTF_8))
    }

    fun canonicalQuery(raw: String?): String = raw.orEmpty().split('&').filter { it.isNotEmpty() }.map {
        val kv = it.split('=', limit = 2)
        decode(kv[0]) to decode(kv.getOrElse(1) { "" })
    }.sortedWith(compareBy({ it.first }, { it.second })).joinToString("&") { URLEncoder.encode(it.first, Charsets.UTF_8) + "=" + URLEncoder.encode(it.second, Charsets.UTF_8) }

    private fun decode(s: String) = try { URLDecoder.decode(s, Charsets.UTF_8) } catch (_: IllegalArgumentException) { s }

    private fun canonicalBody(body: ByteArray?): String {
        if (body == null || body.isEmpty()) return "b:-"
        return try { "j:" + EventEnvelope.canonical(mapper.readTree(body)) } catch (_: Exception) { "raw:" + sha256(body) }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
