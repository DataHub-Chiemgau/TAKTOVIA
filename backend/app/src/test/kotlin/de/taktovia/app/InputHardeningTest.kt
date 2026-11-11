package de.taktovia.app

import de.taktovia.app.probe.ProbeDb
import de.taktovia.app.probe.ProbeServer
import de.taktovia.app.probe.Resp
import de.taktovia.app.web.SecurityHeaders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.Socket

/** LH-6.18-SEC-009: Standardangriffe werden serverseitig abgewiesen, Antworten enthalten keine internen Details. */
class InputHardeningTest {
    private val s get() = ProbeServer.get("taktovia.api.rate-limit.requests=100000")
    private val h get() = mapOf("X-Test-Tenant" to ProbeDb.tenantA.toString(), "X-Test-Actor" to "hardening-test")
    private fun v(r: Resp) = r.json["violations"].values().map { it["pointer"].asString() to it["code"].asString() }
    private val internal = Regex("(?i)exception|stack|\\bat de\\.taktovia|select |insert |SQL|postgres|org\\.|java\\.|apache tomcat|<html|/home/|\\.kt")

    private fun raw(request: String): String = Socket("127.0.0.1", s.port).use { sock ->
        sock.soTimeout = 5000
        sock.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1)); sock.getOutputStream().flush()
        sock.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
    }

    @Test
    fun sql_injection_in_pfad_query_und_header_wird_abgewiesen() {
        val id = s.post("/v1/probe/items", """{"name":"x"}""", h).json["id"].asString()
        // Pfadvariable muss eine UUID sein
        for (payload in listOf("'%20OR%20'1'='1", "$id'--", "%27%20UNION%20SELECT%20NULL--", "1%20OR%201=1")) {
            val r = s.get("/v1/probe/items/$payload", h)
            assertEquals(422, r.status, payload); assertEquals(listOf("/\$path/item_id"), v(r).map { it.first }, payload)
            assertFalse(internal.containsMatchIn(r.text.replace("api.taktovia.example", "")), r.text)
        }
        // Stapelanweisung über Pfadparameter (;) wird schon als Pfadmanipulation abgewiesen
        assertEquals(400, s.get("/v1/probe/items/1;DROP%20TABLE%20etag_probe", h).status)
        // Query: limit ist eine Ganzzahl 1-100
        for (payload in listOf("1;DROP%20TABLE%20x", "1%20OR%201=1", "%27", "1e2", "0x10", "99999999999999999999", "-1", "1.5")) {
            val r = s.get("/v1/probe/items?limit=$payload", h)
            assertEquals(422, r.status, payload); assertEquals("/\$query/limit", v(r).single().first, payload)
        }
        // unbekannte und doppelte Query-Parameter (Parameter Pollution), Name wird nicht reflektiert
        val unknown = s.get("/v1/probe/items?admin=true&%3Cscript%3Ealert(1)%3C/script%3E=1", h)
        assertEquals(422, unknown.status)
        assertEquals(setOf("/\$query/admin", "/\$query"), v(unknown).map { it.first }.toSet())
        assertFalse(unknown.text.contains("script"), unknown.text)
        assertEquals(listOf("/\$query/limit" to "NOT_UNIQUE"), v(s.get("/v1/probe/items?limit=1&limit=2", h)))
        // Header-Parameter mit Schema: zu langer, ungültiger If-Match-Wert
        assertEquals(422, s.send("PUT", "/v1/probe/items/$id", """{"value":"x"}""", h + ("If-Match" to "' OR 1=1--")).status)
        // Der Datenbestand ist unversehrt
        assertEquals(200, s.get("/v1/probe/items/$id", h).status)
    }

    @Test
    fun xss_und_html_in_freitext_bleiben_daten_und_werden_nie_als_html_ausgeliefert() {
        val payload = "<script>alert('xss')</script><img src=x onerror=alert(1)>"
        val created = s.post("/v1/probe/items", """{"name":"<b>a</b>"}""", h)
        assertEquals(201, created.status)
        val upd = s.send("PUT", "/v1/probe/items/${created.json["id"].asString()}", """{"value":"${payload.take(20).replace("'", "")}"}""", h + ("If-Match" to created.header("ETag")!!))
        assertEquals(200, upd.status, upd.text)
        // Antworten sind JSON mit nosniff: ein Browser rendert sie nie als HTML
        for (r in listOf(created, upd)) {
            assertEquals("application/json", r.header("Content-Type")!!.substringBefore(';'))
            assertEquals("nosniff", r.header("X-Content-Type-Options"))
            assertTrue(r.header("Content-Security-Policy")!!.contains("default-src 'none'"))
        }
        assertEquals("<b>a</b>", created.json["name"].asString(), "Freitext wird unverändert gespeichert und nur JSON-kodiert ausgegeben")
        // Ein Angriffsfeld im Schlüssel wird nicht in die Fehlerantwort gespiegelt
        val bad = s.post("/v1/probe/items", """{"name":"a","<script>alert(1)</script>":1}""", h)
        assertEquals(422, bad.status)
        assertEquals(listOf("/" to "UNKNOWN_FIELD"), v(bad))
        assertFalse(bad.text.contains("script"), bad.text)
        // Eingabe, die HTML im Header-Wert versucht
        assertEquals("application/problem+json", bad.header("Content-Type")!!.substringBefore(';'))
    }

    @Test
    fun mass_assignment_duplikate_steuerzeichen_und_trailing_tokens() {
        // Mass Assignment: unbekannte Felder (additionalProperties:false)
        for (field in listOf("is_admin", "tenant_id", "revision", "role")) {
            val r = s.post("/v1/probe/items", """{"name":"a","$field":"x"}""", h)
            assertEquals(422, r.status, field); assertEquals(listOf("/$field" to "UNKNOWN_FIELD"), v(r))
        }
        assertEquals(422, s.send("PUT", "/v1/probe/items/${java.util.UUID.randomUUID()}", """{"value":"a","revision":99}""", h + ("If-Match" to "\"1\"")).status)
        // doppelte Schlüssel: „letzter gewinnt“ wäre ein Umgehungsweg
        val dup = s.post("/v1/probe/items", """{"name":"gut","name":"boese"}""", h)
        assertEquals(400, dup.status); assertEquals("BAD_REQUEST", dup.json["code"].asString())
        // Text nach dem JSON-Wert
        assertEquals(400, s.post("/v1/probe/items", """{"name":"a"} {"name":"b"}""", h).status)
        assertEquals(400, s.post("/v1/probe/items", """{"name":"a"}garbage""", h).status)
        // NUL und andere Steuerzeichen in Werten und Schlüsseln
        for (bad in listOf("""{"name":"a\u0000b"}""", """{"name":"a\u0007"}""", """{"name":"a\u001b[31m"}""")) {
            val r = s.post("/v1/probe/items", bad, h)
            assertEquals(422, r.status, bad); assertEquals(listOf("/name" to "INVALID_VALUE"), v(r))
        }
        assertEquals(422, s.post("/v1/probe/free", """{"a\u0000":1}""", h).status)
        // Tab und Zeilenumbruch bleiben in Freitext zulässig
        assertEquals(201, s.post("/v1/probe/items", """{"name":"zeile1\nzeile2\tx"}""", h).status)
        // Typverwechslung: Objekt/Array/Zahl statt String, null
        for (bad in listOf("""{"name":{"${'$'}ne":""}}""", """{"name":["a"]}""", """{"name":1}""", """{"name":null}""", """{"name":true}"""))
            assertEquals(422, s.post("/v1/probe/items", bad, h).status, bad)
    }

    @Test
    fun pfadmanipulation_wird_abgewiesen() {
        val bad = listOf("/v1/probe/items;jsessionid=1", "/v1/probe/items/..%2f..%2fadmin", "/v1/probe/%2e%2e/admin", "/v1/probe/%2E%2E/admin", "/v1//probe/items",
            "/v1/probe/items/%00", "/v1/probe/items%5c..%5cadmin", "/v1/probe/./items", "/v1/probe/../probe/items", "/v1/probe/items%3bx=y", "/v1/probe/items/%252e%252e")
        for (p in bad) {
            val r = s.get(p, h)
            assertEquals(400, r.status, "$p -> ${r.text}")
            assertEquals("application/problem+json", r.header("Content-Type")!!.substringBefore(';'), p)
            assertFalse(internal.containsMatchIn(r.text), r.text)
        }
        // Kontrolle: der unveränderte Pfad funktioniert
        assertEquals(200, s.get("/v1/probe/items", h).status)
    }

    @Test
    fun header_injektion_wird_abgewiesen_und_nie_gespiegelt() {
        // LF und CR LF in Headerwerten: der Container weist ab, die Antwort ist ein Problem ohne Containertext
        for (inj in listOf("a\r\nSet-Cookie: pwned=1", "a\nSet-Cookie: pwned=1", "a\u0000b")) {
            val resp = raw("GET /v1/probe/items HTTP/1.1\r\nHost: localhost\r\nX-Test-Tenant: ${ProbeDb.tenantA}\r\nX-Test-Actor: $inj\r\nConnection: close\r\n\r\n")
            val head = resp.substringBefore("\r\n\r\n").lowercase()
            assertFalse(head.contains("set-cookie: pwned"), "injizierter Header in der Antwort: $head")
            assertTrue(resp.startsWith("HTTP/1.1 400") || resp.startsWith("HTTP/1.1 200"), resp.take(120))
            if (resp.startsWith("HTTP/1.1 400")) {
                assertTrue(head.contains("application/problem+json"), head)
                assertFalse(Regex("(?i)apache|tomcat|<html").containsMatchIn(resp), resp)
            }
        }
        // zu großer Header: 4xx als Problem
        val big = raw("GET /v1/probe/items HTTP/1.1\r\nHost: localhost\r\nX-Test-Tenant: ${ProbeDb.tenantA}\r\nX-Big: ${"a".repeat(70_000)}\r\nConnection: close\r\n\r\n")
        assertTrue(Regex("^HTTP/1.1 (400|431)").containsMatchIn(big), big.take(100))
        assertTrue(big.contains("application/problem+json"), big.take(400))
        // keine Anfrageheader in der Antwort: weder Host noch Actor noch traceparent werden gespiegelt
        val r = s.get("/v1/probe/items", h + ("traceparent" to "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01") + ("X-Test-Evil" to "SPIEGEL-MICH"))
        assertFalse(r.headers.values.flatten().any { it.contains("SPIEGEL-MICH") } || r.text.contains("SPIEGEL-MICH"))
        assertNull(r.header("Server"), "kein Server-Header mit Produktname/Version")
        assertNull(r.header("X-Powered-By"))
    }

    @Test
    fun falscher_content_type_und_formular_csrf_vektor() {
        for (ct in listOf("text/plain", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x", "text/html", "application/xml", "application/json; charset=utf-16", "application/jsonx")) {
            val r = s.send("POST", "/v1/probe/items", """{"name":"a"}""", h, contentType = ct)
            assertEquals(415, r.status, ct); assertEquals("UNSUPPORTED_MEDIA_TYPE", r.json["code"].asString())
        }
        // Body ohne Content-Type
        assertEquals(415, s.send("POST", "/v1/probe/items", """{"name":"a"}""", h, contentType = null).status)
    }

    @Test
    fun abweisungen_sind_problem_json_ohne_interne_details() {
        val problem = s.registry.schema("Problem")
        val responses = listOf(
            s.get("/v1/probe/items/'%20OR%201=1", h), s.get("/v1/probe/items?limit=abc", h), s.post("/v1/probe/items", "{kaputt", h), s.post("/v1/probe/items", """{"name":"a\u0000"}""", h),
            s.send("POST", "/v1/probe/items", "{}", h, contentType = "text/plain"), s.get("/v1/probe/items;x", h), s.send("POST", "/v1/probe/items", """{"name":"a","pad":"${"x".repeat(1_100_000)}"}""", h),
            s.post("/v1/probe/free", "[".repeat(100) + "]".repeat(100), h), s.get("/v1/probe/gibtsnicht", h), s.send("TRACE", "/v1/probe/items", null, h), s.send("PATCH", "/v1/probe/items", "{}", h),
        )
        for (r in responses) {
            assertTrue(r.status in 400..499, "${r.status} ${r.text.take(200)}")
            assertEquals("application/problem+json", r.header("Content-Type")!!.substringBefore(';'), r.text.take(200))
            assertEquals(emptyList<Any>(), problem.validate(r.json), r.text)
            assertFalse(internal.containsMatchIn(r.text.replace("https://api.taktovia.example/problems/", "")), "interne Details in: ${r.text}")
        }
    }

    @Test
    fun sicherheitsheader_stehen_an_jeder_antwort_auch_an_fehlern_des_containers() {
        val ok = s.get("/v1/probe/items", h)
        val notFound = s.get("/v1/probe/gibtsnicht", h)
        val invalid = s.post("/v1/probe/items", "{}", h)
        val rateLimitedOrNot = s.get("/actuator/health")
        val container = raw("GET /v1/probe/items HTTP/1.1\r\nHost: localhost\r\nX-Test-Actor: a\nb\r\nConnection: close\r\n\r\n")
        for ((name, r) in listOf("200" to ok, "404" to notFound, "422" to invalid, "health" to rateLimitedOrNot)) for ((k, v) in SecurityHeaders.REQUIRED)
            assertEquals(v, r.header(k), "$name: Header $k")
        assertTrue(container.startsWith("HTTP/1.1 400"), container.take(100))
        val head = container.substringBefore("\r\n\r\n").lowercase()
        for ((k, v) in SecurityHeaders.REQUIRED) assertTrue(head.contains("${k.lowercase()}: ${v.lowercase()}"), "Containerfehler ohne $k")
        assertEquals(setOf("X-Content-Type-Options", "Cache-Control", "Content-Security-Policy", "X-Frame-Options", "Referrer-Policy", "Strict-Transport-Security", "Permissions-Policy", "Cross-Origin-Resource-Policy"),
            SecurityHeaders.REQUIRED.keys, "Pflicht-Header-Liste laut Detailvertrag")
    }
}
