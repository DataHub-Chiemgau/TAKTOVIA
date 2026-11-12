package de.taktovia.fleetsafety.api

import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.statemachine.StateMachine

/** Zustände der Schadenakte (`defect_case.status`, LH-6.22-DMG-007). Wire-Werte sind die Namen. */
enum class DefectState { NEW, REVIEWED, IN_PROGRESS, REPAIR, CLOSED, DISMISSED;
    val isFinal get() = this == CLOSED || this == DISMISSED
    companion object { fun parse(code: String): DefectState? = entries.firstOrNull { it.name == code } }
}

/**
 * Der Automat im Rahmen aus WP-M1-17 ([StateMachine]): Zustände und erlaubte Übergänge laut `docs/umsetzungsplan/vertraege/zustaende/defect_case.md`; die Datenbank erzwingt dieselbe Tabelle per Trigger
 * (`V30470`, `db/tests/test_defect_state.py`). Die Ausführung macht [Defects.transition] selbst, weil `dismissed_reason`, `reopen_count` und die Statushistorie im selben Schritt entstehen
 * (der generische `StateTransitioner` schreibt nur Zustand und Revision). Rollen je Übergang laut Spalte „Akteur“ der Datei; `tenant_admin` (Betriebsleitung) nur beim Abschluss nach Reparatur und
 * bei der Wiedereröffnung.
 */
object DefectStateMachine {
    val MACHINE: StateMachine<DefectState> = StateMachine(
        "defect", 1, DefectState.entries, "taktovia.defect_case", "status",
        mapOf(
            DefectState.NEW to setOf(DefectState.REVIEWED, DefectState.DISMISSED),
            DefectState.REVIEWED to setOf(DefectState.IN_PROGRESS, DefectState.DISMISSED, DefectState.CLOSED),
            DefectState.IN_PROGRESS to setOf(DefectState.REPAIR, DefectState.CLOSED, DefectState.DISMISSED),
            DefectState.REPAIR to setOf(DefectState.CLOSED, DefectState.IN_PROGRESS),
        ),
    )

    private val DISPATCH_OR_WORKSHOP = setOf("dispatcher", "workshop")
    private val WORKSHOP = setOf("workshop")
    private val WORKSHOP_OR_ADMIN = setOf("workshop", "tenant_admin")

    /** Rollen, die den Übergang auslösen dürfen (Rollenbezeichnungen sind Arbeitsnamen, OP-34). */
    val EDGE_ROLES: Map<Pair<DefectState, DefectState>, Set<String>> = mapOf(
        (DefectState.NEW to DefectState.REVIEWED) to DISPATCH_OR_WORKSHOP,
        (DefectState.NEW to DefectState.DISMISSED) to WORKSHOP,
        (DefectState.REVIEWED to DefectState.IN_PROGRESS) to DISPATCH_OR_WORKSHOP,
        (DefectState.REVIEWED to DefectState.DISMISSED) to WORKSHOP,
        (DefectState.REVIEWED to DefectState.CLOSED) to WORKSHOP,
        (DefectState.IN_PROGRESS to DefectState.REPAIR) to WORKSHOP,
        (DefectState.IN_PROGRESS to DefectState.CLOSED) to WORKSHOP,
        (DefectState.IN_PROGRESS to DefectState.DISMISSED) to WORKSHOP,
        (DefectState.REPAIR to DefectState.CLOSED) to WORKSHOP_OR_ADMIN,
        (DefectState.REPAIR to DefectState.IN_PROGRESS) to WORKSHOP,
    )

    /** Wiedereröffnung eines Endzustands nach [DefectState.REVIEWED]: dokumentiert (Begründung Pflicht, `reopen=true`, Zähler und Historie), nur Werkstatt und Betriebsleitung. */
    val REOPEN_ROLES: Set<String> = WORKSHOP_OR_ADMIN

    /** Wer überhaupt Statusübergänge auslösen darf (sonst 403, bevor die Tabelle geprüft wird). */
    val TRANSITIONERS: Set<String> = setOf("dispatcher", "workshop", "tenant_admin")
}

/** Statusübergang (`POST /v1/defects/{id}/transitions`). [reason] ist für [DefectState.DISMISSED] und die Wiedereröffnung Pflicht; [reopen] kennzeichnet die Wiedereröffnung. */
data class DefectTransition(val to: DefectState, val reason: String?, val reopen: Boolean) {
    companion object {
        private val FIELDS = setOf("to", "reason", "reopen")

        fun parse(raw: Map<String, Any?>): DefectTransition {
            val v = Violations()
            v.unknown(raw, FIELDS, "")
            val to = when (val t = raw["to"]) {
                null -> { v.add("/to", ViolationCode.REQUIRED); null }
                is String -> DefectState.parse(t)?.takeIf { it != DefectState.NEW } ?: run { v.add("/to", ViolationCode.NOT_IN_ENUM); null }
                else -> { v.add("/to", ViolationCode.TYPE_MISMATCH); null }
            }
            val reason = v.text(raw, "reason", DefectCatalog.MAX_REASON, false)
            val reopen = when (val r = raw["reopen"]) { null -> false; is Boolean -> r; else -> { v.add("/reopen", ViolationCode.TYPE_MISMATCH); false } }
            v.throwIfAny()
            return DefectTransition(to!!, reason, reopen)
        }
    }
}

/** Eintrag der Statushistorie (append-only): Akteur und Zeit je Übergang; [fromStatus] ist `null` bei der Anlage. */
data class StatusHistoryEntry(
    val id: java.util.UUID, val defectId: java.util.UUID, val fromStatus: String?, val toStatus: String, val actor: String, val reason: String?, val revision: Long, val reopening: Boolean,
    val occurredAt: java.time.Instant,
)
