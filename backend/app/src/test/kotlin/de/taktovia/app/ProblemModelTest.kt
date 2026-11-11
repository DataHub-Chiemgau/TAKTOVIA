package de.taktovia.app

import de.taktovia.app.probe.ProbeDb
import de.taktovia.app.probe.ProbeServer
import de.taktovia.app.probe.Resp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** LH-6.17-INT-003: Feldfehler eindeutig ohne Auswertung freier Fehlermeldung erkennbar; Fehlermodell einheitlich. */
class ProblemModelTest {
    private val s get() = ProbeServer.get()
    private val tenant get() = mapOf("X-Test-Tenant" to ProbeDb.tenantA.toString(), "X-Test-Actor" to "problem-test")

    private fun violations(r: Resp) = r.json["violations"].values().map { it["pointer"].asString() to it["code"].asString() }

    @Test
    fun feldfehler_liefert_422_mit_pointer_und_code() {
        // positiv: gültiger Request
        val ok = s.post("/v1/probe/items", """{"name":"Alpha","price":{"amount_minor":1800,"currency":"EUR","exponent":2},"quantity":3}""", tenant)
        assertEquals(201, ok.status, ok.text)
        // Feldfehler: Client erkennt Feld und Fehlerart allein aus pointer und code
        val r = s.post("/v1/probe/items", """{"name":"","quantity":"drei","extra":1,"price":{"amount_minor":1800,"currency":"EUR","exponent":3}}""", tenant)
        assertEquals(422, r.status)
        assertEquals("application/problem+json", r.header("Content-Type")!!.substringBefore(';'))
        assertEquals("VALIDATION_FAILED", r.json["code"].asString())
        assertEquals("https://api.taktovia.example/problems/validation-failed", r.json["type"].asString())
        val v = violations(r)
        assertTrue("/name" to "TOO_SHORT" in v, v.toString())
        assertTrue("/quantity" to "TYPE_MISMATCH" in v, v.toString())
        assertTrue("/extra" to "UNKNOWN_FIELD" in v, v.toString())
        assertTrue("/price/exponent" to "EXPONENT_MISMATCH" in v, v.toString())
        // Pflichtfeld fehlt, Typfehler an der Wurzel, zu langer String
        assertEquals(listOf("/name" to "REQUIRED"), violations(s.post("/v1/probe/items", "{}", tenant)))
        assertEquals(listOf("/" to "TYPE_MISMATCH"), violations(s.post("/v1/probe/items", "[1]", tenant)))
        assertEquals(listOf("/name" to "TOO_LONG"), violations(s.post("/v1/probe/items", """{"name":"${"x".repeat(41)}"}""", tenant)))
        assertEquals(listOf("/quantity" to "ABOVE_MAXIMUM"), violations(s.post("/v1/probe/items", """{"name":"a","quantity":1001}""", tenant)))
        // die freie Meldung enthält keine Eingabewerte
        assertFalse(r.text.contains("drei"))
    }

    @Test
    fun geldfeldfehler_im_request_werden_je_feld_gemeldet() {
        fun v(price: String, field: String = "price") = violations(s.post("/v1/probe/items", """{"name":"a","$field":$price}""", tenant))
        assertEquals(listOf("/price/amount_minor" to "MONEY_NOT_INTEGER"), v("""{"amount_minor":18.5,"currency":"EUR","exponent":2}"""))
        assertEquals(listOf("/price/amount_minor" to "MONEY_NOT_INTEGER"), v("""{"amount_minor":1800.0,"currency":"EUR","exponent":2}"""))
        assertEquals(listOf("/price/amount_minor" to "MONEY_NEGATIVE"), v("""{"amount_minor":-5,"currency":"EUR","exponent":2}"""))
        assertEquals(listOf("/price/amount_minor" to "MONEY_OUT_OF_RANGE"), v("""{"amount_minor":9007199254740992,"currency":"EUR","exponent":2}"""))
        assertEquals(listOf("/price/currency" to "CURRENCY_UNKNOWN"), v("""{"amount_minor":1,"currency":"XXX","exponent":2}"""))
        assertEquals(listOf("/discount/amount_minor" to "MONEY_OUT_OF_RANGE"), v("""{"amount_minor":-9007199254740992,"currency":"EUR","exponent":2}""", "discount"))
        // SignedMoney darf negativ sein
        assertEquals(201, s.post("/v1/probe/items", """{"name":"a","discount":{"amount_minor":-500,"currency":"EUR","exponent":2}}""", tenant).status)
    }

    @Test
    fun alle_fehlerantworten_validieren_gegen_problem_schema() {
        val problem = s.registry.schema("Problem")
        val id = "00000000-0000-4000-8000-0000000000ff"
        val responses = listOf(
            s.post("/v1/probe/items", "{}", tenant),                                                              // 422
            s.post("/v1/probe/items", "{kaputt", tenant),                                                         // 400 Body
            s.send("POST", "/v1/probe/items", """{"name":"a"}""", tenant, contentType = "text/plain"),            // 415
            s.get("/v1/probe/items/$id", tenant),                                                                 // 404 nicht vorhanden
            s.get("/v1/probe/nicht-vorhanden", tenant),                                                           // 404 Route
            s.send("DELETE", "/v1/probe/items", null, tenant),                                                    // 405
            s.get("/v1/probe/admin", tenant),                                                                     // 403
            s.send("PUT", "/v1/probe/items/$id", """{"value":"x"}""", tenant),                                    // 428
            s.send("PUT", "/v1/probe/items/$id", """{"value":"x"}""", tenant + ("If-Match" to "\"1\"")),          // 404
            s.send("PUT", "/v1/probe/items/$id", """{"value":"x"}""", tenant + ("If-Match" to "7")),              // 422
            s.get("/v1/probe/items?limit=0", tenant),                                                             // 422
            s.get("/v1/probe/items?cursor=murks", tenant),                                                        // 400 Cursor
            s.get("/v1/probe/boom", tenant),                                                                      // 500
            s.post("/v1/probe/immutable", "", tenant),                                                            // 409 DB-Constraint
        )
        val expected = listOf(422, 400, 415, 404, 404, 405, 403, 428, 404, 422, 422, 400, 500, 409)
        assertEquals(expected, responses.map { it.status }, responses.joinToString("\n") { "${it.status} ${it.text}" })
        for (r in responses) {
            val errors = problem.validate(r.json)
            assertTrue(errors.isEmpty(), "Problem-Schema verletzt: $errors in ${r.text}")
            assertEquals("application/problem+json", r.header("Content-Type")!!.substringBefore(';'), r.text)
            assertEquals(r.status, r.json["status"].asInt())
            assertEquals(r.json["correlation_id"].asString(), r.header("X-Correlation-Id"))
            assertFalse(Regex("(?i)exception|stack|\\bat de\\.taktovia|select |SECRET|audit_event|public\\.|org\\.postgresql").containsMatchIn(r.text), "interne Details in: ${r.text}")
        }
    }

    @Test
    fun nicht_sichtbar_ist_identisch_zu_nicht_vorhanden_und_fehlender_scope_ist_403() {
        val created = s.post("/v1/probe/items", """{"name":"nur-fuer-a"}""", tenant)
        val id = created.json["id"].asString()
        val other = mapOf("X-Test-Tenant" to ProbeDb.tenantB.toString(), "X-Test-Actor" to "problem-test")
        val foreign = s.get("/v1/probe/items/$id", other)                                // existiert, aber in Mandant A
        val missing = s.get("/v1/probe/items/00000000-0000-4000-8000-0000000000fe", other)
        assertEquals(404, foreign.status)
        assertEquals(missing.status, foreign.status)
        fun strip(r: Resp) = r.json.properties().filter { it.key != "correlation_id" }.associate { it.key to it.value.toString() }
        assertEquals(strip(missing), strip(foreign), "404 für fremde Ressource darf sich nicht von 'nicht vorhanden' unterscheiden")
        assertFalse(foreign.text.contains("nur-fuer-a") || foreign.text.contains(id))
        // fehlender Funktionsscope: 403 ohne Daten
        val forbidden = s.get("/v1/probe/admin", tenant)
        assertEquals(403, forbidden.status)
        assertEquals("FORBIDDEN", forbidden.json["code"].asString())
        assertFalse(forbidden.json.has("violations"))
        assertEquals(200, s.get("/v1/probe/admin", tenant + ("X-Test-Scopes" to "probe:read probe:admin")).status)
    }

    @Test
    fun db_constraint_fehler_erscheinen_nie_roh() {
        // SQLSTATE 23514 (Trigger tenant_id_immutable) an der echten Tabelle legal_entity
        val r = s.post("/v1/probe/immutable", "", tenant)
        assertEquals(409, r.status, r.text)
        assertEquals("IMMUTABLE_RECORD", r.json["code"].asString())
        val low = r.text.lowercase()
        listOf("tenant_id is immutable", "append a correction", "legal_entity", "23514", "reject_mutation", "taktovia.legal", "taktovia.audit", "psql", "postgres").forEach {
            assertFalse(low.contains(it), "rohe Datenbankangabe '$it' in: ${r.text}")
        }
        // unerwartete Ausnahme: 500 ohne Text, Stack oder SQL
        val boom = s.get("/v1/probe/boom", tenant)
        assertEquals(500, boom.status)
        assertEquals("INTERNAL_ERROR", boom.json["code"].asString())
        assertFalse(boom.text.contains("SECRET") || boom.text.contains("audit_event") || boom.text.contains("de.taktovia"))
    }
}
