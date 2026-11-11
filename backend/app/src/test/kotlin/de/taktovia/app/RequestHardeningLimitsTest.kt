package de.taktovia.app

import de.taktovia.app.probe.ProbeDb
import de.taktovia.app.probe.ProbeServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Grenzen und Content-Type für schreibende Requests (OP-20-Planungsdefaults: 1 MiB, Tiefe 32). */
class RequestHardeningLimitsTest {
    private val s get() = ProbeServer.get()
    private val h get() = mapOf("X-Test-Tenant" to ProbeDb.tenantA.toString(), "X-Test-Actor" to "limits-test")

    @Test
    fun content_type_pflicht_fuer_schreibende_requests() {
        assertEquals(415, s.send("POST", "/v1/probe/items", """{"name":"a"}""", h, contentType = null).status)
        assertEquals(415, s.send("POST", "/v1/probe/items", """{"name":"a"}""", h, contentType = "text/plain").status)
        assertEquals(415, s.send("POST", "/v1/probe/items", """{"name":"a"}""", h, contentType = "application/xml").status)
        assertEquals(415, s.send("POST", "/v1/probe/free", """{"a":1}""", h, contentType = "application/x-www-form-urlencoded").status)
        assertEquals(201, s.send("POST", "/v1/probe/items", """{"name":"a"}""", h, contentType = "application/json; charset=utf-8").status)
    }

    @Test
    fun uebergrosser_und_tief_verschachtelter_body_werden_abgewiesen() {
        val big = """{"name":"a","pad":"${"x".repeat(1_100_000)}"}"""
        val r = s.send("POST", "/v1/probe/items", big, h)
        assertEquals(413, r.status); assertEquals("PAYLOAD_TOO_LARGE", r.json["code"].asString())
        val deep = "[".repeat(40) + "]".repeat(40)
        val d = s.send("POST", "/v1/probe/free", deep, h)
        assertEquals(413, d.status, d.text)
        val ok = "[".repeat(10) + "]".repeat(10)
        // freier Endpunkt ohne Body-Schema verlangt trotzdem ein JSON-Objekt für die Map; Tiefe 10 ist zulässig, daher kein 413
        assert(s.send("POST", "/v1/probe/free", "{\"a\":$ok}", h).status != 413)
    }

    @Test
    fun kaputtes_json_ergibt_400_ohne_parserdetails() {
        val r = s.send("POST", "/v1/probe/items", "{\"name\":", h)
        assertEquals(400, r.status); assertEquals("BAD_REQUEST", r.json["code"].asString())
        assertEquals(false, r.text.contains("Unexpected") || r.text.contains("jackson"))
    }
}
