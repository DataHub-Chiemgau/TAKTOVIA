package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.CreateRepairOrder
import de.taktovia.fleetsafety.api.DispatchRefusal
import de.taktovia.fleetsafety.api.DispatchVerdict
import de.taktovia.fleetsafety.api.IssueRelease
import de.taktovia.fleetsafety.api.ReleaseBlocker
import de.taktovia.fleetsafety.api.ReleaseOutcome
import de.taktovia.fleetsafety.api.ReleaseVehicle
import de.taktovia.fleetsafety.api.RepairOrderView
import de.taktovia.fleetsafety.api.RepairStatus
import de.taktovia.fleetsafety.api.RepairTransition
import de.taktovia.fleetsafety.api.TriageDefect
import de.taktovia.fleetsafety.api.DefectView
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-51: Reparaturauftrag und Wiederfreigabe mit Rollenprüfung gegen die echte Datenbank unter der Runtime-Rolle. Positiv: AT-60 (Reparaturnachweis, technische Freigabe mit Vieraugenpflicht und neue
 * Prüfung machen das Fahrzeug wieder disponierbar). Negativ: AT-35 (Werkstattabschluss ohne technische Freigabe lässt das Fahrzeug gesperrt), AT-51 (die Leitstelle entfernt eine kritische Sperre nicht ohne
 * Werkstattnachweis; Fehlversuch im Audit), Freigabe für fremdes Fahrzeug, ohne Nachweis, ohne Rolle, durch dieselbe Person zweimal. Konkurrierend: Freigabe parallel zu einer neuen kritischen Meldung
 * (das Fahrzeug bleibt gesperrt), zwei gleichzeitige Freigaben (eine Entscheidung).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepairReleaseTest {
    private lateinit var fx: FleetFixture
    private lateinit var w: TriageWorld
    private val api get() = fx.fleet

    @BeforeAll fun up() { fx = FleetFixture(); w = TriageWorld(fx) }
    @AfterAll fun down() = fx.close()

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)
    private fun workshop(subject: UUID = UUID.randomUUID(), world: TriageWorld = w) = world.ctx("workshop", subject = subject)
    private fun admin(world: TriageWorld = w) = world.ctx("tenant_admin")

    /** Fahrzeug mit gültiger Einsatzfreigabe, sicherheitskritischer Akte (Sperre), Reparaturauftrag mit Nachweis; [proof] = Nachweis erfasst. */
    private inner class Case(val world: TriageWorld, val r: ReadinessWorld.Ready, val defect: DefectView, var order: RepairOrderView, val evidence: UUID) {
        fun safetyRevision() = world.safetyRevision(r.vehicle)
        fun release(ctx: RequestContext, inspection: UUID, reason: String = "Bremsanlage erneuert und geprüft", second: UUID? = null, expected: Long = safetyRevision()) =
            api.vehicleReleases.release(ctx, r.vehicle, expected, ReleaseVehicle(order.id, inspection, reason, second))
    }

    private fun case(world: TriageWorld = w, proof: Boolean = true): Case {
        val r = world.released(); val dispatcher = world.ctx("dispatcher")
        val d = world.report(dispatcher, r.vehicle, "BRAKES", "MALFUNCTION", "WHEELS_TIRES", "NOT_ROADWORTHY")
        val inProgress = api.defects.triage(dispatcher, d.id, d.revision, TriageDefect("SAFETY_CRITICAL", "IN_PROGRESS", false, null, null)).defect
        var order = api.repairOrders.create(workshop(world = world), CreateRepairOrder(inProgress.id, "Werkstatt Nord", null, "KST-100"))
        val ev = world.evidence(d.id)
        order = api.repairOrders.transition(workshop(world = world), order.id, order.revision, RepairTransition(RepairStatus.IN_PROGRESS, null, null, null))
        if (proof) order = api.repairOrders.transition(workshop(world = world), order.id, order.revision, RepairTransition(RepairStatus.PROOF_RECORDED, ev, null, null))
        return Case(world, r, inProgress, order, ev)
    }

    private fun verdict(c: Case) = api.readiness.assertDispatchable(w.ctx("dispatcher"), c.r.vehicle, c.r.shift, null, Instant.now())
    private fun rejected(vehicle: UUID) = fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.release.attempt_rejected' AND object_ref=?", w.tenant.value, "vehicle:$vehicle")

    @Test
    fun reparaturnachweis_technische_freigabe_und_neue_pruefung_machen_das_fahrzeug_wieder_disponierbar() {
        val c = case(); val before = c.safetyRevision(); val a = UUID.randomUUID()
        assertEquals(RepairStatus.PROOF_RECORDED, c.order.status)
        assertEquals("REPAIR", api.defects.get(w.ctx("workshop"), c.defect.id).status)
        val follow = w.followUp(c.r)
        // erste (technische) Freigabe: ausstehend, die Sperre bleibt
        val first = c.release(workshop(a), follow)
        assertEquals("PENDING_SECOND_APPROVAL", first.decision); assertEquals("workshop", first.decidedByRole); assertTrue(first.liftedBlockIds.isEmpty())
        assertEquals(1, w.activeBlocks(c.r.vehicle).size); assertEquals(before, c.safetyRevision()); assertTrue(w.activeBlockFlag(c.r.vehicle))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.release_decision.recorded' AND object_ref=? AND redacted_changes->>'decision'='PENDING_SECOND_APPROVAL'", w.tenant.value, "vehicle:${c.r.vehicle}"))
        // dieselbe Person kann den zweiten Schritt nicht ausführen (Vieraugenpflicht), der Versuch ist ein Fehlversuch im Audit
        assertEquals(403, problem { c.release(workshop(a), follow) }.code.status)
        assertEquals(1, rejected(c.r.vehicle))
        // eine andere berechtigte Person bestätigt: Entscheidung RELEASED, Sperre dieser Akte aufgehoben, Revision erhöht, keine Einsatzfreigabe
        val releasesBefore = fx.count("SELECT count(*) FROM taktovia.resource_release WHERE vehicle_id = ? AND revoked_at IS NULL", c.r.vehicle)
        val second = c.release(admin(), follow)
        assertEquals("RELEASED", second.decision); assertEquals(first.decidedBy, second.decidedBy); assertNotEquals(second.decidedBy, second.secondApprover); assertEquals("tenant_admin", second.secondApproverRole)
        assertEquals(c.defect.id, second.defectId); assertEquals(c.evidence, second.proofEvidenceId); assertEquals(1, second.liftedBlockIds.size)
        assertEquals(before + 1, second.safetyRevision); assertEquals(before + 1, c.safetyRevision()); assertEquals(0, w.activeBlocks(c.r.vehicle).size); assertFalse(w.activeBlockFlag(c.r.vehicle))
        assertEquals(releasesBefore, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE vehicle_id = ? AND revoked_at IS NULL", c.r.vehicle), "die Entscheidung erteilt selbst keine Einsatzfreigabe")
        assertTrue(verdict(c) is DispatchVerdict.Refused, "ohne neue Prüfung nicht disponierbar")
        // eine vor der Aufhebung abgeschlossene Prüfung begründet keine Einsatzfreigabe; erst die neue Prüfung nach der Entscheidung
        val older = w.rw.inspection(c.r.shift, c.r.vehicle, c.r.driver)
        val refused = api.readiness.issueRelease(w.rw.safetyService(), IssueRelease(c.r.vehicle, c.r.shift, older))
        assertTrue(refused is ReleaseOutcome.Refused && ReleaseBlocker.NO_INSPECTION in refused.missing, refused.toString())
        Thread.sleep(20)
        val fresh = w.followUp(c.r)   // neue Prüfung nach der Entscheidung (Art unerheblich für die Einsatzfreigabe, maßgeblich ist der Abschluss nach der Aufhebung)
        assertTrue(api.readiness.issueRelease(w.rw.safetyService(), IssueRelease(c.r.vehicle, c.r.shift, fresh)) is ReleaseOutcome.Issued)
        assertTrue(verdict(c) is DispatchVerdict.Dispatchable, "Reparatur, technische Freigabe und neue Prüfung liegen vor")
        // Wiederholung nach abgeschlossener Entscheidung: dieselbe Entscheidung, keine zweite
        val again = c.release(admin(), follow, expected = c.safetyRevision())
        assertEquals(second.id, again.id); assertTrue(again.replayed)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle_release_decision WHERE repair_order_id = ? AND decision = 'RELEASED'", c.order.id))
        // Audit und Ereignis der Entscheidung, Auftrag und Akte bleiben unverändert; die Entscheidung ist unveränderlich
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='fleet.release_decided' AND payload->>'decision'='RELEASED' AND payload->>'vehicle_id'=?", w.tenant.value, c.r.vehicle.toString()))
        for (sql in listOf("UPDATE taktovia.vehicle_release_decision SET decision='REJECTED' WHERE id = ?", "DELETE FROM taktovia.vehicle_release_decision WHERE id = ?")) assertThrows(Exception::class.java) { fx.exec(sql, second.id) }
        // der Schadenfall ist danach abschließbar (sicherheitskritisch nur mit Freigabeentscheidung)
        assertEquals("CLOSED", api.defects.transition(workshop(), c.defect.id, api.defects.get(w.ctx("workshop"), c.defect.id).revision, de.taktovia.fleetsafety.api.DefectTransition(de.taktovia.fleetsafety.api.DefectState.CLOSED, null, false)).status)
    }

    @Test
    fun werkstattabschluss_ohne_technische_freigabe_laesst_das_fahrzeug_gesperrt() {
        val c = case()
        assertEquals(RepairStatus.PROOF_RECORDED, c.order.status)
        assertEquals(1, w.activeBlocks(c.r.vehicle).size); assertTrue(w.activeBlockFlag(c.r.vehicle)); assertTrue(verdict(c) is DispatchVerdict.Refused)
        // auch die erste, noch nicht bestätigte Freigabe hebt nichts auf
        c.release(workshop(), w.followUp(c.r))
        assertEquals(1, w.activeBlocks(c.r.vehicle).size); assertTrue(w.activeBlockFlag(c.r.vehicle))
        val v = verdict(c) as DispatchVerdict.Refused
        assertTrue(DispatchRefusal.VEHICLE_BLOCKED in v.reasons, v.reasons.toString())
        // Akte kann nicht abgeschlossen werden, solange die Freigabeentscheidung fehlt
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { api.defects.transition(workshop(), c.defect.id, api.defects.get(w.ctx("workshop"), c.defect.id).revision, de.taktovia.fleetsafety.api.DefectTransition(de.taktovia.fleetsafety.api.DefectState.CLOSED, null, false)) }.code)
    }

    @Test
    fun leitstelle_kann_sicherheitssperre_nicht_ohne_werkstattnachweis_entfernen() {
        val c = case(proof = false); val dispatcher = w.ctx("dispatcher"); val blockId = api.readiness.activeBlocks(dispatcher, c.r.vehicle).single().id
        // (1) Aufheben der Sperre als operative Sperre: serverseitig verhindert, Fehlversuch im Audit und als Ereignis
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { api.readiness.liftOperationalBlock(dispatcher, c.r.vehicle, blockId) }.code)
        assertEquals(1, rejected(c.r.vehicle)); assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='fleet.release_attempt_rejected' AND payload->>'vehicle_id'=?", w.tenant.value, c.r.vehicle.toString()))
        // (2) Freigabe ohne Rolle (Disposition) und ohne Nachweis: 403 bzw. 409, jeweils protokolliert
        assertEquals(403, problem { c.release(dispatcher, w.followUp(c.r)) }.code.status)
        assertEquals(2, rejected(c.r.vehicle))
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { c.release(workshop(), w.followUp(c.r)) }.code)   // Auftrag noch nicht im Status PROOF_RECORDED
        assertEquals(3, rejected(c.r.vehicle))
        // (3) die Betriebsleitung allein erteilt keine erste Freigabe; Sperre über den Kern ohne Entscheidung: 409
        val proven = case(); assertEquals(403, problem { proven.release(admin(), w.followUp(proven.r)) }.code.status)
        assertEquals(ErrorCode.CONFLICT, problem { api.readiness.liftSafetyBlock(workshop(), proven.r.vehicle, api.readiness.activeBlocks(dispatcher, proven.r.vehicle).single().id, UUID.randomUUID()) }.code)
        for (x in listOf(c, proven)) { assertEquals(1, w.activeBlocks(x.r.vehicle).size); assertTrue(w.activeBlockFlag(x.r.vehicle)); assertTrue(verdict(x) is DispatchVerdict.Refused) }
        // die Fehlversuche tragen weder Fallinhalt noch Begründungstext
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.release.attempt_rejected' AND redacted_changes::text LIKE '%Bremsanlage%'", w.tenant.value))
    }

    @Test
    fun jede_fehlende_bedingung_haelt_die_sperre() {
        val c = case(); val ok = w.followUp(c.r)
        fun refuse(inspection: UUID, pointer: String) {
            val p = problem { c.release(workshop(), inspection) }
            assertEquals(ErrorCode.INVALID_STATE_TRANSITION, p.code); assertEquals(pointer, p.violations.single().pointer)
            assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle_release_decision WHERE repair_order_id = ?", c.order.id)); assertEquals(1, w.activeBlocks(c.r.vehicle).size)
        }
        refuse(w.followUp(c.r, outcome = "BLOCKED"), "/follow_up_inspection_id")                                    // nicht bestanden
        refuse(w.followUp(c.r, status = "IN_PROGRESS"), "/follow_up_inspection_id")                                 // nicht abgeschlossen
        refuse(w.followUp(c.r, vehicle = w.rw.vehicle()), "/follow_up_inspection_id")                               // anderes Fahrzeug
        refuse(w.followUp(c.r, completedAt = Instant.now().minusSeconds(3600)), "/follow_up_inspection_id")         // vor dem Reparaturnachweis abgeschlossen
        refuse(UUID.randomUUID(), "/follow_up_inspection_id")                                                       // unbekannt
        // Reparaturauftrag ohne Nachweis, abgebrochen oder fremd
        val noProof = case(proof = false); val p = problem { noProof.release(workshop(), w.followUp(noProof.r)) }
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, p.code); assertEquals("/repair_order_id", p.violations.single().pointer)
        // alles vorhanden: gelingt (erste Entscheidung)
        assertEquals("PENDING_SECOND_APPROVAL", c.release(workshop(), ok).decision)
    }

    @Test
    fun freigabe_fuer_fremdes_fahrzeug_ohne_rolle_veraltet_und_ungueltig() {
        val c = case(); val foreign = TriageWorld(fx); val fc = case(foreign)
        // fremder Mandant: 404 (kein Hinweis), auch mit vorhandener Auftragskennung; Auftrag eines anderen Fahrzeugs: 404
        assertEquals(404, problem { api.vehicleReleases.release(workshop(world = foreign), c.r.vehicle, 1, ReleaseVehicle(c.order.id, w.followUp(c.r), "x", null)) }.code.status)
        assertEquals(404, problem { api.vehicleReleases.release(workshop(), fc.r.vehicle, 1, ReleaseVehicle(c.order.id, w.followUp(c.r), "x", null)) }.code.status)
        assertEquals(404, problem { api.vehicleReleases.release(workshop(), c.r.vehicle, c.safetyRevision(), ReleaseVehicle(UUID.randomUUID(), w.followUp(c.r), "x", null)) }.code.status)
        // Rollen: Fahrer, Fahrgast, Disposition, Leitstelle ohne Rolle, Dienstkonto
        for (role in listOf("driver", "passenger", "dispatcher", "planner")) assertEquals(403, problem { c.release(w.ctx(role), w.followUp(c.r)) }.code.status, role)
        assertEquals(403, problem { c.release(w.rw.safetyService(), w.followUp(c.r)) }.code.status)
        // veraltete Sicherheitsrevision (If-Match): 412; zweite Person darf nicht die erste sein; benannte zweite Person ist nie die erste selbst
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { c.release(workshop(), w.followUp(c.r), expected = c.safetyRevision() + 3) }.code)
        val me = UUID.randomUUID()
        assertEquals("/second_approver_ref", problem { c.release(workshop(me), w.followUp(c.r), second = me) }.violations.single().pointer)
        val named = UUID.randomUUID(); val follow = w.followUp(c.r)
        assertEquals("PENDING_SECOND_APPROVAL", c.release(workshop(me), follow, second = named).decision)
        assertEquals(403, problem { c.release(admin(), follow) }.code.status, "eine andere Person als die benannte zweite")
        assertEquals("RELEASED", c.release(w.ctx("tenant_admin", subject = named), follow).decision)
        // Eingabevalidierung
        assertEquals(setOf("/reason", "/follow_up_inspection_id"), problem { ReleaseVehicle.parse(mapOf("repair_order_id" to UUID.randomUUID().toString())) }.violations.map { it.pointer }.toSet())
        assertEquals(ViolationCode.UNKNOWN_FIELD, problem { ReleaseVehicle.parse(mapOf("repair_order_id" to UUID.randomUUID().toString(), "follow_up_inspection_id" to UUID.randomUUID().toString(), "reason" to "x", "decision" to "RELEASED")) }.violations.single().code)
    }

    @Test
    fun freigabe_parallel_zu_neuer_kritischer_meldung_bleibt_das_fahrzeug_gesperrt() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(8) { i ->
                val c = case(); val follow = w.followUp(c.r); c.release(workshop(), follow)
                val expected = c.safetyRevision(); val start = CountDownLatch(1)
                val release = pool.submit<Result<Unit>> { start.await(); Thread.sleep((i % 4) * 7L); runCatching { c.release(admin(), follow, expected = expected); Unit } }
                val report = pool.submit<DefectView> { start.await(); w.report(w.ctx("dispatcher"), c.r.vehicle, "TIRES_WHEELS", "CRACK_BREAK", "WHEELS_TIRES", "NOT_ROADWORTHY") }
                start.countDown(); val r = release.get(30, TimeUnit.SECONDS); val newDefect = report.get(30, TimeUnit.SECONDS)
                // in jeder Reihenfolge: die neue Sperre besteht, das Fahrzeug ist gesperrt und nicht disponierbar
                val blocks = w.activeBlocks(c.r.vehicle)
                assertTrue(blocks.any { it.endsWith("|${newDefect.caseNumber}") }, "Lauf $i: Sperre der neuen Meldung besteht ($blocks)")
                assertTrue(w.activeBlockFlag(c.r.vehicle)); assertTrue(verdict(c) is DispatchVerdict.Refused, "Lauf $i")
                if (r.isFailure) assertEquals(ErrorCode.REVISION_MISMATCH, (r.exceptionOrNull() as ProblemException).code, "Lauf $i: die Freigabe gegen den veralteten Sicherheitsstand scheitert mit 412")
                else assertFalse(blocks.any { it.endsWith("|${c.defect.caseNumber}") }, "Lauf $i: die Freigabe hob nur die Sperre ihrer Akte auf")
            }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun zwei_gleichzeitige_freigaben_eine_entscheidung() {
        val pool = Executors.newFixedThreadPool(6)
        try {
            // dieselbe Person sechsmal: genau eine ausstehende Entscheidung, die übrigen werden abgewiesen
            val c = case(); val follow = w.followUp(c.r); val me = UUID.randomUUID(); val start = CountDownLatch(1)
            val same = (1..6).map { pool.submit<Result<Unit>> { start.await(); runCatching { c.release(workshop(me), follow); Unit } } }
            start.countDown(); val results = same.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess }, "genau eine Entscheidung"); assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle_release_decision WHERE repair_order_id = ?", c.order.id))
            assertTrue(results.filter { it.isFailure }.all { (it.exceptionOrNull() as ProblemException).code.status in setOf(403, 409) })
            assertEquals(1, w.activeBlocks(c.r.vehicle).size)
            // zwei verschiedene Personen gleichzeitig aus dem Anfangszustand: eine abgeschlossene Entscheidung, die Sperre wird genau einmal aufgehoben
            val d = case(); val dFollow = w.followUp(d.r); val rev = d.safetyRevision(); val go = CountDownLatch(1)
            val two = listOf(workshop(), admin()).mapIndexed { i, ctx -> pool.submit<Result<String>> { go.await(); Thread.sleep(i * 3L); runCatching { d.release(if (i == 0) ctx else workshop(), dFollow, expected = rev).decision } } }
            go.countDown(); val outs = two.map { it.get(30, TimeUnit.SECONDS) }
            assertTrue(outs.all { it.isSuccess }, outs.toString())
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle_release_decision WHERE repair_order_id = ? AND decision = 'RELEASED'", d.order.id))
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle_release_decision WHERE repair_order_id = ? AND decision = 'PENDING_SECOND_APPROVAL'", d.order.id))
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ? AND status = 'LIFTED'", d.r.vehicle)); assertEquals(rev + 1, d.safetyRevision())
        } finally { pool.shutdownNow() }
    }

    @Test
    fun reparaturauftrag_nur_aus_in_arbeit_mit_nachweis_und_stornierung() {
        val dispatcher = w.ctx("dispatcher"); val r = w.released()
        val d = w.report(dispatcher, r.vehicle, "LIGHTS", "MALFUNCTION", "FRONT", "FUNCTION_LIMITED")
        val create = CreateRepairOrder(d.id, "Werkstatt Süd", null, "KST-7")
        // Akte im Status NEW: 409; Rollen: nur die Werkstatt; fremder Mandant 404
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { api.repairOrders.create(workshop(), create) }.code)
        assertEquals(403, problem { api.repairOrders.create(dispatcher, create) }.code.status); assertEquals(403, problem { api.repairOrders.create(admin(), create) }.code.status)
        val inProgress = api.defects.triage(dispatcher, d.id, d.revision, TriageDefect("OPERATIONALLY_RELEVANT", "IN_PROGRESS", false, null, null)).defect
        assertEquals(404, problem { api.repairOrders.create(workshop(world = TriageWorld(fx)), create) }.code.status)
        val order = api.repairOrders.create(workshop(), create)
        assertEquals(RepairStatus.OPEN, order.status); assertEquals(d.id, order.defectId); assertEquals(r.vehicle, order.vehicleId); assertEquals("KST-7", order.costReference)
        assertEquals("REPAIR", api.defects.get(w.ctx("workshop"), d.id).status, "die Akte geht mit dem Auftrag auf REPAIR")
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='repair.order_created' AND aggregate_id=?", w.tenant.value, order.id))
        assertEquals(inProgress.revision + 1, api.defects.get(w.ctx("workshop"), d.id).revision)
        // zweiter aktiver Auftrag: 409; Übergänge nur nach der Tabelle; Nachweis nur mit Evidenz dieser Akte und Kostenbezug
        assertEquals(409, problem { api.repairOrders.create(workshop(), create) }.code.status)
        val ws = workshop()
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { api.repairOrders.transition(ws, order.id, order.revision, RepairTransition(RepairStatus.PROOF_RECORDED, w.evidence(d.id), null, null)) }.code)
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { api.repairOrders.transition(ws, order.id, order.revision + 4, RepairTransition(RepairStatus.IN_PROGRESS, null, null, null)) }.code)
        assertEquals(403, problem { api.repairOrders.transition(dispatcher, order.id, order.revision, RepairTransition(RepairStatus.IN_PROGRESS, null, null, null)) }.code.status)
        val running = api.repairOrders.transition(ws, order.id, order.revision, RepairTransition(RepairStatus.IN_PROGRESS, null, null, null))
        assertEquals("/proof_evidence_id", problem { api.repairOrders.transition(ws, order.id, running.revision, RepairTransition(RepairStatus.PROOF_RECORDED, null, null, null)) }.violations.single().pointer)
        val otherDefect = w.report(dispatcher, w.rw.vehicle(), "BODY")
        assertEquals("/proof_evidence_id", problem { api.repairOrders.transition(ws, order.id, running.revision, RepairTransition(RepairStatus.PROOF_RECORDED, w.evidence(otherDefect.id), null, null)) }.violations.single().pointer)
        val proven = api.repairOrders.transition(ws, order.id, running.revision, RepairTransition(RepairStatus.PROOF_RECORDED, w.evidence(d.id), "KST-8", null))
        assertEquals(RepairStatus.PROOF_RECORDED, proven.status); assertEquals("KST-8", proven.costReference); assertTrue(proven.proofRecordedAt != null)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='repair.proof_recorded' AND aggregate_id=?", w.tenant.value, order.id))
        // Nachweis ist endgültig (IMMUTABLE_RECORD), Stornierung nur mit Grund und nur vor dem Nachweis; danach fällt die Akte in die Bearbeitung zurück
        assertEquals(ErrorCode.IMMUTABLE_RECORD, problem { api.repairOrders.transition(ws, order.id, proven.revision, RepairTransition(RepairStatus.IN_PROGRESS, null, null, null)) }.code)
        val d2 = w.report(dispatcher, w.rw.vehicle(), "LIGHTS", "MALFUNCTION", "FRONT", "FUNCTION_LIMITED")
        api.defects.triage(dispatcher, d2.id, d2.revision, TriageDefect("OPERATIONALLY_RELEVANT", "IN_PROGRESS", false, null, null))
        val o2 = api.repairOrders.create(ws, CreateRepairOrder(d2.id, null, null, null))
        assertEquals("/reason", problem { api.repairOrders.transition(ws, o2.id, o2.revision, RepairTransition(RepairStatus.CANCELED, null, null, null)) }.violations.single().pointer)
        val canceled = api.repairOrders.transition(ws, o2.id, o2.revision, RepairTransition(RepairStatus.CANCELED, null, null, "Werkstatt abgesagt"))
        assertEquals(RepairStatus.CANCELED, canceled.status); assertEquals("IN_PROGRESS", api.defects.get(w.ctx("workshop"), d2.id).status)
        assertEquals("/cost_reference", problem { val o3 = api.repairOrders.create(ws, CreateRepairOrder(d2.id, null, null, null)); val run = api.repairOrders.transition(ws, o3.id, o3.revision, RepairTransition(RepairStatus.IN_PROGRESS, null, null, null)); api.repairOrders.transition(ws, o3.id, run.revision, RepairTransition(RepairStatus.PROOF_RECORDED, w.evidence(d2.id), null, null)) }.violations.single().pointer)
        // Lesen: Filter, Rollen, fremder Mandant
        assertEquals(listOf(order.id), api.repairOrders.list(dispatcher, d.id, null, 10, null).map { it.id }); assertEquals(setOf(order.id), api.repairOrders.list(admin(), null, r.vehicle, 10, null).map { it.id }.toSet())
        assertEquals(403, problem { api.repairOrders.list(w.ctx("driver"), null, null, 10, null) }.code.status); assertEquals(emptyList<UUID>(), api.repairOrders.list(TriageWorld(fx).ctx("dispatcher"), d.id, null, 10, null).map { it.id })
        assertEquals(404, problem { api.repairOrders.get(TriageWorld(fx).ctx("dispatcher"), order.id) }.code.status); assertEquals(proven.revision, api.repairOrders.get(dispatcher, order.id).revision)
    }
}
