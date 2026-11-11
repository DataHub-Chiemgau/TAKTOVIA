package de.taktovia.platform.statemachine

import java.util.UUID
import org.springframework.jdbc.core.JdbcOperations

/** Woher die Revision eines Übergangs kommt: Spalte des Aggregats oder (nur ohne Revisionsspalte, OP-33) Ereignisfolge der Outbox. */
enum class RevisionSource { COLUMN, EVENT_SEQUENCE }

/** Eine Definition eines Zustandsautomaten; je Automat genau eine Zustandsspalte (Kap. 02 §7.3, LH-6.1-SYS-007). */
sealed interface MachineDefinition {
    /** Name des Automaten und Aggregattyp im Ereignis `<name>.state_changed`. */
    val name: String
    val table: String?
    val stateColumn: String?
    /** `false`, solange die Zustands- und Übergangsliste im Pflichtenheft fehlt (OP-21); dann ist kein Übergang ausführbar. */
    val specified: Boolean
}

/** Zustandsfolge steht noch nicht fest (OP-21). Registriert, damit der Automat nicht still fehlt; jeder Übergang wird abgelehnt. */
class UnspecifiedMachine(override val name: String, override val table: String?, override val stateColumn: String?, val openPoint: String) : MachineDefinition {
    override val specified = false
}

/** Vom Wächter abgelehnt: Ablehnungscode (Kleinbuchstaben/Unterstrich), erscheint nur im Fehlertext der API. */
fun interface TransitionGuard<S : Enum<S>> {
    /** `null` = Übergang erlaubt; sonst ein Ablehnungscode. Fachliche Wächter (Kapazität, Safety, PSP) liefern M2/M3. */
    fun check(jdbc: JdbcOperations, id: UUID, from: S, to: S): String?
}

/**
 * Deklarative Definition eines Automaten (WP-M1-17): Zustände (Enum), erlaubte Übergänge, optionale Wächter. Jede Definition schreibt
 * ausschließlich ihre eigene Zustandsspalte (und die Revision des Aggregats); die Zuordnung Tabelle/Spalte ist je Definition eindeutig.
 */
class StateMachine<S : Enum<S>>(
    override val name: String,
    val version: Int,
    val states: List<S>,
    override val table: String,
    override val stateColumn: String,
    transitions: Map<S, Set<S>>,
    val revisionColumn: String? = "revision",
    /** Spalte, die den Mandanten trägt; `id` nur an der Mandantentabelle selbst. */
    val tenantColumn: String = "tenant_id",
    val guards: Map<Pair<S, S>, TransitionGuard<S>> = emptyMap(),
) : MachineDefinition {
    override val specified = true
    val transitions: Map<S, Set<S>> = states.associateWith { transitions[it].orEmpty() }
    val revisionSource get() = if (revisionColumn != null) RevisionSource.COLUMN else RevisionSource.EVENT_SEQUENCE
    val eventType get() = "$name.state_changed"

    init {
        require(Regex("[a-z][a-z0-9_]{1,40}").matches(name)) { "ungültiger Automatenname $name" }
        require(version >= 1)
        listOf(table, stateColumn, tenantColumn).plus(listOfNotNull(revisionColumn)).forEach { require(IDENT.matches(it)) { "ungültiger Bezeichner $it" } }
        require(transitions.keys.all { it in states } && transitions.values.all { v -> v.all { it in states } }) { "Übergang mit unbekanntem Zustand" }
        require(transitions.all { (f, ts) -> f !in ts }) { "Übergang in denselben Zustand ist kein Übergang" }
    }

    fun allowed(from: S, to: S) = to in transitions.getValue(from)
    fun parse(value: String): S? = states.firstOrNull { it.name == value }

    /** Alle definierten Übergänge, aus der Tabelle erzeugt. */
    fun definedTransitions(): List<Pair<S, S>> = states.flatMap { f -> transitions.getValue(f).map { f to it } }
    /** Alle nicht definierten Übergänge (ohne Selbstübergang), aus der Tabelle erzeugt. */
    fun undefinedTransitions(): List<Pair<S, S>> = states.flatMap { f -> states.filter { it != f && !allowed(f, it) }.map { f to it } }

    /** Dieselbe Definition an einer anderen Tabelle (z. B. Testaggregate); Spalten bleiben gleich. */
    fun onTable(other: String) = StateMachine(name, version, states, other, stateColumn, transitions, revisionColumn, tenantColumn, guards)

    companion object { private val IDENT = Regex("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)?") }
}
