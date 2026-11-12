package de.taktovia.servicegeo.internal

import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.servicegeo.api.AreaStatus
import de.taktovia.servicegeo.api.FavoritePlace
import de.taktovia.servicegeo.api.GeoPoint
import de.taktovia.servicegeo.api.GeocodeCandidate
import de.taktovia.servicegeo.api.Geocoder
import de.taktovia.servicegeo.api.NearestStop
import de.taktovia.servicegeo.api.PlaceHit
import de.taktovia.servicegeo.api.PlaceNotice
import de.taktovia.servicegeo.api.PlaceQuery
import de.taktovia.servicegeo.api.PlaceSearch
import de.taktovia.servicegeo.api.PlaceSearchResult
import de.taktovia.servicegeo.api.PlaceType
import de.taktovia.servicegeo.api.ServiceAreas
import de.taktovia.servicegeo.api.StopFilter
import de.taktovia.servicegeo.api.Stops
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ortssuche (WP-M2-38). Haltestellen: nur zum Zeitpunkt wirksame, **aktive** Revisionen in **einer** Anweisung (ein Snapshot: bei gleichzeitiger Deaktivierung erscheint die
 * Haltestelle entweder als aktive Revision oder gar nicht). Der Geocoder läuft außerhalb jeder Datenbanktransaktion mit hartem Zeitlimit; Ausnahme, Zeitüberschreitung oder
 * ungültige Koordinaten eines Kandidaten verhindern nie die übrigen Quellen. Es wird nichts protokolliert und nichts gespeichert.
 */
internal class JdbcPlaceSearch(
    private val ctxTx: ContextTransactions, private val stops: Stops, private val areas: ServiceAreas, private val geocoder: Geocoder,
    private val clock: Clock, private val geocoderTimeout: Duration = Duration.ofSeconds(2),
) : PlaceSearch {

    override fun search(ctx: RequestContext, q: PlaceQuery): PlaceSearchResult {
        val text = normalize(q)
        val limit = q.limit.coerceIn(1, PlaceSearch.MAX_LIMIT)
        val now = clock.instant()
        val notices = linkedSetOf<PlaceNotice>()
        val hits = mutableListOf<PlaceHit>()

        if (q.mapPoint != null) {
            val label = reverseLabel(q.mapPoint, notices)
            hits += PlaceHit(PlaceType.MAP_POINT, label?.label ?: MAP_LABEL, q.mapPoint, null, label?.source ?: "map", label?.dataAsOf ?: now, AreaStatus.UNKNOWN, null)
        } else {
            val t = text!!
            hits += favoriteHits(ctx, q, t, now)
            hits += stopHits(ctx, q, t, limit, now)
            hits += geocodeHits(q, t, limit, now, notices)
        }
        val enriched = dedupe(hits).take(limit).map { enrich(ctx, it, q.serviceId, now) }
        if (enriched.isEmpty()) notices += PlaceNotice.NO_MATCH
        return PlaceSearchResult(enriched, notices)
    }

    // ---- Eingabe -----------------------------------------------------------------------------------------------------------------------

    private fun normalize(q: PlaceQuery): String? {
        val hasText = q.text != null
        if (hasText == (q.mapPoint != null)) throw ProblemException.invalid(Violation(if (hasText) "/map_point" else "/q", if (hasText) ViolationCode.NOT_UNIQUE else ViolationCode.REQUIRED))
        if (q.mapPoint != null) {
            if (q.mapPoint.latitude == 0.0 && q.mapPoint.longitude == 0.0) throw ProblemException.invalid(Violation("/map_point", ViolationCode.INVALID_VALUE))
            return null
        }
        val t = q.text!!.trim().replace(WHITESPACE, " ")
        if (t.any { Character.isISOControl(it) }) throw ProblemException.invalid(Violation("/q", ViolationCode.INVALID_VALUE))
        if (t.length < PlaceSearch.MIN_TEXT) throw ProblemException.invalid(Violation("/q", ViolationCode.TOO_SHORT))
        if (t.length > PlaceSearch.MAX_TEXT) throw ProblemException.invalid(Violation("/q", ViolationCode.TOO_LONG))
        return t
    }

    // ---- Quellen -----------------------------------------------------------------------------------------------------------------------

    /** Favoriten des Akteurs (von der Anwendung geliefert); ein Haltepunkt zählt nur, wenn er jetzt buchbar ist (inaktiv: Ort bleibt nutzbar, `stopId` entfällt). */
    private fun favoriteHits(ctx: RequestContext, q: PlaceQuery, text: String, now: Instant): List<PlaceHit> {
        val matching = q.favorites.filter { it.label.contains(text, ignoreCase = true) }
        if (matching.isEmpty()) return emptyList()
        val bookable = bookableStops(ctx, matching.mapNotNull { it.stopId }, q.serviceId, now)
        return matching.map { f -> PlaceHit(PlaceType.FAVORITE, f.label, f.point, f.stopId?.takeIf { it in bookable }, "favorite", now, AreaStatus.UNKNOWN, null) }
    }

    private fun bookableStops(ctx: RequestContext, ids: List<UUID>, serviceId: UUID?, at: Instant): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        return ctxTx.run(ctx, readOnly = true) { j ->
            val product = if (serviceId != null) (effectiveProduct(j, serviceId, at) ?: return@run emptySet()) else null
            j.query("SELECT DISTINCT s.stop_id FROM taktovia.stop_revision s WHERE s.stop_id = ANY(CAST(? AS uuid[])) AND s.active AND $EFFECTIVE " +
                "AND (CAST(? AS text) IS NULL OR CAST(? AS text) = ANY(s.traffic_products))",
                { rs, _ -> rs.getObject(1, UUID::class.java) }, ids.joinToString(",", "{", "}"), Timestamp.from(at), Timestamp.from(at), product, product).toSet()
        }
    }

    private fun stopHits(ctx: RequestContext, q: PlaceQuery, text: String, limit: Int, now: Instant): List<PlaceHit> = ctxTx.run(ctx, readOnly = true) { j ->
        val product = if (q.serviceId != null) (effectiveProduct(j, q.serviceId, now) ?: return@run emptyList()) else null
        j.query("SELECT s.stop_id, s.name, ST_X(s.point), ST_Y(s.point) FROM taktovia.stop_revision s WHERE s.active AND $EFFECTIVE " +
            "AND (CAST(? AS text) IS NULL OR CAST(? AS text) = ANY(s.traffic_products)) AND strpos(lower(s.name), lower(?)) > 0 " +
            "ORDER BY (strpos(lower(s.name), lower(?)) = 1) DESC, s.name, s.stop_id LIMIT ?",
            { rs, _ ->
                PlaceHit(PlaceType.STOP, rs.getString(2), GeoPoint(rs.getDouble(3), rs.getDouble(4)), rs.getObject(1, UUID::class.java), "stop_registry", now, AreaStatus.UNKNOWN, null)
            }, Timestamp.from(now), Timestamp.from(now), product, product, text, text, limit)
    }

    private fun geocodeHits(q: PlaceQuery, text: String, limit: Int, now: Instant, notices: MutableSet<PlaceNotice>): List<PlaceHit> {
        val found = callGeocoder { geocoder.search(text, q.bias, limit) }
        if (found == null) { notices += PlaceNotice.GEOCODER_UNAVAILABLE; return emptyList() }
        return found.mapNotNull { c -> valid(c)?.let { PlaceHit(if (c.type == PlaceType.POI) PlaceType.POI else PlaceType.ADDRESS, c.label.trim(), it, null, c.source, c.dataAsOf ?: now, AreaStatus.UNKNOWN, null) } }
    }

    private fun reverseLabel(point: GeoPoint, notices: MutableSet<PlaceNotice>): GeocodeCandidate? {
        val r = callGeocoder { listOfNotNull(geocoder.reverse(point)) }
        if (r == null) { notices += PlaceNotice.GEOCODER_UNAVAILABLE; return null }
        return r.firstOrNull()?.takeIf { it.label.isNotBlank() }
    }

    /** Ausfall = Ausnahme, Zeitüberschreitung oder Unterbrechung; liefert dann `null`. Der Aufruf läuft in einem eigenen Thread, nie in einer Transaktion. */
    private fun <T> callGeocoder(call: () -> T): T? {
        val future = EXECUTOR.submit(Callable { call() })
        return try { future.get(geocoderTimeout.toMillis(), TimeUnit.MILLISECONDS) } catch (e: Exception) {
            future.cancel(true)
            if (e is InterruptedException) Thread.currentThread().interrupt()
            null
        }
    }

    /** Koordinate eines Kandidaten: im Wertebereich, endlich, nie 0/0; sonst verworfen. */
    private fun valid(c: GeocodeCandidate): GeoPoint? {
        if (!c.latitude.isFinite() || !c.longitude.isFinite() || c.label.isBlank() || c.label.length > 200) return null
        if (c.latitude !in -90.0..90.0 || c.longitude !in -180.0..180.0 || (c.latitude == 0.0 && c.longitude == 0.0)) return null
        return GeoPoint(c.longitude, c.latitude)
    }

    // ---- Zuordnung ---------------------------------------------------------------------------------------------------------------------

    private fun enrich(ctx: RequestContext, hit: PlaceHit, serviceId: UUID?, now: Instant): PlaceHit {
        if (serviceId == null) return if (hit.type == PlaceType.STOP) hit.copy(nearestStop = NearestStop(hit.stopId!!, hit.label, 0)) else hit
        val inside = areas.covering(ctx, serviceId, hit.point, now) != null
        val nearest = if (hit.type == PlaceType.STOP) NearestStop(hit.stopId!!, hit.label, 0) else nearestBookable(ctx, serviceId, hit.point, now)
        return hit.copy(serviceArea = if (inside) AreaStatus.INSIDE else AreaStatus.OUTSIDE, nearestStop = nearest)
    }

    private fun nearestBookable(ctx: RequestContext, serviceId: UUID, point: GeoPoint, now: Instant): NearestStop? =
        stops.list(ctx, StopFilter(serviceId = serviceId, at = now, near = point, nearRadiusMeters = PlaceSearch.NEAREST_STOP_RADIUS_M), 50, null)
            .map { s -> NearestStop(s.stopId, s.name, distanceMeters(point, s.point)) }.minByOrNull { it.distanceMeters }

    private fun dedupe(hits: List<PlaceHit>): List<PlaceHit> {
        val seen = HashSet<Pair<PlaceType, String>>()
        return hits.filter { seen.add(it.type to "${it.label.lowercase()}|${"%.5f".format(java.util.Locale.ROOT, it.point.latitude)}|${"%.5f".format(java.util.Locale.ROOT, it.point.longitude)}") }
    }

    private fun effectiveProduct(j: JdbcOperations, serviceId: UUID, at: Instant): String? =
        j.query("SELECT r.traffic_product FROM taktovia.service_revision r WHERE r.service_id = ? AND r.valid_from <= ? AND (r.valid_to IS NULL OR ? < r.valid_to) ORDER BY r.revision DESC LIMIT 1",
            { rs, _ -> rs.getString(1) }, serviceId, Timestamp.from(at), Timestamp.from(at)).singleOrNull()

    private companion object {
        const val MAP_LABEL = "Punkt auf der Karte"
        const val EFFECTIVE = "s.valid_from <= ? AND (s.valid_to IS NULL OR ? < s.valid_to)"
        val WHITESPACE = Regex("\\s+")
        val EXECUTOR = Executors.newCachedThreadPool { r -> Thread(r, "place-geocoder").apply { isDaemon = true } }

        /** Großkreisentfernung in Metern (Haversine). */
        fun distanceMeters(a: GeoPoint, b: GeoPoint): Int {
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.latitude - a.latitude); val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2) * sin(dLon / 2)
            return (2 * r * asin(sqrt(h))).roundToInt()
        }
    }
}
