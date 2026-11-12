package de.taktovia.app.documents

import de.taktovia.app.identity.RequestContexts
import de.taktovia.documentsfinance.api.ConflictDecision
import de.taktovia.documentsfinance.api.OdometerConflictStatus
import de.taktovia.documentsfinance.api.OdometerConflictView
import de.taktovia.documentsfinance.api.OdometerReadingView
import de.taktovia.documentsfinance.api.OdometerRegister
import de.taktovia.documentsfinance.api.OdometerSource
import de.taktovia.documentsfinance.api.RecordOdometer
import de.taktovia.documentsfinance.api.ResolveConflict
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Paging
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Kilometerstände und Konflikte je Fahrzeug (WP-M4-41, FCO-003; Modul `documents-finance`). Rechte (`odometer:read|record|resolve`) aus der Rechtematrix im Filter; Mandant und Akteur aus dem Kontext.
 * Es gibt bewusst keinen Pfad, der einen Stand ändert oder löscht oder einen Konflikt automatisch auflöst.
 */
@RestController
class OdometerController(private val register: OdometerRegister, private val codec: CursorCodec) {

    @GetMapping("/v1/vehicles/{vehicle_id}/odometer")
    fun readings(@PathVariable("vehicle_id") vehicleId: UUID, @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "odometer:$vehicleId")
        val lim = Paging.limit(limit)
        val rows = register.readings(ctx, vehicleId, lim + 1, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.measuredAt, it.id) }, codec, scope, Instant.now()).toMap(::reading)
    }

    @PostMapping("/v1/vehicles/{vehicle_id}/odometer")
    fun record(@PathVariable("vehicle_id") vehicleId: UUID, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val ctx = RequestContexts.require(request)
        val km = when (val v = body["reading_km"]) {
            null -> throw ProblemException.invalid(Violation("/reading_km", ViolationCode.REQUIRED))
            is Int -> v.toLong(); is Long -> v
            else -> throw ProblemException.invalid(Violation("/reading_km", ViolationCode.TYPE_MISMATCH))
        }
        val ref = body["source_ref"] as? String ?: throw ProblemException.invalid(Violation("/source_ref", if (body["source_ref"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH))
        val at = (body["measured_at"] as? String)?.let { try { Instant.parse(it) } catch (e: DateTimeParseException) { throw ProblemException.invalid(Violation("/measured_at", ViolationCode.INVALID_FORMAT)) } }
            ?: throw ProblemException.invalid(Violation("/measured_at", if (body["measured_at"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH))
        // der Importkanal kennt nur die Werkstatt; die anderen Quellen kommen aus ihren Ereignissen (`source` im Body wird nie gelesen)
        val out = register.record(ctx, vehicleId, RecordOdometer(OdometerSource.WORKSHOP, ref, km, at))
        return ResponseEntity.status(if (out.created) HttpStatus.CREATED else HttpStatus.OK).body(linkedMapOf("reading" to reading(out.reading), "created" to out.created, "conflicts" to out.conflicts.map(::conflict)))
    }

    @GetMapping("/v1/vehicles/{vehicle_id}/odometer/conflicts")
    fun conflicts(@PathVariable("vehicle_id") vehicleId: UUID, @RequestParam(required = false) status: String?, @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?,
        request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val st = status?.let { s -> OdometerConflictStatus.entries.firstOrNull { it.name == s } ?: throw ProblemException.invalid(Violation("/\$query/status", ViolationCode.NOT_IN_ENUM)) }
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "odometer-conflicts:$vehicleId:${st ?: "all"}")
        val lim = Paging.limit(limit)
        val rows = register.conflicts(ctx, vehicleId, st, lim + 1, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.detectedAt, it.id) }, codec, scope, Instant.now()).toMap(::conflict)
    }

    @PostMapping("/v1/vehicles/{vehicle_id}/odometer/conflicts/{conflict_id}/resolution")
    fun resolve(@PathVariable("vehicle_id") vehicleId: UUID, @PathVariable("conflict_id") conflictId: UUID, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val decision = (body["decision"] as? String)?.let { d -> ConflictDecision.entries.firstOrNull { it.name == d } ?: throw ProblemException.invalid(Violation("/decision", ViolationCode.NOT_IN_ENUM)) }
            ?: throw ProblemException.invalid(Violation("/decision", if (body["decision"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH))
        val chosen = body["chosen_reading_id"]?.let { v -> try { UUID.fromString(v as? String ?: throw ProblemException.invalid(Violation("/chosen_reading_id", ViolationCode.TYPE_MISMATCH))) }
            catch (e: IllegalArgumentException) { throw ProblemException.invalid(Violation("/chosen_reading_id", ViolationCode.INVALID_FORMAT)) } }
        val reason = body["reason"] as? String ?: throw ProblemException.invalid(Violation("/reason", if (body["reason"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH))
        return conflict(register.resolve(ctx, vehicleId, conflictId, ResolveConflict(decision, chosen, reason)))
    }

    private fun reading(r: OdometerReadingView): Map<String, Any?> = linkedMapOf("id" to r.id.toString(), "vehicle_id" to r.vehicleId.toString(), "reading_km" to r.readingKm, "source" to r.source.name,
        "source_ref" to r.sourceRef, "measured_at" to r.measuredAt.toString(), "recorded_at" to r.recordedAt.toString(), "recorded_by" to r.recordedBy)

    private fun conflict(c: OdometerConflictView): Map<String, Any?> = linkedMapOf("id" to c.id.toString(), "vehicle_id" to c.vehicleId.toString(), "kind" to c.kind.name, "status" to c.status.name,
        "tolerance_km" to c.toleranceKm, "earlier" to reading(c.earlier), "later" to reading(c.later), "detected_at" to c.detectedAt.toString(),
        "resolution" to c.resolution?.let { linkedMapOf("decision" to it.decision.name, "chosen_reading_id" to it.chosenReadingId?.toString(), "resolved_by" to it.resolvedBy, "reason" to it.reason, "resolved_at" to it.resolvedAt.toString()) })
}
