package de.taktovia.platform.statemachine

import de.taktovia.platform.api.Etag
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.util.UUID

/** Ergebnis eines ausgeführten Übergangs. [revision] ist die neue Revision des Aggregats. */
data class Transition<S : Enum<S>>(val aggregateId: UUID, val from: S, val to: S, val revision: Long, val eventId: UUID)

/** Ergebnis der Anwendung eines (möglicherweise verspäteten oder mehrfach zugestellten) Ereignisses. */
sealed interface EventOutcome<S : Enum<S>> {
    data class Applied<S : Enum<S>>(val transition: Transition<S>) : EventOutcome<S>
    /** Das Aggregat hat die zugrunde liegende Revision bereits verlassen: keine Wirkung, kein Rücksprung (PH-AT-017). */
    class Stale<S : Enum<S>> : EventOutcome<S>
}

/**
 * Serverseitige Übergangsprüfung (WP-M1-17, LH-6.1-SYS-007). Läuft in der Mandantentransaktion des Aufrufers (`TenantTransactions.run`):
 * Zeile sperren, Revision prüfen (CAS), Übergang gegen die Tabelle der Definition prüfen, Wächter, Zustand und Revision schreiben und das Ereignis
 * `<aggregat>.state_changed` in dieselbe Transaktion legen (Leitlinie 3). Ein abgewiesener Übergang ändert nichts und schreibt kein Ereignis.
 * Es wird nur die Zustandsspalte der Definition (und die Revision) geschrieben, nie der Zustand eines anderen Automaten.
 */
class StateTransitioner(private val outbox: Outbox = Outbox(), private val clock: Clock = Clock.systemUTC()) {

    private class Row(val state: String, val revision: Long)

    /**
     * Führt `aggregat → [to]` aus. [expectedRevision] stammt aus `If-Match`; fehlt sie, 428, weicht sie ab, 412 `REVISION_MISMATCH`.
     * Nicht erlaubter Übergang: 409 `INVALID_STATE_TRANSITION`. Unbekannte Aggregat-ID oder fremder Mandant: 404.
     */
    fun <S : Enum<S>> transition(jdbc: JdbcOperations, m: StateMachine<S>, id: UUID, to: S, expectedRevision: Long?, correlationId: UUID? = null): Transition<S> {
        val row = lock(jdbc, m, id)
        if (m.revisionColumn != null) {
            val expected = expectedRevision ?: throw ProblemException(ErrorCode.PRECONDITION_REQUIRED, "Vor einer Änderung die Ressource lesen und ihren ETag als If-Match senden.")
            if (expected != row.revision) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Ressource wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        }
        return execute(jdbc, m, id, row, to, correlationId)
    }

    /** Wie [transition], der Zielzustand kommt als Text vom Client; ein unbekannter Wert ist ein Feldfehler (422), kein Übergangsfehler. */
    fun <S : Enum<S>> transition(jdbc: JdbcOperations, m: StateMachine<S>, id: UUID, to: String, expectedRevision: Long?, correlationId: UUID? = null): Transition<S> {
        val target = m.parse(to) ?: throw ProblemException.invalid(Violation("/state", ViolationCode.NOT_IN_ENUM))
        return transition(jdbc, m, id, target, expectedRevision, correlationId)
    }

    /**
     * Ereignisgetriebene Anwendung (Inbox-Handler, PSP-Rückmeldung): [basedOnRevision] ist die Revision, auf die sich das Ereignis stützt. Hat das Aggregat
     * sie verlassen, ist das Ereignis veraltet oder eine Wiederzustellung: keine Wirkung, kein Rücksprung, kein Ereignis. Ein nicht erlaubter Übergang
     * auf der passenden Revision bleibt ein Fehler (409).
     */
    fun <S : Enum<S>> applyEvent(jdbc: JdbcOperations, m: StateMachine<S>, id: UUID, to: S, basedOnRevision: Long, correlationId: UUID? = null): EventOutcome<S> {
        require(m.revisionColumn != null) { "Ereignisanwendung braucht eine Revisionsspalte (OP-33)" }
        val row = lock(jdbc, m, id)
        if (basedOnRevision != row.revision) return EventOutcome.Stale()
        return EventOutcome.Applied(execute(jdbc, m, id, row, to, correlationId))
    }

    private fun <S : Enum<S>> lock(jdbc: JdbcOperations, m: StateMachine<S>, id: UUID): Row {
        val rev = m.revisionColumn ?: "0"
        // Bezeichner stammen ausschließlich aus der geprüften Definition (StateMachine.init), Werte sind gebunden.
        return jdbc.query("SELECT ${m.stateColumn}, $rev FROM ${m.table} WHERE ${m.tenantColumn} = taktovia.current_tenant() AND " + // sql-lint: ok Bezeichner stammen aus der geprüften Automatendefinition, Werte sind gebunden
            (if (m.tenantColumn == "id") "TRUE" else "id = ?") + " FOR UPDATE", { rs, _ -> Row(rs.getString(1), rs.getLong(2)) },
            *(if (m.tenantColumn == "id") emptyArray() else arrayOf<Any>(id))).firstOrNull() ?: throw ProblemException.notFound()
    }

    private fun <S : Enum<S>> execute(jdbc: JdbcOperations, m: StateMachine<S>, id: UUID, row: Row, to: S, correlationId: UUID?): Transition<S> {
        val from = m.parse(row.state) ?: throw IllegalStateException("Zustandswert in der Datenbank ist dem Automaten ${m.name} unbekannt")
        if (!m.allowed(from, to)) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Der Übergang von ${from.name} nach ${to.name} ist nicht erlaubt.")
        m.guards[from to to]?.check(jdbc, id, from, to)?.let { throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Der Übergang von ${from.name} nach ${to.name} ist nicht zulässig: $it.") }

        val sets = if (m.revisionColumn != null) "${m.stateColumn} = ?, ${m.revisionColumn} = ${m.revisionColumn} + 1" else "${m.stateColumn} = ?"
        val cas = if (m.revisionColumn != null) "${m.revisionColumn} = ?" else "${m.stateColumn} = ?"
        val tenantAndId = if (m.tenantColumn == "id") "id = taktovia.current_tenant()" else "${m.tenantColumn} = taktovia.current_tenant() AND id = ?"
        val args = listOfNotNull<Any>(to.name, if (m.tenantColumn == "id") null else id, if (m.revisionColumn != null) row.revision else row.state)
        val updated = jdbc.update("UPDATE ${m.table} SET $sets WHERE $tenantAndId AND $cas", *args.toTypedArray()) // sql-lint: ok Bezeichner stammen aus der geprüften Automatendefinition, Werte sind gebunden
        Etag.requireUpdated(updated) { true }

        val newRevision = if (m.revisionColumn != null) row.revision + 1 else nextSequence(jdbc, m, id)
        val eventId = outbox.append(jdbc, OutboxEvent(id, newRevision, m.eventType,
            mapOf("machine" to m.name, "machine_version" to m.version, "from" to from.name, "to" to to.name, "previous_revision" to newRevision - 1, "revision" to newRevision),
            clock.instant(), correlationId = correlationId))
        return Transition(id, from, to, newRevision, eventId)
    }

    /** Ohne Revisionsspalte (OP-33): laufende Nummer der Zustandsereignisse dieses Aggregats. Die Zeilensperre aus [lock] serialisiert die Vergabe. */
    private fun <S : Enum<S>> nextSequence(jdbc: JdbcOperations, m: StateMachine<S>, id: UUID): Long =
        (jdbc.queryForObject("SELECT COALESCE(max(aggregate_revision), 0) FROM taktovia.outbox WHERE aggregate_id = ? AND event_type = ?", Long::class.java, id, m.eventType) ?: 0L) + 1
}
