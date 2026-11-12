package de.taktovia.platform.telemetry

import de.taktovia.platform.TestDatabase
import de.taktovia.platform.outbox.InMemoryMessagePublisher
import de.taktovia.platform.outbox.OutboxRelay
import de.taktovia.platform.outbox.OutboxTestSupport
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** WP-M5-03: Konvention, Labelschutz, Warteschlangenmetriken je Mandant gegen die echte Datenbank, Ausfallsicherheit der Instrumentierung. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelemetryTest {
    private lateinit var s: OutboxTestSupport

    @BeforeAll fun up() { TestDatabase.require(); s = OutboxTestSupport() }
    @AfterAll fun down() = s.close()

    @Test fun label_mit_kontakt_name_oder_suchbegriff_wird_nicht_registriert() {
        val filter = LabelPolicyFilter()
        val reg = SimpleMeterRegistry().also { it.config().meterFilter(filter) }
        reg.counter("taktovia_booking_requests_total", "route_class", "offer", "result", "ok").increment()
        // Schlüssel mit Personen-/Such-/Kontaktbezug
        reg.counter("taktovia_booking_requests_total", "passenger_name", "Erika").increment()
        reg.counter("taktovia_booking_requests_total", "search.query", "Hauptbahnhof").increment()
        reg.counter("taktovia_booking_requests_total", "email", "x").increment()
        // zulässiger Schlüssel, aber Kontaktwert
        reg.counter("taktovia_booking_requests_total", "result", "erika.mustermann@example.test").increment()
        reg.counter("taktovia_booking_requests_total", "reason", "+49 176 12345678").increment()
        reg.counter("taktovia_booking_requests_total", "reason", "DE89 3704 0044 0532 0130 00").increment()
        // nicht in der Positivliste, ungültiger Name, tenant_id ohne UUID
        reg.counter("taktovia_booking_requests_total", "ort", "x").increment()
        reg.counter("taktovia_x", "result", "ok").increment()
        reg.counter("taktovia_booking_requests_total", "tenant_id", "Mandant A").increment()
        assertEquals(1, reg.find("taktovia_booking_requests_total").counters().size, "nur die zulässige Reihe existiert: ${filter.violations()}")
        assertEquals(9L, filter.denied(), filter.violations().toString())
        assertTrue(filter.violations().none { it.contains("Erika") || it.contains("@") || it.contains("Hauptbahnhof") || it.contains("12345678") }, "Verletzungen nennen nie den Wert")
        assertNull(MetricConvention.violation("taktovia_x_total", mapOf("tenant_id" to UUID.randomUUID().toString(), "route_class" to "offer")))
    }

    private fun rows(c: Connection, t: UUID) {
        c.autoCommit = false
        c.createStatement().use { it.execute("SET LOCAL session_replication_role = replica") }
    }

    @Test fun warteschlangenmetriken_je_mandant_ohne_fremddaten() {
        val a = s.newTenant(); val b = s.newTenant()
        // A: 3 offene Outbox-Zeilen (eine alt), 1 Dead-Letter; B: 1 offene
        val old = s.append(a, occurredAt = Instant.now())
        repeat(2) { s.append(a, occurredAt = Instant.now()) }
        val dead = s.append(a, occurredAt = Instant.now())
        s.admin.update("UPDATE taktovia.outbox SET recorded_at = now() - interval '90 seconds' WHERE tenant_id = ? AND event_id = ?", a.value, old)
        s.admin.update("UPDATE taktovia.outbox SET dead_lettered_at = now() WHERE tenant_id = ? AND event_id = ?", a.value, dead)
        s.append(b, occurredAt = Instant.now())
        // Operationen: A hat eine abgelaufene Lease (RUNNING) und eine fällige Frist
        val ops = de.taktovia.platform.operation.OperationService()
        s.tx.run(a) { j -> ops.enqueue(j, "TEST_COMMAND", Instant.now().minusSeconds(60)) }
        s.tx.run(a) { j -> ops.enqueue(j, "TEST_COMMAND", Instant.now().plusSeconds(3600)) }
        s.admin.update("UPDATE taktovia.operation SET status = 'RUNNING', lease_until = now() - interval '30 seconds', lease_owner = 'w', fencing_token = 1 WHERE tenant_id = ? AND deadline_at > now()", a.value)
        // payment_intent unbestimmt (Fremdschlüssel-Kette für den Test per Replikationsrolle umgangen)
        s.admin.dataSource!!.connection.use { c ->
            rows(c, a.value)
            c.prepareStatement("INSERT INTO taktovia.payment_intent(tenant_id,id,journey_id,provider,provider_ref,state,currency,authorized_minor) VALUES (?,?,?,?,?,?,'EUR',100)").use { ps ->
                for ((t, state) in listOf(a to "CAPTURE_UNKNOWN", a to "RECONCILING", a to "CAPTURED", b to "CAPTURED")) {
                    ps.setObject(1, t.value); ps.setObject(2, UUID.randomUUID()); ps.setObject(3, UUID.randomUUID()); ps.setString(4, "test"); ps.setString(5, "ref-" + UUID.randomUUID()); ps.setString(6, state); ps.addBatch()
                }
                ps.executeBatch()
            }
            c.commit()
        }
        val reg = SimpleMeterRegistry()
        val qm = QueueMetrics(s.tx, { listOf(a, b) }, reg)
        assertEquals(2, qm.refresh())
        fun g(name: String, t: UUID) = reg.get(name).tag("tenant_id", t.toString()).gauge().value()
        assertEquals(3.0, g("taktovia_outbox_depth", a.value)); assertEquals(1.0, g("taktovia_outbox_depth", b.value))
        assertEquals(1.0, g("taktovia_outbox_dead_lettered", a.value)); assertEquals(0.0, g("taktovia_outbox_dead_lettered", b.value))
        assertTrue(g("taktovia_outbox_oldest_age_seconds", a.value) in 85.0..600.0, "Alter der ältesten offenen Zeile")
        assertEquals(2.0, g("taktovia_operation_open", a.value)); assertEquals(0.0, g("taktovia_operation_open", b.value))
        assertEquals(1.0, g("taktovia_operation_lease_overdue", a.value))
        assertEquals(1.0, g("taktovia_operation_deadline_overdue", a.value))
        assertEquals(2.0, g("taktovia_payment_intent_indeterminate", a.value)); assertEquals(0.0, g("taktovia_payment_intent_indeterminate", b.value))
        // Konvention gilt für die eigenen Reihen: jedes Label ist technisch
        reg.meters.filter { it.id.name.startsWith("taktovia_") }.forEach { m: Meter -> assertNull(MetricConvention.violation(m.id.name, m.id.tags.associate { it.key to it.value }), m.id.name) }
        // zweiter Lauf aktualisiert dieselben Reihen (keine Verdopplung)
        s.append(b, occurredAt = Instant.now()); qm.refresh()
        assertEquals(2.0, g("taktovia_outbox_depth", b.value))
        assertEquals(reg.meters.size, reg.meters.map { it.id }.distinct().size)
    }

    @Test fun relay_misst_zustellverzoegerung_zwischen_commit_und_veroeffentlichung() {
        val t = s.newTenant()
        s.append(t, occurredAt = Instant.now())
        s.admin.update("UPDATE taktovia.outbox SET recorded_at = now() - interval '5 seconds' WHERE tenant_id = ?", t.value)
        val reg = SimpleMeterRegistry()
        val st = OutboxRelay(s.tx, InMemoryMessagePublisher(), { listOf(t) }, s.alerts, s.security, metrics = PlatformMetrics(reg)).runOnce()
        assertEquals(1, st.published)
        val timer = reg.get("taktovia_outbox_publish_delay_seconds").timer()
        assertEquals(1, timer.count())
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS) in 4.5..60.0, "Verzögerung ${timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)} s")
    }

    @Test fun telemetrieausfall_beeintraechtigt_weder_relay_noch_abfragen() {
        val broken = object : SimpleMeterRegistry() {
            override fun newTimer(id: Meter.Id, c: io.micrometer.core.instrument.distribution.DistributionStatisticConfig, p: io.micrometer.core.instrument.distribution.pause.PauseDetector) = throw IllegalStateException("Sammler ausgefallen")
            override fun newCounter(id: Meter.Id) = throw IllegalStateException("Sammler ausgefallen")
        }
        val before = SafeTelemetry.failures()
        val pm = PlatformMetrics(broken)
        pm.httpRequest(RouteClass.OFFER, "POST", 200, Duration.ofMillis(5))        // wirft nicht
        assertEquals(42, pm.measureLock("hold") { 42 })
        val t = s.newTenant(); s.append(t, occurredAt = Instant.now())
        val st = OutboxRelay(s.tx, InMemoryMessagePublisher(), { listOf(t) }, s.alerts, s.security, metrics = pm).runOnce()
        assertEquals(1, st.published, "die Zustellung läuft trotz ausgefallener Telemetrie")
        assertTrue(SafeTelemetry.failures() > before)
        // Warteschlangenquelle fällt aus: refresh wirft nicht
        assertEquals(0, QueueMetrics(s.tx, { throw IllegalStateException("Quelle weg") }, SimpleMeterRegistry()).refresh())
    }

    @Test fun endpunktklassen_und_pfade_ohne_personenbezug() {
        assertEquals(RouteClass.OFFER, RouteClass.of("/v1/ride-offers"))
        assertEquals(RouteClass.BOOKING, RouteClass.of("/v1/bookings/5c1f"))
        assertEquals(RouteClass.STANDARD, RouteClass.of("/v1/passengers/me"))
        assertEquals(RouteClass.OTHER, RouteClass.of("/x"))
        assertEquals("OTHER", RouteClass.method("BREW"))
        val reg = SimpleMeterRegistry()
        PlatformMetrics(reg).httpRequest(RouteClass.OFFER, "POST", 201, Duration.ofMillis(120))
        val timer = reg.get("taktovia_http_server_request_seconds").tags("route_class", "offer", "method", "POST", "status_class", "2xx").timer()
        assertEquals(1, timer.count())
        assertNotNull(timer.takeSnapshot())
        val _unused: MeterRegistry = reg
    }
}
