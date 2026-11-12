package de.taktovia.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.OutputStreamAppender
import de.taktovia.app.logging.CardDataMaskingMessageConverter
import de.taktovia.app.logging.CardDataMaskingThrowableConverter
import de.taktovia.app.probe.ProbeServer
import de.taktovia.platform.logging.Maskierung
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * WP-M5-06 (LH-6.19-NFR-015): Fehlerpfade der echten Anwendung hinterlassen im Log (mit dem Layout aus logback-spring.xml) und in der
 * API-Antwort keine Klartext-Kontakt- oder Zahlungsdaten; der Stapel steht nicht in der Antwort.
 */
class DiagnoseMaskingApiTest {
    private val roh = listOf("erika.mustermann@example.test", "12345678", "DE89370400440532013000", "4111 1111", "Musterstraße", "10115")

    @Test
    fun logback_layout_nutzt_die_zentrale_maskierung() {
        val xml = Files.readString(Path.of(System.getProperty("taktovia.repoRoot"), "backend/app/src/main/resources/logback-spring.xml"))
        assertTrue(xml.contains("%safeMsg") && xml.contains("%safeEx"))
        assertTrue(xml.contains("CardDataMaskingMessageConverter") && xml.contains("CardDataMaskingThrowableConverter"))
    }

    @Test
    fun fehlerpfade_log_und_antwort_ohne_klartext_und_ohne_stacktrace() {
        val server = ProbeServer.get()
        val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
        val out = ByteArrayOutputStream()
        val enc = PatternLayoutEncoder().also {
            it.context = ctx; it.pattern = "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %5p [%t] [%X{correlation_id:-}] %logger{39} : %safeMsg%n%safeEx"   // wie logback-spring.xml
        }
        PatternLayout.DEFAULT_CONVERTER_MAP["safeMsg"] = CardDataMaskingMessageConverter::class.java.name
        PatternLayout.DEFAULT_CONVERTER_MAP["safeEx"] = CardDataMaskingThrowableConverter::class.java.name
        enc.start()
        val appender = OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent>().also { it.context = ctx; it.encoder = enc; it.outputStream = out; it.start() }
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val app = LoggerFactory.getLogger("de.taktovia") as Logger
        val oldLevel = app.level
        root.addAppender(appender); app.level = Level.DEBUG
        val r1: de.taktovia.app.probe.Resp; val r2: de.taktovia.app.probe.Resp
        // Echter PSP-Adapter (Produktivcode) gegen einen Stub mit Fehlerantwort, die Karten- und Kontaktdaten enthält (PSP-Fehlerantwort-Pfad), und gegen einen toten Port.
        val stub = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        stub.createContext("/") { ex ->
            val body = """{"code":"INTERNAL","message":"Karte 4111 1111 1111 1111 abgelehnt für erika.mustermann@example.test, IBAN DE89 3704 0044 0532 0130 00, Tel +49 176 12345678"}""".toByteArray()
            ex.sendResponseHeaders(500, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        stub.start()
        try {
            r1 = server.get("/v1/probe/diagnose/validierung")
            r2 = server.get("/v1/probe/diagnose/ausnahme")
            val cmd = de.taktovia.adapters.psp.api.PspAuthorize(de.taktovia.adapters.psp.api.PspCommandRef.derive("booking-" + java.util.UUID.randomUUID(), de.taktovia.adapters.psp.api.PspOperation.AUTHORIZE),
                "merchant-" + java.util.UUID.randomUUID(), 1500, "EUR", "pm-" + java.util.UUID.randomUUID())
            val unbekannt = de.taktovia.adapters.psp.api.PspAdapter.http("sim", java.net.URI("http://127.0.0.1:${stub.address.port}"), java.time.Duration.ofSeconds(5)).authorize(cmd)
            assertTrue(unbekannt is de.taktovia.adapters.psp.api.PspOutcome.Unknown, "PSP-Fehlerantwort bleibt unbestimmt: $unbekannt")
            val nichtErreichbar = de.taktovia.adapters.psp.api.PspAdapter.http("sim", java.net.URI("http://127.0.0.1:1"), java.time.Duration.ofSeconds(2)).authorize(cmd)
            assertTrue(nichtErreichbar is de.taktovia.adapters.psp.api.PspOutcome.Unavailable, "$nichtErreichbar")
        } finally { stub.stop(0); root.detachAppender(appender); app.level = oldLevel }
        assertEquals(422, r1.status); assertEquals(500, r2.status)
        for (r in listOf(r1, r2)) {
            for (w in roh) assertFalse(r.text.contains(w), "Antwort enthält '$w': ${r.text}")
            assertFalse(r.text.contains("\tat ") || r.text.contains("Exception") || r.text.contains("de.taktovia"), "Stapel/Klassen in Antwort: ${r.text}")
            assertEquals("application/problem+json", r.header("Content-Type")!!.substringBefore(';'))
        }
        val log = out.toString(Charsets.UTF_8)
        assertTrue(log.contains("taktovia.adapters.psp") && log.contains("PSP-Ergebnis unbestimmt"), "PSP-Adapter-Zeilen fehlen: $log")
        // Die Logzeilen des Testlaufs liegen als Stichprobe für tools/log_stichprobe.py bereit (tests/acceptance/diagnose).
        val dir = Path.of(System.getProperty("taktovia.repoRoot"), "backend/app/build/diagnose")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("fachkern.log"), log)
        assertTrue(log.contains("Validierung abgelehnt") && log.contains("IllegalStateException"), "der Test belegt nichts ohne Logzeilen: $log")
        for (w in roh) assertFalse(log.contains(w), "Log enthält '$w'")
        assertEquals(emptySet<Maskierung.Klasse>(), Maskierung.klartextKlassen(log))
    }
}
