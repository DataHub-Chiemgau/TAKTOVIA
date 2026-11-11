package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.internal.InMemoryVolatileRouteCache
import de.taktovia.bookingdispatch.internal.RoutingCacheKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP-M2-10 / LH-6.5-OPT-007 und Kap. 01 §1.7: Redis-Schlüssel tragen Mandant, Profil und Kartenversion; ohne Mandantenanteil sind sie nicht zulässig. */
class RoutingCacheKeyTest {
    private val a = UUID.fromString("00000000-0000-4000-8000-00000000000a")
    private val b = UUID.fromString("00000000-0000-4000-8000-00000000000b")

    @Test
    fun redis_schluessel_enthaelt_mandant_profil_und_kartenversion() {
        val key = RoutingCacheKey.redisKey(a, "van_high", "referenz-2026-11-01", "H1", "H4")
        assertEquals("taktovia:route:v1:$a:van_high:referenz-2026-11-01:H1:H4", key)
        assertTrue(RoutingCacheKey.isValid(key))
        assertEquals(a, RoutingCacheKey.tenantOf(key)); assertEquals("referenz-2026-11-01", RoutingCacheKey.mapVersionOf(key))
        // jede Komponente verändert den Schlüssel: anderer Mandant, anderes Profil, andere Version, andere Relation
        val others = listOf(
            RoutingCacheKey.redisKey(b, "van_high", "referenz-2026-11-01", "H1", "H4"), RoutingCacheKey.redisKey(a, "car", "referenz-2026-11-01", "H1", "H4"),
            RoutingCacheKey.redisKey(a, "van_high", "referenz-2026-12-01", "H1", "H4"), RoutingCacheKey.redisKey(a, "van_high", "referenz-2026-11-01", "H4", "H1"),
        )
        others.forEach { assertNotEquals(key, it) }
        assertEquals(others.size, others.toSet().size)
    }

    @Test
    fun schluessel_ohne_mandantenanteil_oder_mit_einschleusung_ist_nicht_zulaessig() {
        assertFalse(RoutingCacheKey.isValid("taktovia:route:v1:car:referenz-2026-11-01:H1:H4"), "ohne Mandant")
        assertFalse(RoutingCacheKey.isValid("route:$a:car:v1:H1:H4"), "falsches Präfix")
        assertFalse(RoutingCacheKey.isValid("taktovia:route:v1:::car:v1:H1:H4"))
        assertNull(RoutingCacheKey.tenantOf("taktovia:route:v1:nicht-uuid:car:v1:H1:H4"))
        for (bad in listOf("", "car:evil", "a b", "../x", "x".repeat(65))) {
            assertTrue(runCatching { RoutingCacheKey.redisKey(a, bad, "v1", "H1", "H4") }.isFailure, "Profil '$bad' darf nicht zulässig sein")
            assertTrue(runCatching { RoutingCacheKey.redisKey(a, "car", bad, "H1", "H4") }.isFailure, "Version '$bad' darf nicht zulässig sein")
        }
        // ein ':' im Anteil kann nie in den Mandantenanteil eines anderen Schlüssels hineinreichen
        assertTrue(runCatching { RoutingCacheKey.redisKey(a, "car", "v1", "H1:$b", "H4") }.isFailure)
        val cache = InMemoryVolatileRouteCache()
        assertTrue(runCatching { cache.put("taktovia:route:v1:car:v1:H1:H4", "x") }.isFailure, "der flüchtige Speicher verweigert Schlüssel ohne Mandant")
        assertTrue(runCatching { cache.get("beliebig") }.isFailure)
    }

    @Test
    fun zwei_mandanten_teilen_nie_einen_eintrag_im_fluechtigen_speicher() {
        val cache = InMemoryVolatileRouteCache()
        val ka = RoutingCacheKey.redisKey(a, "car", "v1", "H1", "H4"); val kb = RoutingCacheKey.redisKey(b, "car", "v1", "H1", "H4")
        cache.put(ka, "v1|180|1500")
        assertEquals("v1|180|1500", cache.get(ka)); assertNull(cache.get(kb))
        assertEquals(1, cache.size())
    }
}
