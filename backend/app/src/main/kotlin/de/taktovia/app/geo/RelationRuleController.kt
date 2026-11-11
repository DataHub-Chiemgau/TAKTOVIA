package de.taktovia.app.geo

import de.taktovia.app.identity.RequestContexts
import de.taktovia.platform.api.Etag
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.servicegeo.api.GeoPoint
import de.taktovia.servicegeo.api.PublishRelationRule
import de.taktovia.servicegeo.api.RelationEnd
import de.taktovia.servicegeo.api.RelationRuleView
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
 * Start-/Zielzulässigkeit (WP-M2-06, UI-17): erlaubte Zielpunkte je Dienst als Mengenversion (Lesen mit ETag, Veröffentlichen mit `If-Match`, 428/412) und die Prüfung
 * „Relation zulässig?“. Die Zonenrolle steht an den Bediengebieten (`/v1/service-areas`, Feld `zone_role`).
 */
@RestController
class RelationRuleController(private val geo: ServicegeoApi) {

    @GetMapping("/v1/services/{service_id}/relation-rules")
    fun get(@PathVariable("service_id") id: UUID, request: HttpServletRequest): GeoResource {
        val v = geo.relations.head(RequestContexts.require(request), id)
        return if (v == null) GeoResource(empty(id), 0) else GeoResource(json(v), v.revision)
    }

    @PutMapping("/v1/services/{service_id}/relation-rules")
    fun put(@PathVariable("service_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): GeoResource {
        val expected = Etag.parseIfMatch(ifMatch)
        val v = geo.relations.publish(RequestContexts.require(request), id, expected, PublishRelationRule(body["destination_stop_ids"], GeoWeb.instant(body["valid_from"], "/valid_from"),
            GeoWeb.optionalInstant(body["valid_to"], "/valid_to"), GeoWeb.string(body, "reason")))
        return GeoResource(json(v), v.revision)
    }

    @GetMapping("/v1/services/{service_id}/relation-check")
    fun check(@PathVariable("service_id") id: UUID, @RequestParam(name = "origin_latitude") oLat: String, @RequestParam(name = "origin_longitude") oLon: String,
              @RequestParam(name = "destination_latitude") dLat: String, @RequestParam(name = "destination_longitude") dLon: String,
              @RequestParam(name = "destination_stop_id", required = false) dStop: String?, @RequestParam(required = false) at: String?, request: HttpServletRequest): Map<String, Any?> {
        val origin = point(oLat, oLon, "origin")
        val stop = dStop?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$query/destination_stop_id", ViolationCode.INVALID_FORMAT)) }
        val destPoint = point(dLat, dLon, "destination")
        val t = GeoWeb.queryInstant(at, "at") ?: Instant.now()
        val d = geo.relations.check(RequestContexts.require(request), id, origin, RelationEnd(destPoint, stop), t)
        return linkedMapOf("service_id" to id.toString(), "at" to t.toString(), "admissible" to d.admissible, "reason" to d.reason?.name, "detail" to d.detail)
    }

    private fun point(lat: String, lon: String, prefix: String): GeoPoint {
        val la = lat.toDoubleOrNull(); val lo = lon.toDoubleOrNull()
        val bad = mutableListOf<Violation>()
        if (la == null || la !in -90.0..90.0) bad += Violation("/\$query/${prefix}_latitude", ViolationCode.INVALID_FORMAT)
        if (lo == null || lo !in -180.0..180.0) bad += Violation("/\$query/${prefix}_longitude", ViolationCode.INVALID_FORMAT)
        if (bad.isNotEmpty()) throw ProblemException.invalid(*bad.toTypedArray())
        return GeoPoint(lo!!, la!!)
    }

    private fun empty(id: UUID): Map<String, Any?> = linkedMapOf("service_id" to id.toString(), "revision" to 0L, "destination_stop_ids" to emptyList<String>(), "valid_from" to null,
        "valid_to" to null, "reason" to null, "created_by" to null, "created_at" to null)

    private fun json(v: RelationRuleView): Map<String, Any?> = linkedMapOf("service_id" to v.serviceId.toString(), "revision" to v.revision,
        "destination_stop_ids" to v.destinationStopIds.map { it.toString() }, "valid_from" to v.validFrom.toString(), "valid_to" to v.validTo?.toString(),
        "reason" to v.reason, "created_by" to v.createdBy, "created_at" to v.createdAt.toString())
}
