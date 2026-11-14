package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.BlockReason
import de.taktovia.fleetsafety.api.BlockSource
import de.taktovia.fleetsafety.api.DispatchRefusal
import de.taktovia.fleetsafety.api.DispatchVerdict
import de.taktovia.fleetsafety.api.IssueRelease
import de.taktovia.fleetsafety.api.ReleaseBlocker
import de.taktovia.fleetsafety.api.ReleaseGate
import de.taktovia.fleetsafety.api.ReleaseOutcome
import de.taktovia.fleetsafety.api.SetBlock
import de.taktovia.fleetsafety.api.SwitchActorRole
import de.taktovia.fleetsafety.api.SwitchReason
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-22 / LH-6.21-CHK-015 (Fahrzeugwechsel in laufender Schicht) unter der Runtime-Rolle gegen die echte Datenbank, ohne Mocks (einziger Stellvertreter: der Port zur Schichtzuweisung, siehe
 * [VehicleSwitchWorld]). Positiv: Wechsel, neue fahrzeugspezifische Prüfung, danach Freigabe (AT-30). Negativ: Bereitmeldung ohne neue Prüfung, Prüfung des alten Fahrzeugs, fehlende Qualifikation,
 * gesperrtes Ersatzfahrzeug, fremde Schicht, widerrufene Freigabe, Audit. Konkurrierend: Zuweisung, Prüfabschluss gegen Sperre, zwei Wechsel derselben Schicht.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VehicleSwitchTest {
    private lateinit var fx: FleetFixture
    private lateinit var w: VehicleSwitchWorld

    @BeforeAll fun up() { fx = FleetFixture(); w = VehicleSwitchWorld(fx) }
    @AfterAll fun down() = fx.close()

    private val readiness get() = fx.fleet.readiness
    private fun verdict(vehicle: UUID, shift: UUID) = readiness.assertDispatchable(w.dispatcher(), vehicle, shift, null, Instant.now())
    private fun refusals(vehicle: UUID, shift: UUID) = (verdict(vehicle, shift) as? DispatchVerdict.Refused)?.reasons ?: emptyList()
    private fun status(f: () -> Any?): Int? = try { f(); null } catch (e: ProblemException) { e.code.status }

    /** Die Freigabe wird nicht erteilt: abgewiesen mit fehlender Voraussetzung, oder (Prüflauf gehört schon zu einer Freigabe anderer Art) 409. */
    private fun notIssued(f: () -> ReleaseOutcome) {
        try { assertTrue(f() is ReleaseOutcome.Refused, "Freigabe darf nicht erteilt werden") } catch (e: ProblemException) { assertEquals(409, e.code.status) }
    }

    @Test fun ersatzfahrzeug_bleibt_bis_pruefung_gesperrt() {
        val r = w.released()
        assertTrue(verdict(r.vehicle, r.shift) is DispatchVerdict.Dispatchable, "Ausgangslage: Fahrzeug freigegeben")
        val to = w.replacement()
        val sw = w.switch(r, to)
        assertEquals(r.vehicle, sw.fromVehicleId); assertEquals(to, sw.toVehicleId); assertEquals(SwitchActorRole.DRIVER, sw.actorRole); assertEquals(1, sw.revokedReleases)
        assertEquals(to, w.shiftVehicle(r.shift)); assertEquals("STARTED", fx.strings("SELECT state FROM taktovia.staff_shift WHERE id = ?", r.shift).single(), "dieselbe Schicht, weiter angetreten")
        // keine Vorabfreigabe: Ersatzfahrzeug nicht disponierbar, Freigabestand „nicht freigegeben“ mit fehlender Prüfung; das alte Fahrzeug ist für diese Schicht nicht mehr disponierbar
        assertTrue(DispatchRefusal.NO_RELEASE in refusals(to, r.shift))
        val req = readiness.requirements(w.dispatcher(), to, r.shift)
        assertEquals(ReleaseGate.NOT_RELEASED, req.gate); assertEquals(listOf(ReleaseBlocker.NO_INSPECTION), req.missing.map { it.code })
        assertTrue(DispatchRefusal.NO_VALID_SHIFT in refusals(r.vehicle, r.shift))
        // neue fahrzeugspezifische Prüfung, danach Freigabe des Ersatzfahrzeugs (AT-30)
        val done = w.inspect(r, to)
        assertEquals(to, done.vehicleId)
        assertTrue(verdict(to, r.shift) is DispatchVerdict.Dispatchable)
        assertEquals(ReleaseGate.RELEASED, readiness.requirements(w.dispatcher(), to, r.shift).gate)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE shift_id = ? AND vehicle_id = ? AND revoked_at IS NULL", r.shift, to))
    }

    @Test fun bereitmeldung_ohne_neue_pruefung_abgewiesen() {
        val r = w.released()
        val to = w.replacement()
        w.switch(r, to)
        val service = w.rw.safetyService()
        // die Prüfung des alten Fahrzeugs trägt weder für das Ersatzfahrzeug noch (die Schicht fährt es nicht mehr) für das alte
        notIssued { readiness.issueRelease(service, IssueRelease(to, r.shift, r.inspection.id)) }
        notIssued { readiness.issueRelease(service, IssueRelease(r.vehicle, r.shift, r.inspection.id)) }
        // auch eine abgeschlossene, aber nicht zum Ersatzfahrzeug gehörende Prüfung (ohne eigene Freigabe) trägt nicht
        val foreign = w.rw.inspection(r.shift, r.vehicle, r.driver)
        val forForeign = readiness.issueRelease(service, IssueRelease(to, r.shift, foreign))
        assertTrue(forForeign is ReleaseOutcome.Refused && ReleaseBlocker.NO_INSPECTION in forForeign.missing, "$forForeign")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE shift_id = ? AND vehicle_id = ? AND revoked_at IS NULL", r.shift, to))
        // ein offener (nicht abgeschlossener) Lauf des Ersatzfahrzeugs genügt nicht
        val started = w.iw.start(r.ctx, r.shift, to, "VEHICLE_CHANGE").inspection
        assertEquals(listOf(ReleaseBlocker.INSPECTION_OPEN), readiness.requirements(w.dispatcher(), to, r.shift).missing.map { it.code })
        val open = readiness.issueRelease(service, IssueRelease(to, r.shift, started.id))
        assertTrue(open is ReleaseOutcome.Refused && ReleaseBlocker.INSPECTION_OPEN in open.missing, "$open")
        assertTrue(DispatchRefusal.NO_RELEASE in refusals(to, r.shift))
    }

    @Test fun pruefung_altes_fahrzeug_gilt_nicht_fuer_ersatzfahrzeug() {
        val r = w.released()
        val to = w.replacement()
        // eine bestandene, abgeschlossene Prüfung des alten Fahrzeugs ohne eigene Freigabe, angelegt vor den Wechseln
        val older = w.rw.inspection(r.shift, r.vehicle, r.driver)
        w.switch(r, to)
        w.inspect(r, to)
        assertTrue(verdict(to, r.shift) is DispatchVerdict.Dispatchable)
        // Rückwechsel auf das alte Fahrzeug: die frühere Prüfung (vor den beiden Wechseln) trägt nicht, auch nicht über die schon vorhandene Freigabe-Historie
        w.switch(r, r.vehicle, SwitchReason.SERVICE_NEED)
        assertEquals(r.vehicle, w.shiftVehicle(r.shift))
        notIssued { readiness.issueRelease(w.rw.safetyService(), IssueRelease(r.vehicle, r.shift, r.inspection.id)) }
        // eine vor dem Wechsel abgeschlossene, bestandene Prüfung des alten Fahrzeugs ohne eigene Freigabe trägt nach dem Rückwechsel ebenfalls nicht (Prüflauf älter als der jüngste Wechsel)
        val stale = readiness.issueRelease(w.rw.safetyService(), IssueRelease(r.vehicle, r.shift, older))
        assertTrue(stale is ReleaseOutcome.Refused && ReleaseBlocker.NO_INSPECTION in stale.missing, "$stale")
        assertFalse(verdict(r.vehicle, r.shift) is DispatchVerdict.Dispatchable)
        // erst eine neue Prüfung nach dem Rückwechsel gibt das alte Fahrzeug wieder frei
        w.inspect(r, r.vehicle)
        assertTrue(verdict(r.vehicle, r.shift) is DispatchVerdict.Dispatchable)
        assertEquals(4, fx.count("SELECT count(*) FROM taktovia.inspection_run WHERE shift_id = ? AND status = 'COMPLETED'", r.shift))
    }

    @Test fun wechsel_ohne_qualifikation_abgewiesen() {
        // Qualifikationsart je Fahrzeugtyp hinterlegt (Planungsannahme): KLEINBUS verlangt „KLASSE_D“; der Fahrer hält nur „PBEFG“
        fx.exec("INSERT INTO taktovia.vehicle_type_qualification(tenant_id,vehicle_type,qualification_code) VALUES (?,'KLEINBUS','KLASSE_D')", w.tenant.value)
        val r = w.released()
        val bus = w.iw.vehicle("KLEINBUS")
        val e = assertThrows(ProblemException::class.java) { w.switch(r, bus) }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code)
        assertFalse(e.message.orEmpty().contains(r.driver.toString()), "neutrale Meldung ohne Personendaten")
        // nichts geändert: Fahrzeug, Freigabe, keine Wechselzeile
        assertEquals(r.vehicle, w.shiftVehicle(r.shift))
        assertTrue(verdict(r.vehicle, r.shift) is DispatchVerdict.Dispatchable)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.shift_vehicle_switch WHERE shift_id = ?", r.shift))
        // mit der verlangten Qualifikation ist derselbe Wechsel möglich
        w.rw.qualify(r.driver, revision = 1, seq = 2, code = "KLASSE_D")
        w.switch(r, bus)
        assertEquals(bus, w.shiftVehicle(r.shift))
        // ein Fahrer ohne jede gültige Qualifikation wird auch für einen Typ ohne Zuordnung abgewiesen
        val none = w.rw.startedRun(qualified = false)
        assertEquals(422, status { w.switches.switchVehicle(none.ctx, de.taktovia.fleetsafety.api.SwitchVehicle(none.shift, none.vehicle, w.replacement(), SwitchReason.BREAKDOWN)) })
    }

    @Test fun wechsel_auf_gesperrtes_fahrzeug_abgewiesen() {
        val r = w.released()
        val to = w.replacement()
        fx.fleet.readiness.setBlock(w.dispatcher(), SetBlock(to, BlockSource.OPERATIONS, BlockReason.MANUAL))
        val e = assertThrows(ProblemException::class.java) { w.switch(r, to) }
        assertEquals(ErrorCode.CONFLICT, e.code); assertTrue(e.detail.orEmpty().startsWith("VEHICLE_BLOCKED"))
        assertEquals(r.vehicle, w.shiftVehicle(r.shift))
        assertTrue(verdict(r.vehicle, r.shift) is DispatchVerdict.Dispatchable, "die Freigabe des alten Fahrzeugs bleibt bei abgewiesenem Wechsel bestehen")
        // inaktives Ersatzfahrzeug
        val inactive = w.replacement(); fx.exec("UPDATE taktovia.vehicle SET active = false WHERE id = ?", inactive)
        assertEquals(422, status { w.switch(r, inactive) })
        // das Fahrzeug der Schicht selbst, und ein veralteter Ausgangsstand
        assertEquals(409, status { w.switch(r, r.vehicle) })
        assertEquals(409, status { w.switch(r, w.replacement(), from = UUID.randomUUID()) })
    }

    @Test fun alte_freigabe_widerrufen_und_wechsel_im_audit() {
        val r = w.released()
        val to = w.replacement()
        val oldRelease = fx.strings("SELECT id::text FROM taktovia.resource_release WHERE shift_id = ? AND vehicle_id = ?", r.shift, r.vehicle).single()
        val inspectionsBefore = fx.strings("SELECT id::text || ':' || vehicle_id::text || ':' || status || ':' || outcome || ':' || revision FROM taktovia.inspection_run WHERE shift_id = ? ORDER BY id", r.shift)
        val sw = w.switch(r, to, SwitchReason.DEFECT, "Reifen platt")
        // alte Freigabe widerrufen (nicht gelöscht, nicht verändert außer dem Widerruf), trägt nicht für das Ersatzfahrzeug
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE id = ?::uuid AND revoked_at IS NOT NULL AND revoke_reason = 'VEHICLE_CHANGED' AND revoked_by LIKE 'user:%'", oldRelease))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE vehicle_id = ? AND shift_id = ?", to, r.shift))
        // Prüfhistorie beider Fahrzeuge unverändert
        assertEquals(inspectionsBefore, fx.strings("SELECT id::text || ':' || vehicle_id::text || ':' || status || ':' || outcome || ':' || revision FROM taktovia.inspection_run WHERE shift_id = ? ORDER BY id", r.shift))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.inspection_run WHERE vehicle_id = ?", to))
        // Audit: Akteur, Begründung, altes und neues Fahrzeug; Freigabewiderruf mit Grund
        val audit = fx.strings("SELECT redacted_changes::text FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'fleet.shift.vehicle_switched' AND object_ref = ?", w.tenant.value, "staff_shift:${r.shift}").single()
        listOf(r.vehicle.toString(), to.toString(), "\"reason_code\": \"DEFECT\"", "Reifen platt", "\"actor_role\": \"DRIVER\"", sw.id.toString()).forEach { assertTrue(audit.contains(it), "Audit enthält $it: $audit") }
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'fleet.shift.vehicle_switched' AND object_ref = ? AND actor_ref = ?", w.tenant.value, "staff_shift:${r.shift}", "user:" + r.driver))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'fleet.release.revoked' AND object_ref = ?", w.tenant.value, "resource_release:$oldRelease"))
        // Ereignis ohne Freitext
        val payload = fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'fleet.vehicle_switched' AND aggregate_id = ?", w.tenant.value, sw.id).single()
        assertFalse(payload.contains("Reifen") || payload.contains("note"), payload)
        assertTrue(payload.contains(to.toString()) && payload.contains("\"reason_code\": \"DEFECT\""))
        // Historie lesbar (Fahrer eigener Schicht und Leitstelle)
        assertEquals(listOf(sw.id), w.switches.history(r.ctx, r.shift).map { it.id }); assertEquals(listOf(sw.id), w.switches.history(w.dispatcher(), r.shift).map { it.id })
    }

    @Test fun zugriff_rollen_mandanten_und_eingaben() {
        val r = w.released()
        val to = w.replacement()
        // fremder Fahrer: 404 ohne Hinweis; Rolle ohne Berechtigung: 403; fremder Mandant: 404; Dienstkonto: 403
        val other = w.iw.ctx(UUID.randomUUID(), "driver")
        assertEquals(404, status { w.switch(r, to, ctx = other) })
        assertEquals(403, status { w.switch(r, to, ctx = w.rw.ctx("support")) })
        val foreign = FleetFixture.World(fx.world(1).tenant, emptyList(), UUID.randomUUID())
        assertEquals(404, status { w.switch(r, to, ctx = w.rw.ctx("dispatcher", tenant = foreign.tenant)) })
        assertEquals(403, status { w.switch(r, to, ctx = w.rw.ctx("dispatcher", service = true)) })
        assertEquals(404, status { w.switch(r, UUID.randomUUID()) }, "unbekanntes Ersatzfahrzeug")
        assertEquals(r.vehicle, w.shiftVehicle(r.shift))
        // Begründung: OTHER braucht einen Hinweistext, zu langer Text abgewiesen
        assertEquals(422, status { w.switch(r, to, SwitchReason.OTHER) })
        assertEquals(422, status { w.switch(r, to, SwitchReason.DEFECT, "x".repeat(501)) })
        // die Leitstelle darf wechseln (Rolle DISPATCH)
        val sw = w.switch(r, to, ctx = w.dispatcher())
        assertEquals(SwitchActorRole.DISPATCH, sw.actorRole)
        // beendete Schicht: nicht mehr wechselbar
        val ended = w.released(); fx.exec("UPDATE taktovia.staff_shift SET state = 'ENDED', ended_at = now() WHERE id = ?", ended.shift)
        assertEquals(409, status { w.switch(ended, w.replacement()) })
    }

    @Test fun wechsel_parallel_zu_zuweisung_keine_zusage_vor_pruefung() {
        // Die Zuweisung (Hold-Commit der Disposition) prüft die Disponierbarkeit unter der Fahrzeugsperre derselben Transaktion; hier dieselbe Prüfung, die jede Verschränkung mit dem Wechsel sieht.
        repeat(6) {
            val r = w.released()
            val to = w.replacement()
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val dispatch = pool.submit<DispatchVerdict> { start.await(); fx.ctxTx.run(w.dispatcher()) { j -> readiness.assertDispatchable(j, to, r.shift, null, Instant.now()) } }
                val change = pool.submit { start.await(); w.switch(r, to, from = r.vehicle) }
                start.countDown()
                val v = dispatch.get(30, TimeUnit.SECONDS); change.get(30, TimeUnit.SECONDS)
                assertTrue(v is DispatchVerdict.Refused, "keine Zusage auf das Ersatzfahrzeug vor der Prüfung: $v")
            } finally { pool.shutdownNow() }
            assertTrue(verdict(to, r.shift) is DispatchVerdict.Refused)
            assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE vehicle_id = ? AND shift_id = ?", to, r.shift))
        }
    }

    @Test fun pruefabschluss_parallel_zu_neuer_sperre_keine_freigabe() {
        repeat(6) {
            val r = w.released()
            val to = w.replacement()
            w.switch(r, to)
            val started = w.iw.start(r.ctx, r.shift, to, "VEHICLE_CHANGE").inspection
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val submit = pool.submit { start.await(); fx.fleet.inspections.submit(r.ctx, started.id, started.revision, de.taktovia.fleetsafety.api.SubmitInspection(w.rw.allOk(), Instant.now().minusSeconds(5), null)) }
                val block = pool.submit { start.await(); fx.fleet.readiness.setBlock(w.rw.safetyService(), SetBlock(to, BlockSource.SAFETY, BlockReason.DEFECT_CRITICAL, "case-${UUID.randomUUID()}")) }
                start.countDown()
                submit.get(30, TimeUnit.SECONDS); block.get(30, TimeUnit.SECONDS)
            } finally { pool.shutdownNow() }
            // in jeder Reihenfolge: gesperrt und ohne gültige Freigabe (entweder nie erteilt oder im Sperr-Commit widerrufen)
            assertTrue(verdict(to, r.shift) is DispatchVerdict.Refused)
            assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE vehicle_id = ? AND shift_id = ? AND revoked_at IS NULL", to, r.shift))
        }
    }

    @Test fun zwei_gleichzeitige_wechsel_genau_ein_ersatzfahrzeug() {
        repeat(6) {
            val r = w.released()
            val b = w.replacement(); val c = w.replacement()
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            val results = try {
                val f = listOf(b, c).map { target -> pool.submit<Result<Unit>> { start.await(); runCatching { w.switch(r, target, from = r.vehicle); Unit } } }
                start.countDown(); f.map { it.get(30, TimeUnit.SECONDS) }
            } finally { pool.shutdownNow() }
            assertEquals(1, results.count { it.isSuccess }, "genau ein Wechsel gewinnt: $results")
            val loser = results.single { it.isFailure }.exceptionOrNull() as ProblemException
            assertEquals(ErrorCode.CONFLICT, loser.code)
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.shift_vehicle_switch WHERE shift_id = ?", r.shift))
            val winner = w.shiftVehicle(r.shift)
            assertTrue(winner == b || winner == c)
            assertEquals(winner, fx.strings("SELECT to_vehicle_id::text FROM taktovia.shift_vehicle_switch WHERE shift_id = ?", r.shift).single().let(UUID::fromString))
        }
    }
}
