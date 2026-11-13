package de.taktovia.app.fleet

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.fleetsafety.api.CreateRepairOrder
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.ReleaseDecisionView
import de.taktovia.fleetsafety.api.ReleaseVehicle
import de.taktovia.fleetsafety.api.RepairOrderView
import de.taktovia.fleetsafety.api.RepairTransition
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Paging
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

class RepairOrderResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned
class VehicleReleaseResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/** Wire-Darstellung des Reparaturauftrags (`RepairOrder` in `api/fleet-safety.yaml`). */
internal object RepairOrderJson {
    fun of(r: RepairOrderView): Map<String, Any?> = linkedMapOf(
        "id" to r.id.toString(), "defect_id" to r.defectId.toString(), "vehicle_id" to r.vehicleId.toString(), "status" to r.status.name, "workshop_ref" to r.workshopRef, "planned_at" to r.plannedAt?.toString(),
        "cost_reference" to r.costReference, "created_at" to r.createdAt.toString(), "revision" to r.revision, "proof_evidence_id" to r.proofEvidenceId?.toString(), "proof_recorded_at" to r.proofRecordedAt?.toString(),
    )

    fun resource(r: RepairOrderView) = RepairOrderResource(of(r), r.revision)

    /** `VehicleRelease`: unveränderliche Freigabeentscheidung; `revision` ist die Entscheidungsrevision (immer 1, unveränderlich). */
    fun of(d: ReleaseDecisionView): Map<String, Any?> = linkedMapOf(
        "id" to d.id.toString(), "vehicle_id" to d.vehicleId.toString(), "decision" to d.decision, "decided_by" to d.decidedBy, "decided_at" to d.decidedAt.toString(), "safety_revision" to d.safetyRevision,
        "lifted_block_ids" to d.liftedBlockIds.map { it.toString() }, "revision" to d.revision, "repair_order_id" to d.repairOrderId.toString(), "defect_id" to d.defectId?.toString(),
        "decided_by_role" to d.decidedByRole, "second_approver" to d.secondApprover, "second_approver_role" to d.secondApproverRole, "proof_evidence_id" to d.proofEvidenceId?.toString(),
    )
}

/**
 * Reparaturaufträge (WP-M3-51, UI-11): Rechte (`repair:read`, `repair:write`) aus der Rechtematrix im Filter, Mandant aus dem Kontext, Idempotenz über `Idempotency-Key`. Die Akte geht mit dem Auftrag
 * auf REPAIR; der Nachweis (`PROOF_RECORDED`) hebt keine Sperre auf, das tut allein die Wiederfreigabe.
 */
@RestController
class RepairOrderController(private val fleet: FleetsafetyApi, private val codec: CursorCodec) {

    @PostMapping("/v1/repair-orders")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<RepairOrderResource> {
        val r = fleet.repairOrders.create(RequestContexts.require(request), CreateRepairOrder.parse(body))
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/repair-orders/${r.id}")).body(RepairOrderJson.resource(r))
    }

    @PostMapping("/v1/repair-orders/{repair_order_id}/transitions")
    fun transition(@PathVariable("repair_order_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): RepairOrderResource =
        RepairOrderJson.resource(fleet.repairOrders.transition(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch), RepairTransition.parse(body)))

    @GetMapping("/v1/repair-orders")
    fun list(@RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, @RequestParam(required = false) defect_id: String?, @RequestParam(required = false) vehicle_id: String?,
             request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        fun uuid(name: String, v: String?): UUID? = v?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$query/$name", ViolationCode.INVALID_FORMAT)) }
        val defect = uuid("defect_id", defect_id); val vehicle = uuid("vehicle_id", vehicle_id)
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "repair-orders|${defect ?: "-"}|${vehicle ?: "-"}")
        val lim = Paging.limit(limit)
        val rows = fleet.repairOrders.list(ctx, defect, vehicle, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap { RepairOrderJson.of(it) }
    }
}

/**
 * Wiederfreigabe (WP-M3-51, `createVehicleRelease`): Rolle und Vieraugenpflicht prüft das Modul, `If-Match` = `safety_revision` des Fahrzeugs (428 ohne, 412 veraltet). 201 bei neuer Entscheidung, 200 bei bereits
 * abgeschlossener (dieselbe Entscheidung, keine zweite). Der Idempotenzschlüssel der Anfrage wiederholt die erste Antwort, ohne das Modul zu erreichen.
 */
@RestController
class VehicleReleaseController(private val fleet: FleetsafetyApi) {

    @PostMapping("/v1/vehicles/{vehicle_id}/release")
    fun release(@PathVariable("vehicle_id") vehicleId: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<VehicleReleaseResource> {
        val d = fleet.vehicleReleases.release(RequestContexts.require(request), vehicleId, Etag.parseIfMatch(ifMatch), ReleaseVehicle.parse(body))
        val res = VehicleReleaseResource(RepairOrderJson.of(d), d.revision)
        return if (d.replayed) ResponseEntity.ok(res) else ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/vehicles/$vehicleId/release/${d.id}")).body(res)
    }
}
