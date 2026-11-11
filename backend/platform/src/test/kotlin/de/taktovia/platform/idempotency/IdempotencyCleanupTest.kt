package de.taktovia.platform.idempotency

import de.taktovia.platform.TestDatabase
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration

/** Bereinigung nach `expires_at` (WP-M1-13 AK 5): abgelaufene Ergebnisse gehen, laufende Befehle behalten ihren Eintrag. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdempotencyCleanupTest {
    private lateinit var s: IdempotencyTestSupport
    private val clock = IdempotencyTestSupport.TestClock()

    @BeforeAll fun up() { TestDatabase.require(); s = IdempotencyTestSupport() }
    @AfterAll fun down() = s.close()

    private fun keys(t: de.taktovia.platform.tenant.TenantId) =
        s.admin.queryForList("SELECT key FROM taktovia.idempotency_record WHERE tenant_id = ? ORDER BY key", String::class.java, t.value).toSet()

    @Test fun bereinigung_entfernt_keinen_laufenden_eintrag() {
        val t = s.tenant(); val ttl = Duration.ofHours(72); val lease = Duration.ofMinutes(2)
        val svc = IdempotencyService(s.registry, clock, IdempotencyConfig(responseTtl = ttl, lease = lease))
        // 1: abgeschlossen, wird abgelaufen; 2: abgeschlossen, bleibt gültig; 3: läuft noch (Lease gültig) trotz abgelaufenem expires_at; 4: abgebrochen (Lease abgelaufen)
        val done = s.request(t, key = "key-done-expired-0001"); val fresh = s.request(t, key = "key-done-valid-000001")
        val running = s.request(t, key = "key-running-live-00001"); val abandoned = s.request(t, key = "key-abandoned-dead-001")
        svc.execute(done) { StoredResponse(201, emptyMap(), """{"x":1}""") }
        assertTrue(s.registry.begin(abandoned, clock.instant(), lease, ttl) is Begin.Execute)
        clock.advance(Duration.ofHours(71))
        svc.execute(fresh) { StoredResponse(201, emptyMap(), """{"x":2}""") }
        // laufender Befehl, dessen Antwortcache-Frist schon überschritten ist (Betriebsfall: Befehl dauert länger als geplant)
        assertTrue(s.registry.begin(running, clock.instant(), Duration.ofHours(3), Duration.ofHours(1)) is Begin.Execute)
        clock.advance(Duration.ofHours(2))    // done: abgelaufen; fresh: noch 70 h; running: expires_at überschritten, Lease (3 h) noch gültig; abandoned: Lease lange abgelaufen
        assertEquals(setOf(done.key, fresh.key, running.key, abandoned.key), keys(t))
        val removed = s.registry.purgeExpired(t, clock.instant(), limit = 100)
        assertEquals(2, removed, "abgelaufenes Ergebnis und abgebrochene Verarbeitung")
        assertEquals(setOf(fresh.key, running.key), keys(t), "laufender Befehl und gültiges Ergebnis bleiben")
        // der laufende Befehl kann seine Antwort noch speichern
        assertTrue(s.registry.complete(running, clock.instant(), ttl, StoredResponse(201, emptyMap(), """{"ok":true}""")))
    }

    @Test fun bereinigung_ist_begrenzt_und_mandantengetrennt() {
        val a = s.tenant(); val b = s.tenant(); val svc = IdempotencyService(s.registry, clock)
        repeat(5) { i -> svc.execute(s.request(a, key = "key-a-%016d".format(i))) { StoredResponse(200, emptyMap(), null) } }
        svc.execute(s.request(b, key = "key-b-0000000000000001")) { StoredResponse(200, emptyMap(), null) }
        clock.advance(Duration.ofHours(100))
        assertEquals(3, s.registry.purgeExpired(a, clock.instant(), limit = 3), "höchstens limit je Lauf")
        assertEquals(2, s.registry.purgeExpired(a, clock.instant(), limit = 3))
        assertEquals(1, keys(b).size, "Mandant B wird von der Bereinigung für A nicht berührt")
        val cleanup = IdempotencyCleanup(s.registry, { listOf(a, b) }, clock)
        assertEquals(1, cleanup.runOnce())
        assertEquals(0, keys(b).size)
        clock.advance(Duration.ofHours(-100))
    }
}
