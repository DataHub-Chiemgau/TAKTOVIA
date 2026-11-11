package de.taktovia.app.structure

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.documentsfinance.api.CostCenterView
import de.taktovia.documentsfinance.api.CreateCostCenter
import de.taktovia.documentsfinance.api.DocumentsfinanceApi
import de.taktovia.documentsfinance.api.UpdateCostCenter
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Paging
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.context.RequestContext
import de.taktovia.servicegeo.api.CreateService
import de.taktovia.servicegeo.api.DepotView
import de.taktovia.servicegeo.api.RegionView
import de.taktovia.servicegeo.api.ServiceView
import de.taktovia.servicegeo.api.ServicegeoApi
import de.taktovia.servicegeo.api.UpdateService
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

class StructureResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

private fun uuid(v: Any?): UUID? = (v as String?)?.let(UUID::fromString)

/**
 * Organisationsstruktur des aktiven Mandanten (WP-M1-09, UI-17 Basis): Regionen, Betriebshöfe, Dienstköpfe, Kostenstellen. Der Mandant folgt aus dem
 * geprüften Kontext, Rechte aus der Rechtematrix (Filter), Daten aus RLS. Änderungen verlangen `If-Match` (428/412).
 */
@RestController
class StructureController(private val geo: ServicegeoApi, private val finance: DocumentsfinanceApi, private val codec: CursorCodec) {

    // ---- Regionen ----

    @GetMapping("/v1/regions")
    fun listRegions(@RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> =
        page(request, "regions", limit, cursor, { c, lim, after -> geo.structure.listRegions(c, lim, after) }, { CursorPosition(it.createdAt, it.id) }, ::json)

    @PostMapping("/v1/regions")
    fun createRegion(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<StructureResource> =
        created("/v1/regions", json(geo.structure.createRegion(RequestContexts.require(request), body["name"] as String)))

    @PutMapping("/v1/regions/{region_id}")
    fun updateRegion(@PathVariable("region_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): StructureResource =
        resource(json(geo.structure.updateRegion(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch), body["name"] as String)))

    // ---- Betriebshöfe ----

    @GetMapping("/v1/depots")
    fun listDepots(@RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> =
        page(request, "depots", limit, cursor, { c, lim, after -> geo.structure.listDepots(c, lim, after) }, { CursorPosition(it.createdAt, it.id) }, ::json)

    @PostMapping("/v1/depots")
    fun createDepot(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<StructureResource> =
        created("/v1/depots", json(geo.structure.createDepot(RequestContexts.require(request), uuid(body["region_id"])!!, body["name"] as String)))

    @PutMapping("/v1/depots/{depot_id}")
    fun updateDepot(@PathVariable("depot_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): StructureResource =
        resource(json(geo.structure.updateDepot(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch), uuid(body["region_id"])!!, body["name"] as String)))

    // ---- Kostenstellen ----

    @GetMapping("/v1/cost-centers")
    fun listCostCenters(@RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> =
        page(request, "cost-centers", limit, cursor, { c, lim, after -> finance.costCenters.list(c, lim, after) }, { CursorPosition(it.createdAt, it.id) }, ::json)

    @PostMapping("/v1/cost-centers")
    fun createCostCenter(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<StructureResource> =
        created("/v1/cost-centers", json(finance.costCenters.create(RequestContexts.require(request),
            CreateCostCenter(body["code"] as String, body["name"] as String, uuid(body["legal_entity_id"])!!, uuid(body["region_id"]), uuid(body["service_id"])))))

    @PutMapping("/v1/cost-centers/{cost_center_id}")
    fun updateCostCenter(@PathVariable("cost_center_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): StructureResource =
        resource(json(finance.costCenters.update(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch), UpdateCostCenter(body["name"] as String, uuid(body["region_id"]), uuid(body["service_id"])))))

    // ---- Dienste ----

    @GetMapping("/v1/services")
    fun listServices(@RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> =
        page(request, "services", limit, cursor, { c, lim, after -> geo.structure.listServices(c, lim, after) }, { CursorPosition(it.createdAt, it.id) }, ::json)

    @PostMapping("/v1/services")
    fun createService(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<StructureResource> =
        created("/v1/services", json(geo.structure.createService(RequestContexts.require(request),
            CreateService(uuid(body["operator_id"])!!, body["name"] as String, body["timezone"] as String, uuid(body["region_id"])))))

    @PutMapping("/v1/services/{service_id}")
    fun updateService(@PathVariable("service_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): StructureResource =
        resource(json(geo.structure.updateService(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch), UpdateService(body["name"] as String, body["timezone"] as String, uuid(body["region_id"])))))

    // ---- gemeinsam ----

    private fun <T> page(request: HttpServletRequest, resource: String, limit: String?, cursor: String?, load: (RequestContext, Int, Pair<Instant, UUID>?) -> List<T>,
                         key: (T) -> CursorPosition, render: (T) -> Map<String, Any?>): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, resource)
        val lim = Paging.limit(limit)
        val rows = load(ctx, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, key, codec, scope, Instant.now()).toMap { render(it) }
    }

    private fun created(base: String, json: Map<String, Any?>) =
        ResponseEntity.status(HttpStatus.CREATED).location(URI.create("$base/${json["id"]}")).body(resource(json))

    private fun resource(json: Map<String, Any?>) = StructureResource(json, json["revision"] as Long)

    private fun json(v: RegionView): Map<String, Any?> = linkedMapOf("id" to v.id.toString(), "name" to v.name, "revision" to v.revision)
    private fun json(v: DepotView): Map<String, Any?> = linkedMapOf("id" to v.id.toString(), "region_id" to v.regionId.toString(), "name" to v.name, "revision" to v.revision)
    private fun json(v: ServiceView): Map<String, Any?> = linkedMapOf("id" to v.id.toString(), "operator_id" to v.operatorId.toString(), "name" to v.name,
        "timezone" to v.timezone, "region_id" to v.regionId?.toString(), "config_revision_id" to v.configRevisionId.toString(), "revision" to v.revision)
    private fun json(v: CostCenterView): Map<String, Any?> = linkedMapOf("id" to v.id.toString(), "code" to v.code, "name" to v.name, "legal_entity_id" to v.legalEntityId.toString(),
        "region_id" to v.regionId?.toString(), "service_id" to v.serviceId?.toString(), "revision" to v.revision)
}
