package de.taktovia.app.documents

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.documentsfinance.api.AssignCosts
import de.taktovia.documentsfinance.api.CaptureFuel
import de.taktovia.documentsfinance.api.DuplicateCandidateView
import de.taktovia.documentsfinance.api.DuplicateDecision
import de.taktovia.documentsfinance.api.DuplicateReview
import de.taktovia.documentsfinance.api.FuelPlausibility
import de.taktovia.documentsfinance.api.FuelReceipts
import de.taktovia.documentsfinance.api.HintView
import de.taktovia.documentsfinance.api.FuelType
import de.taktovia.documentsfinance.api.FuelView
import de.taktovia.documentsfinance.api.CorrectExpense
import de.taktovia.documentsfinance.api.CostAssignments
import de.taktovia.documentsfinance.api.CostObjectView
import de.taktovia.documentsfinance.api.ExpenseView
import de.taktovia.documentsfinance.api.Expenses
import de.taktovia.documentsfinance.api.RevisionView
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.Base64
import java.util.UUID

/** JSON-Form eines Kostenobjekts (WP-M4-29), gemeinsam für Beleg und Kostenobjektliste. */
internal object CostObjectJson {
    fun of(o: CostObjectView): Map<String, Any?> = linkedMapOf("id" to o.id.toString(), "kind" to o.kind.name, "reference" to o.reference, "name" to o.name,
        "legal_entity_id" to o.legalEntityId.toString(), "vehicle_id" to o.vehicleId?.toString(), "active" to o.active)
}

private val DECIMAL = Regex("[0-9]{1,12}(\\.[0-9]{1,8})?")

class ExpenseResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Beleg lesen, Revisionen, Korrektur und Original (WP-M4-22, WP-M4-23, WP-M4-24; Modul `documents-finance`). Rechte (`receipts:read|write`) aus der Rechtematrix im Filter; Mandant und Akteur aus
 * dem Kontext. Änderungen nur mit `If-Match` (428 ohne, 412 bei veralteter Revision). Das Original wird unverändert ausgeliefert, nach einer Hashprüfung.
 */
@RestController
class ExpenseController(private val expenses: Expenses, private val costs: CostAssignments, private val fuel: FuelReceipts, private val plausibility: FuelPlausibility, private val duplicates: DuplicateReview) {

    @GetMapping("/v1/expenses/{expense_id}")
    fun get(@PathVariable("expense_id") id: UUID, request: HttpServletRequest): ExpenseResource = resource(expenses.get(RequestContexts.require(request), id))

    @PatchMapping("/v1/expenses/{expense_id}")
    @Suppress("UNCHECKED_CAST")
    fun correct(@PathVariable("expense_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ExpenseResource {
        val revision = Etag.parseIfMatch(ifMatch)
        val ctx = RequestContexts.require(request)
        val assignment = body["cost_assignment"] as Map<String, Any?>?
        val fuelBody = body["fuel"] as Map<String, Any?>?
        if (fuelBody != null) {
            // Tankwerte (WP-M4-30): eigene Revision, nicht mit Feldkorrektur oder Zuordnung kombinierbar
            if (assignment != null || !((body["fields"] as Map<*, *>?).isNullOrEmpty() && (body["confirm"] as List<*>?).isNullOrEmpty())) throw ProblemException.invalid(Violation("/fuel", ViolationCode.INVALID_VALUE))
            return resource(fuel.capture(ctx, id, revision, captureOf(fuelBody)))
        }
        if (assignment != null) {
            // Kostenzuordnung (WP-M4-29): nicht mit Feldkorrekturen kombinierbar (eine Revision je Änderung)
            if (!((body["fields"] as Map<*, *>?).isNullOrEmpty() && (body["confirm"] as List<*>?).isNullOrEmpty())) throw ProblemException.invalid(Violation("/cost_assignment", ViolationCode.INVALID_VALUE))
            return resource(costs.assign(ctx, id, revision, AssignCosts(uuids(assignment["cost_object_ids"], "/cost_assignment/cost_object_ids"), uuids(assignment["vehicle_ids"], "/cost_assignment/vehicle_ids"))))
        }
        val reason = body["reason"] as String? ?: throw ProblemException.invalid(Violation("/reason", ViolationCode.REQUIRED))
        val cmd = CorrectExpense(reason, (body["fields"] as Map<String, Any?>?) ?: emptyMap(), ((body["confirm"] as List<String>?) ?: emptyList()).toSet())
        return resource(expenses.correct(ctx, id, revision, cmd))
    }

    /** Dezimalwerte kommen nur als Strings (nie JSON-Zahlen, nie Gleitkomma); Zeitpunkte mit UTC-Offset. */
    private fun captureOf(m: Map<String, Any?>): CaptureFuel {
        fun decimal(key: String): java.math.BigDecimal? = m[key]?.let { v ->
            val pointer = "/fuel/$key"
            if (v !is String) throw ProblemException.invalid(Violation(pointer, ViolationCode.TYPE_MISMATCH))
            if (!DECIMAL.matches(v)) throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_FORMAT))
            java.math.BigDecimal(v)
        }
        return CaptureFuel(
            vehicleId = m["vehicle_id"]?.let { v -> try { UUID.fromString(v as String) } catch (e: RuntimeException) { throw ProblemException.invalid(Violation("/fuel/vehicle_id", ViolationCode.INVALID_FORMAT)) } },
            fuelType = m["fuel_type"]?.let { v -> FuelType.entries.firstOrNull { it.name == v } ?: throw ProblemException.invalid(Violation("/fuel/fuel_type", ViolationCode.NOT_IN_ENUM)) },
            quantity = decimal("quantity"), unitPrice = decimal("unit_price"),
            odometerKm = m["odometer_km"]?.let { v -> if (v is Int) v.toLong() else if (v is Long) v else throw ProblemException.invalid(Violation("/fuel/odometer_km", ViolationCode.TYPE_MISMATCH)) },
            fueledAt = m["fueled_at"]?.let { v -> try { java.time.OffsetDateTime.parse(v as String).toInstant() } catch (e: RuntimeException) { throw ProblemException.invalid(Violation("/fuel/fueled_at", ViolationCode.OFFSET_REQUIRED)) } },
            cardId = m["card_id"]?.let { v -> v as? String ?: throw ProblemException.invalid(Violation("/fuel/card_id", ViolationCode.TYPE_MISMATCH)) },
        )
    }

    private fun fuelJson(f: FuelView): Map<String, Any?> = linkedMapOf("vehicle_id" to f.vehicleId?.toString(), "fuel_type" to f.fuelType?.name, "quantity" to f.quantity?.toPlainString(), "unit" to f.unit?.name,
        "unit_price" to f.unitPrice?.toPlainString(), "gross_minor" to f.grossMinor, "currency" to f.currency, "odometer_km" to f.odometerKm, "fueled_at" to f.fueledAt?.toString(), "card_id" to f.cardId,
        "missing" to f.missing, "complete" to f.complete)

    private fun duplicateJson(d: DuplicateCandidateView): Map<String, Any?> = linkedMapOf("id" to d.id.toString(), "role" to d.role.name, "other_document_id" to d.otherDocumentId?.toString(), "match" to d.match.name,
        "certainty" to d.certainty.name, "score" to d.score, "basis" to d.basis, "state" to d.state.name, "detected_at" to d.detectedAt.toString(), "decided_by" to d.decidedBy, "decided_at" to d.decidedAt?.toString(), "reason" to d.reason,
        "other" to d.other?.let { linkedMapOf("merchant" to it.merchant, "document_date" to it.documentDate, "currency" to it.currency, "amount_minor" to it.amountMinor, "document_number" to it.documentNumber,
            "source_channel" to it.channel.name, "received_at" to it.receivedAt.toString()) })

    private fun hintJson(h: HintView): Map<String, Any?> = linkedMapOf("id" to h.id.toString(), "rule" to h.rule.name, "document_revision" to h.documentRevision, "threshold" to h.threshold,
        "actual_value" to h.actualValue, "basis" to h.basis, "raised_at" to h.raisedAt.toString(),
        "acknowledgement" to h.acknowledgement?.let { linkedMapOf("acknowledged_by" to it.by, "reason" to it.reason, "acknowledged_at" to it.at.toString()) })

    private fun uuids(v: Any?, pointer: String): Set<UUID> = ((v as List<*>?) ?: emptyList<Any>()).map {
        try { UUID.fromString(it as String) } catch (e: RuntimeException) { throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_FORMAT)) }
    }.toSet()

    /** Quittierung eines Plausibilitätshinweises (WP-M4-31); `If-Match` trägt die Belegrevision. */
    @PostMapping("/v1/expenses/{expense_id}/hints/{hint_id}/acknowledgement")
    fun acknowledgeHint(@PathVariable("expense_id") id: UUID, @PathVariable("hint_id") hintId: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ExpenseResource {
        val revision = Etag.parseIfMatch(ifMatch)
        val reason = body["reason"] as? String ?: throw ProblemException.invalid(Violation("/reason", ViolationCode.REQUIRED))
        return resource(plausibility.acknowledge(RequestContexts.require(request), id, hintId, revision, reason))
    }

    /** Entscheidung zu einem offenen Dublettentreffer (WP-M4-32); `If-Match` trägt die Belegrevision. */
    @PostMapping("/v1/expenses/{expense_id}/duplicates/{candidate_id}/decision")
    fun decideDuplicate(@PathVariable("expense_id") id: UUID, @PathVariable("candidate_id") candidateId: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ExpenseResource {
        val revision = Etag.parseIfMatch(ifMatch)
        val decision = DuplicateDecision.entries.firstOrNull { it.name == body["decision"] } ?: throw ProblemException.invalid(Violation("/decision", if (body["decision"] == null) ViolationCode.REQUIRED else ViolationCode.NOT_IN_ENUM))
        val reason = body["reason"] as? String ?: throw ProblemException.invalid(Violation("/reason", ViolationCode.REQUIRED))
        return resource(duplicates.decide(RequestContexts.require(request), id, candidateId, revision, decision, reason))
    }

    @GetMapping("/v1/expenses/{expense_id}/revisions")
    fun revisions(@PathVariable("expense_id") id: UUID, request: HttpServletRequest): Map<String, Any?> =
        linkedMapOf("items" to expenses.revisions(RequestContexts.require(request), id).map(::json))

    @GetMapping("/v1/expenses/{expense_id}/original")
    fun original(@PathVariable("expense_id") id: UUID, request: HttpServletRequest): ResponseEntity<ByteArray> {
        val ctx = RequestContexts.require(request)
        val mime = expenses.get(ctx, id).document.mime
        val bytes = expenses.original(ctx, id)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime)).cacheControl(CacheControl.noStore()).header("Content-Disposition", "inline; filename=\"beleg-$id\"").body(bytes)
    }

    private fun resource(e: ExpenseView): ExpenseResource {
        val d = e.document
        return ExpenseResource(linkedMapOf(
            "document_id" to d.documentId.toString(), "legal_entity_id" to d.legalEntityId?.toString(), "state" to d.workflowState, "source_channel" to d.channel.name, "document_kind" to d.kind.name,
            "case_ref" to d.caseRef?.let { linkedMapOf("kind" to it.kind.name, "id" to it.id.toString()) }, "mime" to d.mime, "size_bytes" to d.sizeBytes, "sha256" to toB64(d.sha256),
            "received_at" to d.receivedAt.toString(), "scanned_at" to d.scannedAt?.toString(), "sender_verified" to d.senderVerified, "integrity" to d.integrity.name, "approvable" to e.approvable,
            "blockers" to e.blockers.map { it.name }, "unconfirmed_fields" to e.review.unconfirmedFields, "sum_mismatch" to e.review.sumMismatch, "cost_objects" to e.costObjects.map(::json), "fuel" to e.fuel?.let(::fuelJson),
            "hints" to e.plausibility.hints.map(::hintJson), "plausibility_notes" to e.plausibility.notes.map { linkedMapOf("rule" to it.rule.name, "code" to it.code, "text" to it.text) },
            "duplicates" to e.duplicates.map(::duplicateJson), "fields" to e.fields, "revision" to d.revision,
        ), d.revision)
    }

    private fun json(o: CostObjectView): Map<String, Any?> = CostObjectJson.of(o)

    private fun json(r: RevisionView): Map<String, Any?> = linkedMapOf(
        "revision" to r.revision, "fields" to r.fields.mapValues { (_, c) -> linkedMapOf<String, Any?>("old" to c.old, "new" to c.new, "confirmed" to c.confirmed).also { if (c.ocrProposal != null) it["ocr_proposal"] = c.ocrProposal } },
        "edited_by" to r.editedBy, "edited_at" to r.editedAt.toString(), "reason" to r.reason, "edited_view_key" to r.editedViewKey,
    )

    private fun toB64(hex: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
}
