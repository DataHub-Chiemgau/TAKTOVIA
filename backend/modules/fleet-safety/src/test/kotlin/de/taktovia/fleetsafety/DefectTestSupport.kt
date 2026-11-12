package de.taktovia.fleetsafety

import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import java.time.Instant
import java.util.UUID

/** Testhilfen der Schadenakte (WP-M3-43/-44/-47/-48), eigene Klasse je Cluster: Kontexte mit Rollen, Schichten, Rohmeldungen. */
class DefectWorld(val fx: FleetFixture) {
    val w = fx.world(1)
    val tenant: TenantId = w.tenant
    val vehicle: UUID = w.vehicle

    fun ctx(vararg roles: String, subject: UUID = UUID.randomUUID(), tenant: TenantId = this.tenant) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/fleet", subject.toString(), "sid-$subject", true), tenant, roles.toSet(), setOf("defects:read", "defects:write"), emptySet(), UUID.randomUUID())

    /** Schicht des Fahrers für das Fahrzeug (Annahme: `driver_ref` = IdP-Subject), Beginn relativ zu jetzt. */
    fun shift(driver: UUID, vehicle: UUID = this.vehicle, startOffsetHours: Long = -2, lengthHours: Long = 8) =
        fx.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,now() + (? || ' hours')::interval, now() + (? || ' hours')::interval,'Europe/Berlin',0,'PUBLISHED')",
            tenant.value, UUID.randomUUID(), driver, vehicle, startOffsetHours.toString(), (startOffsetHours + lengthHours).toString())

    fun raw(vehicle: UUID = this.vehicle, occurredAt: Instant = Instant.now().minusSeconds(3600), category: String = "BODY", description: String? = "Kratzer an der Beifahrertür",
            extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = linkedMapOf<String, Any?>("vehicle_id" to vehicle.toString(), "occurred_at" to occurredAt.toString(), "category_code" to category,
        "description" to description).also { m -> if (description == null) m.remove("description"); m.putAll(extra) }

    /** Setzt die Klasse (Triage, WP-M3-45) per Administration, solange die Triage nicht gebaut ist. */
    fun classify(defect: UUID, severity: String) = fx.exec("UPDATE taktovia.defect_case SET severity = ? WHERE id = ?", severity, defect)
}
