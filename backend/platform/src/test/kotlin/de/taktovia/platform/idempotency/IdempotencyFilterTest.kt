package de.taktovia.platform.idempotency

import de.taktovia.platform.TestDatabase
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kern des Idempotenzfilters (WP-M1-13, LH-6.17-INT-004) gegen die echte Datenbank unter der Runtime-Rolle: Scope, Fingerprint, Wiederholung,
 * Konflikt, Erstverarbeitung in Arbeit, echte parallele Anfragen. Die Servlet-Anbindung und die Filterkette prüfen die Tests unter
 * `backend/app` (`IdempotencyApiTest`, `PublishConfigIdempotencyTest`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdempotencyFilterTest {
    private lateinit var s: IdempotencyTestSupport
    private val clock = IdempotencyTestSupport.TestClock()
    private fun service(wait: Duration = Duration.ofSeconds(20)) = IdempotencyService(s.registry, clock, IdempotencyConfig(inProgressWait = wait))

    private val json = tools.jackson.databind.json.JsonMapper.builder().build()
    /** Das Register speichert jsonb: Darstellung (Leerzeichen) kann abweichen, der Inhalt nicht. */
    private fun tree(body: String?) = json.readTree(body)

    @BeforeAll fun up() { TestDatabase.require(); s = IdempotencyTestSupport() }
    @AfterAll fun down() = s.close()

    @Test fun wiederholung_nach_timeout_liefert_gespeichertes_ergebnis() {
        val t = s.tenant(); val n = AtomicInteger(); val req = s.request(t)
        val first = service().execute(req, s.command(t, counter = n)) as IdempotencyResult.Executed
        // Der Client hat die Antwort nie gesehen (Timeout bzw. Abbruch zwischen Commit und Antwort) und wiederholt:
        val retry = service().execute(req, s.command(t, counter = n)) as IdempotencyResult.Replayed
        assertEquals(1, n.get(), "keine zweite Ausführung")
        assertEquals(tree(first.response.body), tree(retry.response.body), "dieselbe Ressourcen-ID")
        assertEquals(201, retry.response.status)
        assertEquals(first.response.headers["Location"], retry.response.headers["Location"])
        assertEquals(1, s.outboxCount(t))
        // neuer Schlüssel für neuen Vorgang wird neu ausgeführt
        assertTrue(service().execute(s.request(t), s.command(t, counter = n)) is IdempotencyResult.Executed)
        assertEquals(2, n.get())
    }

    @Test fun hundert_parallele_identische_requests_eine_wirkung() {
        val t = s.tenant(); val n = AtomicInteger(); val req = s.request(t); val aggregate = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(100); val start = CountDownLatch(1)
        val futures = (1..100).map { pool.submit<IdempotencyResult> { start.await(); service().execute(req, s.command(t, aggregate, n) { Thread.sleep(150) }) } }
        start.countDown()
        val results = futures.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, n.get(), "genau eine Ausführung")
        assertEquals(1, s.outboxCount(t), "genau ein Outbox-Eintrag")
        assertEquals(1, results.count { it is IdempotencyResult.Executed })
        assertEquals(99, results.count { it is IdempotencyResult.Replayed })
        val bodies = results.map { tree((it as? IdempotencyResult.Executed)?.response?.body ?: (it as IdempotencyResult.Replayed).response.body) }.toSet()
        assertEquals(setOf(tree("""{"id":"$aggregate"}""")), bodies, "alle Antworten tragen dieselbe Ressourcen-ID")
        assertEquals(1, s.records(t))
    }

    @Test fun gleicher_schluessel_anderer_fingerprint_409() {
        val t = s.tenant(); val n = AtomicInteger(); val key = "key-" + UUID.randomUUID()
        service().execute(s.request(t, key = key, fingerprint = "1".repeat(64)), s.command(t, counter = n))
        val other = service().execute(s.request(t, key = key, fingerprint = "2".repeat(64)), s.command(t, counter = n))
        assertEquals(IdempotencyResult.Conflict, other, "Konflikt statt Wiederverwendung des fremden Ergebnisses")
        assertEquals(1, n.get(), "keine zweite Ausführung")
        assertEquals(1, s.outboxCount(t))
        // der erste Fingerprint bekommt weiterhin sein Ergebnis
        assertTrue(service().execute(s.request(t, key = key, fingerprint = "1".repeat(64)), s.command(t, counter = n)) is IdempotencyResult.Replayed)
    }

    @Test fun retransmit_waehrend_erstverarbeitung_operation_in_progress() {
        val t = s.tenant(); val n = AtomicInteger(); val req = s.request(t)
        val running = CountDownLatch(1); val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val first = pool.submit<IdempotencyResult> { service().execute(req, s.command(t, counter = n) { running.countDown(); release.await(20, TimeUnit.SECONDS) }) }
        assertTrue(running.await(10, TimeUnit.SECONDS))
        val during = service(wait = Duration.ofMillis(150)).execute(req, s.command(t, counter = n))
        assertTrue(during is IdempotencyResult.InProgress, "laufende Erstverarbeitung: OPERATION_IN_PROGRESS")
        assertTrue((during as IdempotencyResult.InProgress).retryAfterSeconds >= 1)
        release.countDown()
        assertTrue(first.get(20, TimeUnit.SECONDS) is IdempotencyResult.Executed)
        pool.shutdown()
        assertTrue(service().execute(req, s.command(t, counter = n)) is IdempotencyResult.Replayed, "danach dasselbe Ergebnis")
        assertEquals(1, n.get())
    }

    @Test fun abgebrochene_erstverarbeitung_wird_nach_leaseablauf_uebernommen() {
        val t = s.tenant(); val n = AtomicInteger(); val req = s.request(t)
        // Prozess starb nach dem Anlegen des Eintrags: IN_PROGRESS bleibt zurück
        assertTrue(s.registry.begin(req, clock.instant(), Duration.ofSeconds(120), Duration.ofHours(72)) is Begin.Execute)
        assertTrue(service(wait = Duration.ZERO).execute(req, s.command(t, counter = n)) is IdempotencyResult.InProgress, "Lease läuft noch")
        clock.advance(Duration.ofSeconds(121))
        assertTrue(service().execute(req, s.command(t, counter = n)) is IdempotencyResult.Executed, "Lease abgelaufen: Übernahme")
        assertEquals(1, n.get())
        clock.advance(Duration.ofSeconds(-121))
    }

    @Test fun schluessel_isoliert_nach_mandant_akteur_operation() {
        val a = s.tenant(); val b = s.tenant(); val n = AtomicInteger(); val key = "key-" + UUID.randomUUID()
        val variants = listOf(
            s.request(a, "iss|u1", "confirmThing", key), s.request(b, "iss|u1", "confirmThing", key),   // anderer Mandant
            s.request(a, "iss|u2", "confirmThing", key),                                                // anderer Akteur
            s.request(a, "iss|u1", "cancelThing", key),                                                 // anderer Operationstyp
        )
        val bodies = variants.map { r -> tree((service().execute(r, s.command(r.tenant, counter = n)) as IdempotencyResult.Executed).response.body) }
        assertEquals(4, n.get(), "vier unabhängige Ausführungen mit demselben Schlüssel")
        assertEquals(4, bodies.toSet().size)
        variants.forEachIndexed { i, r ->
            val replay = service().execute(r, s.command(r.tenant, counter = n)) as IdempotencyResult.Replayed
            assertEquals(bodies[i], tree(replay.response.body), "jeder Scope bekommt nur sein eigenes Ergebnis")
        }
        assertEquals(4, n.get())
        assertEquals(3, s.records(a)); assertEquals(1, s.records(b))
    }

    @Test fun gleicher_schluessel_zweier_mandanten_parallel_bleibt_getrennt() {
        val a = s.tenant(); val b = s.tenant(); val n = AtomicInteger(); val key = "key-" + UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(20); val start = CountDownLatch(1)
        val futures = (1..20).map { i -> val tenant = if (i % 2 == 0) a else b
            pool.submit<IdempotencyResult> { start.await(); service().execute(s.request(tenant, key = key), s.command(tenant, counter = n) { Thread.sleep(50) }) } }
        start.countDown(); futures.forEach { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(2, n.get(), "je Mandant genau eine Ausführung")
        assertEquals(1, s.outboxCount(a)); assertEquals(1, s.outboxCount(b))
    }

    @Test fun wiederholung_ohne_authentisierung_abgewiesen() {
        val t = s.tenant(); val n = AtomicInteger(); val key = "key-" + UUID.randomUUID()
        service().execute(s.request(t, key = key), s.command(t, counter = n))
        for (anonymous in listOf("", "  ")) {
            assertThrows(IllegalArgumentException::class.java) { service().execute(s.request(t, principal = anonymous, key = key), s.command(t, counter = n)) }
        }
        assertEquals(1, n.get(), "ohne Akteur gibt es weder Ausführung noch gespeichertes Ergebnis")
    }

    @Test fun schluesselformat_wird_geprueft() {
        val t = s.tenant()
        for (bad in listOf("zu-kurz", "x".repeat(129), "mit leerzeichen und mehr", "ungültig-äöü-ungültig")) {
            assertThrows(IllegalArgumentException::class.java, { service().execute(s.request(t, key = bad), { StoredResponse(201, emptyMap(), null) }) }, bad)
        }
    }

    @Test fun fehlerantwort_wird_nicht_gespeichert_und_gibt_den_schluessel_frei() {
        val t = s.tenant(); val req = s.request(t); val n = AtomicInteger()
        val failed = service().execute(req) { n.incrementAndGet(); StoredResponse(503, emptyMap(), """{"code":"SERVICE_UNAVAILABLE"}""") }
        assertTrue(failed is IdempotencyResult.Executed && !failed.stored, "nicht gespeichert")
        val ok = service().execute(req, s.command(t, counter = n))
        assertTrue(ok is IdempotencyResult.Executed && ok.stored, "Wiederholung nach Fehler wird neu ausgeführt")
        assertThrows(IllegalStateException::class.java) { service().execute(s.request(t), { n.incrementAndGet(); error("Absturz im Befehl") }) }
        assertEquals(1, s.records(t), "nach Ausnahme bleibt kein hängender Eintrag")
    }

    @Test fun ablauf_des_antwortcaches_erlaubt_keine_zweite_wirkung_bei_fachlichem_unique_schluessel() {
        val t = s.tenant(); val n = AtomicInteger(); val req = s.request(t); val aggregate = UUID.randomUUID()
        service().execute(req, s.command(t, aggregate, n))
        clock.advance(Duration.ofHours(73))
        // Antwortcache abgelaufen: der Befehl läuft erneut an, das fachliche Unique (Aggregat, Revision, Ereignistyp) verhindert die zweite Wirkung
        val again = service().execute(req) { n.incrementAndGet(); try { s.command(t, aggregate, AtomicInteger())() } catch (e: org.springframework.dao.DuplicateKeyException) { StoredResponse(409, emptyMap(), """{"code":"CONFLICT"}""") } }
        assertTrue(again is IdempotencyResult.Executed)
        assertEquals(409, (again as IdempotencyResult.Executed).response.status)
        assertEquals(2, n.get()); assertEquals(1, s.outboxCount(t), "genau eine Wirkung")
        clock.advance(Duration.ofHours(-73))
    }

    @Test fun fingerprint_ist_kanonisch_und_haengt_von_pfad_body_und_if_match_ab() {
        val base = RequestFingerprint.of("POST", "confirmThing", "/v1/things/1", "", mapOf("If-Match" to "\"3\""), """{"b":1,"a":{"y":[1,2],"x":"t"}}""".toByteArray())
        assertEquals(base, RequestFingerprint.of("POST", "confirmThing", "/v1/things/1", "", mapOf("if-match" to "\"3\""), """{ "a" : {"x":"t","y":[1,2]}, "b": 1.0 }""".toByteArray()), "Formatierung, Schlüsselreihenfolge, Zahlenschreibweise und Header-Groß-/Kleinschreibung ändern nichts")
        assertNotEquals(base, RequestFingerprint.of("POST", "confirmThing", "/v1/things/2", "", mapOf("If-Match" to "\"3\""), """{"b":1,"a":{"y":[1,2],"x":"t"}}""".toByteArray()), "anderer Pfad")
        assertNotEquals(base, RequestFingerprint.of("POST", "confirmThing", "/v1/things/1", "", mapOf("If-Match" to "\"4\""), """{"b":1,"a":{"y":[1,2],"x":"t"}}""".toByteArray()), "andere Revision")
        assertNotEquals(base, RequestFingerprint.of("POST", "confirmThing", "/v1/things/1", "", mapOf("If-Match" to "\"3\""), """{"b":2,"a":{"y":[1,2],"x":"t"}}""".toByteArray()), "anderer Body")
        assertNotEquals(base, RequestFingerprint.of("POST", "confirmThing", "/v1/things/1", "", mapOf("If-Match" to "\"3\""), """{"b":1,"a":{"y":[2,1],"x":"t"}}""".toByteArray()), "Arrayreihenfolge zählt")
        assertNotEquals(base, RequestFingerprint.of("POST", "otherThing", "/v1/things/1", "", mapOf("If-Match" to "\"3\""), """{"b":1,"a":{"y":[1,2],"x":"t"}}""".toByteArray()), "andere Operation")
        assertEquals(RequestFingerprint.of("POST", "o", "/p", "b=2&a=1", emptyMap(), null), RequestFingerprint.of("POST", "o", "/p", "a=1&b=2", emptyMap(), null), "Queryreihenfolge")
        assertFalse(base.isBlank()); assertEquals(64, base.length)
    }
}
