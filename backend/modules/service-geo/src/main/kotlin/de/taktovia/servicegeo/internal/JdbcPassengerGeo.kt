package de.taktovia.servicegeo.internal

import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.servicegeo.api.GeoBounds
import de.taktovia.servicegeo.api.GeoPoint
import de.taktovia.servicegeo.api.PassengerAreaView
import de.taktovia.servicegeo.api.PassengerGeo
import de.taktovia.servicegeo.api.PassengerStopView
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

private const val STOP_EFFECTIVE = "s.active AND s.valid_from <= ? AND (s.valid_to IS NULL OR ? < s.valid_to)"

/**
 * Fahrgastsicht (WP-M2-40). Eigene, bewusst enge Abfragen: Rechte des Aufrufers erweitern die Sicht nicht (auch ein Pfleger sieht hier nur, was ein Fahrgast sähe).
 * Die Generalisierung der Gebietsgeometrie geschieht in PostGIS (`ST_SimplifyPreserveTopology`, Toleranz 0,0005 Grad, Rundung auf 4 Stellen); wird sie ungültig oder leer, gilt die nur gerundete Geometrie.
 */
internal class JdbcPassengerGeo(private val ctxTx: ContextTransactions, private val clock: Clock) : PassengerGeo {
    private val json = JsonMapper.builder().build()

    override fun stops(ctx: RequestContext, serviceId: UUID?, at: Instant?, near: GeoPoint?, radiusMeters: Int?, limit: Int): List<PassengerStopView> = ctxTx.run(ctx, readOnly = true) { j ->
        val t = Timestamp.from(at ?: clock.instant())
        val product: String? = if (serviceId != null) (effectiveProduct(j, serviceId, t) ?: return@run emptyList()) else null
        val radius = if (near != null) (radiusMeters ?: 1000).toDouble() else null
        val hasNear: Double? = if (near != null) 1.0 else null
        j.query("SELECT s.stop_id, s.revision, s.name, ST_X(s.point) AS lon, ST_Y(s.point) AS lat, s.accessibility::text AS accessibility, " +
            "CASE WHEN CAST(? AS float8) IS NULL THEN NULL ELSE ST_Distance(CAST(s.point AS geography), CAST(ST_SetSRID(ST_MakePoint(CAST(? AS float8), CAST(? AS float8)), 4326) AS geography)) END AS dist " +
            "FROM taktovia.stop_revision s WHERE $STOP_EFFECTIVE AND (CAST(? AS text) IS NULL OR CAST(? AS text) = ANY(s.traffic_products)) " +
            "AND (CAST(? AS float8) IS NULL OR ST_DWithin(CAST(s.point AS geography), CAST(ST_SetSRID(ST_MakePoint(CAST(? AS float8), CAST(? AS float8)), 4326) AS geography), CAST(? AS float8))) " +
            "ORDER BY dist NULLS LAST, s.name, s.stop_id LIMIT ?",
            STOP_MAPPER, hasNear, near?.longitude, near?.latitude, t, t, product, product, hasNear, near?.longitude, near?.latitude, radius, limit)
    }

    override fun stop(ctx: RequestContext, stopId: UUID, at: Instant?): PassengerStopView = ctxTx.run(ctx, readOnly = true) { j ->
        val t = Timestamp.from(at ?: clock.instant())
        j.query("SELECT s.stop_id, s.revision, s.name, ST_X(s.point) AS lon, ST_Y(s.point) AS lat, s.accessibility::text AS accessibility, CAST(NULL AS float8) AS dist " +
            "FROM taktovia.stop_revision s WHERE s.stop_id = ? AND $STOP_EFFECTIVE ORDER BY s.revision DESC LIMIT 1", STOP_MAPPER, stopId, t, t).singleOrNull()
    } ?: throw ProblemException.notFound()

    override fun areas(ctx: RequestContext, serviceId: UUID?, at: Instant?, limit: Int): List<PassengerAreaView> = ctxTx.run(ctx, readOnly = true) { j ->
        val t = Timestamp.from(at ?: clock.instant())
        j.query("$AREA_SELECT WHERE a.valid_from <= ? AND (a.valid_to IS NULL OR ? < a.valid_to) AND (CAST(? AS uuid) IS NULL OR a.service_id = CAST(? AS uuid)) " +
            "ORDER BY a.valid_from, a.id LIMIT ?", areaMapper(), t, t, serviceId, serviceId, limit)
    }

    override fun area(ctx: RequestContext, areaKey: UUID, at: Instant?): PassengerAreaView = ctxTx.run(ctx, readOnly = true) { j ->
        val t = Timestamp.from(at ?: clock.instant())
        j.query("$AREA_SELECT WHERE COALESCE(a.area_key, a.id) = ? AND a.valid_from <= ? AND (a.valid_to IS NULL OR ? < a.valid_to) ORDER BY a.revision DESC LIMIT 1", areaMapper(), areaKey, t, t).singleOrNull()
    } ?: throw ProblemException.notFound()

    private fun effectiveProduct(j: JdbcOperations, serviceId: UUID, t: Timestamp): String? =
        j.query("SELECT r.traffic_product FROM taktovia.service_revision r WHERE r.service_id = ? AND r.valid_from <= ? AND (r.valid_to IS NULL OR ? < r.valid_to) ORDER BY r.revision DESC LIMIT 1",
            { rs, _ -> rs.getString(1) }, serviceId, t, t).singleOrNull()

    private fun areaMapper() = RowMapper { rs, _ ->
        @Suppress("UNCHECKED_CAST")
        PassengerAreaView(rs.getObject("area_key", UUID::class.java), rs.getObject("service_id", UUID::class.java), rs.getLong("revision"),
            json.readValue(rs.getString("area_json"), Map::class.java) as Map<String, Any?>, rs.getDouble("km2"),
            GeoBounds(rs.getDouble("min_lat"), rs.getDouble("min_lon"), rs.getDouble("max_lat"), rs.getDouble("max_lon")),
            rs.getTimestamp("valid_from").toInstant(), rs.getTimestamp("valid_to")?.toInstant())
    }

    private companion object {
        const val GEN = "CASE WHEN ST_IsValid(ST_SimplifyPreserveTopology(a.area, 0.0005)) AND NOT ST_IsEmpty(ST_SimplifyPreserveTopology(a.area, 0.0005)) " +
            "THEN ST_SimplifyPreserveTopology(a.area, 0.0005) ELSE a.area END"
        const val AREA_SELECT = "SELECT COALESCE(a.area_key, a.id) AS area_key, a.service_id, a.revision, a.valid_from, a.valid_to, " +
            "ST_AsGeoJSON(ST_Multi($GEN), 4)::text AS area_json, ST_Area(CAST(a.area AS geography)) / 1000000.0 AS km2, " +
            "ST_YMin(a.area) AS min_lat, ST_XMin(a.area) AS min_lon, ST_YMax(a.area) AS max_lat, ST_XMax(a.area) AS max_lon FROM taktovia.service_area a"
        private val mapper = JsonMapper.builder().build()
        @Suppress("UNCHECKED_CAST")
        val STOP_MAPPER = RowMapper { rs, _ ->
            val d = rs.getDouble("dist"); val isNull = rs.wasNull()
            PassengerStopView(rs.getObject("stop_id", UUID::class.java), rs.getLong("revision"), rs.getString("name"), GeoPoint(rs.getDouble("lon"), rs.getDouble("lat")),
                mapper.readValue(rs.getString("accessibility"), Map::class.java) as Map<String, Any?>, if (isNull) null else Math.round(d).toInt())
        }
    }
}
