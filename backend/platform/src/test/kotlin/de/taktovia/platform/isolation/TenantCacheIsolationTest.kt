package de.taktovia.platform.isolation

import de.taktovia.platform.cache.CacheKey
import de.taktovia.platform.cache.InMemoryTenantCache
import de.taktovia.platform.cache.RedisTenantCache
import de.taktovia.platform.cache.TenantCache
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** WP-M1-11: Cache-Schlüssel mit Mandant, Dienst, Ressourcen-ID und Policyrevision; Cache-Poisoning mit identischer Ressourcen-ID wirkt nie mandantenübergreifend. */
class TenantCacheIsolationTest {
    private val a = TenantId(UUID.randomUUID()); private val b = TenantId(UUID.randomUUID())
    private val service = UUID.randomUUID()

    private class Tick(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    private fun behaviour(cache: TenantCache, count: (TenantId) -> Int) {
        val ka = CacheKey(a, service, "vehicle-42", 7); val kb = CacheKey(b, service, "vehicle-42", 7)   // identische Ressourcen-ID, Dienst und Revision
        cache.put(ka, "wert-a", Duration.ofSeconds(30))
        assertEquals("wert-a", cache.get(ka))
        assertNull(cache.get(kb), "Cache-Poisoning: dieselbe Ressourcen-ID in B trifft einen anderen Schlüssel")
        assertEquals(0, count(b), "kein Cacheeintrag in B durch Schreiben in A")
        cache.put(kb, "wert-b", Duration.ofSeconds(30))
        assertEquals("wert-a", cache.get(ka)); assertEquals("wert-b", cache.get(kb))
        // andere Policyrevision und anderer Dienst sind andere Schlüssel
        assertNull(cache.get(CacheKey(a, service, "vehicle-42", 8)), "neue Policyrevision liefert nie den Wert der alten")
        assertNull(cache.get(CacheKey(a, null, "vehicle-42", 7)))
        assertNull(cache.get(CacheKey(a, UUID.randomUUID(), "vehicle-42", 7)))
        cache.evictTenant(a)
        assertNull(cache.get(ka)); assertEquals("wert-b", cache.get(kb), "Verwerfen eines Mandanten lässt den anderen unberührt")
        cache.evict(kb); assertNull(cache.get(kb))
    }

    @Test
    fun cache_poisoning_identische_ressourcen_id() {
        val mem = InMemoryTenantCache()
        behaviour(mem) { mem.count(it) }
    }

    @Test
    fun cache_key_ohne_tenant_wird_abgewiesen() {
        // Es gibt keinen Schlüsseltyp ohne Mandant; ungültige Bestandteile werden abgewiesen (Typ erzwingt tenant, Ressourcen-ID ohne Trennzeichen/Pfadtricks).
        assertThrows(IllegalArgumentException::class.java) { CacheKey(a, service, "", 1) }
        assertThrows(IllegalArgumentException::class.java) { CacheKey(a, service, "x:t:${b}:r:y", 1) }   // Trennzeichen kann keinen anderen Mandanten vortäuschen
        assertThrows(IllegalArgumentException::class.java) { CacheKey(a, service, "id", -1) }
        assertTrue(CacheKey(a, service, "r", 1).render().startsWith("t:${a.value}:"))
        assertNotEquals(CacheKey(a, service, "r", 1).render(), CacheKey(b, service, "r", 1).render())
    }

    @Test
    fun eintraege_laufen_ab() {
        val tick = Tick(); val c = InMemoryTenantCache(tick)
        val k = CacheKey(a, null, "r", 1)
        c.put(k, "x", Duration.ofSeconds(10)); assertEquals("x", c.get(k))
        tick.now = tick.now.plusSeconds(11); assertNull(c.get(k))
        assertFalse(c.count(a) > 0)
    }

    @Test
    fun redis_umsetzung_trennt_mandanten_gegen_echten_redis_server() {
        val bin = listOf("/usr/bin/redis-server", "/usr/local/bin/redis-server").firstOrNull { Files.isExecutable(Path.of(it)) }
        assumeTrue(bin != null, "redis-server nicht installiert (dann kein Nachweis)")
        val port = ServerSocket(0).use { it.localPort }
        val proc = ProcessBuilder(bin, "--port", port.toString(), "--bind", "127.0.0.1", "--save", "", "--appendonly", "no", "--dir", Files.createTempDirectory("redis-tc").toString())
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            var cache: RedisTenantCache? = null
            repeat(50) { if (cache == null) { Thread.sleep(100); cache = runCatching { RedisTenantCache.connect("redis://127.0.0.1:$port", Duration.ofSeconds(2)) }.getOrNull() } }
            val c = cache ?: error("redis-server nicht erreichbar")
            c.use { behaviour(it) { t -> it.count(t) } }
        } finally { proc.destroy(); proc.waitFor() }
    }
}
