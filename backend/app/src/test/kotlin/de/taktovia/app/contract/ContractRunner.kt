package de.taktovia.app.contract

import de.taktovia.app.openapi.OpenApiRegistry
import de.taktovia.app.probe.ProbeServer
import de.taktovia.app.probe.Resp

/** Ein Aufruf eines Vertragsfalls. */
class ContractRequest(val method: String, val path: String, val body: String? = null, val headers: Map<String, String> = emptyMap(), val contentType: String? = "application/json")

/**
 * Vertragsfall einer Operation: mindestens ein positiver Fall und ein Fehlerfall (LH-6.17-INT-001 AK 2). Die Requests entstehen
 * erst beim Lauf (Datenvoraussetzungen werden dann angelegt). `expected…` sind die erwarteten HTTP-Status.
 */
class ContractCase(
    val operationId: String,
    val expectedPositive: Int, val positive: () -> ContractRequest,
    val expectedError: Int, val error: () -> ContractRequest,
)

/**
 * Contract-Test-Runner: sendet die Fälle gegen die laufende Anwendung und prüft Status und Body gegen die OpenAPI-Datei:
 * der Status ist für die Operation dokumentiert, der Body validiert gegen das Antwortschema (`additionalProperties:false`
 * erkennt Felder, die der Vertrag nicht kennt), Fehlerantworten gegen `Problem`.
 */
class ContractRunner(private val server: ProbeServer, private val registry: OpenApiRegistry) {
    /** Befunde; leer = der Fall ist vertragskonform. */
    fun run(case: ContractCase): List<String> {
        val op = registry.operations.singleOrNull { it.operationId == case.operationId } ?: return listOf("${case.operationId}: operationId nicht im Vertrag")
        return check(case, op, "positiv", case.positive(), case.expectedPositive) + check(case, op, "Fehlerfall", case.error(), case.expectedError)
    }

    private fun check(case: ContractCase, op: de.taktovia.app.openapi.ApiOperation, kind: String, req: ContractRequest, expected: Int): List<String> {
        val who = "${case.operationId} ($kind)"
        val r: Resp = server.send(req.method, req.path, req.body, req.headers, req.contentType)
        val out = mutableListOf<String>()
        if (r.status != expected) out += "$who: erwartet HTTP $expected, erhalten ${r.status}: ${r.text.take(200)}"
        if (r.status.toString() !in op.responseStatuses) out += "$who: HTTP ${r.status} ist im Vertrag nicht dokumentiert (dokumentiert: ${op.responseStatuses.sorted()})"
        if (kind == "Fehlerfall" && r.status >= 400 && r.header("Content-Type")?.startsWith("application/problem+json") != true)
            out += "$who: Fehlerantwort ist nicht application/problem+json"
        val schema = registry.responseSchema(op, r.status.toString())
        if (schema != null && r.text.isNotEmpty()) {
            registry.schemaErrors(schema, r.json).forEach { out += "$who: Antwort verletzt das Vertragsschema: $it" }
        }
        return out
    }
}
