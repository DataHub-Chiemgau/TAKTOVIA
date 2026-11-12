package de.taktovia.app.geo

import de.taktovia.app.identity.RequestContexts
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Page
import de.taktovia.platform.api.Paging
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.servicegeo.api.CreateStop
import de.taktovia.servicegeo.api.GeoPoint
import de.taktovia.servicegeo.api.ReviseStop
import de.taktovia.servicegeo.api.ServicegeoApi
import de.taktovia.servicegeo.api.PassengerStopView
import de.taktovia.servicegeo.api.StopFilter
import de.taktovia.servicegeo.api.StopView
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * Haltepunkte (WP-M2-05, UI-17): Revisionen mit Aktivstatus und zulässigen Verkehrsprodukten. Fahrgäste sehen ausschließlich aktive, jetzt wirksame Haltepunkte (GEO-002);
 * Pflege verlangt `geo:write`. Änderungen mit `If-Match` (428/412); die Deaktivierung ist eine neue Revision.
 */
@RestController
class StopController(private val geo: ServicegeoApi, private val codec: CursorCodec) {

    @GetMapping("/v1/stops")
    fun list(@RequestParam(name = "service_id", required = false) serviceId: String?, @RequestParam(required = false) at: String?,
             @RequestParam(name = "near_latitude", required = false) nearLat: String?, @RequestParam(name = "near_longitude", required = false) nearLon: String?,
             @RequestParam(name = "near_radius_m", required = false) radius: String?, @RequestParam(required = false) active: String?,
             @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val service = serviceId?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw invalid("service_id") }
        val near = when {
            nearLat == null && nearLon == null -> null
            nearLat != null && nearLon != null -> runCatching { GeoPoint(nearLon.toDouble(), nearLat.toDouble()) }.getOrNull() ?: throw invalid("near_latitude")
            else -> throw ProblemException.invalid(Violation(if (nearLat == null) "/\$query/near_latitude" else "/\$query/near_longitude", ViolationCode.REQUIRED))
        }
        val rad = radius?.let { it.toIntOrNull()?.takeIf { v -> v in 1..100_000 } ?: throw invalid("near_radius_m") } ?: 1000
        val act = active?.let { when (it) { "true" -> true; "false" -> false; else -> throw invalid("active") } }
        val atInstant = GeoWeb.queryInstant(at, "at")
        // Fahrgäste und alle Rollen ohne Stammdatenrecht: enge Lesesicht (WP-M2-40), ohne interne Felder, nach Entfernung bzw. Name sortiert, ohne Folgeseiten
        if (!ctx.hasScope("geo:write")) {
            val rows = geo.passenger.stops(ctx, service, atInstant, near, if (near != null) rad else null, Paging.limit(limit))
            return Page(rows, null, Instant.now()).toMap(::passengerJson)
        }
        val filter = StopFilter(service, atInstant, near, if (near != null) rad else null, act)
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "stops/" + listOf(service, at, near, act, rad).joinToString("|"))
        val lim = Paging.limit(limit)
        val rows = geo.stops.list(ctx, filter, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.revisionId) }, codec, scope, Instant.now()).toMap(::json)
    }

    @PostMapping("/v1/stops")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<GeoResource> {
        val stopId = body["stop_id"]?.let { runCatching { UUID.fromString(it as String) }.getOrNull() ?: throw ProblemException.invalid(Violation("/stop_id", ViolationCode.INVALID_FORMAT)) }
        val v = geo.stops.create(RequestContexts.require(request), CreateStop(stopId, GeoWeb.string(body, "name"), body["point"], body["accessibility"], body["traffic_products"],
            body["active"] as? Boolean ?: true, GeoWeb.instant(body["valid_from"], "/valid_from"), GeoWeb.optionalInstant(body["valid_to"], "/valid_to")))
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/stops/${v.stopId}")).body(GeoResource(json(v), v.revision))
    }

    @GetMapping("/v1/stops/{stop_id}")
    fun get(@PathVariable("stop_id") id: UUID, @RequestParam(required = false) revision: String?, request: HttpServletRequest): GeoResource {
        val rev = revision?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: throw invalid("revision") }
        val ctx = RequestContexts.require(request)
        if (!ctx.hasScope("geo:write")) {
            if (rev != null) throw ProblemException.notFound()   // ältere Revisionen sind Pflegesicht
            val v = geo.passenger.stop(ctx, id, null)
            return GeoResource(passengerJson(v), v.revision)
        }
        val v = geo.stops.get(ctx, id, rev)
        return GeoResource(json(v), v.revision)
    }

    @PutMapping("/v1/stops/{stop_id}")
    fun update(@PathVariable("stop_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): GeoResource {
        val expected = Etag.parseIfMatch(ifMatch)
        val v = geo.stops.revise(RequestContexts.require(request), id, expected, ReviseStop(GeoWeb.string(body, "name"), body["point"], body["accessibility"], body["traffic_products"],
            body["active"] as? Boolean ?: throw ProblemException.invalid(Violation("/active", ViolationCode.REQUIRED)), GeoWeb.instant(body["valid_from"], "/valid_from"), GeoWeb.optionalInstant(body["valid_to"], "/valid_to")))
        return GeoResource(json(v), v.revision)
    }

    private fun invalid(name: String) = ProblemException.invalid(Violation("/\$query/$name", ViolationCode.INVALID_FORMAT))

    private fun passengerJson(v: PassengerStopView): Map<String, Any?> = linkedMapOf("stop_id" to v.stopId.toString(), "revision" to v.revision, "name" to v.name,
        "point" to linkedMapOf("latitude" to v.point.latitude, "longitude" to v.point.longitude), "accessibility" to v.accessibility, "distance_m" to v.distanceMeters)

    private fun json(v: StopView): Map<String, Any?> = linkedMapOf("id" to v.revisionId.toString(), "stop_id" to v.stopId.toString(), "revision" to v.revision, "name" to v.name,
        "point" to linkedMapOf("latitude" to v.point.latitude, "longitude" to v.point.longitude), "accessibility" to v.accessibility, "traffic_products" to v.trafficProducts,
        "active" to v.active, "approved_by" to v.approvedBy, "valid_from" to v.validFrom.toString(), "valid_to" to v.validTo?.toString())
}
