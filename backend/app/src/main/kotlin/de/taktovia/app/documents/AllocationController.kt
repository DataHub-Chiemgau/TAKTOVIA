package de.taktovia.app.documents

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.documentsfinance.api.AllocateCost
import de.taktovia.documentsfinance.api.AllocationLineInput
import de.taktovia.documentsfinance.api.AllocationView
import de.taktovia.documentsfinance.api.CostAllocations
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

class AllocationResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/** Lesen der Aufteilungszeilen aus dem JSON-Body, gemeinsam für `allocateCost` und den Beleg-Split (WP-M4-35). Dezimalwerte nur als Strings, Beträge nur als ganze Zahlen (nie Gleitkomma). */
internal object AllocationJson {
    private val PERCENT = Regex("(100(\\.0{1,8})?|[0-9]{1,2}(\\.[0-9]{1,8})?)")
    private val QUANTITY = Regex("(0|[1-9][0-9]{0,11})(\\.[0-9]{1,6})?")

    @Suppress("UNCHECKED_CAST")
    fun lines(raw: Any?, pointer: String): List<AllocationLineInput> {
        val list = raw as? List<*> ?: throw ProblemException.invalid(Violation(pointer, if (raw == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH))
        return list.mapIndexed { i, e ->
            val m = e as? Map<String, Any?> ?: throw ProblemException.invalid(Violation("$pointer/$i", ViolationCode.TYPE_MISMATCH))
            val p = "$pointer/$i"
            if (m.keys.any { it !in KEYS }) throw ProblemException.invalid(Violation(p, ViolationCode.UNKNOWN_FIELD))
            val id = uuid(m["cost_object_id"], "$p/cost_object_id")
            val amount = m["amount"] as Map<String, Any?>?
            val minor = amount?.let { a ->
                if (a.keys.any { it != "amount_minor" && it != "currency" && it != "exponent" }) throw ProblemException.invalid(Violation("$p/amount", ViolationCode.UNKNOWN_FIELD))
                when (val v = a["amount_minor"]) { is Int -> v.toLong(); is Long -> v; null -> throw ProblemException.invalid(Violation("$p/amount/amount_minor", ViolationCode.REQUIRED)); else -> throw ProblemException.invalid(Violation("$p/amount/amount_minor", ViolationCode.MONEY_NOT_INTEGER)) }
            }
            AllocationLineInput(
                id, m["cost_object_type"] as String?, minor, amount?.get("currency") as String?, (amount?.get("exponent") as Number?)?.toInt(),
                (m["percent"] as Any?)?.let { v -> decimal(v, PERCENT, "$p/percent") }, (m["quantity"] as Any?)?.let { v -> decimal(v, QUANTITY, "$p/quantity") }, m["unit"] as String?,
            ).also { l -> (m["cost_center_id"] as String?)?.let { c -> if (uuid(c, "$p/cost_center_id") != id && l.costObjectType == "COST_CENTER") throw ProblemException.invalid(Violation("$p/cost_center_id", ViolationCode.INVALID_VALUE)) } }
        }
    }

    /** `AllocationResult` (Antwort von `allocateCost` und Teil des Gegenereignisses). */
    fun result(v: AllocationView): Map<String, Any?> {
        fun money(minor: Long) = linkedMapOf("amount_minor" to minor, "currency" to v.currency, "exponent" to v.exponent)
        return linkedMapOf(
            "allocation_id" to v.id.toString(), "cost_event_id" to v.eventId.toString(), "revision" to v.revision, "state" to v.state, "basis" to money(v.basisMinor),
            "lines" to v.lines.map { l ->
                linkedMapOf("cost_center_id" to (if (l.costObjectKind == "COST_CENTER") l.costObjectId.toString() else null), "cost_object_type" to l.costObjectKind, "cost_object_id" to l.costObjectId.toString(),
                    "amount" to money(l.amountMinor), "percent" to l.percent.toPlainString(), "quantity" to l.quantity?.toPlainString(), "unit" to l.unit)
            },
        )
    }

    private val KEYS = setOf("cost_center_id", "cost_object_type", "cost_object_id", "amount", "percent", "quantity", "unit")

    private fun uuid(v: Any?, pointer: String): UUID = try { UUID.fromString(v as? String ?: throw ProblemException.invalid(Violation(pointer, if (v == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH))) }
        catch (e: IllegalArgumentException) { throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_FORMAT)) }

    private fun decimal(v: Any, regex: Regex, pointer: String): BigDecimal {
        if (v !is String) throw ProblemException.invalid(Violation(pointer, ViolationCode.TYPE_MISMATCH))
        if (!regex.matches(v)) throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_FORMAT))
        return BigDecimal(v)
    }
}

/**
 * Aufteilung eines gebuchten Kostenereignisses auf Kostenobjekte (WP-M4-35, DOC-011; Modul `documents-finance`). Recht (`costs:allocate`) aus der Rechtematrix im Filter, Finanzrolle im Dienst; `If-Match` = aktive
 * Aufteilungsrevision (`"0"` = noch nicht aufgeteilt); `Idempotency-Key` übernimmt der HTTP-Filter (gleicher Schlüssel und Inhalt liefert die gespeicherte Antwort, keine zweite Revision).
 */
@RestController
class AllocationController(private val allocations: CostAllocations) {

    @PostMapping("/v1/cost-events/{cost_event_id}/allocations")
    fun allocate(@PathVariable("cost_event_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<AllocationResource> {
        val revision = Etag.parseIfMatch(ifMatch)
        val policy = body["allocation_policy_revision"] as? String ?: throw ProblemException.invalid(Violation("/allocation_policy_revision", ViolationCode.REQUIRED))
        val reason = body["reason"] as? String ?: throw ProblemException.invalid(Violation("/reason", ViolationCode.REQUIRED))
        val v = allocations.allocate(RequestContexts.require(request), id, revision, AllocateCost(policy, reason, AllocationJson.lines(body["lines"], "/lines")))
        return ResponseEntity.status(HttpStatus.CREATED).body(AllocationResource(json(v), v.revision))
    }

    private fun json(v: AllocationView): Map<String, Any?> = AllocationJson.result(v)
}
