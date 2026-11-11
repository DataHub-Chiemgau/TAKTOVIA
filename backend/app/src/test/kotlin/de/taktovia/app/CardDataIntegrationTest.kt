package de.taktovia.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import de.taktovia.app.logging.CardDataMaskingMessageConverter
import de.taktovia.app.probe.ProbeDb
import de.taktovia.app.probe.ProbeServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M1-37 / SEC-008 an der laufenden Anwendung: Anfragen mit Testkartennummer in Body, Query und Header werden mit 422 abgewiesen, die Nummer
 * steht weder in der Antwort noch in Logs. (Audit des abgewiesenen Requests und die 100-fach parallele Last gegen die Datenbank:
 * platform/CardDataFilterTest; hier wirkt die Kette mit 100 parallelen HTTP-Requests.)
 */
class CardDataIntegrationTest {
    private val s get() = ProbeServer.get("taktovia.api.rate-limit.requests=100000")
    private val h get() = mapOf("X-Test-Tenant" to ProbeDb.tenantA.toString(), "X-Test-Actor" to "carddata-test")
    private val pan = "4111111111111111"

    private fun leaks(t: String) = t.contains(pan) || t.contains("4111 1111 1111 1111") || t.contains("4111-1111-1111-1111")

    @Test
    fun kartennummer_in_body_query_und_header_wird_mit_422_abgewiesen_und_nicht_geloggt() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val old = root.level
        s  // Anwendung vor dem Mitschnitt starten
        root.addAppender(appender); root.level = Level.DEBUG
        val responses = mutableListOf<String>()
        try {
            val body = s.post("/v1/probe/items", """{"name":"Karte 4111 1111 1111 1111"}""", h)
            assertEquals(422, body.status); assertEquals("/name", body.json["violations"][0]["pointer"].asString())
            val query = s.get("/v1/probe/items?limit=$pan", h)
            assertEquals(422, query.status); assertEquals("/\$query/limit", query.json["violations"][0]["pointer"].asString())
            val header = s.get("/v1/probe/items", h + ("X-Referenz" to "4111-1111-1111-1111"))
            assertEquals(422, header.status); assertEquals("/\$header/X-Referenz", header.json["violations"][0]["pointer"].asString())
            responses += listOf(body.text, query.text, header.text)
            // gültige Anfragen laufen unverändert (positiv)
            assertEquals(201, s.post("/v1/probe/items", """{"name":"psp_test_token_not_a_real_credential"}""", h).status)
            assertEquals(200, s.get("/v1/probe/items?limit=5", h).status)
        } finally { root.detachAppender(appender); root.level = old }
        assertTrue(responses.none { leaks(it) }, "Antwort wiederholt die Nummer")
        val lines = appender.list.map { e -> e.formattedMessage + " " + (e.throwableProxy?.let { ThrowableProxyUtil.asString(it) } ?: "") }
        assertTrue(lines.isNotEmpty())
        assertFalse(lines.any { leaks(it) }, "Kartennummer in Logs: " + lines.firstOrNull { leaks(it) }?.take(100))
    }

    @Test
    fun hundert_parallele_requests_mit_testkartennummer_werden_alle_abgewiesen() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val old = root.level
        s
        root.addAppender(appender); root.level = Level.DEBUG
        val start = CountDownLatch(1); val exec = Executors.newFixedThreadPool(16)
        val statuses = mutableListOf<Int>(); val texts = java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            val jobs = (0 until 100).map { i ->
                exec.submit(Callable {
                    start.await()
                    val r = when (i % 3) {
                        0 -> s.post("/v1/probe/items", """{"name":"zahle mit $pan bitte"}""", h)
                        1 -> s.get("/v1/probe/items?limit=$pan", h)
                        else -> s.get("/v1/probe/items", h + ("X-Referenz" to pan))
                    }
                    texts += r.text; synchronized(statuses) { statuses += r.status }
                })
            }
            start.countDown(); jobs.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally { exec.shutdownNow(); root.detachAppender(appender); root.level = old }
        assertEquals(100, statuses.size); assertTrue(statuses.all { it == 422 }, statuses.groupBy { it }.mapValues { it.value.size }.toString())
        assertFalse(texts.any { leaks(it) })
        // Abweisungen laufen asynchron in Log und Audit; kurz warten, bis die Zeilen da sind, dann scannen
        val deadline = System.nanoTime() + 15_000_000_000L
        while (appender.list.count { it.formattedMessage.contains("card_data_rejected") } < 100 && System.nanoTime() < deadline) Thread.sleep(50)
        assertEquals(100, appender.list.count { it.formattedMessage.contains("card_data_rejected") })
        assertFalse(appender.list.any { leaks(it.formattedMessage + " " + (it.throwableProxy?.let { t -> ThrowableProxyUtil.asString(t) } ?: "")) })
    }

    @Test
    fun logzeilenkonverter_maskiert_auch_unabsichtlich_protokollierte_nummern() {
        val e = ch.qos.logback.classic.spi.LoggingEvent("x", LoggerFactory.getLogger("t") as Logger, Level.INFO, "Zahlung mit {} durch", null, arrayOf("4111 1111 1111 1111"))
        val out = CardDataMaskingMessageConverter().convert(e)
        assertFalse(leaks(out), out); assertTrue(out.contains("[card-data-removed]"))
    }
}
