package de.taktovia.bookingdispatch.api

import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Ausstattungsmerkmale (Schema `resource-vector.v1`; Vorschlag dieses Plans, M2 OP-15). Transportrelevant, nie eine Diagnose. */
enum class EquipmentCode { RAMP, LIFT, CHILD_SEAT, BOOSTER_SEAT, LUGGAGE_SPACE, ASSISTANCE_DOG_SPACE, STRETCHER_FIXING }

/** Katalog des Unterstützungsbedarfs (Feldschutz WP-M1-31): Auswahl aus festen Codes, kein Freitext, keine Diagnose. */
enum class AssistanceCode { BOARDING_HELP, AUDIO_GUIDANCE, LOW_FLOOR, WHEELCHAIR_SPACE, ASSISTANCE_ANIMAL, ACCOMPANYING_PERSON }

enum class Dimension { SEATS, WHEELCHAIR, EQUIPMENT }

/**
 * Ressourcenvektor (WP-M2-14, Schema `docs/umsetzungsplan/vertraege/schemas/resource-vector.v1.schema.json`): getrennte Dimensionen Sitz, Rollstuhlplatz und Ausstattung.
 * Als Angebot eines Fahrzeugs ist er die Kapazität, als Bedarf einer Buchung der Anspruch. **Keine Dimension ersetzt eine andere:** [covers] prüft jede einzeln, ein freier Sitz
 * deckt nie einen Rollstuhlplatz (BKG-004, Variante aus PH-AT-004). Umrüstzustände (Kap. 01 §1.4 Schritt 2) sind in P1 nicht modelliert (OP-15, Arbeitsannahme).
 */
data class ResourceVector(val seats: Int = 0, val wheelchair: Int = 0, val equipment: Set<EquipmentCode> = emptySet()) {
    init { require(seats >= 0 && wheelchair >= 0) { "Ressourcenvektor: Zähler dürfen nicht negativ sein" } }

    /** Komponentenweise Addition (Zähler addieren, Ausstattung vereinen). */
    operator fun plus(o: ResourceVector) = ResourceVector(Math.addExact(seats, o.seats), Math.addExact(wheelchair, o.wheelchair), equipment + o.equipment)

    /** Deckt dieser Vektor (als Kapazität) den [demand]? Jede Dimension einzeln. */
    fun covers(demand: ResourceVector): Boolean = missing(demand).isEmpty()

    /** Dimensionen, in denen dieser Vektor den [demand] nicht deckt. */
    fun missing(demand: ResourceVector): Set<Dimension> = buildSet {
        if (seats < demand.seats) add(Dimension.SEATS)
        if (wheelchair < demand.wheelchair) add(Dimension.WHEELCHAIR)
        if (!equipment.containsAll(demand.equipment)) add(Dimension.EQUIPMENT)
    }

    /** Frei verbleibende Kapazität nach Abzug der Belegung und der geschützten Reserve (je Dimension, nie unter null; Ausstattung ist keine verbrauchbare Größe). */
    fun freeAfter(occupied: ResourceVector, reserve: ReserveRule = ReserveRule.NONE) =
        ResourceVector(maxOf(0, seats - occupied.seats - reserve.seats), maxOf(0, wheelchair - occupied.wheelchair - reserve.wheelchair), equipment)

    fun toJson(): String = MAPPER.writeValueAsString(toMap())
    fun toMap(): Map<String, Any?> = linkedMapOf("schema_version" to SCHEMA_VERSION, "seats" to seats, "wheelchair" to wheelchair, "equipment" to equipment.map { it.name }.sorted())

    companion object {
        const val SCHEMA_VERSION = "1.0"
        const val MAX_SEATS = 99
        const val MAX_WHEELCHAIR = 20
        private val MAPPER: JsonMapper = JsonMapper.builder().build()
        val ZERO = ResourceVector()

        /** Strikte Prüfung gegen das Schema: unbekannte Dimension, falscher Typ, Bereichsverletzung oder unbekanntes Merkmal ergeben 422 mit Pointer unter [pointer]. */
        fun parse(json: String, pointer: String = "/capacity_vector"): ResourceVector {
            val node = try { MAPPER.readTree(json) } catch (e: Exception) { throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_FORMAT)) }
            return parse(node, pointer)
        }

        fun parse(node: JsonNode, pointer: String = "/capacity_vector"): ResourceVector {
            if (!node.isObject) throw ProblemException.invalid(Violation(pointer, ViolationCode.TYPE_MISMATCH))
            val v = mutableListOf<Violation>()
            node.propertyNames().filter { it !in setOf("schema_version", "seats", "wheelchair", "equipment") }.forEach { v += Violation("$pointer/$it", ViolationCode.UNKNOWN_FIELD) }
            node.get("schema_version")?.let { if (!it.isString || it.asString() != SCHEMA_VERSION) v += Violation("$pointer/schema_version", ViolationCode.NOT_IN_ENUM) }
            fun count(name: String, max: Int): Int {
                val n = node.get(name) ?: return 0
                return when {
                    !n.isIntegralNumber -> { v += Violation("$pointer/$name", ViolationCode.TYPE_MISMATCH); 0 }
                    n.asLong() < 0 -> { v += Violation("$pointer/$name", ViolationCode.BELOW_MINIMUM); 0 }
                    n.asLong() > max -> { v += Violation("$pointer/$name", ViolationCode.ABOVE_MAXIMUM); 0 }
                    else -> n.asInt()
                }
            }
            val seats = count("seats", MAX_SEATS); val wheelchair = count("wheelchair", MAX_WHEELCHAIR)
            val equipment = mutableSetOf<EquipmentCode>()
            node.get("equipment")?.let { e ->
                if (!e.isArray) v += Violation("$pointer/equipment", ViolationCode.TYPE_MISMATCH)
                else for (i in 0 until e.size()) {
                    val code = e.get(i).let { if (it.isString) EquipmentCode.entries.firstOrNull { c -> c.name == it.asString() } else null }
                    if (code == null) v += Violation("$pointer/equipment/$i", ViolationCode.NOT_IN_ENUM) else if (!equipment.add(code)) v += Violation("$pointer/equipment/$i", ViolationCode.NOT_UNIQUE)
                }
            }
            if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray())
            return ResourceVector(seats, wheelchair, equipment)
        }
    }
}

/**
 * Regel für `min_capacity_reserve` je Dimension (WP-M2-14): geschützte Reserve, die für Buchungen unberührt bleibt. Der Katalogparameter ist eine Platzzahl der Dimension Sitz
 * (`x-unit SEATS`); für den Rollstuhlplatz gilt dieselbe Regel als eigener Wert. Die Reserve darf nie größer sein als die tatsächlich verfügbare Kapazität der Dimension
 * ([validateAgainst]); Ausstattung hat keine Reserve.
 */
data class ReserveRule(val seats: Int = 0, val wheelchair: Int = 0) {
    init { require(seats >= 0 && wheelchair >= 0) }

    fun validateAgainst(capacity: ResourceVector) {
        val v = mutableListOf<Violation>()
        if (seats > capacity.seats) v += Violation("/min_capacity_reserve/seats", ViolationCode.ABOVE_MAXIMUM)
        if (wheelchair > capacity.wheelchair) v += Violation("/min_capacity_reserve/wheelchair", ViolationCode.ABOVE_MAXIMUM)
        if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray())
    }

    companion object { val NONE = ReserveRule() }
}

/**
 * Höchstwerte je Buchung (Dienstparameter; im Pflichtenheft nicht festgelegt, OP-15): Testwerte aus der Dienstparameter-Fixture, bis der Auftraggeber sie nennt.
 */
data class BookingLimits(val maxPersons: Int = 8, val maxWheelchair: Int = 2)

/**
 * Bedarf einer Buchung (WP-M2-14): Personenzahl, Rollstuhlplätze, Ausstattung und Unterstützungsbedarf. Personen schließen die Rollstuhlplätze ein
 * (`wheelchair_spaces <= passengers`, wie `Party` in der Föderation); Sitzbedarf = Personen minus Rollstuhlplätze. Der Unterstützungsbedarf besteht nur aus Katalogcodes
 * (keine Diagnose, kein Freitext); `ASSISTANCE_ANIMAL` verlangt zusätzlich das Ausstattungsmerkmal `ASSISTANCE_DOG_SPACE`.
 */
data class BookingDemand(val passengers: Int, val vector: ResourceVector, val assistance: Set<AssistanceCode>) {
    companion object {
        fun of(passengers: Int, wheelchairSpaces: Int, equipment: Set<EquipmentCode>, assistanceCodes: List<String>, limits: BookingLimits = BookingLimits()): BookingDemand {
            val v = mutableListOf<Violation>()
            if (passengers < 1) v += Violation("/passengers", ViolationCode.BELOW_MINIMUM) else if (passengers > limits.maxPersons) v += Violation("/passengers", ViolationCode.ABOVE_MAXIMUM)
            if (wheelchairSpaces < 0) v += Violation("/wheelchair_spaces", ViolationCode.BELOW_MINIMUM)
            else if (wheelchairSpaces > limits.maxWheelchair || wheelchairSpaces > passengers) v += Violation("/wheelchair_spaces", ViolationCode.ABOVE_MAXIMUM)
            val codes = linkedSetOf<AssistanceCode>()
            assistanceCodes.forEachIndexed { i, c ->
                val code = AssistanceCode.entries.firstOrNull { it.name == c }
                if (code == null) v += Violation("/assistance_codes/$i", ViolationCode.NOT_IN_ENUM) else if (!codes.add(code)) v += Violation("/assistance_codes/$i", ViolationCode.NOT_UNIQUE)
            }
            if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray())
            val needed = equipment + (if (AssistanceCode.ASSISTANCE_ANIMAL in codes) setOf(EquipmentCode.ASSISTANCE_DOG_SPACE) else emptySet())
            return BookingDemand(passengers, ResourceVector(passengers - wheelchairSpaces, wheelchairSpaces, needed), codes)
        }
    }
}
