package de.taktovia.app.payment

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.geo.GeoWeb
import de.taktovia.app.identity.RequestContexts
import de.taktovia.farepayment.api.CreateTariffRevision
import de.taktovia.farepayment.api.TariffRevisionView
import de.taktovia.farepayment.api.TariffRuleInput
import de.taktovia.farepayment.api.TariffState
import de.taktovia.farepayment.api.Tariffs
import de.taktovia.farepayment.api.UpdateTariffRevision
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Paging
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
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

/** Antwort mit Ressourcenrevision (der Antwortfilter setzt daraus den ETag). */
class TariffResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Tarifpflege (WP-M2-07, UI-17): Tarifrevision anlegen, ändern, prüfen, veröffentlichen, wirksame Revision zum Preisstichtag lesen. Mandant aus dem geprüften Kontext, Rechte
 * aus der Rechtematrix (Funktionsscopes `tariffs:*`), Mandantentrennung aus RLS; Pflichtangaben, Vier-Augen und Überlappung prüft das Modul `fare-payment`.
 */
@RestController
class TariffController(private val tariffs: Tariffs, private val codec: CursorCodec) {

    @GetMapping("/v1/tariffs")
    fun list(@RequestParam(name = "service_id", required = false) serviceId: String?, @RequestParam(required = false) state: String?,
             @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val service = serviceId?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$query/service_id", ViolationCode.INVALID_FORMAT)) }
        val st = state?.let { s -> TariffState.entries.firstOrNull { it.name == s } ?: throw ProblemException.invalid(Violation("/\$query/state", ViolationCode.NOT_IN_ENUM)) }
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "tariffs/" + (service ?: "all") + "/" + (st ?: "all"))
        val lim = Paging.limit(limit)
        val rows = tariffs.list(ctx, service, st, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, Instant.now()).toMap(::json)
    }

    @PostMapping("/v1/tariffs")
    fun create(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<TariffResource> {
        val v = tariffs.create(RequestContexts.require(request), CreateTariffRevision(uuid(body, "service_id"), text(body, "tariff_class"), instant(body, "valid_from"), instant(body, "valid_to"),
            text(body, "currency"), text(body, "reason"), rules(body)))
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/tariffs/${v.id}")).body(TariffResource(json(v), v.revision))
    }

    @GetMapping("/v1/tariffs/{tariff_id}")
    fun get(@PathVariable("tariff_id") id: UUID, request: HttpServletRequest): TariffResource = resource(tariffs.get(RequestContexts.require(request), id))

    @PutMapping("/v1/tariffs/{tariff_id}")
    fun update(@PathVariable("tariff_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): TariffResource {
        val expected = Etag.parseIfMatch(ifMatch)
        return resource(tariffs.update(RequestContexts.require(request), id, expected, UpdateTariffRevision(instant(body, "valid_from"), instant(body, "valid_to"), text(body, "reason"), rules(body))))
    }

    @PostMapping("/v1/tariffs/{tariff_id}/review")
    fun review(@PathVariable("tariff_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): TariffResource =
        resource(tariffs.review(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch)))

    @PostMapping("/v1/tariffs/{tariff_id}/publish")
    fun publish(@PathVariable("tariff_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): TariffResource =
        resource(tariffs.publish(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch)))

    @GetMapping("/v1/services/{service_id}/tariff")
    fun effective(@PathVariable("service_id") service: UUID, @RequestParam(name = "tariff_class", required = false) tariffClass: String?, @RequestParam(required = false) at: String?,
                  request: HttpServletRequest): TariffResource {
        val cls = tariffClass ?: "STANDARD"
        if (!Regex("^[A-Z][A-Z0-9_]{0,39}$").matches(cls)) throw ProblemException.invalid(Violation("/\$query/tariff_class", ViolationCode.PATTERN_MISMATCH))
        val t = GeoWeb.queryInstant(at, "at") ?: Instant.now()
        return resource(tariffs.effective(RequestContexts.require(request), service, cls, t) ?: throw ProblemException(ErrorCode.NOT_FOUND, "Kein Tarif für den Preisstichtag wirksam."))
    }

    // ---- Eingabe ------------------------------------------------------------------------------------------------------------------------

    private fun resource(v: TariffRevisionView) = TariffResource(json(v), v.revision)

    private fun text(body: Map<String, Any?>, f: String): String? = when (val v = body[f]) { null -> null; is String -> v; else -> throw ProblemException.invalid(Violation("/$f", ViolationCode.TYPE_MISMATCH)) }
    private fun uuid(body: Map<String, Any?>, f: String): UUID? = text(body, f)?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw ProblemException.invalid(Violation("/$f", ViolationCode.INVALID_FORMAT)) }
    private fun instant(body: Map<String, Any?>, f: String): Instant? = if (body[f] == null) null else GeoWeb.instant(body[f], "/$f")

    private fun rules(body: Map<String, Any?>): List<TariffRuleInput>? = when (val r = body["rules"]) {
        null -> null
        is List<*> -> r.mapIndexed { i, e ->
            val m = e as? Map<*, *> ?: throw ProblemException.invalid(Violation("/rules/$i", ViolationCode.TYPE_MISMATCH))
            TariffRuleInput(m["rule_type"], m["person_mode"], m["amount_minor"], m["zone_from"], m["zone_to"], m["rate_per_km"], m["booking_fee_minor"], m["tax_rate"], m["tax_mode"], m["tax_rule_revision"])
        }
        else -> throw ProblemException.invalid(Violation("/rules", ViolationCode.TYPE_MISMATCH))
    }

    private fun json(v: TariffRevisionView): Map<String, Any?> = linkedMapOf(
        "id" to v.id.toString(), "service_id" to v.serviceId.toString(), "tariff_class" to v.tariffClass, "revision_no" to v.revisionNo, "state" to v.state.name,
        "valid_from" to v.validFrom.toString(), "valid_to" to v.validTo?.toString(), "currency" to v.currency, "reason" to v.reason, "revision" to v.revision,
        "created_by" to v.createdBy, "created_at" to v.createdAt.toString(), "reviewed_by" to v.reviewedBy, "reviewed_at" to v.reviewedAt?.toString(),
        "published_by" to v.publishedBy, "published_at" to v.publishedAt?.toString(),
        "rules" to v.rules.map { r -> linkedMapOf("id" to r.id.toString(), "rule_no" to r.ruleNo, "rule_type" to r.ruleType, "person_mode" to r.personMode, "amount_minor" to r.amountMinor,
            "zone_from" to r.zoneFrom?.toString(), "zone_to" to r.zoneTo?.toString(), "rate_per_km" to r.ratePerKm, "booking_fee_minor" to r.bookingFeeMinor, "tax_rate" to r.taxRate,
            "tax_mode" to r.taxMode, "tax_rule_revision" to r.taxRuleRevision) })
}
