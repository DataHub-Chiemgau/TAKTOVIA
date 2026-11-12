package de.taktovia.app.documents

import de.taktovia.app.identity.RequestContexts
import de.taktovia.documentsfinance.api.CostEventCorrections
import de.taktovia.documentsfinance.api.CreateFiscalPeriod
import de.taktovia.documentsfinance.api.FiscalPeriodView
import de.taktovia.documentsfinance.api.FiscalPeriods
import de.taktovia.documentsfinance.api.JournalView
import de.taktovia.documentsfinance.api.ReverseCostEvent
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
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/** Zeitstempel und Datum aus JSON-Feldern; ungültige Werte sind 422 mit Feldverletzung. */
internal object FinanceJson {
    fun uuid(v: Any?, pointer: String): UUID = try { UUID.fromString(v as? String ?: throw ProblemException.invalid(Violation(pointer, if (v == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH))) }
        catch (e: IllegalArgumentException) { throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_FORMAT)) }

    fun date(v: Any?, pointer: String): LocalDate = try { LocalDate.parse(v as? String ?: throw ProblemException.invalid(Violation(pointer, if (v == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH))) }
        catch (e: DateTimeParseException) { throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_FORMAT)) }
}

/**
 * Fiskalperioden (WP-M4-36; Modul `documents-finance`): lesen, anlegen und abschließen. Rechte (`periods:read|manage|close`) aus der Rechtematrix im Filter, Finanzrolle im Dienst. Es gibt bewusst keinen Pfad,
 * der eine geschlossene Periode wieder öffnet, ändert oder löscht (alle anderen Methoden auf diesen Pfaden sind 405).
 */
@RestController
class FiscalPeriodController(private val periods: FiscalPeriods, private val codec: CursorCodec) {

    @GetMapping("/v1/fiscal-periods")
    fun list(@RequestParam(name = "legal_entity_id", required = false) legalEntityId: String?, @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val entity = legalEntityId?.let { v -> try { UUID.fromString(v) } catch (e: IllegalArgumentException) { throw ProblemException.invalid(Violation("/\$query/legal_entity_id", ViolationCode.INVALID_FORMAT)) } }
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "fiscal-periods:${entity ?: "all"}")
        val lim = Paging.limit(limit)
        // Der Cursor trägt Beginn (als Epochentag) und Kennung; die Zeitkomponente der Position ist der Beginn der Periode um 00:00 UTC.
        val rows = periods.list(ctx, entity, lim + 1, Paging.position(cursor, codec, scope)?.let { LocalDate.ofInstant(it.createdAt, java.time.ZoneOffset.UTC) to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.startsOn.atStartOfDay().toInstant(java.time.ZoneOffset.UTC), it.id) }, codec, scope, Instant.now()).toMap(::json)
    }

    @PostMapping("/v1/fiscal-periods")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val v = periods.create(RequestContexts.require(request), CreateFiscalPeriod(FinanceJson.uuid(body["legal_entity_id"], "/legal_entity_id"), FinanceJson.date(body["starts_on"], "/starts_on"), FinanceJson.date(body["ends_on"], "/ends_on")))
        return ResponseEntity.status(HttpStatus.CREATED).body(json(v))
    }

    @PostMapping("/v1/fiscal-periods/{period_id}/close")
    fun close(@PathVariable("period_id") id: UUID, request: HttpServletRequest): Map<String, Any?> = json(periods.close(RequestContexts.require(request), id))

    private fun json(p: FiscalPeriodView): Map<String, Any?> = linkedMapOf("id" to p.id.toString(), "legal_entity_id" to p.legalEntityId.toString(), "starts_on" to p.startsOn.toString(), "ends_on" to p.endsOn.toString(),
        "state" to (if (p.closed) "CLOSED" else "OPEN"), "closed_at" to p.closedAt?.toString(), "closed_by" to p.closedBy, "created_by" to p.createdBy)
}

/** Gegenereignis zu einem gebuchten Kostenereignis (WP-M4-36). Recht `costs:reverse` aus der Rechtematrix im Filter; `Idempotency-Key` übernimmt der HTTP-Filter. */
@RestController
class CostEventReversalController(private val corrections: CostEventCorrections) {

    @PostMapping("/v1/cost-events/{cost_event_id}/reversals")
    fun reverse(@PathVariable("cost_event_id") id: UUID, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val reason = body["reason"] as? String ?: throw ProblemException.invalid(Violation("/reason", ViolationCode.REQUIRED))
        val credit = when (val v = body["credit_gross_minor"]) {
            null -> null; is Int -> v.toLong(); is Long -> v
            else -> throw ProblemException.invalid(Violation("/credit_gross_minor", ViolationCode.MONEY_NOT_INTEGER))
        }
        val date = body["accounting_date"]?.let { FinanceJson.date(it, "/accounting_date") }
        val r = corrections.reverse(RequestContexts.require(request), id, ReverseCostEvent(reason, credit, date))
        return ResponseEntity.status(HttpStatus.CREATED).body(linkedMapOf("event" to CostEventJson.of(r.event), "journal" to journal(r.journal), "allocation" to r.allocation?.let(AllocationJson::result)))
    }

    private fun journal(j: JournalView): Map<String, Any?> = linkedMapOf("id" to j.id.toString(), "event_id" to j.eventId.toString(), "state" to j.state, "accounting_date" to j.accountingDate.toString(),
        "period_id" to j.periodId.toString(), "posted_by" to j.postedBy, "lines" to j.lines.map { linkedMapOf("account" to it.account, "side" to it.side, "amount_minor" to it.amountMinor) })
}
