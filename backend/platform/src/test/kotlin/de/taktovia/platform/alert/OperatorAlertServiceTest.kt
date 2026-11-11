package de.taktovia.platform.alert

import de.taktovia.platform.TestTls
import de.taktovia.platform.secret.FileSecretStore
import de.taktovia.platform.secret.SecretRef
import de.taktovia.platform.secret.SecretResolver
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** WP-M1-47: Betriebsalarmkanal. Alle Tests laufen gegen echte Komponenten (Metrikregister, Kanal, HTTPS-Empfänger); Uhr ist steuerbar. */
class OperatorAlertServiceTest {
    class TestClock(var now: Instant = Instant.parse("2026-11-01T10:00:00Z")) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
        fun advance(d: Duration) { now = now.plus(d) }
    }
    class Recorder : AlertChannel {
        val got = CopyOnWriteArrayList<AlertNotification>(); val down = AtomicBoolean(false); val attempts = AtomicInteger()
        override fun deliver(notification: AlertNotification) { attempts.incrementAndGet(); if (down.get()) throw IllegalStateException("Kanal nicht erreichbar"); got.add(notification) }
    }

    private val reg = SimpleMeterRegistry(); private val lines = CopyOnWriteArrayList<String>(); private val clock = TestClock(); private val ch = Recorder()
    private val svc = OperatorAlertService(ch, reg, { lines.add(it) }, clock)
    private fun w(kind: String = "dead_letter", sev: Severity = Severity.WARNING, tenant: UUID? = UUID.randomUUID(), attrs: Map<String, String> = emptyMap()) =
        OperatorWarning("outbox-relay", kind, sev, UUID.randomUUID(), tenant, attrs)

    @Test fun `Testwarnung erreicht den Empfaenger und Ausloese- und Zustellzeitpunkt sind protokolliert`() {
        svc.report(w(attrs = mapOf("queue" to "events.q", "attempts" to "12")))
        assertEquals(1, ch.got.size)
        assertEquals("outbox-relay/dead_letter", ch.got.single().dedupKey)
        assertEquals(clock.now, ch.got.single().triggeredAt)
        val delivered = lines.single { it.contains("operator_alert_delivered") }
        assertTrue(delivered.contains("\"triggered_at\":\"2026-11-01T10:00:00Z\"") && delivered.contains("\"delivered_at\""), delivered)
    }

    @Test fun `anhaltend gleichartige Warnung erzeugt genau einen offenen Alarm und eine Zustellung`() {
        repeat(50) { svc.report(w()); clock.advance(Duration.ofSeconds(5)) }
        assertEquals(1, svc.openAlerts().size)
        assertEquals(50, svc.openAlerts().single().count)
        assertEquals(1, ch.got.size)
        svc.report(w(kind = "andere_art")); assertEquals(2, svc.openAlerts().size)
        // Ruhefenster: nach 15 min ohne gleichartige Warnung öffnet die nächste einen neuen Alarm
        clock.advance(Duration.ofMinutes(16)); svc.report(w()); assertEquals(2, ch.got.count { it.kind == "dead_letter" })
        // höhere Schwere eskaliert
        svc.report(w(sev = Severity.CRITICAL)); assertEquals("escalated", ch.got.last().reason)
        svc.resolve("outbox-relay", "dead_letter"); svc.report(w()); assertEquals("opened", ch.got.last().reason)
    }

    @Test fun `Warnung mit personenbezogenem Feld wird abgewiesen und nichts wird gemeldet`() {
        val mail = "anna" + "@" + "example.test"
        for (attrs in listOf(mapOf("email" to "x"), mapOf("passenger_name" to "Anna"), mapOf("reason_code" to mail), mapOf("component" to "+49 170 1234567"),
            mapOf("component" to "DE89370400440532013000"), mapOf("unbekannt" to "x"))) {
            assertThrows<IllegalArgumentException>("$attrs") { svc.report(w(attrs = attrs)) }
        }
        assertThrows<PersonalDataInWarningException> { svc.report(w(attrs = mapOf("phone" to "123"))) }
        assertEquals(0, svc.openAlerts().size); assertEquals(0, ch.got.size); assertTrue(lines.isEmpty())
        assertEquals(0, reg.meters.count { it.id.name == "taktovia_operator_warnings_total" })
    }

    @Test fun `Metrik und Logzeile enthalten nur zugelassene Labels und keine personenbezogenen oder fremden Mandantendaten`() {
        val t = UUID.randomUUID()
        svc.report(w(tenant = t, attrs = mapOf("queue" to "events.q")))
        val allowed = setOf("source", "kind", "severity", "result")
        reg.meters.forEach { m -> m.id.tags.forEach { tag ->
            assertTrue(tag.key in allowed, "Label ${tag.key}")
            assertFalse(tag.value.contains(t.toString()), "Mandant als Label")
            assertTrue(Regex("[a-z][a-z0-9_.-]*").matches(tag.value), tag.value)
        } }
        val line = lines.first()
        assertTrue(line.startsWith("{\"event\":\"operator_warning\"") && line.contains(t.toString()), line)   // Mandant nur als opake ID
        assertFalse(line.contains("events.q"), "Attribute gehören nicht in Metrik/Log (Platz für Inhalte vermeiden)")
    }

    @Test fun `nicht erreichbarer Kanal verliert nichts, Warnung bleibt in Metrik und Log sichtbar und die Zustellung wird wiederholt`() {
        ch.down.set(true)
        svc.report(w())
        assertEquals(0, ch.got.size); assertEquals(1, svc.pendingDeliveries())
        assertEquals(1.0, reg.counter("taktovia_operator_warnings_total", "source", "outbox-relay", "kind", "dead_letter", "severity", "warning").count())
        assertTrue(lines.any { it.contains("operator_warning\"") } && lines.any { it.contains("operator_alert_delivery_failed") })
        svc.flush(); assertEquals(1, ch.attempts.get(), "vor Fälligkeit keine Wiederholung")
        clock.advance(Duration.ofSeconds(2)); svc.flush(); assertEquals(2, ch.attempts.get())
        ch.down.set(false); clock.advance(Duration.ofSeconds(10)); svc.flush()
        assertEquals(1, ch.got.size); assertEquals(0, svc.pendingDeliveries())
        assertTrue(lines.last().contains("\"attempts\":3"))
    }

    @Test fun `200 gleichartige Warnungen aus parallelen Workern ergeben genau einen offenen Alarm und es geht keine verloren`() {
        val n = 200; val start = CountDownLatch(1); val exec = Executors.newFixedThreadPool(16)
        try {
            val fs = (1..n).map { exec.submit { start.await(); svc.report(w()) } }
            start.countDown(); fs.forEach { it.get() }
        } finally { exec.shutdownNow() }
        assertEquals(1, svc.openAlerts().size)
        assertEquals(n.toLong(), svc.openAlerts().single().count)
        assertEquals(n.toDouble(), reg.counter("taktovia_operator_warnings_total", "source", "outbox-relay", "kind", "dead_letter", "severity", "warning").count())
        assertEquals(n, lines.count { it.startsWith("{\"event\":\"operator_warning\"") })
        assertEquals(1, ch.got.size, "genau eine Zustellung für den einen Alarm")
    }

    @TempDir lateinit var dir: Path

    @Test fun `HTTPS-Webhook stellt zu, Zugang nur ueber Secret-Referenz, Klartext-URL wird abgewiesen`() {
        val m = TestTls.material(); val received = CopyOnWriteArrayList<String>()
        val server = TestTls.server(m) { received.add(it); 204 }
        try {
            val domain = "taktovia-development-secrets"; Files.createDirectories(dir.resolve(domain))
            val url = "https://localhost:${server.address.port}/hook/${UUID.randomUUID()}"
            Files.writeString(dir.resolve(domain).resolve("alert_channel_credential"), url + "\n")
            val resolver = SecretResolver(domain, FileSecretStore(dir))
            val chan = HttpsWebhookChannel(SecretRef(domain, "alert_channel_credential"), resolver, HttpClient.newBuilder().sslContext(m.client).build())
            val s = OperatorAlertService(chan, reg, { lines.add(it) }, clock); s.report(w())
            assertEquals(1, received.size); assertTrue(received.single().contains("\"dedup_key\":\"outbox-relay/dead_letter\""))
            assertEquals(0, s.pendingDeliveries()); assertFalse(lines.any { it.contains(url) || it.contains("/hook/") }, "Adresse darf nicht im Log stehen")
            // Klartext-Adresse: Zustellung scheitert ohne den Wert auszugeben und bleibt zur Wiederholung vorgemerkt
            Files.writeString(dir.resolve(domain).resolve("alert_channel_credential"), url.replace("https://", "http://") + "\n")
            val s2 = OperatorAlertService(chan, reg, { lines.add(it) }, clock); s2.report(w(kind = "x_art"))
            assertEquals(1, s2.pendingDeliveries()); assertEquals(1, received.size); assertFalse(lines.any { it.contains("/hook/") })
            // Empfänger antwortet mit Fehler -> Wiederholung
            val bad = TestTls.server(m) { 503 }
            try {
                Files.writeString(dir.resolve(domain).resolve("alert_channel_credential"), "https://localhost:${bad.address.port}/h\n")
                val s3 = OperatorAlertService(chan, reg, { lines.add(it) }, clock); s3.report(w(kind = "y_art")); assertEquals(1, s3.pendingDeliveries())
            } finally { bad.stop(0) }
        } finally { server.stop(0) }
    }
}
