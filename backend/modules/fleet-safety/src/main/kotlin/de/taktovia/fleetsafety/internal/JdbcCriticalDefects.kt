package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.AlertKinds
import de.taktovia.fleetsafety.api.BlockOutcome
import de.taktovia.fleetsafety.api.BlockReason
import de.taktovia.fleetsafety.api.BlockSource
import de.taktovia.fleetsafety.api.DefectSeverities
import de.taktovia.fleetsafety.api.DefectView
import de.taktovia.fleetsafety.api.SetBlock
import de.taktovia.platform.alert.BusinessAlertPort
import de.taktovia.platform.alert.BusinessAlertRequest
import de.taktovia.platform.alert.BusinessAlertResult
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.util.UUID

/**
 * Sofortsperre und priorisierter Alarm bei einer sperrenden Einstufung der Schadenakte (WP-M3-46, LH-6.22-DMG-006; WP-M3-45 Kriterium 2 und 7). Läuft **in der Transaktion des Aufrufers**
 * (Meldung oder Triage), also im selben Commit wie die Einstufung:
 * 1. Sperre über den Sperrkern ([JdbcReadiness.setBlockIn]): Quelle `SAFETY`, Grund `DEFECT_CRITICAL`, Bezug = Fallnummer; im selben Zug `vehicle.active_block`, `safety_revision + 1`, Widerruf
 *    jeder gültigen Einsatzfreigabe des Fahrzeugs, Audit (Fallnummer als Grund) und Ereignis „Fahrzeug gesperrt“. Die Fahrzeugzeile wird dabei zuerst `FOR UPDATE` gesperrt: ein gleichzeitiger
 *    Hold-Commit der Disposition (`FOR SHARE`) wartet und sieht danach die Sperre (PH-AT-020). Idempotent: dieselbe Sperre erzeugt weder zweiten Grund noch zweiten Revisionssprung.
 * 2. Alarm höchster Priorität nach der wirksamen `alert_policy_revision` ([BusinessAlertPort], nur bei `SAFETY_CRITICAL`); kritische Alarme umgehen die Eskalationsverzögerung
 *    (`alert_escalation_delay_min`). Der Alarm nennt Fahrzeug, Fallnummer und die Zahl der betroffenen Fahrten, keine Fahrgastdaten.
 * 3. Störungsablauf: genau ein offener Incident je Fahrzeug und Ursache (Dedupe-Schlüssel, partieller Unique-Index) mit Verknüpfung zu Akte, Alarm und jedem betroffenen Umlauf, damit bestehende
 *    Zuweisungen nicht still bestehen bleiben. Die Ersatzdisposition selbst gehört WP-M3-54.
 * Lesend gelesen wird `vehicle_run` (Modul `booking-dispatch`); geschrieben werden nur Tabellen von `fleet-safety`.
 */
internal class CriticalDefectBlocker(private val readiness: JdbcReadiness, private val alerts: BusinessAlertPort, private val outbox: Outbox, private val clock: Clock) {

    data class Outcome(val block: BlockOutcome, val alert: BusinessAlertResult?, val incidentId: UUID, val incidentCreated: Boolean, val affectedRuns: Int)

    fun apply(j: JdbcOperations, actor: ActorRef, correlationId: UUID, defect: DefectView, severity: String, classificationEventId: UUID): Outcome {
        val block = readiness.setBlockIn(j, actor, correlationId, SetBlock(defect.vehicleId, BlockSource.SAFETY, BlockReason.DEFECT_CRITICAL, defect.caseNumber))
        val runs = j.query(AFFECTED_RUNS, { rs, _ -> rs.getObject(1, UUID::class.java) }, defect.vehicleId)
        val alert = if (severity == DefectSeverities.SAFETY_CRITICAL) alerts.raise(j, BusinessAlertRequest(
            kind = AlertKinds.VEHICLE_BLOCK_CRITICAL, sourceEventId = classificationEventId, vehicleId = defect.vehicleId, refType = "defect_case", refId = defect.id, reasonCode = "DEFECT_CRITICAL",
            caseNumber = defect.caseNumber, affectedRunCount = runs.size)) else null
        val (incident, created) = openIncident(j, actor, defect.vehicleId, correlationId)
        link(j, actor, incident, "DEFECT", defect.id)
        alert?.let { link(j, actor, incident, "ALERT", it.alertId) }
        runs.forEach { link(j, actor, incident, "VEHICLE_RUN", it) }
        return Outcome(block, alert, incident, created, runs.size)
    }

    /** Genau ein offener Störungsablauf je Fahrzeug und Ursache (Sicherheitssperre): ein zweiter Auslöser wird dem bestehenden zugeordnet. */
    private fun openIncident(j: JdbcOperations, actor: ActorRef, vehicleId: UUID, correlationId: UUID): Pair<UUID, Boolean> {
        val key = incidentKey(vehicleId)
        val id = UUID.randomUUID()
        val n = j.update(
            "INSERT INTO taktovia.incident (tenant_id, id, kind, vehicle_id, status, dedupe_key, created_by) VALUES (taktovia.current_tenant(), ?, 'VEHICLE_OUTAGE', ?, 'OPEN', ?, ?) " +
                "ON CONFLICT (tenant_id, dedupe_key) WHERE status IN ('OPEN', 'REPLACEMENT_SEARCH') DO NOTHING", id, vehicleId, key, actor.value)
        if (n == 1) {
            outbox.append(j, OutboxEvent(id, 1, "incident.opened", linkedMapOf("incident_id" to id.toString(), "kind" to "VEHICLE_OUTAGE", "vehicle_id" to vehicleId.toString(), "dedupe_key" to key),
                clock.instant(), correlationId = correlationId))
            return id to true
        }
        return j.queryForObject("SELECT id FROM taktovia.incident WHERE dedupe_key = ? AND status IN ('OPEN', 'REPLACEMENT_SEARCH')", UUID::class.java, key)!! to false
    }

    private fun link(j: JdbcOperations, actor: ActorRef, incident: UUID, kind: String, objectId: UUID) {
        j.update("INSERT INTO taktovia.incident_link (tenant_id, id, incident_id, link_kind, object_id, created_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?) " +
            "ON CONFLICT (tenant_id, incident_id, link_kind, object_id) DO NOTHING", UUID.randomUUID(), incident, kind, objectId, actor.value)
    }

    companion object {
        /** Dedupe-Schlüssel des Störungsablaufs „Fahrzeugausfall durch Sicherheitssperre“ (WP-M3-53 ordnet weitere Ereignisse demselben Vorgang zu). */
        fun incidentKey(vehicleId: UUID) = "VEHICLE_OUTAGE|$vehicleId|SAFETY"

        /** Betroffene Fahrten: nicht abgeschlossene, nicht entfallene Umläufe des Fahrzeugs (`vehicle_run`, Zustandsautomat `fahrtenplan.md`). */
        const val AFFECTED_RUNS = "SELECT id FROM taktovia.vehicle_run WHERE vehicle_id = ? AND state IN ('PLANNED', 'ACTIVE', 'LOCKED', 'IN_PROGRESS') ORDER BY id"
    }
}
