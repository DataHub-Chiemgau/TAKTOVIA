package de.taktovia.app.documents

import de.taktovia.app.contract.W12C8ContractCases
import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M4-39 und WP-M4-40 über die echte Anwendung: Kostenklassifikation (Pflichtangaben 422, 401/403/404, Kreuztabelle nach dem echten Freigabeweg eines Tankbelegs), Umklassifikation, manuelles
 * Fahrzeugkostenereignis über HTTP (Rollentrennung 403, Vier-Augen-Schwelle, Idempotenz, ETag/If-Match) und die Monatsauswertung des Fahrzeugs.
 */
class CostClassificationApiTest {
    private val env = IdentityEnv
    private fun idem(key: String = "k-" + UUID.randomUUID()) = mapOf("Idempotency-Key" to key)
    private fun im(rev: Long) = mapOf("If-Match" to "\"$rev\"")
    private fun get(path: String, token: String) = env.server.send("GET", path, null, env.headers(token))
    private fun post(path: String, body: String, token: String, extra: Map<String, String> = idem()) = env.server.send("POST", path, body, env.headers(token, extra = extra))
    private val q = "valuation=ACTUAL&currency=EUR&period_from=2026-01-01&period_to=2027-01-01"

    @Test
    fun klassifikation_pflichtangaben_rechte_und_kreuztabelle() {
        val w = W12C8ContractCases.World(); val other = W12C8ContractCases.World()
        // ohne Authentisierung 401, ohne Leserecht 403
        assertEquals(401, env.server.send("GET", "/v1/cost-events/classification?$q", null, emptyMap()).status)
        val driver = UUID.randomUUID(); env.grant(w.tenant, driver, "driver")
        assertEquals(403, get("/v1/cost-events/classification?$q", env.token(driver)).status)
        // Pflichtangaben: Bewertungsart, Periode, Währung; für P1 nur ACTUAL
        for (query in listOf("currency=EUR&period_from=2026-01-01&period_to=2027-01-01", "valuation=ACTUAL&period_from=2026-01-01&period_to=2027-01-01", "valuation=ACTUAL&currency=EUR&period_to=2027-01-01",
            "valuation=ACTUAL&currency=EUR&period_from=2026-01-01", "valuation=PLAN&currency=EUR&period_from=2026-01-01&period_to=2027-01-01", "valuation=ACTUAL&currency=EUR&period_from=2027-01-01&period_to=2026-01-01")) {
            val r = get("/v1/cost-events/classification?$query", w.finance); assertEquals(422, r.status, "$query -> ${r.text}"); assertEquals("VALIDATION_FAILED", r.json["code"].asString())
        }
        assertEquals(422, get("/v1/cost-events/classification?valuation=ACTUAL&currency=EUR&period_from=gestern&period_to=2027-01-01", w.finance).status)
        // fremdes Fahrzeug: 404
        assertEquals(404, get("/v1/cost-events/classification?$q&vehicle_id=${other.base.vehicle}", w.finance).status)
        // leere Auswertung ist 200
        val empty = get("/v1/cost-events/classification?$q", w.finance); assertEquals(200, empty.status, empty.text); assertEquals(0, empty.json["totals"]["total_minor"].asLong()); assertEquals(0, empty.json["rows"].size())
        // gebuchter Tankbeleg (Kostenbasis 100,00 EUR, Kostenart FUEL: variabel, direkt am Fahrzeug)
        val event = W12C8ContractCases.postedFuelEvent(w)
        val s = get("/v1/cost-events/classification?$q&vehicle_id=${w.base.vehicle}", w.finance); assertEquals(200, s.status, s.text)
        assertEquals("ACTUAL", s.json["valuation"].asString()); assertEquals("EUR", s.json["currency"].asString()); assertEquals("2026-01-01", s.json["period_from"].asString()); assertEquals("ACCOUNTING_DATE", s.json["period_basis"].asString())
        assertTrue(s.json["as_of"].asString().isNotBlank())
        val t = s.json["totals"]; assertEquals(10000, t["total_minor"].asLong()); assertEquals(10000, t["variable_minor"].asLong()); assertEquals(0, t["fixed_minor"].asLong()); assertEquals(10000, t["direct_minor"].asLong()); assertEquals(0, t["unclassified_minor"].asLong())
        assertEquals(w.base.vehicle.toString(), s.json["rows"][0]["vehicle_id"].asString())
        assertEquals("VARIABLE", s.json["rows"][0]["cells"][0]["cost_behavior"].asString())
        // der Fremdmandant sieht keine fremden Kosten
        assertEquals(0, get("/v1/cost-events/classification?$q", other.finance).json["totals"]["total_minor"].asLong())

        // Umklassifikation über HTTP: Rechte, Gegenereignis plus Neubuchung, zweiter Versuch 409, Summe bleibt
        val body = """{"cost_behavior":"FIXED","attribution":"ALLOCATED","reason":"Pauschale ist fix und umgelegt","accounting_date":"2026-06-20"}"""
        assertEquals(403, post("/v1/cost-events/$event/reclassifications", body, env.token(driver)).status)
        assertEquals(404, post("/v1/cost-events/$event/reclassifications", body, other.allocator).status, "Ereignis eines anderen Mandanten")
        val r = post("/v1/cost-events/$event/reclassifications", body, w.allocator); assertEquals(201, r.status, r.text)
        assertEquals(event, r.json["reversal"]["reverses_id"].asString()); assertEquals(event, r.json["rebooking"]["reclassified_from_id"].asString()); assertEquals("FIXED", r.json["rebooking"]["cost_behavior"].asString())
        assertEquals("VARIABLE", r.json["reversal"]["cost_behavior"].asString(), "das Gegenereignis trägt die Klassifikation des Ursprungs")
        assertEquals(409, post("/v1/cost-events/$event/reclassifications", body, w.allocator).status)
        val after = get("/v1/cost-events/classification?$q&vehicle_id=${w.base.vehicle}", w.finance).json["totals"]
        assertEquals(10000, after["total_minor"].asLong()); assertEquals(10000, after["fixed_minor"].asLong()); assertEquals(0, after["variable_minor"].asLong()); assertEquals(10000, after["allocated_minor"].asLong()); assertEquals(0, after["direct_minor"].asLong())
        assertEquals(422, post("/v1/cost-events/$event/reclassifications", """{"cost_behavior":"SEMI","attribution":"DIRECT","reason":"falsch"}""", w.allocator).status)
    }

    @Test
    fun manuelles_kostenereignis_ueber_http_rollentrennung_schwelle_und_fahrzeugauswertung() {
        val w = W12C8ContractCases.World(); val other = W12C8ContractCases.World()
        val key = "ursprung-" + UUID.randomUUID()
        val body = w.manualBody(key)
        assertEquals(401, env.server.send("POST", "/v1/cost-events", body, idem()).status)
        val driver = UUID.randomUUID(); env.grant(w.tenant, driver, "driver")
        assertEquals(403, post("/v1/cost-events", body, env.token(driver)).status)
        assertEquals(404, post("/v1/cost-events", other.manualBody().replace(other.base.vehicle.toString(), w.base.vehicle.toString()), other.finance).status, "Fahrzeug eines anderen Mandanten")
        // Anlegen: 201, ETag; dasselbe Ereignis (neuer Idempotency-Key, gleicher Ursprung) liefert den Vorgang mit 200; anderer Inhalt 409
        val c = post("/v1/cost-events", body, w.finance); assertEquals(201, c.status, c.text)
        assertEquals("IN_REVIEW", c.json["state"].asString()); assertEquals(1, c.json["revision"].asLong()); assertEquals("CAPTURE", c.json["steps"][0]["step"].asString()); assertEquals(50000, c.json["gross_minor"].asLong())
        val id = c.json["id"].asString()
        val again = post("/v1/cost-events", body, w.finance); assertEquals(200, again.status); assertEquals(id, again.json["id"].asString())
        assertEquals(409, post("/v1/cost-events", w.manualBody(key, gross = 60000), w.finance).status)
        assertEquals(422, post("/v1/cost-events", body.replace(""""amount_minor":50000""", """"amount_minor":500.5"""), w.finance).status, "Gleitkomma wird abgewiesen")
        assertEquals(422, post("/v1/cost-events", body.replace(""""exponent":2""", """"exponent":0"""), w.finance).status, "Exponent weicht vom Währungskatalog ab")
        assertEquals(422, post("/v1/cost-events", body.replace("origin_key", "state"), w.finance).status, "unbekanntes Feld")
        // Lesen: fremder Mandant 404
        assertEquals(200, get("/v1/manual-cost-events/$id", w.finance).status); assertEquals(404, get("/v1/manual-cost-events/$id", other.finance).status)
        // Prüfung: ohne If-Match 428, veraltet 412, ohne Recht 403
        val review = """{"decision":"APPROVED","reason":"Sachlich geprüft"}"""
        assertEquals(428, post("/v1/manual-cost-events/$id/review", review, w.reviewer).status)
        assertEquals(412, post("/v1/manual-cost-events/$id/review", review, w.reviewer, idem() + im(7)).status)
        assertEquals(403, post("/v1/manual-cost-events/$id/review", review, env.token(driver), idem() + im(1)).status)
        assertEquals(200, post("/v1/manual-cost-events/$id/review", review, w.reviewer, idem() + im(1)).status)
        // Freigabe: der Erfasser (w.finance) gibt nie selbst frei (403), ein anderer Freigeber bucht
        assertEquals(403, post("/v1/manual-cost-events/$id/approve", """{"reason":"Selbst freigegeben"}""", w.finance, idem() + im(1)).status, "Selbstfreigabe")
        val ok = post("/v1/manual-cost-events/$id/approve", """{"reason":"Freigegeben"}""", w.approver, idem() + im(1)); assertEquals(200, ok.status, ok.text)
        assertEquals("POSTED", ok.json["state"].asString()); assertTrue(!ok.json["posting_id"].isNull); assertEquals(ok.json["posting_id"].asString(), ok.json["event_id"].asString())
        assertEquals(409, post("/v1/manual-cost-events/$id/approve", """{"reason":"Nochmal"}""", w.approver2, idem() + im(1)).status, "zweite Freigabe nach dem Buchen")
        assertEquals(1, env.tx.run(w.tenant) { j -> j.queryForObject("SELECT count(*) FROM taktovia.economic_event WHERE source_ref = ?", Int::class.java, key)!! })
        // Fahrzeugauswertung: Leistungszeitraum Juni, Anschaffung/Versicherung als erfasste Position; leere Monate ohne Fehler
        val v = get("/v1/vehicles/${w.base.vehicle}/total-cost?valuation=ACTUAL&currency=EUR&month_from=2026-05&month_to=2026-07", w.finance); assertEquals(200, v.status, v.text)
        assertEquals(1, v.json["items"].size()); assertEquals("2026-06", v.json["items"][0]["month"].asString()); assertEquals("INSURANCE", v.json["items"][0]["category"].asString()); assertEquals(50000, v.json["items"][0]["amount_minor"].asLong())
        assertEquals(50000, v.json["total_minor"].asLong()); assertTrue(v.json["assignment_rule"].asString().contains("service_from"))
        assertEquals(0, get("/v1/vehicles/${w.base.vehicle}/total-cost?valuation=ACTUAL&currency=EUR&month_from=2026-08&month_to=2026-09", w.finance).json["items"].size())
        assertEquals(404, get("/v1/vehicles/${w.base.vehicle}/total-cost?valuation=ACTUAL&currency=EUR&month_from=2026-05&month_to=2026-07", other.finance).status, "Fahrzeug eines anderen Mandanten")
        assertEquals(422, get("/v1/vehicles/${w.base.vehicle}/total-cost?currency=EUR&month_from=2026-05&month_to=2026-07", w.finance).status)
        assertEquals(422, get("/v1/vehicles/${w.base.vehicle}/total-cost?valuation=ACTUAL&currency=EUR&month_from=2026-5&month_to=2026-07", w.finance).status)
        assertEquals(401, env.server.send("GET", "/v1/vehicles/${w.base.vehicle}/total-cost?valuation=ACTUAL&currency=EUR&month_from=2026-05&month_to=2026-07", null, emptyMap()).status)
    }
}
