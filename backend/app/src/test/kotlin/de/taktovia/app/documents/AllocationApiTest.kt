package de.taktovia.app.documents

import de.taktovia.app.contract.W11C5ContractCases
import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M4-35 über die echte Anwendung (DOC-011, E2E-072, PH-AT-013): `POST /v1/cost-events/{id}/allocations` mit Idempotenz (gleicher Schlüssel, eine Revision), `If-Match` (428/412), 401/403, 409 `ALREADY_ALLOCATED`
 * und `PERIOD_CLOSED`, 422 `ALLOCATION_SUM_MISMATCH` (auch um einen Cent), Prozent, 404 für fremde Mandanten und Antwortform (SignedMoney, Gleitkommafreiheit).
 */
class AllocationApiTest {
    private val env = IdentityEnv
    private fun idem(key: String = "k-" + UUID.randomUUID()) = mapOf("Idempotency-Key" to key)
    private fun im(rev: Long) = mapOf("If-Match" to "\"$rev\"")
    private fun allocate(w: W11C5ContractCases.World, event: String, body: String, rev: Long?, key: String? = null, token: String = w.allocatorToken) =
        env.server.send("POST", "/v1/cost-events/$event/allocations", body, env.headers(token, extra = (key?.let { idem(it) } ?: idem()) + (rev?.let { im(it) } ?: emptyMap())))
    private fun count(w: W11C5ContractCases.World, sql: String) = env.tx.run(w.tenant) { j -> j.queryForObject(sql, Int::class.java)!! }

    @Test
    fun gleicher_idempotency_key_ergibt_eine_revision() {
        val w = W11C5ContractCases.World(); val (a, b) = w.centers(); val e = w.postedEvent()
        val body = W11C5ContractCases.allocationBody(W11C5ContractCases.amountLines(a, b))
        val key = "alloc-" + UUID.randomUUID()
        val first = allocate(w, e, body, 0, key)
        assertEquals(201, first.status, first.text); assertEquals("\"1\"", first.header("ETag")); assertEquals("POSTED", first.json["state"].asString())
        assertEquals(10000L, first.json["basis"]["amount_minor"].asLong()); assertEquals("EUR", first.json["basis"]["currency"].asString()); assertEquals(2, first.json["basis"]["exponent"].asInt())
        assertEquals(listOf(6000L, 4000L), (0 until 2).map { first.json["lines"][it]["amount"]["amount_minor"].asLong() }.let { l -> if (first.json["lines"][0]["cost_object_id"].asString() == a) l else l.reversed() })
        // dieselbe Anfrage mit demselben Schlüssel: gespeicherte Antwort, keine zweite Revision; anderer Inhalt mit demselben Schlüssel: 409
        val again = allocate(w, e, body, 0, key)
        assertEquals(201, again.status); assertEquals(first.json["allocation_id"].asString(), again.json["allocation_id"].asString())
        assertEquals(1, count(w, "SELECT count(*) FROM taktovia.allocation_set"))
        assertEquals(409, allocate(w, e, W11C5ContractCases.allocationBody(W11C5ContractCases.amountLines(a, b, 7000, 3000)), 0, key).status)
        assertEquals(1, count(w, "SELECT count(*) FROM taktovia.allocation_set")); assertEquals(1, count(w, "SELECT count(*) FROM taktovia.allocation_activation"))
    }

    @Test
    fun voraussetzungen_if_match_authentisierung_recht_und_409_codes() {
        val w = W11C5ContractCases.World(); val (a, b) = w.centers(); val e = w.postedEvent()
        val body = W11C5ContractCases.allocationBody(W11C5ContractCases.amountLines(a, b))
        assertEquals(428, allocate(w, e, body, null).status)
        assertEquals(401, env.server.send("POST", "/v1/cost-events/$e/allocations", body, idem() + im(0)).status)
        val driver = UUID.randomUUID(); env.grant(w.tenant, driver, "driver")
        assertEquals(403, allocate(w, e, body, 0, token = env.token(driver)).status)
        assertEquals(0, count(w, "SELECT count(*) FROM taktovia.allocation_set"))
        assertEquals(201, allocate(w, e, body, 0).status)
        // bereits aufgeteilt, ohne Kenntnis der Revision: 409 ALREADY_ALLOCATED; veraltet: 412; mit der aktiven Revision: neue Revision 2
        val already = allocate(w, e, body, 0); assertEquals(409, already.status); assertEquals("ALREADY_ALLOCATED", already.json["code"].asString())
        assertEquals(412, allocate(w, e, body, 5).status)
        val second = allocate(w, e, W11C5ContractCases.allocationBody(W11C5ContractCases.amountLines(a, b, 5000, 5000)), 1)
        assertEquals(201, second.status, second.text); assertEquals(2L, second.json["revision"].asLong()); assertEquals("\"2\"", second.header("ETag"))
        assertEquals(1, count(w, "SELECT count(*) FROM taktovia.allocation_activation")); assertEquals(2, count(w, "SELECT count(*) FROM taktovia.allocation_set"))
        // geschlossene Periode: 409 PERIOD_CLOSED
        env.tx.run(w.tenant) { j -> j.update("UPDATE taktovia.fiscal_period SET closed_at = clock_timestamp()") }
        val closed = allocate(w, e, body, 2); assertEquals(409, closed.status); assertEquals("PERIOD_CLOSED", closed.json["code"].asString())
    }

    @Test
    fun summe_ungleich_basis_prozent_und_fremder_mandant() {
        val w = W11C5ContractCases.World(); val (a, b) = w.centers(); val e = w.postedEvent()
        for (amounts in listOf(6000L to 4001L, 6000L to 3999L)) {
            val r = allocate(w, e, W11C5ContractCases.allocationBody(W11C5ContractCases.amountLines(a, b, amounts.first, amounts.second)), 0)
            assertEquals(422, r.status); assertEquals("ALLOCATION_SUM_MISMATCH", r.json["code"].asString(), r.text)
        }
        // JSON-Zahl mit Nachkommastellen ist kein Geldbetrag; Prozent nur als Dezimalstring
        val floating = """[{"cost_object_type":"COST_CENTER","cost_object_id":"$a","amount":{"amount_minor":6000.5,"currency":"EUR","exponent":2}},{"cost_object_type":"COST_CENTER","cost_object_id":"$b","amount":{"amount_minor":3999,"currency":"EUR","exponent":2}}]"""
        assertEquals(422, allocate(w, e, W11C5ContractCases.allocationBody(floating), 0).status)
        val asNumber = """[{"cost_object_type":"COST_CENTER","cost_object_id":"$a","percent":60},{"cost_object_type":"COST_CENTER","cost_object_id":"$b","percent":"40"}]"""
        assertEquals(422, allocate(w, e, W11C5ContractCases.allocationBody(asNumber), 0).status)
        assertEquals(0, count(w, "SELECT count(*) FROM taktovia.allocation_set"))
        val pct = """[{"cost_object_type":"COST_CENTER","cost_object_id":"$a","percent":"33.34"},{"cost_object_type":"COST_CENTER","cost_object_id":"$b","percent":"66.66"}]"""
        val ok = allocate(w, e, W11C5ContractCases.allocationBody(pct), 0)
        assertEquals(201, ok.status, ok.text); assertEquals(10000L, (0 until 2).sumOf { ok.json["lines"][it]["amount"]["amount_minor"].asLong() })
        val other = W11C5ContractCases.World()
        assertEquals(404, allocate(other, e, W11C5ContractCases.allocationBody(W11C5ContractCases.amountLines(other.centers()[0], other.centers()[1])), 0).status, "Ereignis eines anderen Mandanten")
        assertTrue(count(w, "SELECT count(*) FROM taktovia.journal_line") > 0)
    }
}
