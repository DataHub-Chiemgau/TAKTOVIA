package de.taktovia.platform.api

import java.util.concurrent.ConcurrentHashMap

/** Quote: höchstens [limit] Anfragen je [windowSeconds]. Planungsdefault (OP-20), konfigurierbar. */
data class Quota(val limit: Int, val windowSeconds: Int) {
    init { require(limit > 0 && windowSeconds > 0) }
}

/** Ergebnis einer Prüfung; [retryAfterSeconds] ist bei Ablehnung die Zeit bis zum nächsten Fenster (mindestens 1). */
data class RateDecision(val allowed: Boolean, val remaining: Int, val retryAfterSeconds: Int)

/** Speicher der Fenster-Zähler. Redis ist der vorgesehene gemeinsame Speicher (Leitlinie 4), kein führender Zustand. */
interface RateLimitStore {
    /** Zählt einen Treffer im aktuellen Fenster von [key] und liefert (Zähler, Sekunden bis Fensterende). */
    fun hit(key: String, windowSeconds: Int): Pair<Long, Int>
}

/** Prozesslokaler Speicher (Einzelinstanz, Tests, Entwicklung). Fester Fensterzähler, atomar je Schlüssel. */
class InMemoryRateLimitStore(private val nowMillis: () -> Long = System::currentTimeMillis) : RateLimitStore {
    private class Window(val start: Long, var count: Long)
    private val windows = ConcurrentHashMap<String, Window>()

    override fun hit(key: String, windowSeconds: Int): Pair<Long, Int> {
        val now = nowMillis()
        val len = windowSeconds * 1000L
        val start = now - Math.floorMod(now, len)
        val w = windows.compute(key) { _, old -> if (old == null || old.start != start) Window(start, 1) else old.also { it.count++ } }!!
        if (windows.size > 100_000) windows.entries.removeIf { it.value.start < start }
        return w.count to Math.max(1, ((start + len - now + 999) / 1000).toInt())
    }
}

/**
 * Quote je Mandant und Akteur (Kap. 05 §5.1): Der Schlüssel enthält beide, die Quote eines Mandanten oder Akteurs
 * beeinflusst keinen anderen. Es gibt keine Metriklabels aus Personen- oder Ortsdaten.
 */
class RateLimiter(private val store: RateLimitStore, private val quota: Quota) {
    fun check(tenant: String, actor: String): RateDecision {
        val (count, resetIn) = store.hit("rl:v1:${tenant.length}:$tenant:$actor", quota.windowSeconds)
        return if (count <= quota.limit) RateDecision(true, (quota.limit - count).toInt(), 0) else RateDecision(false, 0, resetIn)
    }
}
