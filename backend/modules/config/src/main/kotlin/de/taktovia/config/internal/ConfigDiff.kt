package de.taktovia.config.internal

import de.taktovia.config.api.ChangeKind
import de.taktovia.config.api.ConfigChange
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Altwert/Neuwert gegen die Vorgängerrevision (LH-I.7-CFG-003). Der Diff wird berechnet, nie gespeichert. Objekte werden bis zu den Blättern
 * aufgelöst (JSON-Pointer, z. B. `/service_config/traffic_type`); Arrays und skalare Werte vergleicht er als Ganzes.
 */
internal object ConfigDiff {
    private val mapper = JsonMapper.builder().build()

    fun between(old: JsonNode?, new: JsonNode): List<ConfigChange> {
        val o = LinkedHashMap<String, JsonNode>().also { if (old != null) flatten(it, "", old) }
        val n = LinkedHashMap<String, JsonNode>().also { flatten(it, "", new) }
        return (o.keys + n.keys).toSortedSet().mapNotNull { p ->
            val a = o[p]; val b = n[p]
            when {
                a == null -> ConfigChange(p, ChangeKind.ADDED, null, plain(b))
                b == null -> ConfigChange(p, ChangeKind.REMOVED, plain(a), null)
                canonical(a) != canonical(b) -> ConfigChange(p, ChangeKind.CHANGED, plain(a), plain(b))
                else -> null
            }
        }
    }

    private fun flatten(into: MutableMap<String, JsonNode>, prefix: String, node: JsonNode) {
        if (node.isObject && node.size() > 0) node.properties().forEach { (k, v) -> flatten(into, prefix + "/" + k.replace("~", "~0").replace("/", "~1"), v) }
        else if (prefix.isNotEmpty()) into[prefix] = node
    }

    /** Zahlen 5 und 5.0 sind derselbe Wert; Reihenfolge der Objektschlüssel ist ohne Bedeutung. */
    private fun canonical(n: JsonNode): String = if (n.isNumber) n.decimalValue().stripTrailingZeros().toPlainString() else mapper.writeValueAsString(n)

    private fun plain(n: JsonNode?): Any? = n?.let { mapper.treeToValue(it, Any::class.java) }
}
