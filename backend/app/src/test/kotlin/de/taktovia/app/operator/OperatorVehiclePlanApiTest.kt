package de.taktovia.app.operator

import de.taktovia.app.driver.DriverRideSupport
import de.taktovia.app.driver.DriverRunHttpWorld
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M3-38 / LH-6.7-OPS-003 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): `GET /v1/operator/vehicles/{vehicle_id}` liefert den Fahrzeugplan (Feld `plan`). Zwei Fahrzeuge mit
 * verschiedenen Plänen; Dienstscope, fremder Mandant, Leerzustand, Besetzung aus echten Fahrerereignissen und bestätigte Klärfälle.
 */
class OperatorVehiclePlanApiTest {
    private val sup = DriverRideSupport()
    private val w: DriverRunHttpWorld = sup.w
    private val env = sup.env
    private val service = UUID.fromString(w.s.world.service)
    private val dispatcher = w.s.operator(listOf(service))

    private fun plan(vehicle: String, token: String = dispatcher) = env.get("/v1/operator/vehicles/$vehicle", token).also { check(it.status == 200) { "${it.status} ${it.text}" } }.json["plan"]

    @Test
    fun umschalten_zwischen_fahrzeugen_liefert_jeweils_nur_den_eigenen_plan() {
        val a = sup.ride(); val b = sup.ride()
        val pa = plan(sup.vehicleOf(a.d)); val pb = plan(sup.vehicleOf(b.d))
        assertEquals(a.d.run.toString(), pa["run_id"].asString()); assertEquals(b.d.run.toString(), pb["run_id"].asString())
        assertEquals(setOf(a.booking.id.toString()), pa["stops"].values().flatMap { s -> s["boarding_booking_ids"].values().map { it.asString() } }.toSet())
        assertEquals(setOf(b.booking.id.toString()), pb["stops"].values().flatMap { s -> s["boarding_booking_ids"].values().map { it.asString() } }.toSet())
        assertEquals(2, pa["stops"].size()); assertEquals(listOf(1, 2), pa["stops"].values().map { it["sequence"].asInt() })
        // Soll, Prognose (Planzeit aus dem Routing, gekennzeichnet) und Zusagefenster sind getrennte Felder
        val first = pa["stops"][0]
        assertTrue(first.has("scheduled_at") && first.has("forecast") && first.has("promise_windows"))
        assertEquals("PLAN_TIME", first["forecast"]["basis"].asString()); assertEquals("PLAN_TIME", pa["forecast_source"].asString())
        assertEquals("PICKUP", first["promise_windows"][0]["kind"].asString())
        // Planänderung an A ändert den Plan von B nicht
        w.plan(a.d.run, listOf(a.booking), base = w.t0.plusSeconds(600))
        val pa2 = plan(sup.vehicleOf(a.d)); val pb2 = plan(sup.vehicleOf(b.d))
        assertEquals(pa["plan_revision"].asLong() + 1, pa2["plan_revision"].asLong()); assertEquals(pb["plan_revision"].asLong(), pb2["plan_revision"].asLong())
        assertEquals(pb["stops"].toString(), pb2["stops"].toString())
        // die Liste enthält den Plan nicht (nur das Detail)
        assertFalse(env.get("/v1/operator/vehicles", dispatcher).text.contains("\"stops\""))
    }

    @Test
    fun fremder_dienst_fremder_mandant_und_fahrzeug_ohne_run() {
        val a = sup.ride(); val vehicle = sup.vehicleOf(a.d)
        // Disponent eines anderen Dienstes: das Fahrzeug existiert für ihn nicht (404, nie 403)
        val otherService = w.s.operator(listOf(UUID.randomUUID()))
        assertEquals(404, env.get("/v1/operator/vehicles/$vehicle", otherService).status)
        // fremder Mandant
        val foreign = DriverRideSupport().w.s.operator(listOf(UUID.randomUUID()))
        assertEquals(404, env.get("/v1/operator/vehicles/$vehicle", foreign).status)
        assertEquals(404, env.get("/v1/operator/vehicles/${UUID.randomUUID()}", dispatcher).status)
        // Fahrzeug im Pool des Dienstes, aber ohne Umlauf: Leerzustand mit 200
        val empty = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label) VALUES (?,?,'fixture','{\"schema_version\":\"1.0\",\"seats\":3,\"wheelchair\":0,\"equipment\":[]}',?)", w.tenant.value, empty, "LEER-1")
        env.exec("INSERT INTO taktovia.vehicle_pool_assignment(tenant_id,id,service_id,vehicle_id,valid_from,created_by) VALUES (?,?,?,?,now() - interval '1 day','user:t')", w.tenant.value, UUID.randomUUID(), service, empty)
        val p = plan(empty.toString())
        assertTrue(p["run_id"].isNull); assertEquals(0, p["stops"].size()); assertEquals(0, p["occupancy"]["seats"].asInt()); assertEquals("NONE", p["forecast_source"].asString())
        // Fahrer hat keinen Zugriff
        assertEquals(403, env.get("/v1/operator/vehicles/$vehicle", a.d.token).status)
    }

    @Test
    fun besetzung_folgt_bestaetigten_fahrerereignissen_und_klaerfaelle_werden_ausgewiesen() {
        val r = sup.ride(); val vehicle = sup.vehicleOf(r.d)
        assertEquals(0, plan(vehicle)["occupancy"]["seats"].asInt())
        assertEquals(200, sup.event(r, "ARRIVAL", r.pickup).status)
        val checkIn = sup.event(r, "CHECK_IN", r.pickup); assertEquals(200, checkIn.status, checkIn.text)
        val boarded = plan(vehicle)
        assertEquals(1, boarded["occupancy"]["seats"].asInt(), "ein bestätigter Einstieg"); assertEquals(0, boarded["review_required"].size())
        // Einstieg am falschen Stopp ist ein Klärfall: nicht in der Besetzung, aber ausgewiesen
        val bad = sup.event(r, "CHECK_OUT", r.pickup)
        assertEquals("REVIEW_REQUIRED", bad.json["state"].asString(), bad.text)
        val after = plan(vehicle)
        assertEquals(1, after["occupancy"]["seats"].asInt()); assertEquals(1, after["review_required"].size()); assertEquals("OUTSIDE_STOP", after["review_required"][0]["reason_code"].asString())
        // regulärer Ausstieg
        assertEquals(200, sup.event(r, "ARRIVAL", r.dropoff).status)
        assertEquals(200, sup.event(r, "CHECK_OUT", r.dropoff).status)
        assertEquals(0, plan(vehicle)["occupancy"]["seats"].asInt())
    }
}
