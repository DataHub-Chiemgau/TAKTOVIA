package de.taktovia.app

import de.taktovia.app.probe.ProbeDb
import de.taktovia.app.probe.ProbeServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** ETag und If-Match (Kap. 05 §5.1): 428 ohne Header, 412 bei veralteter Revision, nie stilles Überschreiben. */
class EtagTest {
    private val s get() = ProbeServer.get()
    private val tenant get() = mapOf("X-Test-Tenant" to ProbeDb.tenantA.toString(), "X-Test-Actor" to "etag-test")
    private fun create(): Pair<String, String> {
        val r = s.post("/v1/probe/items", """{"name":"etag"}""", tenant)
        assertEquals(201, r.status, r.text)
        assertEquals("\"1\"", r.header("ETag"))
        return r.json["id"].asString() to r.header("ETag")!!
    }

    @Test
    fun fehlender_if_match_428_veraltet_412() {
        val (id, etag) = create()
        // fehlender If-Match
        val missing = s.send("PUT", "/v1/probe/items/$id", """{"value":"neu"}""", tenant)
        assertEquals(428, missing.status); assertEquals("PRECONDITION_REQUIRED", missing.json["code"].asString())
        // aktuelle Revision: Erfolg, neuer ETag
        val ok = s.send("PUT", "/v1/probe/items/$id", """{"value":"eins"}""", tenant + ("If-Match" to etag))
        assertEquals(200, ok.status, ok.text); assertEquals("\"2\"", ok.header("ETag")); assertEquals("eins", ok.json["value"].asString())
        // veraltete Revision: 412, nichts überschrieben
        val stale = s.send("PUT", "/v1/probe/items/$id", """{"value":"zwei"}""", tenant + ("If-Match" to etag))
        assertEquals(412, stale.status); assertEquals("REVISION_MISMATCH", stale.json["code"].asString())
        val now = s.get("/v1/probe/items/$id", tenant)
        assertEquals("eins", now.json["value"].asString()); assertEquals("\"2\"", now.header("ETag"))
        // ungültiger If-Match (schwach, *, Text): 422 mit Feldverletzung im Header
        listOf("W/\"2\"", "*", "2").forEach {
            val bad = s.send("PUT", "/v1/probe/items/$id", """{"value":"x"}""", tenant + ("If-Match" to it))
            assertEquals(422, bad.status, it)
            assertEquals("/\$header/If-Match", bad.json["violations"][0]["pointer"].asString())
        }
    }

    @Test
    fun paralleles_update_gleiche_revision_genau_ein_412() {
        val (id, etag) = create()
        val n = 8
        val pool = Executors.newFixedThreadPool(n)
        val start = CountDownLatch(1)
        try {
            val futures = (1..n).map { i ->
                pool.submit<Int> {
                    start.await()
                    s.send("PUT", "/v1/probe/items/$id", """{"value":"w$i"}""", tenant + ("If-Match" to etag)).status
                }
            }
            start.countDown()
            val statuses = futures.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, statuses.count { it == 200 }, "genau ein Update gewinnt: $statuses")
            assertEquals(n - 1, statuses.count { it == 412 }, "alle anderen erhalten 412: $statuses")
        } finally { pool.shutdownNow() }
        // Die Verlierer haben keine Teilwirkung hinterlassen: Revision 2, genau ein Wert.
        val final = s.get("/v1/probe/items/$id", tenant)
        assertEquals("\"2\"", final.header("ETag"))
        assertNotEquals("w0", final.json["value"].asString())
        assertEquals(2L, final.json["revision"].asLong())
    }
}
