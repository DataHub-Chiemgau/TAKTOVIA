package de.taktovia.app.documents

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.documentsfinance.api.ApprovalDecision
import de.taktovia.documentsfinance.api.CostAttribution
import de.taktovia.documentsfinance.api.CostBehavior
import de.taktovia.documentsfinance.api.CostClassCell
import de.taktovia.documentsfinance.api.CostClassTotals
import de.taktovia.documentsfinance.api.CostClassifications
import de.taktovia.documentsfinance.api.CostSummaryQuery
import de.taktovia.documentsfinance.api.CreateManualCostEvent
import de.taktovia.documentsfinance.api.ManualCostEventView
import de.taktovia.documentsfinance.api.ManualCostEvents
import de.taktovia.documentsfinance.api.ReclassifyCostEvent
import de.taktovia.documentsfinance.api.TaxTreatment
import de.taktovia.documentsfinance.api.VehicleCostQuery
import de.taktovia.documentsfinance.api.VehicleCosts
import de.taktovia.platform.api.Etag
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
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.util.UUID

/** Manuelles Kostenereignis mit Revision (starker ETag aus [Revisioned]). */
class ManualCostResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Kostenklassifikation und Umklassifikation (WP-M4-39) sowie Fahrzeugkosten je Monat und Kostenart (WP-M4-40); Modul `documents-finance`. Rechte (`costs:read|reverse`) aus der Rechtematrix im Filter.
 * Bewertungsart, Periode und Währung der Auswertungen sind Pflicht (422 ohne); die Prüfung steht im Dienst, nicht hier.
 */
@RestController
class CostClassificationController(private val classifications: CostClassifications, private val vehicleCosts: VehicleCosts) {

    @GetMapping("/v1/cost-events/classification")
    fun classification(
        @RequestParam(required = false) valuation: String?, @RequestParam(required = false) currency: String?, @RequestParam(name = "period_from", required = false) periodFrom: String?,
        @RequestParam(name = "period_to", required = false) periodTo: String?, @RequestParam(name = "legal_entity_id", required = false) legalEntityId: String?,
        @RequestParam(name = "vehicle_id", required = false) vehicleId: String?, request: HttpServletRequest,
    ): Map<String, Any?> {
        val q = CostSummaryQuery(valuation, currency, periodFrom?.let { date(it, "/\$query/period_from") }, periodTo?.let { date(it, "/\$query/period_to") },
            legalEntityId?.let { uuid(it, "/\$query/legal_entity_id") }, vehicleId?.let { uuid(it, "/\$query/vehicle_id") })
        val s = classifications.summary(RequestContexts.require(request), q)
        return linkedMapOf("valuation" to s.valuation, "currency" to s.currency, "period_from" to s.periodFrom.toString(), "period_to" to s.periodTo.toString(), "period_basis" to s.periodBasis,
            "as_of" to s.asOf.toString(), "event_count" to s.eventCount, "totals" to totals(s.totals),
            "rows" to s.rows.map { r -> linkedMapOf("cost_object_id" to r.costObjectId?.toString(), "cost_object_kind" to r.costObjectKind, "business_ref" to r.businessRef, "vehicle_id" to r.vehicleId?.toString(),
                "totals" to totals(r.totals), "cells" to r.cells.map(::cell)) })
    }

    @PostMapping("/v1/cost-events/{cost_event_id}/reclassifications")
    fun reclassify(@PathVariable("cost_event_id") id: UUID, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val behavior = CostBehavior.entries.firstOrNull { it.name == body["cost_behavior"] } ?: throw ProblemException.invalid(Violation("/cost_behavior", if (body["cost_behavior"] == null) ViolationCode.REQUIRED else ViolationCode.NOT_IN_ENUM))
        val attribution = CostAttribution.entries.firstOrNull { it.name == body["attribution"] } ?: throw ProblemException.invalid(Violation("/attribution", if (body["attribution"] == null) ViolationCode.REQUIRED else ViolationCode.NOT_IN_ENUM))
        val reason = body["reason"] as? String ?: throw ProblemException.invalid(Violation("/reason", ViolationCode.REQUIRED))
        val date = body["accounting_date"]?.let { FinanceJson.date(it, "/accounting_date") }
        val r = classifications.reclassify(RequestContexts.require(request), id, ReclassifyCostEvent(behavior, attribution, reason, date))
        return ResponseEntity.status(HttpStatus.CREATED).body(linkedMapOf("reversal" to CostEventJson.of(r.reversal), "rebooking" to CostEventJson.of(r.rebooking),
            "reversal_journal_id" to r.reversalJournal.id.toString(), "rebooking_journal_id" to r.rebookingJournal.id.toString(), "period_id" to r.rebookingJournal.periodId.toString(), "accounting_date" to r.rebookingJournal.accountingDate.toString()))
    }

    @GetMapping("/v1/vehicles/{vehicle_id}/total-cost")
    fun totalCost(
        @PathVariable("vehicle_id") vehicleId: UUID, @RequestParam(required = false) valuation: String?, @RequestParam(required = false) currency: String?,
        @RequestParam(name = "month_from", required = false) monthFrom: String?, @RequestParam(name = "month_to", required = false) monthTo: String?, request: HttpServletRequest,
    ): Map<String, Any?> {
        val r = vehicleCosts.totalCost(RequestContexts.require(request), vehicleId, VehicleCostQuery(valuation, currency, monthFrom?.let { month(it, "/\$query/month_from") }, monthTo?.let { month(it, "/\$query/month_to") }))
        return linkedMapOf("vehicle_id" to r.vehicleId.toString(), "valuation" to r.valuation, "currency" to r.currency, "month_from" to r.monthFrom.toString(), "month_to" to r.monthTo.toString(),
            "assignment_rule" to r.assignmentRule, "as_of" to r.asOf.toString(), "total_minor" to r.totalMinor,
            "items" to r.items.map { linkedMapOf("month" to it.month.toString(), "category" to it.category, "cost_group" to it.costGroup, "label" to it.label, "amount_minor" to it.amountMinor, "event_count" to it.eventCount,
                "by_service_period_events" to it.byServicePeriodEvents, "by_accounting_date_events" to it.byAccountingDateEvents) },
            "month_totals" to r.monthTotals.map { linkedMapOf("month" to it.month.toString(), "amount_minor" to it.amountMinor) },
            "category_totals" to r.categoryTotals.map { linkedMapOf("category" to it.category, "cost_group" to it.costGroup, "amount_minor" to it.amountMinor) })
    }

    private fun totals(t: CostClassTotals): Map<String, Any?> = linkedMapOf("fixed_minor" to t.fixedMinor, "variable_minor" to t.variableMinor, "mixed_minor" to t.mixedMinor, "unclassified_minor" to t.unclassifiedMinor,
        "direct_minor" to t.directMinor, "allocated_minor" to t.allocatedMinor, "total_minor" to t.totalMinor)

    private fun cell(c: CostClassCell): Map<String, Any?> = linkedMapOf("cost_behavior" to c.costBehavior?.name, "attribution" to c.attribution?.name, "amount_minor" to c.amountMinor, "event_count" to c.eventCount)

    private fun uuid(v: String, pointer: String): UUID = try { UUID.fromString(v) } catch (e: IllegalArgumentException) { throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_FORMAT)) }
    private fun date(v: String, pointer: String) = try { java.time.LocalDate.parse(v) } catch (e: DateTimeParseException) { throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_FORMAT)) }
    private fun month(v: String, pointer: String): YearMonth = try { YearMonth.parse(v) } catch (e: DateTimeParseException) { throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_FORMAT)) }
}

/**
 * Manuelles Fahrzeugkostenereignis (WP-M4-40): anlegen (freigabepflichtig, `Idempotency-Key` übernimmt der HTTP-Filter, zusätzlich der fachliche Ursprungsschlüssel), lesen sowie Prüfung, Freigabe und
 * Ablehnung mit denselben Regeln wie beim Beleg. Rechte aus der Rechtematrix im Filter; Rollentrennung und Schwelle im Dienst.
 */
@RestController
class ManualCostEventController(private val manual: ManualCostEvents) {

    @PostMapping("/v1/cost-events")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val (gross, currency, exponent) = money(body["gross"], "/gross") ?: throw ProblemException.invalid(Violation("/gross", ViolationCode.REQUIRED))
        val tax = money(body["tax"], "/tax")
        if (tax != null && tax.second != currency) throw ProblemException.invalid(Violation("/tax/currency", ViolationCode.INVALID_VALUE, "dieselbe Währung wie der Bruttobetrag"))
        if (tax != null && tax.third != exponent) throw ProblemException.invalid(Violation("/tax/exponent", ViolationCode.INVALID_VALUE, "derselbe Exponent wie der Bruttobetrag"))
        val treatment = body["tax_treatment"]?.let { v -> TaxTreatment.entries.firstOrNull { it.name == v } ?: throw ProblemException.invalid(Violation("/tax_treatment", ViolationCode.NOT_IN_ENUM)) } ?: TaxTreatment.FULLY_DEDUCTIBLE
        val cmd = CreateManualCostEvent(
            legalEntityId = FinanceJson.uuid(body["legal_entity_id"], "/legal_entity_id"), vehicleId = FinanceJson.uuid(body["vehicle_id"], "/vehicle_id"),
            category = body["category"] as? String ?: throw ProblemException.invalid(Violation("/category", ViolationCode.REQUIRED)), currency = currency, grossMinor = gross, taxMinor = tax?.first ?: 0L, taxTreatment = treatment,
            accountingDate = FinanceJson.date(body["accounting_date"], "/accounting_date"), serviceFrom = body["service_from"]?.let { FinanceJson.date(it, "/service_from") },
            serviceTo = body["service_to"]?.let { FinanceJson.date(it, "/service_to") }, description = body["description"] as? String, originKey = body["origin_key"] as? String ?: throw ProblemException.invalid(Violation("/origin_key", ViolationCode.REQUIRED)),
            exponent = exponent,
        )
        val r = manual.create(RequestContexts.require(request), cmd)
        return ResponseEntity.status(if (r.reused) HttpStatus.OK else HttpStatus.CREATED).eTag(Etag.format(r.view.revision)).body(json(r.view))
    }

    @GetMapping("/v1/manual-cost-events/{manual_cost_event_id}")
    fun get(@PathVariable("manual_cost_event_id") id: UUID, request: HttpServletRequest): ManualCostResource = resource(manual.get(RequestContexts.require(request), id))

    @PostMapping("/v1/manual-cost-events/{manual_cost_event_id}/review")
    fun review(@PathVariable("manual_cost_event_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ManualCostResource {
        val revision = Etag.parseIfMatch(ifMatch)
        val decision = ApprovalDecision.entries.firstOrNull { it.name == body["decision"] } ?: throw ProblemException.invalid(Violation("/decision", if (body["decision"] == null) ViolationCode.REQUIRED else ViolationCode.NOT_IN_ENUM))
        val reason = body["reason"] as? String ?: throw ProblemException.invalid(Violation("/reason", ViolationCode.REQUIRED))
        return resource(manual.review(RequestContexts.require(request), id, revision, decision, reason))
    }

    @PostMapping("/v1/manual-cost-events/{manual_cost_event_id}/approve")
    fun approve(@PathVariable("manual_cost_event_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ManualCostResource {
        val revision = Etag.parseIfMatch(ifMatch)
        val reason = body["reason"] as? String ?: throw ProblemException.invalid(Violation("/reason", ViolationCode.REQUIRED))
        val o = manual.approve(RequestContexts.require(request), id, revision, reason)
        return ManualCostResource(json(o.view) + ("posting_id" to o.postingId?.toString()), o.view.revision)
    }

    @PostMapping("/v1/manual-cost-events/{manual_cost_event_id}/reject")
    fun reject(@PathVariable("manual_cost_event_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ManualCostResource {
        val revision = Etag.parseIfMatch(ifMatch)
        val reason = body["reason"] as? String ?: throw ProblemException.invalid(Violation("/reason", ViolationCode.REQUIRED))
        return resource(manual.reject(RequestContexts.require(request), id, revision, reason))
    }

    private fun resource(v: ManualCostEventView) = ManualCostResource(json(v), v.revision)

    /** Betrag `{amount_minor, currency, exponent}`: Ganzzahl (kein Gleitkomma), Rückgabe `(Betrag, Währung, Exponent)`; `null` wenn nicht vorhanden. */
    private fun money(v: Any?, pointer: String): Triple<Long, String, Int>? {
        if (v == null) return null
        val m = v as? Map<*, *> ?: throw ProblemException.invalid(Violation(pointer, ViolationCode.TYPE_MISMATCH))
        if (m.keys.any { it != "amount_minor" && it != "currency" && it != "exponent" }) throw ProblemException.invalid(Violation(pointer, ViolationCode.UNKNOWN_FIELD))
        val minor = when (val x = m["amount_minor"]) { is Int -> x.toLong(); is Long -> x; null -> throw ProblemException.invalid(Violation("$pointer/amount_minor", ViolationCode.REQUIRED)); else -> throw ProblemException.invalid(Violation("$pointer/amount_minor", ViolationCode.MONEY_NOT_INTEGER)) }
        val currency = m["currency"] as? String ?: throw ProblemException.invalid(Violation("$pointer/currency", ViolationCode.REQUIRED))
        val exponent = (m["exponent"] as? Int) ?: throw ProblemException.invalid(Violation("$pointer/exponent", ViolationCode.REQUIRED))
        return Triple(minor, currency, exponent)
    }

    private fun json(v: ManualCostEventView): LinkedHashMap<String, Any?> {
        return linkedMapOf(
            "id" to v.id.toString(), "legal_entity_id" to v.legalEntityId.toString(), "vehicle_id" to v.vehicleId.toString(), "cost_object_id" to v.costObjectId.toString(), "category" to v.category,
            "currency" to v.currency, "net_minor" to v.netMinor, "tax_minor" to v.taxMinor, "gross_minor" to v.grossMinor, "tax_treatment" to v.taxTreatment.name, "accounting_date" to v.accountingDate.toString(),
            "service_from" to v.serviceFrom?.toString(), "service_to" to v.serviceTo?.toString(), "description" to v.description, "origin_key" to v.originKey, "state" to v.state, "revision" to v.revision,
            "created_by" to v.createdBy, "approved_by" to v.approvedBy, "event_id" to v.eventId?.toString(), "created_at" to v.createdAt.toString(),
            "steps" to v.steps.map { linkedMapOf("id" to it.id.toString(), "step" to it.step.name, "revision" to it.revision, "decision" to it.decision.name, "decided_by" to it.decidedBy, "reason" to it.reason, "decided_at" to it.decidedAt.toString()) },
        )
    }
}
