package de.taktovia.platform.transport

import java.net.URI

/** Art einer ausgehenden oder eingehenden Verbindung, für die TLS Pflicht ist (LH-6.18-SEC-002). */
enum class Endpoint { POSTGRES, RABBITMQ, REDIS, OBJECT_STORE, IDP, HTTP }

/**
 * Transportrichtlinie: Klartext-Konfiguration wird beim Start abgewiesen, die Anwendung startet nicht (WP-M1-36).
 * Planungsdefault (OP-31): TLS 1.2 und 1.3, keine älteren Versionen; PostgreSQL nur mit `sslmode=verify-full` (verschlüsselt und
 * Server-Identität geprüft). Cipher-Vorgabe: Standardsuiten des JDK/Gateways für TLS 1.2+ (keine RC4/3DES/CBC-SHA1-Sondererlaubnis).
 */
object TransportPolicy {
    val ENABLED_PROTOCOLS = listOf("TLSv1.3", "TLSv1.2")
    private val SCHEMES = mapOf(
        Endpoint.RABBITMQ to "amqps", Endpoint.REDIS to "rediss", Endpoint.OBJECT_STORE to "https",
        Endpoint.IDP to "https", Endpoint.HTTP to "https",
    )

    /** Leere Liste = zulässig. Meldungen enthalten nie Zugangsdaten (Userinfo und Query werden nicht ausgegeben). */
    fun violations(kind: Endpoint, name: String, uri: String): List<String> {
        if (kind == Endpoint.POSTGRES) {
            if (!uri.startsWith("jdbc:postgresql:")) return listOf("$name: kein PostgreSQL-JDBC-URL")
            val mode = Regex("[?&]sslmode=([a-z-]+)").find(uri)?.groupValues?.get(1)
            return if (mode == "verify-full") emptyList() else listOf("$name: PostgreSQL ohne sslmode=verify-full (ist: ${mode ?: "nicht gesetzt"})")
        }
        val scheme = try { URI(uri).scheme } catch (e: Exception) { null } ?: return listOf("$name: URI ungültig")
        val want = SCHEMES.getValue(kind)
        return if (scheme.lowercase() == want) emptyList() else listOf("$name: Schema '$scheme' ist Klartext, erwartet '$want'")
    }

    /** Prüft alle übergebenen Endpunkte (Name -> Art, URI); null/leer bedeutet „nicht konfiguriert" und wird übersprungen. */
    fun check(endpoints: List<Triple<Endpoint, String, String?>>): List<String> =
        endpoints.filter { !it.third.isNullOrBlank() }.flatMap { (k, n, u) -> violations(k, n, u!!) }
}
