package de.taktovia.stafftime.internal

import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.readports.DriverReadPort
import de.taktovia.platform.readports.DriverView
import de.taktovia.platform.readports.QualificationView
import org.springframework.jdbc.core.JdbcOperations
import java.util.UUID

/**
 * Leseport „Fahrer und Qualifikationen“ für M4 (WP-M4-02) auf den eigenen Tabellen `driver` und `qualification_revision` (WP-M3-05). Nur Kennung, Status und freigegebene
 * Qualifikationsrevisionen: **kein Klarname, kein Kontakt, kein Nachweisverweis** (Datenklasse Personal). Nur lesend, im Mandanten des Kontexts (RLS).
 */
internal class JdbcDriverReadPort(private val ctxTx: ContextTransactions) : DriverReadPort {
    override fun driver(ctx: RequestContext, driverId: UUID): DriverView? = ctxTx.run(ctx, readOnly = true) { j -> load(j, driverId).firstOrNull() }

    override fun drivers(ctx: RequestContext): List<DriverView> = ctxTx.run(ctx, readOnly = true) { j -> load(j, null) }

    private fun load(j: JdbcOperations, only: UUID?): List<DriverView> {
        val heads = j.query("SELECT d.id, d.status FROM taktovia.driver d WHERE (CAST(? AS uuid) IS NULL OR d.id = CAST(? AS uuid)) ORDER BY d.id",
            { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }, only, only)
        if (heads.isEmpty()) return emptyList()
        val quals = j.query("SELECT q.driver_id, q.qualification_code, q.revision, q.valid_from, q.valid_to FROM taktovia.qualification_revision q WHERE q.approval_status = 'APPROVED' " +
            "AND (CAST(? AS uuid) IS NULL OR q.driver_id = CAST(? AS uuid)) ORDER BY q.driver_id, q.qualification_code, q.revision",
            { rs, _ -> rs.getObject(1, UUID::class.java) to QualificationView(rs.getString(2), rs.getLong(3), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5)?.toInstant()) }, only, only)
            .groupBy({ it.first }, { it.second })
        return heads.map { (id, status) -> DriverView(id, status, quals[id].orEmpty()) }
    }
}
