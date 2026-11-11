package de.taktovia.platform.webhook

import com.sun.net.httpserver.HttpServer
import de.taktovia.platform.TestDatabase
import de.taktovia.platform.alert.AlertChannel
import de.taktovia.platform.alert.AlertNotification
import de.taktovia.platform.alert.OperatorAlertService
import de.taktovia.platform.net.SsrfGuard
import de.taktovia.platform.outbox.OutboxTestSupport
import de.taktovia.platform.secret.FileSecretStore
import de.taktovia.platform.secret.SecretResolver
import de.taktovia.platform.tenant.TenantId
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ausgehende Webhook-Zustellung (WP-M1-16) gegen die echte Datenbank und einen echten lokalen HTTP-Empfänger (Sockets, kein Mock). Der Empfänger prüft die
 * Signatur wie ein Partner. Weil TLS-Ziele mit öffentlicher Adresse in dieser Umgebung nicht erreichbar sind, nutzen die Tests einen `SsrfGuard` mit Testauflösung auf
 * Loopback (nur Klartext-HTTP und Loopback erlaubt); die SSRF-Sperren selbst prüfen die Fälle mit dem produktiven Guard und `SsrfGuardTest`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookDeliveryTest {
    private lateinit var s: WebhookTestSupport
    private lateinit var secretsDir: Path
    private lateinit var server: HttpServer
    private val clock = OutboxTestSupport.TestClock()
    private val received = ConcurrentLinkedQueue<Received>()
    @Volatile private var respond: (Int) -> Pair<Int, Map<String, String>> = { 200 to emptyMap() }
    private val requestNo = AtomicInteger()
    private val domain = "taktovia-test-secrets"

    class Received(val headers: Map<String, String>, val body: ByteArray, val path: String)

    private val resolver by lazy { SecretResolver(domain, FileSecretStore(secretsDir)) }
    private fun writeSecret(name: String, value: String) { Files.createDirectories(secretsDir.resolve(domain)); Files.writeString(secretsDir.resolve(domain).resolve(name), value) }
    /** Testguard: Klartext erlaubt, der Testname wird auf Loopback aufgelöst, Loopback gilt als „öffentlich“. Produktiver Guard: siehe die SSRF-Fälle. */
    private fun testGuard() = SsrfGuard(allowedPorts = setOf(server.address.port), allowHttp = false, resolver = { listOf(InetAddress.getLoopbackAddress()) }, addressCheck = { true })
    private fun url(path: String = "/hook") = "https://hook.example.test:${server.address.port}$path"
    private fun worker(alerts: de.taktovia.platform.alert.OperatorAlertPort = noAlerts, guard: SsrfGuard = testGuard(), config: DeliveryConfig = DeliveryConfig(), transport: WebhookTransport = PlainTestTransport) =
        WebhookDeliveryWorker(s.tx, transport, resolver, guard, alerts, clock, config, jitter = { 1.0 })
    /** Der Testempfänger spricht Klartext (TLS prüft `tls_zustellung_...` mit echtem Zertifikat); Adresse, Header, Body und Statuslesen sind die produktive [PinnedHttpTransport]. */
    private object PlainTestTransport : WebhookTransport {
        override fun post(target: de.taktovia.platform.net.SafeTarget, headers: Map<String, String>, body: ByteArray, timeout: Duration) =
            PinnedHttpTransport().post(target.copy(uri = java.net.URI(target.uri.toString().replaceFirst("https://", "http://"))), headers, body, timeout)
    }
    private val noAlerts = object : de.taktovia.platform.alert.OperatorAlertPort { override fun report(warning: de.taktovia.platform.alert.OperatorWarning) {} }

    @BeforeAll fun up() {
        TestDatabase.require(); s = WebhookTestSupport(); secretsDir = Files.createTempDirectory("whsecrets")
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            val n = requestNo.incrementAndGet()
            received.add(Received(ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }, ex.requestBody.readBytes(), ex.requestURI.path))
            val (code, hdr) = respond(n); hdr.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            ex.sendResponseHeaders(code, -1); ex.close()
        }
        server.executor = Executors.newFixedThreadPool(8); server.start()
    }
    @AfterAll fun down() { server.stop(0); s.close() }
    @AfterEach fun reset() { received.clear(); respond = { 200 to emptyMap() }; requestNo.set(0); clock.now = Instant.now() }

    private fun event(t: TenantId, type: String = "membership.granted", recipient: TenantId? = null, agg: UUID = UUID.randomUUID(), rev: Long = 1) = s.base.append(t, agg, rev, type, recipient)
    private fun setup(types: List<String> = listOf("membership.granted"), keyMaterial: String = "key-v1-" + UUID.randomUUID()): Triple<TenantId, UUID, String> {
        val t = s.base.newTenant(); val name = "webhook_" + UUID.randomUUID().toString().replace("-", ""); writeSecret(name, keyMaterial)
        return Triple(t, s.insertSubscription(t, url(), types, "secret://$domain/$name"), name)
    }

    @Test fun zustellung_an_testempfaenger_mit_gueltiger_signatur_und_minimiertem_payload() {
        val (t, sub, _) = setup(keyMaterial = "geheimnis-eins"); val eventId = event(t)
        assertEquals(1, WebhookFanout(s.tx).fanOut(t)); assertEquals(0, WebhookFanout(s.tx).fanOut(t), "wiederholbar: je Abonnement und Ereignis genau eine Zustellung")
        assertEquals(DeliveryStats(delivered = 1), worker().runTenant(t))
        val r = received.single(); assertEquals("/hook", r.path)
        val sig = WebhookSignature.parse(r.headers["taktovia-signature"])!!
        assertEquals(WebhookSignature.keyId("geheimnis-eins".toByteArray()), sig.keyId)
        assertTrue(WebhookSignature.matches(WebhookSignature.hmacHex("geheimnis-eins".toByteArray(), sig.timestamp, r.body), sig.signature), "Signatur über den exakten Rohbody")
        assertEquals(eventId.toString(), r.headers["taktovia-event-id"]); assertEquals("membership.granted", r.headers["taktovia-event-type"]); assertEquals("1", r.headers["taktovia-delivery-attempt"])
        val body = tools.jackson.databind.json.JsonMapper().readTree(r.body)
        assertEquals(setOf("event_id", "event_type", "schema_version", "aggregate_id", "aggregate_revision", "occurred_at"), body.propertyNames().toSet(), "minimierter Payload: Hülle ohne Nutzdaten")
        assertEquals(eventId.toString(), body["event_id"].asString())
        assertEquals(listOf("DELIVERED:1:-"), s.deliveries(t, "subscription_id = '$sub'"))
        assertFalse(String(r.body).contains("secret"), "kein Secret im Payload")
    }

    @Test fun nur_passende_aktive_abonnements_und_nur_an_den_eigenen_mandanten_adressierte_ereignisse() {
        val (a, subA, _) = setup(listOf("membership.granted")); val b = s.base.newTenant(); val subB = s.insertSubscription(b, url(), listOf("membership.granted"))
        val inactive = s.insertSubscription(a, url(), listOf("membership.granted"), active = false)
        event(a, "membership.revoked")                       // anderer Ereignistyp
        event(a, "membership.granted", recipient = b)        // an einen anderen Mandanten adressiert: nie an Abonnements von A
        val ok = event(a, "membership.granted")
        assertEquals(1, WebhookFanout(s.tx).fanOut(a))
        assertEquals(listOf("PENDING:0:-"), s.deliveries(a)); assertEquals(0, s.deliveries(b).size, "Mandant B bekommt nichts aus A")
        assertEquals(1, s.admin.queryForObject("SELECT count(*) FROM taktovia.webhook_delivery WHERE tenant_id = ? AND subscription_id = ? AND event_id = ?", Int::class.java, a.value, subA, ok))
        assertEquals(0, s.admin.queryForObject("SELECT count(*) FROM taktovia.webhook_delivery WHERE subscription_id = ?", Int::class.java, inactive))
        assertEquals(0, WebhookFanout(s.tx).fanOut(b), "keine Ereignisse in B"); assertNotNull(subB)
        // Ereignis vor dem Anlegen des Abonnements wird nicht nachträglich zugestellt
        val late = s.insertSubscription(a, url(), listOf("membership.granted"), createdAt = Instant.now().plusSeconds(60))
        assertEquals(0, WebhookFanout(s.tx).fanOut(a)); assertEquals(0, s.admin.queryForObject("SELECT count(*) FROM taktovia.webhook_delivery WHERE subscription_id = ?", Int::class.java, late))
    }

    @Test fun retry_staffel_und_429_retry_after_werden_respektiert() {
        val (t, _, _) = setup(); event(t); WebhookFanout(s.tx).fanOut(t)
        respond = { 500 to emptyMap() }
        assertEquals(DeliveryStats(retried = 1), worker().runTenant(t))
        assertEquals(listOf("PENDING:1:http_5xx"), s.deliveries(t))
        assertEquals(DeliveryStats(), worker().runTenant(t), "vor next_attempt_at nicht erneut")
        clock.advance(Duration.ofSeconds(2))                                            // Staffel: 1 s nach Versuch 1
        respond = { 429 to mapOf("Retry-After" to "120") }
        assertEquals(DeliveryStats(retried = 1), worker().runTenant(t)); assertEquals(listOf("PENDING:2:http_429"), s.deliveries(t))
        val next = s.admin.queryForObject("SELECT next_attempt_at FROM taktovia.webhook_delivery WHERE tenant_id = ?", java.sql.Timestamp::class.java, t.value)!!.toInstant()
        assertTrue(next >= clock.instant().plusSeconds(119), "Retry-After 120 s wird respektiert (Staffel allein wären 5 s): $next")
        clock.advance(Duration.ofSeconds(60)); assertEquals(DeliveryStats(), worker().runTenant(t), "noch vor Retry-After")
        clock.advance(Duration.ofSeconds(61)); respond = { 200 to emptyMap() }
        assertEquals(DeliveryStats(delivered = 1), worker().runTenant(t)); assertEquals(listOf("DELIVERED:3:-"), s.deliveries(t))
    }

    @Test fun dead_letter_nach_erschoepfung_genau_eine_betreiberwarnung_ohne_stillen_verlust() {
        val (t, _, _) = setup(); event(t); WebhookFanout(s.tx).fanOut(t); respond = { 503 to emptyMap() }
        val delivered = CopyOnWriteArrayList<AlertNotification>()
        val alerts = OperatorAlertService(AlertChannel { delivered.add(it) }, SimpleMeterRegistry(), { })
        val w = worker(alerts, config = DeliveryConfig(maxAttempts = 3))
        for (i in 1..3) { w.runTenant(t); clock.advance(Duration.ofHours(25)) }
        assertEquals(listOf("DEAD:3:http_503"), s.deliveries(t), "nach Erschöpfung Dead-Letter, nicht gelöscht")
        assertEquals(1, delivered.size, "genau eine Betreiberwarnung erreicht den konfigurierten Empfängerkanal")
        assertEquals("dead_letter", delivered.single().kind); assertEquals(t.value, delivered.single().tenantRef)
        assertEquals(setOf("event_type", "attempts", "reason_code"), delivered.single().attributes.keys, "nur technische Angaben, keine Personen- oder Inhaltsdaten")
        w.runTenant(t); w.runTenant(t); assertEquals(1, delivered.size, "keine zweite Warnung für dieselbe Zustellung"); assertEquals(3, requestNo.get(), "nach Dead-Letter keine weiteren Versuche")
    }

    @Test fun ssrf_ziel_wird_vor_jedem_aufruf_erneut_geprueft_dns_rebinding_blockiert() {
        val (t, _, _) = setup(); event(t); WebhookFanout(s.tx).fanOut(t)
        val flip = java.util.concurrent.atomic.AtomicBoolean(false)
        // beim Speichern öffentlich, zum Zustellzeitpunkt intern (klassisches DNS-Rebinding); produktive Adressprüfung
        val guard = SsrfGuard(allowedPorts = setOf(server.address.port), allowHttp = false, resolver = { listOf(if (flip.get()) InetAddress.getByName("10.0.0.5") else InetAddress.getByName("93.184.216.34")) })
        assertTrue(guard.resolve(url()).isSuccess, "beim Anlegen öffentlich")
        flip.set(true)
        assertEquals(DeliveryStats(retried = 1), worker(guard = guard).runTenant(t))
        assertEquals(listOf("PENDING:1:target_not_allowed"), s.deliveries(t)); assertEquals(0, requestNo.get(), "es wurde keine Verbindung aufgebaut")
        // dasselbe für Loopback, Link-Local und Metadaten-Adresse
        for (ip in listOf("127.0.0.1", "169.254.169.254", "192.168.1.1", "::1", "fd00::1")) {
            val g = SsrfGuard(allowedPorts = setOf(server.address.port), allowHttp = false, resolver = { listOf(InetAddress.getByName(ip)) })
            assertTrue(g.resolve(url()).isFailure, ip)
        }
    }

    @Test fun weiterleitung_auf_internes_ziel_wird_nie_verfolgt() {
        val (t, _, _) = setup(); event(t); WebhookFanout(s.tx).fanOut(t)
        respond = { n -> if (n == 1) 302 to mapOf("Location" to "https://127.0.0.1:${server.address.port}/intern") else 200 to emptyMap() }
        assertEquals(DeliveryStats(retried = 1), worker().runTenant(t))
        assertEquals(listOf("/hook"), received.map { it.path }, "der Redirect auf /intern wurde nicht aufgerufen")
        assertEquals(listOf("PENDING:1:redirect_not_followed"), s.deliveries(t))
    }

    @Test fun schluesselrotation_waehrend_zustellung_jede_signatur_traegt_die_schluessel_id_ihres_schluessels() {
        val (t, _, name) = setup(keyMaterial = "rotations-schluessel-alt"); val ev1 = event(t, agg = UUID.randomUUID()); WebhookFanout(s.tx).fanOut(t)
        assertEquals(DeliveryStats(delivered = 1), worker().runTenant(t))
        writeSecret(name, "rotations-schluessel-neu")                                  // Rotation: Referenz bleibt, Wert wechselt, kein Neustart
        val ev2 = event(t, agg = UUID.randomUUID()); WebhookFanout(s.tx).fanOut(t)
        assertEquals(DeliveryStats(delivered = 1), worker().runTenant(t))
        val keysByKid = listOf("rotations-schluessel-alt", "rotations-schluessel-neu").associateBy { WebhookSignature.keyId(it.toByteArray()) }
        val rs = received.toList(); assertEquals(2, rs.size)
        assertEquals(keysByKid.keys, rs.map { WebhookSignature.parse(it.headers["taktovia-signature"])!!.keyId }.toSet())
        for (r in rs) { val sig = WebhookSignature.parse(r.headers["taktovia-signature"])!!; assertTrue(WebhookSignature.matches(WebhookSignature.hmacHex(keysByKid.getValue(sig.keyId).toByteArray(), sig.timestamp, r.body), sig.signature)) }
        assertNotNull(ev1); assertNotNull(ev2)
    }

    @Test fun secret_nicht_verfuegbar_ist_ein_wiederholbarer_fehler_kein_verlust() {
        val t = s.base.newTenant(); s.insertSubscription(t, url(), secretRef = "secret://$domain/webhook_nicht_vorhanden"); event(t); WebhookFanout(s.tx).fanOut(t)
        assertEquals(DeliveryStats(retried = 1), worker().runTenant(t)); assertEquals(listOf("PENDING:1:secret_unavailable"), s.deliveries(t)); assertEquals(0, requestNo.get())
    }

    @Test fun deaktiviertes_abonnement_wird_nicht_mehr_zugestellt() {
        val (t, sub, _) = setup(); event(t); WebhookFanout(s.tx).fanOut(t)
        s.admin.update("UPDATE taktovia.webhook_subscription SET active = false, deactivated_at = now(), revision = revision + 1 WHERE id = ?", sub)
        assertEquals(DeliveryStats(), worker().runTenant(t)); assertEquals(listOf("CANCELLED:1:-"), s.deliveries(t)); assertEquals(0, requestNo.get())
    }

    @Test fun zwei_worker_parallel_stellen_jede_zustellung_genau_einmal_zu() {
        val (t, _, _) = setup(); repeat(12) { event(t, agg = UUID.randomUUID()) }; assertEquals(12, WebhookFanout(s.tx).fanOut(t))
        val pool = Executors.newFixedThreadPool(4); val start = CountDownLatch(1)
        val stats = (1..4).map { pool.submit<DeliveryStats> { start.await(); worker().runTenant(t) } }
        start.countDown(); val total = stats.map { it.get(60, TimeUnit.SECONDS) }.fold(DeliveryStats()) { a, b -> a + b }; pool.shutdown()
        assertEquals(12, total.delivered); assertEquals(12, received.size); assertEquals(12, received.map { it.headers["taktovia-event-id"] }.toSet().size, "kein Ereignis doppelt zugestellt")
    }

    @Test fun verbindungsfehler_ist_ein_wiederholbarer_fehlversuch() {
        val t = s.base.newTenant(); val name = "webhook_" + UUID.randomUUID().toString().replace("-", ""); writeSecret(name, "x")
        s.insertSubscription(t, "https://hook.example.test:1/hook", secretRef = "secret://$domain/$name"); event(t); WebhookFanout(s.tx).fanOut(t)
        val guard = SsrfGuard(allowedPorts = setOf(1), allowHttp = false, resolver = { listOf(InetAddress.getLoopbackAddress()) }, addressCheck = { true })
        assertEquals(DeliveryStats(retried = 1), worker(guard = guard).runTenant(t)); assertEquals(listOf("PENDING:1:connection_failed"), s.deliveries(t))
    }

    @Test fun tls_zustellung_prueft_zertifikat_gegen_den_hostnamen_der_url_nicht_gegen_die_ip() {
        val m = tlsMaterial("hook.example.test"); val received = AtomicInteger()
        val tls = de.taktovia.platform.TestTls.server(m) { received.incrementAndGet(); 200 }
        try {
            val port = tls.address.port
            val guard = SsrfGuard(allowedPorts = setOf(port), allowHttp = false, resolver = { listOf(InetAddress.getLoopbackAddress()) }, addressCheck = { true })
            val tlsTransport = PinnedHttpTransport(m.client.socketFactory)
            fun scenario(host: String): Pair<DeliveryStats, List<String>> {
                val t = s.base.newTenant(); val name = "webhook_" + UUID.randomUUID().toString().replace("-", ""); writeSecret(name, "tls-secret")
                s.insertSubscription(t, "https://$host:$port/hook", secretRef = "secret://$domain/$name"); event(t); WebhookFanout(s.tx).fanOut(t)
                return worker(guard = guard, transport = tlsTransport).runTenant(t) to s.deliveries(t).filterNotNull()
            }
            assertEquals(DeliveryStats(delivered = 1) to listOf("DELIVERED:1:-"), scenario("hook.example.test"), "Zertifikat gilt für den Namen der URL")
            assertEquals(1, received.get())
            assertEquals(DeliveryStats(retried = 1) to listOf("PENDING:1:connection_failed"), scenario("evil.example.test"), "Zertifikat passt nicht zum Namen der URL: kein Aufruf")
            assertEquals(1, received.get(), "der Server hat die Anfrage des falschen Namens nie gesehen")
        } finally { tls.stop(0) }
    }

    /** Zur Laufzeit erzeugtes Testzertifikat für [host] (keytool, nicht im Repository); `TestTls` kennt nur `localhost`, das der SSRF-Guard als internen Namen sperrt. */
    private fun tlsMaterial(host: String): de.taktovia.platform.TestTls.Material {
        val dir = Files.createTempDirectory("tls"); val ks = dir.resolve("s.p12").toFile(); val pw = UUID.randomUUID().toString()
        val p = ProcessBuilder("keytool", "-genkeypair", "-alias", "srv", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12", "-keystore", ks.path,
            "-storepass", pw, "-keypass", pw, "-dname", "CN=$host", "-validity", "1", "-ext", "san=dns:$host").redirectErrorStream(true).start()
        check(p.waitFor() == 0) { p.inputStream.readBytes().decodeToString() }
        val store = java.security.KeyStore.getInstance("PKCS12").also { st -> ks.inputStream().use { st.load(it, pw.toCharArray()) } }
        val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).also { it.init(store, pw.toCharArray()) }
        val tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).also { it.init(store) }
        return de.taktovia.platform.TestTls.Material(javax.net.ssl.SSLContext.getInstance("TLS").also { it.init(kmf.keyManagers, null, null) }, javax.net.ssl.SSLContext.getInstance("TLS").also { it.init(null, tmf.trustManagers, null) })
    }
}
