package de.taktovia.bookingdispatch.internal

import java.util.UUID

/**
 * Schlüssel des flüchtigen Redis-Caches (Leitlinie 4: Redis nur Cache) und Vergleichsschlüssel des PostgreSQL-Caches (WP-M2-10, Kap. 01 §1.7: Mandant im Schlüssel).
 * Format: `taktovia:route:v1:<mandant>:<profil>:<kartenversion>:<start>:<ziel>`. Jeder Anteil ist Pflicht und darf nur `[A-Za-z0-9._-]` enthalten (kein `:`), damit ein
 * Anteil nie in einen anderen hineinreicht; ein Schlüssel ohne Mandantenanteil ist nicht darstellbar.
 */
internal object RoutingCacheKey {
    private const val PREFIX = "taktovia:route:v1"
    private val PART = Regex("^[A-Za-z0-9._-]{1,64}$")
    private val VALID = Regex("^taktovia:route:v1:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(:[A-Za-z0-9._-]{1,64}){4}$")

    fun redisKey(tenant: UUID, profile: String, mapVersion: String, origin: String, destination: String): String {
        val parts = listOf(profile, mapVersion, origin, destination)
        parts.forEach { require(PART.matches(it)) { "Cache-Schlüsselanteil ungültig oder leer" } }
        return "$PREFIX:$tenant:${parts.joinToString(":")}"
    }

    /** Ein Schlüssel ist nur zulässig, wenn er das Format mit Mandantenanteil hat. */
    fun isValid(key: String): Boolean = VALID.matches(key)

    fun tenantOf(key: String): UUID? = if (isValid(key)) UUID.fromString(key.split(':')[3]) else null
    fun mapVersionOf(key: String): String? = if (isValid(key)) key.split(':')[5] else null
}

/** Flüchtiger Zwischenspeicher (Redis-Rolle). Die Anbindung an Redis ist nicht gebaut; der Vertrag (Schlüssel, Werte mit Version) steht. */
internal interface VolatileRouteCache {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

internal class InMemoryVolatileRouteCache : VolatileRouteCache {
    private val data = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(key: String): String? { require(RoutingCacheKey.isValid(key)) { "Schlüssel ohne Mandantenanteil" }; return data[key] }
    override fun put(key: String, value: String) { require(RoutingCacheKey.isValid(key)) { "Schlüssel ohne Mandantenanteil" }; data[key] = value }
    fun size() = data.size
}
