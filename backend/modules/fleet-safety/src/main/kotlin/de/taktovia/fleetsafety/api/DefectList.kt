package de.taktovia.fleetsafety.api

import de.taktovia.platform.api.KeyedCursorPosition
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Sortierbare Spalten der Schadenliste (WP-M3-49, DMG-009): genau die angezeigten Spalten. [CREATED] ist die Standardreihenfolge `(created_at, id)` (Kap. 05 §5.1). Jede Sortierung hat den Tiebreaker
 * `id` in derselben Richtung, die Reihenfolge ist also auch bei gleichen Werten eindeutig.
 */
enum class DefectSortColumn(val token: String) {
    CREATED("created_at"), REPORTED_AT("reported_at"), CASE_NUMBER("case_number"), VEHICLE("vehicle"), PRIORITY("priority"), STATUS("status"), ASSIGNEE("assignee");

    companion object { fun parse(token: String): DefectSortColumn? = entries.firstOrNull { it.token == token && it != CREATED } }
}

/** Sortierung `sort=<spalte>` aufsteigend bzw. `sort=-<spalte>` absteigend. */
data class DefectSort(val column: DefectSortColumn = DefectSortColumn.CREATED, val descending: Boolean = false) {
    val token: String get() = (if (descending) "-" else "") + column.token
}

/**
 * Abfrage der Schadenliste (`GET /v1/defects`): sechs Filter (Fahrzeug, Priorität, Status, Zeitraum, Depot, Verantwortlicher), Sortierung, `limit` 1..100 (Standard 50) und Position aus dem Cursor.
 * Priorität ist aus der Klasse abgeleitet (Arbeitsannahme, Detailvertrag `m3-schadenakte.md` §8): 1 = `SAFETY_CRITICAL`, 2 = `OPERATIONALLY_RELEVANT`, 3 = `COSMETIC`, 4 = `OTHER`; der bestehende
 * Filter `severity` bleibt, beide wirken zusammen (UND). Verantwortlicher = Zuständiger der Triage. Ungültige Werte: 422 mit Feldverweis `/$query/<name>`.
 */
data class DefectListQuery(
    val vehicleId: UUID? = null, val severities: Set<String>? = null, val status: String? = null, val reportedFrom: Instant? = null, val reportedTo: Instant? = null, val depotId: UUID? = null,
    val assigneeRef: UUID? = null, val sort: DefectSort = DefectSort(), val limit: Int = 50, val after: KeyedCursorPosition? = null,
) {
    /** Beschreibt Filter und Sortierung für die Bindung des Cursors (ein Cursor gehört zu genau dieser Abfrage). */
    val resourceKey: String get() = listOf("defects", vehicleId ?: "-", severities?.sorted()?.joinToString(",") ?: "-", status ?: "-", reportedFrom ?: "-", reportedTo ?: "-", depotId ?: "-", assigneeRef ?: "-", sort.token).joinToString("|")

    companion object {
        val PRIORITIES: Map<Int, String> = mapOf(1 to DefectSeverities.SAFETY_CRITICAL, 2 to DefectSeverities.OPERATIONALLY_RELEVANT, 3 to DefectSeverities.COSMETIC, 4 to DefectSeverities.OTHER)
        const val MAX_LIMIT = 100
        const val DEFAULT_LIMIT = 50

        /** Liest die Abfrageparameter (alle als Text, wie sie ankommen); sammelt **alle** Feldfehler. */
        fun parse(raw: Map<String, String?>, after: KeyedCursorPosition? = null): DefectListQuery {
            val bad = mutableListOf<Violation>()
            fun add(name: String, code: ViolationCode) { bad += Violation("/\$query/$name", code) }
            fun uuid(name: String): UUID? = raw[name]?.let { v -> runCatching { UUID.fromString(v) }.getOrNull() ?: run { add(name, ViolationCode.INVALID_FORMAT); null } }
            fun instant(name: String): Instant? = raw[name]?.let { v -> try { OffsetDateTime.parse(v).toInstant() } catch (e: DateTimeParseException) { add(name, ViolationCode.INVALID_FORMAT); null } }
            val vehicle = uuid("vehicle_id"); val depot = uuid("depot_id"); val assignee = uuid("assignee_ref")
            val from = instant("reported_from"); val to = instant("reported_to")
            if (from != null && to != null && !from.isBefore(to)) add("reported_to", ViolationCode.INVALID_VALUE)
            val status = raw["status"]?.also { if (it !in DefectCatalog.STATUSES) add("status", ViolationCode.NOT_IN_ENUM) }
            var severities: Set<String>? = null
            raw["severity"]?.let { v -> if (v !in DefectCatalog.SEVERITIES) add("severity", ViolationCode.NOT_IN_ENUM) else severities = setOf(v) }
            raw["priority"]?.let { v ->
                val p = v.toIntOrNull()
                if (p == null) add("priority", ViolationCode.TYPE_MISMATCH)
                else if (p !in PRIORITIES) add("priority", if (p < 1) ViolationCode.BELOW_MINIMUM else ViolationCode.ABOVE_MAXIMUM)
                else severities = severities?.intersect(setOf(PRIORITIES.getValue(p))) ?: setOf(PRIORITIES.getValue(p))
            }
            val sort = raw["sort"]?.let { v ->
                val desc = v.startsWith("-")
                DefectSortColumn.parse(v.removePrefix("-"))?.let { DefectSort(it, desc) } ?: run { add("sort", ViolationCode.NOT_IN_ENUM); null }
            } ?: DefectSort()
            val limit = raw["limit"]?.let { v ->
                val n = v.toIntOrNull()
                if (n == null) { add("limit", ViolationCode.TYPE_MISMATCH); DEFAULT_LIMIT } else if (n < 1) { add("limit", ViolationCode.BELOW_MINIMUM); DEFAULT_LIMIT } else if (n > MAX_LIMIT) { add("limit", ViolationCode.ABOVE_MAXIMUM); DEFAULT_LIMIT } else n
            } ?: DEFAULT_LIMIT
            if (bad.isNotEmpty()) throw ProblemException.invalid(*bad.toTypedArray())
            return DefectListQuery(vehicle, severities, status, from, to, depot, assignee, sort, limit, after)
        }
    }
}

/** Eine Seite der Schadenliste: [next] ist die Position für die Folgeseite (`null` = letzte Seite), [asOf] der Stand der Momentaufnahme (gilt für alle Seiten derselben Paginierung). */
data class DefectListPage(val items: List<DefectView>, val next: KeyedCursorPosition?, val asOf: Instant)
