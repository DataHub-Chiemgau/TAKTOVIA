package de.taktovia.app

import de.taktovia.app.probe.ProbeDb
import de.taktovia.app.probe.ProbeServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** LH-6.18-SEC-009: CORS-Whitelist und CSRF-Schutz für Browserflüsse (Origin und Fetch-Metadata). */
class CorsCsrfTest {
    private val allowed = "https://app.taktovia.example"
    private val s get() = ProbeServer.get("taktovia.security.cors.allowed-origins=$allowed,https://ops.taktovia.example:8443", "taktovia.api.rate-limit.requests=100000")
    private val h get() = mapOf("X-Test-Tenant" to ProbeDb.tenantA.toString(), "X-Test-Actor" to "cors-test")

    @Test
    fun fremde_origin_wird_abgewiesen_freigegebene_erhaelt_cors_header() {
        for (bad in listOf("https://evil.example", "http://app.taktovia.example", "https://app.taktovia.example.evil.example", "null", "*", "https://app.taktovia.example:444", "https://ops.taktovia.example")) {
            val r = s.get("/v1/probe/items", h + ("Origin" to bad))
            assertEquals(403, r.status, bad); assertEquals("FORBIDDEN", r.json["code"].asString())
            assertNull(r.header("Access-Control-Allow-Origin"), bad)
        }
        for (ok in listOf(allowed, "https://APP.taktovia.example", "https://ops.taktovia.example:8443")) {
            val r = s.get("/v1/probe/items", h + ("Origin" to ok))
            assertEquals(200, r.status, ok)
            assertEquals(ok.lowercase(), r.header("Access-Control-Allow-Origin"))
            assertEquals("Origin", r.header("Vary"))
            assertNull(r.header("Access-Control-Allow-Credentials"), "keine Cookies/Anmeldedaten über CORS")
            assertTrue(r.header("Access-Control-Expose-Headers")!!.contains("ETag"))
        }
        // ohne Origin (Server-zu-Server) kein CORS-Header
        assertNull(s.get("/v1/probe/items", h).header("Access-Control-Allow-Origin"))
    }

    @Test
    fun preflight_nur_fuer_freigegebene_herkunft_methoden_und_header() {
        val pre = h + ("Origin" to allowed) + ("Access-Control-Request-Method" to "POST") + ("Access-Control-Request-Headers" to "content-type, authorization, idempotency-key, if-match")
        val ok = s.send("OPTIONS", "/v1/probe/items", null, pre)
        assertEquals(204, ok.status)
        assertEquals(allowed, ok.header("Access-Control-Allow-Origin"))
        assertTrue(ok.header("Access-Control-Allow-Methods")!!.contains("PUT"))
        assertEquals("600", ok.header("Access-Control-Max-Age"))
        // fremder Header, fremde Methode, fremde Herkunft
        assertEquals(403, s.send("OPTIONS", "/v1/probe/items", null, pre + ("Access-Control-Request-Headers" to "x-evil")).status)
        assertEquals(403, s.send("OPTIONS", "/v1/probe/items", null, pre + ("Access-Control-Request-Method" to "TRACE")).status)
        val evil = s.send("OPTIONS", "/v1/probe/items", null, pre + ("Origin" to "https://evil.example"))
        assertEquals(403, evil.status); assertNull(evil.header("Access-Control-Allow-Origin"))
    }

    @Test
    fun csrf_zustandsaendernde_cross_site_anfragen_werden_abgewiesen() {
        val body = """{"name":"csrf"}"""
        // fremde Herkunft: 403, nichts angelegt
        val before = s.get("/v1/probe/items?limit=100", h).json["items"].values().count { it["name"].asString() == "csrf" }
        assertEquals(403, s.post("/v1/probe/items", body, h + ("Origin" to "https://evil.example") + ("Sec-Fetch-Site" to "cross-site")).status)
        // Browser ohne Origin, aber als cross-site/same-site gekennzeichnet (Fetch-Metadata)
        assertEquals(403, s.post("/v1/probe/items", body, h + ("Sec-Fetch-Site" to "cross-site")).status)
        assertEquals(403, s.post("/v1/probe/items", body, h + ("Sec-Fetch-Site" to "same-site")).status)
        // Formular-CSRF: einfacher Content-Type scheitert am JSON-Zwang
        assertEquals(415, s.send("POST", "/v1/probe/items", "name=csrf", h + ("Sec-Fetch-Site" to "same-origin"), contentType = "application/x-www-form-urlencoded").status)
        assertEquals(415, s.send("POST", "/v1/probe/items", body, h, contentType = "text/plain").status)
        val after = s.get("/v1/probe/items?limit=100", h).json["items"].values().count { it["name"].asString() == "csrf" }
        assertEquals(before, after, "keine der abgewiesenen Anfragen hat etwas angelegt")
        // Gegenprobe: freigegebene Herkunft oder same-origin/none ist zulässig
        assertEquals(201, s.post("/v1/probe/items", body, h + ("Origin" to allowed) + ("Sec-Fetch-Site" to "cross-site")).status)
        assertEquals(201, s.post("/v1/probe/items", body, h + ("Sec-Fetch-Site" to "same-origin")).status)
        assertEquals(201, s.post("/v1/probe/items", body, h + ("Sec-Fetch-Site" to "none")).status)
        assertFalse(after < 0)
    }
}
