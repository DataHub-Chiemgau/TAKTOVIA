package de.taktovia.app.documents

import de.taktovia.app.contract.W10C5ContractCases
import de.taktovia.app.contract.W11C5ContractCases
import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP-M4-38: Arbeitsliste `GET /v1/expenses` (UI-14): Filter nach Status, Cursor, Datenstand, Sichtbarkeit je Rolle und Mandant, Kostenereignis am gebuchten Beleg. */
class ExpenseListApiTest {
    private val env = IdentityEnv
    private fun get(path: String, token: String) = env.server.send("GET", path, null, env.headers(token))

    @Test
    fun arbeitsliste_nach_status_mit_cursor_datenstand_und_kostenereignis() {
        val w = W11C5ContractCases.World(); val event = w.postedEvent()
        val stored = with(W10C5ContractCases) { w.base.readyDocument("STORED").first }
        val all = get("/v1/expenses?limit=1", w.finance); assertEquals(200, all.status, all.text)
        assertEquals(1, all.json["items"].size()); assertTrue(!all.json["next_cursor"].isNull); assertTrue(!all.json["as_of"].isNull)
        val page2 = get("/v1/expenses?limit=5&cursor=" + all.json["next_cursor"].asString(), w.finance); assertEquals(1, page2.json["items"].size())
        assertEquals(setOf("POSTED", "STORED"), setOf(all.json["items"][0]["state"].asString(), page2.json["items"][0]["state"].asString()))
        val posted = get("/v1/expenses?state=POSTED", w.finance).json["items"]; assertEquals(1, posted.size())
        assertEquals(event, posted[0]["cost_event_id"].asString()); assertEquals(11900L, posted[0]["amount_minor"].asLong()); assertEquals("EUR", posted[0]["currency"].asString())
        val open = get("/v1/expenses?state=STORED", w.finance).json["items"]; assertEquals(1, open.size()); assertEquals(stored, open[0]["document_id"].asString()); assertTrue(open[0]["cost_event_id"].isNull)
        assertEquals(0, get("/v1/expenses?state=IN_REVIEW", w.finance).json["items"].size(), "leere Arbeitsliste")
        assertEquals(event, get("/v1/expenses/${posted[0]["document_id"].asString()}", w.finance).json["cost_event_id"].asString())
        assertEquals(422, get("/v1/expenses?state=GIBT_ES_NICHT", w.finance).status); assertEquals(422, get("/v1/expenses?limit=0", w.finance).status)
        assertEquals(401, env.server.send("GET", "/v1/expenses", null, emptyMap()).status)
    }

    @Test
    fun sichtbarkeit_je_rolle_und_mandant() {
        val w = W11C5ContractCases.World(); w.postedEvent()
        val driver = UUID.randomUUID(); env.grant(w.base.tenant, driver, "driver")
        assertEquals(0, get("/v1/expenses", env.token(driver)).json["items"].size(), "ein Fahrer sieht nur seine eigenen Belege")
        val other = W11C5ContractCases.World()
        assertEquals(0, get("/v1/expenses?state=POSTED", other.finance).json["items"].size(), "der fremde Mandant sieht den Beleg nicht")
    }
}
