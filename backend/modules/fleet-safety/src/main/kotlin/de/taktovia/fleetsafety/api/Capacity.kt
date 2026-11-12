package de.taktovia.fleetsafety.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import java.util.UUID

/**
 * Ressourcenvektor einer Fahrzeugkonfiguration (Form von `resource-vector.v1`, WP-M2-14): Sitzplätze, Rollstuhlplätze, Ausstattung. Keine Dimension ersetzt eine andere;
 * die Umrechnung „ein Rollstuhlplatz ersetzt n Sitzplätze“ steckt in der Konfiguration, nicht im Vektor.
 */
data class ConfiguredVector(val code: String, val seats: Int, val wheelchair: Int, val equipment: List<String>) {
    /** Stabile JSON-Form (`resource-vector.v1`), wie sie in `vehicle.capacity_config` steht und von `booking-dispatch` gelesen wird. */
    fun toJson(): String = "{\"schema_version\":\"1.0\",\"seats\":$seats,\"wheelchair\":$wheelchair,\"equipment\":[" + equipment.sorted().joinToString(",") { "\"$it\"" } + "]}"
}

/** Eine Umrüstzustand-Kombination: [wheelchairPlaces] belegte Stellplätze für Rollstuhlnutzer, die Sitzplätze folgen aus der Ersatzregel der Stellplätze. */
data class CapacityCombination(val code: String, val wheelchairPlaces: Int)

/** Restriktion: [MAX_TOTAL_PERSONS] (Sitze plus Rollstuhlplätze höchstens so viele Personen) oder [MAX_WHEELCHAIR_SPACES] (höchstens so viele Rollstuhlplätze gleichzeitig). */
data class CapacityRestriction(val code: String, val value: Int) {
    companion object { const val MAX_TOTAL_PERSONS = "MAX_TOTAL_PERSONS"; const val MAX_WHEELCHAIR_SPACES = "MAX_WHEELCHAIR_SPACES"; val CODES = setOf(MAX_TOTAL_PERSONS, MAX_WHEELCHAIR_SPACES) }
}

/** Stellplatzteil: Sitzplätze, barrierefrei nutzbare Stellplätze und der Wert n, wie viele Sitzplätze ein belegter Rollstuhlplatz ersetzt (Konfigurationsdatum des Fahrzeugs). */
data class CapacitySpaces(val seatPlaces: Int, val wheelchairSpaces: Int, val seatsReplacedPerWheelchair: Int)

/**
 * Kapazitätskonfiguration eines Fahrzeugs (WP-M3-04, Schema `capacity_config.v1`, LH-6.8-FLT-004). Getrennte, unabhängig änderbare Teile: Stellplätze ([spaces]), Ausstattung,
 * Kombinationen (Umrüstzustände, [combinations] mit der gerade gültigen [activeCombination]) und Restriktionen. [validate] weist Widersprüche ab (mehr belegte Fläche als vorhanden).
 */
data class CapacityLayout(
    val spaces: CapacitySpaces, val equipment: Set<String>, val combinations: List<CapacityCombination>, val activeCombination: String, val restrictions: List<CapacityRestriction>,
) {
    /** Vektor je Kombination: Rollstuhlplätze = Plätze der Kombination, Sitzplätze = Sitzplätze minus Plätze mal n (die Belegung eines Rollstuhlplatzes reduziert die Sitzkapazität um genau n). */
    fun vectors(): List<ConfiguredVector> = combinations.map { vector(it) }
    fun vector(c: CapacityCombination) = ConfiguredVector(c.code, spaces.seatPlaces - c.wheelchairPlaces * spaces.seatsReplacedPerWheelchair, c.wheelchairPlaces, equipment.sorted())
    fun effective(): ConfiguredVector = vector(combinations.first { it.code == activeCombination })

    /** Verfügbare Sitzkapazität und Rollstuhlplätze, wenn [occupiedWheelchair] Rollstuhlplätze belegt sind (Basis: volle Sitzzahl, alle Stellplätze frei). */
    fun availableAfterOccupying(occupiedWheelchair: Int): ConfiguredVector {
        require(occupiedWheelchair in 0..spaces.wheelchairSpaces)
        return ConfiguredVector("OCCUPIED_$occupiedWheelchair", spaces.seatPlaces - occupiedWheelchair * spaces.seatsReplacedPerWheelchair, spaces.wheelchairSpaces - occupiedWheelchair, equipment.sorted())
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "schema_version" to VERSION,
        "spaces" to linkedMapOf("seat_places" to spaces.seatPlaces, "wheelchair_spaces" to spaces.wheelchairSpaces, "seats_replaced_per_wheelchair" to spaces.seatsReplacedPerWheelchair),
        "equipment" to equipment.sorted(),
        "combinations" to combinations.map { linkedMapOf("code" to it.code, "wheelchair_places" to it.wheelchairPlaces) },
        "active_combination" to activeCombination,
        "restrictions" to restrictions.map { linkedMapOf("code" to it.code, "value" to it.value) },
    )

    companion object {
        const val VERSION = "1.0"
        val CODE = Regex("^[A-Z][A-Z0-9_]{1,29}$")

        /** Liest und prüft ein vollständiges Dokument (gespeicherter Stand). */
        fun parse(raw: Map<String, Any?>): CapacityLayout {
            val v = Violations()
            v.unknown(raw, setOf("schema_version", "spaces", "equipment", "combinations", "active_combination", "restrictions"), "")
            if (raw["schema_version"] != null && raw["schema_version"] != VERSION) v.add("/schema_version", ViolationCode.NOT_IN_ENUM)
            val spaces = spaces(raw["spaces"], v)
            val equipment = v.equipment(raw, "equipment", "/equipment")
            val combos = combinations(raw["combinations"], v)
            val active = (raw["active_combination"] as? String) ?: run { v.add("/active_combination", if (raw["active_combination"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH); null }
            val restrictions = restrictions(raw["restrictions"], v)
            v.throwIfAny()
            return of(spaces!!, equipment!!, combos!!, active!!, restrictions!!)
        }

        internal fun spaces(raw: Any?, v: Violations): CapacitySpaces? {
            if (raw == null) { v.add("/spaces", ViolationCode.REQUIRED); return null }
            if (raw !is Map<*, *>) { v.add("/spaces", ViolationCode.TYPE_MISMATCH); return null }
            v.unknown(raw, setOf("seat_places", "wheelchair_spaces", "seats_replaced_per_wheelchair"), "/spaces")
            val s = v.int(raw, "seat_places", "/spaces/seat_places", 0, VehicleCatalog.MAX_SEATS)
            val w = v.int(raw, "wheelchair_spaces", "/spaces/wheelchair_spaces", 0, VehicleCatalog.MAX_WHEELCHAIR)
            val n = v.int(raw, "seats_replaced_per_wheelchair", "/spaces/seats_replaced_per_wheelchair", 0, VehicleCatalog.MAX_SEATS)
            return if (s == null || w == null || n == null) null else CapacitySpaces(s, w, n)
        }

        internal fun combinations(raw: Any?, v: Violations): List<CapacityCombination>? {
            if (raw == null) { v.add("/combinations", ViolationCode.REQUIRED); return null }
            val l = raw as? List<*> ?: run { v.add("/combinations", ViolationCode.TYPE_MISMATCH); return null }
            if (l.isEmpty()) { v.add("/combinations", ViolationCode.TOO_FEW_ITEMS); return null }
            if (l.size > 20) { v.add("/combinations", ViolationCode.TOO_MANY_ITEMS); return null }
            val out = mutableListOf<CapacityCombination>()
            l.forEachIndexed { i, e ->
                if (e !is Map<*, *>) { v.add("/combinations/$i", ViolationCode.TYPE_MISMATCH); return@forEachIndexed }
                v.unknown(e, setOf("code", "wheelchair_places"), "/combinations/$i")
                val code = (e["code"] as? String)?.takeIf { CODE.matches(it) } ?: run { v.add("/combinations/$i/code", if (e["code"] == null) ViolationCode.REQUIRED else ViolationCode.PATTERN_MISMATCH); null }
                val k = v.int(e, "wheelchair_places", "/combinations/$i/wheelchair_places", 0, VehicleCatalog.MAX_WHEELCHAIR)
                if (code != null && k != null) out += CapacityCombination(code, k)
            }
            return out
        }

        internal fun restrictions(raw: Any?, v: Violations): List<CapacityRestriction>? {
            if (raw == null) { v.add("/restrictions", ViolationCode.REQUIRED); return null }
            val l = raw as? List<*> ?: run { v.add("/restrictions", ViolationCode.TYPE_MISMATCH); return null }
            if (l.size > 10) { v.add("/restrictions", ViolationCode.TOO_MANY_ITEMS); return null }
            val out = mutableListOf<CapacityRestriction>()
            l.forEachIndexed { i, e ->
                if (e !is Map<*, *>) { v.add("/restrictions/$i", ViolationCode.TYPE_MISMATCH); return@forEachIndexed }
                v.unknown(e, setOf("code", "value"), "/restrictions/$i")
                val code = (e["code"] as? String)?.takeIf { it in CapacityRestriction.CODES } ?: run { v.add("/restrictions/$i/code", if (e["code"] == null) ViolationCode.REQUIRED else ViolationCode.NOT_IN_ENUM); null }
                val value = v.int(e, "value", "/restrictions/$i/value", 0, VehicleCatalog.MAX_SEATS + VehicleCatalog.MAX_WHEELCHAIR)
                if (code != null && value != null) out += CapacityRestriction(code, value)
            }
            return out
        }

        /** Baut die Konfiguration und prüft die Widerspruchsfreiheit; Verstöße sind 422 mit Feldverweis. */
        fun of(spaces: CapacitySpaces, equipment: Set<String>, combinations: List<CapacityCombination>, active: String, restrictions: List<CapacityRestriction>): CapacityLayout {
            val v = Violations()
            // mehr belegte Fläche als vorhanden: alle Rollstuhlplätze zusammen dürfen nicht mehr Sitzplätze ersetzen, als es gibt
            if (spaces.wheelchairSpaces.toLong() * spaces.seatsReplacedPerWheelchair > spaces.seatPlaces) v.add("/spaces/seats_replaced_per_wheelchair", ViolationCode.ABOVE_MAXIMUM)
            val codes = HashSet<String>()
            combinations.forEachIndexed { i, c ->
                if (!codes.add(c.code)) v.add("/combinations/$i/code", ViolationCode.NOT_UNIQUE)
                if (c.wheelchairPlaces > spaces.wheelchairSpaces) v.add("/combinations/$i/wheelchair_places", ViolationCode.ABOVE_MAXIMUM)
                else if (c.wheelchairPlaces.toLong() * spaces.seatsReplacedPerWheelchair > spaces.seatPlaces) v.add("/combinations/$i/wheelchair_places", ViolationCode.ABOVE_MAXIMUM)
            }
            if (active !in codes) v.add("/active_combination", ViolationCode.NOT_IN_ENUM)
            val rcodes = HashSet<String>()
            restrictions.forEachIndexed { i, r ->
                if (!rcodes.add(r.code)) v.add("/restrictions/$i/code", ViolationCode.NOT_UNIQUE)
                if (r.code == CapacityRestriction.MAX_WHEELCHAIR_SPACES && r.value > spaces.wheelchairSpaces) v.add("/restrictions/$i/value", ViolationCode.ABOVE_MAXIMUM)
                if (r.code == CapacityRestriction.MAX_TOTAL_PERSONS && (r.value < 1 || r.value > spaces.seatPlaces + spaces.wheelchairSpaces)) v.add("/restrictions/$i/value", if (r.value < 1) ViolationCode.BELOW_MINIMUM else ViolationCode.ABOVE_MAXIMUM)
            }
            v.throwIfAny()
            return CapacityLayout(spaces, equipment, combinations, active, restrictions)
        }
    }
}

/**
 * Änderung der Kapazitätskonfiguration in Teilen (jeder Teil unabhängig; `null` = unverändert). Gibt es noch keine Konfiguration, sind [spaces] und [combinations] Pflicht. Die
 * Teile werden mit dem gespeicherten Stand zusammengeführt und als Ganzes auf Widersprüche geprüft.
 */
data class CapacityPatch(
    val spaces: CapacitySpaces? = null, val equipment: Set<String>? = null, val combinations: List<CapacityCombination>? = null, val activeCombination: String? = null,
    val restrictions: List<CapacityRestriction>? = null,
) {
    companion object {
        fun parse(raw: Map<String, Any?>): CapacityPatch {
            val v = Violations()
            v.unknown(raw, setOf("spaces", "equipment", "combinations", "active_combination", "restrictions"), "")
            val spaces = if (raw.containsKey("spaces")) CapacityLayout.spaces(raw["spaces"], v) else null
            val equipment = if (raw.containsKey("equipment")) v.equipment(raw, "equipment", "/equipment") else null
            val combos = if (raw.containsKey("combinations")) CapacityLayout.combinations(raw["combinations"], v) else null
            val active = if (raw.containsKey("active_combination")) (raw["active_combination"] as? String)?.takeIf { CapacityLayout.CODE.matches(it) } ?: run { v.add("/active_combination", ViolationCode.PATTERN_MISMATCH); null } else null
            val restrictions = if (raw.containsKey("restrictions")) CapacityLayout.restrictions(raw["restrictions"], v) else null
            if (raw.isEmpty()) v.add("", ViolationCode.TOO_FEW_ITEMS)
            v.throwIfAny()
            return CapacityPatch(spaces, equipment, combos, active, restrictions)
        }
    }
}

/** Stand der Kapazitätskonfiguration: [layout] ist `null`, solange nur die Grundkapazität des Stamms ([effective]) gepflegt ist. [vectors] sind die verfügbaren Ressourcenvektoren je Konfiguration. */
data class CapacityView(val vehicleId: UUID, val layout: CapacityLayout?, val effective: ConfiguredVector, val vectors: List<ConfiguredVector>, val revision: Long)

/**
 * Kapazitätskonfiguration je Fahrzeug (WP-M3-04): Lesen, Ändern in Teilen (mit der Stammdaten-Revision, 428/412), Ressourcenvektor je Konfiguration als Modul-API für `booking-dispatch`.
 * Eine Änderung schreibt die Konfiguration und den daraus abgeleiteten wirksamen Vektor (`vehicle.capacity_config`) in einer Transaktion unter der Fahrzeugzeilensperre; bestehende
 * Holds und Buchungen tragen ihren eigenen Vektor und bleiben unverändert (nicht rückwirkend). Ein gleichzeitiger Hold (`FOR SHARE` auf der Fahrzeugzeile) sieht entweder den Stand vor
 * oder nach der Änderung, nie einen Mischstand.
 */
interface VehicleCapacities {
    fun getCapacity(ctx: RequestContext, vehicleId: UUID): CapacityView
    fun updateCapacity(ctx: RequestContext, vehicleId: UUID, expectedRevision: Long, patch: CapacityPatch): CapacityView

    /** Verfügbare Ressourcenvektoren je Konfiguration innerhalb der laufenden Mandantentransaktion des Aufrufers; fremdes oder unbekanntes Fahrzeug: 404. */
    fun resourceVectors(jdbc: JdbcOperations, vehicleId: UUID): List<ConfiguredVector>
}

internal fun notFound(): Nothing = throw ProblemException.notFound()
internal fun invalid(pointer: String, code: ViolationCode): Nothing = throw ProblemException.invalid(Violation(pointer, code))
