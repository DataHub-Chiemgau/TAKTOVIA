package de.taktovia.app

import de.taktovia.TaktoviaTestSupport
import de.taktovia.app.probe.ProbeCommandStats
import de.taktovia.app.probe.ProbeDb
import de.taktovia.app.probe.ProbeServer
import de.taktovia.app.probe.Resp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Idempotenzregister über die echte Filterkette und Datenbank (WP-M1-13, LH-6.17-INT-004 „Gleichzeitiger Retransmit erzeugt kein Duplikat“).
 * Nachweis am Testbefehl (`createProbeCommand`) und an `publishProbeCommand`, der `publishConfig` (WP-M1-22) vertritt: If-Match-geschützt, Revision +1,
 * Ereignis zur neuen Revision. `publishConfig` selbst folgt mit WP-M1-22 über denselben Filter; Buchungs- und Zahlungsbefehle mit M2.
 */
class PublishConfigIdempotencyTest {
    private val props = arrayOf("taktovia.probe.idempotency=true", "taktovia.api.extra-specs=classpath:probe/idempotency-probe-api.yaml",
        "taktovia.api.rate-limit.requests=1000000", "spring.datasource.hikari.maximum-pool-size=24")
    private val server get() = ProbeServer.get(*props)
    private val tenantA = ProbeDb.tenantA.toString()
    private fun who(actor: String = "alice", tenant: String = tenantA) = mapOf("X-Test-Tenant" to tenant, "X-Test-Actor" to actor)
    private fun key() = "key-" + UUID.randomUUID()
    private fun create(key: String, name: String = "cmd", extra: String = "", headers: Map<String, String> = who(), s: ProbeServer = server): Resp =
        s.post("/v1/probe/commands", """{"name":"$name"$extra}""", headers + ("Idempotency-Key" to key))
    private fun publish(id: String, ifMatch: String, key: String, reason: String = "Veröffentlichen", headers: Map<String, String> = who()): Resp =
        server.post("/v1/probe/commands/$id/publish", """{"reason":"$reason"}""", headers + ("Idempotency-Key" to key) + ("If-Match" to ifMatch))

    private fun count(sql: String, vararg args: Any?): Int = TaktoviaTestSupport.adminDataSourceOrNull()!!.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { it.next(); it.getInt(1) } }
    }
    private fun outbox(id: String, type: String) = count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type=?", ProbeDb.tenantA, UUID.fromString(id), type)

    @Test
    fun wiederholung_nach_timeout_liefert_gespeichertes_ergebnis_ohne_zweite_wirkung() {
        val k = key(); val before = ProbeCommandStats.creates.get()
        val first = create(k)
        assertEquals(201, first.status, first.text)
        // Der Client hat die Antwort nie erhalten und wiederholt mit demselben Schlüssel:
        val retry = create(k)
        assertEquals(201, retry.status)
        assertEquals(first.json["id"].asString(), retry.json["id"].asString(), "dieselbe Ressourcen-ID")
        assertEquals(first.header("Location"), retry.header("Location")); assertEquals(first.header("ETag"), retry.header("ETag"))
        assertNull(first.header("Idempotent-Replayed")); assertEquals("true", retry.header("Idempotent-Replayed"))
        assertEquals(before + 1, ProbeCommandStats.creates.get(), "keine zweite Ausführung")
        assertEquals(1, outbox(first.json["id"].asString(), "probe.command.created"))
        // neuer Schlüssel für neuen Vorgang wird neu ausgeführt
        assertNotEquals(first.json["id"].asString(), create(key()).json["id"].asString())
    }

    @Test
    fun hundert_parallele_identische_requests_eine_wirkung() {
        val k = key(); val name = "parallel-" + UUID.randomUUID(); val before = ProbeCommandStats.creates.get()
        val pool = Executors.newFixedThreadPool(100); val start = CountDownLatch(1)
        val futures = (1..100).map { pool.submit<Resp> { start.await(); create(k, name, extra = ""","delay_ms":300""") } }
        start.countDown()
        val responses = futures.map { it.get(90, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(setOf(201), responses.map { it.status }.toSet(), responses.firstOrNull { it.status != 201 }?.text)
        val ids = responses.map { it.json["id"].asString() }.toSet()
        assertEquals(1, ids.size, "alle Antworten tragen dieselbe Ressourcen-ID")
        assertEquals(1, responses.count { it.header("Idempotent-Replayed") == null }, "genau eine Erstausführung")
        assertEquals(before + 1, ProbeCommandStats.creates.get())
        assertEquals(1, count("SELECT count(*) FROM public.etag_probe WHERE tenant_id=? AND name=?", ProbeDb.tenantA, name), "genau ein Ergebnisobjekt")
        assertEquals(1, outbox(ids.single(), "probe.command.created"), "genau ein Outbox-Eintrag")
    }

    @Test
    fun gleicher_schluessel_anderer_body_oder_pfad_409_ohne_zweite_ausfuehrung() {
        val k = key(); val first = create(k, "eins"); val before = ProbeCommandStats.creates.get()
        val other = create(k, "zwei")
        assertEquals(409, other.status); assertEquals("IDEMPOTENCY_CONFLICT", other.json["code"].asString())
        assertEquals(before, ProbeCommandStats.creates.get(), "keine zweite Ausführung")
        assertTrue(!other.text.contains(first.json["id"].asString()), "kein fremdes Ergebnis in der Antwort")
        // anderer Pfad (andere Ressource) mit gleichem Schlüssel und gleicher Operation
        val a = create(key()).json["id"].asString(); val b = create(key()).json["id"].asString(); val pk = key()
        assertEquals(200, publish(a, "\"1\"", pk).status)
        val wrongPath = publish(b, "\"1\"", pk)
        assertEquals(409, wrongPath.status); assertEquals("IDEMPOTENCY_CONFLICT", wrongPath.json["code"].asString())
        assertEquals(0, outbox(b, "probe.command.published"))
        // andere erwartete Revision (If-Match) ist ein anderer Request
        assertEquals("IDEMPOTENCY_CONFLICT", publish(a, "\"2\"", pk).json["code"].asString())
    }

    @Test
    fun schluessel_kollidieren_nicht_zwischen_mandanten_akteuren_und_operationen() {
        val k = key(); val before = ProbeCommandStats.creates.get()
        val alice = create(k, headers = who("alice")); val bob = create(k, headers = who("bob"))
        val tenantB = create(k, headers = who("alice", ProbeDb.tenantB.toString()))
        assertEquals(listOf(201, 201, 201), listOf(alice, bob, tenantB).map { it.status })
        assertEquals(3, listOf(alice, bob, tenantB).map { it.json["id"].asString() }.toSet().size)
        assertEquals(before + 3, ProbeCommandStats.creates.get())
        // jeder bekommt bei Wiederholung nur sein eigenes Ergebnis
        assertEquals(bob.json["id"].asString(), create(k, headers = who("bob")).json["id"].asString())
        assertEquals(tenantB.json["id"].asString(), create(k, headers = who("alice", ProbeDb.tenantB.toString())).json["id"].asString())
        // anderer Operationstyp mit demselben Schlüssel
        assertEquals(200, publish(alice.json["id"].asString(), "\"1\"", k).status, "anderer Operationstyp: eigener Scope")
    }

    @Test
    fun zwei_schluessel_gleiche_revision_genau_einer_gewinnt() {
        val id = create(key()).json["id"].asString()
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        val futures = listOf(key(), key()).map { k -> pool.submit<Resp> { start.await(); publish(id, "\"1\"", k) } }
        start.countDown()
        val statuses = futures.map { it.get(30, TimeUnit.SECONDS).status }.sorted(); pool.shutdown()
        assertEquals(listOf(200, 412), statuses, "genau einer gewinnt, der andere erhält REVISION_MISMATCH")
        assertEquals(1, outbox(id, "probe.command.published"), "kein Duplikat und keine zweite Wirkung")
        assertEquals(2L, count("SELECT revision FROM public.etag_probe WHERE tenant_id=? AND id=?", ProbeDb.tenantA, UUID.fromString(id)).toLong())
    }

    @Test
    fun gleicher_schluessel_parallel_am_publish_wirkt_genau_einmal() {
        val id = create(key()).json["id"].asString(); val k = key()
        val pool = Executors.newFixedThreadPool(20); val start = CountDownLatch(1)
        val futures = (1..20).map { pool.submit<Resp> { start.await(); publish(id, "\"1\"", k) } }
        start.countDown()
        val responses = futures.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(setOf(200), responses.map { it.status }.toSet(), "alle 20 erhalten das Ergebnis der einen Ausführung, nicht 412")
        assertEquals(1, outbox(id, "probe.command.published"))
    }

    @Test
    fun angenommenes_kommando_erscheint_bei_wiederholung_nicht_als_abgelaufen() {
        val k = key(); val deadline = Instant.now().plusMillis(1200).toString()
        val first = create(k, extra = ""","deadline":"$deadline"""")
        assertEquals(201, first.status)
        Thread.sleep(1500)
        val retry = create(k, extra = ""","deadline":"$deadline"""")
        assertEquals(201, retry.status, "Wiederholung liefert das angenommene Ergebnis, nicht OFFER_EXPIRED")
        assertEquals(first.json["id"].asString(), retry.json["id"].asString())
        val fresh = create(key(), extra = ""","deadline":"$deadline"""")
        assertEquals(409, fresh.status); assertEquals("OFFER_EXPIRED", fresh.json["code"].asString(), "ein neuer Befehl mit abgelaufener Frist wird abgewiesen")
    }

    @Test
    fun fehlerantwort_wird_nicht_gespeichert_der_schluessel_ist_danach_frei() {
        val id = create(key()).json["id"].asString(); val k = key()
        val stale = publish(id, "\"9\"", k)
        assertEquals(412, stale.status); assertEquals("REVISION_MISMATCH", stale.json["code"].asString())
        assertEquals(412, publish(id, "\"9\"", k).status, "die Fehlerantwort wird nicht aus dem Register wiederholt, sondern neu geprüft")
        assertEquals(200, publish(id, "\"1\"", k).status, "mit korrekter Revision ist derselbe Schlüssel wieder verwendbar")
    }

    @Test
    fun schluesselformat_und_fehlender_header_werden_abgewiesen() {
        fun violation(r: Resp) = r.json["violations"][0]
        val missing = server.post("/v1/probe/commands", """{"name":"x"}""", who())
        assertEquals(422, missing.status); assertEquals("/\$header/Idempotency-Key", violation(missing)["pointer"].asString()); assertEquals("REQUIRED", violation(missing)["code"].asString())
        for ((label, bad) in mapOf("zu kurz" to "k".repeat(15), "zu lang" to "k".repeat(129), "Zeichen" to "ungültig-äöü-ungültig-1", "Leerzeichen" to "ein schluessel mit blank")) {
            val r = create(bad)
            assertEquals(422, r.status, label); assertEquals("/\$header/Idempotency-Key", violation(r)["pointer"].asString(), label)
        }
        assertEquals(201, create("k".repeat(16)).status); assertEquals(201, create("k".repeat(128).let { it.dropLast(8) + UUID.randomUUID().toString().take(8) }).status)
    }

    @Test
    fun retransmit_waehrend_erstverarbeitung_operation_in_progress() {
        val slow = ProbeServer.get(*props, "taktovia.idempotency.in-progress-wait-millis=200")
        val k = key(); val before = ProbeCommandStats.creates.get()
        val pool = Executors.newSingleThreadExecutor()
        val first = pool.submit<Resp> { create(k, extra = ""","delay_ms":2000""", s = slow) }
        Thread.sleep(600)   // Erstverarbeitung läuft
        val during = create(k, extra = ""","delay_ms":2000""", s = slow)
        assertEquals(409, during.status, during.text); assertEquals("OPERATION_IN_PROGRESS", during.json["code"].asString())
        assertEquals("1", during.header("Retry-After"))
        val done = first.get(20, TimeUnit.SECONDS); pool.shutdown()
        assertEquals(201, done.status)
        val after = create(k, extra = ""","delay_ms":2000""", s = slow)
        assertEquals(201, after.status); assertEquals(done.json["id"].asString(), after.json["id"].asString(), "danach dasselbe Ergebnis")
        assertEquals(before + 1, ProbeCommandStats.creates.get(), "genau eine Ausführung")
    }
}
