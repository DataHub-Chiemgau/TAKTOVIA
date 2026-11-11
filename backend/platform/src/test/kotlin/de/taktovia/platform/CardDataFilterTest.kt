package de.taktovia.platform

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.HikariDataSource
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.carddata.CardData
import de.taktovia.platform.carddata.CardDataFilter
import de.taktovia.platform.carddata.CardDataIncidents
import de.taktovia.platform.carddata.CardDataInput
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.fieldprotection.FieldRights
import de.taktovia.platform.fieldprotection.ProtectedFields
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** WP-M1-37 / LH-6.18-SEC-008: keine rohen Kartendaten im System. Testkartennummern sind die öffentlich dokumentierten Prüfnummern der Netzwerke. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CardDataFilterTest {
    private val pans = listOf("4111111111111111", "5555555555554444", "378282246310005", "6011111111111117", "3530111333300000", "30569309025904", "2223003122003222", "4222222222222")
    private val json = JsonMapper.builder().build()
    private val roles = listOf("passenger", "driver", "callcenter", "support", "dispatcher", "planner", "tenant_admin", "finance", "staff_planning", "workshop")
    private fun spaced(p: String) = p.chunked(4).joinToString(" ")
    private fun dashed(p: String) = p.chunked(4).joinToString("-")
    private fun digitsOf(s: String) = s.filter(Char::isDigit)
    private fun leaksAnyPan(text: String): Boolean = pans.any { text.contains(it) || text.contains(spaced(it)) || text.contains(dashed(it)) } || CardData.contains(text)

    private lateinit var runtime: HikariDataSource
    private lateinit var admin: JdbcTemplate
    private lateinit var tx: TenantTransactions
    private val tenantA = TenantId(UUID.randomUUID()); private val tenantB = TenantId(UUID.randomUUID())

    @BeforeAll fun setup() {
        TestDatabase.require()
        admin = JdbcTemplate(TestDatabase.admin())
        for (t in listOf(tenantA, tenantB)) admin.update("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?, 'ACTIVE')", t.value, "kartendaten-$t")
        admin.update("INSERT INTO taktovia.currency(code,exponent) VALUES ('EUR',2) ON CONFLICT DO NOTHING")
        runtime = TestDatabase.runtime(poolSize = 16); tx = TenantTransactions(runtime)
    }

    @AfterAll fun teardown() { if (::runtime.isInitialized) runtime.close() }

    @Test fun kartennummer_in_freitext_wird_abgewiesen_oder_maskiert() {
        for (p in pans) for (form in listOf(p, spaced(p), dashed(p), p.chunked(4).joinToString(".")))
            for (text in listOf(form, "Bitte abbuchen von $form danke", "x-$form", "[$form]")) {
                assertTrue(CardData.contains(text), "nicht erkannt: Netzwerk ${p.take(2)}** in '${text.length} Zeichen'")
                val masked = CardData.mask(text)
                assertFalse(CardData.contains(masked)); assertTrue(digitsOf(masked).length < 13, "Ziffernrest nach Maskierung: $masked")
            }
        // Verschleierung durch Anhängen weiterer Ziffern wirkt nicht
        assertTrue(CardData.contains("9" + "4111111111111111" + "7")); assertTrue(CardData.contains("4111111111111111 4111111111111111"))
        // Kein Fehlalarm: UUIDs, Zeitstempel, Zahlen ohne Netzwerkpräfix oder mit falscher Prüfsumme, kurze Nummern, PSP-Referenz
        for (ok in listOf(UUID.randomUUID().toString(), "12345678-1234-1234-1234-123456789012", "2026-11-11T10:15:30.123Z", "1731321600000", "4111111111111112", "0151 23456789",
            "9999999999999995", "psp_test_token_not_a_real_credential", "pi_3Kx9AbCdEf1234567890", "Rechnung 2026-000123", "")) assertFalse(CardData.contains(ok), "Fehlalarm: $ok")
        // Freitextfeld im Body: Fundstelle ohne Wert
        val body = json.readTree("""{"contact":{"channel":"EMAIL","note":"Karte ${spaced(pans[0])} bitte"},"locale":"de-DE"}""")
        val findings = CardDataFilter.screen(CardDataInput(body = body))
        assertEquals(listOf("body:/contact/note"), findings.map { it.location })
        // Audit: Freitext, Schlüssel und Zahlenwert mit Kartennummer erscheinen nie in redacted_changes
        val corr = UUID.randomUUID(); val obj = UUID.randomUUID()
        tx.run(tenantA) { j -> JdbcAuditPort().record(j, AuditEntry(ActorRef.system("kartentest"), "identity.organization.changed", ObjectRef("organization", obj), corr,
            mapOf("note" to "Karte ${spaced(pans[0])}", "comment" to dashed(pans[1]), "nested" to mapOf("x" to listOf(pans[2], pans[3])), pans[4] to "v", "amount_ref" to pans[1].toLong()))) }
        val stored = admin.queryForList("SELECT redacted_changes::text FROM taktovia.audit_event WHERE correlation_id = ?", String::class.java, corr).single()!!
        assertFalse(leaksAnyPan(stored), stored); assertTrue(stored.contains("_redacted"), stored)
    }

    @Test fun kartennummer_in_body_query_header_wird_abgewiesen_oder_maskiert() {
        val p = pans[0]
        fun locations(i: CardDataInput) = CardDataFilter.screen(i).map { it.location }
        assertEquals(listOf("query:q"), locations(CardDataInput(query = "q=$p&page=1")))
        assertEquals(listOf("query:q"), locations(CardDataInput(query = "q=${spaced(p).replace(" ", "%20")}")))
        assertEquals(listOf("query:*"), locations(CardDataInput(query = "$p=1")))                      // Name mit Kartennummer: Fundstelle ohne Namen
        assertEquals(listOf("header:X-Referenz"), locations(CardDataInput(headers = mapOf("X-Referenz" to listOf("a", dashed(p))))))
        assertEquals(listOf("body:/items/1/n"), locations(CardDataInput(body = json.readTree("""{"items":[{"n":"ok"},{"n":"$p"}]}"""))))
        assertEquals(listOf("body:/amount"), locations(CardDataInput(body = json.readTree("""{"amount":$p}"""))))          // Zahlenwert
        assertEquals(listOf("body:/"), locations(CardDataInput(body = json.readTree("""{"$p":"x"}"""))))                      // Schlüssel
        assertEquals(listOf("body:/"), locations(CardDataInput(rawBody = "kein json $p")))
        // Fundstellen enthalten nie Ziffernfolgen der Eingabe
        for (l in locations(CardDataInput(query = "$p=1&a=${spaced(p)}", headers = mapOf(p to listOf(p)), body = json.readTree("""{"$p":"$p"}""")))) assertFalse(CardData.contains(l), l)
        // Gültige Anfrage mit PSP-Referenz wird nicht beanstandet (positiv)
        assertEquals(emptyList<String>(), locations(CardDataInput(query = "limit=20&cursor=abc", headers = mapOf("Idempotency-Key" to listOf(UUID.randomUUID().toString()), "traceparent" to listOf("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")),
            body = json.readTree("""{"provider":"psp","provider_ref":"psp_test_token_not_a_real_credential","captured_at":1731321600000,"id":"${UUID.randomUUID()}"}"""))))
        // Ereignis-Payload: kartennummernähnliche Werte werden vom Outbox-Baustein abgewiesen, ohne die Nummer zu nennen
        val e = assertThrows<IllegalArgumentException> {
            tx.run(tenantA) { j -> Outbox().append(j, OutboxEvent(UUID.randomUUID(), 1, "payment.test_event", mapOf("memo" to listOf("x", mapOf("n" to p))), Instant.now())) }
        }
        assertFalse(leaksAnyPan(e.message!!)); assertEquals(0, admin.queryForObject("SELECT count(*) FROM taktovia.outbox WHERE payload::text LIKE ?", Int::class.java, "%$p%"))
    }

    @Test fun psp_referenz_nur_fuer_rolle_mit_zahlungsbezug() {
        val rights = FieldRights.load()
        fun human(role: String, scopes: Set<String>) = RequestContext(Actor(ActorType.HUMAN, "https://idp.test", "u-$role", null, true), tenantA, setOf(role), scopes, emptySet(), UUID.randomUUID())
        val ref = "psp_test_token_not_a_real_credential"
        val scope = setOf("payments:reference:read")
        for (role in roles) {
            val seen = rights.project(human(role, scope), ProtectedFields.PROVIDER_REF, "PAYMENT_PROCESSING", ref)
            if (role == "finance") assertEquals(ref, seen, "Rolle mit Zahlungsbezug") else assertNull(seen, "Rolle ohne Zahlungsbezug: $role")
            assertNull(rights.project(human(role, emptySet()), ProtectedFields.PROVIDER_REF, "PAYMENT_PROCESSING", ref), "ohne Feldscope: $role")
            assertNull(rights.project(human(role, scope), ProtectedFields.PROVIDER_REF, null, ref), "ohne Zweck (Standardantwort): $role")
        }
        val service = RequestContext(Actor(ActorType.SERVICE, "https://idp.test", "adapter-psp", null, false), tenantA, emptySet(), scope, emptySet(), UUID.randomUUID())
        assertEquals(ref, rights.project(service, ProtectedFields.PROVIDER_REF, "PAYMENT_PROCESSING", ref))
        assertNull(rights.project(human("finance", scope), ProtectedFields.PROVIDER_REF, "SUPPORT_CASE", ref), "falscher Zweck")
        // Fremdmandant: die Zeile des Mandanten A ist unter dem Kontext von B nicht sichtbar (RLS), unter A schon
        val pi = UUID.randomUUID()
        admin.execute("SET session_replication_role = replica")   // nur Testaufbau: Fachketten (Reise, Angebot) sind nicht Teil dieses Tests
        try {
            admin.update("INSERT INTO taktovia.payment_intent(tenant_id,id,journey_id,provider,provider_ref,state,currency,authorized_minor) VALUES (?,?,?,?,?,?,?,?)",
                tenantA.value, pi, UUID.randomUUID(), "psp", ref, "AUTHORIZED", "EUR", 100L)
        } finally { admin.execute("SET session_replication_role = DEFAULT") }
        fun lookup(t: TenantId) = tx.run(t, readOnly = true) { j -> j.queryForList("SELECT provider_ref FROM taktovia.payment_intent WHERE id = ?", String::class.java, pi) }
        assertEquals(listOf(ref), lookup(tenantA)); assertEquals(emptyList<String>(), lookup(tenantB))
    }

    @Test fun hundert_parallele_requests_ohne_treffer_in_logs_und_audit() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val old = root.level
        root.addAppender(appender); root.level = Level.DEBUG
        val incidents = CardDataIncidents(tx, SecurityEventPort(JdbcAuditPort()), threads = 4)
        val start = CountDownLatch(1); val exec = Executors.newFixedThreadPool(16)
        val correlations = (1..100).map { UUID.randomUUID() }
        val rejected = java.util.concurrent.atomic.AtomicInteger()
        try {
            val jobs = correlations.mapIndexed { i, corr ->
                exec.submit(Callable {
                    start.await()
                    val p = pans[i % pans.size]
                    val input = when (i % 4) {
                        0 -> CardDataInput(body = json.readTree("""{"note":"${spaced(p)}","locale":"de-DE"}"""))
                        1 -> CardDataInput(query = "memo=$p")
                        2 -> CardDataInput(headers = mapOf("X-Memo" to listOf(dashed(p))))
                        else -> CardDataInput(body = json.readTree("""{"a":[{"b":"$p"}]}"""))
                    }
                    val findings = CardDataFilter.screen(input)
                    if (findings.isNotEmpty()) { rejected.incrementAndGet(); incidents.report(tenantA, corr, findings) }
                    // der Fachpfad hätte die Eingabe auditieren oder protokollieren wollen: Maskierung gilt auch dort
                    tx.run(tenantA) { j -> JdbcAuditPort().record(j, AuditEntry(ActorRef.system("kartentest"), "identity.organization.changed", ObjectRef("request", corr), corr, mapOf("echo" to (input.query ?: dashed(p)), "echo2" to spaced(p)))) }
                    LoggerFactory.getLogger("taktovia.test").info("Eingabe {}", CardData.mask(input.query ?: spaced(p)))
                })
            }
            start.countDown(); jobs.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally { exec.shutdown(); incidents.close(); root.detachAppender(appender); root.level = old }
        assertEquals(100, rejected.get(), "jeder Request wird abgewiesen")
        assertEquals(100, admin.queryForObject("SELECT count(*) FROM taktovia.audit_event WHERE action = 'security.input.card_data_rejected' AND correlation_id = ANY(?)", Int::class.java, correlations.toTypedArray()),
            "jede Abweisung ist als Sicherheitsereignis auditiert")
        val logs = appender.list.map { e -> e.formattedMessage + " " + (e.throwableProxy?.let { ThrowableProxyUtil.asString(it) } ?: "") }
        assertTrue(logs.count { it.contains("card_data_rejected") } >= 100, "Abweisungen sind im Log nachvollziehbar")
        assertFalse(logs.any { leaksAnyPan(it) }, "Treffer in Logs: " + logs.firstOrNull { leaksAnyPan(it) }?.take(120))
        val audits = admin.queryForList("SELECT redacted_changes::text FROM taktovia.audit_event WHERE correlation_id = ANY(?)", String::class.java, correlations.toTypedArray()).filterNotNull()
        assertTrue(audits.size >= 200)
        assertFalse(audits.any { leaksAnyPan(it) }, "Treffer im Audit")
        assertNotNull(logs.firstOrNull { it.contains("card_data_rejected") })
    }
}
