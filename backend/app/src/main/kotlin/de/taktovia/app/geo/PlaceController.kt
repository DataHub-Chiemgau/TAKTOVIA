package de.taktovia.app.geo

import de.taktovia.app.identity.RequestContexts
import de.taktovia.identity.api.PassengerApi
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.servicegeo.api.FavoritePlace
import de.taktovia.servicegeo.api.GeoPoint
import de.taktovia.servicegeo.api.PlaceHit
import de.taktovia.servicegeo.api.PlaceQuery
import de.taktovia.servicegeo.api.PlaceSearch
import de.taktovia.servicegeo.api.ServicegeoApi
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Ortssuche mit fünf Eingabearten (WP-M2-38, LH-6.3-PAX-002): `GET /v1/places`. Die Favoriten stammen aus dem Profil des **Akteurs** (nie aus der Anfrage); das Modul
 * `service-geo` liest keine Konten. Suchtext, Suchkoordinate und Treffer werden weder protokolliert noch gespeichert noch als Metriklabel verwendet: dieser Controller und
 * das Modul schreiben keine Logzeile und setzen keine Metrik; die Quote hat einen Schlüssel aus Mandant und Akteur.
 */
@RestController
class PlaceController(private val geo: ServicegeoApi, private val passengers: ObjectProvider<PassengerApi>, private val limiter: PlaceSearchLimiter) {

    @GetMapping("/v1/places")
    fun search(@RequestParam(required = false) q: String?, @RequestParam(name = "map_latitude", required = false) mapLat: String?,
               @RequestParam(name = "map_longitude", required = false) mapLon: String?, @RequestParam(name = "bias_latitude", required = false) biasLat: String?,
               @RequestParam(name = "bias_longitude", required = false) biasLon: String?, @RequestParam(name = "service_id", required = false) serviceId: String?,
               @RequestParam(required = false) limit: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        limiter.check(ctx.tenant.toString(), ctx.actor.subject)
        val map = pair(mapLat, mapLon, "map_latitude", "map_longitude")
        val bias = pair(biasLat, biasLon, "bias_latitude", "bias_longitude")
        val service = serviceId?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw invalid("service_id") }
        val lim = limit?.let { it.toIntOrNull()?.takeIf { v -> v in 1..PlaceSearch.MAX_LIMIT } ?: throw invalid("limit") } ?: 10
        // Favoriten nur für Suchtext und nur die des Akteurs (leer ohne Fahrgastkonto)
        val favorites = if (q != null) passengers.getObject().profiles.favorites(ctx).map { FavoritePlace(it.label, GeoPoint(it.lon, it.lat), it.stopId) } else emptyList()
        val result = try {
            geo.places.search(ctx, PlaceQuery(q, map, bias, service, favorites, lim))
        } catch (e: ProblemException) {
            if (e.code != ErrorCode.VALIDATION_FAILED) throw e
            throw ProblemException(e.code, e.detail, e.violations.map { v -> v.copy(pointer = "/\$query" + v.pointer) })
        }
        return linkedMapOf("items" to result.hits.map(::json), "notices" to result.notices.map { it.name }, "as_of" to Instant.now().toString())
    }

    private fun pair(lat: String?, lon: String?, latName: String, lonName: String): GeoPoint? {
        if (lat == null && lon == null) return null
        if (lat == null || lon == null) throw ProblemException.invalid(Violation("/\$query/${if (lat == null) latName else lonName}", ViolationCode.REQUIRED))
        val la = lat.toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: throw invalid(latName)
        val lo = lon.toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: throw invalid(lonName)
        return GeoPoint(lo, la)
    }

    private fun invalid(name: String) = ProblemException.invalid(Violation("/\$query/$name", ViolationCode.INVALID_FORMAT))

    private fun json(h: PlaceHit): Map<String, Any?> = linkedMapOf(
        "type" to h.type.name, "label" to h.label, "point" to linkedMapOf("latitude" to h.point.latitude, "longitude" to h.point.longitude), "stop_id" to h.stopId?.toString(),
        "source" to h.source, "data_as_of" to h.dataAsOf.toString(), "service_area" to h.serviceArea.name,
        "nearest_stop" to h.nearestStop?.let { linkedMapOf("stop_id" to it.stopId.toString(), "name" to it.name, "distance_m" to it.distanceMeters) },
    )
}
