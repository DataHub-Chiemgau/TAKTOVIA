package de.taktovia.fleetsafety

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.fleetsafety.api.AssignPoolService
import de.taktovia.fleetsafety.api.PoolVerdict
import de.taktovia.platform.geo.GeoPoint
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-06 / LH-6.8-FLT-002 gegen die echte Datenbank (Runtime-Rolle): Fahrzeug erhält nur Fahrten aus aktuell zugeordneten Diensten (Pool-Port), mehrere Dienste gleichzeitig,
 * `valid_to` exklusiv, zeitliche und geografische Einschränkung unabhängig, Fremdmandant, Beenden mit Hinweis auf zugesagte Fahrten, Audit und Ereignisse, konkurrierende Anlage.
 * Der Hold-Pfad (`booking-dispatch`) wird im Anwendungstest `VehiclePoolHoldTest` nachgewiesen (`fleet-safety` darf nicht von `booking-dispatch` abhängen).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VehiclePoolAssignmentTest {
    private lateinit var fx: FleetFixture
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val registry = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012())

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private val t0 = Instant.parse("2030-01-01T00:00:00Z")
    private val t1 = Instant.parse("2030-06-01T00:00:00Z")
    private val past = Instant.parse("2026-01-01T00:00:00Z")   // Beginn in der Vergangenheit: ein Ende ab jetzt liegt nach dem Beginn
    private fun problem(block: () -> Unit): ProblemException = org.junit.jupiter.api.Assertions.assertThrows(ProblemException::class.java, block)

    /** Mandant mit Fixture-Fahrzeug, zwei Diensten und je einem Bediengebiet (Quadrat 0,1 Grad ab Längengrad 8 bzw. 9, Breitengrad 49). */
    private class PoolWorld(val w: FleetFixture.World, val serviceA: UUID, val serviceB: UUID, val areaA: UUID, val areaB: UUID)

    private fun square(x0: Double) = "MULTIPOLYGON((($x0 49.0,${x0 + 0.1} 49.0,${x0 + 0.1} 49.1,$x0 49.1,$x0 49.0)))"

    private fun service(w: FleetFixture.World): UUID {
        val t = w.tenant.value; val cfg = UUID.randomUUID(); val svc = UUID.randomUUID()
        fx.exec("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'SERVICE',?,1,'1',CAST('{}' AS jsonb),'2026-01-01T00:00:00Z','user:t','Test')", t, cfg, svc)
        fx.exec("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,created_by) VALUES (?,?,?,'Dienst','Europe/Berlin',?,'user:t')", t, svc, w.orgs[0], cfg)
        return svc
    }

    private fun area(w: FleetFixture.World, service: UUID, x0: Double, from: String = "2026-01-01T00:00:00Z", to: String? = null, key: UUID? = null, revision: Int = 1): UUID {
        val id = UUID.randomUUID()
        fx.exec("INSERT INTO taktovia.service_area(tenant_id,id,service_id,revision,area,valid_from,valid_to,area_key) VALUES (?,?,?,?,ST_GeomFromText(?,4326),CAST(? AS timestamptz),CAST(? AS timestamptz),?)",
            w.tenant.value, id, service, revision, square(x0), from, to, key)
        return id
    }

    private fun world(): PoolWorld {
        val w = fx.world(); val a = service(w); val b = service(w)
        return PoolWorld(w, a, b, area(w, a, 8.0), area(w, b, 9.0))
    }

    private fun assign(p: PoolWorld, service: UUID, from: Instant, to: Instant? = null, area: UUID? = null, vehicle: UUID = p.w.vehicle) =
        fx.fleet.vehiclePool.assign(fx.context(p.w.tenant), AssignPoolService(vehicle, service, area, from, to))

    private fun eligible(p: PoolWorld, service: UUID, at: Instant, vehicle: UUID = p.w.vehicle, vararg locations: GeoPoint): PoolVerdict =
        fx.fleet.vehiclePool.check(fx.context(p.w.tenant), vehicle, service, at, locations.toList())

    private fun pool(p: PoolWorld, service: UUID, at: Instant, vararg locations: GeoPoint) = fx.fleet.vehiclePool.eligibleVehicles(fx.context(p.w.tenant), service, at, locations.toList()).map { it.vehicleId }

    @Test
    fun fahrzeug_erhaelt_nur_fahrten_zugeordneter_dienste() {
        val p = world(); val other = fx.vehicle(p.w.tenant)
        assign(p, p.serviceA, t0)
        val mid = Instant.parse("2030-03-01T00:00:00Z")
        assertEquals(listOf(p.w.vehicle), pool(p, p.serviceA, mid), "Fahrzeug von Dienst A erhält Fahrten aus A")
        assertEquals(emptyList<UUID>(), pool(p, p.serviceB, mid), "Fahrzeug von Dienst A erhält keine Fahrt aus Dienst B")
        assertEquals(PoolVerdict.NOT_ASSIGNED, eligible(p, p.serviceB, mid))
        assertEquals(PoolVerdict.NOT_ASSIGNED, eligible(p, p.serviceA, mid, other), "Fahrzeug ohne jede Zuordnung erhält keine Fahrt")
        assertEquals(PoolVerdict.NOT_ASSIGNED, eligible(p, p.serviceA, t0.minusMillis(1)), "vor valid_from keine Fahrt")
        // fremder Mandant: Dienst und Fahrzeug sind nicht sichtbar
        val foreign = world()
        assertEquals(emptyList<UUID>(), fx.fleet.vehiclePool.eligibleVehicles(fx.context(foreign.w.tenant), p.serviceA, mid).map { it.vehicleId })
        assertEquals(ErrorCode.NOT_FOUND, problem { assign(foreign, p.serviceA, t0, vehicle = foreign.w.vehicle) }.code, "Dienst eines anderen Mandanten")
        assertEquals(ErrorCode.NOT_FOUND, problem { assign(foreign, foreign.serviceA, t0, vehicle = p.w.vehicle) }.code, "Fahrzeug eines anderen Mandanten")
        assertEquals(ErrorCode.NOT_FOUND, problem { assign(foreign, foreign.serviceA, t0, area = p.areaA) }.code, "Gebiet eines anderen Mandanten")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle_pool_assignment WHERE tenant_id=? AND (service_id=? OR vehicle_id=?)", foreign.w.tenant.value, p.serviceA, p.w.vehicle))
    }

    @Test
    fun valid_to_exklusiv_und_mehrere_dienste() {
        val p = world()
        val a = assign(p, p.serviceA, t0, t1)
        val b = assign(p, p.serviceB, t0)   // gleichzeitig zwei Diensten zugeordnet
        assertEquals(listOf(p.w.vehicle), pool(p, p.serviceA, t1.minusMillis(1)), "vor valid_to noch zulässig")
        assertEquals(PoolVerdict.NOT_ASSIGNED, eligible(p, p.serviceA, t1), "genau zum Zeitpunkt valid_to keine neue Fahrt aus A")
        assertEquals(PoolVerdict.NOT_ASSIGNED, eligible(p, p.serviceA, t1.plusSeconds(3600)), "nach Ablauf keine neue Fahrt aus A")
        assertEquals(PoolVerdict.ELIGIBLE, eligible(p, p.serviceB, t1.plusSeconds(3600)), "aus Dienst B weiterhin")
        assertEquals(PoolVerdict.ELIGIBLE, eligible(p, p.serviceA, t0)); assertEquals(PoolVerdict.ELIGIBLE, eligible(p, p.serviceB, t0), "beide Dienste im Intervall")
        // im selben Dienst: Überschneidung 409, Anschluss (Ende = Beginn) zulässig, ungültiges Intervall 422
        assertEquals(ErrorCode.CONFLICT, problem { assign(p, p.serviceA, Instant.parse("2030-05-01T00:00:00Z"), Instant.parse("2030-08-01T00:00:00Z")) }.code)
        assign(p, p.serviceA, t1, Instant.parse("2030-09-01T00:00:00Z"))
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { assign(p, p.serviceA, Instant.parse("2031-01-01T00:00:00Z"), Instant.parse("2031-01-01T00:00:00Z")) }.code)
        assertEquals(setOf(a.id, b.id), fx.fleet.vehiclePool.list(fx.context(p.w.tenant), p.w.vehicle, t0.plusSeconds(1), 50, null).map { it.id }.toSet(), "as_of: nur die zum Zeitpunkt gültigen")
        assertEquals(3, fx.fleet.vehiclePool.list(fx.context(p.w.tenant), p.w.vehicle, null, 50, null).size)
    }

    @Test
    fun zeitliche_und_geografische_einschraenkung_wirken_unabhaengig() {
        val p = world()
        assign(p, p.serviceA, t0, t1, area = p.areaA)
        val inside = GeoPoint(8.05, 49.05); val outside = GeoPoint(9.05, 49.05)
        val mid = Instant.parse("2030-03-01T00:00:00Z")
        assertEquals(PoolVerdict.ELIGIBLE, eligible(p, p.serviceA, mid, p.w.vehicle, inside), "im Intervall und im Gebiet")
        assertEquals(PoolVerdict.OUTSIDE_SERVICE_AREA, eligible(p, p.serviceA, mid, p.w.vehicle, outside), "im Intervall, außerhalb des Gebiets: keine Fahrt")
        assertEquals(PoolVerdict.NOT_ASSIGNED, eligible(p, p.serviceA, t1, p.w.vehicle, inside), "im Gebiet, außerhalb des Intervalls: keine Fahrt")
        assertEquals(PoolVerdict.OUTSIDE_SERVICE_AREA, eligible(p, p.serviceA, mid, p.w.vehicle, inside, outside), "alle Orte müssen im Gebiet liegen")
        assertEquals(PoolVerdict.OUTSIDE_SERVICE_AREA, eligible(p, p.serviceA, mid), "ohne Ortsangabe ist die Gebietsgrenze nicht prüfbar: nicht zulässig")
        assertEquals(listOf(p.w.vehicle), pool(p, p.serviceA, mid, inside)); assertEquals(emptyList<UUID>(), pool(p, p.serviceA, mid, outside))
        // Zuordnung ohne Gebietsbezug: keine geografische Einschränkung
        val free = fx.vehicle(p.w.tenant); assign(p, p.serviceA, t0, vehicle = free)
        assertEquals(PoolVerdict.ELIGIBLE, eligible(p, p.serviceA, mid, free, outside)); assertEquals(PoolVerdict.ELIGIBLE, eligible(p, p.serviceA, mid, free))
        // Gebiet eines anderen Dienstes: 422 an /service_area_id
        val e = problem { assign(p, p.serviceB, t0, area = p.areaA, vehicle = free) }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code); assertTrue(e.violations.any { it.pointer == "/service_area_id" })
    }

    @Test
    fun gebietsrevision_zum_pruefzeitpunkt_gilt_nicht_die_genannte_zeile() {
        val p = world(); val s = service(p.w)   // eigener Dienst: je Dienst darf sich zu einem Zeitpunkt nur ein Gebiet erstrecken
        val rev1 = area(p.w, s, 8.0, "2026-01-01T00:00:00Z", "2031-01-01T00:00:00Z")
        area(p.w, s, 20.0, "2031-01-01T00:00:00Z", null, key = rev1, revision = 2)   // Nachfolgerevision derselben Kette: anderes Gebiet ab 2031
        assign(p, s, t0, area = rev1)
        assertEquals(PoolVerdict.ELIGIBLE, eligible(p, s, Instant.parse("2030-03-01T00:00:00Z"), p.w.vehicle, GeoPoint(8.05, 49.05)))
        assertEquals(PoolVerdict.OUTSIDE_SERVICE_AREA, eligible(p, s, Instant.parse("2031-03-01T00:00:00Z"), p.w.vehicle, GeoPoint(8.05, 49.05)), "ab 2031 gilt die Nachfolgerevision")
        assertEquals(PoolVerdict.ELIGIBLE, eligible(p, s, Instant.parse("2031-03-01T00:00:00Z"), p.w.vehicle, GeoPoint(20.05, 49.05)))
        // keine zum Zeitpunkt gültige Revision: kein Gebiet, keine Fahrt (Zuordnung beginnt vor der ersten Gebietsrevision)
        val early = fx.vehicle(p.w.tenant); assign(p, s, Instant.parse("2025-06-01T00:00:00Z"), Instant.parse("2025-12-31T00:00:00Z"), area = rev1, vehicle = early)
        assertEquals(PoolVerdict.OUTSIDE_SERVICE_AREA, eligible(p, s, Instant.parse("2025-09-01T00:00:00Z"), early, GeoPoint(8.05, 49.05)))
    }

    @Test
    fun anlegen_und_beenden_auditiert_mit_genau_einem_ereignis_und_schema() {
        val p = world(); val ctx = fx.context(p.w.tenant, "disp-pool")
        val a = fx.fleet.vehiclePool.assign(ctx, AssignPoolService(p.w.vehicle, p.serviceA, p.areaA, past, null))
        val end = Instant.now().plusSeconds(86_400)
        val ended = fx.fleet.vehiclePool.end(ctx, p.w.vehicle, a.id, a.revision, end)
        assertEquals(2, ended.revision); assertEquals(end.toEpochMilli(), ended.validTo!!.toEpochMilli()); assertEquals(emptyList<Any>(), ended.committedRidesAfterEnd)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.pool_assignment.created' AND object_ref=? AND actor_ref='user:disp-pool'", p.w.tenant.value, "vehicle:${p.w.vehicle}"))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.pool_assignment.ended' AND object_ref=? AND actor_ref='user:disp-pool'", p.w.tenant.value, "vehicle:${p.w.vehicle}"))
        fun events(type: String) = fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type=?", p.w.tenant.value, a.id, type)
        assertEquals(1, events("vehicle.pool_assignment_created").size); assertEquals(1, events("vehicle.pool_assignment_ended").size)
        listOf("created", "ended").forEach { k ->
            val schema = registry.getSchema(Files.readString(repo.resolve("api/events/vehicle.pool_assignment_$k.v1.schema.json")))
            events("vehicle.pool_assignment_$k").forEach { assertEquals(emptyList<String>(), schema.validate(it, InputFormat.JSON).map { m -> m.toString() }, it) }
        }
        // Fehlerfälle: veraltete Revision 412, bereits beendet 409, Ende vor Beginn / in der Vergangenheit 422, fremde Zuordnung 404; nichts bleibt zurück
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { fx.fleet.vehiclePool.end(ctx, p.w.vehicle, a.id, 1, end.plusSeconds(1)) }.code)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { fx.fleet.vehiclePool.end(ctx, p.w.vehicle, a.id, 2, end.plusSeconds(1)) }.code)
        val v2 = fx.vehicle(p.w.tenant); val v3 = fx.vehicle(p.w.tenant); val v4 = fx.vehicle(p.w.tenant)
        val b = assign(p, p.serviceB, Instant.now().plusSeconds(3600), vehicle = v2)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { fx.fleet.vehiclePool.end(ctx, v2, b.id, 1, Instant.now().plusSeconds(60)) }.code, "Ende vor dem Beginn")
        val c = assign(p, p.serviceB, t0, Instant.parse("2031-01-01T00:00:00Z"), vehicle = v3)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { fx.fleet.vehiclePool.end(ctx, v3, c.id, 1, Instant.parse("2030-02-01T00:00:00Z")) }.code, "bereits mit Ende versehen")
        val d = assign(p, p.serviceA, Instant.parse("2032-01-01T00:00:00Z"), vehicle = v4)
        val foreign = world()
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.vehiclePool.end(fx.context(foreign.w.tenant), v4, d.id, 1, end) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.vehiclePool.end(ctx, p.w.vehicle, UUID.randomUUID(), 1, end) }.code)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.pool_assignment.ended'", p.w.tenant.value))
        // Ende in der Vergangenheit
        val v5 = fx.vehicle(p.w.tenant); val e = assign(p, p.serviceB, Instant.parse("2020-01-01T00:00:00Z"), vehicle = v5)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { fx.fleet.vehiclePool.end(ctx, v5, e.id, 1, Instant.parse("2020-06-01T00:00:00Z")) }.code)
    }

    /** Zugesagte Fahrt: Schicht, Fahrtenplan, verbrauchter Hold und Buchung (Fixture über den Administrator, wie die Tests von `service-geo`). */
    private fun commitRide(p: PoolWorld, service: UUID, pickupEarliest: Instant, pickupLatest: Instant, state: String = "CONFIRMED"): UUID {
        val t = p.w.tenant.value; val shift = UUID.randomUUID(); val run = UUID.randomUUID(); val hold = UUID.randomUUID(); val booking = UUID.randomUUID()
        fx.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,CAST('2030-01-01 00:00:00+00' AS timestamptz) + (abs(hashtext(CAST(? AS text))) % 20000 || ' days')::interval,CAST('2030-01-01 12:00:00+00' AS timestamptz) + (abs(hashtext(CAST(? AS text))) % 20000 || ' days')::interval,'Europe/Berlin',1,'PUBLISHED')", t, shift, UUID.randomUUID(), p.w.vehicle, shift.toString(), shift.toString())
        fx.exec("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (?,?,?,?,1,'ACTIVE')", t, run, p.w.vehicle, shift)
        fx.exec("INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state) VALUES (?,?,?,1,?,CAST('{\"schema_version\":\"1.0\",\"seats\":1,\"wheelchair\":0,\"equipment\":[]}' AS jsonb),0,1,now(),'CONSUMED')", t, hold, run, "h-$hold")
        fx.exec("INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision) VALUES (?,?,?,?,?,?,CAST(? AS jsonb),1)", t, booking, hold, service, "b-$booking", state,
            """{"pickup_earliest":"$pickupEarliest","pickup_latest":"$pickupLatest"}""")
        return booking
    }

    @Test
    fun beendete_zuordnung_zugesagte_fahrt_bleibt_mit_hinweis() {
        val p = world(); val ctx = fx.context(p.w.tenant)
        val a = assign(p, p.serviceA, past); assign(p, p.serviceB, past)
        val end = Instant.now().plusSeconds(10 * 86_400)
        val after = commitRide(p, p.serviceA, end.plusSeconds(3600), end.plusSeconds(4500))             // nach dem Ende: Hinweis
        val straddle = commitRide(p, p.serviceA, end.minusSeconds(600), end.plusSeconds(600))          // Abholfenster überspannt das Ende: Hinweis
        commitRide(p, p.serviceA, end.minusSeconds(86_400), end.minusSeconds(85_000))                  // klar vor dem Ende: kein Hinweis
        commitRide(p, p.serviceB, end.plusSeconds(3600), end.plusSeconds(4500))                        // anderer Dienst: kein Hinweis
        commitRide(p, p.serviceA, end.plusSeconds(7200), end.plusSeconds(8000), state = "CANCELED")    // storniert: kein Hinweis
        val rideRows = fx.strings("SELECT b.id::text || b.state || h.state FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.id=b.hold_id WHERE b.tenant_id=? ORDER BY b.id", p.w.tenant.value)
        val ended = fx.fleet.vehiclePool.end(ctx, p.w.vehicle, a.id, 1, end)
        assertEquals(setOf(after, straddle), ended.committedRidesAfterEnd.map { it.bookingId }.toSet(), "nur zugesagte Fahrten des Dienstes ab dem Ende")
        assertEquals(rideRows, fx.strings("SELECT b.id::text || b.state || h.state FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.id=b.hold_id WHERE b.tenant_id=? ORDER BY b.id", p.w.tenant.value), "Fahrten werden nicht still entfernt oder geändert")
        // der Hinweis geht als Ereignis an die Leitstelle und bleibt in der Liste sichtbar
        val ev = fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='vehicle.pool_assignment_ended'", p.w.tenant.value, a.id).single()
        assertTrue(ev.contains(after.toString()) && ev.contains(straddle.toString()) && ev.contains("\"committed_ride_count\": 2") || ev.contains("\"committed_ride_count\":2"), ev)
        assertEquals(setOf(after, straddle), fx.fleet.vehiclePool.list(ctx, p.w.vehicle, null, 50, null).single { it.id == a.id }.committedRidesAfterEnd.map { it.bookingId }.toSet())
        // neue Fahrten aus A nach dem Ende gibt es nicht mehr, aus B schon
        assertEquals(PoolVerdict.NOT_ASSIGNED, eligible(p, p.serviceA, end)); assertEquals(PoolVerdict.ELIGIBLE, eligible(p, p.serviceB, end))
    }

    @Test
    fun paralleles_anlegen_im_selben_dienst_genau_eine_in_verschiedenen_diensten_beide() {
        val p = world()
        val pool = Executors.newFixedThreadPool(8); val start = CountDownLatch(1)
        try {
            val results = (0 until 8).map { i -> pool.submit<String> { start.await(); try { assign(p, if (i == 7) p.serviceB else p.serviceA, Instant.parse("2035-01-01T00:00:00Z")); if (i == 7) "B" else "A" } catch (e: ProblemException) { e.code.name } } }
            start.countDown()
            val r = results.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, r.count { it == "A" }, r.toString()); assertEquals(1, r.count { it == "B" }, r.toString()); assertEquals(6, r.count { it == "CONFLICT" }, r.toString())
            assertEquals(2, fx.count("SELECT count(*) FROM taktovia.vehicle_pool_assignment WHERE tenant_id=? AND vehicle_id=?", p.w.tenant.value, p.w.vehicle))
            assertEquals(2, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='vehicle.pool_assignment_created'", p.w.tenant.value), "kein Ereignis für abgewiesene Anlagen")
        } finally { pool.shutdownNow() }
    }
}
