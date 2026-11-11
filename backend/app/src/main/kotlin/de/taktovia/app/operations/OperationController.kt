package de.taktovia.app.operations

import de.taktovia.app.identity.RequestContexts
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.operation.OperationService
import de.taktovia.platform.operation.OperationView
import de.taktovia.platform.problem.ProblemException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

/** Antwortbaustein `OperationAccepted` (`api/common.yaml`): 202 mit `Location` auf die Operation und `Retry-After`. */
object OperationResponses {
    fun body(v: OperationView): Map<String, Any?> = linkedMapOf(
        "operation_id" to v.id.toString(), "kind" to v.kind, "status" to v.status.name, "journey_id" to v.journeyId?.toString(),
        "created_at" to v.createdAt.toString(), "updated_at" to v.updatedAt.toString(), "result_code" to v.resultCode, "retry_after_seconds" to v.retryAfterSeconds,
    )

    fun accepted(v: OperationView): ResponseEntity<Map<String, Any?>> = ResponseEntity.status(HttpStatus.ACCEPTED)
        .location(URI("/v1/operations/${v.id}")).header("Retry-After", v.retryAfterSeconds.toString()).body(body(v))
}

/**
 * `GET /v1/operations/{operation_id}` (`getOperation`, Scope `operations:read`, WP-M1-15): persistierter Stand einer Operation. Der Zugriff läuft in der
 * Mandantentransaktion des Anfragekontexts unter RLS: eine fremde oder unbekannte Operation ist 404, nie 403. Die Scope-Prüfung (403) erledigt
 * die zentrale Autorisierung vor dem Controller.
 */
@RestController
class OperationController(private val tx: ContextTransactions, private val operations: OperationService) {
    @GetMapping("/v1/operations/{operation_id}")
    fun getOperation(@PathVariable("operation_id") id: UUID, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val view = tx.run(ctx, readOnly = true) { j -> operations.find(j, id) } ?: throw ProblemException.notFound()
        return OperationResponses.body(view)
    }
}
