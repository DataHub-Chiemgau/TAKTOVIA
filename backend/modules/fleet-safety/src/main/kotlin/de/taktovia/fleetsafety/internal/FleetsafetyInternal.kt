package de.taktovia.fleetsafety.internal

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
    fun create(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, clock: Clock): FleetsafetyApi {
        val roles = JdbcVehicleRoles(ctxTx, audit, outbox, clock)
        val vehicles = JdbcVehicles(ctxTx, audit, outbox, clock)
        return object : FleetsafetyApi {
            override val vehicleRoles: VehicleRoles = roles
            override val vehicles: Vehicles = vehicles
            override val vehicleCapacities: VehicleCapacities = vehicles
        }
    }
}
