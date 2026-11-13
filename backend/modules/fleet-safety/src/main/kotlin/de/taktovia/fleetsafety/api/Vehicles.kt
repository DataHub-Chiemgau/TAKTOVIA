package de.taktovia.fleetsafety.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/**
 * Wertekatalog des Fahrzeugstamms. Fahrzeugtyp und Ausstattung sind Platzhalter bis zur Festlegung durch den Auftraggeber (OP-14): der Typ ist ein Code nach Muster,
 * die Ausstattungsmerkmale entsprechen dem Ressourcenvektor `resource-vector.v1` (WP-M2-14; ein Test in `app` hält beide Listen gleich).
 */
object VehicleCatalog {
    val EQUIPMENT: Set<String> = setOf("RAMP", "LIFT", "CHILD_SEAT", "BOOSTER_SEAT", "LUGGAGE_SPACE", "ASSISTANCE_DOG_SPACE", "STRETCHER_FIXING")
    val TYPE_PATTERN = Regex("^[A-Z][A-Z0-9_]{1,39}$")
    const val MAX_SEATS = 99
    const val MAX_WHEELCHAIR = 20
    const val MAX_LABEL = 64
}

/** Strikte Hilfen zum Lesen untypisierter Eingaben (JSON-Objekt als Map); jede Verletzung wird als Feldfehler (422) mit JSON-Pointer gesammelt. */
internal class Violations {
    val list = mutableListOf<Violation>()
    fun add(pointer: String, code: ViolationCode) { list += Violation(pointer, code) }
    fun throwIfAny() { if (list.isNotEmpty()) throw ProblemException.invalid(*list.toTypedArray()) }

    /** Ganzzahl im Bereich: fehlend = REQUIRED, kein ganzzahliger Zahlenwert (auch 2.5, "3", true) = TYPE_MISMATCH, außerhalb = BELOW_MINIMUM/ABOVE_MAXIMUM. */
    fun int(raw: Map<*, *>, key: String, pointer: String, min: Int, max: Int): Int? {
        if (!raw.containsKey(key) || raw[key] == null) { add(pointer, ViolationCode.REQUIRED); return null }
        val v = raw[key]
        val n: Long = when (v) {
            is Int -> v.toLong()
            is Long -> v
            is Short, is Byte -> (v as Number).toLong()
            is java.math.BigInteger -> if (v.bitLength() < 63) v.toLong() else { add(pointer, if (v.signum() < 0) ViolationCode.BELOW_MINIMUM else ViolationCode.ABOVE_MAXIMUM); return null }
            is Double -> if (v.isFinite() && v == Math.rint(v) && Math.abs(v) < 1e15) v.toLong() else { add(pointer, ViolationCode.TYPE_MISMATCH); return null }   // 4.0 ist ganzzahlig (JSON Schema), 2.5 nicht
            is java.math.BigDecimal -> if (v.stripTrailingZeros().scale() <= 0 && v.abs() < java.math.BigDecimal("1e15")) v.toLong() else { add(pointer, ViolationCode.TYPE_MISMATCH); return null }
            else -> { add(pointer, ViolationCode.TYPE_MISMATCH); return null }
        }
        if (n < min) { add(pointer, ViolationCode.BELOW_MINIMUM); return null }
        if (n > max) { add(pointer, ViolationCode.ABOVE_MAXIMUM); return null }
        return n.toInt()
    }

    fun equipment(raw: Map<*, *>, key: String, pointer: String): Set<String>? {
        if (!raw.containsKey(key) || raw[key] == null) { add(pointer, ViolationCode.REQUIRED); return null }
        val l = raw[key] as? List<*> ?: run { add(pointer, ViolationCode.TYPE_MISMATCH); return null }
        val out = linkedSetOf<String>()
        l.forEachIndexed { i, e ->
            when {
                e !is String || e !in VehicleCatalog.EQUIPMENT -> add("$pointer/$i", ViolationCode.NOT_IN_ENUM)
                !out.add(e) -> add("$pointer/$i", ViolationCode.NOT_UNIQUE)
            }
        }
        if (l.size > 20) add(pointer, ViolationCode.TOO_MANY_ITEMS)
        return out
    }

    fun unknown(raw: Map<*, *>, allowed: Set<String>, base: String) {
        raw.keys.forEach { k -> if (k !in allowed) add("$base/$k", ViolationCode.UNKNOWN_FIELD) }
    }
}

/**
 * Pflichtangaben des Fahrzeugstamms (LH-6.8-FLT-001): Kennung, Betreiber, Typ, Kapazitäten (Sitz, Rollstuhlplatz), Ausstattung und Aktivstatus. [parse] prüft eine untypisierte
 * Eingabe vollständig: negative, fehlende oder nicht ganzzahlige Kapazität ergibt 422 mit Feldverweis (Originalabnahme), ebenso jedes fehlende Pflichtfeld.
 */
data class VehicleMasterInput(
    val label: String, val type: String, val operatorOrganizationId: UUID, val seats: Int, val wheelchair: Int, val equipment: Set<String>, val active: Boolean,
    /** Betriebshof (Arbeitsannahme OP-22, WP-M3-49); [depotGiven] = `depot_id` kam in der Anfrage vor (fehlt es, bleibt der Betriebshof bei einer Änderung unverändert, `null` entfernt ihn). */
    val depotId: UUID? = null, val depotGiven: Boolean = false,
) {
    companion object {
        private val FIELDS = setOf("vehicle_label", "vehicle_type", "operator_organization_id", "capacity", "equipment", "active", "depot_id")

        fun parse(raw: Map<String, Any?>): VehicleMasterInput {
            val v = Violations()
            v.unknown(raw, FIELDS, "")
            val label = (raw["vehicle_label"] as? String)?.also {
                if (it.isBlank() || it.length > VehicleCatalog.MAX_LABEL) v.add("/vehicle_label", if (it.isBlank()) ViolationCode.TOO_SHORT else ViolationCode.TOO_LONG)
            } ?: run { v.add("/vehicle_label", if (raw["vehicle_label"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH); null }
            val type = (raw["vehicle_type"] as? String)?.also { if (!VehicleCatalog.TYPE_PATTERN.matches(it)) v.add("/vehicle_type", ViolationCode.PATTERN_MISMATCH) }
                ?: run { v.add("/vehicle_type", if (raw["vehicle_type"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH); null }
            val operator = (raw["operator_organization_id"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: run { v.add("/operator_organization_id", if (raw["operator_organization_id"] == null) ViolationCode.REQUIRED else ViolationCode.INVALID_FORMAT); null }
            var seats: Int? = null; var wheelchair: Int? = null
            val cap = raw["capacity"]
            if (cap == null) v.add("/capacity", ViolationCode.REQUIRED)
            else if (cap !is Map<*, *>) v.add("/capacity", ViolationCode.TYPE_MISMATCH)
            else {
                v.unknown(cap, setOf("seats", "wheelchair"), "/capacity")
                seats = v.int(cap, "seats", "/capacity/seats", 0, VehicleCatalog.MAX_SEATS)
                wheelchair = v.int(cap, "wheelchair", "/capacity/wheelchair", 0, VehicleCatalog.MAX_WHEELCHAIR)
            }
            val equipment = v.equipment(raw, "equipment", "/equipment")
            val active = raw["active"] as? Boolean ?: run { v.add("/active", if (raw["active"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH); null }
            val depotGiven = raw.containsKey("depot_id")
            val depot = if (depotGiven && raw["depot_id"] != null) v.uuid(raw, "depot_id", false) else null
            v.throwIfAny()
            return VehicleMasterInput(label!!, type!!, operator!!, seats!!, wheelchair!!, equipment!!, active!!, depot, depotGiven)
        }
    }
}

/** Stammdaten eines Fahrzeugs, wie gespeichert. [operatorOrganizationId] ist bei Altbestand ohne Organisationsverweis `null`. [dispatchBlocked] ist `vehicle.active_block` (Sperre/fehlende Einsatzfreigabe). */
data class VehicleMasterView(
    val id: UUID, val label: String?, val type: String?, val operatorOrganizationId: UUID?, val seats: Int, val wheelchair: Int, val equipment: List<String>,
    val active: Boolean, val dispatchBlocked: Boolean, val safetyRevision: Long, val hasCapacityLayout: Boolean, val revision: Long, val createdAt: Instant, val updatedAt: Instant,
    val depotId: UUID? = null,
) {
    /** Disponierbar nur aktiv und ohne Sperre (ein neues Fahrzeug ist bis zur Einsatzfreigabe gesperrt, OP-01). */
    val dispatchable: Boolean get() = active && !dispatchBlocked
}

/**
 * Fahrzeugstamm (WP-M3-03): Anlegen, Ändern, Deaktivieren. Mandant nur aus dem Kontext; fremde Fahrzeuge sind 404. Änderung mit der erwarteten Revision (`If-Match`, ETag =
 * Stammdaten-Revision, getrennt von `safety_revision`): veraltet 412, gleichzeitige Änderung derselben Revision: genau eine gewinnt. Doppelte Kennung im Mandanten: 409 mit Feldverweis.
 * Jede Änderung: eine Zeile, genau ein Audit (`fleet.vehicle.created|changed|deactivated`) und genau ein Outbox-Ereignis in derselben Transaktion.
 */
interface Vehicles {
    fun create(ctx: RequestContext, input: VehicleMasterInput): VehicleMasterView
    fun get(ctx: RequestContext, vehicleId: UUID): VehicleMasterView
    fun list(ctx: RequestContext, active: Boolean?, limit: Int, after: Pair<Instant, UUID>?): List<VehicleMasterView>
    fun update(ctx: RequestContext, vehicleId: UUID, expectedRevision: Long, input: VehicleMasterInput): VehicleMasterView

    /** Disponierbar jetzt? Innerhalb der laufenden Mandantentransaktion des Aufrufers (z. B. Hold im Kandidatenfilter); unbekannt oder fremd = `false`. */
    fun isActive(jdbc: JdbcOperations, vehicleId: UUID): Boolean
}
