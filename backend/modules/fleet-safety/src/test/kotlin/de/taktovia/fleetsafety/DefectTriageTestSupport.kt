package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.DefectView
import de.taktovia.fleetsafety.api.IssueRelease
import de.taktovia.fleetsafety.api.ReportDefect
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/**
 * Testhilfen der Schadenklassifikation, Sofortsperre und Wiederfreigabe (WP-M3-45/-46/-51), eigene Klasse je Cluster: Mandant mit freigegebenem Fahrzeug (qualifizierter Fahrer, Schicht,
 * bestandene Prüfung, Einsatzfreigabe), Meldung mit Katalogwerten, Sperrenlage am Fahrzeug. Alle Zeilen legt der Administrator an (wie die Fixtures der anderen Pakete).
 */
class TriageWorld(val fx: FleetFixture, val api: de.taktovia.fleetsafety.api.FleetsafetyApi = fx.fleet) {
    val rw = ReadinessWorld(fx)
    val tenant = rw.tenant

    /** Kontext mit Rollen; Funktionsscopes wie die Rechtematrix sie für die Rollen vergibt (Alarmzugriff braucht `operator:*`). */
    fun ctx(vararg roles: String, subject: UUID = UUID.randomUUID(), tenant: de.taktovia.platform.tenant.TenantId = this.tenant, services: Set<UUID> = emptySet()) = RequestContext(
        Actor(ActorType.HUMAN, "https://idp.test/realms/fleet", subject.toString(), "sid-$subject", true), tenant, roles.toSet(),
        setOf("defects:read", "defects:write", "defects:triage", "defects:state", "operator:read", "operator:write", "repair:read", "repair:write", "fleet:release"), services, UUID.randomUUID())

    /** Fahrzeug mit gültiger Einsatzfreigabe (Fahrer qualifiziert, Schicht, bestandene Prüfung). */
    fun released(): ReadinessWorld.Ready = rw.ready().also { api.readiness.issueRelease(rw.safetyService(), IssueRelease(it.vehicle, it.shift, it.inspection)) }

    fun raw(vehicle: UUID, category: String = "BODY", kind: String? = null, area: String? = null, effect: String? = null, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        linkedMapOf<String, Any?>("vehicle_id" to vehicle.toString(), "occurred_at" to Instant.now().minusSeconds(600).toString(), "category_code" to category, "description" to "Testmeldung").also {
            kind?.let { k -> it["kind"] = k }; area?.let { a -> it["vehicle_area"] = a }; effect?.let { e -> it["observed_effect"] = e }; it.putAll(extra)
        }

    fun report(ctx: RequestContext, vehicle: UUID, category: String = "BODY", kind: String? = null, area: String? = null, effect: String? = null, extra: Map<String, Any?> = emptyMap()): DefectView =
        api.defects.report(ctx, ReportDefect.parse(raw(vehicle, category, kind, area, effect, extra))).defect

    fun activeBlocks(vehicle: UUID): List<String> =
        fx.strings("SELECT source || '|' || reason_code || '|' || coalesce(reason_ref, '') FROM taktovia.vehicle_block WHERE vehicle_id = ? AND status = 'ACTIVE' ORDER BY set_at", vehicle)

    fun safetyRevision(vehicle: UUID): Long = fx.count("SELECT safety_revision FROM taktovia.vehicle WHERE id = ?", vehicle).toLong()
    fun activeBlockFlag(vehicle: UUID): Boolean = fx.strings("SELECT active_block::text FROM taktovia.vehicle WHERE id = ?", vehicle).single() == "true"
    fun audit(action: String, objectRef: String): Int = fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = ? AND object_ref = ?", tenant.value, action, objectRef)

    /** Evidenz der Akte (freigegebenes Foto) als Reparaturnachweis; der Administrator legt die unveränderliche Zeile an. */
    fun evidence(defect: UUID): UUID = UUID.randomUUID().also { id ->
        fx.exec("INSERT INTO taktovia.defect_evidence(tenant_id,id,defect_id,media_ref,sha256,captured_at,created_by) VALUES (?,?,?,'obj',?,now(),'user:t')", tenant.value, id, defect, UUID.randomUUID().toString().replace("-", "").repeat(2))
    }

    /** Folgeprüfung (Art `FOLLOW_UP`) des Fahrzeugs für Schicht und Fahrer, abgeschlossen zum Zeitpunkt [completedAt] mit dem Ergebnis [outcome]; `status` `COMPLETED` oder `IN_PROGRESS`. */
    fun followUp(r: ReadinessWorld.Ready, outcome: String? = "PASSED", status: String = "COMPLETED", vehicle: UUID = r.vehicle, completedAt: Instant = Instant.now()): UUID = UUID.randomUUID().also { id ->
        val done = status == "COMPLETED"
        fx.exec("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,status,outcome,started_at,completed_at,received_at,created_by) VALUES (?,?,?,?,?,?,'FOLLOW_UP',?,?,?,?,?,'user:test')",
            tenant.value, id, r.shift, vehicle, r.driver, rw.template, status, if (done) outcome else null, java.sql.Timestamp.from(completedAt.minusSeconds(30)), if (done) java.sql.Timestamp.from(completedAt) else null, if (done) java.sql.Timestamp.from(completedAt) else null)
    }
}
