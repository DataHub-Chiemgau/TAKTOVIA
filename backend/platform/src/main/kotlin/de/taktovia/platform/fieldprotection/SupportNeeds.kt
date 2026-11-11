package de.taktovia.platform.fieldprotection

import tools.jackson.databind.json.JsonMapper

/**
 * Transportrelevanter Unterstützungsbedarf **ohne Diagnose** (Kap. 02 §3 „erforderliche Unterstützung ohne Diagnose“, SEC-005). Es gibt
 * bewusst nur eine Auswahl aus einem festen Katalog und keinen Freitext: Diagnosen oder Gesundheitsangaben lassen sich nicht ablegen.
 * Der Katalog ist vorläufig (OP-24); die ersten drei Codes entsprechen `assistance_codes` in `api/federation.yaml`.
 */
class SupportNeeds private constructor(val codes: Set<String>) {
    init { require(codes.size <= MAX) { "zu viele Angaben" } }
    override fun equals(other: Any?) = other is SupportNeeds && other.codes == codes
    override fun hashCode() = codes.hashCode()
    /** Keine Werte im Text: Unterstützungsbedarf gehört nicht in Logs. */
    override fun toString() = "SupportNeeds(n=${codes.size})"

    fun toBytes(): ByteArray = mapper.writeValueAsBytes(mapOf("v" to 1, "codes" to codes.sorted()))

    companion object {
        val CATALOG: Set<String> = linkedSetOf("BOARDING_HELP", "AUDIO_GUIDANCE", "LOW_FLOOR", "WHEELCHAIR_SPACE", "ASSISTANCE_ANIMAL", "ACCOMPANYING_PERSON")
        const val MAX = 16
        private val mapper: JsonMapper = JsonMapper.builder().build()

        /** Nur Katalogcodes; jeder andere Wert (insbesondere Diagnosen oder Freitext) wird abgewiesen, ohne ihn zu wiederholen. */
        fun of(codes: Collection<String>): SupportNeeds {
            val unknown = codes.count { it !in CATALOG }
            require(unknown == 0) { "$unknown Angabe(n) nicht im Katalog der transportrelevanten Unterstützung (keine Diagnosen, kein Freitext)" }
            return SupportNeeds(codes.toSet())
        }

        fun fromBytes(bytes: ByteArray): SupportNeeds {
            val node = mapper.readTree(bytes)
            return of(node.get("codes").values().map { it.asString() })
        }
    }
}
