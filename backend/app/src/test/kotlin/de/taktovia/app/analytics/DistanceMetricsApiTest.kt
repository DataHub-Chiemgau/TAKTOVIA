package de.taktovia.app.analytics

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M4-56/-57 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): Kilometer-, Betriebsstunden-, Fahrgastkilometer- und Auslastungskennzahlen mit Zerlegung (`components`), Definition und Formel,
 * Vollständigkeit bei nicht zuordenbaren Kilometern, Nullnenner, Mandantentrennung und 404 für ein fremdes Fahrzeug. Die Läufe werden wie in `EnergyCostApiTest` direkt angelegt (Klassifikation: Tests des Moduls).
 * Datensatz: 60 km Fahrgast (eine Stunde, im Mittel 2 Fahrgäste), 20 km leer (0,5 h), 5 km Überführung (0,25 h), 5 km nicht zuordenbar (0,25 h, nicht Betriebszeit); vier Sitzplätze.
 */
class DistanceMetricsApiTest {
    private val env = IdentityEnv
    private val q = "from=2026-12-01&to=2026-12-01"

    private class World(val env: IdentityEnv) {
        val tenant: TenantId = env.tenant("Km-" + UUID.randomUUID()); val vehicle: UUID = UUID.randomUUID(); val dispatcher = UUID.randomUUID(); val driver = UUID.randomUUID()
        init { env.grant(tenant, dispatcher, "dispatcher"); env.grant(tenant, driver, "driver") }
        val token = env.token(dispatcher); val driverToken = env.token(driver)
    }

    private fun world(): World {
        val w = World(env)
        env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'org','{\"seats\":4,\"wheelchair\":1}'::jsonb)", w.tenant.value, w.vehicle)
        val run = UUID.randomUUID()
        env.exec("WITH r AS (INSERT INTO taktovia.vehicle_km_run(tenant_id,id,vehicle_id,service_day,input_sha256,data_as_of,rule_version,position_count,events_complete,total_km_thousandths,capacity_seats,capacity_wheelchair) " +
            "VALUES (?,?,?,'2026-12-01',?,'2020-01-01T00:00:00Z',1,10,true,90000,4,1) RETURNING tenant_id, id, vehicle_id, service_day), " +
            "s AS (INSERT INTO taktovia.vehicle_km_segment(tenant_id,id,run_id,vehicle_id,service_day,period_from,period_to,category,km_thousandths,source,rule_version) " +
            "SELECT r.tenant_id, gen_random_uuid(), r.id, r.vehicle_id, r.service_day, v.f::timestamptz, v.t::timestamptz, v.c, v.m, 'POSITION', 1 FROM r CROSS JOIN (VALUES " +
            "('PASSENGER','2026-12-01T06:00:00Z','2026-12-01T07:00:00Z',60000),('EMPTY','2026-12-01T07:00:00Z','2026-12-01T07:30:00Z',20000)," +
            "('TRANSFER','2026-12-01T07:30:00Z','2026-12-01T07:45:00Z',5000),('UNASSIGNED','2026-12-01T07:45:00Z','2026-12-01T08:00:00Z',5000)) AS v(c, f, t, m)) " +
            "INSERT INTO taktovia.vehicle_km_occupancy(tenant_id,id,run_id,vehicle_id,service_day,period_from,period_to,passengers,km_thousandths,rule_version) " +
            "SELECT r.tenant_id, gen_random_uuid(), r.id, r.vehicle_id, r.service_day, v.f::timestamptz, v.t::timestamptz, v.p, v.m, 1 FROM r CROSS JOIN (VALUES " +
            "('2026-12-01T06:00:00Z','2026-12-01T06:30:00Z',1,30000),('2026-12-01T06:30:00Z','2026-12-01T07:00:00Z',3,30000)) AS v(f, t, p, m)",
            w.tenant.value, run, w.vehicle, UUID.randomUUID().toString().replace("-", "").repeat(2))
        return w
    }

    private fun value(w: World, id: String, extra: String = "") = env.get("/v1/analytics/metrics?$q&metric_id=$id$extra", w.token).also { assertEquals(200, it.status, it.text) }.json["values"].single()

    @Test
    fun kilometerkennzahlen_mit_zerlegung_vollstaendigkeit_und_definition() {
        val w = world()
        val vehicle = value(w, "vehicle_km")
        assertEquals(90.0, vehicle["value"].asDouble(), 0.0001); assertEquals("PROVISIONAL", vehicle["state"].asString())   // 5 km nicht zuordenbar: Vollständigkeit statt scheingenauem Wert
        assertEquals(0.9444, vehicle["coverage"].asDouble(), 0.0001)
        val c = vehicle["components"]
        assertEquals(60.0, c["PASSENGER"].asDouble(), 0.0001); assertEquals(20.0, c["EMPTY"].asDouble(), 0.0001); assertEquals(5.0, c["TRANSFER"].asDouble(), 0.0001); assertEquals(5.0, c["UNASSIGNED"].asDouble(), 0.0001)
        val empty = value(w, "empty_km_rate")   // 20 / (60 + 20 + 5 nicht zuordenbar)
        assertEquals(20.0, empty["numerator"].asDouble(), 0.0001); assertEquals(85.0, empty["denominator"].asDouble(), 0.0001); assertEquals(0.235294, empty["value"].asDouble(), 0.0000001)
        assertEquals(60.0, value(w, "productive_km")["value"].asDouble(), 0.0001)
        assertEquals(120.0, value(w, "passenger_km")["value"].asDouble(), 0.0001, "30 km x 1 + 30 km x 3")
        assertEquals(1.75, value(w, "operating_hours")["value"].asDouble(), 0.0001, "Fahrgast, Leer und Überführung")
        assertEquals(2, value(w, "empty_km_rate")["definition_version"].asInt())
        val def = env.get("/v1/analytics/metrics?metric_id=passenger_km", w.token).json["definitions"].single()
        assertTrue(def["executable"].asBoolean() && def["numerator"].asString().contains("individuellen Fahrtstrecken") && def["exclusions"].asString().contains("Erfassungsgrenze"))
    }

    @Test
    fun auslastung_beide_kennzahlen_und_fehlerfaelle() {
        val w = world()
        val time = value(w, "seat_time_utilization")   // belegt: 30 min x 1 + 30 min x 3 = 120 von 4 x 90 min = 360 -> 1/3
        assertEquals(120.0, time["numerator"].asDouble(), 0.0001); assertEquals(360.0, time["denominator"].asDouble(), 0.0001); assertEquals(0.333333, time["value"].asDouble(), 0.0000001)
        val dist = value(w, "capacity_utilization_distance_weighted")   // 120 Platz-km von 4 x 80 km = 320 -> 0,375
        assertEquals(120.0, dist["numerator"].asDouble(), 0.0001); assertEquals(320.0, dist["denominator"].asDouble(), 0.0001); assertEquals(0.375, dist["value"].asDouble(), 0.0000001)
        val other = world()
        assertEquals(404, env.get("/v1/analytics/metrics?$q&metric_id=vehicle_km&vehicle_id=${other.vehicle}", w.token).status, "fremdes Fahrzeug")
        assertEquals(200, env.get("/v1/analytics/metrics?$q&metric_id=vehicle_km&vehicle_id=${w.vehicle}", w.token).status)
        assertEquals(400, env.get("/v1/analytics/metrics?$q&metric_id=vehicle_km&service_id=${UUID.randomUUID()}", w.token).status, "Dienst ist keine Dimension der Kilometerkennzahlen")
        assertEquals(403, env.get("/v1/analytics/metrics?$q&metric_id=vehicle_km", w.driverToken).status)
        val none = env.get("/v1/analytics/metrics?from=2026-12-10&to=2026-12-10&metric_id=seat_time_utilization", w.token).json["values"].single()
        assertEquals("NOT_COMPUTABLE", none["state"].asString()); assertTrue(none["value"].isNull)
        assertEquals(60.0, value(other, "productive_km")["value"].asDouble(), 0.0001, "jeder Mandant sieht nur die eigenen Kilometer, nie die Summe")
    }
}
