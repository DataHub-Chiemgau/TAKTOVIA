package de.taktovia.platform.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Sperrlisten-Cache gegen einen echten `redis-server` (lokaler Prozess, ohne Persistenz). Ohne installierte Binärdatei übersprungen (dann kein Nachweis). */
class RedisSessionBlockCacheTest {
    private fun redisBinary() = listOf("/usr/bin/redis-server", "/usr/local/bin/redis-server").firstOrNull { Files.isExecutable(Path.of(it)) }

    @Test
    fun sperreintraege_ablauf_entfernen_und_ausfall() {
        val bin = redisBinary(); assumeTrue(bin != null, "redis-server nicht installiert")
        val port = ServerSocket(0).use { it.localPort }
        val proc = ProcessBuilder(bin, "--port", port.toString(), "--bind", "127.0.0.1", "--save", "", "--appendonly", "no", "--dir", Files.createTempDirectory("redis-sb").toString())
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            var cache: RedisSessionBlockCache? = null
            repeat(50) { if (cache == null) { Thread.sleep(100); cache = runCatching { RedisSessionBlockCache.connect("redis://127.0.0.1:$port") }.getOrNull() } }
            val c = cache ?: error("redis-server nicht erreichbar")
            c.use {
                assertFalse(it.isBlocked("k1"), "kein Eintrag heißt: Datenbank fragen")
                it.block("k1", Duration.ofSeconds(30)); assertTrue(it.isBlocked("k1")); assertFalse(it.isBlocked("k2"))
                it.clear("k1"); assertFalse(it.isBlocked("k1"), "Rollback entfernt den Eintrag")
                it.block("k3", Duration.ofMillis(150)); assertTrue(it.isBlocked("k3")); Thread.sleep(300); assertFalse(it.isBlocked("k3"), "Einträge laufen ab")
                proc.destroy(); proc.waitFor()
                assertThrows(Exception::class.java) { it.isBlocked("k1") }   // Ausfall wird gemeldet, der Aufrufer fällt auf die Datenbank zurück
                assertEquals(false, SessionBlockCache.None.isBlocked("k1"))
            }
        } finally { proc.destroy(); proc.waitFor() }
    }
}
