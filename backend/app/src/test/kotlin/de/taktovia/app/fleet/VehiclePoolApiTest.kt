package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * WP-M3-06 / LH-6.8-FLT-002 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): Dienstzuordnungen als Teilressource `/v1/operator/vehicles/{id}/service-assignments`.
 * Rechte (Disponent schreibt, Support liest, Fahrgast nichts), Anlegen/Lesen/Beenden mit ETag und If-Match, 404 für fremde Mandanten, 409/422, Audit und Ereignis, Hinweis auf zugesagte Fahrten.
 */
class VehiclePoolApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    class Pool(val w: VehicleMasterApiTest.World, val env: IdentityEnv) {
        val vehicle: String = env.post("/v1/operator/vehicle-master", w.body(), w.dispatcherToken, null, mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())).json["id"].asString()
        val serviceA = service(); val serviceB = service(); val area = area(serviceA)
        fun service(): UUID {
            val t = w.tenant.value; val cfg = UUID.randomUUID(); val svc = UUID.randomUUID()
            env.exec("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'SERVICE',?,1,'1',CAST('{}' AS jsonb),'2026-01-01T00:00:00Z','user:t','Test')", t, cfg, svc)
            env.exec("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,created_by) VALUES (?,?,?,'Dienst','Europe/Berlin',?,'user:t')", t, svc, w.orgs[0], cfg)
            return svc
        }
        fun area(service: UUID): UUID {
            val id = UUID.randomUUID()
            env.exec("INSERT INTO taktovia.service_area(tenant_id,id,service_id,revision,area,valid_from) VALUES (?,?,?,1,ST_GeomFromText('MULTIPOLYGON(((8.0 49.0,8.1 49.0,8.1 49.1,8.0 49.1,8.0 49.0)))',4326),'2026-01-01T00:00:00Z')", w.tenant.value, id, service)
            return id
        }
        fun body(service: UUID, from: String = "2026-01-01T00:00:00Z", to: String? = null, area: UUID? = null) =
            """{"service_id":"$service","valid_from":"$from"${if (to != null) ""","valid_to":"$to"""" else ""}${if (area != null) ""","service_area_id":"$area"""" else ""}}"""
    }

    private fun pool() = Pool(VehicleMasterApiTest.World(env), env)
    private fun path(p: Pool, extra: String = "") = "/v1/operator/vehicles/${p.vehicle}/service-assignments$extra"

    @Test
    fun anlegen_lesen_beenden_ueber_die_api_mit_rechten_audit_und_ereignis() {
        val p = pool(); val w = p.w
        val c = env.post(path(p), p.body(p.serviceA, area = p.area), w.dispatcherToken, null, idem())
        assertEquals(201, c.status, c.text); assertEquals("\"1\"", c.header("ETag"))
        val id = c.json["id"].asString(); assertEquals("/v1/operator/vehicles/${p.vehicle}/service-assignments/$id", c.header("Location"))
        assertEquals(p.area.toString(), c.json["service_area_id"].asString()); assertTrue(c.json["valid_to"].isNull); assertEquals(0, c.json["committed_rides_after_end"].size())
        // ein Fahrzeug kann mehreren Diensten zugeordnet sein
        assertEquals(201, env.post(path(p), p.body(p.serviceB), w.dispatcherToken, null, idem()).status)
        val list = env.get(path(p), w.supportToken); assertEquals(200, list.status, list.text)
        assertEquals(2, list.json["items"].size()); assertTrue(list.json["next_cursor"].isNull)
        assertEquals(2, env.get(path(p) + "?as_of=2026-06-01T00:00:00Z", w.supportToken).json["items"].size())
        assertEquals(0, env.get(path(p) + "?as_of=2025-06-01T00:00:00Z", w.supportToken).json["items"].size())
        assertEquals(422, env.get(path(p) + "?as_of=gestern", w.supportToken).status)
        // Rechte: Support liest nur, Fahrgast nichts
        assertEquals(403, env.post(path(p), p.body(p.serviceB, "2040-01-01T00:00:00Z"), w.supportToken, null, idem()).status)
        assertEquals(403, env.get(path(p), w.passengerToken).status)
        // Überschneidung im selben Dienst 409; Feldfehler 422; Gebiet eines anderen Dienstes 422
        assertEquals(409, env.post(path(p), p.body(p.serviceA, "2027-01-01T00:00:00Z"), w.dispatcherToken, null, idem()).status)
        assertEquals(422, env.post(path(p), """{"valid_from":"2026-01-01T00:00:00Z"}""", w.dispatcherToken, null, idem()).status)
        assertEquals(422, env.post(path(p), """{"service_id":"${p.serviceB}","valid_from":"2040-01-01T00:00:00Z","valid_to":"2039-01-01T00:00:00Z"}""", w.dispatcherToken, null, idem()).status)
        assertEquals(422, env.post(path(p), p.body(p.serviceB, "2040-01-01T00:00:00Z", area = p.area), w.dispatcherToken, null, idem()).status)
        // Beenden: ohne If-Match 428, veraltet 412, Ende in der Vergangenheit 422, dann 200 mit neuer Revision
        val end = Instant.now().plusSeconds(3 * 86_400).toString()
        assertEquals(428, env.server.send("POST", path(p, "/$id/end"), """{"valid_to":"$end"}""", env.headers(w.dispatcherToken, extra = idem())).status)
        assertEquals(412, env.post(path(p, "/$id/end"), """{"valid_to":"$end"}""", w.dispatcherToken, null, idem() + ("If-Match" to "\"9\"")).status)
        assertEquals(422, env.post(path(p, "/$id/end"), """{"valid_to":"2026-02-01T00:00:00Z"}""", w.dispatcherToken, null, idem() + ("If-Match" to "\"1\"")).status)
        val ended = env.post(path(p, "/$id/end"), """{"valid_to":"$end"}""", w.dispatcherToken, null, idem() + ("If-Match" to "\"1\""))
        assertEquals(200, ended.status, ended.text); assertEquals("\"2\"", ended.header("ETag")); assertEquals(2, ended.json["revision"].asLong())
        assertEquals(409, env.post(path(p, "/$id/end"), """{"valid_to":"$end"}""", w.dispatcherToken, null, idem() + ("If-Match" to "\"2\"")).status)
        assertEquals(2, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.pool_assignment.created' AND object_ref=? AND actor_ref=?", w.tenant.value, "vehicle:${p.vehicle}", "user:${w.dispatcher}"), "zwei Zuordnungen, je ein Audit")
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.pool_assignment.ended' AND object_ref=? AND actor_ref=?", w.tenant.value, "vehicle:${p.vehicle}", "user:${w.dispatcher}"))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='vehicle.pool_assignment_ended'", w.tenant.value, UUID.fromString(id)))
    }

    @Test
    fun fremder_mandant_ergibt_404_und_nichts_wird_gespeichert() {
        val p = pool(); val other = pool()
        val c = env.post(path(p), p.body(p.serviceA), p.w.dispatcherToken, null, idem()); assertEquals(201, c.status, c.text); val id = c.json["id"].asString()
        // Fahrzeug eines anderen Mandanten
        assertEquals(404, env.get(path(p), other.w.supportToken).status)
        assertEquals(404, env.post(path(p), other.body(other.serviceA, "2040-01-01T00:00:00Z"), other.w.dispatcherToken, null, idem()).status)
        assertEquals(404, env.post(path(p, "/$id/end"), """{"valid_to":"${Instant.now().plusSeconds(86_400)}"}""", other.w.dispatcherToken, null, idem() + ("If-Match" to "\"1\"")).status)
        // Dienst und Gebiet eines anderen Mandanten am eigenen Fahrzeug
        assertEquals(404, env.post(path(other), other.body(p.serviceA, "2040-01-01T00:00:00Z"), other.w.dispatcherToken, null, idem()).status)
        assertEquals(404, env.post(path(other), other.body(other.serviceA, "2040-01-01T00:00:00Z", area = p.area), other.w.dispatcherToken, null, idem()).status)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.vehicle_pool_assignment WHERE tenant_id=?", other.w.tenant.value))
        assertEquals(404, env.get("/v1/operator/vehicles/${UUID.randomUUID()}/service-assignments", p.w.supportToken).status)
    }

    @Test
    fun beenden_nennt_zugesagte_fahrten_als_hinweis_und_aendert_sie_nicht() {
        val p = pool(); val w = p.w; val t = w.tenant.value
        val id = env.post(path(p), p.body(p.serviceA), w.dispatcherToken, null, idem()).json["id"].asString()
        val end = Instant.now().plusSeconds(5 * 86_400)
        val shift = UUID.randomUUID(); val run = UUID.randomUUID(); val hold = UUID.randomUUID(); val booking = UUID.randomUUID(); val vehicle = UUID.fromString(p.vehicle)
        env.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,CAST('2030-01-01 00:00:00+00' AS timestamptz) + (abs(hashtext(CAST(? AS text))) % 20000 || ' days')::interval,CAST('2030-01-01 12:00:00+00' AS timestamptz) + (abs(hashtext(CAST(? AS text))) % 20000 || ' days')::interval,'Europe/Berlin',1,'PUBLISHED')", t, shift, UUID.randomUUID(), vehicle, shift.toString(), shift.toString())
        env.exec("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (?,?,?,?,1,'ACTIVE')", t, run, vehicle, shift)
        env.exec("INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state) VALUES (?,?,?,1,?,CAST('{\"schema_version\":\"1.0\",\"seats\":1,\"wheelchair\":0,\"equipment\":[]}' AS jsonb),0,1,now(),'CONSUMED')", t, hold, run, "h-$hold")
        env.exec("INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision) VALUES (?,?,?,?,?,'CONFIRMED',CAST(? AS jsonb),1)", t, booking, hold, p.serviceA, "b-$booking",
            """{"pickup_earliest":"${end.plusSeconds(3600)}","pickup_latest":"${end.plusSeconds(4500)}"}""")
        val before = env.string("SELECT b.state || h.state || b.revision FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.id = b.hold_id WHERE b.id = ?", booking)
        val ended = env.post(path(p, "/$id/end"), """{"valid_to":"$end"}""", w.dispatcherToken, null, idem() + ("If-Match" to "\"1\""))
        assertEquals(200, ended.status, ended.text)
        assertEquals(booking.toString(), ended.json["committed_rides_after_end"].values().single()["booking_id"].asString())
        assertEquals(before, env.string("SELECT b.state || h.state || b.revision FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.id = b.hold_id WHERE b.id = ?", booking), "die Fahrt bleibt unverändert")
        assertEquals(booking.toString(), env.get(path(p), w.supportToken).json["items"].values().single()["committed_rides_after_end"].values().single()["booking_id"].asString(), "und bleibt sichtbar")
    }
}
