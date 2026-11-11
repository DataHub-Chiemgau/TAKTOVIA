package de.taktovia.app.geo

import de.taktovia.app.identity.RequestContexts
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Paging
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.servicegeo.api.CreateServiceArea
import de.taktovia.servicegeo.api.ReviseServiceArea
import de.taktovia.servicegeo.api.ServiceAreaView
import de.taktovia.servicegeo.api.ServicegeoApi
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
 * Bediengebiete (WP-M2-04, UI-17): GeoJSON-`MultiPolygon` mit Aktivierungszeitraum, Revisionskette, `If-Match` (428/412). Der Mandant folgt aus dem geprüften Kontext, Rechte
 * aus der Rechtematrix (Filter), Mandantentrennung aus RLS; Geometrie und Überlappungsregeln prüft das Modul `service-geo`.
 */
@RestController
class ServiceAreaController(private val geo: ServicegeoApi, private val codec: CursorCodec) {

    @GetMapping("/v1/service-areas")
    fun list(@RequestParam(name = "service_id", required = false) serviceId: String?, @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?,
             request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val service = serviceId?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$query/service_id", ViolationCode.INVALID_FORMAT)) }
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "service-areas/" + (service ?: "all"))
        val lim = Paging.limit(limit)
        val rows = geo.areas.list(ctx, service, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.revisionId) }, codec, scope, Instant.now()).toMap(::json)
    }

    @PostMapping("/v1/service-areas")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<GeoResource> {
        val v = geo.areas.create(RequestContexts.require(request), CreateServiceArea(uuid(body, "service_id"), body["area"], GeoWeb.instant(body["valid_from"], "/valid_from"),
            GeoWeb.optionalInstant(body["valid_to"], "/valid_to")))
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/service-areas/${v.id}")).body(GeoResource(json(v), v.revision))
    }

    @GetMapping("/v1/service-areas/{area_id}")
    fun get(@PathVariable("area_id") id: UUID, @RequestParam(required = false) revision: String?, request: HttpServletRequest): GeoResource {
        val rev = revision?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: throw ProblemException.invalid(Violation("/\$query/revision", ViolationCode.INVALID_FORMAT)) }
        val v = geo.areas.get(RequestContexts.require(request), id, rev)
        return GeoResource(json(v), v.revision)
    }

    @PutMapping("/v1/service-areas/{area_id}")
    fun update(@PathVariable("area_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): GeoResource {
        val expected = Etag.parseIfMatch(ifMatch)
        val v = geo.areas.revise(RequestContexts.require(request), id, expected, ReviseServiceArea(body["area"], GeoWeb.instant(body["valid_from"], "/valid_from"), GeoWeb.optionalInstant(body["valid_to"], "/valid_to")))
        return GeoResource(json(v), v.revision)
    }

    private fun uuid(body: Map<String, Any?>, field: String): UUID =
        runCatching { UUID.fromString(GeoWeb.string(body, field)) }.getOrNull() ?: throw ProblemException.invalid(Violation("/$field", ViolationCode.INVALID_FORMAT))

    private fun json(v: ServiceAreaView): Map<String, Any?> = linkedMapOf("id" to v.id.toString(), "revision_id" to v.revisionId.toString(), "service_id" to v.serviceId.toString(),
        "revision" to v.revision, "area" to v.area, "valid_from" to v.validFrom.toString(), "valid_to" to v.validTo?.toString())
}
