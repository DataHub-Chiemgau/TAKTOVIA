package de.taktovia.fleetsafety.internal

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
    fun create(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, store: TenantObjectStore, clock: Clock): FleetsafetyApi {
        val roles = JdbcVehicleRoles(ctxTx, audit, outbox, clock)
        val vehicles = JdbcVehicles(ctxTx, audit, outbox, clock)
        val pool = JdbcVehiclePool(ctxTx, audit, outbox, clock)
        val checklists = JdbcChecklistTemplates(ctxTx, audit, outbox, store, clock)
        return object : FleetsafetyApi {
            override val checklistTemplates: ChecklistTemplates = checklists
            override val vehicleRoles: VehicleRoles = roles
            override val vehicles: Vehicles = vehicles
            override val vehicleCapacities: VehicleCapacities = vehicles
            override val fleetReadPort: de.taktovia.platform.readports.FleetReadPort = JdbcFleetReadPort(ctxTx)
            override val vehiclePool: VehiclePool = pool
        }
    }
}
