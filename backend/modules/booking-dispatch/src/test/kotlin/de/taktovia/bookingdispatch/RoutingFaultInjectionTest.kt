package de.taktovia.bookingdispatch

import com.sun.net.httpserver.HttpServer
import de.taktovia.bookingdispatch.api.RoutingException
import de.taktovia.bookingdispatch.api.RoutingFailure
import de.taktovia.bookingdispatch.internal.CachingRoutingPort
import de.taktovia.bookingdispatch.internal.HttpRoutingClient
import de.taktovia.bookingdispatch.internal.JdbcRouteMatrixCache
import de.taktovia.bookingdispatch.internal.VolatileRouteCache
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * WP-M5-16, Fault-Injection für Cache (Redis-Rolle) und Kartenquelle (Routing-Worker) am `RoutingPort` mit echter PostgreSQL und einem STUB-Worker (kein echter Kartenanbieter, kein echtes Redis:
 * die Redis-Anbindung ist nicht gebaut, der flüchtige Cache ist die Schnittstelle [VolatileRouteCache], hier mit einem Ausfall nachgestellt).
 *
 * Erwartung Cache: ein Ausfall des flüchtigen Caches (Lesen und Schreiben werfen) ändert das Ergebnis nicht; Wahrheit sind PostgreSQL (`route_matrix_cache`) und der Worker.
 * Erwartung Kartenquelle: Worker nicht erreichbar oder zu langsam ergibt einen definierten Fehler (`RoutingException`, `PROVIDER_UNAVAILABLE`/`PROVIDER_TIMEOUT`), nie ein Ergebnis aus alten Daten;
 * nach Rückkehr arbeitet der Port ohne Eingriff weiter (Selbsterholung).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoutingFaultInjectionTest {
    private class Stub {
        @Volatile var down = false
        @Volatile var delayMillis = 0L
        val matrixCalls = AtomicInteger()
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port get() = server.address.port
        init {
            server.createContext("/v1/map-version") { ex -> answer(ex, """{"map_version":"referenz-2026-11-01"}""") }
            server.createContext("/v1/matrix") { ex ->
                answer(ex) {
                    matrixCalls.incrementAndGet()
                    val body = String(ex.requestBody.readAllBytes()); val n = Regex(""""from":\[([^\]]*)]""").find(body)!!.groupValues[1].split(',').size
                    val m = Regex(""""to":\[([^\]]*)]""").find(body)!!.groupValues[1].split(',').size
                    val cells = (0 until n).joinToString(",", "[", "]") { i -> (0 until m).joinToString(",", "[", "]") { j -> """{"time_s":${100 + i * 10 + j},"distance_m":${1000 + j}}""" } }
                    """{"origins":[],"destinations":[],"cells":$cells,"profile":"car","map_version":"referenz-2026-11-01","provider":"stub"}"""
                }
            }
            server.executor = java.util.concurrent.Executors.newCachedThreadPool(); server.start()
        }
        private fun answer(ex: com.sun.net.httpserver.HttpExchange, body: String) = answer(ex) { body }
        private fun answer(ex: com.sun.net.httpserver.HttpExchange, body: () -> String) {
            if (down) { ex.close(); return }                                    // Verbindung ohne Antwort abgebrochen (Ausfall)
            if (delayMillis > 0) Thread.sleep(delayMillis)
            val raw = body().toByteArray(); ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(200, raw.size.toLong()); ex.responseBody.use { it.write(raw) }
        }
        fun stop() = server.stop(0)
    }

    /** Flüchtiger Cache, der wie ein ausgefallenes Redis jede Operation mit einer Ausnahme beantwortet. */
    private class DeadCache : VolatileRouteCache {
        val calls = AtomicInteger()
        override fun get(key: String): String? { calls.incrementAndGet(); throw java.io.IOException("Redis nicht erreichbar") }
        override fun put(key: String, value: String) { calls.incrementAndGet(); throw java.io.IOException("Redis nicht erreichbar") }
    }

    private lateinit var fx: BookingFixture
    private lateinit var stub: Stub
    private val run = java.util.UUID.randomUUID().toString().take(8)
    private fun k(n: String) = "$n-$run"

    @BeforeAll fun up() { fx = BookingFixture(); stub = Stub() }
    @AfterAll fun down() { stub.stop(); fx.close() }

    private fun port(volatile: VolatileRouteCache, budgetMs: Long = 400) =
        CachingRoutingPort(fx.ctxTx, HttpRoutingClient("http://127.0.0.1:${stub.port}", Duration.ofMillis(budgetMs)), JdbcRouteMatrixCache(), volatile)

    @Test
    fun redis_ausfall_aendert_das_ergebnis_nicht_und_postgresql_bleibt_die_wahrheit() {
        val dead = DeadCache(); val port = port(dead); val ctx = fx.context(fx.tenantA)
        val before = stub.matrixCalls.get()
        val first = port.matrix(ctx, listOf(k("R1"), k("R2")), listOf(k("R3")), "car")
        assertEquals("stub", first.provider); assertEquals(1, stub.matrixCalls.get() - before)
        // zweiter Aufruf trotz totem flüchtigem Cache aus PostgreSQL, ohne neuen Workeraufruf, mit gleichen Werten
        val second = port.matrix(ctx, listOf(k("R1"), k("R2")), listOf(k("R3")), "car")
        assertEquals("cache", second.provider); assertEquals(first.cells, second.cells); assertEquals(1, stub.matrixCalls.get() - before)
        assertTrue(dead.calls.get() >= 4, "der tote Cache wurde versucht und übergangen")
        // Mandantentrennung bleibt auch ohne flüchtigen Cache: Mandant B hat keinen Eintrag und ruft den Worker selbst auf
        val forB = port.matrix(fx.context(fx.tenantB), listOf(k("R1"), k("R2")), listOf(k("R3")), "car")
        assertEquals("stub", forB.provider); assertEquals(2, stub.matrixCalls.get() - before)
    }

    @Test
    fun kartenquelle_ausfall_ist_ein_definierter_fehler_und_nach_rueckkehr_laeuft_der_port_ohne_eingriff() {
        val port = port(de.taktovia.bookingdispatch.internal.InMemoryVolatileRouteCache()); val ctx = fx.context(fx.tenantA)
        assertEquals("stub", port.matrix(ctx, listOf(k("K1")), listOf(k("K2")), "car").provider)
        stub.down = true
        val e = assertThrows(RoutingException::class.java) { port.matrix(ctx, listOf(k("K1")), listOf(k("K2")), "car") }
        assertTrue(e.failure == RoutingFailure.PROVIDER_UNAVAILABLE || e.failure == RoutingFailure.PROVIDER_TIMEOUT, "definierter Fehler: ${e.failure}")
        stub.down = false; stub.delayMillis = 1500   // Latenz über dem Zeitbudget (400 ms)
        val slow = assertThrows(RoutingException::class.java) { port.matrix(ctx, listOf(k("K1")), listOf(k("K2")), "car") }
        assertTrue(slow.failure == RoutingFailure.PROVIDER_TIMEOUT || slow.failure == RoutingFailure.PROVIDER_UNAVAILABLE, "Zeitbudget eingehalten: ${slow.failure}")
        stub.delayMillis = 0
        assertEquals("cache", port.matrix(ctx, listOf(k("K1")), listOf(k("K2")), "car").provider, "nach Rückkehr arbeitet der Port ohne Eingriff weiter (validierter Cache)")
    }
}
