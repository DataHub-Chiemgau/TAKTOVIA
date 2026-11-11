package de.taktovia.platform.statemachine

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.problem.DbErrorMapper
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * WP-M1-17 / LH-6.1-SYS-007, Originalabnahme: „Unerlaubte Statusübergänge werden serverseitig abgewiesen.“ Alles gegen die echte Datenbank unter der
 * Runtime-Rolle (Mandantenstatus unter dem Administrator, weil die Runtime ihn nicht ändern darf). Die Tests werden aus den Übergangstabellen erzeugt.
 */
class StateMachineTransitionTest {
    companion object {
        private lateinit var s: StateMachineTestSupport
        @BeforeAll @JvmStatic fun setup() { s = StateMachineTestSupport("zz_sm_transition") }
        @AfterAll @JvmStatic fun teardown() { if (::s.isInitialized) s.close() }

    }

    private fun <S : Enum<S>> doTransition(m: StateMachine<S>, t: TenantId, id: UUID, to: S, rev: Long?) =
        s.txFor(m).run(t) { j -> s.transitioner.transition(j, m, id, to, rev) }

    private fun <S : Enum<S>> eachPair(m: StateMachine<S>, pairs: List<Pair<S, S>>, body: (TenantId, UUID, S, S) -> Unit) {
        for ((from, to) in pairs) {
            val t = s.newTenant(if (m.name == "tenant") from.name else "ACTIVE"); val id = s.seed(m, t, from.name)
            body(t, id, from, to)
        }
    }

    @Test fun jeder_definierte_uebergang_erlaubt_mit_genau_einem_state_changed() {
        var total = 0
        total += check(StateMachines.OPERATION); total += check(StateMachines.TENANT); total += check(s.booking); total += check(s.payment)
        assertEquals(StateMachines.OPERATION.definedTransitions().size + StateMachines.TENANT.definedTransitions().size +
            StateMachines.BOOKING.definedTransitions().size + StateMachines.PAYMENT.definedTransitions().size, total)
    }

    private fun <S : Enum<S>> check(m: StateMachine<S>): Int {
        var n = 0
        eachPair(m, m.definedTransitions()) { t, id, from, to ->
            val before = s.state(m, t, id)
            val r = doTransition(m, t, id, to, if (m.revisionColumn != null) before.second else null)
            assertEquals(from, r.from); assertEquals(to, r.to)
            val after = s.state(m, t, id)
            assertEquals(to.name, after.first, "${m.name} $from -> $to")
            if (m.revisionColumn != null) assertEquals(before.second + 1, after.second)
            val ev = s.events(t, id, "${m.name}.state_changed")
            assertEquals(1, ev.size, "${m.name} $from -> $to: genau ein Ereignis")
            assertEquals(r.revision, (ev.single()["aggregate_revision"] as Number).toLong())
            val payload = ev.single()["payload"].toString()
            assertTrue(payload.contains("\"from\": \"${from.name}\"") && payload.contains("\"to\": \"${to.name}\"") && payload.contains("\"revision\": ${r.revision}"), payload)
            n++
        }
        return n
    }

    @Test fun unerlaubter_uebergang_wird_mit_409_abgewiesen() {
        var total = 0
        total += reject(StateMachines.OPERATION); total += reject(StateMachines.TENANT); total += reject(s.booking); total += reject(s.payment)
        assertEquals(StateMachines.OPERATION.undefinedTransitions().size + StateMachines.TENANT.undefinedTransitions().size +
            StateMachines.BOOKING.undefinedTransitions().size + StateMachines.PAYMENT.undefinedTransitions().size, total)
        assertTrue(total > 100, "es gibt viele nicht definierte Übergänge: $total")
    }

    private fun <S : Enum<S>> reject(m: StateMachine<S>): Int {
        var n = 0
        eachPair(m, m.undefinedTransitions()) { t, id, from, to ->
            val before = s.state(m, t, id)
            val e = assertThrows<ProblemException>("${m.name} $from -> $to") { doTransition(m, t, id, to, if (m.revisionColumn != null) before.second else null) }
            assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.code); assertEquals(409, e.code.status)
            assertEquals(before, s.state(m, t, id), "Zustand und Revision unverändert")
            assertEquals(0, s.events(t, id, "${m.name}.state_changed").size, "kein Ereignis")
            n++
        }
        return n
    }

    @Test fun unbekannter_zustand_und_fehlende_oder_veraltete_revision_und_fremder_mandant() {
        val m = s.payment; val t = s.newTenant(); val id = s.seed(m, t, "CREATED")
        val bad = assertThrows<ProblemException> { s.tx.run(t) { j -> s.transitioner.transition(j, m, id, "GIBT_ES_NICHT", 1L) } }
        assertEquals(ErrorCode.VALIDATION_FAILED, bad.code); assertEquals(ViolationCode.NOT_IN_ENUM, bad.violations.single().code)
        assertEquals(ErrorCode.PRECONDITION_REQUIRED, assertThrows<ProblemException> { s.tx.run(t) { j -> s.transitioner.transition(j, m, id, PaymentState.AUTHORIZED, null) } }.code)
        assertEquals(ErrorCode.REVISION_MISMATCH, assertThrows<ProblemException> { s.tx.run(t) { j -> s.transitioner.transition(j, m, id, PaymentState.AUTHORIZED, 7L) } }.code)
        val other = s.newTenant()   // fremder Mandant sieht das Aggregat nicht: 404, nie 403 oder 409
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { s.tx.run(other) { j -> s.transitioner.transition(j, m, id, PaymentState.AUTHORIZED, 1L) } }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { s.tx.run(t) { j -> s.transitioner.transition(j, m, UUID.randomUUID(), PaymentState.AUTHORIZED, 1L) } }.code)
        assertEquals("CREATED" to 1L, s.state(m, t, id)); assertEquals(0, s.events(t, id, "payment.state_changed").size)
    }

    @Test fun direkter_schreibversuch_umgeht_die_pruefung_nicht() {
        val m = StateMachines.OPERATION; val t = s.newTenant(); val id = s.seed(m, t, "SUCCEEDED")
        // unzulässiger Wert: CHECK der Datenbank; nicht definierter Übergang: Trigger der Datenbank; beide ergeben denselben Problem-Code wie der Baustein
        val badInsert = assertThrows<DataAccessException> { s.tx.run(t) { it.update(
            "INSERT INTO taktovia.operation(tenant_id,id,journey_id,kind,status,deadline_at) VALUES (taktovia.current_tenant(),?,?,'x','DONE',now())", UUID.randomUUID(), UUID.randomUUID()) } }
        assertTrue(badInsert.message!!.contains("operation_status_state_valid"), badInsert.message)   // der CHECK greift (beim UPDATE käme die Übergangsregel zuerst)
        assertEquals(ErrorCode.VALIDATION_FAILED, DbErrorMapper.map(badInsert).code)
        val badValue = assertThrows<DataAccessException> { s.tx.run(t) { it.update("UPDATE taktovia.operation SET status = 'DONE' WHERE id = ?", id) } }
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, DbErrorMapper.map(badValue).code)
        val badTransition = assertThrows<DataAccessException> { s.tx.run(t) { it.update("UPDATE taktovia.operation SET status = 'QUEUED' WHERE id = ?", id) } }
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, DbErrorMapper.map(badTransition).code)
        assertEquals("SUCCEEDED" to 1L, s.state(m, t, id))
    }

    @Test fun widerspruechliche_uebergaenge_genau_einer_gewinnt() {
        val m = s.payment; val exec = Executors.newFixedThreadPool(8)
        try {
            repeat(15) {
                val t = s.newTenant(); val id = s.seed(m, t, "CREATED"); val go = CountDownLatch(1)
                val targets = listOf(PaymentState.REQUIRES_ACTION, PaymentState.AUTHORIZED, PaymentState.AUTHORIZATION_FAILED, PaymentState.AUTHORIZED)   // alle aus CREATED erlaubt, widersprüchlich, alle auf Revision 1
                val results = targets.map { to -> exec.submit(Callable { go.await(); runCatching { doTransition(m, t, id, to, 1L) } }) }
                go.countDown()
                val rs = results.map { it.get() }
                val winners = rs.filter { it.isSuccess }
                assertEquals(1, winners.size, "genau einer gewinnt")
                rs.filter { it.isFailure }.forEach { assertEquals(ErrorCode.REVISION_MISMATCH, (it.exceptionOrNull() as ProblemException).code) }
                val (state, rev) = s.state(m, t, id)
                assertEquals(winners.single().getOrThrow().to.name, state); assertEquals(2L, rev)
                assertEquals(1, s.events(t, id, "payment.state_changed").size, "genau ein Ereignis")
            }
        } finally { exec.shutdownNow() }
    }

    @Test fun aeltere_revision_bewirkt_keinen_ruecksprung() {
        val m = s.payment; val t = s.newTenant(); val id = s.seed(m, t, "CREATED")
        fun apply(to: PaymentState, rev: Long) = s.tx.run(t) { j -> s.transitioner.applyEvent(j, m, id, to, rev) }
        assertTrue(apply(PaymentState.REQUIRES_ACTION, 1) is EventOutcome.Applied)      // Revision 1 -> 2
        assertTrue(apply(PaymentState.AUTHORIZED, 2) is EventOutcome.Applied)           // 2 -> 3
        // verspätetes Ereignis, das auf Revision 1 beruhte: weder Rücksprung noch Fehler noch Ereignis
        assertTrue(apply(PaymentState.AUTHORIZATION_FAILED, 1) is EventOutcome.Stale)
        assertEquals("AUTHORIZED" to 3L, s.state(m, t, id))
        // dreifach zugestelltes Ereignis (Revision 3 -> CAPTURE_PENDING): genau eine Wirkung
        val outcomes = (1..3).map { apply(PaymentState.CAPTURE_PENDING, 3) }
        assertEquals(1, outcomes.count { it is EventOutcome.Applied }); assertEquals(2, outcomes.count { it is EventOutcome.Stale })
        assertEquals("CAPTURE_PENDING" to 4L, s.state(m, t, id))
        assertEquals(listOf(2L, 3L, 4L), s.events(t, id, "payment.state_changed").map { (it["aggregate_revision"] as Number).toLong() })
        // dieselbe Wirkung auch parallel zugestellt
        val t2 = s.newTenant(); val id2 = s.seed(m, t2, "CREATED"); val go = CountDownLatch(1); val exec = Executors.newFixedThreadPool(6)
        try {
            val fs = (1..6).map { exec.submit(Callable { go.await(); s.tx.run(t2) { j -> s.transitioner.applyEvent(j, m, id2, PaymentState.AUTHORIZED, 1) } }) }
            go.countDown()
            assertEquals(1, fs.map { it.get() }.count { it is EventOutcome.Applied })
        } finally { exec.shutdownNow() }
        assertEquals(1, s.events(t2, id2, "payment.state_changed").size)
    }

    @Test fun waechter_kann_einen_erlaubten_uebergang_ablehnen_ohne_wirkung() {
        val guarded = StateMachine("payment", 1, PaymentState.entries, s.payment.table, "state", s.payment.transitions,
            guards = mapOf((PaymentState.CREATED to PaymentState.AUTHORIZED) to TransitionGuard { _, _, _, _ -> "psp_nicht_bestaetigt" }))
        val t = s.newTenant(); val id = s.seed(guarded, t, "CREATED")
        val e = assertThrows<ProblemException> { s.tx.run(t) { j -> s.transitioner.transition(j, guarded, id, PaymentState.AUTHORIZED, 1L) } }
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.code); assertTrue(e.detail!!.contains("psp_nicht_bestaetigt"))
        assertEquals("CREATED" to 1L, s.state(guarded, t, id)); assertEquals(0, s.events(t, id, "payment.state_changed").size)
        s.tx.run(t) { j -> s.transitioner.transition(j, guarded, id, PaymentState.REQUIRES_ACTION, 1L) }   // ungeschützter Nachbarübergang bleibt möglich
    }

    @Test fun kotlin_und_datenbank_kennen_dieselben_uebergaenge() {
        for (m in listOf(StateMachines.OPERATION, StateMachines.TENANT, StateMachines.BOOKING, StateMachines.PAYMENT)) {
            val db: List<Map<String, Any?>> = s.admin.queryForList("SELECT from_state, to_state, version FROM taktovia.state_transition_rule WHERE machine = ?", m.name)
            assertEquals(m.definedTransitions().map { it.first.name to it.second.name }.toSet(), db.map { it["from_state"] as String to it["to_state"] as String }.toSet(), m.name)
            assertTrue(db.all { (it["version"] as Number).toInt() == m.version })
            assertEquals(m.definedTransitions().size, db.size)
        }
    }

    @Test fun nicht_spezifizierte_automaten_sind_registriert_aber_nicht_ausfuehrbar() {
        val open = StateMachines.all.filterIsInstance<UnspecifiedMachine>()
        assertEquals(setOf("trip", "vehicle", "disruption"), open.map { it.name }.toSet())
        assertTrue(open.all { !it.specified && it.openPoint == "OP-21" })
        assertEquals(7, StateMachines.all.size)
    }
}
