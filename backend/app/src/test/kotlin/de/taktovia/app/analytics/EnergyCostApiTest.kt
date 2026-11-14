package de.taktovia.app.analytics

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M4-43 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): Energiekosten je 100 km und je Betriebsstunde mit Definitionsversion, Datenstand, Vollständigkeit, Ausschlussliste und Kennzeichnung der
 * Erwerbsnäherung; Mandantentrennung, 404 für ein fremdes Fahrzeug, 400 für unzulässige Filter, 403 ohne Recht. Referenzwerte wie `EnergyCostMetricTest` des Moduls: 15 000 Minor Units Kostenbasis,
 * 185 km und 2 Betriebsstunden -> 8 108 je 100 km und 7 500 je Stunde.
 */
class EnergyCostApiTest {
    private val env = IdentityEnv
    private val q = "from=2026-12-01&to=2026-12-01"

    private class World(val env: IdentityEnv) {
        val tenant: TenantId = env.tenant("Energie-" + UUID.randomUUID()); val legalEntity: UUID = UUID.randomUUID(); val vehicle: UUID = UUID.randomUUID()
        val dispatcher = UUID.randomUUID(); val finance = UUID.randomUUID(); val driver = UUID.randomUUID()
        init { env.grant(tenant, dispatcher, "dispatcher"); env.grant(tenant, finance, "finance"); env.grant(tenant, driver, "driver") }
        val token = env.token(dispatcher); val financeToken = env.token(finance); val driverToken = env.token(driver)
    }

    private fun fuel(w: World, gross: Long, tax: Long, state: String = "POSTED"): UUID {
        val doc = UUID.randomUUID(); val e = w.env
        e.exec("INSERT INTO taktovia.expense_document(tenant_id,id,legal_entity_id,object_key,original_sha256,mime,state,revision,created_by,original_received_at,document_kind,current_fields) " +
            "VALUES (?,?,?,?,?,'image/jpeg','STORED',1,'erfasser','2026-12-01T09:00:00Z','FUEL_RECEIPT',?::jsonb)", w.tenant.value, doc, w.legalEntity, "receipts/originals/$doc", "a".repeat(64), """{"amount_minor":$gross,"currency":"EUR"}""")
        e.exec("INSERT INTO taktovia.fuel_transaction(tenant_id,id,document_id,vehicle_id,fuel_type,quantity,unit_price,fueled_at,document_revision,recorded_by) VALUES (?,?,?,?,'DIESEL',40.0,1.5,'2026-12-01T09:00:00Z',1,'erfasser')",
            w.tenant.value, UUID.randomUUID(), doc, w.vehicle)
        if (state != "STORED") e.exec("UPDATE taktovia.expense_document SET state = ? WHERE tenant_id = ? AND id = ?", state, w.tenant.value, doc)
        if (state == "POSTED") e.exec("INSERT INTO taktovia.economic_event(tenant_id,id,legal_entity_id,source_system,source_ref,event_type,scenario,category,currency,net_minor,tax_minor,gross_minor,recoverable_tax_minor," +
            "tax_rule_revision,occurred_at,booked_at,document_id) VALUES (?,?,?,'EXPENSE',?,'EXPENSE_COST','ACTUAL','FUEL','EUR',?,?,?,?,'test-1','2026-12-01T00:00:00Z',clock_timestamp(),?)",
            w.tenant.value, UUID.randomUUID(), w.legalEntity, doc.toString(), gross - tax, tax, gross, tax, doc)
        return doc
    }

    private fun world(): World {
        val w = World(env)
        env.exec("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'Test GmbH','DE')", w.tenant.value, w.legalEntity)
        env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'org','{}'::jsonb)", w.tenant.value, w.vehicle)
        fuel(w, 11900, 1900); fuel(w, 5950, 950)
        env.exec("WITH r AS (INSERT INTO taktovia.vehicle_km_run(tenant_id,id,vehicle_id,service_day,input_sha256,data_as_of,rule_version,position_count,events_complete,total_km_thousandths) " +
            "VALUES (?,?,?,'2026-12-01',?,'2020-01-01T00:00:00Z',1,10,true,185000) RETURNING tenant_id, id, vehicle_id, service_day) " +
            "INSERT INTO taktovia.vehicle_km_segment(tenant_id,id,run_id,vehicle_id,service_day,period_from,period_to,category,km_thousandths,source,rule_version) " +
            "SELECT r.tenant_id, gen_random_uuid(), r.id, r.vehicle_id, r.service_day, v.f::timestamptz, v.t::timestamptz, v.c, v.m, 'POSITION', 1 FROM r CROSS JOIN (VALUES " +
            "('PASSENGER','2026-12-01T06:00:00Z','2026-12-01T07:00:00Z',120000),('EMPTY','2026-12-01T07:00:00Z','2026-12-01T07:30:00Z',40000)," +
            "('TRANSFER','2026-12-01T07:30:00Z','2026-12-01T08:00:00Z',20000),('WORKSHOP','2026-12-01T08:00:00Z','2026-12-01T08:15:00Z',5000)) AS v(c, f, t, m)",
            w.tenant.value, UUID.randomUUID(), w.vehicle, UUID.randomUUID().toString().replace("-", "").repeat(2))
        return w
    }

    @Test
    fun energiekosten_je_100_km_und_je_betriebsstunde_mit_datenstand_und_vollstaendigkeit() {
        val w = world()
        val perKm = env.get("/v1/analytics/metrics?$q&metric_id=energy_cost_per_100km&energy=FUEL", w.token)
        assertEquals(200, perKm.status, perKm.text)
        val v = perKm.json["values"].single()
        assertEquals("OK", v["state"].asString()); assertEquals(8108, v["value"].asInt()); assertEquals(15000, v["numerator"].asInt()); assertEquals(185.0, v["denominator"].asDouble(), 0.0001)
        assertEquals(1.0, v["coverage"].asDouble(), 0.0001); assertEquals(1, v["definition_version"].asInt()); assertEquals("ACQUISITION_BASIS_P1", v["approximation"].asString())
        assertEquals("FUEL", v["filter"]["energy"].asString()); assertEquals("EUR", v["filter"]["currency"].asString()); assertTrue(v["as_of"].asString().isNotBlank()); assertTrue(v["exclusions"].isEmpty)
        assertEquals("Minor Units je 100 km", v["unit"].asString())
        val perHour = env.get("/v1/analytics/metrics?$q&metric_id=energy_cost_per_operating_hour&energy=FUEL&vehicle_id=${w.vehicle}", w.token).json["values"].single()
        assertEquals(7500, perHour["value"].asInt()); assertEquals(2.0, perHour["denominator"].asDouble(), 0.0001)
        // ohne Energieart: je Energieart ein Wert, Strom ohne Daten ist „nicht berechenbar“ (keine plausibilisierten Tankvorgänge), nie 0
        val both = env.get("/v1/analytics/metrics?$q&metric_id=energy_cost_per_100km", w.token).json["values"]
        assertEquals(listOf("FUEL", "ELECTRIC"), (0 until both.size()).map { both[it]["filter"]["energy"].asString() })
        val electric = both[1]; assertEquals("NOT_COMPUTABLE", electric["state"].asString()); assertEquals("NO_DATA", electric["reason"].asString()); assertTrue(electric["value"].isNull)
        // Definition mit Filterdimensionen
        val def = env.get("/v1/analytics/metrics?metric_id=energy_cost_per_100km", w.token).json["definitions"].single()
        assertEquals(setOf("vehicle_id", "energy", "currency"), (0 until def["filter_dimensions"].size()).map { def["filter_dimensions"][it].asString() }.toSet()); assertTrue(def["executable"].asBoolean())
    }

    @Test
    fun nenner_null_unfreigegebener_beleg_und_ausschlussliste() {
        val w = world(); val pending = fuel(w, 5000, 0, state = "IN_REVIEW")
        val v = env.get("/v1/analytics/metrics?$q&metric_id=energy_cost_per_100km&energy=FUEL", w.token).json["values"].single()
        assertEquals("PROVISIONAL", v["state"].asString()); assertEquals(0.6667, v["coverage"].asDouble(), 0.0001); assertEquals(15000, v["numerator"].asInt())
        assertEquals(pending.toString(), v["exclusions"].single()["document_id"].asString()); assertEquals("NOT_RELEASED", v["exclusions"].single()["reason"].asString())
        // Zeitraum ohne Tankvorgänge: kein Wert (nie 0 Kosten)
        val none = env.get("/v1/analytics/metrics?from=2026-12-10&to=2026-12-10&metric_id=energy_cost_per_100km&energy=FUEL", w.token).json["values"].single()
        assertEquals("NOT_COMPUTABLE", none["state"].asString()); assertEquals("NO_DATA", none["reason"].asString()); assertTrue(none["value"].isNull)
        // Tankvorgänge ohne Kilometer: Nenner 0, Zähler bleibt sichtbar
        val w2 = World(env)
        env.exec("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'Test GmbH','DE')", w2.tenant.value, w2.legalEntity)
        env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'org','{}'::jsonb)", w2.tenant.value, w2.vehicle)
        fuel(w2, 11900, 1900)
        val zero = env.get("/v1/analytics/metrics?$q&metric_id=energy_cost_per_100km&energy=FUEL", w2.token).json["values"].single()
        assertEquals("NOT_COMPUTABLE", zero["state"].asString()); assertEquals("ZERO_DENOMINATOR", zero["reason"].asString()); assertTrue(zero["value"].isNull); assertEquals(10000, zero["numerator"].asInt())
    }

    @Test
    fun fehlerfaelle_404_400_403_und_mandantentrennung() {
        val w = world(); val other = world()
        assertEquals(404, env.get("/v1/analytics/metrics?$q&metric_id=energy_cost_per_100km&vehicle_id=${other.vehicle}", w.token).status, "fremdes Fahrzeug")
        assertEquals(404, env.get("/v1/analytics/metrics?$q&metric_id=energy_cost_per_100km&vehicle_id=${UUID.randomUUID()}", w.token).status, "unbekanntes Fahrzeug")
        assertEquals(400, env.get("/v1/analytics/metrics?$q&metric_id=requests&energy=FUEL", w.token).status, "Energieart ist für Versuchskennzahlen keine Filterdimension")
        assertEquals(400, env.get("/v1/analytics/metrics?$q&metric_id=energy_cost_per_100km&energy=GAS", w.token).status.let { if (it == 422) 400 else it }, "unbekannte Energieart")
        assertEquals(400, env.get("/v1/analytics/metrics?$q&metric_id=energy_cost_per_100km&service_id=${UUID.randomUUID()}", w.token).status, "Dienst ist keine Dimension der Energiekosten")
        assertEquals(400, env.get("/v1/analytics/metrics?metric_id=energy_cost_per_100km&energy=FUEL", w.token).status, "Filter braucht einen Zeitraum")
        assertEquals(403, env.get("/v1/analytics/metrics?$q&metric_id=energy_cost_per_100km", w.driverToken).status, "ohne Kennzahlenrecht")
        assertEquals(200, env.get("/v1/analytics/metrics?$q&metric_id=energy_cost_per_100km&energy=FUEL", w.financeToken).status, "die Finanzrolle liest die Energiekosten (UI-15)")
        val own = env.get("/v1/analytics/metrics?$q&metric_id=energy_cost_per_100km&energy=FUEL", w.token).json["values"].single()["numerator"].asInt()
        val foreignTenantsView = env.get("/v1/analytics/metrics?$q&metric_id=energy_cost_per_100km&energy=FUEL", other.token).json["values"].single()
        assertEquals(15000, own); assertEquals(15000, foreignTenantsView["numerator"].asInt(), "jeder Mandant sieht nur die eigenen Belege (je 15 000), nie die Summe 30 000")
    }
}
