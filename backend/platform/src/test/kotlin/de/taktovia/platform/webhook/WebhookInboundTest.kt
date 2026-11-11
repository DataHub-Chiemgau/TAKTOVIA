package de.taktovia.platform.webhook

import de.taktovia.platform.TestDatabase
import de.taktovia.platform.outbox.OutboxTestSupport
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

/** Eingangsfilter für signierte Ereignisse (WP-M1-16, Kap. 05 §5.6) gegen die echte Datenbank: Signatur, Zeitfenster, Größe, Rotation, Dedupe, Konflikt, Quarantäne. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookInboundTest {
    private lateinit var s: WebhookTestSupport
    private val clock = OutboxTestSupport.TestClock()
    private val effects = AtomicInteger()
    private val secretA = "inbound-secret-a-0123456789abcdef".toByteArray(); private val secretB = "inbound-secret-b-0123456789abcdef".toByteArray()

    @BeforeAll fun up() { TestDatabase.require(); s = WebhookTestSupport() }
    @AfterAll fun down() = s.close()

    private fun receiver(t: TenantId, keys: List<InboundKey>, handler: InboundHandler = InboundHandler { _, _, _, _ -> effects.incrementAndGet() }, config: InboundConfig = InboundConfig()) =
        InboundWebhookReceiver(s.tx, StaticInboundKeys(keys), s.base.security, handler, config, clock)

    private fun body(id: UUID = UUID.randomUUID(), data: String = """{"n":1}""", type: String = "partner.thing.changed") =
        """{"event_id":"$id","event_type":"$type","occurred_at":"2026-01-02T03:04:05Z","data":$data}""".toByteArray()
    private fun sign(secret: ByteArray, kid: String, body: ByteArray, skew: Long = 0) = WebhookSignature.header(secret, kid, clock.instant().plusSeconds(skew), body)
    private fun inboxCount(t: TenantId) = s.base.read(t) { it.queryForObject("SELECT count(*) FROM taktovia.inbox WHERE consumer LIKE 'webhook.%'", Int::class.java) }

    @Test fun hundert_identische_eingaenge_ein_inbox_eintrag_eine_wirkung() {
        val t = s.base.newTenant(); val r = receiver(t, listOf(InboundKey(t, "partner-x", "k1", secretA))); val b = body(); val sig = sign(secretA, "k1", b)
        effects.set(0)
        val pool = Executors.newFixedThreadPool(100); val start = CountDownLatch(1)
        val results = (1..100).map { pool.submit<InboundResult> { start.await(); r.receive("partner-x", sig, b) } }
        start.countDown(); val done = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, done.count { it is InboundResult.Accepted }, "eine Annahme (202)")
        assertEquals(99, done.count { it is InboundResult.AlreadyReceived }, "99 Antworten ALREADY_RECEIVED (200)")
        assertEquals(1, effects.get(), "genau eine Wirkung"); assertEquals(1, inboxCount(t))
    }

    @Test fun falsche_signatur_zeitabweichung_und_zu_grosser_body_ohne_inbox_eintrag() {
        val t = s.base.newTenant(); val key = InboundKey(t, "partner-x", "k1", secretA); val r = receiver(t, listOf(key), config = InboundConfig(maxBodyBytes = 2000)); val b = body()
        effects.set(0)
        fun rejected(reason: Rejection, header: String?, payload: ByteArray = b, source: String = "partner-x") = assertEquals(InboundResult.Rejected(reason), r.receive(source, header, payload), "$reason")
        rejected(Rejection.SIGNATURE_INVALID, sign(secretB, "k1", b))                                // falscher Schlüssel
        val good = sign(secretA, "k1", b); rejected(Rejection.SIGNATURE_INVALID, good.dropLast(1) + (if (good.last() == '0') '1' else '0'))   // verändert
        rejected(Rejection.SIGNATURE_INVALID, sign(secretA, "k1", b), body(data = """{"n":2}"""))   // Body nach dem Signieren verändert
        rejected(Rejection.SIGNATURE_INVALID, sign(secretA, "k1", b), String(b).replace(",", ", ").toByteArray())  // exakter Rohbody: Umformatierung ändert die Signatur
        rejected(Rejection.SIGNATURE_INVALID, sign(secretA, "unbekannt", b))                          // unbekannter Schlüssel
        rejected(Rejection.SIGNATURE_INVALID, sign(secretA, "k1", b), source = "anderer-absender")    // Schlüssel gehört zu anderer Quelle
        rejected(Rejection.SIGNATURE_INVALID, null); rejected(Rejection.SIGNATURE_INVALID, "v1=abcdef"); rejected(Rejection.SIGNATURE_INVALID, "")
        rejected(Rejection.TIMESTAMP_OUT_OF_WINDOW, sign(secretA, "k1", b, -301)); rejected(Rejection.TIMESTAMP_OUT_OF_WINDOW, sign(secretA, "k1", b, 301))
        val big = body(data = """{"x":"${"a".repeat(2500)}"}""")
        rejected(Rejection.TOO_LARGE, sign(secretA, "k1", big), big)
        assertEquals(0, inboxCount(t), "kein Inbox-Eintrag bei Abweisung"); assertEquals(0, effects.get())
        // Grenzen des Zeitfensters werden angenommen
        assertTrue(r.receive("partner-x", sign(secretA, "k1", b, -300), b) is InboundResult.Accepted)
        val b2 = body(); assertTrue(r.receive("partner-x", sign(secretA, "k1", b2, 300), b2) is InboundResult.Accepted)
        // formal ungültiger Inhalt trotz gültiger Signatur
        for (bad in listOf("""{"event_id":"kein-uuid","event_type":"a.b","occurred_at":"2026-01-02T03:04:05Z"}""", """{"event_type":"a.b"}""", "[]", "kein json", """{"event_id":"${UUID.randomUUID()}","event_type":"GROSS","occurred_at":"2026-01-02T03:04:05Z"}""")) {
            assertEquals(InboundResult.Rejected(Rejection.MALFORMED), r.receive("partner-x", sign(secretA, "k1", bad.toByteArray()), bad.toByteArray()), bad)
        }
        assertEquals(2, inboxCount(t))
    }

    @Test fun gleiche_ereignis_id_mit_anderem_payload_409_sicherheitsereignis_und_quarantaene() {
        val t = s.base.newTenant(); val r = receiver(t, listOf(InboundKey(t, "partner-x", "k1", secretA))); val id = UUID.randomUUID(); effects.set(0)
        val first = body(id); val changed = body(id, data = """{"n":99}""")
        assertTrue(r.receive("partner-x", sign(secretA, "k1", first), first) is InboundResult.Accepted)
        assertEquals(InboundResult.Rejected(Rejection.PAYLOAD_CONFLICT), r.receive("partner-x", sign(secretA, "k1", changed), changed))
        assertEquals(1, effects.get(), "die veränderte Fassung wirkt nicht")
        assertEquals(1, s.base.read(t) { it.queryForObject("SELECT count(*) FROM taktovia.webhook_quarantine WHERE event_id = ? AND reason = 'payload_hash_mismatch' AND payload_sha256 <> original_sha256", Int::class.java, id) })
        assertEquals(1, s.base.read(t) { it.queryForObject("SELECT count(*) FROM taktovia.audit_event WHERE action = 'security.event.payload_hash_mismatch' AND object_ref = ?", Int::class.java, "event:$id") })
        assertEquals(1, inboxCount(t), "die Inbox behält nur die erste Fassung")
        assertTrue(r.receive("partner-x", sign(secretA, "k1", first), first) is InboundResult.AlreadyReceived, "die Originalfassung bleibt ALREADY_RECEIVED")
    }

    @Test fun schluesselrotation_alter_und_neuer_schluessel_kurzzeitig_gueltig_auch_parallel() {
        val t = s.base.newTenant(); effects.set(0)
        val old = InboundKey(t, "partner-x", "k1", secretA, validUntil = clock.instant().plus(Duration.ofMinutes(10)))
        val new = InboundKey(t, "partner-x", "k2", secretB); val r = receiver(t, listOf(old, new))
        val pool = Executors.newFixedThreadPool(16); val start = CountDownLatch(1)
        val results = (1..40).map { i -> pool.submit<InboundResult> { val b = body(); start.await(); if (i % 2 == 0) r.receive("partner-x", sign(secretA, "k1", b), b) else r.receive("partner-x", sign(secretB, "k2", b), b) } }
        start.countDown(); assertTrue(results.map { it.get(60, TimeUnit.SECONDS) }.all { it is InboundResult.Accepted }, "beide Schlüssel gelten während der Rotation")
        assertEquals(40, inboxCount(t))
        clock.advance(Duration.ofMinutes(11))
        val b = body(); assertEquals(InboundResult.Rejected(Rejection.SIGNATURE_INVALID), r.receive("partner-x", sign(secretA, "k1", b), b), "alter Schlüssel nach Ablauf abgewiesen")
        assertTrue(r.receive("partner-x", sign(secretB, "k2", b), b) is InboundResult.Accepted)
        clock.advance(Duration.ofMinutes(-11))
    }

    @Test fun eingang_ist_an_den_mandanten_des_schluessels_gebunden() {
        val a = s.base.newTenant(); val b = s.base.newTenant(); val id = UUID.randomUUID(); val payload = body(id); effects.set(0)
        val ra = receiver(a, listOf(InboundKey(a, "partner-x", "ka", secretA))); val rb = receiver(b, listOf(InboundKey(b, "partner-x", "kb", secretB)))
        assertTrue(ra.receive("partner-x", sign(secretA, "ka", payload), payload) is InboundResult.Accepted)
        assertTrue(rb.receive("partner-x", sign(secretB, "kb", payload), payload) is InboundResult.Accepted, "gleiche Ereignis-ID in anderem Mandanten ist ein eigener Eingang")
        assertEquals(1, inboxCount(a)); assertEquals(1, inboxCount(b)); assertEquals(2, effects.get())
        assertEquals(InboundResult.Rejected(Rejection.SIGNATURE_INVALID), ra.receive("partner-x", sign(secretB, "kb", payload), payload), "Schlüssel des anderen Mandanten ist hier unbekannt")
    }

    @Test fun fehlschlagende_wirkung_rollt_den_inbox_eintrag_zurueck_und_die_wiederholung_gelingt() {
        val t = s.base.newTenant(); val fail = java.util.concurrent.atomic.AtomicBoolean(true); effects.set(0)
        val r = receiver(t, listOf(InboundKey(t, "partner-x", "k1", secretA)), InboundHandler { _, _, _, _ -> if (fail.get()) error("Fachfehler") else effects.incrementAndGet() })
        val b = body(); val sig = sign(secretA, "k1", b)
        org.junit.jupiter.api.assertThrows<IllegalStateException> { r.receive("partner-x", sig, b) }
        assertEquals(0, inboxCount(t), "kein Eintrag ohne Wirkung: der Absender wiederholt")
        fail.set(false); assertTrue(r.receive("partner-x", sig, b) is InboundResult.Accepted); assertEquals(1, effects.get())
    }

    @Test fun signatur_ist_konstantzeitlich_und_format_streng() {
        val expected = WebhookSignature.hmacHex(secretA, clock.instant(), body())
        assertTrue(WebhookSignature.matches(expected, expected)); assertFalse(WebhookSignature.matches(expected, expected.dropLast(1))); assertFalse(WebhookSignature.matches(expected, ""))
        assertEquals(null, WebhookSignature.parse("t=1,kid=a,v1=${"G".repeat(64)}")); assertEquals(null, WebhookSignature.parse("kid=a,t=1,v1=${"a".repeat(64)}"))
        assertEquals("k1", WebhookSignature.parse("t=1700000000,kid=k1,v1=${"a".repeat(64)}")!!.keyId)
        assertEquals(12, WebhookSignature.keyId(secretA).length); assertFalse(String(secretA).contains(WebhookSignature.keyId(secretA)))
    }
}
