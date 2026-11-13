package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.DefectState
import de.taktovia.fleetsafety.api.DefectStateMachine
import de.taktovia.fleetsafety.api.DefectTransition
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-47 / LH-6.22-DMG-007: Statusautomat der Schadenakte unter der Runtime-Rolle. Originalabnahme: unzulässige Statussprünge werden serverseitig verhindert. Positiv: jeder zulässige Übergang
 * (tabellengetrieben) mit Historie, vollständiger Durchlauf beider Endzustände, Wiedereröffnung. Negativ: jeder Übergang außerhalb der Tabelle (tabellengetrieben, mit Rückprüfung des unveränderten
 * Zustands), ohne Rolle, ohne Begründung, sicherheitskritischer Abschluss ohne Freigabeentscheidung. Konkurrierend: gleichzeitige Übergänge, genau einer.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefectStateMachineTest {
    private lateinit var fx: FleetFixture

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)
    private fun to(state: DefectState, reason: String? = null, reopen: Boolean = false) = DefectTransition(state, reason, reopen)
    private fun go(w: DefectWorld, id: UUID, role: String, cmd: DefectTransition, rev: Long = w.revision(id)) = fx.fleet.defects.transition(w.ctx(role), id, rev, cmd)

    /** Rolle, die den Übergang auslösen darf. */
    private fun roleFor(from: DefectState, target: DefectState) = DefectStateMachine.EDGE_ROLES.getValue(from to target).first()

    /** Akte in [from] mit den Fixtures, die der Übergang nach [target] verlangt. */
    private fun prepared(w: DefectWorld, from: DefectState, target: DefectState): UUID {
        val id = w.caseIn(from.name, severity = "COSMETIC")
        if (from == DefectState.REPAIR) when (target) {
            DefectState.CLOSED -> w.repairOrder(id, "PROOF_RECORDED")
            DefectState.IN_PROGRESS -> w.repairOrder(id, "CANCELED")
            else -> {}
        }
        if (from == DefectState.IN_PROGRESS && target == DefectState.REPAIR) w.repairOrder(id, "OPEN")
        return id
    }

    @Test
    fun jeder_zulaessige_uebergang_ist_ausfuehrbar_und_in_der_historie_mit_akteur_und_zeit() {
        val w = DefectWorld(fx)
        for ((edge, _) in DefectStateMachine.EDGE_ROLES) {
            val (from, target) = edge
            val id = prepared(w, from, target); val rev = w.revision(id)
            val reason = if (target == DefectState.DISMISSED) "Fehlmeldung, kein Schaden" else null
            val v = go(w, id, roleFor(from, target), to(target, reason), rev)
            assertEquals(target.name, v.status, "$from -> $target"); assertEquals(rev + 1, v.revision)
            val ctx = w.ctx(roleFor(from, target)); val actor = "user:${ctx.actor.subject}"
            val h = fx.strings("SELECT from_status || '>' || to_status || '|' || actor FROM taktovia.defect_status_history WHERE defect_id=? AND revision=?", id, v.revision).single()
            assertTrue(h.startsWith("${from.name}>${target.name}|user:"), h)
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.defect.state_changed' AND object_ref=?", w.tenant.value, "defect:$id"), "$from -> $target Audit")
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE aggregate_id=? AND event_type='defect.state_changed' AND aggregate_revision=?", id, v.revision), "$from -> $target Ereignis")
            assertTrue(actor.startsWith("user:"))
        }
    }

    @Test
    fun vollstaendiger_durchlauf_beider_endzustaende() {
        val w = DefectWorld(fx); val ctx = w.ctx("dispatcher")
        // Endzustand CLOSED über die Hauptfolge, beginnend mit der echten Meldung (Statushistorie ab Anlage)
        val d = fx.fleet.defects.report(ctx, de.taktovia.fleetsafety.api.ReportDefect.parse(w.raw())).defect
        w.classify(d.id, "OPERATIONALLY_RELEVANT")
        assertEquals("REVIEWED", go(w, d.id, "dispatcher", to(DefectState.REVIEWED)).status)
        assertEquals("IN_PROGRESS", go(w, d.id, "dispatcher", to(DefectState.IN_PROGRESS)).status)
        w.repairOrder(d.id, "OPEN")
        assertEquals("REPAIR", go(w, d.id, "workshop", to(DefectState.REPAIR)).status)
        assertEquals("Der Reparaturnachweis fehlt.", problem { go(w, d.id, "workshop", to(DefectState.CLOSED)) }.detail!!.substringAfter(": "))
        w.recordProof(d.id)   // Auftrag über IN_PROGRESS mit Nachweis und Kostenbezug (Wächter repair_order_guard, WP-M3-51)
        val closed = go(w, d.id, "tenant_admin", to(DefectState.CLOSED))
        assertEquals("CLOSED", closed.status); assertEquals(5, closed.revision)
        val history = fx.fleet.defects.statusHistory(w.ctx("workshop"), d.id)
        assertEquals(listOf(null, "NEW", "REVIEWED", "IN_PROGRESS", "REPAIR"), history.map { it.fromStatus })
        assertEquals(listOf("NEW", "REVIEWED", "IN_PROGRESS", "REPAIR", "CLOSED"), history.map { it.toStatus })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), history.map { it.revision })
        assertTrue(history.all { it.actor.startsWith("user:") && it.occurredAt.isAfter(java.time.Instant.now().minusSeconds(300)) })
        // Endzustand DISMISSED mit Begründung
        val e = fx.fleet.defects.report(ctx, de.taktovia.fleetsafety.api.ReportDefect.parse(w.raw())).defect
        assertEquals("DISMISSED", go(w, e.id, "workshop", to(DefectState.DISMISSED, "Fehlmeldung")).status)
        assertEquals("Fehlmeldung", fx.strings("SELECT dismissed_reason FROM taktovia.defect_case WHERE id=?", e.id).single())
        // Wiedereröffnung ist dokumentiert: Zähler, Historie, Begründung; danach weiter in der Hauptfolge
        val re = go(w, e.id, "tenant_admin", to(DefectState.REVIEWED, "Neue Hinweise", reopen = true))
        assertEquals("REVIEWED", re.status)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.defect_status_history WHERE defect_id=? AND is_reopening AND reason='Neue Hinweise' AND from_status='DISMISSED'", e.id))
        assertEquals("1", fx.strings("SELECT reopen_count::text FROM taktovia.defect_case WHERE id=?", e.id).single())
        assertEquals("IN_PROGRESS", go(w, e.id, "workshop", to(DefectState.IN_PROGRESS)).status)
    }

    @Test
    fun unzulaessige_uebergaenge_ergeben_409() {
        val w = DefectWorld(fx)
        val undefined = DefectStateMachine.MACHINE.undefinedTransitions()
        assertEquals(30 - 10, undefined.size)   // 6 Zustände: 30 mögliche Übergänge, 10 definiert
        for ((from, target) in undefined) {
            if (target == DefectState.NEW) continue   // NEW ist kein Ziel einer Anfrage (422 im Schema, nie ein Übergang)
            val id = w.caseIn(from.name, severity = "COSMETIC"); val before = w.revision(id)
            val p = problem { go(w, id, "workshop", to(target, "Begründung"), before) }
            assertEquals(ErrorCode.INVALID_STATE_TRANSITION, p.code, "$from -> $target")
            assertEquals(from.name, w.status(id), "$from -> $target ändert nichts"); assertEquals(before, w.revision(id))
        }
        // Rücksprung nach NEW ist als Ziel nicht darstellbar
        assertEquals(listOf("/to"), problem { DefectTransition.parse(mapOf("to" to "NEW")) }.violations.map { it.pointer })
        assertEquals(listOf("/to"), problem { DefectTransition.parse(mapOf("to" to "ERFUNDEN")) }.violations.map { it.pointer })
        // Endzustand ohne Wiedereröffnung: 409; Wiedereröffnung außerhalb eines Endzustands oder nicht nach REVIEWED: 409
        for (end in listOf(DefectState.CLOSED, DefectState.DISMISSED)) {
            val id = w.caseIn(end.name)
            assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { go(w, id, "workshop", to(DefectState.REVIEWED, "x")) }.code, "$end ohne Wiedereröffnung")
            assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { go(w, id, "workshop", to(DefectState.IN_PROGRESS, "x", reopen = true)) }.code)
        }
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { go(w, w.caseIn("NEW"), "workshop", to(DefectState.REVIEWED, "x", reopen = true)) }.code)
        // Wiedereröffnung: Begründung Pflicht, nur Werkstatt und Betriebsleitung
        val closed = w.caseIn("CLOSED")
        assertEquals(listOf("/reason"), problem { go(w, closed, "workshop", to(DefectState.REVIEWED, null, reopen = true)) }.violations.map { it.pointer })
        assertEquals(ErrorCode.FORBIDDEN, problem { go(w, closed, "dispatcher", to(DefectState.REVIEWED, "x", reopen = true)) }.code)
        assertEquals("CLOSED", w.status(closed))
    }

    @Test
    fun uebergang_ohne_rolle_403_veraltet_412_fremder_mandant_404() {
        val w = DefectWorld(fx); val id = w.caseIn("NEW")
        for (role in listOf("driver", "passenger", "support", "callcenter")) assertEquals(ErrorCode.FORBIDDEN, problem { go(w, id, role, to(DefectState.REVIEWED)) }.code, role)
        // Rolle ohne Recht für genau diesen Übergang: Disposition darf nicht verwerfen
        assertEquals(ErrorCode.FORBIDDEN, problem { go(w, id, "dispatcher", to(DefectState.DISMISSED, "x")) }.code)
        assertEquals(ErrorCode.FORBIDDEN, problem { go(w, id, "tenant_admin", to(DefectState.REVIEWED)) }.code)
        assertEquals("NEW", w.status(id))
        // veraltete Revision: 412, nichts geändert
        val rev = w.revision(id)
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { go(w, id, "dispatcher", to(DefectState.REVIEWED), rev + 1) }.code)
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { go(w, id, "dispatcher", to(DefectState.REVIEWED), 0) }.code)
        // Akte eines anderen Mandanten: 404 (auch mit gültiger Revision und passender Rolle)
        val other = DefectWorld(fx)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.defects.transition(other.ctx("workshop"), id, rev, to(DefectState.REVIEWED)) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.fleet.defects.statusHistory(other.ctx("workshop"), id) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { go(w, UUID.randomUUID(), "workshop", to(DefectState.REVIEWED), 1) }.code)
        assertEquals("NEW", w.status(id)); assertEquals(rev, w.revision(id))
        // Historie nicht für Fahrer
        assertEquals(ErrorCode.FORBIDDEN, problem { fx.fleet.defects.statusHistory(w.ctx("driver"), id) }.code)
    }

    @Test
    fun verworfen_ohne_grund_und_abschluss_sicherheitskritisch_ohne_freigabe_abgewiesen() {
        val w = DefectWorld(fx)
        val d = w.caseIn("REVIEWED", severity = "COSMETIC")
        assertEquals(listOf("/reason"), problem { go(w, d, "workshop", to(DefectState.DISMISSED)) }.violations.map { it.pointer })
        assertEquals(listOf("/reason"), problem { go(w, d, "workshop", to(DefectState.DISMISSED, "   ")) }.violations.map { it.pointer })
        assertEquals("REVIEWED", w.status(d))
        // sicherheitskritisch: ohne Reparatur nie abschließbar; nach Reparatur nur mit Freigabeentscheidung RELEASED
        val sk = w.caseIn("REVIEWED", severity = "SAFETY_CRITICAL")
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { go(w, sk, "workshop", to(DefectState.CLOSED)) }.code)
        val sk2 = w.caseIn("IN_PROGRESS", severity = "SAFETY_CRITICAL")
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { go(w, sk2, "workshop", to(DefectState.CLOSED)) }.code)
        val rep = w.caseIn("REPAIR", severity = "SAFETY_CRITICAL"); w.repairOrder(rep, "PROOF_RECORDED")
        val p = problem { go(w, rep, "tenant_admin", to(DefectState.CLOSED)) }
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, p.code); assertTrue(p.detail!!.contains("Freigabeentscheidung"), p.detail)
        assertEquals("REPAIR", w.status(rep))
        w.releaseDecision(rep, "RELEASED")
        assertEquals("CLOSED", go(w, rep, "tenant_admin", to(DefectState.CLOSED)).status)
        // nicht klassifiziert: kein Abschluss ohne Reparatur
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { go(w, w.caseIn("IN_PROGRESS"), "workshop", to(DefectState.CLOSED)) }.code)
        // Reparatur ohne Auftrag; Rückkehr aus der Reparatur nur nach Stornierung
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { go(w, w.caseIn("IN_PROGRESS", "COSMETIC"), "workshop", to(DefectState.REPAIR)) }.code)
        val open = w.caseIn("REPAIR", "COSMETIC"); w.repairOrder(open, "OPEN")
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { go(w, open, "workshop", to(DefectState.IN_PROGRESS)) }.code)
    }

    @Test
    fun abschluss_hebt_sperre_nicht_auf() {
        val w = DefectWorld(fx)
        val blockId = w.block()
        val closed = w.caseIn("REVIEWED", severity = "COSMETIC")
        assertEquals("CLOSED", go(w, closed, "workshop", to(DefectState.CLOSED)).status)
        val dismissed = w.caseIn("NEW")
        assertEquals("DISMISSED", go(w, dismissed, "workshop", to(DefectState.DISMISSED, "Fehlmeldung")).status)
        // die Sperre bleibt: Zeile ACTIVE, active_block gesetzt, safety_revision unverändert
        assertEquals("ACTIVE", fx.strings("SELECT status FROM taktovia.vehicle_block WHERE id=?", blockId).single())
        assertEquals("true", fx.strings("SELECT active_block::text FROM taktovia.vehicle WHERE id=?", w.vehicle).single())
        assertEquals("1", fx.strings("SELECT safety_revision::text FROM taktovia.vehicle WHERE id=?", w.vehicle).single())
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id=? AND status='ACTIVE'", w.vehicle))
        // das Modul schreibt über den Statusautomaten weder vehicle noch vehicle_block (Quelltextprüfung)
        val src = java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("taktovia.repoRoot"), "backend/modules/fleet-safety/src/main/kotlin/de/taktovia/fleetsafety/internal/JdbcDefects.kt"))
        assertFalse(Regex("(?i)(update|insert\\s+into|delete\\s+from)\\s+taktovia\\.(vehicle|vehicle_block|resource_release)\\b").containsMatchIn(src))
    }

    @Test
    fun zwei_gleichzeitige_uebergaenge_genau_einer() {
        val w = DefectWorld(fx)
        // zwei verschiedene Übergänge derselben Revision
        val d = w.caseIn("REVIEWED", severity = "COSMETIC"); val rev = w.revision(d)
        val pool = Executors.newFixedThreadPool(6); val start = CountDownLatch(1)
        val a = pool.submit<Result<Any>> { start.await(); runCatching { go(w, d, "workshop", to(DefectState.IN_PROGRESS), rev) } }
        val b = pool.submit<Result<Any>> { start.await(); runCatching { go(w, d, "workshop", to(DefectState.DISMISSED, "Fehlmeldung"), rev) } }
        start.countDown()
        val rs = listOf(a, b).map { it.get(60, TimeUnit.SECONDS) }
        assertEquals(1, rs.count { it.isSuccess }, rs.toString())
        assertEquals(ErrorCode.REVISION_MISMATCH, (rs.single { it.isFailure }.exceptionOrNull() as ProblemException).code)
        assertEquals(rev + 1, w.revision(d)); assertEquals(1, fx.count("SELECT count(*) FROM taktovia.defect_status_history WHERE defect_id=? AND revision=?", d, rev + 1))
        // derselbe Übergang sechsmal gleichzeitig: genau einer, ein Audit, ein Ereignis
        val e = w.caseIn("NEW"); val rev2 = w.revision(e); val start2 = CountDownLatch(1)
        val many = (1..6).map { pool.submit<Result<Any>> { start2.await(); runCatching { go(w, e, "dispatcher", to(DefectState.REVIEWED), rev2) } } }
        start2.countDown()
        val rs2 = many.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, rs2.count { it.isSuccess }, rs2.toString())
        assertTrue(rs2.filter { it.isFailure }.all { (it.exceptionOrNull() as ProblemException).code == ErrorCode.REVISION_MISMATCH })
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE object_ref=? AND action='fleet.defect.state_changed'", "defect:$e"))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE aggregate_id=? AND event_type='defect.state_changed'", e))
        assertEquals(rev2 + 1, w.revision(e))
    }
}
