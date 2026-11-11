package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.MatrixCell
import org.springframework.jdbc.core.JdbcOperations
import java.util.UUID

/**
 * PostgreSQL-Cache `route_matrix_cache` (WP-M2-10): mandantengebunden durch RLS und Schlüssel; gelesen wird nur die aktuelle Kartendatenversion. Die Haltepunkt-IDs des Caches
 * sind UUIDs (`stop_revision.stop_id`); Einträge für nicht-UUID-Kennungen (Fixture-Haltepunkte `H1`) werden aus dem Namen deterministisch abgeleitet.
 */
internal class JdbcRouteMatrixCache {
    fun stopUuid(stopId: String): UUID = runCatching { UUID.fromString(stopId) }.getOrElse { UUID.nameUUIDFromBytes("stop:$stopId".toByteArray()) }

    fun get(j: JdbcOperations, origin: String, destination: String, profile: String, mapVersion: String): MatrixCell? = j.query(
        "SELECT time_s, distance_m FROM taktovia.route_matrix_cache WHERE origin_stop = ? AND dest_stop = ? AND profile = ? AND map_version = ?",
        { rs, _ -> MatrixCell(rs.getInt("time_s"), rs.getInt("distance_m")) }, stopUuid(origin), stopUuid(destination), profile, mapVersion,
    ).firstOrNull()

    fun put(j: JdbcOperations, tenant: UUID, origin: String, destination: String, profile: String, mapVersion: String, cell: MatrixCell, provider: String) {
        j.update(
            "INSERT INTO taktovia.route_matrix_cache(tenant_id, id, origin_stop, dest_stop, profile, map_version, time_s, distance_m, provider) VALUES (?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT ON CONSTRAINT route_matrix_cache_key DO NOTHING",
            tenant, UUID.randomUUID(), stopUuid(origin), stopUuid(destination), profile, mapVersion, cell.timeSeconds, cell.distanceMeters, provider,
        )
    }

    /** Neuer Kartenstand: alle Einträge des Mandanten mit anderer Version entfernen (die RLS-Policy begrenzt auf den eigenen Mandanten). */
    fun invalidateOtherVersions(j: JdbcOperations, mapVersion: String): Int = j.update("DELETE FROM taktovia.route_matrix_cache WHERE map_version <> ?", mapVersion)
}
