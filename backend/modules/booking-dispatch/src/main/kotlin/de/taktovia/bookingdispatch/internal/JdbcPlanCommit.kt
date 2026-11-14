package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.BookingImpact
import de.taktovia.bookingdispatch.api.PlanBookingChangeKind
import de.taktovia.bookingdispatch.api.PlanCommit
import de.taktovia.bookingdispatch.api.PlanStop
import de.taktovia.bookingdispatch.api.PlanViolationCode
import de.taktovia.bookingdispatch.api.PromiseWindow
import de.taktovia.bookingdispatch.api.PromiseWindowGuard
import de.taktovia.bookingdispatch.api.ReplanCommand
import de.taktovia.bookingdispatch.api.ReplanException
import de.taktovia.bookingdispatch.api.ReplanRule
import de.taktovia.bookingdispatch.api.ResourceAvailability
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RevisionCause
import de.taktovia.bookingdispatch.api.RunAccess
import de.taktovia.bookingdispatch.api.RunPlanValidator
import de.taktovia.bookingdispatch.api.RunRevisionRecord
import de.taktovia.bookingdispatch.api.RunState
import de.taktovia.bookingdispatch.api.StopChange
import de.taktovia.bookingdispatch.api.StopChangeKind
import de.taktovia.bookingdispatch.api.StopMovement
import de.taktovia.bookingdispatch.api.TimeWindow
import de.taktovia.bookingdispatch.api.VehicleAvailability
import de.taktovia.bookingdispatch.api.WindowViolationCode
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.config.api.EffectiveRequest
import de.taktovia.config.api.EffectiveStatus
import de.taktovia.platform.api.Etag
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Manuelle Umplanung auf PostgreSQL (WP-M3-40); Vertrag und Reihenfolge der Prüfungen siehe [PlanCommit]. **Sperrreihenfolge** (Detailvertrag m3 §5, wie Hold und Bestätigung): zuerst die beteiligten Umläufe
 * (`vehicle_run`, aufsteigend nach Kennung), dann die Fahrzeugzeilen (`vehicle`, gemeinsam), dann Hold-Zeilen. Planrevision, Prüfungen und alle Schreibvorgänge (Revision, Stopps, Planänderungsstrom, Ereignis,
 * Umbuchungsprotokoll, Hold-Wechsel, Audit) liegen in **einer** Transaktion; die Prüfung der Grenzen liest die wirksame Konfiguration in derselben Transaktion (Wert zum Commit).
 */
internal class JdbcPlanCommit(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val config: EffectiveConfig, private val resources: ResourceAvailability, private val plans: JdbcRunPlans, private val clock: Clock,
) : PlanCommit {
    private val json = JsonMapper.builder().build()

    private class RunRow(val id: UUID, val vehicleId: UUID, val shiftId: UUID, val revision: Long, val state: RunState)
    private class StopRow(val routeStopId: UUID, val stop: PlanStop)
    private class Booking(val id: UUID, val serviceId: UUID, val state: String, val snapshot: String, val hold: String?)

    // ---- Lesen -----------------------------------------------------------------------------------------------------------------------

    private fun run(j: JdbcOperations, id: UUID, lock: Boolean): RunRow {
        val sql = "SELECT id, vehicle_id, shift_id, revision, state FROM taktovia.vehicle_run WHERE id = ?"
        return j.query(if (lock) "$sql FOR UPDATE" else sql, { rs, _ ->   // sql-lint: ok Zusatz aus Konstante, Werte gebunden
            RunRow(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getLong(4), RunState.valueOf(rs.getString(5)))
        }, id).firstOrNull() ?: throw ProblemException.notFound()
    }

    private fun stops(j: JdbcOperations, runId: UUID, revision: Long): List<StopRow> = j.query(
        "SELECT id, seq, stop_revision_id, planned_at, boardings::text, alightings::text, leg_duration_sec, leg_distance_m FROM taktovia.route_stop WHERE run_id = ? AND run_revision = ? ORDER BY seq",
        { rs, _ -> StopRow(rs.getObject(1, UUID::class.java), PlanStop(rs.getInt(2), rs.getObject(3, UUID::class.java), rs.getTimestamp(4).toInstant(), movements(rs.getString(5)), movements(rs.getString(6)), rs.getObject(7) as Int?, rs.getObject(8) as Int?)) },
        runId, revision,
    )

    private fun movements(text: String): List<StopMovement> = json.readTree(text).let { arr -> (0 until arr.size()).map { i -> arr.get(i).let { StopMovement(UUID.fromString(it.get("booking_id").asString()), ResourceVector.parse(it.get("vector"))) } } }

    private fun capacityOf(j: JdbcOperations, vehicleId: UUID): ResourceVector =
        ResourceVector.parse(j.queryForObject("SELECT capacity_config::text FROM taktovia.vehicle WHERE id = ?", String::class.java, vehicleId)!!, "/vehicle/capacity_config")

    private fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    // ---- Befehl ----------------------------------------------------------------------------------------------------------------------

    private fun validateInput(cmd: ReplanCommand) {
        val v = mutableListOf<Violation>()
        if (cmd.reason.isBlank()) v += Violation("/reason", ViolationCode.REQUIRED) else if (cmd.reason.length > 500) v += Violation("/reason", ViolationCode.TOO_LONG)
        if (cmd.changes.isEmpty()) v += Violation("/changes", ViolationCode.TOO_FEW_ITEMS) else if (cmd.changes.size > 100) v += Violation("/changes", ViolationCode.TOO_MANY_ITEMS)
        cmd.changes.forEachIndexed { i, c ->
            if (c.kind != StopChangeKind.MOVE && c.bookingId == null) v += Violation("/changes/$i/booking_id", ViolationCode.REQUIRED)
            if (c.kind != StopChangeKind.REMOVE && c.newSequence == null) v += Violation("/changes/$i/new_sequence", ViolationCode.REQUIRED)
        }
        cmd.exception?.let { e ->
            if (e.rules.isEmpty()) v += Violation("/exception/rules", ViolationCode.TOO_FEW_ITEMS)
            if (e.justification.trim().length < 10) v += Violation("/exception/justification", ViolationCode.TOO_SHORT) else if (e.justification.length > 500) v += Violation("/exception/justification", ViolationCode.TOO_LONG)
        }
        if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray())
    }

    /** Löst die beteiligten Umläufe (Ziel und Quellen der übernommenen Stopps) auf; ungültiger Stopp 422. Rein lesend. */
    private fun sourcesOf(j: JdbcOperations, runId: UUID, cmd: ReplanCommand): Pair<RunRow, LinkedHashMap<UUID, UUID>> {
        val sourceRuns = LinkedHashMap<UUID, UUID>()   // Quell-Umlauf -> Fahrzeug
        val target = run(j, runId, false)
        cmd.changes.forEachIndexed { i, c ->
            if (c.kind != StopChangeKind.INSERT) return@forEachIndexed
            val src = j.query("SELECT r.id, r.vehicle_id FROM taktovia.route_stop s JOIN taktovia.vehicle_run r ON r.tenant_id = s.tenant_id AND r.id = s.run_id WHERE s.id = ?",
                { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }, c.routeStopId).firstOrNull()
                ?: throw ProblemException.invalid(Violation("/changes/$i/route_stop_id", ViolationCode.INVALID_VALUE))
            if (src.first == runId) throw ProblemException.invalid(Violation("/changes/$i/route_stop_id", ViolationCode.INVALID_VALUE, "Der Stopp gehört zum Ziel-Umlauf; Einfügen übernimmt Stopps anderer Umläufe."))
            sourceRuns[src.first] = src.second
        }
        return target to sourceRuns
    }

    override fun replan(ctx: RequestContext, runId: UUID, cmd: ReplanCommand, access: RunAccess): RunRevisionRecord {
        validateInput(cmd)
        // Vorlauf ohne Sperre: beteiligte Umläufe auflösen und die Dienstberechtigung je Fahrzeug prüfen, bevor etwas gesperrt oder geschrieben wird (nicht berechtigt = 404)
        val (target, sourceRuns) = ctxTx.run(ctx, readOnly = true) { j -> sourcesOf(j, runId, cmd) }
        (listOf(target.vehicleId) + sourceRuns.values).distinct().forEach { access.check(ctx, it) }
        return ctxTx.run(ctx) { j -> commit(j, ctx, runId, sourceRuns.keys.toList(), cmd) }
    }

    /**
     * Wie [replan], aber **in der laufenden Mandantentransaktion** [j] des Aufrufers (Störungsablauf WP-M3-54: Umbuchung und Entscheidung der Leitstelle gelten gemeinsam oder gar nicht). Die Dienstberechtigung
     * hat der Aufrufer (Incident im Dienstscope) bereits geprüft.
     */
    internal fun replanIn(j: JdbcOperations, ctx: RequestContext, runId: UUID, cmd: ReplanCommand): RunRevisionRecord {
        validateInput(cmd)
        val (_, sourceRuns) = sourcesOf(j, runId, cmd)
        return commit(j, ctx, runId, sourceRuns.keys.toList(), cmd)
    }

    private fun commit(j: JdbcOperations, ctx: RequestContext, runId: UUID, sourceIds: List<UUID>, cmd: ReplanCommand): RunRevisionRecord {
        val now = clock.instant()
        // 1. Umläufe sperren (aufsteigend), danach die Fahrzeuge gemeinsam
        val ids = (listOf(runId) + sourceIds).distinct().sortedBy { it.toString() }
        val rows = ids.associateWith { run(j, it, true) }
        rows.values.map { it.vehicleId }.distinct().sortedBy { it.toString() }.forEach { j.query("SELECT 1 FROM taktovia.vehicle WHERE id = ? FOR SHARE", { _, _ -> 1 }, it) }
        val target = rows.getValue(runId)
        // 2. Plan-CAS auf den Ziel-Umlauf: veraltet = 412 mit der aktuellen Revision
        if (target.revision != cmd.baseRevision) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Fahrtenplan wurde zwischenzeitlich geändert; aktuelle Revision ${target.revision} lesen.", headers = mapOf("ETag" to Etag.format(target.revision)))
        rows.values.firstOrNull { it.state !in RunState.PLAN_CHANGEABLE }?.let { throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Im Zustand ${it.state} ist keine Planänderung möglich.") }

        // 3. Änderungen anwenden (rein), Routeninvarianten und Kapazität je Dimension
        val oldTarget = stops(j, runId, target.revision)
        val oldSources = sourceIds.associateWith { stops(j, it, rows.getValue(it).revision) }
        val outcome = ManualPlanEditor.apply(oldTarget.map { EditStop(it.routeStopId, it.stop) }, oldSources.mapValues { e -> e.value.map { EditStop(it.routeStopId, it.stop) } }, cmd.changes)
        for ((id, newStops) in listOf(runId to outcome.target) + outcome.sources.entries.map { it.key to it.value }) checkInvariants(j, rows.getValue(id).vehicleId, newStops)
        val inserted = outcome.movedFrom.keys
        if (inserted.isNotEmpty()) checkTargetUsable(j, target, outcome.target, inserted)

        // 4. Regeln mit den im Commit wirksamen Grenzen
        val bookings = bookings(j, (oldTarget + oldSources.values.flatten()).flatMap { r -> (r.stop.boardings + r.stop.alightings).map { it.bookingId } }.toSet() + inserted)
        val params = parameters(j, ctx, cmd.changes.firstNotNullOfOrNull { c -> c.bookingId?.let { bookings[it]?.serviceId } }, now)
        val broken = linkedMapOf<ReplanRule, String>()
        val oldPlans = mapOf(runId to oldTarget.map { it.stop }) + oldSources.mapValues { e -> e.value.map { it.stop } }
        val newPlans = mapOf(runId to outcome.target) + outcome.sources
        val horizonEnd = now.plusSeconds(params.horizonMin * 60L)
        newPlans.forEach { (id, newStops) -> if (!protectedPrefixIntact(oldPlans.getValue(id), newStops, horizonEnd)) broken.putIfAbsent(ReplanRule.PROTECTED_STOP_HORIZON, "Ein Stopp innerhalb der nächsten ${params.horizonMin} Minuten würde geändert.") }
        inserted.forEach { b ->
            val done = j.queryForObject("SELECT count(*) FROM taktovia.booking_change WHERE booking_id = ? AND to_run_id IS NOT NULL", Long::class.javaObjectType, b)!!
            if (done + 1 > params.maxReassignments) broken.putIfAbsent(ReplanRule.MAX_REASSIGNMENTS, "Die Buchung wurde bereits $done-mal umgebucht (Höchstzahl ${params.maxReassignments}).")
        }
        val before = windowViolations(bookings, merge(oldPlans))
        val after = windowViolations(bookings, merge(newPlans))
        val fresh = after.filter { v -> v !in before }
        if (fresh.isNotEmpty()) broken.putIfAbsent(ReplanRule.PROMISE_WINDOW, "Zugesagte Fenster würden verletzt (${fresh.joinToString { it.second.name }}).")
        val overridden = cmd.exception?.rules?.intersect(broken.keys)?.takeIf { it.isNotEmpty() }
        broken.filterKeys { cmd.exception == null || it !in cmd.exception.rules }.entries.firstOrNull()?.let { (rule, text) ->
            throw ProblemException(when (rule) { ReplanRule.PROTECTED_STOP_HORIZON -> ErrorCode.PROTECTED_STOP_HORIZON; ReplanRule.MAX_REASSIGNMENTS -> ErrorCode.MAX_REASSIGNMENTS_EXCEEDED; ReplanRule.PROMISE_WINDOW -> ErrorCode.PROMISE_WINDOW_VIOLATION }, text)
        }

        // 5. Planvergleich und Folgen für Buchungen (auch ohne Commit)
        val touched = cmd.changes.mapNotNull { it.bookingId }.toSet()
        val impacts = newPlans.flatMap { (id, newStops) -> PlanDiff.changes(oldPlans.getValue(id), newStops, touched) }.map { c ->
            BookingImpact(c.bookingId, c.kind, c.pickupBefore, c.pickupAfter, c.dropoffBefore, c.dropoffAfter, after.filter { it.first == c.bookingId }.map { it.second }.distinct())
        }
        val oldIds = oldTarget.map { it.routeStopId }
        val exception = overridden?.let { ReplanException(it, cmd.exception!!.justification) }
        if (cmd.compareOnly) {
            val diff = ManualPlanEditor.diff(oldTarget.map { it.stop }, outcome.target, { oldIds[it] }, { UUID.nameUUIDFromBytes("$runId:${target.revision}:$it".toByteArray()) })
            return RunRevisionRecord(runId, target.revision + 1, target.revision, RevisionCause.MANUAL_CHANGE, cmd.reason, now, actor(ctx).value, diff, false, impacts, exception)
        }

        // 6. Schreiben: Quell-Umläufe, Ziel-Umlauf, Umbuchungsprotokoll, Hold-Wechsel, Audit
        val meta = PlanChangeMeta(cmd.reason, actor(ctx).value, exception?.rules?.map { it.name }?.sorted(), exception?.justification)
        sourceIds.sortedBy { it.toString() }.forEach { id ->
            plans.appendIn(j, ctx.correlationId, id, rows.getValue(id).revision, outcome.sources.getValue(id), RevisionCause.MANUAL_CHANGE, outcome.movedFrom.filterValues { it == id }.keys.toList(), meta)
        }
        val plan = plans.appendIn(j, ctx.correlationId, runId, target.revision, outcome.target, RevisionCause.MANUAL_CHANGE, touched.filter { it in inserted || it in newPlans.getValue(runId).flatMap { s -> (s.boardings + s.alightings).map { m -> m.bookingId } } }, meta)
        outcome.movedFrom.forEach { (booking, from) ->
            j.update("INSERT INTO taktovia.booking_change(tenant_id, id, booking_id, change_kind, actor, reason, from_run_id, to_run_id) VALUES (taktovia.current_tenant(), ?, ?, 'MODIFY', ?, ?, ?, ?)",
                UUID.randomUUID(), booking, actor(ctx).value, cmd.reason, from, runId)
            val (fromIdx, toIdx) = plan.intervalOf(booking) ?: throw ProblemException.invalid(Violation("/changes", ViolationCode.INVALID_VALUE, "Einstieg und Ausstieg der Buchung müssen beide übernommen werden."))
            j.query("SELECT 1 FROM taktovia.capacity_hold WHERE id = (SELECT hold_id FROM taktovia.booking WHERE id = ?) FOR UPDATE", { _, _ -> 1 }, booking)
            // Der Hold wandert mit der Buchung, solange er am Quell-Umlauf hängt (Normalfall); hängt er an einem anderen Umlauf (Altbestand), bleibt er unberührt
            j.update("UPDATE taktovia.capacity_hold SET run_id = ?, run_revision = ?, interval_from = ?, interval_to = ? WHERE run_id = ? AND id = (SELECT hold_id FROM taktovia.booking WHERE id = ?)", runId, plan.revision, fromIdx, toIdx, from, booking)
        }
        audit.record(j, AuditEntry(actor(ctx), AuditActions.DISPATCH_RUN_REVISED, ObjectRef("vehicle_run", runId), ctx.correlationId,
            mapOf("revision" to plan.revision, "previous_revision" to target.revision, "cause" to RevisionCause.MANUAL_CHANGE.name, "reason" to cmd.reason, "bookings" to impacts.size, "moved_bookings" to inserted.size)))
        exception?.let { e -> audit.record(j, AuditEntry(actor(ctx), AuditActions.DISPATCH_RUN_REPLAN_EXCEPTION, ObjectRef("vehicle_run", runId), ctx.correlationId, mapOf("revision" to plan.revision, "rules" to e.rules.map { it.name }.sorted(), "justification" to e.justification))) }
        val newIds = j.query("SELECT seq, id FROM taktovia.route_stop WHERE run_id = ? AND run_revision = ?", { rs, _ -> rs.getInt(1) to rs.getObject(2, UUID::class.java) }, runId, plan.revision).toMap()
        val diff = ManualPlanEditor.diff(oldTarget.map { it.stop }, plan.stops, { oldIds[it] }, { newIds.getValue(it) })
        return RunRevisionRecord(runId, plan.revision, target.revision, RevisionCause.MANUAL_CHANGE, cmd.reason, now, actor(ctx).value, diff, true, impacts, exception)
    }

    // ---- Prüfungen -------------------------------------------------------------------------------------------------------------------

    /** Routeninvarianten und Kapazität je Dimension: eine Überbuchung scheitert als `CAPACITY_UNAVAILABLE`, jede andere Verletzung als 422. */
    private fun checkInvariants(j: JdbcOperations, vehicleId: UUID, stops: List<PlanStop>) {
        val violations = RunPlanValidator.validate(capacityOf(j, vehicleId), stops)
        if (violations.isEmpty()) return
        violations.firstOrNull { it.code == PlanViolationCode.OVER_CAPACITY || it.code == PlanViolationCode.EQUIPMENT_MISSING }?.let {
            throw ProblemException(ErrorCode.CAPACITY_UNAVAILABLE, "Die Umplanung überbucht das Zielfahrzeug (${it.dimension}).")
        }
        throw ProblemException.invalid(Violation("/changes", ViolationCode.INVALID_VALUE, "Der Plan verletzt eine Routeninvariante (${violations.first().code})."))
    }

    /** Das Zielfahrzeug muss für jede übernommene Buchung einsetzbar sein: nicht gesperrt, aktiv, im Pool des Dienstes, Schicht und gültige Einsatzfreigabe decken die Fahrt, Dienststatus erlaubt Zuweisung, Dimension nicht entfallen. */
    private fun checkTargetUsable(j: JdbcOperations, target: RunRow, newStops: List<PlanStop>, inserted: Set<UUID>) {
        val services = bookings(j, inserted)
        inserted.forEach { b ->
            val times = newStops.filter { s -> s.boardings.any { it.bookingId == b } || s.alightings.any { it.bookingId == b } }.map { it.plannedAt }
            val window = TimeWindow(times.min(), times.max().plusSeconds(1))
            val service = services[b]?.serviceId ?: throw ProblemException.notFound()
            when (val a = resources.check(j, target.vehicleId, service, window)) {
                is VehicleAvailability.NotUsable -> throw ProblemException(ErrorCode.CONFLICT, "Das Zielfahrzeug ist für diese Buchung nicht einsetzbar (${a.reason}).")
                is VehicleAvailability.Usable -> {
                    if (!resources.isStatusDispatchable(j, target.vehicleId, window.from)) throw ProblemException(ErrorCode.CONFLICT, "Der Dienststatus des Zielfahrzeugs erlaubt keine neue Zuweisung.")
                    val demand = newStops.flatMap { it.boardings }.first { it.bookingId == b }.vector
                    if (!ResourceVector.parse(a.resource.effectiveCapacityConfig, "/vehicle/capacity_config").covers(demand))
                        throw ProblemException(ErrorCode.CAPACITY_UNAVAILABLE, "Das Zielfahrzeug stellt die benötigte Ressource nicht bereit.")
                }
            }
        }
    }

    private class Params(val horizonMin: Int, val maxReassignments: Int)

    private fun parameters(j: JdbcOperations, ctx: RequestContext, serviceId: UUID?, now: Instant): Params {
        val view = config.resolveInTransaction(j, ctx, EffectiveRequest(parameters = setOf("protected_stop_horizon_min", "max_reassignments"), serviceId = serviceId, at = now))
        val by = view.items.associateBy { it.parameter }
        fun int(name: String, default: Int) = by[name]?.takeIf { it.status == EffectiveStatus.EFFECTIVE }?.value?.let { (it as? Number)?.toInt() } ?: default
        return Params(int("protected_stop_horizon_min", PlanCommit.DEFAULT_PROTECTED_STOP_HORIZON_MIN), int("max_reassignments", PlanCommit.DEFAULT_MAX_REASSIGNMENTS))
    }

    /** Die Stopps bis zum Ende des Horizonts (auch bereits vergangene) müssen mit Haltepunkt, Bewegungen und Planzeit unverändert die ersten Stopps des neuen Plans bleiben. */
    private fun protectedPrefixIntact(old: List<PlanStop>, new: List<PlanStop>, horizonEnd: Instant): Boolean {
        fun sig(s: PlanStop) = listOf(s.stopRevisionId, s.plannedAt, s.boardings.map { it.bookingId }.sorted(), s.alightings.map { it.bookingId }.sorted())
        val prefix = old.sortedBy { it.seq }.takeWhile { !it.plannedAt.isAfter(horizonEnd) }
        val now = new.sortedBy { it.seq }
        return prefix.indices.all { i -> now.getOrNull(i)?.let { sig(it) == sig(prefix[i]) } == true }
    }

    private fun bookings(j: JdbcOperations, ids: Set<UUID>): Map<UUID, Booking> {
        if (ids.isEmpty()) return emptyMap()
        return j.query(
            "SELECT b.id, b.service_id, b.state, b.promise_snapshot::text, h.promise_window::text FROM taktovia.booking b LEFT JOIN taktovia.capacity_hold h ON h.tenant_id = b.tenant_id AND h.id = b.hold_id WHERE b.id = ANY(CAST(? AS uuid[]))",
            { rs, _ -> Booking(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4), rs.getString(5)) }, ids.joinToString(",", "{", "}"),   // nur UUIDs
        ).associateBy { it.id }
    }

    private fun merge(plans: Map<UUID, List<PlanStop>>): List<PlanStop> = plans.values.flatten()

    /**
     * Verletzte Zusagefenster je Buchung eines Planstands (bestätigte Buchungen): mit gespeichertem Hold-Fenster volle Prüfung (Abhol-/Ankunftsfenster, Karenz, Wartezeit, Umweg), sonst Abhol- und
     * Ankunftsfenster der Zusage; fehlt eine zugesagte Buchung im Plan, ist das `NOT_IN_PLAN`. Verglichen wird alt gegen neu: nur **neue** Verletzungen zählen als Verschlechterung.
     */
    private fun windowViolations(bookings: Map<UUID, Booking>, stops: List<PlanStop>): List<Pair<UUID, WindowViolationCode>> {
        val rides = PromiseWindowGuard.rides(stops)
        return bookings.values.filter { it.state in LIVE }.flatMap { b ->
            val ride = rides[b.id] ?: return@flatMap listOf(b.id to WindowViolationCode.NOT_IN_PLAN)
            val full = b.hold?.let { PromiseWindow.parse(it) }
            if (full != null) PromiseWindowGuard.check(full, ride).map { b.id to it }
            else {
                val n = json.readTree(b.snapshot)
                fun at(k: String) = OffsetDateTime.parse(n.get(k).asString()).toInstant()
                buildList {
                    if (ride.pickupAt.isBefore(at("pickup_earliest")) || ride.pickupAt.isAfter(at("pickup_latest"))) add(b.id to WindowViolationCode.PICKUP_OUTSIDE_WINDOW)
                    if (ride.dropoffAt.isBefore(at("arrival_earliest")) || ride.dropoffAt.isAfter(at("arrival_latest"))) add(b.id to WindowViolationCode.ARRIVAL_OUTSIDE_WINDOW)
                }
            }
        }
    }

    // ---- Ersatzvorschläge (Störungsablauf WP-M3-54) ------------------------------------------------------------------------------------

    /** Ergebnis der Probe einer Übernahme: Zeiten der Buchung im Zielplan, verletzte Zusagefenster und die Änderungen, die den Befehl bilden. */
    internal class Transfer(val targetRunId: UUID, val targetRevision: Long, val changes: List<StopChange>, val pickupAt: Instant, val dropoffAt: Instant, val violations: List<WindowViolationCode>)

    /**
     * Bildet die Änderungen, die die Buchung [bookingId] aus dem Plan des Quell-Umlaufs in den Zielplan [target] übernehmen (Einstieg und Ausstieg an der nach der Planzeit passenden Position). `null`, wenn die
     * Buchung im Quellplan nicht mit Einstieg und Ausstieg steht.
     */
    internal fun transferChanges(sourceStops: List<PlanStop>, sourceStopIds: Map<Int, UUID>, targetStops: List<PlanStop>, bookingId: UUID): Triple<List<StopChange>, Instant, Instant>? {
        val board = sourceStops.firstOrNull { s -> s.boardings.any { it.bookingId == bookingId } } ?: return null
        val alight = sourceStops.firstOrNull { s -> s.alightings.any { it.bookingId == bookingId } } ?: return null
        val pre = targetStops.count { !it.plannedAt.isAfter(board.plannedAt) }
        val upToDropoff = targetStops.count { !it.plannedAt.isAfter(alight.plannedAt) }
        val pos1 = pre + 1
        val pos2 = maxOf(upToDropoff + 2, pos1 + 1)
        return Triple(listOf(StopChange(StopChangeKind.INSERT, sourceStopIds.getValue(board.seq), pos1, bookingId), StopChange(StopChangeKind.INSERT, sourceStopIds.getValue(alight.seq), pos2, bookingId)),
            board.plannedAt, alight.plannedAt)
    }

    /**
     * Probe der Übernahme der Buchung [bookingId] vom Quell- in den Ziel-Umlauf, rein lesend und ohne Sperre, mit denselben Prüfungen wie der Commit (Routeninvarianten, Kapazität je Dimension, Einsetzbarkeit des
     * Zielfahrzeugs inklusive Ressourcenvektor, geschützter Stopphorizont, Höchstzahl der Umbuchungen); verletzte Zusagefenster werden gemeldet, nicht abgewiesen. Mit [ctx] gelten die wirksamen Konfigurationswerte,
     * ohne (Systemsuche) die Planungsdefaults; der Commit prüft ohnehin erneut mit den Werten zum Commit. Wirft [ProblemException], wenn die Übernahme unzulässig ist.
     */
    internal fun simulateTransfer(j: JdbcOperations, ctx: RequestContext?, targetRunId: UUID, sourceRunId: UUID, bookingId: UUID): Transfer {
        val target = run(j, targetRunId, false); val source = run(j, sourceRunId, false)
        if (target.state !in RunState.PLAN_CHANGEABLE || source.state !in RunState.PLAN_CHANGEABLE) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Im Zustand des Umlaufs ist keine Planänderung möglich.")
        val oldTarget = stops(j, targetRunId, target.revision); val oldSource = stops(j, sourceRunId, source.revision)
        val (changes, pickupAt, dropoffAt) = transferChanges(oldSource.map { it.stop }, oldSource.associate { it.stop.seq to it.routeStopId }, oldTarget.map { it.stop }, bookingId)
            ?: throw ProblemException.invalid(Violation("/booking_id", ViolationCode.INVALID_VALUE, "Die Buchung steht nicht mit Einstieg und Ausstieg im Plan des Quellumlaufs."))
        val outcome = ManualPlanEditor.apply(oldTarget.map { EditStop(it.routeStopId, it.stop) }, mapOf(sourceRunId to oldSource.map { EditStop(it.routeStopId, it.stop) }), changes)
        checkInvariants(j, target.vehicleId, outcome.target)
        checkInvariants(j, source.vehicleId, outcome.sources.getValue(sourceRunId))
        checkTargetUsable(j, target, outcome.target, setOf(bookingId))
        val now = clock.instant()
        val booking = bookings(j, setOf(bookingId))
        val params = if (ctx != null) parameters(j, ctx, booking[bookingId]?.serviceId, now) else Params(PlanCommit.DEFAULT_PROTECTED_STOP_HORIZON_MIN, PlanCommit.DEFAULT_MAX_REASSIGNMENTS)
        val horizonEnd = now.plusSeconds(params.horizonMin * 60L)
        if (!protectedPrefixIntact(oldTarget.map { it.stop }, outcome.target, horizonEnd) || !protectedPrefixIntact(oldSource.map { it.stop }, outcome.sources.getValue(sourceRunId), horizonEnd))
            throw ProblemException(ErrorCode.PROTECTED_STOP_HORIZON, "Ein Stopp innerhalb der nächsten ${params.horizonMin} Minuten würde geändert.")
        val done = j.queryForObject("SELECT count(*) FROM taktovia.booking_change WHERE booking_id = ? AND to_run_id IS NOT NULL", Long::class.javaObjectType, bookingId)!!
        if (done + 1 > params.maxReassignments) throw ProblemException(ErrorCode.MAX_REASSIGNMENTS_EXCEEDED, "Die Buchung wurde bereits $done-mal umgebucht (Höchstzahl ${params.maxReassignments}).")
        val newTarget = outcome.target
        val boardAt = newTarget.first { s -> s.boardings.any { it.bookingId == bookingId } }.plannedAt
        val alightAt = newTarget.first { s -> s.alightings.any { it.bookingId == bookingId } }.plannedAt
        val violations = windowViolations(booking, newTarget).filter { it.first == bookingId }.map { it.second }.distinct()
        return Transfer(targetRunId, target.revision, changes, boardAt, alightAt, violations)
    }

    // ---- Revisionen lesen ------------------------------------------------------------------------------------------------------------

    override fun revisions(ctx: RequestContext, runId: UUID, limit: Int, before: Long?, access: RunAccess): List<RunRevisionRecord> {
        val vehicle = ctxTx.run(ctx, readOnly = true) { j -> run(j, runId, false).vehicleId }
        access.check(ctx, vehicle)
        return ctxTx.run(ctx, readOnly = true) { j ->
            class Row(val revision: Long, val previous: Long, val cause: RevisionCause, val reason: String?, val by: String?, val at: Instant, val rules: List<String>?, val justification: String?, val id: UUID)
            val rows = j.query(
                "SELECT run_revision, previous_revision, cause, reason, created_by, recorded_at, exception_rules::text, exception_justification, id FROM taktovia.plan_change WHERE run_id = ? AND (CAST(? AS bigint) IS NULL OR run_revision < ?) ORDER BY run_revision DESC LIMIT ?",
                { rs, _ -> Row(rs.getLong(1), rs.getLong(2), RevisionCause.valueOf(rs.getString(3)), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant(),
                    rs.getString(7)?.trim('{', '}')?.split(',')?.filter { it.isNotBlank() }, rs.getString(8), rs.getObject(9, UUID::class.java)) }, runId, before, before, limit,
            )
            rows.map { r ->
                val old = stops(j, runId, r.previous); val new = stops(j, runId, r.revision)
                val diff = ManualPlanEditor.diff(old.map { it.stop }, new.map { it.stop }, { old[it].routeStopId }, { new[it].routeStopId })
                val impacts = j.query("SELECT booking_id, kind, pickup_before, pickup_after, dropoff_before, dropoff_after FROM taktovia.plan_change_booking WHERE plan_change_id = ? ORDER BY booking_id", { rs, _ ->
                    BookingImpact(rs.getObject(1, UUID::class.java), PlanBookingChangeKind.valueOf(rs.getString(2)), rs.getTimestamp(3)?.toInstant(), rs.getTimestamp(4)?.toInstant(), rs.getTimestamp(5)?.toInstant(), rs.getTimestamp(6)?.toInstant())
                }, r.id)
                RunRevisionRecord(runId, r.revision, r.previous, r.cause, r.reason, r.at, r.by ?: "system:dispatch", diff, true, impacts,
                    r.rules?.takeIf { it.isNotEmpty() }?.let { ReplanException(it.map(ReplanRule::valueOf).toSet(), r.justification ?: "") })
            }
        }
    }

    companion object {
        private val LIVE = setOf("CONFIRMED", "CONFIRMING")

        fun create(ctxTx: ContextTransactions, outbox: Outbox, audit: AuditPort, config: EffectiveConfig, clock: Clock, staffing: de.taktovia.platform.readports.ShiftStaffingReadPort?): PlanCommit =
            JdbcPlanCommit(ctxTx, audit, config, JdbcResourceAvailability(ctxTx, clock, staffing), JdbcRunPlans(ctxTx, outbox, clock), clock)
    }
}
