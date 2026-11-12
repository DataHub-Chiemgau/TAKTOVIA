package de.taktovia.stafftime

import de.taktovia.stafftime.api.PublishRoster
import de.taktovia.stafftime.api.RosterShiftInput
import java.time.Instant
import java.util.UUID

/**
 * Welt der Besetzungstests (WP-M4-17): Mandant mit Betreiber, Depot, Dienst und Fahrzeugen auf Basis von [RosterTestWorld]; Schichten entstehen über den echten Dienstplanweg
 * (Entwurf anlegen, veröffentlichen) oder, für Sonderzustände, per Administrator-SQL. Eigene Klasse je Paket, keine Duplikate anderer Fixtures.
 */
class ShiftStaffingTestWorld(val fx: StaffFixture) {
    val w = RosterTestWorld(fx)
    val tenant get() = w.tenant
    val planner = w.planner()
    val reader = StaffM4Support.employee(w.tenant)
    val service: UUID = UUID.randomUUID()

    init {
        val org = w.operator; val rev = UUID.randomUUID()
        fx.tx.run(tenant) { j ->
            j.update("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'SERVICE',?,1,'1','{}','2026-01-01','user:test','Test')", tenant.value, rev, service)
            j.update("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,created_by) VALUES (?,?,?,'Rufbus','Europe/Berlin',?,'user:test')", tenant.value, service, org, rev)
        }
    }

    fun vehicle(): UUID = UUID.randomUUID().also { v ->
        fx.tx.run(tenant) { j -> j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'fixture','{}')", tenant.value, v) }
    }

    /** Schichteingabe mit Fahrzeug und Dienstbezug am Tag [day] (Berliner Ortszeit). */
    fun input(day: String, start: String, end: String, driver: UUID?, vehicle: UUID?, qualification: String? = null, serviceId: UUID? = service): RosterShiftInput =
        w.input(day = day, start = start, end = end, driver = driver, qualification = qualification).copy(vehicleId = vehicle, serviceId = serviceId)

    fun publish(rosterId: UUID, reason: String = "Freigabe Besetzung") =
        fx.staff.publishedRosters.publish(planner, rosterId, PublishRoster(Instant.parse("2026-12-01T00:00:00Z"), reason, UUID.randomUUID(), fx.staff.rosters.get(planner, rosterId).etag, null))
}
