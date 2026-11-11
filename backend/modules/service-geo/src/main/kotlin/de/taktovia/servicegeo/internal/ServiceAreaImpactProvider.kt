package de.taktovia.servicegeo.internal

import de.taktovia.config.api.ImpactFinding
import de.taktovia.config.api.ImpactObjectRef
import de.taktovia.config.api.ImpactProvider
import de.taktovia.config.api.ImpactSeverity
import de.taktovia.config.api.ImpactSubject
import de.taktovia.config.api.ScopeKind
import de.taktovia.platform.context.RequestContext
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Zählt bestätigte Buchungen eines Dienstes, deren Start oder Ziel (Haltepunkt im aktuellen Fahrtenplan) nach [from] **außerhalb** eines Gebiets liegt. Nur Verweise, nie Personendaten. */
internal object AreaImpactQuery {
    /** [areaGeoJson] ist ein GeoJSON-`MultiPolygon` (SRID 4326). Eine Buchung zählt einmal, auch wenn Start und Ziel außerhalb liegen. Der Rand zählt als innerhalb (`ST_Covers`). */
    fun bookingsOutside(j: JdbcOperations, serviceId: UUID, areaGeoJson: String, from: Instant): List<UUID> =
        j.query("SELECT DISTINCT b.id FROM taktovia.booking b " +
            "JOIN taktovia.capacity_hold h ON h.tenant_id = b.tenant_id AND h.id = b.hold_id " +
            "JOIN taktovia.vehicle_run r ON r.tenant_id = h.tenant_id AND r.id = h.run_id " +
            "JOIN taktovia.route_stop rs ON rs.tenant_id = r.tenant_id AND rs.run_id = r.id AND rs.run_revision = r.revision " +
            "JOIN taktovia.stop_revision sr ON sr.tenant_id = rs.tenant_id AND sr.id = rs.stop_revision_id " +
            "WHERE b.service_id = ? AND b.state = 'CONFIRMED' AND rs.planned_at >= ? " +
            "AND (rs.boardings @> jsonb_build_array(jsonb_build_object('booking_id', b.id::text)) OR rs.alightings @> jsonb_build_array(jsonb_build_object('booking_id', b.id::text))) " +
            "AND NOT ST_Covers(ST_SetSRID(ST_GeomFromGeoJSON(CAST(? AS text)), 4326), sr.point) ORDER BY b.id",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, serviceId, Timestamp.from(from), areaGeoJson)

    fun finding(ids: List<UUID>): List<ImpactFinding> =
        if (ids.isEmpty()) emptyList() else listOf(ImpactFinding("BOOKING", ImpactSeverity.BLOCKING, ids.size.toLong(), ids.take(100).map { ImpactObjectRef("BOOKING", it) }))
}

/**
 * ImpactProvider „Bediengebiet“ (WP-M2-04, Rahmen aus WP-M1-23): ändert eine Dienstkonfiguration den Bediengebietsbezug (`/service_config/service_area_id`), nennt er
 * die bestätigten Buchungen des Dienstes, deren Start oder Ziel außerhalb der jüngsten Revision des neuen Gebiets liegt (Gebietsverkleinerung). Kennt er das Gebiet nicht
 * oder berührt die Revision den Gebietsbezug nicht, liefert er nichts. Die Behandlung der betroffenen Zusagen ist offen (OP-8): der Anbieter nennt sie nur.
 */
internal class ServiceAreaImpactProvider : ImpactProvider {
    override val name = "bediengebiet"

    override fun analyze(j: JdbcOperations, ctx: RequestContext, subject: ImpactSubject): List<ImpactFinding> {
        val rev = subject.revision
        if (rev.scopeKind != ScopeKind.SERVICE) return emptyList()
        val change = subject.impactRelevantChanges.firstOrNull { it.pointer == "/service_config/service_area_id" } ?: return emptyList()
        val areaKey = (change.newValue as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return emptyList()
        val area = j.query("SELECT ST_AsGeoJSON(a.area)::text FROM taktovia.service_area a WHERE a.area_key = ? ORDER BY a.revision DESC LIMIT 1", { rs, _ -> rs.getString(1) }, areaKey)
            .singleOrNull() ?: return emptyList()
        return AreaImpactQuery.finding(AreaImpactQuery.bookingsOutside(j, rev.scopeId, area, rev.effectiveFrom))
    }
}
