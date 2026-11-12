package de.taktovia.app.driver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-27 / LH-6.6-DRV-004 über die echte Anwendung (HTTP, echter Fahrtenplan, echter Live-Kanal): eine neu eingespielte Route erreicht den Fahrer über den Live-Kanal und den Abruf
 * (Messung Commit bis dargestellte Clientrevision, p95 ≤ 10 s), bedingter Abruf, Bestätigung (428/412/200, zwei Geräte, fremde Schicht, falsche Rolle), kein Fahrgast- oder Ortsbezug in der
 * Live-Nachricht. Nicht ausgeführt: die Flutter-Anzeige (UI-10) und der Push-Weckruf an ein Gerät (Anbieter offen, OP-29/D-05).
 */
class PlanRevisionDeliveryHttpTest {
    private val w = DriverRunHttpWorld()
    private val env = w.env
    private val json = JsonMapper.builder().build()

    private fun ack(d: DriverRunHttpWorld.Driver, revision: Long, ifMatch: String? = "\"$revision\"", token: String = d.token, shift: UUID = d.shift, device: UUID? = null, key: String = "ack-" + UUID.randomUUID(), at: String = "2026-11-05T09:00:00Z") =
        env.server.send("POST", "/v1/driver/shifts/$shift/run/acknowledgements",
            """{"plan_revision":$revision,"acknowledged_at":"$at"${if (device != null) ""","device_id":"$device"""" else ""}}""",
            env.headers(token, extra = buildMap { put("Idempotency-Key", key); if (ifMatch != null) put("If-Match", ifMatch) }))

    @Test
    fun neue_route_innerhalb_p95_dargestellt() {
        val d = w.driver(); val a = w.booking(); val b = w.booking(); val c = w.booking()
        w.plan(d.run, listOf(a, b))
        // Fahrer-App: Live-Kanal offen (Strom über echtes HTTP, Zeilenweise gelesen); bei jedem Ereignis lädt sie den Plan per Abruf und stellt ihn dar
        val committed = ConcurrentHashMap<Long, Long>(); val shown = ConcurrentHashMap<Long, Long>(); val stateOf = ConcurrentHashMap<Long, String>()
        val http = HttpClient.newHttpClient(); val ready = CountDownLatch(1); val done = CountDownLatch(1)
        val total = 100
        val reader = Executors.newSingleThreadExecutor(); val loader = Executors.newFixedThreadPool(4)
        reader.submit {
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:${env.server.port}/v1/driver/live/events?wait_seconds=55")).also { env.headers(d.token).forEach { (k, v) -> it.header(k, v) } }.build()
            http.send(req, HttpResponse.BodyHandlers.ofLines()).body().use { lines ->
                for (line in lines) {
                    if (line.startsWith("event: ready")) ready.countDown()
                    if (!line.startsWith("data: ") || !line.contains("dispatch.run_revised")) continue
                    val rev = json.readTree(line.removePrefix("data: "))["aggregate_revision"].asLong()
                    loader.submit {
                        // Darstellung: Abruf des Plans mit der gemeldeten Revision (ETag = Planrevision)
                        val r = d.get()
                        if (r.status == 200 && r.json["plan_revision"].asLong() >= rev) { shown.putIfAbsent(rev, System.nanoTime()); stateOf[rev] = r.json["revision_state"].asString() }
                        if (shown.size >= total) done.countDown()
                    }
                }
            }
        }
        assertTrue(ready.await(20, TimeUnit.SECONDS), "Live-Kanal steht")
        repeat(total) { i ->
            val rev = w.plan(d.run, listOf(a, b, c).take(2 + i % 2), base = w.t0.plusSeconds(900L * (i + 1)))   // jede Revision verschiebt den Plan deutlich (bestätigungspflichtig)
            committed[rev] = System.nanoTime()
            Thread.sleep(80)
        }
        assertTrue(done.await(40, TimeUnit.SECONDS), "alle $total Revisionen wurden dargestellt: ${shown.size}")
        reader.shutdownNow(); loader.shutdownNow()
        // eine Revision kann durch eine spätere Darstellung (>=) mitabgedeckt sein; maßgeblich ist je Revision der erste Zeitpunkt, ab dem sie oder eine neuere dargestellt war
        val latencies = committed.keys.sorted().map { rev -> (shown.filterKeys { it >= rev }.values.minOrNull() ?: Long.MAX_VALUE) - committed.getValue(rev) }.map { maxOf(it, 0L) }
        val p95 = latencies.sorted()[latencies.size * 95 / 100 - 1]
        assertTrue(Duration.ofNanos(p95) <= Duration.ofSeconds(10), "p95 Commit bis dargestellte Clientrevision: ${Duration.ofNanos(p95).toMillis()} ms")
        assertTrue(stateOf.values.all { it == "NEW_UNCONFIRMED" }, "jede dargestellte Revision ist als bestätigungspflichtig gekennzeichnet: ${stateOf.values.toSet()}")
        println("M3-27 Messung Commit bis dargestellte Clientrevision über $total Planänderungen: p95 = ${Duration.ofNanos(p95).toMillis()} ms, Maximum = ${Duration.ofNanos(latencies.max()).toMillis()} ms")
    }

    @Test
    fun live_nachricht_enthaelt_keine_fahrgast_oder_ortsdaten() {
        val d = w.driver(); val a = w.booking(); w.plan(d.run, listOf(a))
        val cursor = env.get("/v1/driver/live/events?wait_seconds=0", d.token).text.lineSequence().first { it.startsWith("data: ") }.let { json.readTree(it.removePrefix("data: "))["cursor"].asString() }
        val rev = w.plan(d.run, listOf(a), base = w.t0.plusSeconds(3600))
        val r = env.get("/v1/driver/live/events?wait_seconds=0&after=$cursor", d.token)
        assertEquals(200, r.status, r.text)
        val data = r.text.lineSequence().filter { it.startsWith("data: ") && it.contains("dispatch.run_revised") }.toList().single()
        val e = json.readTree(data.removePrefix("data: "))
        assertEquals(rev, e["aggregate_revision"].asLong()); assertEquals(d.shift.toString(), e["shift_id"].asString())
        for (forbidden in listOf(a.id.toString(), "booking", "latitude", "longitude", "pickup", "dropoff", "stop_name", "passenger")) assertFalse(data.contains(forbidden, ignoreCase = true), "Live-Nachricht enthält $forbidden: $data")
    }

    @Test
    fun verlorener_push_abruf_liefert_neue_revision_und_bedingter_abruf() {
        val d = w.driver(); val a = w.booking(); val b = w.booking()
        val r1 = w.plan(d.run, listOf(a))
        val first = d.get(); assertEquals(200, first.status); assertEquals("\"$r1\"", first.header("ETag"))
        assertEquals("NEW_UNCONFIRMED", first.json["revision_state"].asString(), "nach dem Dienstantritt geänderter Plan ist zu bestätigen")
        assertEquals(200, d.get("\"$r1\"").status, "offene Bestätigung: nie 304")
        assertEquals(200, ack(d, r1).status); assertEquals(304, d.get("\"$r1\"").status, "bestätigt und unverändert: bedingter Abruf 304")
        // zwei Planänderungen, jede Benachrichtigung geht verloren: der nächste Abruf liefert die neueste Revision samt Kennzeichnung gegenüber der Basis
        w.plan(d.run, listOf(a, b), base = w.t0.plusSeconds(1800)); val r3 = w.plan(d.run, listOf(a, b), base = w.t0.plusSeconds(3600))
        val again = d.get("\"$r1\"")
        assertEquals(200, again.status, "veralteter ETag: voller Inhalt"); assertEquals("\"$r3\"", again.header("ETag"))
        assertEquals(r3, again.json["plan_revision"].asLong()); assertEquals("NEW_UNCONFIRMED", again.json["revision_state"].asString()); assertTrue(again.json["acknowledgement_required"].asBoolean())
        assertEquals(r1, again.json["base_revision"].asLong())
        val changes = again.json["stops"].values().asSequence().mapNotNull { it["change"].takeIf { c -> !c.isNull }?.asString() }.toSet()
        assertTrue("NEW" in changes, "die Buchung b ist neu: $changes")
        assertEquals(200, d.get("\"$r3\"").status, "solange die bestätigungspflichtige Änderung offen ist, antwortet der bedingte Abruf nie 304 (der Client behält die Pflicht nicht stillschweigend)")
        // nach der Bestätigung gilt der bedingte Abruf wieder
        assertEquals(200, ack(d, r3).status)
        assertEquals(304, d.get("\"$r3\"").status)
    }

    @Test
    fun bestaetigung_ueber_http_428_412_200_zwei_geraete_fremde_schicht_und_rolle() {
        val d = w.driver(); val a = w.booking(); val b = w.booking()
        val r1 = w.plan(d.run, listOf(a)); val r2 = w.plan(d.run, listOf(a, b), base = w.t0.plusSeconds(1800))
        assertEquals(428, ack(d, r2, ifMatch = null).status)
        val stale = ack(d, r1, ifMatch = "\"$r1\""); assertEquals(412, stale.status, stale.text); assertEquals("\"$r2\"", stale.header("ETag")); assertEquals("REVISION_MISMATCH", stale.json["code"].asString())
        assertEquals(422, ack(d, r2, ifMatch = "\"$r1\"").status, "If-Match und Body nennen verschiedene Revisionen")
        assertEquals(422, env.server.send("POST", "/v1/driver/shifts/${d.shift}/run/acknowledgements", """{"plan_revision":$r2,"acknowledged_at":"${java.time.Instant.now()}","tenant_id":"${w.tenant}"}""",
            env.headers(d.token, extra = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID(), "If-Match" to "\"$r2\""))).status, "tenant_id im Body ist kein Recht")
        val ok = ack(d, r2); assertEquals(200, ok.status, ok.text); assertEquals("\"$r2\"", ok.header("ETag"))
        assertEquals(r2, ok.json["acknowledged_revision"].asLong()); assertEquals("CONFIRMED", ok.json["revision_state"].asString()); assertFalse(ok.json["acknowledgement_required"].asBoolean())
        // zweites Gerät desselben Fahrers (anderer Idempotenzschlüssel): derselbe Zustand, kein zweiter Eintrag
        val second = ack(d, r2); assertEquals(200, second.status); assertEquals(1, env.count("SELECT count(*) FROM taktovia.driver_plan_ack WHERE tenant_id = ? AND shift_id = ?", w.tenant.value, d.shift))
        // Wiederholung mit demselben Schlüssel liefert dieselbe Antwort
        val key = "same-" + UUID.randomUUID(); val one = ack(d, r2, key = key); val two = ack(d, r2, key = key); assertEquals(one.json, two.json); assertEquals("true", two.header("Idempotent-Replayed"))
        // anderer Fahrer: 404; Fahrgast: 403
        val other = w.driver(); w.plan(other.run, listOf(w.booking()))
        assertEquals(404, ack(d, r2, token = other.token).status, "fremde Schicht")
        assertEquals(403, ack(d, r2, token = a.held.account.token).status, "Fahrgast")
        assertEquals(401, env.server.send("POST", "/v1/driver/shifts/${d.shift}/run/acknowledgements", "{}", mapOf("Idempotency-Key" to "x-" + UUID.randomUUID(), "If-Match" to "\"1\"")).status)
        // die Bestätigung des anderen Fahrers ändert nichts an diesem Fahrer
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.driver_plan_ack WHERE tenant_id = ? AND shift_id = ?", w.tenant.value, other.shift))
    }
}
