package de.taktovia.app

import de.taktovia.app.observability.MaskingObservationFilter
import de.taktovia.app.probe.ProbeDb
import de.taktovia.app.probe.ProbeServer
import de.taktovia.platform.logging.Maskierung
import io.micrometer.common.KeyValue
import io.micrometer.observation.Observation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/** WP-M5-03: Trace-/Correlation-Durchstich im Fachkern, Verwaltungsport mit Prometheus-Ansicht, Maskierung der Trace-Attribute, Sammlerausfall. */
class TelemetryApiTest {
    private val base get() = mapOf("X-Test-Tenant" to ProbeDb.tenantA.toString(), "X-Test-Actor" to "telemetry-test")

    private fun traceId(s: ProbeServer, headers: Map<String, String>) = s.get("/v1/probe/telemetrie/trace", base + headers).let { it.json["trace_id"].asString() to it.header("X-Correlation-Id")!! }

    @Test
    fun gueltiger_traceparent_wird_uebernommen_ungueltiger_erzeugt_neue_correlation_id_gleich_trace_id() {
        val s = ProbeServer.get("management.tracing.export.enabled=true", "management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:1/v1/traces")
        val (trace, corr) = traceId(s, mapOf("traceparent" to "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", trace)
        assertEquals("4bf92f35-77b3-4da6-a3ce-929d0e0e4736", corr)
        for (bad in listOf("kaputt", "00-zz-zz-zz", "00-00000000000000000000000000000000-00f067aa0ba902b7-01", null)) {
            val (t, c) = traceId(s, if (bad == null) emptyMap() else mapOf("traceparent" to bad))
            assertNotEquals("4bf92f3577b34da6a3ce929d0e0e4736", t, "$bad")
            assertEquals(UUID.fromString(c).toString().replace("-", ""), t, "Correlation-ID und Trace-ID stimmen überein: $bad")
        }
    }

    @Test
    fun prometheus_nur_auf_dem_verwaltungsport_ohne_personenbezug_in_den_reihen() {
        val s = ProbeServer.get("management.server.port=0", "taktovia.telemetry.test=verwaltungsport")
        val mport = s.managementPort ?: error("kein Verwaltungsport")
        assertNotEquals(s.port, mport)
        // Anfragen mit Kontaktdaten in Pfad, Query und Header
        s.get("/v1/probe/items?q=erika.mustermann@example.test&phone=%2B491761234567", base + ("X-Contact" to "erika.mustermann@example.test"))
        s.get("/v1/probe/items/erika.mustermann@example.test", base)
        s.get("/v1/ride-offers?from=Musterstra%C3%9Fe%2012", base)
        val http = HttpClient.newHttpClient()
        val scrape = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$mport/actuator/prometheus")).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, scrape.statusCode())
        val text = scrape.body()
        // Scrape-Ausgabe des echten Fachkerns als Eingabe für tools/check_metric_labels.py (tests/acceptance/diagnose)
        java.nio.file.Files.createDirectories(java.nio.file.Path.of(System.getProperty("taktovia.repoRoot"), "backend/app/build/diagnose"))
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("taktovia.repoRoot"), "backend/app/build/diagnose/prometheus.txt"), text)
        assertTrue(text.contains("taktovia_http_server_request_seconds_bucket{") && text.contains("route_class=\"standard\"") && text.contains("route_class=\"offer\""), "Latenz je Endpunktklasse fehlt")
        assertTrue(text.contains("hikaricp_connections") || text.contains("jvm_memory"), "Standardmetriken (Pool, JVM) fehlen")
        for (w in listOf("erika", "mustermann", "example.test", "1761234567", "Musterstra", "phone")) assertFalse(text.contains(w), "Scrape enthält '$w'")
        assertEquals(emptySet<Maskierung.Klasse>(), Maskierung.klartextKlassen(text.lines().filter { !it.startsWith("#") }.joinToString("\n")).minus(setOf(Maskierung.Klasse.TELEFON, Maskierung.Klasse.KARTE)),
            "Muster in den Reihen")
        // Mandanten-API-Port liefert die Ansicht nicht ohne Anmeldung
        val onApi = s.get("/actuator/prometheus")
        assertTrue(onApi.status == 401 || onApi.status == 404, "API-Port: ${onApi.status}")
    }

    @Test
    fun prometheus_ohne_verwaltungsport_nie_oeffentlich() {
        // Server mit echter Authentifizierung (Test-Issuer, wie LogScanTest): ohne Token keine Metrikansicht auf dem API-Port
        val r = de.taktovia.app.identity.IdentityEnv.server.get("/actuator/prometheus")
        assertEquals(401, r.status)
    }

    @Test
    fun sammlerausfall_stoppt_keine_anfrage_und_keine_schreibende_buchung() {
        // OTLP-Exporter aktiv, Ziel nicht erreichbar (Port 1)
        val s = ProbeServer.get("management.tracing.export.enabled=true", "management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:1/v1/traces",
            "management.opentelemetry.tracing.export.schedule-delay=200ms")
        repeat(5) {
            val r = s.post("/v1/probe/items", """{"name":"telemetrie-$it"}""", base + mapOf("Idempotency-Key" to "tel-" + UUID.randomUUID()))
            assertEquals(201, r.status, r.text)
        }
        Thread.sleep(1500)   // Exportversuche gegen den toten Sammler laufen im Hintergrund
        assertEquals(200, s.get("/v1/probe/items", base).status)
    }

    @Test
    fun trace_attribute_der_beobachtung_werden_maskiert_und_url_ohne_query() {
        val ctx = Observation.Context()
        ctx.addHighCardinalityKeyValue(KeyValue.of("http.url", "https://x.example/v1/places?q=Musterstra%C3%9Fe&mail=erika.mustermann@example.test"))
        ctx.addHighCardinalityKeyValue(KeyValue.of("user.email", "erika.mustermann@example.test"))
        ctx.addHighCardinalityKeyValue(KeyValue.of("note", "Rückruf +49 176 12345678"))
        ctx.addLowCardinalityKeyValue(KeyValue.of("http.route", "/v1/bookings/{id}"))
        MaskingObservationFilter().map(ctx)
        val all = (ctx.highCardinalityKeyValues + ctx.lowCardinalityKeyValues).associate { it.key to it.value }
        assertEquals("https://x.example/v1/places", all["http.url"])
        assertEquals(Maskierung.Klasse.TOKEN.ersatz, all["user.email"])
        assertFalse(all["note"]!!.contains("12345678"))
        assertEquals("/v1/bookings/{id}", all["http.route"])
    }
}
