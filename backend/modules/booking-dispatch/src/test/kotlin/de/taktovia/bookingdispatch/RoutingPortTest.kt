package de.taktovia.bookingdispatch

import com.sun.net.httpserver.HttpServer
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.RoutingException
import de.taktovia.bookingdispatch.api.RoutingFailure
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * WP-M2-10: `RoutingPort` mit mandantengebundenem Cache gegen einen STUB des Routing-Workers (die Antwortform der Route stammt aus `workers/routing/contract/route-response.json`,
 * die der Python-Test gegen den echten Worker prüft). Der Worker selbst läuft in den Python-Tests; hier wird kein echter Worker gestartet.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoutingPortTest {
    private class StubWorker(val routeSample: String) {
        @Volatile var version = "referenz-2026-11-01"
        @Volatile var matrixVersion: String? = null     // abweichende Version in der Matrixantwort (Stand-Wechsel mitten im Aufruf)
        @Volatile var delayMillis = 0L
        @Volatile var failure: Pair<Int, String>? = null
        val matrixCalls = AtomicInteger()
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/v1/map-version") { ex -> reply(ex, 200, """{"map_version":"$version"}""") }
            server.createContext("/v1/route") { ex -> respond(ex) { routeSample } }
            server.createContext("/v1/matrix") { ex ->
                respond(ex) {
                    matrixCalls.incrementAndGet()
                    val body = String(ex.requestBody.readAllBytes()); val n = Regex(""""from":\[([^\]]*)]""").find(body)!!.groupValues[1].split(',').size
                    val m = Regex(""""to":\[([^\]]*)]""").find(body)!!.groupValues[1].split(',').size
                    val cells = (0 until n).joinToString(",", "[", "]") { i -> (0 until m).joinToString(",", "[", "]") { j -> if (j == 99) "null" else """{"time_s":${100 + i * 10 + j},"distance_m":${1000 + j}}""" } }
                    """{"origins":[],"destinations":[],"cells":$cells,"profile":"car","map_version":"${matrixVersion ?: version}","provider":"stub"}"""
                }
            }
            server.executor = java.util.concurrent.Executors.newCachedThreadPool()
            server.start()
        }

        private fun respond(ex: com.sun.net.httpserver.HttpExchange, body: () -> String) {
            if (delayMillis > 0) Thread.sleep(delayMillis)
            val f = failure
            if (f != null) reply(ex, f.first, """{"error":{"code":"${f.second}","message":"x"}}""") else reply(ex, 200, body())
        }

        private fun reply(ex: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
            val raw = body.toByteArray(); ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(status, raw.size.toLong()); ex.responseBody.use { it.write(raw) }
        }

        fun stop() = server.stop(0)
    }

    private lateinit var fx: BookingFixture
    private lateinit var worker: StubWorker
    private lateinit var api: BookingdispatchApi

    @BeforeAll fun up() {
        fx = BookingFixture()
        worker = StubWorker(Files.readString(BookingTestDb.repoRoot().resolve("workers/routing/contract/route-response.json")))
        api = BookingdispatchApi.create(fx.ctxTx, routingWorkerUrl = worker.url, routingBudgetMillis = 400)
    }
    @AfterAll fun down() { worker.stop(); fx.close() }

    /** Je Lauf eigene Haltepunktkennungen: die Datenbank behält Cache-Einträge früherer Läufe. */
    private val run = java.util.UUID.randomUUID().toString().take(8)
    private fun k(n: String) = "$n-$run"

    private fun cacheRows(tenant: de.taktovia.platform.tenant.TenantId, version: String? = null): Int = fx.tx.run(tenant, readOnly = true) { j ->
        j.queryForObject("SELECT count(*) FROM taktovia.route_matrix_cache WHERE (CAST(? AS text) IS NULL OR map_version = ?)", Int::class.java, version, version)!!
    }

    @Test
    fun referenzroute_mit_profil_und_kartenversion_und_haltepunktbindung() {
        val r = api.routing!!.route(fx.context(fx.tenantA), "H1", "H4", "car")
        assertEquals(180, r.timeSeconds); assertEquals(1500, r.distanceMeters)
        assertEquals("car", r.profile); assertEquals("referenz-2026-11-01", r.mapVersion)
        assertEquals("H1", r.origin); assertEquals("H4", r.destination)
        assertEquals(51.05 to 13.74, r.geometry.first())
    }

    @Test
    fun matrix_wird_mandantengebunden_gecacht_und_neuer_kartenstand_loescht_alte_eintraege() {
        val ctxA = fx.context(fx.tenantA); val ctxB = fx.context(fx.tenantB)
        val before = worker.matrixCalls.get()
        val first = api.routing!!.matrix(ctxA, listOf(k("K1"), k("K2")), listOf(k("K3"), k("K4")), "car")
        assertEquals("stub", first.provider)
        val second = api.routing!!.matrix(ctxA, listOf(k("K1"), k("K2")), listOf(k("K3"), k("K4")), "car")
        assertEquals("cache", second.provider, "zweiter Aufruf aus dem Cache"); assertEquals(first.cells, second.cells)
        assertEquals(1, worker.matrixCalls.get() - before, "nur ein Workeraufruf für Mandant A")
        // Mandant B: identische Relation, identisches Profil und identische Version, aber eigener Cache-Eintrag und eigener Workeraufruf
        val forB = api.routing!!.matrix(ctxB, listOf(k("K1"), k("K2")), listOf(k("K3"), k("K4")), "car")
        assertEquals("stub", forB.provider); assertEquals(2, worker.matrixCalls.get() - before)
        assertTrue(cacheRows(fx.tenantA, "referenz-2026-11-01") >= 4); assertTrue(cacheRows(fx.tenantB, "referenz-2026-11-01") >= 4)
        // neuer Kartenstand: alte Version wird nie geliefert, Einträge des Mandanten A werden gelöscht, B bleibt bis zu seiner eigenen Abfrage unberührt
        worker.version = "referenz-2026-12-01"
        val third = api.routing!!.matrix(ctxA, listOf(k("K1"), k("K2")), listOf(k("K3"), k("K4")), "car")
        assertEquals("stub", third.provider); assertEquals("referenz-2026-12-01", third.mapVersion)
        assertEquals(0, cacheRows(fx.tenantA, "referenz-2026-11-01"), "alte Version des Mandanten A gelöscht")
        assertTrue(cacheRows(fx.tenantA, "referenz-2026-12-01") >= 4)
        assertTrue(cacheRows(fx.tenantB, "referenz-2026-11-01") >= 4, "Mandant B bleibt unberührt")
        worker.version = "referenz-2026-11-01"
    }

    @Test
    fun matrix_zeigt_unerreichbar_als_null_ohne_schaetzwert_und_cached_es_nicht() {
        val ctx = fx.context(fx.tenantA)
        val before = cacheRows(fx.tenantA)
        val targets = (0..99).map { k("U$it") }
        val m = api.routing!!.matrix(ctx, listOf(k("UQ")), targets, "car")
        assertNull(m.cells[0][99]); assertTrue(m.cells[0][0] != null)
        assertEquals(before + 99, cacheRows(fx.tenantA), "unerreichbar wird nicht gecacht")
    }

    @Test
    fun timeout_und_ausfall_liefern_definierten_fehler_und_nichts_im_cache() {
        val ctx = fx.context(fx.tenantA); val before = cacheRows(fx.tenantA)
        worker.delayMillis = 900
        val t = runCatching { api.routing!!.route(ctx, "H1", "H4", "car") }.exceptionOrNull() as RoutingException
        assertEquals(RoutingFailure.PROVIDER_TIMEOUT, t.failure)
        worker.delayMillis = 0
        worker.failure = 422 to "NO_ROUTE"
        assertEquals(RoutingFailure.NO_ROUTE, (runCatching { api.routing!!.route(ctx, "H1", "H7", "car") }.exceptionOrNull() as RoutingException).failure)
        worker.failure = 422 to "NOT_A_STOP"
        assertEquals(RoutingFailure.NOT_A_STOP, (runCatching { api.routing!!.route(ctx, "X", "H4", "car") }.exceptionOrNull() as RoutingException).failure)
        worker.failure = 503 to "PROVIDER_UNAVAILABLE"
        assertEquals(RoutingFailure.PROVIDER_UNAVAILABLE, (runCatching { api.routing!!.matrix(ctx, listOf("A"), listOf("B"), "car") }.exceptionOrNull() as RoutingException).failure)
        worker.failure = null
        assertEquals(before, cacheRows(fx.tenantA), "Fehler schreiben nichts in den Cache")
        // Worker nicht erreichbar (anderer Port, nichts hört zu)
        val dead = BookingdispatchApi.create(fx.ctxTx, routingWorkerUrl = "http://127.0.0.1:9", routingBudgetMillis = 300)
        assertEquals(RoutingFailure.PROVIDER_UNAVAILABLE, (runCatching { dead.routing!!.route(ctx, "H1", "H4", "car") }.exceptionOrNull() as RoutingException).failure)
    }

    @Test
    fun kartenstandwechsel_waehrend_der_abfrage_liefert_kein_gemischtes_ergebnis() {
        worker.matrixVersion = "referenz-2099-01-01"   // Matrixantwort trägt eine andere Version als die zuvor abgefragte
        try {
            val e = runCatching { api.routing!!.matrix(fx.context(fx.tenantA), listOf(k("M1")), listOf(k("M2")), "car") }.exceptionOrNull() as RoutingException
            assertEquals(RoutingFailure.PROVIDER_UNAVAILABLE, e.failure)
        } finally { worker.matrixVersion = null }
    }
}
