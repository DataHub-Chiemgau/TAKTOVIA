package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.DefectEvidenceUploads
import de.taktovia.fleetsafety.api.DefectEvidenceWiring
import de.taktovia.fleetsafety.api.Defects
import de.taktovia.fleetsafety.api.DriverVehicleRelation
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.VehicleCapacities
import de.taktovia.fleetsafety.api.VehiclePool
import de.taktovia.fleetsafety.api.VehicleRoles
import de.taktovia.fleetsafety.api.Vehicles
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.storage.TenantObjectStore
import de.taktovia.fleetsafety.api.ChecklistTemplates
import java.time.Clock

/** Nur innerhalb des Moduls `fleet-safety` sichtbar (durch Architekturtest erzwungen). */
internal object FleetServicesFactory {
    fun create(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, store: TenantObjectStore, clock: Clock, driverRelation: DriverVehicleRelation?, evidenceWiring: DefectEvidenceWiring?, releasePolicy: de.taktovia.fleetsafety.api.ReleasePolicy = de.taktovia.fleetsafety.api.ReleasePolicy(), alertSettings: de.taktovia.fleetsafety.api.AlertSettings = de.taktovia.fleetsafety.api.AlertSettings(), defectSettings: de.taktovia.fleetsafety.api.DefectSettings = de.taktovia.fleetsafety.api.DefectSettings(), replacementSearch: de.taktovia.fleetsafety.api.ReplacementSearchPort = de.taktovia.fleetsafety.api.ReplacementSearchPort.UNAVAILABLE): FleetsafetyApi {
        val roles = JdbcVehicleRoles(ctxTx, audit, outbox, clock)
        val vehicles = JdbcVehicles(ctxTx, audit, outbox, clock)
        val pool = JdbcVehiclePool(ctxTx, audit, outbox, clock)
        val checklists = JdbcChecklistTemplates(ctxTx, audit, outbox, store, clock)
        val evidenceService = JdbcDefectEvidence(ctxTx, audit, clock, evidenceWiring ?: DefectEvidenceWiring())
        val attempts = ReleaseAttempts(ctxTx, audit, outbox, clock)
        val readinessService = JdbcReadiness(ctxTx, audit, outbox, clock, releasePolicy, attempts)
        val alertService = JdbcAlerts(ctxTx, audit, outbox, clock, alertSettings)
        val criticalBlocker = CriticalDefectBlocker(readinessService, alertService.port, outbox, clock)
        val defectService = JdbcDefects(ctxTx, audit, outbox, clock, driverRelation ?: ShiftDriverRelation(), evidenceService, criticalBlocker, defectSettings)
        val repairService = JdbcRepairOrders(ctxTx, audit, outbox, clock, defectService)
        val operatorVehicleService = JdbcOperatorVehicles(ctxTx, readinessService, clock)
        val releaseService = JdbcVehicleReleases(ctxTx, audit, outbox, clock, readinessService, attempts)
        val inspectionService = JdbcInspections(ctxTx, audit, outbox, checklists, clock, readinessService, alertService.port)
        val knownDefectService = JdbcKnownDefects(ctxTx, audit, driverRelation ?: ShiftDriverRelation(), clock)
        val workflowService = JdbcIncidentWorkflow(ctxTx, audit, outbox, clock, replacementSearch)
        val incidentService = JdbcIncidents(ctxTx)
        return object : FleetsafetyApi {
            override val checklistTemplates: ChecklistTemplates = checklists
            override val vehicleRoles: VehicleRoles = roles
            override val vehicles: Vehicles = vehicles
            override val vehicleCapacities: VehicleCapacities = vehicles
            override val fleetReadPort: de.taktovia.platform.readports.FleetReadPort = JdbcFleetReadPort(ctxTx)
            override val vehiclePool: VehiclePool = pool
            override val defects: Defects = defectService
            override val defectEvidence: DefectEvidenceUploads = evidenceService
            override val inspections: de.taktovia.fleetsafety.api.Inspections = inspectionService
            override val knownDefects: de.taktovia.fleetsafety.api.KnownDefects = knownDefectService
            override val readiness: de.taktovia.fleetsafety.api.Readiness = readinessService
            override val alerts: de.taktovia.fleetsafety.api.Alerts = alertService
            override val operatorVehicles: de.taktovia.fleetsafety.api.OperatorVehicles = operatorVehicleService
            override val repairOrders: de.taktovia.fleetsafety.api.RepairOrders = repairService
            override val vehicleReleases: de.taktovia.fleetsafety.api.VehicleReleases = releaseService
            override val incidentWorkflow: de.taktovia.fleetsafety.api.IncidentWorkflow = workflowService
            override val incidents: de.taktovia.fleetsafety.api.Incidents = incidentService
        }
    }
}
