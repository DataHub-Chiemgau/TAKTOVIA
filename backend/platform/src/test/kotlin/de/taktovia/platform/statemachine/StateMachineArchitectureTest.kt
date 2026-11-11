package de.taktovia.platform.statemachine

import de.taktovia.platform.outbox.Outbox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.ResultSet
import java.util.UUID

/**
 * WP-M1-17: Struktur- und Abgrenzungsregeln der Automaten ohne Datenbank. „Kein Automat schreibt den Zustand eines anderen“ wird an den tatsächlich
 * abgesetzten SQL-Anweisungen geprüft (aufgezeichnet über eine Stellvertreter-Verbindung), nicht an Behauptungen der Definition.
 */
class StateMachineArchitectureTest {
    private class Recorder(var state: String, var revision: Long) {
        val sql = mutableListOf<String>()
        val jdbc: JdbcOperations = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(JdbcOperations::class.java)) { _, method, args ->
            val text = args?.firstOrNull() as? String
            if (text != null) sql += text
            @Suppress("UNCHECKED_CAST")
            when (method.name) {
                "query" -> {
                    val mapper = args!!.first { it is RowMapper<*> } as RowMapper<Any?>
                    val rs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ResultSet::class.java)) { _, m, a ->
                        when (m.name) { "getString" -> state; "getLong" -> revision; else -> error("unerwartet ${m.name} ${a?.toList()}") }
                    } as ResultSet
                    listOf(mapper.mapRow(rs, 0))
                }
                "update" -> 1
                "queryForObject" -> 0L
                else -> error("unerwartete Verbindungsmethode ${method.name}")
            }
        } as JdbcOperations
    }

    @Test fun kein_automat_schreibt_zustandsspalte_eines_anderen() {
        // (1) Jede Zustandsspalte gehört genau einem Automaten, jeder Automat hat einen eigenen Namen.
        val columns = StateMachines.all.mapNotNull { m -> m.table?.let { "$it.${m.stateColumn}" } }
        assertEquals(columns.size, columns.toSet().size, "Zustandsspalte doppelt vergeben: $columns")
        assertEquals(StateMachines.all.size, StateMachines.all.map { it.name }.toSet().size)
        assertEquals(setOf("trip", "booking", "vehicle", "payment", "disruption", "operation", "tenant"), StateMachines.all.map { it.name }.toSet())
        // (2) Jede ausgeführte Anweisung eines Übergangs schreibt nur die Tabelle, die Zustands- und die Revisionsspalte der eigenen Definition
        //     (und das Ereignis in die Outbox).
        exercise(StateMachines.OPERATION); exercise(StateMachines.TENANT); exercise(StateMachines.BOOKING); exercise(StateMachines.PAYMENT)
        assertEquals(setOf("operation", "tenant", "booking", "payment"), StateMachines.specified.map { it.name }.toSet(), "jeder spezifizierte Automat wird geprüft")
    }

    private fun <S : Enum<S>> exercise(m: StateMachine<S>) {
        for ((from, to) in m.definedTransitions()) {
            val rec = Recorder(from.name, 5)
            StateTransitioner(Outbox()).transition(rec.jdbc, m, UUID.randomUUID(), to, if (m.revisionColumn != null) 5L else null)
            val writes = rec.sql.filter { Regex("^\\s*(UPDATE|INSERT|DELETE)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            val updates = writes.filter { it.trimStart().startsWith("UPDATE") }
            assertEquals(1, updates.size, "${m.name}: genau ein UPDATE")
            val set = Regex("UPDATE (\\S+) SET (.*?) WHERE").find(updates.single())!!
            assertEquals(m.table, set.groupValues[1], "${m.name}: nur die eigene Tabelle")
            val assigned = set.groupValues[2].split(",").map { it.substringBefore("=").trim() }
            assertEquals(listOfNotNull(m.stateColumn, m.revisionColumn), assigned, "${m.name}: nur Zustands- und Revisionsspalte")
            assertTrue(writes.filter { it !in updates }.all { it.contains("taktovia.outbox") }, "${m.name}: sonst nur die Outbox: $writes")
        }
    }

    @Test fun zahlungsstatus_wird_nie_aus_buchungsstatus_abgeleitet_und_umgekehrt() {
        // getrennte Zustandstypen mit getrennten Werten, keine Überführung zwischen ihnen
        val b = BookingState.entries.map { it.name }.toSet(); val p = PaymentState.entries.map { it.name }.toSet()
        assertTrue(StateMachines.BOOKING.states.all { it is BookingState } && StateMachines.PAYMENT.states.all { it is PaymentState })
        assertEquals(setOf("RECONCILING"), b intersect p, "gemeinsam nur der Name RECONCILING: zwei verschiedene Zustände zweier Automaten")
        assertTrue(StateMachines.BOOKING.guards.isEmpty() && StateMachines.PAYMENT.guards.isEmpty())
        // Quelltext: die Definition der Zahlung nennt den Buchungsstatus nicht und umgekehrt
        val src = Files.readString(repoRoot().resolve("backend/platform/src/main/kotlin/de/taktovia/platform/statemachine/StateMachines.kt"))
        val booking = src.substringAfter("val BOOKING").substringBefore("val PAYMENT")
        val payment = src.substringAfter("val PAYMENT").substringBefore("// OP-21")
        assertFalse(payment.contains("BookingState") || payment.contains("BOOKING"), "Zahlung referenziert Buchung")
        assertFalse(booking.contains("PaymentState") || booking.contains("PAYMENT"), "Buchung referenziert Zahlung")
        // Selbsttest der Prüfung: eine absichtlich verschränkte Definition würde auffallen
        assertTrue("val PAYMENT = x(BookingState.HELD)".substringAfter("val PAYMENT").contains("BookingState"))
    }

    private fun repoRoot(): Path = Path.of(System.getProperty("taktovia.repoRoot") ?: Path.of("").toAbsolutePath().let { generateSequence(it) { p -> p.parent }.first { p -> Files.exists(p.resolve("db/migrations")) }.toString() })
}
