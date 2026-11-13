package de.taktovia.bookingdispatch.internal

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Reduzierter Ressourcenvektor bei eingeschränkter Freigabe (WP-M3-12, LH-6.21-CHK-007, Semantik OP-17 als Annahme): entfällt die Dimension `wheelchair`, hat das Fahrzeug für die Disposition keine
 * Rollstuhlplätze und keine Rampe/Lift (Ausstattung `RAMP`, `LIFT`); Sitze und übrige Ausstattung bleiben. Rein und ohne Datenbankzugriff; die Eingabe ist der Text `vehicle.capacity_config`.
 */
internal object ResourceRestriction {
    private val json = JsonMapper.builder().build()

    /** Liefert den (ggf. reduzierten) Vektor als JSON-Text und die entfallenen Dimensionen; ohne Einschränkung bleibt der Text unverändert. */
    fun apply(capacityConfig: String, restriction: String?): Pair<String, Set<String>> {
        if (restriction == null) return capacityConfig to emptySet()
        val dims = json.readTree(restriction).path("unavailable_dimensions").values().map { it.asString() }.toSet()
        if ("wheelchair" !in dims) return capacityConfig to dims
        val node: JsonNode = json.readTree(capacityConfig)
        if (node !is ObjectNode) return capacityConfig to dims
        node.put("wheelchair", 0)
        val equipment = node.get("equipment")
        if (equipment != null && equipment.isArray) {
            val kept = json.createArrayNode()
            equipment.values().filter { it.asString() !in WHEELCHAIR_EQUIPMENT }.forEach { kept.add(it) }
            node.set("equipment", kept)
        }
        return json.writeValueAsString(node) to dims
    }

    /** Ausstattung, die ohne die Dimension Rollstuhl entfällt. */
    val WHEELCHAIR_EQUIPMENT = setOf("RAMP", "LIFT")
}
