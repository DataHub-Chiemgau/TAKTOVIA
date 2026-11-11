package de.taktovia.platform.cache

import de.taktovia.platform.tenant.TenantId
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Cache-Schlüssel mit Pflichtbestandteilen (WP-M1-11, Kap. 02 §4.3, LH-6.1-SYS-001): **Mandant, Dienst, Ressourcen-ID und Policyrevision**. Einen Schlüssel ohne Mandant
 * gibt es nicht (der Typ verlangt ihn); die Policyrevision steht im Schlüssel, damit nach einer Regeländerung nie ein Wert unter der alten Revision geliefert wird.
 * Ein Cache-Poisoning-Versuch mit identischer Ressourcen-ID in einem anderen Mandanten trifft einen anderen Schlüssel. [service] `null` = mandantenweit (wird als `-` geschrieben).
 * Alle Zugriffe auf Cache und Redis laufen über [TenantCache]; rohe Zeichenkettenschlüssel gibt es dort nicht (Architekturregel `IsolationRules`).
 */
data class CacheKey(val tenant: TenantId, val service: UUID?, val resourceId: String, val policyRevision: Long) {
    init {
        require(RESOURCE.matches(resourceId)) { "ungültige Ressourcen-ID im Cache-Schlüssel" }
        require(policyRevision >= 0) { "Policyrevision muss >= 0 sein" }
    }

    /** Textform `t:<mandant>:s:<dienst|->:r:<ressource>:p:<revision>`; das Präfix `t:<mandant>:` erlaubt das Löschen je Mandant. */
    fun render(): String = "${prefixOf(tenant)}s:${service ?: "-"}:r:$resourceId:p:$policyRevision"

    companion object {
        private val RESOURCE = Regex("[A-Za-z0-9._-]{1,120}")
        fun prefixOf(tenant: TenantId) = "t:${tenant.value}:"
    }
}

/**
 * Zwischenspeicher mit Mandant im Schlüssel (WP-M1-11). Nie führender Zustand (Leitlinie 4): jeder Eintrag ist aus der Datenbank wiederherstellbar; Ausfall heißt „nicht vorhanden“.
 * [evictTenant] verwirft alle Einträge eines Mandanten (z. B. bei Sperre oder Policywechsel).
 */
interface TenantCache {
    fun get(key: CacheKey): String?
    fun put(key: CacheKey, value: String, ttl: Duration)
    fun evict(key: CacheKey)
    fun evictTenant(tenant: TenantId)
}

/** Prozesslokale Umsetzung (Tests, Einzelinstanz): thread-sicher, mit Ablauf und Größenbegrenzung. */
class InMemoryTenantCache(private val clock: Clock = Clock.systemUTC(), private val maxEntries: Int = 10_000) : TenantCache {
    private class Entry(val value: String, val until: Instant)
    private val map = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > maxEntries
    }

    override fun get(key: CacheKey): String? = synchronized(map) {
        val k = key.render(); val e = map[k] ?: return null
        if (!e.until.isAfter(clock.instant())) { map.remove(k); null } else e.value
    }

    override fun put(key: CacheKey, value: String, ttl: Duration) { synchronized(map) { map[key.render()] = Entry(value, clock.instant().plus(ttl)) } }
    override fun evict(key: CacheKey) { synchronized(map) { map.remove(key.render()) } }
    override fun evictTenant(tenant: TenantId) { synchronized(map) { val p = CacheKey.prefixOf(tenant); map.keys.removeIf { it.startsWith(p) } } }

    /** Anzahl der Einträge eines Mandanten (Nachweis „kein Cacheeintrag in B“). */
    fun count(tenant: TenantId): Int = synchronized(map) { val p = CacheKey.prefixOf(tenant); map.keys.count { it.startsWith(p) } }
}

/** Redis-Umsetzung; kurzer Befehls-Timeout, Ausfall wirft (der Aufrufer fällt auf die Datenbank zurück). Redis ist nur Cache. */
class RedisTenantCache private constructor(private val client: RedisClient, private val prefix: String) : TenantCache, AutoCloseable {
    private val connection: StatefulRedisConnection<String, String> = client.connect()

    override fun get(key: CacheKey): String? = connection.sync().get(prefix + key.render())
    override fun put(key: CacheKey, value: String, ttl: Duration) { connection.sync().set(prefix + key.render(), value, SetArgs.Builder.px(ttl.toMillis().coerceAtLeast(1))) }
    override fun evict(key: CacheKey) { connection.sync().del(prefix + key.render()) }

    override fun evictTenant(tenant: TenantId) {
        val sync = connection.sync(); var cursor: ScanCursor = ScanCursor.INITIAL
        do {
            val page = sync.scan(cursor, ScanArgs.Builder.matches(prefix + CacheKey.prefixOf(tenant) + "*").limit(500))
            if (page.keys.isNotEmpty()) sync.del(*page.keys.toTypedArray())
            cursor = page
        } while (!cursor.isFinished)
    }

    /** Anzahl der Einträge eines Mandanten (Nachweis in Tests). */
    fun count(tenant: TenantId): Int {
        val sync = connection.sync(); var cursor: ScanCursor = ScanCursor.INITIAL; var n = 0
        do { val page = sync.scan(cursor, ScanArgs.Builder.matches(prefix + CacheKey.prefixOf(tenant) + "*").limit(500)); n += page.keys.size; cursor = page } while (!cursor.isFinished)
        return n
    }

    override fun close() { connection.close(); client.shutdown() }

    companion object {
        fun connect(url: String, commandTimeout: Duration = Duration.ofMillis(250), prefix: String = "taktovia:tc:"): RedisTenantCache {
            val client = RedisClient.create(RedisURI.create(url).also { it.timeout = commandTimeout })
            client.options = ClientOptions.builder().disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).autoReconnect(true).build()
            return RedisTenantCache(client, prefix)
        }
    }
}
