package de.taktovia.app.documents

import de.taktovia.app.identity.RequestContexts
import de.taktovia.documentsfinance.api.CostEventView
import de.taktovia.documentsfinance.api.CostEvents
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Paging
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Kostenereignisse des Ist-Berichts (WP-M4-33; Modul `documents-finance`): nur Ereignisse mit gebuchtem Journal, aufsteigend nach `(booked_at, id)`, signierte Cursor, `as_of` begrenzt auf den
 * Buchungsstand zu einem Zeitpunkt. Der Cursor gilt nur für denselben Filter (Rechtsträger, `as_of`). Das Posting selbst löst der Freigabe-Workflow aus (WP-M4-34), kein HTTP-Pfad.
 */
@RestController
class CostEventController(private val events: CostEvents, private val codec: CursorCodec) {

    @GetMapping("/v1/cost-events")
    fun list(
        @RequestParam(name = "as_of", required = false) asOf: String?, @RequestParam(name = "legal_entity_id", required = false) legalEntityId: String?,
        @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val at = asOf?.let { v -> try { Instant.parse(v) } catch (e: DateTimeParseException) { throw ProblemException.invalid(Violation("/\$query/as_of", ViolationCode.INVALID_FORMAT)) } }
        val entity = legalEntityId?.let { v -> try { UUID.fromString(v) } catch (e: IllegalArgumentException) { throw ProblemException.invalid(Violation("/\$query/legal_entity_id", ViolationCode.INVALID_FORMAT)) } }
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "cost-events:${entity ?: "all"}:${at ?: "now"}")
        val lim = Paging.limit(limit)
        val rows = events.list(ctx, lim + 1, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id }, at, entity)
        return Paging.page(rows, lim, { CursorPosition(it.bookedAt, it.id) }, codec, scope, at ?: Instant.now()).toMap(::json)
    }

    private fun json(e: CostEventView): Map<String, Any?> = CostEventJson.of(e)
}

/** JSON-Form eines Kostenereignisses (`CostEvent`), gemeinsam für die Liste und das Gegenereignis (WP-M4-36). */
internal object CostEventJson {
    fun of(e: CostEventView): Map<String, Any?> = linkedMapOf(
        "id" to e.id.toString(), "legal_entity_id" to e.legalEntityId.toString(), "source_system" to e.sourceSystem, "source_ref" to e.sourceRef, "event_type" to e.eventType, "scenario" to e.scenario,
        "category" to e.category, "currency" to e.currency, "net_minor" to e.netMinor, "tax_minor" to e.taxMinor, "gross_minor" to e.grossMinor, "recoverable_tax_minor" to e.recoverableTaxMinor,
        "cost_basis_minor" to e.costBasisMinor, "tax_rule_revision" to e.taxRuleRevision, "occurred_at" to e.occurredAt.toString(), "booked_at" to e.bookedAt.toString(),
        "document_id" to e.documentId?.toString(), "reverses_id" to e.reversesId?.toString(),
    )
}
