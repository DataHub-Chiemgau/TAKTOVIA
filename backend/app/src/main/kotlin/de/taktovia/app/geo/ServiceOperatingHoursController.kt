package de.taktovia.app.geo

import de.taktovia.app.identity.RequestContexts
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Paging
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.servicegeo.api.PublishServiceRevision
import de.taktovia.servicegeo.api.ServiceRevisionView
import de.taktovia.servicegeo.api.ServicegeoApi
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Betriebszeiten und Dienstversionen (WP-M2-03, UI-17): Kopf lesen mit ETag, neue Version per `PUT` mit `If-Match` (428/412), Versionsliste und die
 * Lesesicht für Fahrgäste (Betriebsstatus). Rechte aus der Rechtematrix (Filter), Mandant aus dem geprüften Kontext, Daten aus RLS.
 */
@RestController
class ServiceOperatingHoursController(private val geo: ServicegeoApi) {

    @GetMapping("/v1/services/{service_id}/operating-hours")
    fun get(@PathVariable("service_id") id: UUID, request: HttpServletRequest): GeoResource {
        val head = geo.serviceRevisions.head(RequestContexts.require(request), id)
        return if (head == null) GeoResource(empty(id), 0) else GeoResource(json(head), head.revision)
    }

    @PutMapping("/v1/services/{service_id}/operating-hours")
    fun put(@PathVariable("service_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): GeoResource {
        val expected = Etag.parseIfMatch(ifMatch)
        val cmd = PublishServiceRevision(GeoWeb.string(body, "traffic_product"), body["weekly_hours"], body["exceptions"], GeoWeb.instant(body["valid_from"], "/valid_from"),
            GeoWeb.optionalInstant(body["valid_to"], "/valid_to"), GeoWeb.string(body, "reason"))
        val v = geo.serviceRevisions.publish(RequestContexts.require(request), id, expected, cmd)
        return GeoResource(json(v), v.revision)
    }

    @GetMapping("/v1/services/{service_id}/operating-hours/revisions")
    fun revisions(@PathVariable("service_id") id: UUID, @RequestParam(required = false) limit: String?, @RequestParam(name = "before_revision", required = false) before: String?,
                  request: HttpServletRequest): Map<String, Any?> {
        val lim = Paging.limit(limit)
        val beforeRev = before?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: throw ProblemException.invalid(Violation("/\$query/before_revision", ViolationCode.INVALID_FORMAT)) }
        val rows = geo.serviceRevisions.list(RequestContexts.require(request), id, lim, beforeRev)
        val page = rows.take(lim)
        return linkedMapOf("items" to page.map(::json), "next_before_revision" to if (rows.size > lim) page.last().revision else null, "as_of" to Instant.now().toString())
    }

    @GetMapping("/v1/services/{service_id}/availability")
    fun availability(@PathVariable("service_id") id: UUID, @RequestParam(required = false) at: String?, request: HttpServletRequest): Map<String, Any?> {
        val t = GeoWeb.queryInstant(at, "at") ?: Instant.now()
        val a = geo.availability.at(RequestContexts.require(request), id, t)
        return linkedMapOf("service_id" to a.serviceId.toString(), "at" to a.at.toString(), "in_operation" to a.inOperation, "next_opening" to a.nextOpening?.toString(),
            "timezone" to a.timezone, "traffic_product" to a.trafficProduct)
    }

    private fun empty(id: UUID): Map<String, Any?> = linkedMapOf("service_id" to id.toString(), "revision" to 0L, "traffic_product" to null, "timezone" to null, "weekly_hours" to null,
        "exceptions" to null, "valid_from" to null, "valid_to" to null, "reason" to null, "created_by" to null, "created_at" to null)

    private fun json(v: ServiceRevisionView): Map<String, Any?> = linkedMapOf("service_id" to v.serviceId.toString(), "revision" to v.revision, "traffic_product" to v.trafficProduct,
        "timezone" to v.timezone, "weekly_hours" to v.weeklyHours, "exceptions" to v.exceptions, "valid_from" to v.validFrom.toString(), "valid_to" to v.validTo?.toString(),
        "reason" to v.reason, "created_by" to v.createdBy, "created_at" to v.createdAt.toString())
}
