package de.taktovia.platform.cache

import de.taktovia.platform.api.Quota
import de.taktovia.platform.api.RateLimiter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Rate-Limit-Speicher gegen einen echten `redis-server` (lokaler Prozess auf freiem Port, ohne Persistenz). Ist die
 * Binärdatei nicht installiert, wird der Test übersprungen und ist dann kein Nachweis.
 */
class RedisRateLimitStoreTest {
    private fun redisBinary() = listOf("/usr/bin/redis-server", "/usr/local/bin/redis-server").firstOrNull { Files.isExecutable(Path.of(it)) }

    @Test
    fun zaehler_sind_atomar_und_je_schluessel_getrennt() {
        val bin = redisBinary(); assumeTrue(bin != null, "redis-server nicht installiert")
        val port = ServerSocket(0).use { it.localPort }
        val dir = Files.createTempDirectory("redis-test")
        val proc = ProcessBuilder(bin, "--port", port.toString(), "--bind", "127.0.0.1", "--save", "", "--appendonly", "no", "--dir", dir.toString()).redirectErrorStream(true).start()
        try {
            var store: RedisRateLimitStore? = null
            repeat(50) { if (store == null) { Thread.sleep(100); store = runCatching { RedisRateLimitStore.connect("redis://127.0.0.1:$port") }.getOrNull() } }
            val s = store ?: error("redis-server nicht erreichbar")
            s.use {
                val limiter = RateLimiter(it, Quota(5, 60))
                val pool = Executors.newFixedThreadPool(8)
                try {
                    val results = (1..40).map { pool.submit(Callable { limiter.check("t1", "a1").allowed }) }.map { f -> f.get() }
                    assertEquals(5, results.count { r -> r }, "genau die Quote wird gewährt, auch parallel")
                } finally { pool.shutdownNow() }
                assertEquals(true, limiter.check("t1", "a2").allowed)
                assertEquals(true, limiter.check("t2", "a1").allowed)
                val denied = limiter.check("t1", "a1")
                assertEquals(false, denied.allowed); assertEquals(true, denied.retryAfterSeconds in 1..60)
            }
        } finally { proc.destroy(); proc.waitFor() }
    }
}
