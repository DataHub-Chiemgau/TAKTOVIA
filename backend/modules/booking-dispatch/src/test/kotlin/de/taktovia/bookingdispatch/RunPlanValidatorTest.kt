package de.taktovia.bookingdispatch

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.bookingdispatch.RunPlanTestSupport.mv
import de.taktovia.bookingdispatch.RunPlanTestSupport.planOf
import de.taktovia.bookingdispatch.RunPlanTestSupport.stop
import de.taktovia.bookingdispatch.RunPlanTestSupport.vec
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.Dimension
import de.taktovia.bookingdispatch.api.EquipmentCode
import de.taktovia.bookingdispatch.api.PlanStop
import de.taktovia.bookingdispatch.api.PlanViolationCode
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RevisionCause
import de.taktovia.bookingdispatch.api.RunPlanRejectedException
import de.taktovia.bookingdispatch.api.RunPlanValidator
import de.taktovia.bookingdispatch.api.RunState
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.util.Random
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * WP-M2-16 / LH-6.5-OPT-004: Routeninvarianten (Einstieg vor Ausstieg, Belegung je Abschnitt zwischen 0 und Kapazität in jeder Dimension, eindeutige Stoppreihenfolge) und
 * Fahrtenplanmodell (Revisionen, Erstanlage, Ereignis). Reine Validatortests laufen ohne Datenbank; Speichertests laufen unter der Runtime-Rolle, die Gegenprobe der Belegung
 * liest die gespeicherten Revisionen mit Administratorrechten per SQL (unabhängig vom Code unter Test).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunPlanValidatorTest {
    private val fx by lazy { BookingFixture() }
    private val api by lazy { BookingdispatchApi.create(fx.ctxTx) }
    private val ctx get() = fx.context(fx.tenantA)

    @AfterAll fun down() { if (lazyInitialized()) fx.close() }
    private fun lazyInitialized() = runCatching { (this::class.java.getDeclaredField("fx\$delegate").also { it.isAccessible = true }.get(this) as Lazy<*>).isInitialized() }.getOrDefault(false)

    private fun cap(seats: Int, wheelchair: Int, vararg eq: String) = """{"schema_version":"1.0","seats":$seats,"wheelchair":$wheelchair,"equipment":[${eq.joinToString(",") { "\"$it\"" }}]}"""
    private val capacity = ResourceVector(4, 1, setOf(EquipmentCode.RAMP))
    private fun b() = UUID.randomUUID()

    private fun storedRows(runId: UUID): Long = fx.admin().use { ds -> ds.connection.use { c ->
        c.prepareStatement("SELECT count(*) FROM taktovia.route_stop WHERE run_id = ?").use { ps -> ps.setObject(1, runId); ps.executeQuery().use { it.next(); it.getLong(1) } }
    } }
    private fun runRevision(runId: UUID): Long = fx.admin().use { ds -> ds.connection.use { c ->
        c.prepareStatement("SELECT revision FROM taktovia.vehicle_run WHERE id = ?").use { ps -> ps.setObject(1, runId); ps.executeQuery().use { it.next(); it.getLong(1) } }
    } }
    private fun outboxCount(runId: UUID): Long = fx.admin().use { ds -> ds.connection.use { c ->
        c.prepareStatement("SELECT count(*) FROM taktovia.outbox WHERE aggregate_id = ? AND event_type = 'dispatch.run_revised'").use { ps -> ps.setObject(1, runId); ps.executeQuery().use { it.next(); it.getLong(1) } }
    } }

    // ---- positiv ----

    @Test
    fun gueltiger_plan_wird_gespeichert() {
        val run = fx.createRun(cap(4, 1, "RAMP")); val a = b(); val w = b()
        // Einstieg und Ausstieg zwischen vorhandenen Stopps: A steigt bei 0 ein und bei 2 aus, der Rollstuhlnutzer W fährt von 1 bis 3
        val stops = planOf(4, listOf(Triple(a, vec(2), 0 to 2), Triple(w, vec(0, 1, EquipmentCode.RAMP), 1 to 3)))
        assertTrue(RunPlanValidator.validate(capacity, stops).isEmpty())
        val saved = api.runs.appendRevision(ctx, run.runId, 1, stops, RevisionCause.BOOKING_INSERTED, listOf(a, w))
        assertEquals(2, saved.revision)
        val read = api.runs.currentPlan(ctx, run.runId)
        assertEquals(2, read.revision); assertEquals(stops, read.stops)
        assertEquals(0 to 2, read.intervalOf(a)); assertEquals(1 to 3, read.intervalOf(w))
        assertEquals(RunState.ACTIVE, api.runs.run(ctx, run.runId).state)
    }

    @Test
    fun at_02_zwei_gepoolte_fahrten_reihenfolge_und_kapazitaet() {
        // Zwei überlappende Relationen: A: S0→S2, B: S1→S3. Auf dem Abschnitt 1 fahren beide gleichzeitig.
        val a = b(); val c = b()
        val stops = planOf(4, listOf(Triple(a, vec(2), 0 to 2), Triple(c, vec(2), 1 to 3)))
        assertTrue(RunPlanValidator.validate(ResourceVector(4), stops).isEmpty(), "4 Sitze reichen für 2+2")
        assertEquals(listOf(2 to 0, 4 to 0, 2 to 0), RunPlanValidator.occupancy(stops))
        val tooSmall = RunPlanValidator.validate(ResourceVector(3), stops)
        assertEquals(listOf(PlanViolationCode.OVER_CAPACITY), tooSmall.map { it.code }); assertEquals(1, tooSmall.single().seq)
        // Reihenfolge: A steigt vor B ein, A steigt vor B aus
        val plan = RunPlanTestSupport.run { stops }
        assertTrue(plan.indexOfFirst { s -> s.boardings.any { it.bookingId == a } } < plan.indexOfFirst { s -> s.boardings.any { it.bookingId == c } })
        assertTrue(plan.indexOfFirst { s -> s.alightings.any { it.bookingId == a } } < plan.indexOfFirst { s -> s.alightings.any { it.bookingId == c } })
    }

    // ---- negativ: eine Verletzung je Invariante ----

    @Test
    fun ausstieg_vor_einstieg_wird_verworfen() {
        val x = b()
        val before = planOf(3, listOf(Triple(x, vec(1), 2 to 1)))
        assertTrue(RunPlanValidator.validate(capacity, before).any { it.code == PlanViolationCode.ALIGHT_BEFORE_BOARD && it.bookingId == x })
        val same = planOf(3, listOf(Triple(x, vec(1), 1 to 1)))
        assertTrue(RunPlanValidator.validate(capacity, same).any { it.code == PlanViolationCode.ALIGHT_BEFORE_BOARD }, "am selben Stopp zählt als nicht davor")
        val noBoard = listOf(stop(0, alight = listOf(mv(x, vec(1)))), stop(1, last = true))
        assertTrue(RunPlanValidator.validate(capacity, noBoard).any { it.code == PlanViolationCode.ALIGHT_WITHOUT_BOARD })
        val noAlight = listOf(stop(0, board = listOf(mv(x, vec(1)))), stop(1, last = true))
        assertTrue(RunPlanValidator.validate(capacity, noAlight).any { it.code == PlanViolationCode.BOARD_WITHOUT_ALIGHT })
        // gespeichert wird so ein Plan nie
        val run = fx.createRun(cap(4, 1, "RAMP"))
        val e = assertThrows<RunPlanRejectedException> { api.runs.appendRevision(ctx, run.runId, 1, before, RevisionCause.REPLAN) }
        assertTrue(e.violations.any { it.code == PlanViolationCode.ALIGHT_BEFORE_BOARD })
        assertEquals(0, storedRows(run.runId)); assertEquals(1, runRevision(run.runId))
    }

    @Test
    fun belegung_kleiner_null_wird_verworfen() {
        val x = b()
        // Es steigen mehr Sitze aus, als eingestiegen sind
        val stops = listOf(stop(0, board = listOf(mv(x, vec(1)))), stop(1, last = true, alight = listOf(mv(x, vec(3)))))
        val v = RunPlanValidator.validate(capacity, stops)
        assertTrue(v.any { it.code == PlanViolationCode.NEGATIVE_OCCUPANCY && it.dimension == Dimension.SEATS && it.seq == 1 })
        assertTrue(v.any { it.code == PlanViolationCode.VECTOR_MISMATCH })
        // dasselbe für den Rollstuhlplatz
        val w = listOf(stop(0, board = listOf(mv(x, vec(1)))), stop(1, last = true, alight = listOf(mv(x, vec(1, 1)))))
        assertTrue(RunPlanValidator.validate(capacity, w).any { it.code == PlanViolationCode.NEGATIVE_OCCUPANCY && it.dimension == Dimension.WHEELCHAIR })
        // die Hold-/Confirm-Prüfung kennt dieselbe Regel
        assertEquals(PlanViolationCode.NEGATIVE_OCCUPANCY, RunPlanValidator.checkSegmentOccupancy(capacity, listOf(-1 to 0)).single().code)
    }

    @Test
    fun belegung_ueber_kapazitaet_je_dimension_wird_verworfen() {
        val seats = planOf(2, listOf(Triple(b(), vec(5), 0 to 1)))
        assertEquals(setOf(Dimension.SEATS), RunPlanValidator.validate(capacity, seats).mapNotNull { it.dimension }.toSet())
        val wheel = planOf(2, listOf(Triple(b(), vec(0, 2), 0 to 1)))
        assertEquals(setOf(Dimension.WHEELCHAIR), RunPlanValidator.validate(capacity, wheel).mapNotNull { it.dimension }.toSet())
        val equip = planOf(2, listOf(Triple(b(), vec(1, 0, EquipmentCode.LIFT), 0 to 1)))
        assertEquals(listOf(PlanViolationCode.EQUIPMENT_MISSING to Dimension.EQUIPMENT), RunPlanValidator.validate(capacity, equip).map { it.code to it.dimension })
        // Ein freier Sitz ersetzt keinen Rollstuhlplatz: 4 Sitze belegt, 1 Rollstuhlplatz frei -> gültig; ein zweiter Rollstuhlplatz -> ungültig
        val ok = planOf(2, listOf(Triple(b(), vec(4), 0 to 1), Triple(b(), vec(0, 1), 0 to 1)))
        assertTrue(RunPlanValidator.validate(capacity, ok).isEmpty())
        // Prüfung der Speicherung: nichts gespeichert
        val run = fx.createRun(cap(4, 1, "RAMP"))
        for (bad in listOf(seats, wheel, equip)) assertThrows<RunPlanRejectedException> { api.runs.appendRevision(ctx, run.runId, 1, bad, RevisionCause.REPLAN) }
        assertEquals(0, storedRows(run.runId))
    }

    @Test
    fun doppelte_sequenznummer_wird_verworfen() {
        val x = b()
        val dup = listOf(stop(0, board = listOf(mv(x, vec(1)))), stop(1, alight = listOf(mv(x, vec(1)))), stop(1, last = true))
        assertTrue(RunPlanValidator.validate(capacity, dup).any { it.code == PlanViolationCode.DUPLICATE_SEQUENCE && it.seq == 1 })
        val gap = listOf(stop(0), stop(2, last = true))
        assertTrue(RunPlanValidator.validate(capacity, gap).any { it.code == PlanViolationCode.SEQUENCE_GAP })
        val run = fx.createRun(cap(4, 1))
        assertThrows<RunPlanRejectedException> { api.runs.appendRevision(ctx, run.runId, 1, dup, RevisionCause.REPLAN) }
        assertEquals(0, storedRows(run.runId))
    }

    @Test
    fun verletzendes_solverergebnis_wird_nicht_gespeichert() {
        val run = fx.createRun(cap(2, 0))
        val good = planOf(2, listOf(Triple(b(), vec(2), 0 to 1)))
        api.runs.appendRevision(ctx, run.runId, 1, good, RevisionCause.BOOKING_INSERTED)
        val events = outboxCount(run.runId); val rows = storedRows(run.runId)
        // Ergebnis des Solvers mit Überbelegung: abgewiesen, Revision, Stopps und Ereignisse unverändert
        val bad = planOf(2, listOf(Triple(b(), vec(2), 0 to 1), Triple(b(), vec(1), 0 to 1)))
        assertThrows<RunPlanRejectedException> { api.runs.appendRevision(ctx, run.runId, 2, bad, RevisionCause.REPLAN) }
        assertEquals(2, runRevision(run.runId)); assertEquals(rows, storedRows(run.runId)); assertEquals(events, outboxCount(run.runId))
        assertEquals(good, api.runs.currentPlan(ctx, run.runId).stops)
        // unbekannte Haltepunktrevision: ebenfalls verworfen
        val unknown = listOf(PlanStop(0, UUID.randomUUID(), RunPlanTestSupport.T0, legDurationSec = 1, legDistanceM = 1), stop(1, last = true))
        val e = assertThrows<ProblemException> { api.runs.appendRevision(ctx, run.runId, 2, unknown, RevisionCause.REPLAN) }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code); assertEquals(2, runRevision(run.runId))
    }

    // ---- Revisionen, Lesbarkeit, Ereignis ----

    @Test
    fun revision_steigt_monoton() {
        val run = fx.createRun(cap(4, 1))
        val revisions = (1L..4L).map { i -> api.runs.appendRevision(ctx, run.runId, i, planOf(2, listOf(Triple(b(), vec(1), 0 to 1))), RevisionCause.BOOKING_INSERTED).revision }
        assertEquals(listOf(2L, 3L, 4L, 5L), revisions)
        // Zurück oder überspringen geht nicht: veraltete oder falsche erwartete Revision
        for (expected in listOf(1L, 4L, 9L)) {
            val e = assertThrows<ProblemException> { api.runs.appendRevision(ctx, run.runId, expected, emptyList(), RevisionCause.MANUAL_CHANGE) }
            assertEquals(ErrorCode.REVISION_MISMATCH, e.code)
        }
        assertEquals(5, runRevision(run.runId))
        // Planänderung in einem Zustand ohne Planänderung
        api.runs.transition(ctx, run.runId, RunState.LOCKED)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, assertThrows<ProblemException> { api.runs.appendRevision(ctx, run.runId, 5, emptyList(), RevisionCause.MANUAL_CHANGE) }.code)
        assertEquals(5, api.runs.run(ctx, run.runId).revision, "ein Zustandswechsel ändert die Planrevision nicht")
    }

    @Test
    fun fruehere_revisionen_bleiben_lesbar() {
        val run = fx.createRun(cap(4, 1)); val x = b(); val y = b()
        val p2 = planOf(2, listOf(Triple(x, vec(1), 0 to 1)))
        val p3 = planOf(3, listOf(Triple(x, vec(1), 0 to 2), Triple(y, vec(1), 1 to 2)))
        api.runs.appendRevision(ctx, run.runId, 1, p2, RevisionCause.BOOKING_INSERTED, listOf(x))
        api.runs.appendRevision(ctx, run.runId, 2, p3, RevisionCause.BOOKING_INSERTED, listOf(y))
        assertEquals(p2, api.runs.plan(ctx, run.runId, 2).stops); assertEquals(p3, api.runs.plan(ctx, run.runId, 3).stops)
        assertEquals(emptyList<PlanStop>(), api.runs.plan(ctx, run.runId, 1).stops)
        assertEquals(p3, api.runs.currentPlan(ctx, run.runId).stops)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { api.runs.plan(ctx, run.runId, 4) }.code)
        // Frühere Revisionen sind in der Datenbank unveränderlich (kein UPDATE/DELETE-Recht der Runtime-Rolle)
        fx.ds.connection.use { c -> c.autoCommit = false
            c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)").use { it.setString(1, fx.tenantA.toString()); it.execute() }
            assertThrows<java.sql.SQLException> { c.prepareStatement("DELETE FROM taktovia.route_stop WHERE run_id = ?").use { it.setObject(1, run.runId); it.execute() } }
            c.rollback() }
    }

    @Test
    fun planrevision_erstellt_ereignis_in_outbox() {
        val run = fx.createRun(cap(4, 1)); val x = b()
        api.runs.appendRevision(ctx, run.runId, 1, planOf(2, listOf(Triple(x, vec(1), 0 to 1))), RevisionCause.BOOKING_INSERTED, listOf(x))
        val rows = fx.admin().use { ds -> ds.connection.use { c ->
            c.prepareStatement("SELECT aggregate_revision, payload::text FROM taktovia.outbox WHERE aggregate_id = ? AND event_type = 'dispatch.run_revised'").use { ps ->
                ps.setObject(1, run.runId); ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1) to rs.getString(2)) } } }
        } }
        assertEquals(1, rows.size); assertEquals(2L, rows.single().first)
        val schema = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012()).getSchema(Files.readString(BookingTestDb.repoRoot().resolve("api/events/dispatch.run_revised.v1.schema.json")))
        assertEquals(emptyList<String>(), schema.validate(rows.single().second, InputFormat.JSON).map { it.toString() })
        assertTrue(rows.single().second.contains(x.toString()) && rows.single().second.contains("BOOKING_INSERTED"))
        // Negativprobe: das Schema prüft wirklich
        assertTrue(schema.validate("""{"run_id":"x"}""", InputFormat.JSON).isNotEmpty())
        // ein abgewiesener Plan schreibt kein Ereignis
        assertThrows<RunPlanRejectedException> { api.runs.appendRevision(ctx, run.runId, 2, planOf(2, listOf(Triple(b(), vec(9), 0 to 1))), RevisionCause.REPLAN) }
        assertEquals(1, outboxCount(run.runId))
    }

    // ---- konkurrierend ----

    private fun <T> parallel(n: Int, job: (Int) -> T): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(n); val start = CountDownLatch(1)
        try {
            val fs = (0 until n).map { i -> pool.submit(Callable { start.await(); runCatching { job(i) } }) }
            start.countDown(); return fs.map { it.get() }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun parallele_planaenderung_ein_revisionskonflikt() {
        repeat(15) { round ->
            val run = fx.createRun(cap(4, 1))
            val rs = parallel(2) { i -> api.runs.appendRevision(ctx, run.runId, 1, planOf(2 + i, listOf(Triple(b(), vec(1), 0 to 1))), RevisionCause.MANUAL_CHANGE) }
            assertEquals(1, rs.count { it.isSuccess }, "Runde $round: genau eine Planänderung gewinnt")
            assertEquals(ErrorCode.REVISION_MISMATCH, (rs.single { it.isFailure }.exceptionOrNull() as ProblemException).code)
            assertEquals(2, runRevision(run.runId)); assertEquals(1, outboxCount(run.runId))
            val winner = rs.single { it.isSuccess }.getOrThrow()
            assertEquals(winner.stops, api.runs.plan(ctx, run.runId, 2).stops, "gespeichert ist genau der Plan des Gewinners")
            assertEquals(winner.stops.size.toLong(), storedRows(run.runId))
        }
    }

    @Test
    fun parallele_erstanlage_genau_ein_run() {
        repeat(5) {
            val shift = RunPlanTestSupport.shiftWithoutRun(fx)
            val rs = parallel(8) { api.runs.ensureRun(ctx, shift) }
            assertTrue(rs.all { it.isSuccess }, rs.mapNotNull { it.exceptionOrNull()?.toString() }.joinToString())
            assertEquals(1, rs.map { it.getOrThrow().id }.toSet().size, "alle Aufrufer sehen denselben Umlauf")
            val view = rs.first().getOrThrow()
            assertEquals(1, view.revision); assertEquals(RunState.PLANNED, view.state)
            val count = fx.admin().use { ds -> ds.connection.use { c -> c.prepareStatement("SELECT count(*) FROM taktovia.vehicle_run WHERE shift_id = ?").use { ps -> ps.setObject(1, shift); ps.executeQuery().use { it.next(); it.getLong(1) } } } }
            assertEquals(1, count)
        }
    }

    // ---- Mandanten, Zustandsautomat ----

    @Test
    fun fremder_mandant_run_nicht_lesbar_ueber_port() {
        val runB = fx.createRun(cap(4, 1), fx.tenantB)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { api.runs.run(ctx, runB.runId) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { api.runs.currentPlan(ctx, runB.runId) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { api.runs.appendRevision(ctx, runB.runId, 1, emptyList(), RevisionCause.MANUAL_CHANGE) }.code)
        val shiftB = RunPlanTestSupport.shiftWithoutRun(fx, fx.tenantB)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { api.runs.ensureRun(ctx, shiftB) }.code, "fremde Schicht nicht referenzierbar")
        assertNotNull(api.runs.run(fx.context(fx.tenantB), runB.runId))
    }

    @Test
    fun zustandsuebergaenge_wie_datenbank() {
        val rules = fx.admin().use { ds -> ds.connection.use { c -> c.prepareStatement("SELECT from_state, to_state FROM taktovia.state_transition_rule WHERE machine = 'vehicle_run'").use { ps ->
            ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } } } } }
        assertEquals(RunState.TRANSITIONS.flatMap { (f, ts) -> ts.map { f.name to it.name } }.toSet(), rules)
        val run = fx.createRun(cap(4, 1)); // ACTIVE
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, assertThrows<ProblemException> { api.runs.transition(ctx, run.runId, RunState.COMPLETED) }.code)
        assertEquals(RunState.LOCKED, api.runs.transition(ctx, run.runId, RunState.LOCKED).state)
    }

    // ---- Property-Test ----

    private class Gen(seed: Long, val rnd: Random = Random(seed)) {
        /** Zufälliger Plan: n Stopps, bis zu 5 Buchungen; mit Wahrscheinlichkeit 1/3 wird eine Struktur verletzt (Ausstieg zu früh, fehlender Einstieg/Ausstieg, doppelte Buchung). */
        fun plan(): List<PlanStop> {
            val n = 2 + rnd.nextInt(5)
            val rides = (0 until rnd.nextInt(6)).map {
                val from = rnd.nextInt(n - 1); val to = from + 1 + rnd.nextInt(n - 1 - from)
                Triple(UUID.randomUUID(), vec(1 + rnd.nextInt(2), if (rnd.nextInt(4) == 0) 1 else 0, *(if (rnd.nextInt(6) == 0) arrayOf(EquipmentCode.LIFT) else emptyArray())), from to to)
            }.toMutableList()
            if (rnd.nextInt(3) == 0 && rides.isNotEmpty()) {
                val i = rnd.nextInt(rides.size); val r = rides[i]
                when (rnd.nextInt(3)) {
                    0 -> rides[i] = Triple(r.first, r.second, r.third.second to r.third.first)                // Ausstieg vor Einstieg
                    1 -> rides[i] = Triple(r.first, r.second, r.third.first to r.third.first)                  // am selben Stopp
                    else -> rides[i] = Triple(r.first, r.second, r.third.first to (n + 2))                     // Ausstieg fehlt (Index außerhalb)
                }
            }
            return planOf(n, rides)
        }
    }

    /** Unabhängiges Orakel (anderer Rechenweg als der Validator): je Buchung ein Intervall, je Abschnitt eine Summe. */
    private fun oracleValid(cap: ResourceVector, stops: List<PlanStop>): Boolean {
        val n = stops.size
        val boards = stops.flatMapIndexed { i, s -> s.boardings.map { Triple(it.bookingId, it.vector, i) } }
        val alights = stops.flatMapIndexed { i, s -> s.alightings.map { it.bookingId to i } }.toMap()
        if (boards.any { (id, _, from) -> alights[id]?.let { it > from } != true }) return false
        if (alights.keys.any { id -> boards.none { it.first == id } }) return false
        for (seg in 0 until n - 1) {
            val on = boards.filter { (id, _, from) -> from <= seg && alights.getValue(id) > seg }
            if (on.sumOf { it.second.seats } > cap.seats || on.sumOf { it.second.wheelchair } > cap.wheelchair) return false
            if (on.any { !cap.equipment.containsAll(it.second.equipment) }) return false
        }
        return true
    }

    @Test
    fun property_keine_negative_oder_ueberhoehte_belegung() {
        val gen = Gen(20260001L)
        var accepted = 0; var rejected = 0
        val run = fx.createRun(cap(4, 1, "RAMP")); var expected = 1L
        val savedRevisions = mutableListOf<Long>()
        repeat(400) {
            val stops = gen.plan()
            val violations = RunPlanValidator.validate(capacity, stops)
            assertEquals(oracleValid(capacity, stops), violations.isEmpty(), "Validator und Orakel müssen übereinstimmen: $stops -> $violations")
            if (violations.isEmpty()) {
                accepted++
                // Jeder akzeptierte Plan: Belegung je Abschnitt zwischen 0 und Kapazität
                RunPlanValidator.occupancy(stops).forEach { (s, w) -> assertTrue(s in 0..capacity.seats && w in 0..capacity.wheelchair) }
                if (savedRevisions.size < 60) { api.runs.appendRevision(ctx, run.runId, expected, stops, RevisionCause.REPLAN); expected++; savedRevisions += expected }
            } else {
                rejected++
                if (rejected <= 25) { assertThrows<RunPlanRejectedException> { api.runs.appendRevision(ctx, run.runId, expected, stops, RevisionCause.REPLAN) } }
            }
        }
        assertTrue(accepted > 50 && rejected > 50, "Generator erzeugt beide Klassen: $accepted gültig, $rejected verletzend")
        assertEquals(expected, runRevision(run.runId), "abgewiesene Pläne haben keine Revision erzeugt")
        // Gegenprobe auf den gespeicherten Daten aller im Testlauf erzeugten Revisionen, per SQL
        val sql = """
            SELECT run_revision, seq, sum(b_s - a_s) OVER w AS seats, sum(b_w - a_w) OVER w AS wheel, max(seq) OVER (PARTITION BY run_revision) AS last_seq
            FROM (SELECT run_revision, seq,
                    coalesce((SELECT sum((e->'vector'->>'seats')::int) FROM jsonb_array_elements(boardings) e), 0) AS b_s,
                    coalesce((SELECT sum((e->'vector'->>'seats')::int) FROM jsonb_array_elements(alightings) e), 0) AS a_s,
                    coalesce((SELECT sum((e->'vector'->>'wheelchair')::int) FROM jsonb_array_elements(boardings) e), 0) AS b_w,
                    coalesce((SELECT sum((e->'vector'->>'wheelchair')::int) FROM jsonb_array_elements(alightings) e), 0) AS a_w
                  FROM taktovia.route_stop WHERE run_id = ?) x
            WINDOW w AS (PARTITION BY run_revision ORDER BY seq) ORDER BY run_revision, seq"""
        var checked = 0; val revs = mutableSetOf<Long>()
        fx.admin().use { ds -> ds.connection.use { c -> c.prepareStatement(sql).use { ps -> ps.setObject(1, run.runId); ps.executeQuery().use { rs ->
            while (rs.next()) {
                val rev = rs.getLong(1); val seats = rs.getInt(3); val wheel = rs.getInt(4)
                assertTrue(seats in 0..capacity.seats, "Revision $rev Stopp ${rs.getInt(2)}: Sitze $seats")
                assertTrue(wheel in 0..capacity.wheelchair, "Revision $rev Stopp ${rs.getInt(2)}: Rollstuhl $wheel")
                if (rs.getInt(2) == rs.getInt(5)) { assertEquals(0, seats); assertEquals(0, wheel) }   // am Ende ist niemand mehr an Bord
                revs += rev; checked++
            }
        } } } }
        assertTrue(checked > 100 && revs.size > 20, "geprüft: $checked Stopps in ${revs.size} Revisionen")
    }
}
