package de.taktovia.app.reports

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * WP-M4-42 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): `GET /v1/analytics/vehicle-km` liefert die Kilometer je Kategorie mit separat ausgewiesenen nicht zuordenbaren Kilometern,
 * Pflichtparameter und Zeitraum werden geprüft (422), ein Fahrzeug eines anderen Mandanten ist 404, ohne Finanzrolle 403.
 */
class AnalyticsMetricsApiTest {
    private val env = IdentityEnv
    private val analytics get() = env.server.ctx.getBean(AnalyticsApi::class.java)
    private val q = "metric=VEHICLE_KM_CLASSES&from=2026-06-01&to=2026-06-01"

    private class World(val env: IdentityEnv) {
        val tenant = env.tenant("Km-" + UUID.randomUUID()); val finance = UUID.randomUUID(); val driver = UUID.randomUUID(); val vehicle = UUID.randomUUID(); val shift = UUID.randomUUID()
        init {
            env.grant(tenant, finance, "finance"); env.grant(tenant, driver, "driver")
            env.tx.run(tenant) { j ->
                j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,created_at) VALUES (?,?,'fixture','{}',now() - interval '1 day')", tenant.value, vehicle)
                j.update("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,TIMESTAMPTZ '2026-06-01T06:00:00Z',TIMESTAMPTZ '2026-06-01T14:00:00Z','Europe/Berlin',1,'PUBLISHED')", tenant.value, shift, driver, vehicle)
                // vier Positionen im Minutentakt (etwa 111 m je Schritt) ohne Fahrtenplan: alles nicht zuordenbar
                (0..3).forEach { i -> j.update("INSERT INTO taktovia.position_sample(tenant_id,id,vehicle_id,shift_id,position,accuracy_m,measured_at,created_by) VALUES (?,?,?,?,ST_SetSRID(ST_MakePoint(9.0, 48.0 + ? / 1000.0),4326)::geography,5,TIMESTAMPTZ '2026-06-01T08:00:00Z' + ? * interval '1 minute','service:test')",
                    tenant.value, UUID.randomUUID(), vehicle, shift, i, i) }
            }
        }
        val token = env.token(finance); val driverToken = env.token(driver)
    }

    @Test
    fun kilometer_je_kategorie_mit_nicht_zuordenbar_separat() {
        val w = World(env)
        val run = analytics.km.classify(w.tenant, w.vehicle, LocalDate.of(2026, 6, 1))
        val r = env.get("/v1/analytics/vehicle-km?$q&vehicle_id=${w.vehicle}", w.token)
        assertEquals(200, r.status, r.text)
        assertEquals("VEHICLE_KM_CLASSES", r.json["metric"].asString()); assertEquals(1, r.json["definition_version"].asInt())
        assertEquals(run.totalKmThousandths, r.json["total_km_thousandths"].asLong()); assertTrue(run.totalKmThousandths > 0)
        assertEquals(run.totalKmThousandths, r.json["unassigned_km_thousandths"].asLong(), "ohne Fahrtenplan: alles nicht zuordenbar")
        listOf("PASSENGER", "EMPTY", "TRANSFER", "WORKSHOP").forEach { assertEquals(0L, r.json["km_thousandths_by_category"][it].asLong()) }
        assertEquals("COMPLETE", r.json["completeness"].asString()); assertEquals(1, r.json["days"].size())
        // ohne Fahrzeug über alle Fahrzeuge des Mandanten; ein Zeitraum ohne Läufe ist leer (200)
        assertEquals(run.totalKmThousandths, env.get("/v1/analytics/vehicle-km?$q", w.token).json["total_km_thousandths"].asLong())
        val empty = env.get("/v1/analytics/vehicle-km?metric=VEHICLE_KM_CLASSES&from=2026-07-01&to=2026-07-02", w.token)
        assertEquals(200, empty.status); assertEquals(0, empty.json["days"].size()); assertTrue(empty.json["as_of"].isNull)
    }

    @Test
    fun pflichtparameter_zeitraum_fremdes_fahrzeug_und_rechte() {
        val w = World(env); val other = World(env)
        assertEquals(422, env.get("/v1/analytics/vehicle-km?metric=VEHICLE_KM_CLASSES&from=2026-06-01", w.token).status)
        assertEquals(422, env.get("/v1/analytics/vehicle-km?from=2026-06-01&to=2026-06-02", w.token).status)
        assertEquals(422, env.get("/v1/analytics/vehicle-km?metric=VEHICLE_KM_CLASSES&from=2026-06-05&to=2026-06-01", w.token).status, "Ende vor Beginn")
        assertEquals(422, env.get("/v1/analytics/vehicle-km?metric=VEHICLE_KM_CLASSES&from=2025-01-01&to=2026-06-01", w.token).status, "mehr als 366 Tage")
        assertEquals(422, env.get("/v1/analytics/vehicle-km?metric=VEHICLE_KM_CLASSES&from=2026-13-01&to=2026-06-01", w.token).status)
        assertEquals(404, env.get("/v1/analytics/vehicle-km?$q&vehicle_id=${other.vehicle}", w.token).status, "Fahrzeug eines anderen Mandanten")
        assertEquals(404, env.get("/v1/analytics/vehicle-km?$q&vehicle_id=${UUID.randomUUID()}", w.token).status)
        assertEquals(403, env.get("/v1/analytics/vehicle-km?$q", w.driverToken).status, "Fahrer haben keinen Zugriff")
    }
}
