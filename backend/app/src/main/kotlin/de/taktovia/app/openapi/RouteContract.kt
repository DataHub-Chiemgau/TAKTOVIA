package de.taktovia.app.openapi

import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/** Eine im Spring-Kontext registrierte HTTP-Route. */
data class RegisteredRoute(val method: String, val path: String)

/** Ergebnis des Abgleichs „registrierte Routen ↔ OpenAPI-Operationen“. */
data class RouteVerification(val errors: List<String>, val routed: Map<String, RegisteredRoute>) {
    val ok get() = errors.isEmpty()
}

/**
 * Vergleicht die registrierten Routen mit den OpenAPI-Verträgen (WP-M1-03, LH-6.17-INT-001): jede Route unter `/v1`
 * hat genau eine `operationId`, jede `operationId` höchstens eine Route, und jede dokumentierte, noch nicht gebaute
 * Operation steht ausdrücklich auf der Liste `api/contract-pending.txt`. Wird eine Operation gebaut, muss sie von der Liste
 * verschwinden und einen Vertragsfall erhalten; die Liste kann so nur schrumpfen.
 */
object RouteContract {
    /** Routen außerhalb von `/v1`, die ohne Vertrag erlaubt sind (Betriebsendpunkte). */
    private val NON_API_PREFIXES = listOf("/actuator", "/error")

    fun routes(mapping: RequestMappingHandlerMapping): List<RegisteredRoute> = mapping.handlerMethods.keys.flatMap { info ->
        val methods = info.methodsCondition.methods.map { it.name }.ifEmpty { listOf("ANY") }
        val patterns = info.pathPatternsCondition?.patternValues ?: emptySet()
        patterns.flatMap { p -> methods.map { RegisteredRoute(it, p) } }
    }.sortedWith(compareBy({ it.path }, { it.method }))

    fun verify(routes: List<RegisteredRoute>, registry: OpenApiRegistry, pending: Set<String>): RouteVerification {
        val errors = mutableListOf<String>()
        val routed = LinkedHashMap<String, RegisteredRoute>()
        for (r in routes) {
            if (NON_API_PREFIXES.any { r.path.startsWith(it) }) continue
            if (!r.path.startsWith("/v1/")) { errors += "Route ${r.method} ${r.path}: außerhalb von /v1 und nicht als Betriebsendpunkt erlaubt"; continue }
            if (r.method == "ANY") { errors += "Route ${r.path}: ohne HTTP-Methode registriert, kein eindeutiger Vertrag möglich"; continue }
            val template = normalize(r.path)
            val ops = registry.operations.filter { it.method == r.method && normalize(it.path) == template }
            when (ops.size) {
                0 -> errors += "Route ${r.method} ${r.path}: keine operationId im Vertrag (api/<modul>.yaml)"
                1 -> { val id = ops[0].operationId; routed[id]?.let { errors += "operationId $id doppelt geroutet: ${it.path} und ${r.path}" }; routed[id] = r }
                else -> errors += "Route ${r.method} ${r.path}: mehrere operationIds ${ops.map { it.operationId }}"
            }
        }
        val documented = registry.operations.map { it.operationId }.toSet()
        for (id in pending - documented) errors += "contract-pending.txt nennt unbekannte operationId $id"
        for (id in routed.keys.filter { it in pending }) errors += "operationId $id ist gebaut, steht aber noch in contract-pending.txt (entfernen und Vertragsfall ergänzen)"
        for (id in documented - routed.keys - pending) errors += "operationId $id ist dokumentiert, hat keine Route und steht nicht in contract-pending.txt"
        return RouteVerification(errors, routed)
    }

    /** `{beliebiger_name}` → `{}`, damit unterschiedlich benannte Pfadvariablen denselben Pfad meinen. */
    fun normalize(path: String) = path.replace(Regex("\\{[^/}]+}"), "{}")
}
