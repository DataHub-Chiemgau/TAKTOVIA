package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.DefectEvidenceUploads
import de.taktovia.fleetsafety.api.DefectEvidenceWiring
import de.taktovia.fleetsafety.api.Defects
import de.taktovia.fleetsafety.api.DriverVehicleRelation
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.VehicleCapacities
import de.taktovia.fleetsafety.api.VehicleRoles
import de.taktovia.fleetsafety.api.Vehicles
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import java.time.Clock

/** Nur innerhalb des Moduls `fleet-safety` sichtbar (durch Architekturtest erzwungen). */
internal object FleetServicesFactory {
    fun create(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, clock: Clock, driverRelation: DriverVehicleRelation?, evidenceWiring: DefectEvidenceWiring?): FleetsafetyApi {
        val roles = JdbcVehicleRoles(ctxTx, audit, outbox, clock)
        val vehicles = JdbcVehicles(ctxTx, audit, outbox, clock)
        val evidenceService = JdbcDefectEvidence(ctxTx, audit, clock, evidenceWiring ?: DefectEvidenceWiring())
        val defectService = JdbcDefects(ctxTx, audit, outbox, clock, driverRelation ?: ShiftDriverRelation(), evidenceService)
        return object : FleetsafetyApi {
            override val vehicleRoles: VehicleRoles = roles
            override val vehicles: Vehicles = vehicles
            override val vehicleCapacities: VehicleCapacities = vehicles
            override val defects: Defects = defectService
            override val defectEvidence: DefectEvidenceUploads = evidenceService
        }
    }
}
