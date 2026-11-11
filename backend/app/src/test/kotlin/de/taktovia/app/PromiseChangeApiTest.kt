package de.taktovia.app

import de.taktovia.app.probe.ProbeDb
import de.taktovia.app.probe.ProbeServer
import de.taktovia.app.contract.ProbeContractCases
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** WP-M1-27 / LH-6.1-SYS-005 über die echte Anwendung: Änderungsverfahren mit If-Match, Grund und Recht, aktuelle Revision im ETag-geschützten Lesen. */
class PromiseChangeApiTest {
    private val s get() = ProbeServer.get()
    private val a = mapOf("X-Test-Tenant" to ProbeDb.tenantA.toString(), "X-Test-Actor" to "promise-test")
    private val b = mapOf("X-Test-Tenant" to ProbeDb.tenantB.toString(), "X-Test-Actor" to "promise-test")
    private val scope = mapOf("X-Test-Scopes" to "promise:change")
    private fun create(): Pair<String, String> { val r = s.post("/v1/probe/promises", "{}", a); assertEquals(201, r.status, r.text); return r.json["id"].asString() to r.header("ETag")!! }
    private fun change(id: String, etag: String?, body: String = ProbeContractCases.PROBE_CHANGE, h: Map<String, String> = a + scope) =
        s.post("/v1/probe/promises/$id/changes", body, h + (etag?.let { mapOf("If-Match" to it) } ?: emptyMap()))

    @Test fun aenderung_liefert_neue_revision_im_etag_geschuetzten_lesen() {
        val (id, etag) = create()
        val r = change(id, etag)
        assertEquals(200, r.status, r.text); assertEquals("\"2\"", r.header("ETag")); assertEquals(2, r.json["revision"].asInt())
        assertEquals("2026-12-01T08:10:00+01:00", r.json["content"]["pickup_earliest"].asString())
        val now = s.get("/v1/probe/promises/$id", a)
        assertEquals("\"2\"", now.header("ETag")); assertNotEquals(r.json["content_hash"].asString(), "")
    }

    @Test fun ohne_grund_422_ohne_recht_403_veraltet_412_ohne_etag_428_fremder_mandant_404() {
        val (id, etag) = create()
        val noReason = change(id, etag, ProbeContractCases.PROBE_CHANGE.replace("\"reason\":\"Umplanung\",", ""))
        assertEquals(422, noReason.status); assertEquals("/reason", noReason.json["violations"][0]["pointer"].asString())
        val blank = change(id, etag, ProbeContractCases.PROBE_CHANGE.replace("Umplanung", "   "))
        assertEquals(422, blank.status)
        assertEquals(403, change(id, etag, h = a).status)
        assertEquals(428, change(id, null).status)
        assertEquals(404, change(id, etag, h = b + scope).status)
        assertEquals("\"1\"", s.get("/v1/probe/promises/$id", a).header("ETag"))   // alles abgewiesen: Revision unverändert
        assertEquals(200, change(id, etag).status)
        val stale = change(id, etag, ProbeContractCases.PROBE_CHANGE.replace("08:10:00", "08:12:00"))
        assertEquals(412, stale.status); assertEquals("REVISION_MISMATCH", stale.json["code"].asString())
        assertEquals("\"2\"", s.get("/v1/probe/promises/$id", a).header("ETag"))
    }
}
