package de.taktovia.app.payment

import com.sun.net.httpserver.HttpServer
import de.taktovia.TaktoviaTestSupport
import de.taktovia.adapters.psp.api.PspAdapter
import de.taktovia.adapters.psp.api.PspAuthorize
import de.taktovia.adapters.psp.api.PspCommandRef
import de.taktovia.adapters.psp.api.PspOperation
import de.taktovia.adapters.psp.api.PspOutcome
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Leitlinie 3 / WP-M2-29 AK 5: während eines PSP-Aufrufs ist keine Datenbanktransaktion offen. Der Nachweis liegt an der **Datenbank**: ein Fake-PSP (echter HTTP-Server) fragt beim Eintreffen
 * jeder Anfrage `pg_stat_activity` ab und zählt Verbindungen der Runtime-Rolle in offener Transaktion (`idle in transaction`). Gegenprobe: derselbe Detektor sieht eine offene Transaktion,
 * wenn der Aufruf (an der Absicherung vorbei) mit einem rohen HTTP-Client innerhalb von `TenantTransactions.run` erfolgt; der Adapter selbst scheitert dort vor dem Senden.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PspNoOpenTransactionTest {
    private lateinit var fake: HttpServer
    private lateinit var tx: TenantTransactions
    private val openTxSeen = CopyOnWriteArrayList<Int>()
    private val requests = java.util.concurrent.atomic.AtomicInteger()

    @BeforeAll fun up() {
        tx = PspEventEnv.server.ctx.getBean(TenantTransactions::class.java)
        val admin = TaktoviaTestSupport.adminDataSourceOrNull()!!
        fake = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fake.createContext("/") { ex ->
            requests.incrementAndGet()
            ex.requestBody.readAllBytes()
            val open = admin.connection.use { c -> c.createStatement().use { st -> st.executeQuery(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND usename = 'taktovia_runtime' AND state IN ('idle in transaction', 'idle in transaction (aborted)')").use { rs -> rs.next(); rs.getInt(1) } } }
            openTxSeen += open
            val body = """{"payment_id":"pay_1","provider_ref":"psp_1","merchant_ref":"m","state":"AUTHORIZED","currency":"EUR","authorized_minor":100,"captured_minor":0,"refunded_minor":0,"dispute_state":null,"operations":[]}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(201, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        fake.start()
    }
    @AfterAll fun down() { if (::fake.isInitialized) fake.stop(0) }

    private val psp get() = PspAdapter.http("fakepsp", URI("http://127.0.0.1:${fake.address.port}"), Duration.ofSeconds(5))
    private fun cmd() = PspAuthorize(PspCommandRef.derive(UUID.randomUUID().toString(), PspOperation.AUTHORIZE), "m-" + UUID.randomUUID(), 100, "EUR", "pm_ok")

    @Test
    fun waehrend_des_psp_aufrufs_ist_keine_datenbanktransaktion_offen() {
        val tenant = PspEventEnv.tenant()
        // Datenbank-Arbeit davor und danach in eigenen, abgeschlossenen Transaktionen; der PSP-Aufruf dazwischen
        tx.run(tenant) { j -> j.queryForObject("SELECT 1", Int::class.java) }
        val before = openTxSeen.size
        val r = psp.authorize(cmd())
        assertTrue(r is PspOutcome.Ok, r.toString())
        tx.run(tenant) { j -> j.queryForObject("SELECT 1", Int::class.java) }
        assertEquals(1, openTxSeen.size - before, "der Fake-PSP wurde genau einmal erreicht")
        assertEquals(0, openTxSeen.last(), "keine Transaktion der Runtime-Rolle war während des PSP-Aufrufs offen")
    }

    @Test
    fun der_detektor_sieht_eine_offene_transaktion_und_der_adapter_sendet_dort_nichts() {
        val tenant = PspEventEnv.tenant()
        val http = HttpClient.newHttpClient()
        val seen = openTxSeen.size
        tx.run(tenant) { j ->
            j.queryForObject("SELECT 1", Int::class.java)   // Transaktion ist offen
            // Gegenprobe des Detektors: roher Aufruf (ohne Adapter) in offener Transaktion wird gesehen
            http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${fake.address.port}/probe")).POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.discarding())
        }
        assertTrue(openTxSeen[seen] >= 1, "Detektor erkennt die offene Transaktion: ${openTxSeen[seen]}")
        val sent = requests.get()
        tx.run(tenant) { j ->
            j.queryForObject("SELECT 1", Int::class.java)
            assertThrows(IllegalStateException::class.java) { psp.authorize(cmd()) }   // der Adapter weigert sich vor dem Senden
        }
        assertEquals(sent, requests.get(), "innerhalb der Transaktion wurde nichts an den PSP gesendet")
    }
}
