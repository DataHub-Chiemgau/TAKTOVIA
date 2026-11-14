package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.BlockReason
import de.taktovia.fleetsafety.api.BlockSource
import de.taktovia.fleetsafety.api.BlockStatus
import de.taktovia.fleetsafety.api.DispatchRefusal
import de.taktovia.fleetsafety.api.DispatchVerdict
import de.taktovia.fleetsafety.api.IssueRelease
import de.taktovia.fleetsafety.api.OperationalBlockInput
import de.taktovia.fleetsafety.api.OperationalLiftInput
import de.taktovia.fleetsafety.api.ReleaseOutcome
import de.taktovia.fleetsafety.api.ReleaseState
import de.taktovia.fleetsafety.api.SetBlock
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
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
import java.util.concurrent.atomic.AtomicReference

/**
 * WP-M3-41 / LH-6.7-OPS-005 (Operative Fahrzeugsperre durch die Leitstelle) im Modul `fleet-safety` unter der Runtime-Rolle gegen die echte Datenbank, ohne Mocks. Der Hold-Pfad der
 * Disposition und die HTTP-Schicht (Idempotency-Key, If-Match, Rechtematrix, Dienstscope) liegen in `app` (`OperationalBlockApiTest`, `OperationalBlockHoldTest`), weil `fleet-safety`
 * nicht von `booking-dispatch` abhängen darf.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationalBlockTest {
    private lateinit var fx: FleetFixture
    private lateinit var w: ReadinessWorld

    @BeforeAll fun up() { fx = FleetFixture(); w = ReadinessWorld(fx) }
    @AfterAll fun down() = fx.close()

    private val blocks get() = fx.fleet.operatorBlocks
    private val readiness get() = fx.fleet.readiness
    private fun dispatcher() = w.ctx("dispatcher")
    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)
    private fun vehicleRevision(v: UUID) = fx.count("SELECT master_revision FROM taktovia.vehicle WHERE id = ?", v).toLong()
    private fun safetyRevision(v: UUID) = fx.count("SELECT safety_revision FROM taktovia.vehicle WHERE id = ?", v).toLong()
    private fun disruption(comment: String? = null) = OperationalBlockInput(BlockReason.OPERATIONAL_DISRUPTION, comment)
    private fun issue(r: ReadinessWorld.Ready) = readiness.issueRelease(w.safetyService(), IssueRelease(r.vehicle, r.shift, r.inspection)) as ReleaseOutcome.Issued
    private fun verdict(r: ReadinessWorld.Ready) = readiness.assertDispatchable(dispatcher(), r.vehicle, r.shift, null, Instant.now())

    @Test
    fun gesperrtes_fahrzeug_erhaelt_keine_neue_zuweisung() {
        val r = w.ready(); val rel = issue(r).release
        assertTrue(verdict(r) is DispatchVerdict.Dispatchable, "vor der Sperre disponierbar")
        val rev = safetyRevision(r.vehicle)
        val out = blocks.create(dispatcher(), r.vehicle, vehicleRevision(r.vehicle), disruption())
        assertTrue(out.created); assertEquals(BlockSource.OPERATIONS, out.block.source); assertEquals(BlockReason.OPERATIONAL_DISRUPTION, out.block.reason)
        assertEquals(rev + 1, out.safetyRevision, "die Sperre erhöht die safety_revision genau einmal")
        // im selben Commit: gültige Freigabe widerrufen, Fahrzeug nicht mehr disponierbar
        assertEquals(ReleaseState.REVOKED, readiness.release(dispatcher(), rel.id).state)
        val v = verdict(r) as DispatchVerdict.Refused
        assertTrue(DispatchRefusal.VEHICLE_BLOCKED in v.reasons, "${v.reasons}")
        // auch eine Prüfung mit der vor der Sperre gelesenen safety_revision scheitert (Hold-Commit gegen veralteten Stand)
        val stale = readiness.assertDispatchable(dispatcher(), r.vehicle, r.shift, rev, Instant.now()) as DispatchVerdict.Refused
        assertTrue(stale.reasons.any { it == DispatchRefusal.VEHICLE_BLOCKED || it == DispatchRefusal.STALE_SAFETY_REVISION }, "${stale.reasons}")
        // die Sperre ersetzt keine Prüfung: Aufheben erteilt keine Freigabe, das Fahrzeug bleibt bis zur neuen Freigabe nicht disponierbar
        val lifted = blocks.lift(dispatcher(), r.vehicle, out.block.id, out.block.revision, OperationalLiftInput("Störung behoben"))
        assertEquals(BlockStatus.LIFTED, lifted.block.status); assertFalse(lifted.stillBlocked)
        assertTrue(verdict(r) is DispatchVerdict.Refused, "nach dem Aufheben gilt keine alte Freigabe wieder")
    }

    @Test
    fun aufheben_operativer_sperre_laesst_kritische_sperre_bestehen() {
        val v = w.vehicle()
        val safety = readiness.setBlock(w.safetyService(), SetBlock(v, BlockSource.SAFETY, BlockReason.DEFECT_CRITICAL, "defect-41")).block
        val ops = blocks.create(dispatcher(), v, vehicleRevision(v), disruption()).block
        assertEquals(2, readiness.activeBlocks(dispatcher(), v).size)
        val lift = blocks.lift(dispatcher(), v, ops.id, ops.revision, OperationalLiftInput(null))
        assertTrue(lift.stillBlocked)
        assertEquals(listOf(safety.id), readiness.activeBlocks(dispatcher(), v).map { it.id })
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle WHERE id = ? AND active_block", v), "die kritische Sperre bleibt wirksam")
        assertEquals(BlockSource.SAFETY, readiness.activeBlocks(dispatcher(), v).single().source)
    }

    @Test
    fun sperre_ohne_grund_ohne_rolle_oder_fremdes_fahrzeug_abgewiesen() {
        val v = w.vehicle(); val rev = vehicleRevision(v)
        // ohne Grund: MANUAL und OTHER verlangen eine Begründung, ein Grund außerhalb des Leitstellenkatalogs gibt es nicht
        assertEquals(422, problem { blocks.create(dispatcher(), v, rev, OperationalBlockInput(BlockReason.MANUAL, null)) }.code.status)
        assertEquals(422, problem { blocks.create(dispatcher(), v, rev, OperationalBlockInput(BlockReason.OTHER, "  ")) }.code.status)
        assertEquals(422, problem { blocks.create(dispatcher(), v, rev, OperationalBlockInput(BlockReason.DEFECT_CRITICAL, "x")) }.code.status)
        assertEquals(422, problem { OperationalBlockInput.parse(mapOf("comment" to "ohne reason_code")) }.code.status)
        assertEquals(422, problem { OperationalBlockInput.parse(mapOf("reason_code" to "UNBEKANNT")) }.code.status)
        assertEquals(422, problem { OperationalBlockInput.parse(mapOf("reason_code" to "MANUAL", "comment" to "x", "source" to "SAFETY")) }.code.status, "die Quelle steht fest, sie ist kein Eingabefeld")
        // ohne Rolle
        assertEquals(403, problem { blocks.create(w.ctx("driver"), v, rev, disruption()) }.code.status)
        assertEquals(403, problem { blocks.create(w.ctx("workshop"), v, rev, disruption()) }.code.status)
        // fremdes Fahrzeug (anderer Mandant): 404, nichts geschrieben
        val foreign = ReadinessWorld(fx).vehicle()
        assertEquals(404, problem { blocks.create(dispatcher(), foreign, 1, disruption()) }.code.status)
        assertEquals(404, problem { blocks.create(dispatcher(), UUID.randomUUID(), 1, disruption()) }.code.status)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id IN (?, ?)", v, foreign))
        assertEquals(rev, vehicleRevision(v)); assertEquals(1L, safetyRevision(v), "abgewiesene Sperren ändern keine Revision")
        // veraltete Revision: 412, nichts geschrieben
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { blocks.create(dispatcher(), v, rev + 5, disruption()) }.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ?", v))
    }

    @Test
    fun aufhebungsversuch_kritischer_sperre_ueber_operativen_weg_scheitert() {
        val v = w.vehicle()
        val safety = readiness.setBlock(w.safetyService(), SetBlock(v, BlockSource.SAFETY, BlockReason.DEFECT_CRITICAL, "defect-42")).block
        val e = problem { blocks.lift(dispatcher(), v, safety.id, safety.revision, OperationalLiftInput("bitte freigeben")) }
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.code)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ? AND status = 'ACTIVE'", v))
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ? AND status = 'LIFTED'", v))
        // Rolle, Fahrzeug und Sperre müssen zusammenpassen
        val ops = blocks.create(dispatcher(), v, vehicleRevision(v), disruption()).block
        assertEquals(403, problem { blocks.lift(w.ctx("driver"), v, ops.id, ops.revision, OperationalLiftInput(null)) }.code.status)
        assertEquals(404, problem { blocks.lift(dispatcher(), w.vehicle(), ops.id, ops.revision, OperationalLiftInput(null)) }.code.status, "Sperre eines anderen Fahrzeugs")
        assertEquals(404, problem { blocks.lift(dispatcher(), v, UUID.randomUUID(), 1, OperationalLiftInput(null)) }.code.status)
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { blocks.lift(dispatcher(), v, ops.id, ops.revision + 3, OperationalLiftInput(null)) }.code)
        assertEquals(BlockStatus.LIFTED, blocks.lift(dispatcher(), v, ops.id, ops.revision, OperationalLiftInput(null)).block.status)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { blocks.lift(dispatcher(), v, ops.id, ops.revision + 1, OperationalLiftInput(null)) }.code, "aufgehobene Sperre wird nie wieder geöffnet")
    }

    @Test
    fun sperre_parallel_zu_hold_commit_keine_zusage() {
        // Modulseite des Hold-Commits: Prüfung unter der gemeinsamen Zeilensperre (wie `JdbcHolds`); der echte Hold-Pfad mit Fahrtenplan steht in `OperationalBlockHoldTest` (app).
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(12) { i ->
                val r = w.ready(); issue(r)
                val rev = safetyRevision(r.vehicle); val vrev = vehicleRevision(r.vehicle)
                val start = CountDownLatch(1)
                val commit = AtomicReference<Instant>(); val blockDone = AtomicReference<Instant>(); val seen = AtomicReference<DispatchVerdict>()
                val dispo = pool.submit {
                    start.await()
                    val v = fx.ctxTx.run(dispatcher()) { j -> readiness.assertDispatchable(j, r.vehicle, r.shift, rev, Instant.now()).also { Thread.sleep(30) } }
                    commit.set(Instant.now()); seen.set(v)
                }
                val block = pool.submit {
                    start.await(); Thread.sleep((i % 5) * 9L)
                    blocks.create(dispatcher(), r.vehicle, vrev, disruption()); blockDone.set(Instant.now())
                }
                start.countDown(); dispo.get(20, TimeUnit.SECONDS); block.get(20, TimeUnit.SECONDS)
                when (val v = seen.get()) {
                    is DispatchVerdict.Dispatchable -> assertFalse(blockDone.get().isBefore(commit.get()), "Zusage nur, wenn die Sperre erst nach dem Commit wirksam wurde (Lauf $i)")
                    is DispatchVerdict.Refused -> assertTrue(v.reasons.any { it == DispatchRefusal.VEHICLE_BLOCKED || it == DispatchRefusal.STALE_SAFETY_REVISION }, "Lauf $i: ${v.reasons}")
                    else -> throw AssertionError("kein Ergebnis")
                }
                assertTrue(verdict(r) is DispatchVerdict.Refused, "Lauf $i: nach der Sperre in keiner Serialisierung disponierbar")
            }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun idempotente_wiederholung_und_zwei_disponenten_ein_aktiver_sperreintrag() {
        val v = w.vehicle(); val rev = vehicleRevision(v)
        val first = blocks.create(dispatcher(), v, rev, disruption())
        assertTrue(first.created); assertEquals(rev + 1, first.vehicleRevision)
        // derselbe Grund, aktuelle Revision: keine zweite Sperre, kein zweiter Revisionssprung
        val again = blocks.create(dispatcher(), v, first.vehicleRevision, disruption())
        assertFalse(again.created); assertEquals(first.block.id, again.block.id); assertEquals(first.safetyRevision, safetyRevision(v)); assertEquals(first.vehicleRevision, vehicleRevision(v))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ?", v))
        // zwei Disponenten gleichzeitig mit demselben Stand: genau einer gewinnt, der andere erhält 412
        val v2 = w.vehicle(); val rev2 = vehicleRevision(v2); val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val results = (1..2).map { pool.submit<Result<Boolean>> { start.await(); runCatching { blocks.create(w.ctx("dispatcher"), v2, rev2, disruption()).created } } }
            start.countDown()
            val outs = results.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, outs.count { it.getOrNull() == true }, "$outs")
            val lost = outs.single { it.isFailure }.exceptionOrNull() as ProblemException
            assertEquals(ErrorCode.REVISION_MISMATCH, lost.code)
            assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ? AND status = 'ACTIVE'", v2))
            assertEquals(2L, safetyRevision(v2), "Ausgangsstand 1 + genau ein Sprung")
        } finally { pool.shutdownNow() }
    }

    @Test
    fun sperre_protokolliert_akteur_grund_zeitpunkt_und_erzeugt_ereignis_zugesagte_fahrten_bleiben_zugeordnet() {
        val r = w.ready(); issue(r); val ctx = dispatcher()
        val before = Instant.now().minusSeconds(2)
        val out = blocks.create(ctx, r.vehicle, vehicleRevision(r.vehicle), OperationalBlockInput(BlockReason.MANUAL, "Reifenschaden gemeldet"))
        val actor = "user:" + ctx.actor.subject
        assertEquals(actor, out.block.setBy); assertTrue(out.block.setAt.isAfter(before))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.vehicle.blocked' AND object_ref=? AND redacted_changes->>'reason_code'='MANUAL' AND redacted_changes->>'source'='OPERATIONS' AND redacted_changes->>'comment'='Reifenschaden gemeldet'",
            w.tenant.value, "vehicle:${r.vehicle}"))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.vehicle.blocked' AND object_ref=? AND actor_ref=?", w.tenant.value, "vehicle:${r.vehicle}", actor))
        // Ereignis „Fahrzeug gesperrt“ (Quelle Leitstelle) über die Outbox, ohne Freitext
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='fleet.vehicle_blocked' AND payload->>'vehicle_id'=? AND payload->>'source'='OPERATIONS' AND payload->'comment' IS NULL", w.tenant.value, r.vehicle.toString()))
        // bereits zugesagte Fahrten bleiben dem Fahrzeug zugeordnet: die Sperre ändert weder Schicht noch Fahrzeugzuordnung (Behandlung erst im Störungsablauf, WP-M3-53/-54)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.staff_shift WHERE id = ? AND vehicle_id = ?", r.shift, r.vehicle))
        // Aufheben ist ebenfalls protokolliert
        blocks.lift(ctx, r.vehicle, out.block.id, out.block.revision, OperationalLiftInput("erledigt"))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.vehicle.block_lifted' AND object_ref=? AND actor_ref=? AND redacted_changes->>'comment'='erledigt'", w.tenant.value, "vehicle:${r.vehicle}", actor))
        assertNotEquals(vehicleRevision(r.vehicle), 1L)
    }
}
