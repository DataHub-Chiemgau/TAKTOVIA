package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.AssignPoolService
import de.taktovia.fleetsafety.api.CommittedRide
import de.taktovia.fleetsafety.api.PoolAssignmentView
import de.taktovia.fleetsafety.api.PoolMember
import de.taktovia.fleetsafety.api.PoolVerdict
import de.taktovia.fleetsafety.api.VehiclePool
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.geo.GeoPoint
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Fahrzeugpool (WP-M3-06, LH-6.8-FLT-002) auf `vehicle_pool_assignment` (M2 OP-43, additiv erweitert um `service_area_id`; die Rollentabelle `vehicle_assignment` aus WP-M1-34 bleibt
 * davon getrennt). Mandantentrennung: RLS und zusammengesetzte Schlüssel (Mandant, Dienst, Gebiet, Fahrzeug). Serialisierung: jede Änderung sperrt die Fahrzeugzeile
 * (`FOR NO KEY UPDATE`), der Hold hält sie `FOR SHARE`; das Überlappungsverbot je (Dienst, Fahrzeug) sichert die Datenbank zusätzlich per Exclusion Constraint ab.
 * Zugesagte Fahrten (Tabellen `booking`, `capacity_hold`, `vehicle_run` des Moduls `booking-dispatch`) werden nur gelesen (Hinweis beim Beenden), nie geändert.
 */
internal class JdbcVehiclePool(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock,
) : VehiclePool {

    override fun assign(ctx: RequestContext, cmd: AssignPoolService): PoolAssignmentView {
        if (cmd.validTo != null && !cmd.validTo.isAfter(cmd.validFrom)) throw ProblemException.invalid(Violation("/valid_to", ViolationCode.INTERVAL_INVALID))
        try {
            return ctxTx.run(ctx) { j -> doAssign(j, actor(ctx), ctx.correlationId, cmd) }
        } catch (e: DataIntegrityViolationException) {
            if (sqlState(e) == "23P01") throw overlap(e)   // Exclusion Constraint als Rückfallebene (zwei gleichzeitige Anlagen)
            throw e
        } catch (e: org.springframework.dao.DeadlockLoserDataAccessException) {
            // Zwei gleichzeitige, einander überschneidende Anlagen können sich am Exclusion Constraint gegenseitig erwarten; PostgreSQL bricht eine ab (40P01): dasselbe Ergebnis wie 409
            throw overlap(e)
        }
    }

    override fun end(ctx: RequestContext, vehicleId: UUID, assignmentId: UUID, expectedRevision: Long, validTo: Instant): PoolAssignmentView =
        ctxTx.run(ctx) { j -> doEnd(j, actor(ctx), ctx.correlationId, vehicleId, assignmentId, expectedRevision, validTo) }

    override fun list(ctx: RequestContext, vehicleId: UUID, asOf: Instant?, limit: Int, after: Pair<Instant, UUID>?): List<PoolAssignmentView> = ctxTx.run(ctx, readOnly = true) { j ->
        if (j.query("SELECT 1 FROM taktovia.vehicle WHERE id = ?", { _, _ -> 1 }, vehicleId).isEmpty()) throw ProblemException.notFound()
        val asOfTs = asOf?.let(Timestamp::from); val afterTs = after?.first?.let(Timestamp::from)
        j.query(
            "SELECT $COLUMNS FROM taktovia.vehicle_pool_assignment a WHERE a.vehicle_id = ? " +
                "AND (CAST(? AS timestamptz) IS NULL OR (a.valid_from <= CAST(? AS timestamptz) AND (a.valid_to IS NULL OR a.valid_to > CAST(? AS timestamptz)))) " +
                "AND (CAST(? AS timestamptz) IS NULL OR (a.created_at, a.id) > (CAST(? AS timestamptz), CAST(? AS uuid))) ORDER BY a.created_at, a.id LIMIT ?",
            MAPPER, vehicleId, asOfTs, asOfTs, asOfTs, afterTs, afterTs, after?.second, limit + 1,
        ).map { withRides(j, it) }
    }

    override fun eligibleVehicles(ctx: RequestContext, serviceId: UUID, at: Instant, locations: List<GeoPoint>): List<PoolMember> = ctxTx.run(ctx, readOnly = true) { j ->
        // ein Statement: Zuordnungen und Gebiete stammen aus demselben Datenstand; nur zulässige Fahrzeuge
        j.query(
            "SELECT a.vehicle_id, a.id, a.service_area_id FROM taktovia.vehicle_pool_assignment a WHERE a.service_id = ? " +
                "AND a.valid_from <= ? AND (a.valid_to IS NULL OR a.valid_to > ?) AND (a.service_area_id IS NULL OR $AREA_OK) ORDER BY a.vehicle_id",
            { rs, _ -> PoolMember(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java)) },
            serviceId, Timestamp.from(at), Timestamp.from(at), GeoPoint.toJsonArray(locations), Timestamp.from(at), Timestamp.from(at),
        )
    }

    override fun check(ctx: RequestContext, vehicleId: UUID, serviceId: UUID, at: Instant, locations: List<GeoPoint>): PoolVerdict =
        ctxTx.run(ctx, readOnly = true) { j -> check(j, vehicleId, serviceId, at, locations) }

    override fun check(jdbc: JdbcOperations, vehicleId: UUID, serviceId: UUID, at: Instant, locations: List<GeoPoint>): PoolVerdict {
        val ts = Timestamp.from(at)
        val rows = jdbc.query(
            "SELECT (a.service_area_id IS NULL OR $AREA_OK) FROM taktovia.vehicle_pool_assignment a WHERE a.vehicle_id = ? AND a.service_id = ? " +
                "AND a.valid_from <= ? AND (a.valid_to IS NULL OR a.valid_to > ?)",
            { rs, _ -> rs.getBoolean(1) }, GeoPoint.toJsonArray(locations), ts, ts, vehicleId, serviceId, ts, ts,
        )
        return when {
            rows.isEmpty() -> PoolVerdict.NOT_ASSIGNED
            rows.any { it } -> PoolVerdict.ELIGIBLE
            else -> PoolVerdict.OUTSIDE_SERVICE_AREA
        }
    }

    @AuditRequired
    private fun doAssign(j: JdbcOperations, actor: ActorRef, correlationId: UUID, cmd: AssignPoolService): PoolAssignmentView {
        // 1. Fahrzeug sperren: unbekannt oder fremder Mandant -> 404; serialisiert Anlegen, Beenden und Hold desselben Fahrzeugs
        if (j.query("SELECT 1 FROM taktovia.vehicle WHERE id = ? FOR NO KEY UPDATE", { _, _ -> 1 }, cmd.vehicleId).isEmpty()) throw ProblemException.notFound()
        // 2. Dienst und Gebiet des Mandanten? Fremde sind nicht auffindbar (404); ein Gebiet eines anderen Dienstes desselben Mandanten ist ein Feldfehler (422)
        if (j.query("SELECT 1 FROM taktovia.service WHERE id = ?", { _, _ -> 1 }, cmd.serviceId).isEmpty()) throw ProblemException.notFound()
        if (cmd.serviceAreaId != null) {
            val areaService = j.query("SELECT service_id FROM taktovia.service_area WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, cmd.serviceAreaId).firstOrNull() ?: throw ProblemException.notFound()
            if (areaService != cmd.serviceId) throw ProblemException.invalid(Violation("/service_area_id", ViolationCode.INVALID_VALUE))
        }
        val from = Timestamp.from(cmd.validFrom); val to = cmd.validTo?.let(Timestamp::from)
        // 3. Überschneidung im selben Dienst: 409 (andere Dienste sind unabhängig)
        val overlaps = j.queryForObject(
            "SELECT count(*) FROM taktovia.vehicle_pool_assignment WHERE vehicle_id = ? AND service_id = ? AND tstzrange(valid_from, valid_to) && tstzrange(?, ?)",
            Int::class.java, cmd.vehicleId, cmd.serviceId, from, to,
        )!!
        if (overlaps > 0) throw overlap(null)
        val id = UUID.randomUUID()
        val view = j.query(
            "INSERT INTO taktovia.vehicle_pool_assignment AS a (tenant_id, id, service_id, vehicle_id, service_area_id, valid_from, valid_to, created_by) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?) RETURNING $COLUMNS",
            MAPPER, id, cmd.serviceId, cmd.vehicleId, cmd.serviceAreaId, from, to, actor.value,
        ).single()
        // 4. genau ein Audit und genau ein Outbox-Ereignis je Änderung
        audit.record(j, AuditEntry(actor, AuditActions.POOL_ASSIGNMENT_CREATED, ObjectRef("vehicle", view.vehicleId), correlationId, details(view)))
        outbox.append(j, OutboxEvent(view.id, view.revision, "vehicle.pool_assignment_created", details(view) + ("revision" to view.revision), clock.instant(), correlationId = correlationId))
        return view
    }

    @AuditRequired
    private fun doEnd(j: JdbcOperations, actor: ActorRef, correlationId: UUID, vehicleId: UUID, assignmentId: UUID, expected: Long, validTo: Instant): PoolAssignmentView {
        if (j.query("SELECT 1 FROM taktovia.vehicle WHERE id = ? FOR NO KEY UPDATE", { _, _ -> 1 }, vehicleId).isEmpty()) throw ProblemException.notFound()
        val cur = j.query("SELECT $COLUMNS FROM taktovia.vehicle_pool_assignment a WHERE a.id = ? AND a.vehicle_id = ?", MAPPER, assignmentId, vehicleId).firstOrNull() ?: throw ProblemException.notFound()
        if (cur.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Zuordnung wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        if (cur.validTo != null) throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Zuordnung ist bereits beendet.")
        if (!validTo.isAfter(cur.validFrom)) throw ProblemException.invalid(Violation("/valid_to", ViolationCode.INTERVAL_INVALID))
        // Ein Ende in der Vergangenheit würde bereits abgewickelte Fahrten nachträglich ohne Zuordnung stellen: nicht zulässig.
        if (validTo.isBefore(clock.instant())) throw ProblemException.invalid(Violation("/valid_to", ViolationCode.INVALID_VALUE))
        j.update("UPDATE taktovia.vehicle_pool_assignment SET valid_to = ? WHERE id = ?", Timestamp.from(validTo), assignmentId)
        val ended = withRides(j, j.query("SELECT $COLUMNS FROM taktovia.vehicle_pool_assignment a WHERE a.id = ?", MAPPER, assignmentId).single())
        val rides = ended.committedRidesAfterEnd.map { it.bookingId.toString() }
        audit.record(j, AuditEntry(actor, AuditActions.POOL_ASSIGNMENT_ENDED, ObjectRef("vehicle", ended.vehicleId), correlationId, details(ended) + ("committed_ride_count" to rides.size)))
        outbox.append(j, OutboxEvent(ended.id, ended.revision, "vehicle.pool_assignment_ended", details(ended) + mapOf("revision" to ended.revision, "committed_ride_count" to rides.size, "committed_booking_ids" to rides), clock.instant(), correlationId = correlationId))
        return ended
    }

    /** Bei beendeten Zuordnungen: zugesagte Fahrten des Dienstes auf diesem Fahrzeug mit Abholung (spätestens) ab dem Ende. Nur lesend; die Fahrten bleiben unverändert. */
    private fun withRides(j: JdbcOperations, a: PoolAssignmentView): PoolAssignmentView {
        val end = a.validTo ?: return a
        val rides = j.query(
            "SELECT b.id, b.state, (b.promise_snapshot->>'pickup_earliest')::timestamptz AS pe, (b.promise_snapshot->>'pickup_latest')::timestamptz AS pl " +
                "FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.tenant_id = b.tenant_id AND h.id = b.hold_id " +
                "JOIN taktovia.vehicle_run r ON r.tenant_id = h.tenant_id AND r.id = h.run_id " +
                "WHERE r.vehicle_id = ? AND b.service_id = ? AND h.state = 'CONSUMED' AND b.state NOT IN ('FAILED', 'CANCELED', 'COMPLETED', 'COMPENSATING') " +
                "AND (b.promise_snapshot->>'pickup_latest')::timestamptz >= ? ORDER BY pe, b.id LIMIT 500",
            { rs, _ -> CommittedRide(rs.getObject("id", UUID::class.java), rs.getString("state"), rs.getTimestamp("pe").toInstant(), rs.getTimestamp("pl").toInstant()) },
            a.vehicleId, a.serviceId, Timestamp.from(end),
        )
        return a.copy(committedRidesAfterEnd = rides)
    }

    private fun details(v: PoolAssignmentView): Map<String, Any?> = linkedMapOf(
        "vehicle_id" to v.vehicleId.toString(), "service_id" to v.serviceId.toString(), "assignment_id" to v.id.toString(), "service_area_id" to v.serviceAreaId?.toString(),
        "valid_from" to v.validFrom.toString(), "valid_to" to v.validTo?.toString(),
    )

    private fun overlap(cause: Throwable?) = ProblemException(ErrorCode.CONFLICT, "Das Fahrzeug ist diesem Dienst im Zeitraum bereits zugeordnet.", cause = cause)

    private fun sqlState(e: Throwable): String? = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    private fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    private companion object {
        const val COLUMNS = "a.id, a.vehicle_id, a.service_id, a.service_area_id, a.valid_from, a.valid_to, a.revision, a.created_at"

        /**
         * Gebietsprüfung (Planungsannahme PA-02/OP-25): die zum Zeitpunkt t gültige Revision der Gebietskette (`COALESCE(area_key, id)`) der referenzierten Zeile muss alle übergebenen Punkte
         * enthalten (`ST_Covers`: der Rand zählt dazu). Ohne Punkt, ohne gültige Revision oder bei einem Punkt außerhalb: falsch. Bindungen in Reihenfolge: Punkte (JSON), t, t.
         */
        const val AREA_OK = "(SELECT count(*) > 0 AND bool_and(ST_Covers(g.area, ST_SetSRID(ST_MakePoint((pt.value->>0)::float8, (pt.value->>1)::float8), 4326))) " +
            "FROM taktovia.service_area a0 JOIN taktovia.service_area g ON g.tenant_id = a0.tenant_id AND g.service_id = a0.service_id AND COALESCE(g.area_key, g.id) = COALESCE(a0.area_key, a0.id) " +
            "CROSS JOIN LATERAL jsonb_array_elements(CAST(? AS jsonb)) AS pt(value) " +
            "WHERE a0.tenant_id = a.tenant_id AND a0.id = a.service_area_id AND g.valid_from <= ? AND (g.valid_to IS NULL OR g.valid_to > ?))"

        val MAPPER = RowMapper { rs, _ ->
            PoolAssignmentView(
                rs.getObject("id", UUID::class.java), rs.getObject("vehicle_id", UUID::class.java), rs.getObject("service_id", UUID::class.java), rs.getObject("service_area_id", UUID::class.java),
                rs.getTimestamp("valid_from").toInstant(), rs.getTimestamp("valid_to")?.toInstant(), rs.getLong("revision"), rs.getTimestamp("created_at").toInstant(),
            )
        }
    }
}
