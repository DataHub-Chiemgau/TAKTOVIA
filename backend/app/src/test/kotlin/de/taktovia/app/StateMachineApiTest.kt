package de.taktovia.app

import de.taktovia.app.probe.ProbeDb
import de.taktovia.app.probe.ProbeServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WP-M1-17 / LH-6.1-SYS-007 über die echte Anwendung (Filterkette, Runtime-Rolle, echte Datenbank): ein per API angeforderter, nicht erlaubter Übergang
 * wird mit 409 und stabilem `code` abgewiesen, der Zustand bleibt, der aktuelle Zustand steht im ETag-geschützten Lesen.
 */
class StateMachineApiTest {
    private val s get() = ProbeServer.get()
    private val a = mapOf("X-Test-Tenant" to ProbeDb.tenantA.toString(), "X-Test-Actor" to "sm-test")
    private val b = mapOf("X-Test-Tenant" to ProbeDb.tenantB.toString(), "X-Test-Actor" to "sm-test")
    private val scope = mapOf("X-Test-Scopes" to "probe:payments:transition")
    private fun create(): Pair<String, String> { val r = s.post("/v1/probe/payments", "{}", a); assertEquals(201, r.status, r.text); return r.json["id"].asString() to r.header("ETag")!! }
    private fun move(id: String, state: String, etag: String?, h: Map<String, String> = a + scope) =
        s.post("/v1/probe/payments/$id/transitions", """{"state":"$state"}""", h + (etag?.let { mapOf("If-Match" to it) } ?: emptyMap()))

    @Test fun unerlaubter_uebergang_409_mit_stabilem_code_zustand_unveraendert() {
        val (id, etag) = create()
        val r = move(id, "CAPTURED", etag)   // CREATED -> CAPTURED gibt es nicht
        assertEquals(409, r.status); assertEquals("INVALID_STATE_TRANSITION", r.json["code"].asString())
        assertTrue(r.header("Content-Type")!!.startsWith("application/problem+json"))
        val now = s.get("/v1/probe/payments/$id", a)
        assertEquals("CREATED", now.json["state"].asString()); assertEquals(etag, now.header("ETag"))   // Revision unverändert
        val ok = move(id, "AUTHORIZED", etag)
        assertEquals(200, ok.status, ok.text); assertEquals("AUTHORIZED", ok.json["state"].asString()); assertEquals("\"2\"", ok.header("ETag"))
        assertEquals("\"2\"", s.get("/v1/probe/payments/$id", a).header("ETag"))
    }

    @Test fun veraltete_revision_412_fehlende_428_unbekannter_zustand_422_fremder_mandant_404_ohne_recht_403() {
        val (id, etag) = create()
        assertEquals(200, move(id, "REQUIRES_ACTION", etag).status)
        assertEquals(412, move(id, "AUTHORIZED", etag).status.also { }); assertEquals("REVISION_MISMATCH", move(id, "AUTHORIZED", etag).json["code"].asString())
        val missing = move(id, "AUTHORIZED", null); assertEquals(428, missing.status); assertEquals("PRECONDITION_REQUIRED", missing.json["code"].asString())
        val unknown = move(id, "GIBT_ES_NICHT", "\"2\""); assertEquals(422, unknown.status); assertEquals("/state", unknown.json["violations"][0]["pointer"].asString())
        val other = move(id, "AUTHORIZED", "\"2\"", b + scope); assertEquals(404, other.status); assertEquals("NOT_FOUND", other.json["code"].asString())
        val denied = move(id, "AUTHORIZED", "\"2\"", a); assertEquals(403, denied.status)
        assertEquals("REQUIRES_ACTION", s.get("/v1/probe/payments/$id", a).json["state"].asString())
    }
}
