package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.AssignVehicleRole
import de.taktovia.fleetsafety.api.RoleAt
import de.taktovia.fleetsafety.api.VehicleRole
import de.taktovia.fleetsafety.api.VehicleRoleAssignmentView
import de.taktovia.fleetsafety.api.VehicleRoleSnapshot
import de.taktovia.fleetsafety.api.VehicleRoles
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
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
 * Fahrzeugrollen mit Gültigkeitsintervallen (WP-M1-34, LH-I.4-MUL-004). Mandantentrennung: RLS und zusammengesetzte Schlüssel (Fahrzeug und Organisation müssen
 * zum Mandanten gehören). Serialisierung: jede Änderung sperrt die Fahrzeugzeile (`FOR NO KEY UPDATE`), damit zwei Wechsel desselben Fahrzeugs nacheinander
 * entschieden werden; die Datenbank sichert Überlappungsfreiheit zusätzlich per Exclusion Constraint ab.
 */
internal class JdbcVehicleRoles(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock,
) : VehicleRoles {

    override fun assign(ctx: RequestContext, cmd: AssignVehicleRole): VehicleRoleAssignmentView {
        if (cmd.validTo != null && !cmd.validTo.isAfter(cmd.validFrom)) throw ProblemException.invalid(Violation("/valid_to", ViolationCode.INTERVAL_INVALID))
        try {
            return ctxTx.run(ctx) { j -> doAssign(j, actor(ctx), ctx.correlationId, cmd) }
        } catch (e: DataIntegrityViolationException) {
            if (sqlState(e) == "23P01") throw overlap(e)   // Exclusion Constraint als Rückfallebene
            throw e
        }
    }

    override fun list(ctx: RequestContext, vehicleId: UUID?, asOf: Instant?, limit: Int, after: Pair<Instant, UUID>?): List<VehicleRoleAssignmentView> = ctxTx.run(ctx, readOnly = true) { j ->
        // feste Anweisung mit optionalen Filtern (NULL = kein Filter); alle Werte gebunden
        val asOfTs = asOf?.let(Timestamp::from); val afterTs = after?.first?.let(Timestamp::from)
        j.query(
            "SELECT $COLUMNS FROM taktovia.vehicle_assignment a WHERE (CAST(? AS uuid) IS NULL OR a.vehicle_id = CAST(? AS uuid)) " +
                "AND (CAST(? AS timestamptz) IS NULL OR (a.valid_from <= CAST(? AS timestamptz) AND (a.valid_to IS NULL OR a.valid_to > CAST(? AS timestamptz)))) " +
                "AND (CAST(? AS timestamptz) IS NULL OR (a.created_at, a.id) > (CAST(? AS timestamptz), CAST(? AS uuid))) ORDER BY a.created_at, a.id LIMIT ?",
            MAPPER, vehicleId, vehicleId, asOfTs, asOfTs, asOfTs, afterTs, afterTs, after?.second, limit + 1,
        )
    }

    override fun rolesAt(ctx: RequestContext, vehicleId: UUID, at: Instant): VehicleRoleSnapshot = ctxTx.run(ctx, readOnly = true) { j -> rolesAt(j, vehicleId, at) }

    override fun rolesAt(jdbc: JdbcOperations, vehicleId: UUID, at: Instant): VehicleRoleSnapshot {
        // ein Statement: Fahrzeug und Zuordnungen kommen aus demselben Datenstand (READ COMMITTED sieht je Statement einen konsistenten Snapshot)
        val rows = jdbc.query(
            "SELECT v.id AS vehicle_id, a.id, a.organization_id, a.role, a.valid_from, a.valid_to, a.revision, a.created_at " +
                "FROM taktovia.vehicle v LEFT JOIN taktovia.vehicle_assignment a ON a.tenant_id = v.tenant_id AND a.vehicle_id = v.id " +
                "AND a.valid_from <= ? AND (a.valid_to IS NULL OR a.valid_to > ?) WHERE v.id = ?",
            { rs, _ -> if (rs.getString("role") == null) null else MAPPER.mapRow(rs, 0) }, Timestamp.from(at), Timestamp.from(at), vehicleId,
        )
        if (rows.isEmpty()) throw ProblemException.notFound()   // Fahrzeug unbekannt oder fremder Mandant (RLS)
        val byRole = rows.filterNotNull().associateBy { it.role }
        return VehicleRoleSnapshot(vehicleId, at, VehicleRole.entries.associateWith { r -> byRole[r]?.let { RoleAt(it.id, it.organizationId, it.validFrom, it.validTo) } })
    }

    @AuditRequired
    private fun doAssign(j: JdbcOperations, actor: ActorRef, correlationId: UUID, cmd: AssignVehicleRole): VehicleRoleAssignmentView {
        // 1. Fahrzeug sperren: unbekannt oder fremder Mandant -> 404; serialisiert gleichzeitige Wechsel desselben Fahrzeugs
        if (j.query("SELECT 1 FROM taktovia.vehicle WHERE id = ? FOR NO KEY UPDATE", { _, _ -> 1 }, cmd.vehicleId).isEmpty()) throw ProblemException.notFound()
        // 2. Organisation des Mandanten? Eine Organisation eines anderen Mandanten ist nicht auffindbar (404)
        if (j.queryForObject("SELECT count(*) FROM taktovia.organization WHERE id = ?", Int::class.java, cmd.organizationId) != 1) throw ProblemException.notFound()
        val from = Timestamp.from(cmd.validFrom); val to = cmd.validTo?.let(Timestamp::from)

        // 3. Das laufende (unbegrenzte) Intervall der Rolle, das vor dem Stichtag begann, wird zum Stichtag geschlossen (Wechsel)
        val predecessor = j.query("SELECT $COLUMNS FROM taktovia.vehicle_assignment a WHERE a.vehicle_id = ? AND a.role = ? AND a.valid_to IS NULL AND a.valid_from < ?", MAPPER,
            cmd.vehicleId, cmd.role.name, from).firstOrNull()

        // 4. Überschneidung mit dem Rest: abgeschlossen (Ende in der Vergangenheit) -> 422 (rückwirkend), sonst laufend/künftig -> 409
        val overlaps = j.query(
            "SELECT $COLUMNS, (a.valid_to IS NOT NULL AND a.valid_to <= clock_timestamp()) AS finished FROM taktovia.vehicle_assignment a " +
                "WHERE a.vehicle_id = ? AND a.role = ? AND tstzrange(a.valid_from, a.valid_to) && tstzrange(?, ?) AND a.id IS DISTINCT FROM ?",
            { rs, _ -> MAPPER.mapRow(rs, 0)!! to rs.getBoolean("finished") }, cmd.vehicleId, cmd.role.name, from, to, predecessor?.id)
        if (overlaps.any { it.second }) throw ProblemException(ErrorCode.VALIDATION_FAILED,
            "Das Intervall überschneidet ein bereits abgeschlossenes Intervall dieser Rolle; abgeschlossene Zuordnungen sind unveränderlich.", listOf(Violation("/valid_from", ViolationCode.INVALID_VALUE)))
        if (overlaps.isNotEmpty()) throw overlap(null)

        if (predecessor != null) j.update("UPDATE taktovia.vehicle_assignment SET valid_to = ? WHERE id = ?", from, predecessor.id)
        val id = UUID.randomUUID()
        val view = j.query(
            "INSERT INTO taktovia.vehicle_assignment AS a (tenant_id, id, vehicle_id, organization_id, role, valid_from, valid_to, created_by) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?) RETURNING $COLUMNS",
            MAPPER, id, cmd.vehicleId, cmd.organizationId, cmd.role.name, from, to, actor.value,
        ).single()

        // 5. genau ein Audit und genau ein Outbox-Ereignis je Änderung
        audit.record(j, AuditEntry(actor, AuditActions.VEHICLE_ROLE_ASSIGNMENT_CHANGED, ObjectRef("vehicle", cmd.vehicleId), correlationId,
            mapOf("role" to view.role.name, "assignment_id" to view.id.toString(), "organization_id" to view.organizationId.toString(), "valid_from" to view.validFrom.toString(),
                "valid_to" to view.validTo?.toString(), "closed_assignment_id" to predecessor?.id?.toString())))
        outbox.append(j, OutboxEvent(view.id, view.revision, "vehicle.role_assignment_changed", linkedMapOf(
            "vehicle_id" to view.vehicleId.toString(), "role" to view.role.name, "assignment_id" to view.id.toString(), "organization_id" to view.organizationId.toString(),
            "valid_from" to view.validFrom.toString(), "valid_to" to view.validTo?.toString(),
            "closed_assignment_id" to predecessor?.id?.toString(), "closed_valid_to" to predecessor?.let { cmd.validFrom.toString() }, "revision" to view.revision,
        ), clock.instant(), correlationId = correlationId))
        return view
    }

    private fun overlap(cause: Throwable?) = ProblemException(ErrorCode.CONFLICT, "Das Intervall überschneidet eine laufende oder künftige Zuordnung derselben Rolle dieses Fahrzeugs.", cause = cause)

    private fun sqlState(e: Throwable): String? = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    private fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    private companion object {
        const val COLUMNS = "a.id, a.vehicle_id, a.organization_id, a.role, a.valid_from, a.valid_to, a.revision, a.created_at"
        val MAPPER = RowMapper { rs, _ ->
            VehicleRoleAssignmentView(
                rs.getObject("id", UUID::class.java), rs.getObject("vehicle_id", UUID::class.java), rs.getObject("organization_id", UUID::class.java),
                VehicleRole.valueOf(rs.getString("role")), rs.getTimestamp("valid_from").toInstant(), rs.getTimestamp("valid_to")?.toInstant(),
                rs.getLong("revision"), rs.getTimestamp("created_at").toInstant(),
            )
        }
    }
}
