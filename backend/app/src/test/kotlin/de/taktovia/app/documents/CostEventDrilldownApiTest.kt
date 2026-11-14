package de.taktovia.app.documents

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M4-44 (Drilldown der Kostenübersicht UI-15): `GET /v1/cost-events` mit Filtern Kostenobjekt, Kostenart, Kostenverhalten, Zurechnung, Währung und Periode über die echte Anwendung. Die Filter wirken
 * wie die Klassifikation (nur gebuchte Journale, Buchungsdatum, ein Ereignis einmal), Cursor und Filter gehören zusammen, fremde Kostenobjekte liefern nichts, ungültige Filter 422, ohne Finanzrolle 403.
 */
class CostEventDrilldownApiTest {
    private val env = IdentityEnv

    private inner class World {
        val tenant = env.tenant("Drilldown44-" + UUID.randomUUID()); val finance = UUID.randomUUID(); val driver = UUID.randomUUID()
        val le = UUID.randomUUID(); val period = UUID.randomUUID(); val vehicleA = UUID.randomUUID(); val vehicleB = UUID.randomUUID(); val objA = UUID.randomUUID(); val objB = UUID.randomUUID()
        init {
            env.grant(tenant, finance, "finance"); env.grant(tenant, driver, "driver")
            env.exec("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", tenant.value, le)
            env.exec("INSERT INTO taktovia.fiscal_period(tenant_id,id,legal_entity_id,starts_on,ends_on) VALUES (?,?,?,DATE '2026-01-01',DATE '2027-01-01')", tenant.value, period, le)
            for ((v, o) in listOf(vehicleA to objA, vehicleB to objB)) {
                env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'org','{}'::jsonb)", tenant.value, v)
                env.exec("INSERT INTO taktovia.cost_object(tenant_id,id,legal_entity_id,kind,business_ref,vehicle_id) VALUES (?,?,?,'VEHICLE',?,?)", tenant.value, o, le, "$v/$le", v)
            }
        }
        val token = env.token(finance); val driverToken = env.token(driver)

        fun event(obj: UUID?, category: String, behavior: String?, attribution: String?, gross: Long, date: String = "2026-09-15", currency: String = "EUR", posted: Boolean = true): UUID {
            val e = UUID.randomUUID(); val jn = UUID.randomUUID()
            env.exec("INSERT INTO taktovia.economic_event(tenant_id,id,legal_entity_id,source_system,source_ref,event_type,scenario,cost_behavior,attribution,category,currency,net_minor,tax_minor,gross_minor,tax_rule_revision," +
                "occurred_at,booked_at,cost_object_id) VALUES (?,?,?,'TEST',?,'TEST_COST','ACTUAL',?,?,?,?,?,0,?,'T',CAST(? AS timestamptz),clock_timestamp(),?)", tenant.value, e, le, e.toString(), behavior, attribution, category, currency, gross, gross, "${date}T00:00:00Z", obj)
            env.exec("INSERT INTO taktovia.journal(tenant_id,id,event_id,legal_entity_id,currency,period_id,accounting_date,state) VALUES (?,?,?,?,?,?,CAST(? AS date),'DRAFT')", tenant.value, jn, e, le, currency, period, date)
            env.exec("INSERT INTO taktovia.journal_line(tenant_id,id,journal_id,account_code,side,amount_minor,account_role) VALUES (?,?,?,'6000','D',?,'EXPENSE')", tenant.value, UUID.randomUUID(), jn, gross)
            env.exec("INSERT INTO taktovia.journal_line(tenant_id,id,journal_id,account_code,side,amount_minor,account_role) VALUES (?,?,?,'1600','C',?,'PAYABLE')", tenant.value, UUID.randomUUID(), jn, gross)
            if (posted) env.exec("UPDATE taktovia.journal SET state='POSTED', posted_by='user:test' WHERE id=?", jn)
            return e
        }
    }

    private fun tools.jackson.databind.JsonNode.ids() = (0 until size()).map { get(it)["id"].asString() }.toSet()

    @Test
    fun drilldown_filtert_nach_kostenobjekt_kostenart_klassifikation_waehrung_und_periode() {
        val w = World(); val other = World()
        val fuelA = w.event(w.objA, "FUEL", "VARIABLE", "DIRECT", 10000); val insA = w.event(w.objA, "INSURANCE", "FIXED", "DIRECT", 30000); val allocA = w.event(w.objA, "OVERHEAD", "FIXED", "ALLOCATED", 5000)
        val fuelB = w.event(w.objB, "FUEL", "VARIABLE", "DIRECT", 7000); val unclassified = w.event(w.objA, "OTHER", null, null, 1234)
        val october = w.event(w.objA, "FUEL", "VARIABLE", "DIRECT", 900, date = "2026-10-05"); val chf = w.event(w.objA, "FUEL", "VARIABLE", "DIRECT", 800, currency = "CHF")
        w.event(w.objA, "FUEL", "VARIABLE", "DIRECT", 111, posted = false)   // Entwurf zählt nirgends
        other.event(other.objA, "FUEL", "VARIABLE", "DIRECT", 99)
        fun ids(q: String): Set<String> = env.get("/v1/cost-events?$q", w.token).also { assertEquals(200, it.status, it.text) }.json["items"].ids()
        val sep = "period_from=2026-09-01&period_to=2026-10-01&currency=EUR"
        assertEquals(setOf(fuelA, insA, allocA, unclassified).map { it.toString() }.toSet(), ids("cost_object_id=${w.objA}&$sep"), "alle gebuchten EUR-Ereignisse des Fahrzeugs im September (ohne Entwurf, Oktober, CHF, anderes Fahrzeug)")
        assertEquals(setOf(fuelB.toString()), ids("cost_object_id=${w.objB}&$sep"))
        assertEquals(setOf(fuelA.toString()), ids("cost_object_id=${w.objA}&$sep&category=FUEL"))
        assertEquals(setOf(insA, allocA).map { it.toString() }.toSet(), ids("cost_object_id=${w.objA}&$sep&cost_behavior=FIXED"))
        assertEquals(setOf(allocA.toString()), ids("cost_object_id=${w.objA}&$sep&cost_behavior=FIXED&attribution=ALLOCATED"))
        assertEquals(setOf(unclassified.toString()), ids("cost_object_id=${w.objA}&$sep&cost_behavior=UNCLASSIFIED"))
        assertEquals(setOf(october.toString()), ids("cost_object_id=${w.objA}&period_from=2026-10-01&period_to=2026-11-01&currency=EUR"))
        assertEquals(setOf(chf.toString()), ids("cost_object_id=${w.objA}&period_from=2026-09-01&period_to=2026-10-01&currency=CHF"))
        // fremdes Kostenobjekt: nichts
        assertTrue(ids("cost_object_id=${other.objA}&$sep").isEmpty(), "Kostenobjekt eines anderen Mandanten liefert nichts")
        // Cursor gilt nur für denselben Filter
        val p1 = env.get("/v1/cost-events?cost_object_id=${w.objA}&$sep&limit=2", w.token); val cursor = p1.json["next_cursor"].asString()
        assertEquals(2, p1.json["items"].size())
        val p2 = env.get("/v1/cost-events?cost_object_id=${w.objA}&$sep&limit=2&cursor=$cursor", w.token)
        assertEquals(2, p2.json["items"].size()); assertTrue(p2.json["next_cursor"].isNull); assertTrue((p1.json["items"].ids() intersect p2.json["items"].ids()).isEmpty())
        assertEquals(400, env.get("/v1/cost-events?cost_object_id=${w.objB}&$sep&limit=2&cursor=$cursor", w.token).status, "Cursor eines anderen Filters")
        assertFalse(env.get("/v1/cost-events?cost_object_id=${w.objA}&$sep", w.token).text.contains(other.tenant.toString()))
    }

    @Test
    fun ungueltige_filter_und_fehlendes_recht() {
        val w = World()
        for (q in listOf("period_from=2026-09-01", "period_to=2026-10-01", "period_from=2026-10-01&period_to=2026-09-01", "cost_object_id=kein-uuid", "category=klein", "currency=EURO", "cost_behavior=SEHR_FIX", "attribution=EGAL", "period_from=gestern&period_to=heute"))
            assertTrue(env.get("/v1/cost-events?$q", w.token).status in setOf(400, 422), q)
        assertEquals(403, env.get("/v1/cost-events?category=FUEL", w.driverToken).status, "ohne Finanzrolle")
    }
}
