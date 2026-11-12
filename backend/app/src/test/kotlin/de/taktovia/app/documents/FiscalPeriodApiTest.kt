package de.taktovia.app.documents

import de.taktovia.app.contract.W11C5ContractCases
import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M4-36 über die echte Anwendung (PH-AT-015, E2E-073): Perioden anlegen, lesen und abschließen (eigenes Recht, kein Wiederöffnen auf irgendeinem Pfad), Idempotenz, Gegenereignis mit Ursprungsbezug in der
 * nächsten offenen Periode, `PERIOD_CLOSED`, Dublette `ALREADY_REVERSED`, 401/403/404.
 */
class FiscalPeriodApiTest {
    private val env = IdentityEnv
    private fun idem(key: String = "k-" + UUID.randomUUID()) = mapOf("Idempotency-Key" to key)
    private fun post(w: W11C5ContractCases.World, path: String, body: String, token: String = w.finance, key: String? = null) = env.server.send("POST", path, body, env.headers(token, extra = key?.let { idem(it) } ?: idem()))
    private fun period(w: W11C5ContractCases.World, from: String, to: String) = post(w, "/v1/fiscal-periods", """{"legal_entity_id":"${w.base.le}","starts_on":"$from","ends_on":"$to"}""")
    private fun count(w: W11C5ContractCases.World, sql: String) = env.tx.run(w.tenant) { j -> j.queryForObject(sql, Int::class.java)!! }

    @Test
    fun perioden_anlegen_lesen_ueberlappung_und_authentisierung() {
        val w = W11C5ContractCases.World()
        val sep = period(w, "2031-09-01", "2031-10-01"); assertEquals(201, sep.status, sep.text); assertEquals("OPEN", sep.json["state"].asString()); assertTrue(sep.json["closed_at"].isNull)
        assertEquals(201, period(w, "2031-10-01", "2031-11-01").status, "benachbart")
        val overlap = period(w, "2031-10-15", "2031-11-15"); assertEquals(409, overlap.status); assertEquals("CONFLICT", overlap.json["code"].asString())
        assertEquals(422, period(w, "2031-12-01", "2031-12-01").status); assertEquals(422, post(w, "/v1/fiscal-periods", """{"legal_entity_id":"${w.base.le}","starts_on":"morgen","ends_on":"2031-12-01"}""").status)
        assertEquals(404, post(w, "/v1/fiscal-periods", """{"legal_entity_id":"${UUID.randomUUID()}","starts_on":"2031-12-01","ends_on":"2032-01-01"}""").status)
        assertEquals(401, env.server.send("POST", "/v1/fiscal-periods", """{"legal_entity_id":"${w.base.le}","starts_on":"2032-01-01","ends_on":"2032-02-01"}""", idem()).status)
        val page = env.server.send("GET", "/v1/fiscal-periods?limit=1", null, env.headers(w.finance)); assertEquals(200, page.status, page.text)
        assertEquals(1, page.json["items"].size()); assertEquals("2026-01-01", page.json["items"][0]["starts_on"].asString(), "die Jahresperiode 2026 des Testmandanten kommt zuerst"); assertTrue(!page.json["next_cursor"].isNull)
        val next = env.server.send("GET", "/v1/fiscal-periods?limit=1&cursor=" + page.json["next_cursor"].asString(), null, env.headers(w.finance)); assertEquals("2031-09-01", next.json["items"][0]["starts_on"].asString())
        val driver = UUID.randomUUID(); env.grant(w.tenant, driver, "driver")
        assertEquals(403, env.server.send("GET", "/v1/fiscal-periods", null, env.headers(env.token(driver))).status)
        val foreign = env.server.send("GET", "/v1/fiscal-periods", null, env.headers(W11C5ContractCases.World().finance)).json["items"]
        assertEquals(1, foreign.size(), "der fremde Mandant sieht nur seine eigene Jahresperiode"); assertTrue(foreign[0]["legal_entity_id"].asString() != w.base.le.toString())
    }

    @Test
    fun abschluss_audit_idempotenz_und_kein_wiederoeffnen_auf_irgendeinem_pfad() {
        val w = W11C5ContractCases.World(); val sep = period(w, "2031-09-01", "2031-10-01").json["id"].asString()
        val driver = UUID.randomUUID(); env.grant(w.tenant, driver, "driver")
        assertEquals(403, post(w, "/v1/fiscal-periods/$sep/close", "", env.token(driver)).status)
        assertEquals(401, env.server.send("POST", "/v1/fiscal-periods/$sep/close", "", idem()).status)
        val key = "close-" + UUID.randomUUID()
        val closed = post(w, "/v1/fiscal-periods/$sep/close", "", key = key); assertEquals(200, closed.status, closed.text); assertEquals("CLOSED", closed.json["state"].asString()); assertTrue(!closed.json["closed_at"].isNull); assertTrue(!closed.json["closed_by"].isNull)
        // gleicher Schlüssel: gespeicherte Antwort; neuer Schlüssel auf die geschlossene Periode: 409 PERIOD_CLOSED; ein Auditeintrag mit Akteur und Zeit
        assertEquals(200, post(w, "/v1/fiscal-periods/$sep/close", "", key = key).status)
        val again = post(w, "/v1/fiscal-periods/$sep/close", ""); assertEquals(409, again.status); assertEquals("PERIOD_CLOSED", again.json["code"].asString())
        assertEquals(1, count(w, "SELECT count(*) FROM taktovia.audit_event WHERE action='finance.period.closed'"))
        // kein Pfad öffnet, ändert oder löscht eine geschlossene Periode
        for ((method, path) in listOf("PATCH" to "/v1/fiscal-periods/$sep", "PUT" to "/v1/fiscal-periods/$sep", "DELETE" to "/v1/fiscal-periods/$sep", "POST" to "/v1/fiscal-periods/$sep/reopen", "POST" to "/v1/fiscal-periods/$sep/open", "DELETE" to "/v1/fiscal-periods/$sep/close")) {
            val r = env.server.send(method, path, if (method == "DELETE") null else """{"closed_at":null}""", env.headers(w.finance, extra = idem() + mapOf("If-Match" to "\"1\"")))
            assertTrue(r.status in setOf(404, 405), "$method $path -> ${r.status}")
        }
        assertEquals(1, count(w, "SELECT count(*) FROM taktovia.fiscal_period WHERE closed_at IS NOT NULL"))
    }

    @Test
    fun gegenereignis_in_naechster_offener_periode_dublette_und_fehlerfaelle() {
        val w = W11C5ContractCases.World()
        // Perioden des Belegdatums (2026-06-15 aus dem Testbeleg: Jahresperiode 2026) und Folgeperiode 2027
        val e = w.postedEvent()
        val orig = env.get("/v1/cost-events?limit=100", w.allocatorToken).json["items"][0]
        assertEquals(e, orig["id"].asString()); assertEquals(10000L, orig["cost_basis_minor"].asLong())
        val next = period(w, "2027-01-01", "2028-01-01"); assertEquals(201, next.status, next.text)
        val yearPeriod = env.tx.run(w.tenant) { j -> j.queryForObject("SELECT id::text FROM taktovia.fiscal_period WHERE ends_on = DATE '2027-01-01'", String::class.java)!! }
        assertEquals(200, post(w, "/v1/fiscal-periods/$yearPeriod/close", "").status)
        val key = "rev-" + UUID.randomUUID()
        val body = """{"reason":"Gutschrift Lieferant","credit_gross_minor":2380,"accounting_date":"2026-12-01"}"""
        val r = post(w, "/v1/cost-events/$e/reversals", body, w.allocatorToken, key); assertEquals(201, r.status, r.text)
        assertEquals(-2380L, r.json["event"]["gross_minor"].asLong()); assertEquals(e, r.json["event"]["reverses_id"].asString()); assertEquals(next.json["id"].asString(), r.json["journal"]["period_id"].asString()); assertEquals("2027-01-01", r.json["journal"]["accounting_date"].asString())
        assertTrue(r.json["allocation"].isNull)
        // gleicher Schlüssel: gespeicherte Antwort, kein zweites Gegenereignis; neuer Schlüssel mit gleichem Betrag: 409 ALREADY_REVERSED
        assertEquals(201, post(w, "/v1/cost-events/$e/reversals", body, w.allocatorToken, key).status); assertEquals(2, count(w, "SELECT count(*) FROM taktovia.economic_event"))
        val dup = post(w, "/v1/cost-events/$e/reversals", body, w.allocatorToken); assertEquals(409, dup.status); assertEquals("ALREADY_REVERSED", dup.json["code"].asString())
        assertEquals(422, post(w, "/v1/cost-events/$e/reversals", """{"reason":"Zu viel","credit_gross_minor":9999999}""", w.allocatorToken).status)
        assertEquals(422, post(w, "/v1/cost-events/$e/reversals", """{"reason":"ab","credit_gross_minor":100}""", w.allocatorToken).status)
        assertEquals(422, post(w, "/v1/cost-events/$e/reversals", """{"reason":"Kommazahl","credit_gross_minor":10.5}""", w.allocatorToken).status)
        assertEquals(409, post(w, "/v1/cost-events/${r.json["event"]["id"].asString()}/reversals", """{"reason":"Rücknahme"}""", w.allocatorToken).status, "Gegenereignis eines Gegenereignisses")
        assertEquals(404, post(w, "/v1/cost-events/${UUID.randomUUID()}/reversals", """{"reason":"Unbekannt"}""", w.allocatorToken).status)
        val other = W11C5ContractCases.World(); assertEquals(404, post(other, "/v1/cost-events/$e/reversals", """{"reason":"Fremder Mandant"}""", other.allocatorToken).status)
        val driver = UUID.randomUUID(); env.grant(w.tenant, driver, "driver")
        assertEquals(403, post(w, "/v1/cost-events/$e/reversals", body, env.token(driver)).status); assertEquals(401, env.server.send("POST", "/v1/cost-events/$e/reversals", body, idem()).status)
        // die Gesamtansicht: der Ursprung bleibt unverändert, der Ist-Bericht enthält Ursprung und Korrektur
        val items = env.get("/v1/cost-events?limit=100", w.allocatorToken).json["items"]; assertEquals(2, items.size()); assertEquals(10000L - 2000L, (0 until 2).sumOf { items[it]["cost_basis_minor"].asLong() }, "Kostenbasis: 100,00 - 20,00 (23,80 brutto, 3,80 abziehbare Steuer)")
    }
}
