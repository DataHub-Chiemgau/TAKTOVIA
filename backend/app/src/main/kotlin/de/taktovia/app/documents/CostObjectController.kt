package de.taktovia.app.documents

import de.taktovia.app.identity.RequestContexts
import de.taktovia.documentsfinance.api.CostAssignments
import de.taktovia.documentsfinance.api.CostObjectKind
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

/**
 * Zuordenbare Kostenobjekte (WP-M4-29, DOC-004; Modul `documents-finance`): Kostenstellen und Fahrzeug-Kostenobjekte des Mandanten, soweit der Benutzer sie sehen und zuordnen darf
 * (Objektberechtigung). Sortierung nach Kennung, signierte Cursor.
 */
@RestController
class CostObjectController(private val costs: CostAssignments, private val codec: CursorCodec) {

    @GetMapping("/v1/cost-objects")
    fun list(@RequestParam(required = false) kind: String?, @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val k = kind?.let { v -> CostObjectKind.entries.firstOrNull { it.name == v } ?: throw ProblemException.invalid(Violation("/\$query/kind", ViolationCode.NOT_IN_ENUM)) }
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "cost-objects:${k?.name ?: "all"}")
        val lim = Paging.limit(limit)
        val rows = costs.costObjects(ctx, k, lim + 1, Paging.position(cursor, codec, scope)?.id)
        return Paging.page(rows, lim, { CursorPosition(Instant.EPOCH, it.id) }, codec, scope, Instant.now()).toMap(CostObjectJson::of)
    }
}
