package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.ReportDefect
import de.taktovia.fleetsafety.api.WorkflowEventKinds
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID

/**
 * WP-M3-20 / LH-6.22-DMG-003, Herkunft Störung: eine aus einer Betriebsstörung erzeugte Schadenakte trägt die Incident-Referenz und ist vom Incident aus erreichbar; Fahrer, fremder Mandant, fremdes
 * Fahrzeug und fremder Dienst werden abgewiesen.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefectFromIncidentTest {
    private lateinit var fx: FleetFixture

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)

    private fun raw(m: IncidentWorld, incident: UUID, vehicle: UUID = m.vehicle, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = linkedMapOf<String, Any?>(
        "vehicle_id" to vehicle.toString(), "occurred_at" to Instant.now().minusSeconds(300).toString(), "category_code" to "DRIVETRAIN", "description" to "Fahrzeug bleibt liegen",
        "source_incident_id" to incident.toString()).also { it.putAll(extra) }

    @Test
    fun akte_aus_stoerung_traegt_incident_referenz() {
        val m = IncidentWorld(fx); val ctx = m.ctx("dispatcher", scopes = setOf("defects:read", "defects:write", "operator:read"))
        val incident = m.handle(m.event(WorkflowEventKinds.DISRUPTION_REPORTED)).incidentId!!
        val r = m.fleet.defects.report(ctx, ReportDefect.parse(raw(m, incident)))
        assertTrue(r.created); assertEquals(incident, r.defect.sourceIncidentId); assertEquals("NEW", r.defect.status); assertEquals(null, r.defect.sourceInspectionId)
        // Akte -> Incident und Incident -> Akte
        assertEquals(incident, m.fleet.defects.get(ctx, r.defect.id).sourceIncidentId)
        assertEquals(listOf(r.defect.id), m.fleet.incidents.get(ctx, incident).defectIds)
        // Wiederholung mit derselben Geräteereignis-ID: dieselbe Akte; eine zweite, andere Akte zum selben Incident ist erlaubt
        val ev = UUID.randomUUID().toString()
        val a = m.fleet.defects.report(ctx, ReportDefect.parse(raw(m, incident, extra = mapOf("device_event_id" to ev))))
        val b = m.fleet.defects.report(ctx, ReportDefect.parse(raw(m, incident, extra = mapOf("device_event_id" to ev))))
        assertTrue(a.created); assertFalse(b.created); assertEquals(a.defect.id, b.defect.id)
        assertEquals(setOf(r.defect.id, a.defect.id), m.fleet.incidents.get(ctx, incident).defectIds.toSet())
        // Ereignis nennt den Incident, ohne Freitext
        val payload = tools.jackson.databind.json.JsonMapper.builder().build().readTree(fx.strings("SELECT payload::text FROM taktovia.outbox WHERE aggregate_id=? AND event_type='defect.reported'", r.defect.id).single())
        assertEquals(incident.toString(), payload["source_incident_id"].asString()); assertFalse(payload.has("description"))
    }

    @Test
    fun abweisungen_fahrer_fremder_mandant_fremdes_fahrzeug_und_gleichzeitige_herkunft() {
        val m = IncidentWorld(fx); val ctx = m.ctx("dispatcher"); val other = IncidentWorld(fx)
        val incident = m.handle(m.event(WorkflowEventKinds.VEHICLE_BLOCKED)).incidentId!!
        val foreignIncident = other.handle(other.event(WorkflowEventKinds.VEHICLE_BLOCKED)).incidentId!!
        // fremder Mandant: Incident unbekannt (404)
        assertEquals(ErrorCode.NOT_FOUND, problem { m.fleet.defects.report(ctx, ReportDefect.parse(raw(m, foreignIncident))) }.code)
        // ein Fahrzeug, das nicht zum Incident gehört: 422 am Feld
        val otherVehicle = fx.vehicle(m.tenant)
        assertEquals("/source_incident_id", problem { m.fleet.defects.report(ctx, ReportDefect.parse(raw(m, incident, vehicle = otherVehicle))) }.violations.single().pointer)
        // ein Fahrer kennt keine Incidents (404), auch nicht mit Bezug zum Fahrzeug
        val driver = UUID.randomUUID(); fx.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,now() - interval '1 hour', now() + interval '5 hours','Europe/Berlin',0,'PUBLISHED')", m.tenant.value, UUID.randomUUID(), driver, m.vehicle)
        assertEquals(ErrorCode.NOT_FOUND, problem { m.fleet.defects.report(m.ctx("driver", tenant = m.tenant).let { de.taktovia.platform.context.RequestContext(de.taktovia.platform.context.Actor(de.taktovia.platform.context.ActorType.HUMAN, "https://idp.test/realms/fleet", driver.toString(), "sid-$driver", true), m.tenant, setOf("driver"), emptySet(), emptySet(), UUID.randomUUID()) }, ReportDefect.parse(raw(m, incident))) }.code)
        // Prüfschritt und Incident zugleich: 422
        assertEquals(ViolationCode.INVALID_VALUE, problem { ReportDefect.parse(raw(m, incident, extra = mapOf("source_inspection_id" to UUID.randomUUID().toString(), "source_step_code" to "S"))) }.violations.single().code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", m.tenant.value))
    }
}
