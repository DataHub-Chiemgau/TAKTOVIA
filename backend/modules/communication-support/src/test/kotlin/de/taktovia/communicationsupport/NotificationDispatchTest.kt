package de.taktovia.communicationsupport

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import de.taktovia.communicationsupport.api.DispatchConfig
import de.taktovia.communicationsupport.api.NotificationChannel
import de.taktovia.communicationsupport.api.NotificationEventConsumer
import de.taktovia.communicationsupport.api.NotificationRequest
import de.taktovia.communicationsupport.api.NotificationState
import de.taktovia.communicationsupport.api.NotificationTrigger
import de.taktovia.platform.alert.Severity
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.outbox.InboxOutcome
import de.taktovia.platform.outbox.InboxProcessor
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * LH-6.12-COM-003 / WP-M2-32: asynchroner Versand mit Zustellversuchen gegen die echte Datenbank (Runtime-Rolle). Der Provider ist ein steuerbares Testdouble
 * ([ScriptedGateway]); Vorlagen entstehen durch den Seed. Nicht ausgeführt wird der Broker (RabbitMQ): der Ereignisverbraucher wird über denselben
 * [InboxProcessor] aufgerufen, den ein Broker-Verbraucher nutzt.
 */
class NotificationDispatchTest {
    private companion object {
        val f = CommFixture()
        @JvmStatic @AfterAll fun close() = f.close()
        val mapper: JsonMapper = JsonMapper.builder().build()
    }

    private fun seededWorld(): CommFixture.World = f.world().also { f.api.templates.seedDefaults(f.context(it.tenant)) }
    private fun runTenant(w: CommFixture.World, config: DispatchConfig = DispatchConfig(maxAttempts = 4)) = f.dispatcher(config).runTenant(w.tenant)

    @Test
    fun provider_timeout_erzeugt_keine_mehrfachnachricht() {
        // (a) Timeout mit spätem Erfolg: der Provider hat zugestellt, die Antwort ging verloren
        val w = seededWorld(); f.gateway.script.add(ScriptedGateway.Step.TIMEOUT_DELIVERED)
        val n = f.enqueue(w)
        runTenant(w)
        assertEquals("UNKNOWN", f.stateOf(n), "Timeout ist unbestimmt, nie Fehler oder Erfolg")
        assertEquals(listOf("TIMED_OUT"), f.attemptsOf(n))
        f.clock.advance(Duration.ofMinutes(11)); runTenant(w)                         // Abgleich beim Provider vor jeder Wiederholung
        assertEquals("DELIVERED", f.stateOf(n))
        assertEquals(1, f.gateway.sent.count { it.messageId == n }, "nach spätem Erfolg kein zweiter Versand")
        assertEquals(1, f.gateway.deliveries.count { it.key == n }, "höchstens eine fachliche Nachricht")
        assertEquals(1, f.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'communication.notification_sent' AND aggregate_id = ?", w.tenant.value, n))

        // (b) Timeout ohne Zustellung: der Abgleich beweist Nichtzustellung, die Wiederholung nutzt dieselbe Nachrichten-ID
        f.gateway.script.add(ScriptedGateway.Step.TIMEOUT_LOST)
        val m = f.enqueue(w)
        runTenant(w); assertEquals("UNKNOWN", f.stateOf(m))
        f.clock.advance(Duration.ofMinutes(11)); runTenant(w)
        assertEquals("DELIVERED", f.stateOf(m))
        assertEquals(listOf(m, m), f.gateway.sent.filter { it.messageId == m }.map { it.messageId }, "zwei Versuche, eine Nachrichten-ID")
        assertEquals(1, f.gateway.deliveries.count { it.key == m }, "eine Nachricht beim Endnutzer")
        assertEquals(listOf("TIMED_OUT", "ACCEPTED"), f.attemptsOf(m))
        assertEquals(2, f.strings("SELECT attempt_count::text FROM taktovia.notification WHERE id = ?", m).single().toInt())
    }

    @Test
    fun unbestimmter_ausgang_wird_nie_zu_erfolg_oder_fehler_verkuerzt() {
        val w = seededWorld(); f.gateway.script.add(ScriptedGateway.Step.TIMEOUT_DELIVERED); f.gateway.statusUnavailable = true
        val n = f.enqueue(w)
        val config = DispatchConfig(maxAttempts = 3)
        runTenant(w, config)
        repeat(2) { f.clock.advance(Duration.ofMinutes(11)); runTenant(w, config); assertEquals("UNKNOWN", f.stateOf(n), "ohne Providerauskunft bleibt es unbestimmt") }
        assertEquals(1, f.gateway.sent.count { it.messageId == n }, "ohne Abgleich keine Wiederholung")
        f.clock.advance(Duration.ofMinutes(11)); runTenant(w, config)                  // Abgleich dauerhaft unmöglich: dokumentierter Fehlschlag mit Warnung
        assertEquals("FAILED", f.stateOf(n)); assertEquals("RETRIES_EXHAUSTED", f.failureOf(n))
        assertEquals(1, f.alerts.warnings.count { it.kind == "dead_letter" && it.tenantRef == w.tenant.value })
        f.gateway.statusUnavailable = false
    }

    @Test
    fun dasselbe_ereignis_parallel_ergibt_ein_auftrag() {
        val w = f.world()
        val security = SecurityEventPort(JdbcAuditPort())
        val processor = InboxProcessor(f.tx, security, { _, _, _ -> error("kein Snapshot erwartet") })
        val trigger = NotificationTrigger { e -> listOf(NotificationRequest(e.eventId, UUID.fromString(e.payload.get("passenger_id").asString()), NotificationChannel.SMS,
            "booking.confirmed", w.serviceA, f.defaultParams(e.payload.get("booking_reference").asString()))) }
        val consumer = f.api.eventConsumer(processor, listOf(trigger))
        val eventId = UUID.randomUUID()
        val json = EventEnvelope(eventId, "booking.confirmed", "1.0", UUID.randomUUID(), 1, f.clock.instant(), f.clock.instant(), w.tenant.value, w.tenant.value, UUID.randomUUID(),
            mapper.readTree("""{"passenger_id":"${w.passenger}","booking_reference":"BK-77"}""")).toJson()
        val n = 6; val barrier = CyclicBarrier(n); val pool = Executors.newFixedThreadPool(n)
        try {
            val outcomes = (1..n).map { pool.submit(Callable { barrier.await(); consumer.consume(json) }) }.map { it.get() }
            assertEquals(1, outcomes.count { it == InboxOutcome.PROCESSED }, outcomes.toString())
            assertEquals(n - 1, outcomes.count { it == InboxOutcome.DUPLICATE }, outcomes.toString())
        } finally { pool.shutdownNow() }
        assertEquals(1, f.count("SELECT count(*) FROM taktovia.notification WHERE tenant_id = ? AND event_id = ?", w.tenant.value, eventId))
        assertEquals(1, f.count("SELECT count(*) FROM taktovia.inbox WHERE tenant_id = ? AND consumer = ? AND event_id = ?", w.tenant.value, NotificationEventConsumer.CONSUMER, eventId))
        // erneute Zustellung später: weiterhin ein Auftrag
        assertEquals(InboxOutcome.DUPLICATE, consumer.consume(json))
        assertEquals(1, f.count("SELECT count(*) FROM taktovia.notification WHERE tenant_id = ? AND event_id = ?", w.tenant.value, eventId))

        // ohne Inbox (zwei Aufrufer der Fachoperation mit demselben Schlüssel): Unique allein genügt ebenfalls
        val key = UUID.randomUUID(); val barrier2 = CyclicBarrier(n); val pool2 = Executors.newFixedThreadPool(n)
        try {
            val results = (1..n).map { pool2.submit(Callable { barrier2.await(); f.tx.run(w.tenant) { j -> f.api.notifications.send(j, NotificationRequest(key, w.passenger, NotificationChannel.EMAIL, "booking.confirmed", w.serviceA, f.defaultParams())) } }) }.map { it.get() }
            assertEquals(1, results.count { it.created }); assertEquals(1, results.map { it.notificationId }.distinct().size, "alle erhalten dieselbe Nachrichten-ID")
        } finally { pool2.shutdownNow() }
        assertEquals(1, f.count("SELECT count(*) FROM taktovia.notification WHERE tenant_id = ? AND event_id = ? AND channel = 'EMAIL'", w.tenant.value, key))
    }

    @Test
    fun dauerhafter_fehler_nach_hoechstzahl_dead_letter_und_warnung() {
        val w = seededWorld(); f.gateway.default = ScriptedGateway.Step.REJECT_TEMPORARY
        val config = DispatchConfig(maxAttempts = 3)
        val n = f.enqueue(w)
        runTenant(w, config); assertEquals("RETRY_WAIT", f.stateOf(n))
        f.clock.advance(Duration.ofMinutes(11)); runTenant(w, config); assertEquals("RETRY_WAIT", f.stateOf(n))
        f.clock.advance(Duration.ofMinutes(11)); runTenant(w, config)
        f.gateway.default = ScriptedGateway.Step.ACCEPT
        assertEquals("FAILED", f.stateOf(n)); assertEquals("RETRIES_EXHAUSTED", f.failureOf(n))
        assertEquals(listOf("REJECTED", "REJECTED", "REJECTED"), f.attemptsOf(n), "kein stiller Verlust: jeder Versuch dokumentiert")
        assertEquals(1, f.count("SELECT count(*) FROM taktovia.notification WHERE id = ? AND dead_lettered_at IS NOT NULL", n))
        val warnings = f.alerts.warnings.filter { it.tenantRef == w.tenant.value }
        assertEquals(1, warnings.size); assertEquals(Severity.CRITICAL, warnings.single().severity); assertEquals("dead_letter", warnings.single().kind)
        val payload = f.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'communication.notification_failed' AND aggregate_id = ?", w.tenant.value, n).single()
        assertEquals("RETRIES_EXHAUSTED", mapper.readTree(payload).get("failure").asString()); assertEquals(3, mapper.readTree(payload).get("attempts").asInt())
        f.clock.advance(Duration.ofHours(1)); runTenant(w, config)
        assertEquals(3, f.gateway.sent.count { it.messageId == n }, "nach dem Dead-Letter wird nichts mehr gesendet")
        // sichtbar für den Betreiber über den Dienst (Grundlage von GET /v1/notifications)
        val view = f.api.notifications.get(f.context(w.tenant), n)
        assertEquals(NotificationState.FAILED, view.state); assertEquals("RETRIES_EXHAUSTED", view.lastFailure?.name); assertEquals(3, view.attempts)

        // dauerhafter Providerfehler (nicht wiederholbar): sofort dokumentierter Fehlschlag
        f.gateway.script.add(ScriptedGateway.Step.REJECT_PERMANENT)
        val m = f.enqueue(w); runTenant(w, config)
        assertEquals("FAILED", f.stateOf(m)); assertEquals("PROVIDER_ERROR", f.failureOf(m)); assertEquals(listOf("REJECTED"), f.attemptsOf(m))
    }

    @Test
    fun ungueltiger_kontakt_dokumentierter_fehler() {
        val w = seededWorld(); f.gateway.script.add(ScriptedGateway.Step.REJECT_INVALID_CONTACT)
        val n = f.enqueue(w); runTenant(w)
        assertEquals("FAILED", f.stateOf(n)); assertEquals("INVALID_CONTACT", f.failureOf(n))
        f.clock.advance(Duration.ofHours(1)); runTenant(w)
        assertEquals(1, f.gateway.sent.count { it.messageId == n }, "ungültiger Kontakt wird nicht wiederholt")
        assertEquals(1, f.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'communication.notification_failed' AND aggregate_id = ?", w.tenant.value, n))

        // kein verifizierter Kontakt: unterdrückt und dokumentiert, nie ein Providerruf
        val x = f.world(1); f.api.templates.seedDefaults(f.context(x.tenant)); f.directory.contacts.remove(x.passenger to NotificationChannel.SMS)
        val before = f.gateway.sent.size
        val m = f.enqueue(x); runTenant(x)
        assertEquals("SUPPRESSED", f.stateOf(m)); assertEquals("NO_VERIFIED_CONTACT", f.failureOf(m))
        assertEquals(before, f.gateway.sent.size)
    }

    @Test
    fun nachrichten_id_in_allen_versuchen_und_beim_provider_identisch() {
        val w = seededWorld()
        f.gateway.script.addAll(listOf(ScriptedGateway.Step.REJECT_TEMPORARY, ScriptedGateway.Step.TIMEOUT_LOST, ScriptedGateway.Step.ACCEPT))
        val n = f.enqueue(w, channel = NotificationChannel.EMAIL)
        runTenant(w)                                                                   // 1: abgelehnt -> RETRY_WAIT
        f.clock.advance(Duration.ofMinutes(11)); runTenant(w)                          // 2: Timeout -> UNKNOWN
        f.clock.advance(Duration.ofMinutes(11)); runTenant(w)                          // Abgleich: nicht bekannt -> 3: angenommen
        assertEquals("DELIVERED", f.stateOf(n))
        assertEquals(3, f.gateway.sent.count { it.messageId == n })
        assertEquals(setOf(n), f.gateway.sent.filter { it.messageId == n }.map { it.messageId }.toSet(), "Nachrichten-ID ist die ID des Auftrags in jedem Versuch")
        assertEquals(listOf("REJECTED", "TIMED_OUT", "ACCEPTED"), f.attemptsOf(n))
        assertEquals(listOf(1, 2, 3), f.strings("SELECT attempt_no::text FROM taktovia.notification_attempt WHERE notification_id = ? ORDER BY attempt_no", n).map { it.toInt() })
        assertEquals(1, f.gateway.deliveries.count { it.key == n })
        assertEquals("ref-$n", f.strings("SELECT provider_ref FROM taktovia.notification_attempt WHERE notification_id = ? AND attempt_no = 3", n).single())
        // die Nachrichten-ID ist die Auftrags-ID (eindeutig je Auftrag)
        val ids = (1..5).map { f.enqueue(w) }
        assertEquals(5, ids.toSet().size)
    }

    @Test
    fun ereignisse_entsprechen_den_vertragsschemas() {
        val w = seededWorld(); val registry = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012()); val repo = java.nio.file.Path.of(System.getProperty("taktovia.repoRoot"))
        fun schema(name: String) = registry.getSchema(java.nio.file.Files.readString(repo.resolve("api/events/$name.v1.schema.json")))
        val bad = f.enqueue(w, channel = NotificationChannel.EMAIL); f.gateway.forMessage(bad, ScriptedGateway.Step.REJECT_PERMANENT)
        val ok = f.enqueue(w)
        val noTemplate = f.enqueue(w, service = null, eventType = "eta.changed", params = f.defaultParams().minus("eta"))
        runTenant(w)
        val sent = f.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'communication.notification_sent'", w.tenant.value)
        val failed = f.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'communication.notification_failed'", w.tenant.value)
        assertTrue(sent.size == 1 && failed.size == 2, "${sent.size}/${failed.size}")
        sent.forEach { assertEquals(emptyList<String>(), schema("communication.notification_sent").validate(it, InputFormat.JSON).map { m -> m.toString() }, it) }
        failed.forEach { assertEquals(emptyList<String>(), schema("communication.notification_failed").validate(it, InputFormat.JSON).map { m -> m.toString() }, it) }
        assertTrue(sent.single().contains(ok.toString())); assertTrue(failed.any { it.contains(bad.toString()) } && failed.any { it.contains(noTemplate.toString()) })
        for (secret in listOf("+49170", "example.org", "GEHEIM")) assertFalse((sent + failed).any { secret in it }, "Kontakt im Ereignis")
    }

    @Test
    fun versand_je_kanal_ueber_den_provider() {
        val w = seededWorld()
        val ids = NotificationChannel.entries.associateWith { f.enqueue(w, channel = it) }
        val stats = runTenant(w)
        assertEquals(3, stats.delivered)
        ids.forEach { (ch, id) ->
            assertEquals("DELIVERED", f.stateOf(id), ch.name)
            val sent = f.gateway.sent.single { it.messageId == id }
            assertEquals(ch, sent.channel); assertEquals(f.directory.contacts[w.passenger to ch], sent.to)
            assertTrue(sent.body.isNotBlank(), "keine leere Nachricht")
        }
        assertEquals(3, f.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'communication.notification_sent'", w.tenant.value))
    }

    @Test
    fun providerausfall_verzoegert_keine_buchung() {
        val w = seededWorld()
        // 1. Der Providerruf läuft ausserhalb jeder Datenbanktransaktion; währenddessen sind Fachtransaktionen unbehindert
        val inFlight = CountDownLatch(1); val release = CountDownLatch(1); val openDuringSend = CopyOnWriteArrayList<Int>()
        f.gateway.beforeSend = { openDuringSend += f.openTransactions(); inFlight.countDown(); release.await(20, TimeUnit.SECONDS) }
        val n = f.enqueue(w)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val run = pool.submit(Callable { runTenant(w) })
            assertTrue(inFlight.await(20, TimeUnit.SECONDS), "Provider wurde aufgerufen")
            val started = System.nanoTime()
            // „Buchung“ (Fachzeile) und neuer Auftrag in einer Fachtransaktion, während der Provider hängt
            val attempt = UUID.randomUUID()
            f.tx.run(w.tenant) { j ->
                j.update("INSERT INTO taktovia.travel_attempt(tenant_id,id,created_at,channel) VALUES (taktovia.current_tenant(),?,now(),'WEB')", attempt)
                f.api.notifications.send(j, NotificationRequest(UUID.randomUUID(), w.passenger, NotificationChannel.SMS, "booking.confirmed", w.serviceA, f.defaultParams()))
            }
            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(5), "Fachtransaktion wurde vom Provider nicht verzögert")
            assertEquals(1, f.count("SELECT count(*) FROM taktovia.travel_attempt WHERE tenant_id = ? AND id = ?", w.tenant.value, attempt))
            assertEquals(listOf(0), openDuringSend, "beim Providerruf ist keine Transaktion offen (Leitlinie 3)")
            release.countDown(); run.get(20, TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow(); f.gateway.beforeSend = {} }
        assertEquals("DELIVERED", f.stateOf(n))

        // 2. Totalausfall des Providers: Aufträge werden weiter angenommen, die Fachzeile bleibt unberührt, nichts geht still verloren
        f.gateway.default = ScriptedGateway.Step.TIMEOUT_LOST; f.gateway.statusUnavailable = true
        val attempt2 = UUID.randomUUID()
        f.tx.run(w.tenant) { j -> j.update("INSERT INTO taktovia.travel_attempt(tenant_id,id,created_at,channel) VALUES (taktovia.current_tenant(),?,now(),'WEB')", attempt2) }
        val m = f.enqueue(w); runTenant(w)
        assertEquals("UNKNOWN", f.stateOf(m))
        assertEquals(1, f.count("SELECT count(*) FROM taktovia.travel_attempt WHERE tenant_id = ? AND id = ?", w.tenant.value, attempt2))
        f.gateway.default = ScriptedGateway.Step.ACCEPT; f.gateway.statusUnavailable = false
        f.clock.advance(Duration.ofMinutes(11)); runTenant(w)
        assertEquals("DELIVERED", f.stateOf(m), "nach Wiederkehr des Providers wird zugestellt")
    }

    @Test
    fun parallele_worker_senden_jede_nachricht_einmal() {
        val w = seededWorld()
        val ids = (1..24).map { f.enqueue(w) }
        val pool = Executors.newFixedThreadPool(4); val barrier = CyclicBarrier(4)
        try {
            (1..4).map { pool.submit(Callable { barrier.await(); runTenant(w) }) }.forEach { it.get() }
        } finally { pool.shutdownNow() }
        ids.forEach { id -> assertEquals(1, f.gateway.sent.count { it.messageId == id }, "genau ein Versand je Nachrichten-ID"); assertEquals("DELIVERED", f.stateOf(id)) }
    }

    @Test
    fun abgelaufene_lease_ist_unbestimmt_und_wird_abgeglichen() {
        val w = seededWorld()
        val n = f.enqueue(w)
        // ein Worker ist nach dem Beanspruchen abgestürzt: Zustand SENDING mit abgelaufener Lease
        f.exec("UPDATE taktovia.notification SET state='SENDING', claimed_until = ? WHERE id = ?", java.sql.Timestamp.from(f.clock.instant().minusSeconds(5)), n)
        runTenant(w)
        // der Abgleich fand nichts beim Provider (nie gesendet) -> Wiederholung mit derselben ID
        assertEquals("DELIVERED", f.stateOf(n)); assertEquals(listOf("TIMED_OUT", "ACCEPTED"), f.attemptsOf(n))
        assertEquals(1, f.gateway.sent.count { it.messageId == n })
    }

    @Test
    fun keine_inhalte_oder_kontaktdaten_in_logs_und_metriklabels() {
        val w = seededWorld(); val metrics = SimpleMeterRegistry()
        val collected = CopyOnWriteArrayList<ILoggingEvent>()
        val appender = object : AppenderBase<ILoggingEvent>() { override fun append(e: ILoggingEvent) { collected += e } }.also { it.start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        // Eigene Logger auf ALL, Frameworks auf DEBUG: das TRACE-Protokoll der JDBC-Bibliothek (gebundene Parameter) ist keine Betriebseinstellung
        val own = listOf("taktovia", "de.taktovia").map { LoggerFactory.getLogger(it) as Logger }; val ownBefore = own.map { it.level }
        val before = root.level; root.level = Level.DEBUG; own.forEach { it.level = Level.ALL }; root.addAppender(appender)
        val ref = "GEHEIM-" + UUID.randomUUID().toString().take(8)
        val phone = f.directory.contacts[w.passenger to NotificationChannel.SMS]!!; val email = f.directory.contacts[w.passenger to NotificationChannel.EMAIL]!!
        try {
            val d = f.dispatcher(DispatchConfig(maxAttempts = 2), metrics)
            val ok = f.enqueue(w, params = f.defaultParams(ref))
            val invalid = f.enqueue(w, channel = NotificationChannel.EMAIL, params = f.defaultParams(ref)); f.gateway.forMessage(invalid, ScriptedGateway.Step.REJECT_INVALID_CONTACT)
            val flaky = f.enqueue(w, params = f.defaultParams(ref)); f.gateway.forMessage(flaky, ScriptedGateway.Step.TIMEOUT_LOST, ScriptedGateway.Step.REJECT_TEMPORARY)
            d.runTenant(w.tenant)
            f.clock.advance(Duration.ofMinutes(11)); d.runTenant(w.tenant); f.clock.advance(Duration.ofMinutes(11)); d.runTenant(w.tenant)
            val logged = collected.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") + " " + it.argumentArray?.joinToString { a -> a.toString() } }
            val mine = f.strings("SELECT id::text FROM taktovia.notification WHERE tenant_id = ?", w.tenant.value).map(UUID::fromString).toSet()
            val sentBodies = f.gateway.sent.filter { it.messageId in mine }.map { it.body }.distinct()
            assertTrue(sentBodies.isNotEmpty() && sentBodies.all { ref in it }, "die Nachricht enthielt die Buchungsreferenz")
            for (secret in listOf(ref, phone, email)) assertFalse(logged.contains(secret), "Inhalt oder Kontakt im Log: " + logged.lines().filter { secret in it }.take(3))
            sentBodies.forEach { assertFalse(logged.contains(it), "Nachrichtentext im Log") }
            // Metriklabels: nur Kanal und Ausgang, nie Kontakt, Inhalt oder IDs
            val labels = metrics.meters.flatMap { m -> m.id.tags.map { it.key to it.value } }
            assertTrue(labels.isNotEmpty(), "Metriken vorhanden")
            assertTrue(labels.all { it.first in setOf("channel", "outcome") }, labels.toString())
            labels.forEach { (_, v) -> assertTrue(v in setOf("push", "sms", "email", "delivered", "retry", "unknown", "failed", "suppressed"), "unerwartetes Label $v") }
            // Datenbank: weder Kontakt noch Nachrichtentext im Auftrag; Betreiberwarnung ohne personenbezogene Angaben
            assertEquals(0, f.count("SELECT count(*) FROM taktovia.notification WHERE tenant_id = ? AND (notification::text LIKE ? OR notification::text LIKE ?)", w.tenant.value, "%$phone%", "%$email%"))
            assertEquals(0, f.count("SELECT count(*) FROM taktovia.notification_attempt WHERE tenant_id = ? AND (notification_attempt::text LIKE ? OR notification_attempt::text LIKE ?)", w.tenant.value, "%$phone%", "%$email%"))
            f.alerts.warnings.forEach { wn -> assertFalse((wn.attributes.values + wn.kind).any { phone in it || email in it || ref in it }, "personenbezogene Angabe in der Warnung") }
            assertEquals("DELIVERED", f.stateOf(ok))
        } finally { root.detachAppender(appender); root.level = before; own.zip(ownBefore).forEach { (l, lv) -> l.level = lv } }
    }

    @Test
    fun zustandsuebergaenge_werden_von_der_datenbank_erzwungen_und_versuche_sind_lueckenlos() {
        val w = seededWorld(); val n = f.enqueue(w); runTenant(w)
        assertEquals("DELIVERED", f.stateOf(n))
        assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) {
            f.tx.run(w.tenant) { j -> j.update("UPDATE taktovia.notification SET state = 'QUEUED', delivered_at = NULL WHERE id = ?", n) }
        }
        assertEquals(f.attemptsOf(n).size, f.strings("SELECT attempt_count::text FROM taktovia.notification WHERE id = ?", n).single().toInt(), "attempt_count entspricht den dokumentierten Versuchen")
    }
}
