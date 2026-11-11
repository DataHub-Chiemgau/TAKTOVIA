package de.taktovia.servicegeo

import de.taktovia.config.api.ConfigApi
import de.taktovia.config.api.CreateDraft
import de.taktovia.config.api.ImpactSeverity
import de.taktovia.config.api.ScopeKind
import de.taktovia.platform.outbox.Outbox
import de.taktovia.servicegeo.api.CreateServiceArea
import de.taktovia.servicegeo.api.ServicegeoApi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * WP-M2-04 Kriterium 6: der ImpactProvider „Bediengebiet“ nennt für eine Gebietsverkleinerung die bestätigten Buchungen mit Start oder Ziel außerhalb des neuen Gebiets
 * (und keine innerhalb liegenden, keine stornierten, keine vergangenen). Echte Buchungen, Fahrtenplan und Haltepunkte in der Datenbank; der Weg läuft über die
 * Wirkungsanalyse des Konfigurationsmoduls (WP-M1-23).
 */
class ServiceAreaImpactProviderTest {
    private val fx = fixture
    private val config: ConfigApi = ConfigApi.create(fx.ctxTx, fx.audit, Outbox(), revisionValidators = listOf(ServicegeoApi.offerModelValidator()), impactProviders = listOf(ServicegeoApi.areaImpactProvider()))
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private val effective = Instant.parse("2026-12-01T00:00:00Z")

    private fun stop(tenant: UUID, lon: Double, lat: Double): UUID {
        val id = UUID.randomUUID()
        fx.adminUpdate("INSERT INTO taktovia.stop_revision(tenant_id,id,stop_id,revision,point,name,accessibility,approved_by,valid_from) VALUES (?,?,?,1,ST_SetSRID(ST_MakePoint(?,?),4326),'Halt','{}'::jsonb,'test','2026-01-01Z')",
            tenant, id, UUID.randomUUID(), lon, lat)
        return id
    }

    /** Bestätigte (oder andere) Buchung mit Abholung und Ziel an den Haltepunkten; liefert die Buchungs-ID. */
    private fun booking(tenant: UUID, service: UUID, from: UUID, to: UUID, state: String = "CONFIRMED", plannedAt: String = "2026-12-05T08:00:00Z"): UUID {
        val vehicle = UUID.randomUUID(); val shift = UUID.randomUUID(); val run = UUID.randomUUID(); val hold = UUID.randomUUID(); val booking = UUID.randomUUID()
        fx.adminUpdate("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'x','{\"seats\":4}'::jsonb)", tenant, vehicle)
        fx.adminUpdate("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,'2026-12-05 04:00+00','2026-12-05 14:00+00','Europe/Berlin',1,'PUBLISHED')",
            tenant, shift, UUID.randomUUID(), vehicle)
        fx.adminUpdate("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (?,?,?,?,1,'ACTIVE')", tenant, run, vehicle, shift)
        fx.adminUpdate("INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state) VALUES (?,?,?,1,?,'{\"seats\":1}'::jsonb,0,1,'2027-01-01Z','CONSUMED')",
            tenant, hold, run, "h-$hold")
        fx.adminUpdate("INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision) VALUES (?,?,?,?,?,?,'{}'::jsonb,1)", tenant, booking, hold, service, "b-$booking", state)
        val entry = """[{"booking_id":"$booking","vector":{"schema_version":"1.0","seats":1}}]"""
        fx.adminUpdate("INSERT INTO taktovia.route_stop(tenant_id,id,run_id,run_revision,seq,stop_revision_id,planned_at,boardings,alightings,leg_duration_sec,leg_distance_m) VALUES (?,?,?,1,0,?,CAST(? AS timestamptz),CAST(? AS jsonb),'[]'::jsonb,60,500)",
            tenant, UUID.randomUUID(), run, from, plannedAt, entry)
        fx.adminUpdate("INSERT INTO taktovia.route_stop(tenant_id,id,run_id,run_revision,seq,stop_revision_id,planned_at,boardings,alightings) VALUES (?,?,?,1,1,?,CAST(? AS timestamptz),'[]'::jsonb,CAST(? AS jsonb))",
            tenant, UUID.randomUUID(), run, to, plannedAt, entry)
        return booking
    }

    private fun serviceConfig(op: UUID, area: UUID) = mapOf("service_config" to mapOf(
        "service_area_id" to area.toString(), "traffic_type" to "AREA_ON_DEMAND", "operator_id" to op.toString(),
        "operating_hours" to listOf(mapOf("weekdays" to listOf("MON"), "from" to "06:00", "to" to "22:00")), "tariff_id" to UUID.randomUUID().toString()))

    @Test
    fun verkleinerung_nennt_betroffene_bestaetigte_buchungen() {
        val w = fx.world(); val ctx = fx.context(w.tenant, scopes = GeoTestSupport.GEO_SCOPES + setOf("config:read", "config:write", "config:publish"))
        val svc = GeoTestSupport.service(fx, w, ctx); val tenant = w.tenant.value
        // großes Bediengebiet (aktueller Stand) und das verkleinerte Gebiet (Kette B)
        val big = fx.geo.areas.create(ctx, CreateServiceArea(svc, GeoTestSupport.square(13.0, 51.0, 0.2), t0, Instant.parse("2026-12-01T00:00:00Z")))
        val small = fx.geo.areas.create(ctx, CreateServiceArea(svc, GeoTestSupport.square(13.0, 51.0, 0.1), Instant.parse("2026-12-01T00:00:00Z"), null))
        val inA = stop(tenant, 13.02, 51.02); val inB = stop(tenant, 13.05, 51.05)               // beide im kleinen Gebiet
        val outsideSmall = stop(tenant, 13.15, 51.15)                                              // nur im großen Gebiet
        val boundary = stop(tenant, 13.1, 51.05)                                                   // auf der Grenze des kleinen Gebiets: zählt als innerhalb
        val farOut = stop(tenant, 14.5, 52.5)
        val bothInside = booking(tenant, svc, inA, inB)
        val onBoundary = booking(tenant, svc, inA, boundary)
        val startOutside = booking(tenant, svc, outsideSmall, inB)
        val destOutside = booking(tenant, svc, inA, outsideSmall)
        val bothOutside = booking(tenant, svc, farOut, outsideSmall)
        val canceled = booking(tenant, svc, outsideSmall, inA, state = "CANCELED")
        val past = booking(tenant, svc, outsideSmall, inA, plannedAt = "2026-11-01T08:00:00Z")
        val otherService = booking(tenant, GeoTestSupport.service(fx, w, ctx), outsideSmall, inA)

        // Dienststand mit dem großen Gebiet veröffentlichen, dann auf das kleine umstellen und die Wirkung analysieren
        val first = config.revisions.createDraft(ctx, CreateDraft(ScopeKind.SERVICE, svc, "1", serviceConfig(w.operator, big.id), t0, null, "Erststand"))
        publish(w, first.id)
        val shrink = config.revisions.createDraft(ctx, CreateDraft(ScopeKind.SERVICE, svc, "1", serviceConfig(w.operator, small.id), effective, null, "Bediengebiet verkleinert"))
        val report = config.impact.analyze(ctx, shrink.id, config.revisions.get(ctx, shrink.id).rowVersion)

        val booking = report.affected.single { it.kind == "BOOKING" }
        assertEquals(ImpactSeverity.BLOCKING, booking.severity)
        assertEquals(setOf(startOutside, destOutside, bothOutside), booking.refs.map { it.id }.toSet(), "genau die bestätigten Buchungen mit Start oder Ziel außerhalb")
        assertEquals(3L, booking.count)
        val named = booking.refs.map { it.id }.toSet()
        listOf(bothInside, onBoundary, canceled, past, otherService).forEach { assertTrue(it !in named, "nicht betroffen: $it") }
    }

    @Test
    fun ohne_aenderung_des_gebietsbezugs_oder_ohne_bekanntes_gebiet_keine_befunde() {
        val w = fx.world(); val ctx = fx.context(w.tenant, scopes = GeoTestSupport.GEO_SCOPES + setOf("config:read", "config:write", "config:publish"))
        val svc = GeoTestSupport.service(fx, w, ctx); val tenant = w.tenant.value
        val a = stop(tenant, 40.0, 40.0); booking(tenant, svc, a, a)
        val first = config.revisions.createDraft(ctx, CreateDraft(ScopeKind.SERVICE, svc, "1", serviceConfig(w.operator, UUID.randomUUID()), t0, null, "Erststand"))
        publish(w, first.id)
        val unknownArea = config.revisions.createDraft(ctx, CreateDraft(ScopeKind.SERVICE, svc, "1", serviceConfig(w.operator, UUID.randomUUID()), effective, null, "anderes Gebiet, unbekannt"))
        assertTrue(config.impact.analyze(ctx, unknownArea.id, config.revisions.get(ctx, unknownArea.id).rowVersion).affected.none { it.kind == "BOOKING" })
    }

    private fun publish(w: StructureFixture.World, id: UUID) {
        for ((state, extra) in listOf("VALIDATED" to "", "APPROVED" to ", approved_by = 'user:zweite-person'", "PUBLISHED" to ", published_at = clock_timestamp()")) {
            fx.tx.run(w.tenant) { j -> j.update("UPDATE taktovia.config_revision SET state = ?$extra WHERE id = ?", state, id) }
        }
    }

    companion object {
        private val fixture by lazy { StructureFixture() }
        @JvmStatic @AfterAll fun close() { fixture.close() }
    }
}
