package de.taktovia.farepayment

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import com.zaxxer.hikari.HikariDataSource
import de.taktovia.farepayment.api.ProviderEventConfig
import de.taktovia.farepayment.api.ProviderEventKey
import de.taktovia.farepayment.api.ProviderEventKeys
import de.taktovia.farepayment.api.ProviderEventKind
import de.taktovia.farepayment.api.ProviderEventRejection
import de.taktovia.farepayment.api.ProviderEventResult
import de.taktovia.farepayment.api.ProviderEvents
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import de.taktovia.platform.webhook.WebhookSignature
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Eingang signierter PSP-Ereignisse (WP-M2-29) gegen die echte Datenbank unter der Runtime-Rolle: Signatur und Zeitfenster, Deduplikation (100 identische Zustellungen parallel),
 * gleiche Ereignis-ID mit anderem Payload, manipulierte Mandantenangabe, Abbildung auf interne Ereignisse.
 */
class ProviderEventsTest {
    companion object {
        private lateinit var runtime: HikariDataSource
        private lateinit var adminDs: HikariDataSource
        private lateinit var admin: JdbcTemplate
        private lateinit var tx: TenantTransactions
        private val mapper = JsonMapper.builder().build()
        private val secretA = "psp-test-secret-a-0123456789abcdef".toByteArray()
        private val secretOld = "psp-test-secret-old-0123456789abc".toByteArray()
        private val secretB = "psp-test-secret-b-0123456789abcdef".toByteArray()

        @BeforeAll @JvmStatic fun up() { PaymentTestDb.require(); runtime = PaymentTestDb.runtime(); adminDs = PaymentTestDb.admin(); admin = JdbcTemplate(adminDs); tx = TenantTransactions(runtime) }
        @AfterAll @JvmStatic fun down() { if (::runtime.isInitialized) runtime.close(); if (::adminDs.isInitialized) adminDs.close() }
    }

    private class MutableClock : Clock() {
        @Volatile var now: Instant = Instant.now()
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    /** Schlüssel: Provider `simpsp` gehört dem Mandanten [tenant] (neu und alt zur Rotation); `ctlpsp` gehört einem zweiten Mandanten. */
    private inner class Env {
        val tenant = PaymentTestDb.newTenant(admin)
        val other = PaymentTestDb.newTenant(admin)
        val provider = "psp" + UUID.randomUUID().toString().replace("-", "").take(8)
        val keys = ProviderEventKeys { p ->
            if (p == provider) listOf(ProviderEventKey(tenant, provider, secretA), ProviderEventKey(tenant, provider, secretOld, validUntil = Instant.now().plusSeconds(3600)),
                ProviderEventKey(other, provider, secretB)) else emptyList()
        }
        val clock = MutableClock()
        val events = ProviderEvents.create(tx, keys, SecurityEventPort(JdbcAuditPort()), Outbox(), ProviderEventConfig(maxBodyBytes = 4096), clock)

        fun body(id: String = "evt_" + UUID.randomUUID().toString().replace("-", "").take(16), type: String = "payment.authorized", state: String = "AUTHORIZED", extra: String = "", data: String = "") =
            """{"event_id":"$id","sequence":1,"type":"$type","created_at":"2026-11-01T10:00:00Z"$extra,"data":{"provider_ref":"pi_$id","state":"$state","authorized_minor":1250,"captured_minor":0,"refunded_minor":0,"currency":"EUR"$data}}"""

        fun sign(body: String, secret: ByteArray = secretA, skew: Long = 0): Pair<String, String> {
            val ts = clock.now.plusSeconds(skew)
            return WebhookSignature.hmacHex(secret, ts, body.toByteArray()).let { "t=${ts.epochSecond},v1=$it" to ts.epochSecond.toString() }
        }

        fun send(body: String, secret: ByteArray = secretA, skew: Long = 0, provider: String = this.provider): ProviderEventResult {
            val (sig, ts) = sign(body, secret, skew)
            return events.receive(provider, sig, ts, body.toByteArray())
        }

        fun count(t: TenantId, where: String = "true") = admin.queryForObject("SELECT count(*) FROM taktovia.payment_event WHERE tenant_id = ? AND provider = ? AND $where", Int::class.java, t.value, provider)!!
        fun outbox(t: TenantId) = admin.queryForObject("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'payment.provider_event_received' AND payload->>'provider' = ?", Int::class.java, t.value, provider)!!
        fun security(t: TenantId) = admin.queryForObject("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'security.event.payload_hash_mismatch' AND object_ref = ?", Int::class.java, t.value,
            "event:" + de.taktovia.farepayment.api.providerEventObjectId(provider, lastId))
        var lastId = ""
    }

    @Test
    fun hundert_identische_ereignisse_parallel_werden_einmal_fachlich_wirksam() {
        val e = Env(); val body = e.body(); val sig = e.sign(body)
        val pool = Executors.newFixedThreadPool(100); val start = CountDownLatch(1)
        val futures = (1..100).map { pool.submit<ProviderEventResult> { start.await(); e.events.receive(e.provider, sig.first, sig.second, body.toByteArray()) } }
        start.countDown(); val rs = futures.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, rs.count { it is ProviderEventResult.Accepted }); assertEquals(99, rs.count { it is ProviderEventResult.AlreadyReceived })
        assertEquals(1, e.count(e.tenant), "genau eine Zeile payment_event"); assertEquals(1, e.outbox(e.tenant), "genau eine fachliche Wirkung (internes Ereignis)")
    }

    @Test
    fun gleiche_ereignis_id_mit_anderem_payload_wird_abgewiesen_und_als_sicherheitsereignis_gemeldet() {
        val e = Env(); val id = "evt_conflict01"; e.lastId = id
        assertTrue(e.send(e.body(id)) is ProviderEventResult.Accepted)
        val changed = e.body(id).replace("1250", "99999")
        val r = e.send(changed)
        assertEquals(ProviderEventResult.Rejected(ProviderEventRejection.PAYLOAD_CONFLICT), r)
        assertEquals(1, e.security(e.tenant), "Sicherheitsereignis im Mandanten"); assertEquals(1, e.count(e.tenant)); assertEquals(1, e.outbox(e.tenant))
        assertEquals(1250L, admin.queryForObject("SELECT authorized_minor FROM taktovia.payment_event WHERE tenant_id = ? AND event_id = ?", Long::class.java, e.tenant.value, id), "der erste Payload bleibt maßgeblich")
        assertTrue(e.send(e.body(id)) is ProviderEventResult.AlreadyReceived, "das Original bleibt ein Duplikat")
    }

    @Test
    fun ungueltige_signatur_und_zeitfenster_werden_ohne_verarbeitung_abgewiesen() {
        val e = Env(); val b = e.body()
        fun rejected(r: ProviderEventResult, reason: ProviderEventRejection, msg: String) = assertEquals(ProviderEventResult.Rejected(reason), r, msg)
        rejected(e.send(b, "falscher-schluessel-0123456789abcd".toByteArray()), ProviderEventRejection.SIGNATURE_INVALID, "falscher Schlüssel")
        rejected(e.events.receive(e.provider, null, null, b.toByteArray()), ProviderEventRejection.SIGNATURE_INVALID, "ohne Signatur")
        rejected(e.events.receive(e.provider, "v1=abc", "1", b.toByteArray()), ProviderEventRejection.SIGNATURE_INVALID, "Format")
        rejected(e.send(b, skew = -301), ProviderEventRejection.TIMESTAMP_OUT_OF_WINDOW, "Replay außerhalb des Zeitfensters (zu alt)")
        rejected(e.send(b, skew = 301), ProviderEventRejection.TIMESTAMP_OUT_OF_WINDOW, "zu neu")
        val (sig, ts) = e.sign(b)
        rejected(e.events.receive(e.provider, sig, ts, b.replace("1250", "1").toByteArray()), ProviderEventRejection.SIGNATURE_INVALID, "Body nach dem Signieren verändert")
        rejected(e.events.receive(e.provider, sig, (ts.toLong() + 1).toString(), b.toByteArray()), ProviderEventRejection.SIGNATURE_INVALID, "Zeitstempel-Header weicht von der Signatur ab")
        rejected(e.send(b, provider = "anderer-provider"), ProviderEventRejection.SIGNATURE_INVALID, "Schlüssel gehört zu anderem Provider")
        rejected(e.send(e.body(extra = """,${"\"x\":\"" + "a".repeat(5000)}"""")), ProviderEventRejection.TOO_LARGE, "zu groß")
        assertEquals(0, e.count(e.tenant)); assertEquals(0, e.count(e.other)); assertEquals(0, e.outbox(e.tenant))
        // Gegenprobe: dieselbe Nachricht mit gültiger Signatur im Fenster (±300 s) wird angenommen
        assertTrue(e.send(b, skew = -299) is ProviderEventResult.Accepted)
    }

    @Test
    fun mandant_folgt_nur_aus_dem_schluessel_manipulierte_tenant_id_ist_wirkungslos() {
        val e = Env()
        // tenant_id auf oberster Ebene: formal ungültig
        assertEquals(ProviderEventResult.Rejected(ProviderEventRejection.MALFORMED), e.send(e.body(extra = ""","tenant_id":"${e.other}"""")))
        // tenant_id in den Nutzdaten: ohne Wirkung, die Zeile liegt im Mandanten des Schlüssels
        val b = e.body(data = ""","tenant_id":"${e.other}"""")
        assertTrue(e.send(b) is ProviderEventResult.Accepted)
        assertEquals(1, e.count(e.tenant)); assertEquals(0, e.count(e.other)); assertEquals(1, e.outbox(e.tenant)); assertEquals(0, e.outbox(e.other))
        // der Schlüssel des zweiten Mandanten legt in dessen Mandanten ab, nicht in den ersten
        assertTrue(e.send(e.body(), secretB) is ProviderEventResult.Accepted); assertEquals(1, e.count(e.other)); assertEquals(1, e.count(e.tenant))
        // Mandantentrennung unter der Runtime-Rolle: fremde Ereignisse sind unsichtbar
        assertEquals(1, tx.run(e.other) { j -> j.queryForObject("SELECT count(*) FROM taktovia.payment_event WHERE provider = ?", Int::class.java, e.provider)!! })
    }

    @Test
    fun schluesselrotation_alter_schluessel_gilt_nur_bis_valid_until() {
        val e = Env()
        assertTrue(e.send(e.body(), secretOld) is ProviderEventResult.Accepted, "alter Schlüssel gilt während der Rotation")
        e.clock.now = Instant.now().plus(Duration.ofHours(2))   // alter Schlüssel abgelaufen, neuer gilt weiter
        assertEquals(ProviderEventResult.Rejected(ProviderEventRejection.SIGNATURE_INVALID), e.send(e.body(), secretOld))
        assertTrue(e.send(e.body(), secretA) is ProviderEventResult.Accepted)
    }

    @Test
    fun ungueltiger_inhalt_wird_trotz_gueltiger_signatur_nicht_gespeichert() {
        val e = Env()
        val bad = listOf(
            e.body(state = "PAID"), e.body(type = "refund.created"), e.body().replace("1250", "12.5"), e.body().replace("1250", "\"1250\""), e.body().replace("1250", "-1"),
            e.body().replace("\"currency\":\"EUR\"", "\"currency\":\"eur\""), e.body(extra = ""","provider":"anderer""""), """{"event_id":"x","type":"payment.authorized","created_at":"kein-datum","data":{}}""", "[]", "kein json",
        )
        bad.forEach { assertEquals(ProviderEventResult.Rejected(ProviderEventRejection.MALFORMED), e.send(it), it) }
        assertEquals(0, e.count(e.tenant)); assertEquals(0, e.outbox(e.tenant))
    }

    @Test
    fun psp_ereignistypen_werden_auf_interne_ereignisse_abgebildet_und_nach_schema_geliefert() {
        val e = Env()
        val schema = SchemaRegistry.withDialect(Dialects.getDraft202012()).getSchema(Files.readString(PaymentTestDb.repoRoot().resolve("api/events/payment.provider_event_received.v1.schema.json")))
        val types = mapOf("payment.requires_action" to "REQUIRES_ACTION", "payment.authorized" to "AUTHORIZED", "payment.authorization_failed" to "AUTHORIZATION_FAILED",
            "payment.capture_pending" to "CAPTURE_PENDING", "payment.captured" to "CAPTURED", "payment.void_pending" to "VOID_PENDING", "payment.voided" to "VOIDED",
            "payment.refund_pending" to "REFUND_PENDING", "payment.partially_refunded" to "PARTIALLY_REFUNDED", "payment.refunded" to "REFUNDED",
            "payment.dispute.opened" to "DISPUTE_OPENED", "payment.dispute.won" to "DISPUTE_WON", "payment.dispute.lost" to "DISPUTE_LOST")
        assertEquals(types.values.toSet(), ProviderEventKind.entries.filter { it != ProviderEventKind.UNMAPPED }.map { it.name }.toSet(), "Abbildung vollständig")
        types.forEach { (t, kind) -> assertTrue(e.send(e.body(type = t, state = "CAPTURED", data = if (kind.startsWith("DISPUTE")) ""","dispute_state":"OPEN"""" else "")) is ProviderEventResult.Accepted, t) }
        assertEquals(13, e.outbox(e.tenant))
        val rows = admin.queryForList("SELECT event_type, internal_kind FROM taktovia.payment_event WHERE tenant_id = ? AND provider = ?", e.tenant.value, e.provider).associate { it["event_type"] as String to it["internal_kind"] as String }
        assertEquals(types, rows)
        admin.queryForList("SELECT payload::text FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'payment.provider_event_received' AND payload->>'provider' = ?", String::class.java, e.tenant.value, e.provider).forEach {
            assertEquals(emptyList<Any>(), schema.validate(mapper.readTree(it)).map { m -> m.message }, it)
        }
        // formal gültiger, aber unbekannter Typ: quittiert und gespeichert, ohne Wirkung
        assertTrue(e.send(e.body(type = "payment.something_new")) is ProviderEventResult.Accepted)
        assertEquals(1, e.count(e.tenant, "internal_kind = 'UNMAPPED'")); assertEquals(13, e.outbox(e.tenant))
    }
}
