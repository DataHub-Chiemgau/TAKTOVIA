package de.taktovia.app.documents

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M4-33 über die echte Anwendung: `GET /v1/cost-events` liefert nur Ereignisse mit gebuchtem Journal des eigenen Mandanten (Ist-Bericht), mit Cursor, `as_of` und Rechtsträgerfilter;
 * Entwürfe, Plan-Ereignisse und fremde Mandanten bleiben unsichtbar; ohne Finanzrolle 403.
 */
class CostEventApiTest {
    private val env = IdentityEnv

    private inner class World {
        val tenant = env.tenant("Kostenereignis33-" + UUID.randomUUID()); val finance = UUID.randomUUID(); val driver = UUID.randomUUID()
        val le = UUID.randomUUID(); val period = UUID.randomUUID()
        init {
            env.grant(tenant, finance, "finance"); env.grant(tenant, driver, "driver")
            env.tx.run(tenant) { j ->
                j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", tenant.value, le)
                j.update("INSERT INTO taktovia.fiscal_period(tenant_id,id,legal_entity_id,starts_on,ends_on) VALUES (?,?,?,DATE '2026-01-01',DATE '2027-01-01')", tenant.value, period, le)
            }
        }
        val token = env.token(finance); val driverToken = env.token(driver)

        /** Ereignis mit Journal: `posted` = gebucht (ausgeglichen, Summe Soll = Brutto), sonst Entwurf. */
        fun event(ref: String, gross: Long = 11900, tax: Long = 1900, posted: Boolean = true, scenario: String = "ACTUAL", bookedAt: String = "2026-06-15T10:00:00Z"): UUID {
            val e = UUID.randomUUID(); val jn = UUID.randomUUID()
            env.tx.run(tenant) { j ->
                j.update("INSERT INTO taktovia.economic_event(tenant_id,id,legal_entity_id,source_system,source_ref,event_type,scenario,category,currency,net_minor,tax_minor,gross_minor,recoverable_tax_minor,tax_rule_revision,occurred_at,booked_at) " +
                    "VALUES (?,?,?,'EXPENSE',?,'EXPENSE_COST',?,'FUEL','EUR',?,?,?,?,'TEST-1',CAST(? AS timestamptz),CAST(? AS timestamptz))", tenant.value, e, le, ref, scenario, gross - tax, tax, gross, tax, bookedAt, bookedAt)
                j.update("INSERT INTO taktovia.journal(tenant_id,id,event_id,legal_entity_id,currency,period_id,accounting_date,state) VALUES (?,?,?,?,'EUR',?,DATE '2026-06-15','DRAFT')", tenant.value, jn, e, le, period)
                fun line(account: String, side: String, amount: Long) { if (amount > 0) j.update("INSERT INTO taktovia.journal_line(tenant_id,id,journal_id,account_code,side,amount_minor) VALUES (?,?,?,?,?,?)", tenant.value, UUID.randomUUID(), jn, account, side, amount) }
                line("6600", "D", gross - tax); line("1576", "D", tax); line("1600", "C", gross)
                if (posted) j.update("UPDATE taktovia.journal SET state='POSTED', posted_by='user:test' WHERE id=?", jn)
            }
            return e
        }
    }

    private fun tools.jackson.databind.JsonNode.ids() = (0 until size()).map { get(it)["id"].asString() }

    @Test
    fun ist_bericht_nur_gebuchte_ereignisse_mit_cursor_as_of_und_filter() {
        val w = World(); val other = World()
        val e1 = w.event("a-1", bookedAt = "2026-06-15T10:00:00Z"); val e2 = w.event("a-2", gross = 5000, tax = 0, bookedAt = "2026-06-15T11:00:00Z"); val e3 = w.event("a-3", bookedAt = "2026-06-15T12:00:00Z")
        w.event("draft", posted = false); w.event("plan", scenario = "PLAN")
        other.event("fremd-1")
        val all = env.get("/v1/cost-events", w.token)
        assertEquals(200, all.status, all.text)
        assertEquals(listOf(e1, e2, e3).map { it.toString() }, all.json["items"].ids(), "nur gebuchte Ist-Ereignisse, nach Buchungszeit")
        assertEquals(11900, all.json["items"][0]["gross_minor"].asInt()); assertEquals(10000, all.json["items"][0]["cost_basis_minor"].asInt()); assertEquals(1900, all.json["items"][0]["recoverable_tax_minor"].asInt())
        assertEquals("ACTUAL", all.json["items"][0]["scenario"].asString()); assertTrue(all.json["items"][0]["document_id"].isNull)
        assertFalse(all.text.contains(other.tenant.toString()))
        // Cursor
        val p1 = env.get("/v1/cost-events?limit=2", w.token)
        assertEquals(2, p1.json["items"].size()); val cursor = p1.json["next_cursor"].asString()
        val p2 = env.get("/v1/cost-events?limit=2&cursor=$cursor", w.token)
        assertEquals(listOf(e3.toString()), p2.json["items"].ids()); assertTrue(p2.json["next_cursor"].isNull)
        // as_of und Rechtsträgerfilter
        assertEquals(listOf(e1.toString()), env.get("/v1/cost-events?as_of=2026-06-15T10:30:00Z", w.token).json["items"].ids())
        assertEquals(3, env.get("/v1/cost-events?legal_entity_id=${w.le}", w.token).json["items"].size())
        assertEquals(0, env.get("/v1/cost-events?legal_entity_id=${other.le}", w.token).json["items"].size(), "Rechtsträger eines anderen Mandanten: leer, kein Hinweis")
        // Fehlerfälle
        assertEquals(422, env.get("/v1/cost-events?as_of=gestern", w.token).status)
        assertEquals(422, env.get("/v1/cost-events?legal_entity_id=kein-uuid", w.token).status)
        assertEquals(400, env.get("/v1/cost-events?cursor=kaputt", w.token).status)
        assertEquals(403, env.get("/v1/cost-events", w.driverToken).status, "ohne Finanzrolle")
    }
}
