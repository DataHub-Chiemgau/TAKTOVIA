package de.taktovia.fleetsafety.internal

import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.readports.FleetReadPort
import de.taktovia.platform.readports.VehicleRoleView
import de.taktovia.platform.readports.VehicleView
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Leseport „Fahrzeugstamm und Rollen“ für M4 (WP-M4-02) auf den eigenen Tabellen `vehicle` und `vehicle_assignment` (WP-M3-03, WP-M1-34). Nur lesend, im Mandanten des Kontexts
 * (RLS); ein fremdes Fahrzeug ist nicht vorhanden (`null`).
 */
internal class JdbcFleetReadPort(private val ctxTx: ContextTransactions) : FleetReadPort {
    private val json = JsonMapper.builder().build()

    override fun vehicle(ctx: RequestContext, vehicleId: UUID): VehicleView? = ctxTx.run(ctx, readOnly = true) { j -> load(j, vehicleId).firstOrNull() }

    override fun vehicles(ctx: RequestContext): List<VehicleView> = ctxTx.run(ctx, readOnly = true) { j -> load(j, null) }

    override fun roles(ctx: RequestContext, vehicleId: UUID): List<VehicleRoleView> = ctxTx.run(ctx, readOnly = true) { j ->
        j.query("SELECT a.vehicle_id, a.organization_id, a.role, a.valid_from, a.valid_to FROM taktovia.vehicle_assignment a WHERE a.vehicle_id = ? ORDER BY a.role, a.valid_from",
            { rs, _ -> VehicleRoleView(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5)?.toInstant()) }, vehicleId)
    }

    private fun load(j: JdbcOperations, only: UUID?): List<VehicleView> =
        j.query("SELECT v.id, v.vehicle_label, v.capacity_config::text, v.active FROM taktovia.vehicle v WHERE (CAST(? AS uuid) IS NULL OR v.id = CAST(? AS uuid)) ORDER BY v.id", { rs, _ ->
            val cap = json.readTree(rs.getString(3))
            VehicleView(rs.getObject(1, UUID::class.java), rs.getString(2), cap.path("seats").asInt(0), cap.path("wheelchair").asInt(0), cap.path("equipment").values().map { it.asString() }.toSet(), rs.getBoolean(4))
        }, only, only)
}
