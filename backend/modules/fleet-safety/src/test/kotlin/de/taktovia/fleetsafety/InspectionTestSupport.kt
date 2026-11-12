package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.ChecklistTemplateInput
import de.taktovia.fleetsafety.api.ChecklistTemplateView
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import java.util.UUID

/** Testhilfen des Prüflauf-Starts (WP-M3-13), eigene Klasse je Cluster: Mandant mit Fahrzeugen nach Klasse und Bedienart, angetretene Schichten, freigegebene Vorlagen. */
class InspectionWorld(val fx: FleetFixture) {
    val w = fx.world(1)
    val tenant: TenantId = w.tenant

    fun ctx(subject: UUID, vararg roles: String, tenant: TenantId = this.tenant) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/fleet", subject.toString(), "sid-$subject", true), tenant, roles.toSet(), setOf("inspections:read", "inspections:write"), emptySet(), UUID.randomUUID())

    /** Fahrzeug mit Fahrzeugklasse und Bedienart (PA-08); `null` = Klasse nicht gepflegt. */
    fun vehicle(vehicleClass: String?, operationKind: String = "STANDARD"): UUID = fx.vehicle(tenant).also {
        fx.exec("UPDATE taktovia.vehicle SET vehicle_type = ?, operation_kind = ? WHERE id = ?", vehicleClass, operationKind, it)
    }

    /** Schicht des Fahrers (Annahme wie in `staff-time`: `driver_ref` = IdP-Kennung); `started` = Dienstanmeldung erfolgt (Gerät und Zeit gesetzt). */
    fun shift(driver: UUID, vehicle: UUID, started: Boolean = true, state: String = if (started) "STARTED" else "PLANNED", fromHours: Long = -2, toHours: Long = 6, tenant: TenantId = this.tenant): UUID = UUID.randomUUID().also { id ->
        var device: UUID? = null
        if (started) {
            device = UUID.randomUUID()
            fx.exec("INSERT INTO taktovia.driver_device(tenant_id,id,issuer,subject,idp_session_id,platform,app_version) VALUES (?,?,'https://idp.test/realms/fleet',?,?,'ANDROID','1.0.0')", tenant.value, device, driver.toString(), "sid-$driver-$id")
        }
        fx.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state,started_at,started_device_id) VALUES (?,?,?,?,now() + (? || ' hours')::interval, now() + (? || ' hours')::interval,'Europe/Berlin',0,?,?,?)",
            tenant.value, id, driver, vehicle, fromHours.toString(), toHours.toString(), state, if (started) java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(600)) else null, device)
    }

    private fun author() = ctx(UUID.randomUUID(), "fleet_safety")

    fun draft(vehicleClass: String, serviceMode: String = "STANDARD", steps: Int = 3, name: String = "Prüfung $vehicleClass"): ChecklistTemplateView =
        fx.fleet.checklistTemplates.create(author(), ChecklistTemplateInput.parse(linkedMapOf("name" to name, "vehicle_class" to vehicleClass, "service_mode" to serviceMode,
            "steps" to (1..steps).map { linkedMapOf("step_code" to "STEP_$it", "title" to "Prüfpunkt $it", "input_kind" to "OK_DEFECT_NA", "mandatory" to true, "safety_critical" to (it == 1)) })))

    /** Veröffentlichte Vorlagenrevision (Vier-Augen: anderer Freigebender als der Ersteller). */
    fun published(vehicleClass: String, serviceMode: String = "STANDARD", steps: Int = 3): ChecklistTemplateView = draft(vehicleClass, serviceMode, steps).let { publish(it) }

    fun publish(d: ChecklistTemplateView): ChecklistTemplateView = fx.fleet.checklistTemplates.publish(author(), d.id, d.revision)

    fun start(ctx: RequestContext, shift: UUID, vehicle: UUID, kind: String = "PRE_SHIFT", extra: Map<String, Any?> = emptyMap()) =
        fx.fleet.inspections.start(ctx, de.taktovia.fleetsafety.api.StartInspection.parse(linkedMapOf<String, Any?>("shift_id" to shift.toString(), "vehicle_id" to vehicle.toString(), "kind" to kind,
            "client_started_at" to java.time.Instant.now().toString()) + extra))
}
