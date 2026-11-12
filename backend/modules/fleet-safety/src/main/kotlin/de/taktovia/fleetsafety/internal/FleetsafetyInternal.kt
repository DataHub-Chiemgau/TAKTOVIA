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
    fun create(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, store: TenantObjectStore, clock: Clock, driverRelation: DriverVehicleRelation?, evidenceWiring: DefectEvidenceWiring?, releasePolicy: de.taktovia.fleetsafety.api.ReleasePolicy = de.taktovia.fleetsafety.api.ReleasePolicy()): FleetsafetyApi {
        val roles = JdbcVehicleRoles(ctxTx, audit, outbox, clock)
        val vehicles = JdbcVehicles(ctxTx, audit, outbox, clock)
        val pool = JdbcVehiclePool(ctxTx, audit, outbox, clock)
        val checklists = JdbcChecklistTemplates(ctxTx, audit, outbox, store, clock)
        val evidenceService = JdbcDefectEvidence(ctxTx, audit, clock, evidenceWiring ?: DefectEvidenceWiring())
        val defectService = JdbcDefects(ctxTx, audit, outbox, clock, driverRelation ?: ShiftDriverRelation(), evidenceService)
        val readinessService = JdbcReadiness(ctxTx, audit, outbox, clock, releasePolicy)
        val inspectionService = JdbcInspections(ctxTx, audit, outbox, checklists, clock)
        val knownDefectService = JdbcKnownDefects(ctxTx, audit, driverRelation ?: ShiftDriverRelation(), clock)
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
        }
    }
}
