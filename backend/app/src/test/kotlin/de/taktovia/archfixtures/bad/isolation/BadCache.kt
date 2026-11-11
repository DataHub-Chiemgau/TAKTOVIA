package de.taktovia.archfixtures.bad.isolation

import de.taktovia.platform.cache.CacheKey
import de.taktovia.platform.cache.TenantCache
import de.taktovia.platform.tenant.TenantId
import java.time.Duration

/** Verstoß: Cache mit zusätzlichem Zeichenkettenschlüssel (kein Mandant im Schlüssel). */
class BadCache : TenantCache {
    private val map = HashMap<String, String>()
    override fun get(key: CacheKey): String? = map[key.render()]
    override fun put(key: CacheKey, value: String, ttl: Duration) { map[key.render()] = value }
    override fun evict(key: CacheKey) { map.remove(key.render()) }
    override fun evictTenant(tenant: TenantId) = Unit
    fun get(rawKey: String): String? = map[rawKey]
}
