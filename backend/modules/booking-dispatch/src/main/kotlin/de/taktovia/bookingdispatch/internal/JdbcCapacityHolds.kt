package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.BookingDemand
import de.taktovia.bookingdispatch.api.BookingView
import de.taktovia.bookingdispatch.api.CapacityHolds
import de.taktovia.bookingdispatch.api.Dimension
import de.taktovia.bookingdispatch.api.HoldOutcome
import de.taktovia.bookingdispatch.api.HoldView
import de.taktovia.bookingdispatch.api.PlaceHold
import de.taktovia.bookingdispatch.api.PlanViolationCode
import de.taktovia.bookingdispatch.api.PromiseCore
import de.taktovia.bookingdispatch.api.PromiseWindow
import de.taktovia.bookingdispatch.api.PromiseWindowGuard
import de.taktovia.bookingdispatch.api.RevisionCause
import de.taktovia.bookingdispatch.api.RejectionReason
import de.taktovia.bookingdispatch.api.ResourceAvailability
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RunPlanValidator
import de.taktovia.bookingdispatch.api.VehicleAvailability
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.promise.PromiseSnapshotSchema
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/**
 * Kernel für Hold und Bestätigung (WP-M2-14, Vorstufe von WP-M2-22/-23). Sperrstrategie nach Spike (a) (`rc_run_row`): READ COMMITTED, Zeilenlock auf `vehicle_run`, danach
 * Hold-Zeile; gleiche Reihenfolge überall, damit es keine Deadlocks gibt. Belegung je Abschnitt: `CONSUMED` plus `HELD` mit `now < expires_at`. Kapazität steht nur in PostgreSQL.
 */
internal class JdbcCapacityHolds(
    private val ctxTx: ContextTransactions, private val resources: ResourceAvailability, private val outbox: Outbox, private val clock: Clock, private val plans: JdbcRunPlans,
) : CapacityHolds {
    private val json = JsonMapper.builder().build()

    private class RunRow(val revision: Long, val state: String, val vehicleId: UUID)

    override fun place(ctx: RequestContext, cmd: PlaceHold): HoldOutcome = ctxTx.run(ctx) { j ->
        require(cmd.intervalTo > cmd.intervalFrom) { "Abschnittsintervall leer" }
        // 1. Lauf sperren (stabile Reihenfolge: ein Lauf je Befehl; mehrere Läufe müssten aufsteigend nach ID gesperrt werden)
        val run = j.query("SELECT revision, state, vehicle_id FROM taktovia.vehicle_run WHERE id = ? FOR UPDATE", { rs, _ -> RunRow(rs.getLong(1), rs.getString(2), rs.getObject(3, UUID::class.java)) }, cmd.runId)
            .firstOrNull() ?: throw ProblemException.notFound()
        // 2. Wiederholung derselben Anfrage findet denselben Hold
        findByReference(j, cmd.reference)?.let { return@run HoldOutcome.Placed(it, replayed = true) }
        if (run.state != RUN_ACTIVE) return@run HoldOutcome.Rejected(RejectionReason.RUN_NOT_ACTIVE, null)
        if (run.revision != cmd.expectedRunRevision) return@run HoldOutcome.Rejected(RejectionReason.REVISION_CONFLICT, null)
        // 3. Einsatzfähigkeit unter dem Lock erneut prüfen (Sperre, Freigabe, Schicht)
        val usable = resources.check(j, run.vehicleId, cmd.serviceId, cmd.window)
        if (usable !is VehicleAvailability.Usable) return@run HoldOutcome.Rejected(RejectionReason.RESOURCE_UNUSABLE, null)
        val capacity = ResourceVector.parse(usable.resource.capacityConfig, "/vehicle/capacity_config")
        cmd.reserve.validateAgainst(capacity)
        // 4. Ausstattung ist keine verbrauchbare Größe; ein Fahrzeug ohne Rollstuhlplatz fehlt die Ausstattung (Kategorie Ausstattung)
        if (!capacity.equipment.containsAll(cmd.demand.equipment)) return@run HoldOutcome.Rejected(RejectionReason.EQUIPMENT_MISSING, Dimension.EQUIPMENT)
        if (cmd.demand.wheelchair > 0 && capacity.wheelchair == 0) return@run HoldOutcome.Rejected(RejectionReason.EQUIPMENT_MISSING, Dimension.WHEELCHAIR)
        if (cmd.proposal != null) return@run placeWithPlan(j, ctx, cmd, run.revision, capacity)
        // 5. Belegung je Abschnitt und Dimension unter dem Lock prüfen (gemeinsamer Validator der Routeninvarianten, WP-M2-16): bestehende Belegung + geschützte Reserve + neuer Bedarf
        val existing = occupancy(j, cmd.runId, cmd.intervalFrom, cmd.intervalTo)
        val projected = existing.map { (s, w) -> (s + cmd.reserve.seats + cmd.demand.seats) to (w + cmd.reserve.wheelchair + cmd.demand.wheelchair) }
        val violations = RunPlanValidator.checkSegmentOccupancy(capacity, projected, cmd.intervalFrom)
        violations.firstOrNull { it.dimension == Dimension.WHEELCHAIR }?.let { return@run HoldOutcome.Rejected(RejectionReason.NO_CAPACITY_WHEELCHAIR, Dimension.WHEELCHAIR) }
        violations.firstOrNull { it.dimension == Dimension.SEATS }?.let { return@run HoldOutcome.Rejected(RejectionReason.NO_CAPACITY_SEATS, Dimension.SEATS) }
        // 6. Hold und Ereignis atomar schreiben
        val id = UUID.randomUUID()
        val expires = j.queryForObject(
            "INSERT INTO taktovia.capacity_hold(tenant_id, id, run_id, run_revision, federation_ref, capacity_vector, interval_from, interval_to, expires_at, state) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?::jsonb, ?, ?, clock_timestamp() + make_interval(secs => ?), 'HELD') RETURNING expires_at",
            Timestamp::class.java, id, cmd.runId, run.revision, cmd.reference, cmd.demand.toJson(), cmd.intervalFrom, cmd.intervalTo, cmd.ttl.toMillis() / 1000.0,
        )!!.toInstant()
        val view = HoldView(id, cmd.runId, run.revision, cmd.intervalFrom, cmd.intervalTo, cmd.demand, expires, "HELD")
        outbox.append(j, OutboxEvent(id, 1, "booking.hold_placed", linkedMapOf(
            "hold_id" to id.toString(), "run_id" to cmd.runId.toString(), "run_revision" to run.revision, "interval_from" to cmd.intervalFrom, "interval_to" to cmd.intervalTo,
            "capacity_vector" to cmd.demand.toMap(), "expires_at" to expires.toString(),
        ), clock.instant(), correlationId = ctx.correlationId))
        HoldOutcome.Placed(view, replayed = false)
    }

    /**
     * Planbasierter Hold (WP-M2-19), aufzurufen unter dem Lock des Umlaufs nach Revisions-, Zustands- und Einsatzprüfung: Routeninvarianten und alle Zusagefenster und harten Grenzen
     * (bestätigte und aktiv gehaltene Buchungen, ihre Fenster stehen unveränderlich in `capacity_hold.promise_window`) gegen den neuen Plan; dann Revision speichern und Hold anlegen.
     */
    private fun placeWithPlan(j: JdbcOperations, ctx: RequestContext, cmd: PlaceHold, currentRevision: Long, capacity: ResourceVector): HoldOutcome {
        val p = cmd.proposal!!
        val planViolations = RunPlanValidator.validate(capacity, p.stops)
        if (planViolations.isNotEmpty()) {
            val over = planViolations.filter { it.code == PlanViolationCode.OVER_CAPACITY }
            return when {
                over.any { it.dimension == Dimension.WHEELCHAIR } -> HoldOutcome.Rejected(RejectionReason.NO_CAPACITY_WHEELCHAIR, Dimension.WHEELCHAIR)
                over.any { it.dimension == Dimension.SEATS } -> HoldOutcome.Rejected(RejectionReason.NO_CAPACITY_SEATS, Dimension.SEATS)
                planViolations.any { it.code == PlanViolationCode.EQUIPMENT_MISSING } -> HoldOutcome.Rejected(RejectionReason.EQUIPMENT_MISSING, Dimension.EQUIPMENT)
                else -> HoldOutcome.Rejected(RejectionReason.PLAN_INVALID, null)
            }
        }
        if (PromiseWindowGuard.check(p.stops, protectedWindows(j, cmd.runId) + p.window).isNotEmpty()) return HoldOutcome.Rejected(RejectionReason.PROMISE_WINDOW_VIOLATION, null)
        val plan = plans.appendIn(j, ctx, cmd.runId, currentRevision, p.stops, RevisionCause.BOOKING_INSERTED, listOf(p.window.bookingId))
        val (from, to) = plan.intervalOf(p.window.bookingId) ?: throw IllegalArgumentException("Die Buchung des Zusagefensters steht nicht im Plan")
        val id = UUID.randomUUID()
        val expires = j.queryForObject(
            "INSERT INTO taktovia.capacity_hold(tenant_id, id, run_id, run_revision, federation_ref, capacity_vector, interval_from, interval_to, expires_at, state, promise_window) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?::jsonb, ?, ?, clock_timestamp() + make_interval(secs => ?), 'HELD', ?::jsonb) RETURNING expires_at",
            Timestamp::class.java, id, cmd.runId, plan.revision, cmd.reference, cmd.demand.toJson(), from, to, cmd.ttl.toMillis() / 1000.0, p.window.toJson(),
        )!!.toInstant()
        outbox.append(j, OutboxEvent(id, 1, "booking.hold_placed", linkedMapOf(
            "hold_id" to id.toString(), "run_id" to cmd.runId.toString(), "run_revision" to plan.revision, "interval_from" to from, "interval_to" to to,
            "capacity_vector" to cmd.demand.toMap(), "expires_at" to expires.toString(),
        ), clock.instant(), correlationId = ctx.correlationId))
        return HoldOutcome.Placed(HoldView(id, cmd.runId, plan.revision, from, to, cmd.demand, expires, "HELD"), replayed = false)
    }

    override fun protectedWindows(ctx: RequestContext, runId: UUID): List<PromiseWindow> = ctxTx.run(ctx, readOnly = true) { j -> protectedWindows(j, runId) }

    /** Zusagefenster aller bestätigten und aktiv gehaltenen (nicht abgelaufenen) Buchungen des Umlaufs; für Prüfungen unter dem Lock des Umlaufs. */
    private fun protectedWindows(j: JdbcOperations, runId: UUID): List<PromiseWindow> = j.query(
        "SELECT promise_window::text FROM taktovia.capacity_hold WHERE run_id = ? AND promise_window IS NOT NULL AND (state = 'CONSUMED' OR (state = 'HELD' AND expires_at > clock_timestamp()))",
        { rs, _ -> PromiseWindow.parse(rs.getString(1)) }, runId,
    )

    /** Belegung (Sitze, Rollstuhlplätze) je Abschnitt `[from, to)`: bestätigte Holds plus gültige gehaltene (Serverzeit); aufzurufen unter dem Lock des Umlaufs. */
    private fun occupancy(j: JdbcOperations, runId: UUID, from: Int, to: Int): List<Pair<Int, Int>> = j.query(
        "SELECT s.seg, coalesce(sum((h.capacity_vector->>'seats')::int), 0), coalesce(sum((h.capacity_vector->>'wheelchair')::int), 0) " +
            "FROM generate_series(?::int, ?::int) AS s(seg) LEFT JOIN taktovia.capacity_hold h ON h.tenant_id = taktovia.current_tenant() AND h.run_id = ? " +
            "AND h.interval_from <= s.seg AND h.interval_to > s.seg AND (h.state = 'CONSUMED' OR (h.state = 'HELD' AND h.expires_at > clock_timestamp())) GROUP BY s.seg ORDER BY s.seg",
        { rs, _ -> rs.getInt(2) to rs.getInt(3) }, from, to - 1, runId,
    )

    private fun findByReference(j: JdbcOperations, reference: String): HoldView? = j.query(
        "SELECT id, run_id, run_revision, interval_from, interval_to, capacity_vector::text, expires_at, state FROM taktovia.capacity_hold WHERE federation_ref = ?",
        { rs, _ -> HoldView(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getLong(3), rs.getInt(4), rs.getInt(5), ResourceVector.parse(rs.getString(6)), rs.getTimestamp(7).toInstant(), rs.getString(8)) },
        reference,
    ).firstOrNull()

    override fun confirm(ctx: RequestContext, holdId: UUID, serviceId: UUID, promise: PromiseCore, demand: BookingDemand): BookingView = ctxTx.run(ctx) { j ->
        // Sperrreihenfolge wie beim Hold: erst der Lauf, dann die Hold-Zeile
        val runId = j.query("SELECT run_id FROM taktovia.capacity_hold WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, holdId).firstOrNull() ?: throw ProblemException.notFound()
        j.query("SELECT 1 FROM taktovia.vehicle_run WHERE id = ? FOR UPDATE", { rs, _ -> rs.getInt(1) }, runId)
        class HoldRow(val state: String, val expired: Boolean, val vector: String, val runRevision: Long, val reference: String, val from: Int, val to: Int, val window: String?)
        val hold = j.query(
            "SELECT state, expires_at <= clock_timestamp(), capacity_vector::text, run_revision, federation_ref, interval_from, interval_to, promise_window::text FROM taktovia.capacity_hold WHERE id = ? FOR UPDATE",
            { rs, _ -> HoldRow(rs.getString(1), rs.getBoolean(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getString(8)) }, holdId,
        ).first()
        existingBooking(j, holdId)?.let { return@run it }
        when {
            hold.state == "HELD" && hold.expired -> throw ProblemException(ErrorCode.HOLD_EXPIRED, "Die Reservierung ist abgelaufen.")
            hold.state != "HELD" -> throw ProblemException(if (hold.state == "EXPIRED") ErrorCode.HOLD_EXPIRED else ErrorCode.INVALID_STATE_TRANSITION)
        }
        // Routeninvariante (gemeinsamer Validator, WP-M2-16): die Belegung der Abschnitte des Holds liegt unter dem Lock innerhalb der Fahrzeugkapazität
        val capacity = ResourceVector.parse(j.queryForObject(
            "SELECT v.capacity_config::text FROM taktovia.vehicle_run r JOIN taktovia.vehicle v ON v.tenant_id = r.tenant_id AND v.id = r.vehicle_id WHERE r.id = ?", String::class.java, runId)!!, "/vehicle/capacity_config")
        val window = hold.window?.let { PromiseWindow.parse(it) }
        if (window == null) {
            if (RunPlanValidator.checkSegmentOccupancy(capacity, occupancy(j, runId, hold.from, hold.to), hold.from).isNotEmpty())
                throw ProblemException(ErrorCode.CAPACITY_UNAVAILABLE, "Die Kapazität des Fahrzeugs ist auf dem Abschnitt nicht mehr verfügbar.")
        } else {
            // planbasierter Hold (WP-M2-19): erneute Prüfung gegen die dann aktuelle Planrevision, Routeninvarianten sowie alle Zusagefenster und harten Grenzen
            val current = plans.planIn(j, runId, j.queryForObject("SELECT revision FROM taktovia.vehicle_run WHERE id = ?", Long::class.java, runId)!!)
            if (RunPlanValidator.validate(capacity, current.stops).isNotEmpty() || PromiseWindowGuard.check(current.stops, protectedWindows(j, runId) + window).isNotEmpty())
                throw ProblemException(ErrorCode.CAPACITY_UNAVAILABLE, "Der aktuelle Fahrtenplan verletzt eine Kapazität oder ein zugesagtes Zeitfenster.")
            fun at(s: String) = java.time.OffsetDateTime.parse(s).toInstant()
            require(at(promise.pickupEarliest) == window.pickupEarliest && at(promise.pickupLatest) == window.pickupLatest &&
                at(promise.arrivalEarliest) == window.arrivalEarliest && at(promise.arrivalLatest) == window.arrivalLatest) { "Zusage weicht vom gehaltenen Zusagefenster ab" }
        }
        // Zusage: Kernfelder plus Ressourcenvektor des Holds, geprüft gegen das Zusage-Schema; danach unveränderlich
        val vector = ResourceVector.parse(hold.vector)
        require(vector == demand.vector) { "Bedarf der Buchung weicht vom gehaltenen Vektor ab" }
        val snapshot = json.createObjectNode().also {
            it.put("pickup_earliest", promise.pickupEarliest); it.put("pickup_latest", promise.pickupLatest)
            it.put("arrival_earliest", promise.arrivalEarliest); it.put("arrival_latest", promise.arrivalLatest)
            it.put("gross_minor", promise.grossMinor); it.put("currency", promise.currency)
            it.put("cancellation_policy_ref", promise.cancellationPolicyRef); it.put("vehicle_class", promise.vehicleClass)
            it.set("resource_vector", json.readTree(vector.toJson()))
        }
        val violations = PromiseSnapshotSchema.validate(PromiseSnapshotSchema.CURRENT, snapshot)
        if (violations.isNotEmpty()) throw ProblemException.invalid(*violations.toTypedArray())
        j.update("UPDATE taktovia.capacity_hold SET state = 'CONSUMED' WHERE id = ?", holdId)
        val bookingId = window?.bookingId ?: UUID.randomUUID(); val snapshotText = json.writeValueAsString(snapshot)
        j.update(
            "INSERT INTO taktovia.booking(tenant_id, id, hold_id, service_id, federation_ref, state, promise_snapshot, revision) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, 'CONFIRMING', ?::jsonb, 1)",
            bookingId, holdId, serviceId, hold.reference, snapshotText,
        )
        val kinds = List(demand.vector.seats) { "SEAT" } + List(demand.vector.wheelchair) { "WHEELCHAIR" }
        val codes = demand.assistance.map { it.name }.sorted().joinToString(",", "{", "}")
        kinds.forEachIndexed { i, kind ->
            j.update("INSERT INTO taktovia.passenger_trip(tenant_id, id, booking_id, seq, place_kind, assistance_codes) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, CAST(? AS text[]))", UUID.randomUUID(), bookingId, i + 1, kind, codes)
        }
        outbox.append(j, OutboxEvent(bookingId, 1, "booking.confirmed", linkedMapOf(
            "booking_id" to bookingId.toString(), "hold_id" to holdId.toString(), "service_id" to serviceId.toString(), "run_id" to runId.toString(), "run_revision" to hold.runRevision,
            "pickup_earliest" to promise.pickupEarliest, "pickup_latest" to promise.pickupLatest, "arrival_latest" to promise.arrivalLatest,
            "gross_minor" to promise.grossMinor, "currency" to promise.currency, "capacity_vector" to vector.toMap(),
        ), clock.instant(), correlationId = ctx.correlationId))
        BookingView(bookingId, holdId, "CONFIRMING", 1, snapshotText, kinds.mapIndexed { i, k -> i + 1 to k })
    }

    private fun existingBooking(j: JdbcOperations, holdId: UUID): BookingView? = j.query(
        "SELECT id, state, revision, promise_snapshot::text FROM taktovia.booking WHERE hold_id = ?",
        { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(4)) to rs.getLong(3) }, holdId,
    ).firstOrNull()?.let { (t, rev) ->
        val trips = j.query("SELECT seq, place_kind FROM taktovia.passenger_trip WHERE booking_id = ? ORDER BY seq", { rs, _ -> rs.getInt(1) to rs.getString(2) }, t.first)
        BookingView(t.first, holdId, t.second, rev, t.third, trips)
    }

    companion object { const val RUN_ACTIVE = "ACTIVE" }
}
