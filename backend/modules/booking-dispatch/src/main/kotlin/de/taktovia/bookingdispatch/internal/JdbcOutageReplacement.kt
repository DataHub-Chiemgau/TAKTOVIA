package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.BookingReason
import de.taktovia.bookingdispatch.api.CancelBooking
import de.taktovia.bookingdispatch.api.CancelOutageCommand
import de.taktovia.bookingdispatch.api.Bookings
import de.taktovia.bookingdispatch.api.IncidentGateway
import de.taktovia.bookingdispatch.api.OutageBookingState
import de.taktovia.bookingdispatch.api.OutageBookingView
import de.taktovia.bookingdispatch.api.OutageOverview
import de.taktovia.bookingdispatch.api.OutageProposalView
import de.taktovia.bookingdispatch.api.OutageReplacement
import de.taktovia.bookingdispatch.api.OutageSearchOutcome
import de.taktovia.bookingdispatch.api.OutageWindowEffect
import de.taktovia.bookingdispatch.api.RebookOutageCommand
import de.taktovia.bookingdispatch.api.ReplanCommand
import de.taktovia.bookingdispatch.api.ReplanException
import de.taktovia.bookingdispatch.api.ReplanRule
import de.taktovia.bookingdispatch.api.RunState
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.platform.api.Etag
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.tenant.TenantId
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Störungsablauf auf PostgreSQL (WP-M3-54). Schichten gegen doppelte oder widersprüchliche Wirkung:
 *  1. Je (Incident, Buchung) genau eine Zeile `outage_booking` (Unique), je Buchung höchstens ein offener Störungsfall (partieller Unique-Index);
 *  2. Entscheidung unter der Zeilensperre der betroffenen Buchung mit `If-Match` (Revision): der zweite Disponent mit überholtem Stand wird abgewiesen (412), ein schon entschiedener Fall ebenso (409);
 *  3. Umbuchung und Storno laufen über den Commit der manuellen Umplanung bzw. den Betreiberstorno in **derselben Transaktion** wie die Zustandsänderung und das Entscheidungsprotokoll;
 *  4. Mitteilung genau einmal je Änderung (`notice_revision` = Revision der Zeile, Ereignis eindeutig je Aggregat und Revision).
 * Lockreihenfolge: Zeile `outage_booking`, dann (im Commit) Umläufe aufsteigend, Fahrzeuge, Holds; die Suche sperrt keine Umläufe.
 */
internal class JdbcOutageReplacement(
    private val ctxTx: ContextTransactions, private val outbox: Outbox, private val audit: AuditPort, private val plans: JdbcPlanCommit, private val bookings: JdbcBookings,
    private val gateway: IncidentGateway, private val clock: Clock,
) : OutageReplacement {
    private val json = JsonMapper.builder().build()

    private class Row(val id: UUID, val bookingId: UUID, val runId: UUID, val vehicleId: UUID, val state: OutageBookingState, val issueCode: String?, val revision: Long, val noticeRevision: Long?)

    // ---------------- Suche (Systemaufruf) ----------------

    override fun search(tenant: TenantId, incidentId: UUID, vehicleId: UUID?): OutageSearchOutcome {
        if (vehicleId == null) return OutageSearchOutcome.NothingAffected
        return ctxTx.runSystem(tenant) { j ->
            lock(j, "outage-search|$incidentId")
            val inc = gateway.accessSystem(j, incidentId)
            val affected = register(j, incidentId, vehicleId)
            if (affected == 0) {
                inc?.let { gateway.conclude(j, incidentId, true, ACTOR_SYSTEM) }   // kein Fahrgast betroffen: nichts umzuplanen
                return@runSystem OutageSearchOutcome.NothingAffected
            }
            val proposals = computeAll(j, null, incidentId, vehicleId)
            if (proposals > 0) OutageSearchOutcome.Proposed(proposals, affected) else OutageSearchOutcome.NoneAvailable(affected)
        }
    }

    /** Legt je betroffener Buchung des Fahrzeugs eine Zeile an (einmalig); liefert die Zahl der Buchungen, die zu diesem Incident gehören. */
    private fun register(j: JdbcOperations, incidentId: UUID, vehicleId: UUID): Int {
        class Hit(val runId: UUID, val runState: RunState, val bookingId: UUID, val legState: String)
        val hits = j.query(
            "SELECT DISTINCT ON (b.id) r.id, r.state, b.id, coalesce(jl.execution_state, 'PLANNED') FROM taktovia.vehicle_run r " +
                "JOIN taktovia.route_stop s ON s.tenant_id = r.tenant_id AND s.run_id = r.id AND s.run_revision = r.revision " +
                "CROSS JOIN LATERAL jsonb_array_elements(s.boardings || s.alightings) m " +
                "JOIN taktovia.booking b ON b.tenant_id = r.tenant_id AND b.id = (m->>'booking_id')::uuid AND b.state IN ('CONFIRMED', 'CONFIRMING') " +
                "LEFT JOIN taktovia.journey_leg jl ON jl.tenant_id = b.tenant_id AND jl.journey_id = b.journey_id " +
                "WHERE r.vehicle_id = ? AND r.state NOT IN ('COMPLETED', 'CANCELED') ORDER BY b.id, r.id",
            { rs, _ -> Hit(rs.getObject(1, UUID::class.java), RunState.valueOf(rs.getString(2)), rs.getObject(3, UUID::class.java), rs.getString(4)) }, vehicleId)
        val now = Timestamp.from(clock.instant())
        hits.forEach { h ->
            val state = when {
                h.legState == "BOARDED" -> "BOARDED"
                h.runState !in RunState.PLAN_CHANGEABLE -> "OPEN_ISSUE"
                else -> "OPEN"
            }
            j.update(
                "INSERT INTO taktovia.outage_booking(tenant_id, id, incident_id, booking_id, run_id, vehicle_id, state, issue_code, created_at, updated_at) " +
                    "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                UUID.randomUUID(), incidentId, h.bookingId, h.runId, vehicleId, state, if (state == "OPEN_ISSUE") "RUN_NOT_CHANGEABLE" else null, now, now)
        }
        return j.queryForObject("SELECT count(*) FROM taktovia.outage_booking WHERE incident_id = ?", Long::class.javaObjectType, incidentId)!!.toInt()
    }

    /** Berechnet die Vorschläge aller noch entscheidbaren Buchungen des Incidents neu (neue Generation) und führt die Zustände nach; liefert die Gesamtzahl der Vorschläge. */
    private fun computeAll(j: JdbcOperations, ctx: RequestContext?, incidentId: UUID, vehicleId: UUID?): Int {
        syncBookings(j, incidentId)
        var total = 0
        rows(j, incidentId).filter { it.state.decidable }.forEach { ob ->
            val found = proposalsFor(j, ctx, ob)
            if (found.isEmpty()) {
                val code = if (runOf(j, ob.bookingId)?.second in RunState.PLAN_CHANGEABLE) "NO_REPLACEMENT" else "RUN_NOT_CHANGEABLE"
                if (ob.state != OutageBookingState.OPEN_ISSUE) setState(j, ob, "OPEN_ISSUE", code)
                else if (ob.issueCode != code) j.update("UPDATE taktovia.outage_booking SET issue_code = ?, updated_at = ? WHERE id = ?", code, Timestamp.from(clock.instant()), ob.id)
            } else {
                val gen = (j.queryForObject("SELECT coalesce(max(generation), 0) FROM taktovia.outage_proposal WHERE outage_booking_id = ?", Int::class.javaObjectType, ob.id) ?: 0) + 1
                found.forEach { p ->
                    j.update("INSERT INTO taktovia.outage_proposal(tenant_id, id, outage_booking_id, generation, vehicle_id, run_id, run_revision, pickup_at, dropoff_at, window_effect, window_violations) " +
                        "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID(), ob.id, gen, p.vehicleId, p.runId, p.runRevision, Timestamp.from(p.pickupAt), Timestamp.from(p.dropoffAt), p.windowEffect.name, p.windowViolations.takeIf { it.isNotEmpty() }?.joinToString(","))
                }
                if (ob.state != OutageBookingState.PROPOSED) setState(j, ob, "PROPOSED", null)
                total += found.size
            }
        }
        return total
    }

    /** Vorschläge für eine Buchung: andere Umläufe, für die die Probe der Übernahme (Prüfungen wie im Commit der Umplanung) besteht; nur freigegebene, geeignete Fahrzeuge. */
    private fun proposalsFor(j: JdbcOperations, ctx: RequestContext?, ob: Row): List<OutageProposalView> {
        val source = runOf(j, ob.bookingId) ?: return emptyList()
        if (source.second !in RunState.PLAN_CHANGEABLE) return emptyList()
        val targets = j.query("SELECT id FROM taktovia.vehicle_run WHERE vehicle_id <> ? AND id <> ? AND state IN ('PLANNED', 'ACTIVE') ORDER BY id LIMIT 100", { rs, _ -> rs.getObject(1, UUID::class.java) }, ob.vehicleId, source.first)
        val out = mutableListOf<OutageProposalView>()
        for (t in targets) {
            val tr = try { plans.simulateTransfer(j, ctx, t, source.first, ob.bookingId) } catch (e: ProblemException) { continue }   // nicht einsetzbar, überbucht, Horizont, Höchstzahl: kein Vorschlag
            val vehicle = j.queryForObject("SELECT vehicle_id FROM taktovia.vehicle_run WHERE id = ?", UUID::class.java, t)!!
            out += OutageProposalView(UUID.randomUUID(), vehicle, t, tr.targetRevision, tr.pickupAt, tr.dropoffAt,
                if (tr.violations.isEmpty()) OutageWindowEffect.IN_WINDOW else OutageWindowEffect.OUTSIDE_WINDOW, tr.violations.map { it.name })
        }
        return out.sortedWith(compareBy({ it.windowEffect }, { it.pickupAt }, { it.runId.toString() })).take(OutageReplacement.MAX_PROPOSALS)
    }

    /** Aktueller Umlauf einer Buchung (der des Holds; er wandert bei Umbuchungen mit) und dessen Zustand. */
    private fun runOf(j: JdbcOperations, bookingId: UUID): Pair<UUID, RunState>? = j.query(
        "SELECT r.id, r.state FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.tenant_id = b.tenant_id AND h.id = b.hold_id JOIN taktovia.vehicle_run r ON r.tenant_id = h.tenant_id AND r.id = h.run_id WHERE b.id = ?",
        { rs, _ -> rs.getObject(1, UUID::class.java) to RunState.valueOf(rs.getString(2)) }, bookingId).firstOrNull()

    /**
     * Gleicht die Zeilen mit dem Stand der Buchungen ab: eine anderweitig stornierte Buchung (Fahrgast, Betrieb) beendet ihren Fall (`CANCELLED`, Akteur `system:booking`, Code `CANCELLED_OTHERWISE`;
     * die Erstattung hat der Storno selbst ausgelöst); ein inzwischen eingestiegener Fahrgast wird `BOARDED`, ein ausgestiegener `ONBOARD_DONE`.
     */
    private fun syncBookings(j: JdbcOperations, incidentId: UUID) {
        val now = Timestamp.from(clock.instant())
        rows(j, incidentId).forEach { ob ->
            val s = j.query("SELECT b.state, coalesce(jl.execution_state, 'PLANNED') FROM taktovia.booking b LEFT JOIN taktovia.journey_leg jl ON jl.tenant_id = b.tenant_id AND jl.journey_id = b.journey_id WHERE b.id = ?",
                { rs, _ -> rs.getString(1) to rs.getString(2) }, ob.bookingId).firstOrNull() ?: return@forEach
            when {
                ob.state.decidable && s.first !in LIVE ->
                    j.update("UPDATE taktovia.outage_booking SET state = 'CANCELLED', issue_code = NULL, decision = 'CANCEL', decision_reason = 'CANCELLED_OTHERWISE', decided_by = ?, decided_at = ?, revision = revision + 1, updated_at = ? WHERE id = ?",
                        ACTOR_BOOKING, now, now, ob.id)
                ob.state.decidable && s.second == "BOARDED" -> setState(j, ob, "BOARDED", null)
                ob.state == OutageBookingState.BOARDED && (s.second == "ALIGHTED" || s.first == "COMPLETED") -> setState(j, ob, "ONBOARD_DONE", null)
            }
        }
    }

    private fun setState(j: JdbcOperations, ob: Row, state: String, issue: String?) {
        j.update("UPDATE taktovia.outage_booking SET state = ?, issue_code = ?, revision = revision + 1, updated_at = ? WHERE id = ? AND revision = ?", state, issue, Timestamp.from(clock.instant()), ob.id, ob.revision)
    }

    // ---------------- Lesen ----------------

    override fun overview(ctx: RequestContext, incidentId: UUID): OutageOverview = ctxTx.run(ctx, readOnly = true) { j ->
        val inc = gateway.access(j, ctx, incidentId, false)
        view(j, inc.id, inc.vehicleId)
    }

    override fun checkOptions(ctx: RequestContext, incidentId: UUID): OutageOverview = ctxTx.run(ctx) { j ->
        val inc = gateway.access(j, ctx, incidentId, true)
        lock(j, "outage-search|$incidentId")
        computeAll(j, ctx, incidentId, inc.vehicleId)
        audit.record(j, AuditEntry(actor(ctx), AuditActions.DISPATCH_OUTAGE_OPTIONS_CHECKED, ObjectRef("incident", incidentId), ctx.correlationId, mapOf("incident_id" to incidentId.toString())))
        view(j, inc.id, inc.vehicleId)
    }

    private fun rows(j: JdbcOperations, incidentId: UUID): List<Row> = j.query(
        "SELECT id, booking_id, run_id, vehicle_id, state, issue_code, revision, notice_revision FROM taktovia.outage_booking WHERE incident_id = ? ORDER BY created_at, id", ROW, incidentId)

    private fun view(j: JdbcOperations, incidentId: UUID, vehicleId: UUID?): OutageOverview {
        class Raw(val row: Row, val number: String?, val snapshot: String, val decision: String?, val reason: String?, val by: String?, val at: Instant?, val replacement: UUID?)
        val raws = j.query(
            "SELECT ob.id, ob.booking_id, ob.run_id, ob.vehicle_id, ob.state, ob.issue_code, ob.revision, ob.notice_revision, b.booking_no, b.promise_snapshot::text, ob.decision, ob.decision_reason, ob.decided_by, ob.decided_at, ob.replacement_run_id " +
                "FROM taktovia.outage_booking ob JOIN taktovia.booking b ON b.tenant_id = ob.tenant_id AND b.id = ob.booking_id WHERE ob.incident_id = ? ORDER BY ob.created_at, ob.id",
            { rs, i -> Raw(ROW.mapRow(rs, i)!!, rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13), rs.getTimestamp(14)?.toInstant(), rs.getObject(15, UUID::class.java)) }, incidentId)
        val ids = raws.map { it.row.id }
        val proposals = if (ids.isEmpty()) emptyMap() else j.query(
            "SELECT p.outage_booking_id, p.id, p.vehicle_id, p.run_id, p.run_revision, p.pickup_at, p.dropoff_at, p.window_effect, p.window_violations FROM taktovia.outage_proposal p " +
                "WHERE p.outage_booking_id = ANY(CAST(? AS uuid[])) AND p.generation = (SELECT max(q.generation) FROM taktovia.outage_proposal q WHERE q.outage_booking_id = p.outage_booking_id) " +
                "ORDER BY (p.window_effect = 'OUTSIDE_WINDOW'), p.pickup_at, p.run_id",
            { rs, _ -> rs.getObject(1, UUID::class.java) to OutageProposalView(rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getObject(4, UUID::class.java), rs.getLong(5),
                rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant(), OutageWindowEffect.valueOf(rs.getString(8)), rs.getString(9)?.split(',').orEmpty()) },
            ids.joinToString(",", "{", "}")).groupBy({ it.first }, { it.second })   // nur UUIDs
        val views = raws.map { r ->
            val node = json.readTree(r.snapshot)
            fun at(k: String) = node.get(k)?.takeIf { it.isString }?.let { OffsetDateTime.parse(it.asString()).toInstant() }
            val vector = node.get("resource_vector")
            val props = if (r.row.state.decidable) proposals[r.row.id].orEmpty() else emptyList()
            val actions = buildList {
                if (r.row.state.decidable) { add("CHECK_OPTIONS"); if (props.isNotEmpty()) add("REBOOK"); add("CANCEL") }
                if (r.row.state == OutageBookingState.REBOOKED && r.row.noticeRevision != r.row.revision) add("INFORM_PASSENGER")
            }
            OutageBookingView(r.row.id, r.row.bookingId, r.number, r.row.runId, r.row.vehicleId, r.row.state, r.row.issueCode, r.decision, r.reason, r.by, r.at, r.replacement, r.row.noticeRevision == r.row.revision,
                r.row.revision, vector?.get("seats")?.asInt() ?: 0, vector?.get("wheelchair")?.asInt() ?: 0, at("pickup_earliest"), at("pickup_latest"), props, actions)
        }
        return OutageOverview(incidentId, vehicleId, views)
    }

    // ---------------- Entscheidungen ----------------

    private fun code(raw: String, pointer: String) { if (!CODE.matches(raw)) throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_FORMAT)) }

    private fun lockRow(j: JdbcOperations, incidentId: UUID, bookingId: UUID, ifMatch: Long): Row {
        val row = j.query("SELECT id, booking_id, run_id, vehicle_id, state, issue_code, revision, notice_revision FROM taktovia.outage_booking WHERE incident_id = ? AND booking_id = ? FOR UPDATE", ROW, incidentId, bookingId).firstOrNull()
            ?: throw ProblemException.notFound()
        if (row.revision != ifMatch) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Fall wurde zwischenzeitlich entschieden oder geändert; aktuelle Revision ${row.revision} lesen.", headers = mapOf("ETag" to Etag.format(row.revision)))
        return row
    }

    /** Gleicht eine anderweitig stornierte Buchung vor der Entscheidung ab (eigene, festgeschriebene Transaktion): danach ist die Entscheidung gegenstandslos (409). */
    private fun syncFirst(ctx: RequestContext, incidentId: UUID, bookingId: UUID) {
        val synced = ctxTx.run(ctx) { j ->
            gateway.access(j, ctx, incidentId, true)
            val row = j.query("SELECT id, booking_id, run_id, vehicle_id, state, issue_code, revision, notice_revision FROM taktovia.outage_booking WHERE incident_id = ? AND booking_id = ? FOR UPDATE", ROW, incidentId, bookingId).firstOrNull()
                ?: throw ProblemException.notFound()
            val before = row.state
            if (before.decidable) syncBookings(j, incidentId)
            before.decidable && j.queryForObject("SELECT state FROM taktovia.outage_booking WHERE id = ?", String::class.java, row.id) == "CANCELLED"
        }
        if (synced) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Buchung wurde zwischenzeitlich anderweitig storniert; der Störungsfall wurde abgeglichen.")
    }

    private fun notDecidable(state: OutageBookingState): ProblemException = when (state) {
        OutageBookingState.BOARDED, OutageBookingState.ONBOARD_DONE -> ProblemException(ErrorCode.CONFLICT, "Der Fahrgast ist eingestiegen: keine automatische Umbuchung oder Stornierung (PASSENGER_ONBOARD).")
        else -> ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Buchung ist im Zustand $state bereits entschieden.")
    }

    override fun rebook(ctx: RequestContext, incidentId: UUID, bookingId: UUID, cmd: RebookOutageCommand): OutageBookingView {
        code(cmd.reasonCode, "/reason_code")
        syncFirst(ctx, incidentId, bookingId)
        return ctxTx.run(ctx) { j ->
            val inc = gateway.access(j, ctx, incidentId, true)
            val ob = lockRow(j, incidentId, bookingId, cmd.ifMatch)
            if (!ob.state.decidable) throw notDecidable(ob.state)
            val p = j.query("SELECT run_id, vehicle_id, window_effect FROM taktovia.outage_proposal WHERE id = ? AND outage_booking_id = ?",
                { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), OutageWindowEffect.valueOf(rs.getString(3))) }, cmd.proposalId, ob.id).firstOrNull()
                ?: throw ProblemException.invalid(Violation("/proposal_id", ViolationCode.INVALID_VALUE))
            if (p.third == OutageWindowEffect.OUTSIDE_WINDOW && !cmd.acceptWindowEffect)
                throw ProblemException(ErrorCode.PROMISE_WINDOW_VIOLATION, "Der Vorschlag verletzt das Zusagefenster; die Übernahme verlangt die ausdrückliche Annahme der Leitstelle.")
            val source = runOf(j, bookingId) ?: throw ProblemException.notFound()
            val tr = plans.simulateTransfer(j, ctx, p.first, source.first, bookingId)   // gleiche Prüfungen wie der Commit; unzulässig: ProblemException (nichts geschrieben)
            val exception = if (tr.violations.isNotEmpty() && cmd.acceptWindowEffect)
                ReplanException(setOf(ReplanRule.PROMISE_WINDOW), "Fahrzeugausfall: Abweichung vom Zusagefenster von der Leitstelle angenommen (${cmd.reasonCode})") else null
            plans.replanIn(j, ctx, p.first, ReplanCommand(tr.changes, "Fahrzeugausfall, Umbuchung auf anderes Fahrzeug (${cmd.reasonCode})", tr.targetRevision, false, exception))
            val now = clock.instant()
            val by = actor(ctx).value
            check(j.update("UPDATE taktovia.outage_booking SET state = 'REBOOKED', issue_code = NULL, decision = 'REBOOK', decision_reason = ?, decided_by = ?, decided_at = ?, replacement_run_id = ?, revision = revision + 1, updated_at = ? WHERE id = ? AND revision = ?",
                cmd.reasonCode, by, Timestamp.from(now), p.first, Timestamp.from(now), ob.id, ob.revision) == 1) { "Zeile des Störungsfalls nicht aktualisierbar" }
            audit.record(j, AuditEntry(actor(ctx), AuditActions.DISPATCH_OUTAGE_REBOOKED, ObjectRef("outage_booking", ob.id), ctx.correlationId,
                mapOf("incident_id" to incidentId.toString(), "booking_id" to bookingId.toString(), "from_run_id" to source.first.toString(), "to_run_id" to p.first.toString(), "reason_code" to cmd.reasonCode,
                    "window_effect_accepted" to (exception != null))))
            gateway.recordDecision(j, ctx, incidentId, "REBOOK_BOOKING", cmd.reasonCode)
            outbox.append(j, OutboxEvent(ob.id, ob.revision + 1, "outage.booking_rebooked", linkedMapOf("incident_id" to incidentId.toString(), "booking_id" to bookingId.toString(),
                "from_run_id" to source.first.toString(), "to_run_id" to p.first.toString(), "reason_code" to cmd.reasonCode), now, correlationId = ctx.correlationId))
            concludeIfDone(j, incidentId)
            single(j, incidentId, bookingId, inc.vehicleId)
        }
    }

    override fun cancel(ctx: RequestContext, incidentId: UUID, bookingId: UUID, cmd: CancelOutageCommand): OutageBookingView {
        code(cmd.reasonCode, "/reason_code")
        syncFirst(ctx, incidentId, bookingId)
        return ctxTx.run(ctx) { j ->
            val inc = gateway.access(j, ctx, incidentId, true)
            val ob = lockRow(j, incidentId, bookingId, cmd.ifMatch)
            if (!ob.state.decidable) throw notDecidable(ob.state)
            // Betreiberstorno mit Ursache Fahrzeugausfall über den Weg der Buchung (Erstattung, Benachrichtigung, Ereignis booking.cancelled); die Buchungsrevision liest die Transaktion selbst unter ihren Sperren
            val bookingRevision = j.queryForObject("SELECT revision FROM taktovia.booking WHERE id = ?", Long::class.javaObjectType, bookingId)!!
            bookings.cancelIn(j, ctx, bookingId, CancelBooking(bookingRevision, BookingReason.VEHICLE_OUTAGE, null))
            val now = clock.instant()
            check(j.update("UPDATE taktovia.outage_booking SET state = 'CANCELLED', issue_code = NULL, decision = 'CANCEL', decision_reason = ?, decided_by = ?, decided_at = ?, revision = revision + 1, updated_at = ? WHERE id = ? AND revision = ?",
                cmd.reasonCode, actor(ctx).value, Timestamp.from(now), Timestamp.from(now), ob.id, ob.revision) == 1) { "Zeile des Störungsfalls nicht aktualisierbar" }
            audit.record(j, AuditEntry(actor(ctx), AuditActions.DISPATCH_OUTAGE_CANCELLED, ObjectRef("outage_booking", ob.id), ctx.correlationId,
                mapOf("incident_id" to incidentId.toString(), "booking_id" to bookingId.toString(), "reason_code" to cmd.reasonCode, "cancel_reason" to BookingReason.VEHICLE_OUTAGE.name)))
            gateway.recordDecision(j, ctx, incidentId, "CANCEL_BOOKING", cmd.reasonCode)
            concludeIfDone(j, incidentId)
            single(j, incidentId, bookingId, inc.vehicleId)
        }
    }

    override fun inform(ctx: RequestContext, incidentId: UUID, bookingId: UUID, ifMatch: Long): OutageBookingView = ctxTx.run(ctx) { j ->
        val inc = gateway.access(j, ctx, incidentId, true)
        val ob = lockRow(j, incidentId, bookingId, ifMatch)
        if (ob.state != OutageBookingState.REBOOKED) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Eine Fahrgastmitteilung gibt es nur zu einer Umbuchung (der Storno löst die Mitteilung über den Stornoweg aus).")
        if (ob.noticeRevision == ob.revision) return@run single(j, incidentId, bookingId, inc.vehicleId)   // genau eine Mitteilung je Änderung: Wiederholung ohne neue Wirkung
        val now = clock.instant()
        j.update("UPDATE taktovia.outage_booking SET notice_revision = revision, updated_at = ? WHERE id = ? AND revision = ?", Timestamp.from(now), ob.id, ob.revision)
        outbox.append(j, OutboxEvent(ob.id, ob.revision, "outage.passenger_notice_requested", linkedMapOf("incident_id" to incidentId.toString(), "booking_id" to bookingId.toString(), "change" to "REBOOKED"),
            now, correlationId = ctx.correlationId))
        audit.record(j, AuditEntry(actor(ctx), AuditActions.DISPATCH_OUTAGE_NOTICE_REQUESTED, ObjectRef("outage_booking", ob.id), ctx.correlationId, mapOf("incident_id" to incidentId.toString(), "booking_id" to bookingId.toString())))
        gateway.recordDecision(j, ctx, incidentId, "INFORM_PASSENGER", "PASSENGER_NOTICE")
        single(j, incidentId, bookingId, inc.vehicleId)
    }

    private fun single(j: JdbcOperations, incidentId: UUID, bookingId: UUID, vehicleId: UUID?): OutageBookingView = view(j, incidentId, vehicleId).bookings.first { it.bookingId == bookingId }

    /** Sind alle Buchungen abgearbeitet (keine mehr offen, auch keine eingestiegene), schließt der Incident: umgebucht = `RESOLVED`, sonst `CLOSED_NO_REPLACEMENT`. */
    private fun concludeIfDone(j: JdbcOperations, incidentId: UUID) {
        val states = j.query("SELECT state FROM taktovia.outage_booking WHERE incident_id = ?", { rs, _ -> OutageBookingState.valueOf(rs.getString(1)) }, incidentId)
        if (states.isNotEmpty() && states.none { it.blocksIncident }) gateway.conclude(j, incidentId, states.any { it == OutageBookingState.REBOOKED }, ACTOR_SYSTEM)
    }

    private fun actor(ctx: RequestContext) = ActorRef("user:" + ctx.actor.subject)

    private fun lock(j: JdbcOperations, key: String) {
        j.query("SELECT pg_advisory_xact_lock(hashtextextended(taktovia.current_tenant()::text || '|' || ?, 0))", { _, _ -> 0 }, key)
    }

    companion object {
        private val CODE = Regex("^[A-Z][A-Z0-9_]{1,59}$")
        private val LIVE = setOf("CONFIRMED", "CONFIRMING")
        private const val ACTOR_SYSTEM = "system:outage"
        private const val ACTOR_BOOKING = "system:booking"
        private val ROW = org.springframework.jdbc.core.RowMapper { rs, _ ->
            Row(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getObject(4, UUID::class.java), OutageBookingState.valueOf(rs.getString(5)),
                rs.getString(6), rs.getLong(7), rs.getObject(8) as Long?)
        }

        fun create(ctxTx: ContextTransactions, outbox: Outbox, audit: AuditPort, config: EffectiveConfig, bookings: Bookings, gateway: IncidentGateway, clock: Clock,
            staffing: de.taktovia.platform.readports.ShiftStaffingReadPort?): OutageReplacement {
            require(bookings is JdbcBookings) { "Der Störungsablauf storniert in der eigenen Transaktion und braucht die Buchungsimplementierung des Moduls" }
            val plans = JdbcPlanCommit(ctxTx, audit, config, JdbcResourceAvailability(ctxTx, clock, staffing), JdbcRunPlans(ctxTx, outbox, clock), clock)
            return JdbcOutageReplacement(ctxTx, outbox, audit, plans, bookings, gateway, clock)
        }
    }
}
