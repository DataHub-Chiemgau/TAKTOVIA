package de.taktovia.fleetsafety

import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Testhilfen des Sperr- und Freigabekerns (WP-M3-08) und des Prüfabschlusses (WP-M3-14), eigene Klasse je Cluster: Mandant mit Fahrzeug, Fahrer mit gültiger Qualifikation, Schicht und
 * abgeschlossenem Prüflauf als Voraussetzungen der Einsatzfreigabe. Alle Zeilen legt der Administrator an (wie die Fixtures der anderen Module).
 */
class ReadinessWorld(val fx: FleetFixture) {
    val iw = InspectionWorld(fx)
    val tenant: TenantId = iw.tenant
    val template: UUID by lazy { iw.published("PKW", steps = 3).id }

    fun ctx(vararg roles: String, service: Boolean = false, tenant: TenantId = this.tenant, subject: UUID = UUID.randomUUID()) = RequestContext(
        Actor(if (service) ActorType.SERVICE else ActorType.HUMAN, "https://idp.test/realms/fleet", subject.toString(), "sid-$subject", !service), tenant, roles.toSet(),
        setOf("fleet:read", "fleet:write", "inspections:read", "inspections:write"), emptySet(), UUID.randomUUID())

    fun driver(qualified: Boolean = true): UUID = UUID.randomUUID().also { id ->
        fx.exec("INSERT INTO taktovia.driver(tenant_id,id,display_name,contact_channel,contact_value) VALUES (?,?,'Fahrer','APP','x')", tenant.value, id)
        if (qualified) qualify(id)
    }

    fun qualify(driver: UUID, revision: Long = 1, seq: Long = 1, status: String = "APPROVED", code: String = "PBEFG") {
        fx.exec("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,approval_status,driver_seq) VALUES (?,?,?,?,?,now() - interval '30 days',?,?)",
            tenant.value, UUID.randomUUID(), driver, code, revision, status, seq)
        fx.exec("UPDATE taktovia.driver SET qualification_seq = ? WHERE id = ?", seq, driver)
    }

    fun vehicle(): UUID = fx.vehicle(tenant)

    fun shift(driver: UUID, vehicle: UUID, state: String = "PUBLISHED", endsInHours: Long = 8): UUID = UUID.randomUUID().also { id ->
        fx.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,now() - interval '1 hour', now() + (? || ' hours')::interval,'Europe/Berlin',0,?)",
            tenant.value, id, driver, vehicle, endsInHours.toString(), state)
    }

    fun inspection(shift: UUID, vehicle: UUID, driver: UUID, status: String = "COMPLETED", outcome: String? = "PASSED", kind: String = "PRE_SHIFT"): UUID = UUID.randomUUID().also { id ->
        val done = status == "COMPLETED"
        fx.exec("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,status,outcome,started_at,completed_at,received_at,created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'user:test')",
            tenant.value, id, shift, vehicle, driver, template, kind, status, if (done) outcome else null, Timestamp.from(Instant.now().minusSeconds(600)),
            if (done) Timestamp.from(Instant.now().minusSeconds(60)) else null, if (done) Timestamp.from(Instant.now().minusSeconds(59)) else null)
    }

    /** Alle Voraussetzungen der Einsatzfreigabe erfüllt (Fahrzeug, qualifizierter Fahrer, laufende Schicht, bestandene Prüfung). */
    class Ready(val vehicle: UUID, val driver: UUID, val shift: UUID, val inspection: UUID)

    fun ready(state: String = "PUBLISHED"): Ready {
        val v = vehicle(); val d = driver(); val s = shift(d, v, state); return Ready(v, d, s, inspection(s, v, d))
    }

    fun safetyService() = ctx(service = true)

    /** Wiederfreigabeentscheidung `RELEASED` (Vieraugen) samt Schadenakte, Reparaturauftrag und Folgeprüfung, Voraussetzung für das Aufheben einer Sicherheitssperre. */
    fun releaseDecision(vehicle: UUID): UUID {
        val d = driver(); val other = vehicle(); val s = shift(d, other, endsInHours = 4); val follow = inspection(s, other, d, kind = "FOLLOW_UP")   // Schichten desselben Fahrzeugs dürfen sich nicht überlappen
        val defect = UUID.randomUUID(); val order = UUID.randomUUID(); val decision = UUID.randomUUID()
        fx.exec("INSERT INTO taktovia.defect_case(tenant_id,id,case_number,vehicle_id,reported_by,occurred_at,category_code,created_by) VALUES (?,?,?,?,?,now(),'BRAKES','user:t')", tenant.value, defect, "DC-" + defect.toString().take(8), vehicle, UUID.randomUUID())
        fx.exec("INSERT INTO taktovia.repair_order(tenant_id,id,defect_id,vehicle_id,created_by) VALUES (?,?,?,?,'user:t')", tenant.value, order, defect, vehicle)
        fx.exec("INSERT INTO taktovia.vehicle_release_decision(tenant_id,id,vehicle_id,repair_order_id,follow_up_inspection_id,decision,decided_by,second_approver,safety_revision) VALUES (?,?,?,?,?,'RELEASED','user:a','user:b',1)",
            tenant.value, decision, vehicle, order, follow)
        return decision
    }
}
