package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.CapacityLayout
import de.taktovia.fleetsafety.api.CapacityPatch
import de.taktovia.fleetsafety.api.CapacityView
import de.taktovia.fleetsafety.api.ConfiguredVector
import de.taktovia.fleetsafety.api.VehicleCapacities
import de.taktovia.fleetsafety.api.notFound
import de.taktovia.fleetsafety.api.VehicleMasterInput
import de.taktovia.fleetsafety.api.VehicleMasterView
import de.taktovia.fleetsafety.api.Vehicles
import de.taktovia.fleetsafety.api.Violations
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
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Fahrzeugstamm (WP-M3-03, LH-6.8-FLT-001) und Kapazitätskonfiguration (WP-M3-04, LH-6.8-FLT-004). Mandantentrennung: RLS und zusammengesetzte Schlüssel. Jede Änderung sperrt die
 * Fahrzeugzeile (`FOR NO KEY UPDATE`) und vergleicht die erwartete Revision unter der Sperre: bei zwei gleichzeitigen Änderungen derselben Revision gewinnt genau eine, die andere
 * erhält 412. Ein Hold (`FOR SHARE` auf derselben Zeile) wartet auf eine laufende Änderung und prüft danach gegen den neuen Stand; bestehende Holds ändert die Änderung nie.
 */
internal class JdbcVehicles(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock,
) : Vehicles, VehicleCapacities {

    // ---------------- Fahrzeugstamm ----------------

    override fun create(ctx: RequestContext, input: VehicleMasterInput): VehicleMasterView {
        try {
            return ctxTx.run(ctx) { j -> doCreate(j, actor(ctx), ctx.correlationId, input) }
        } catch (e: DataIntegrityViolationException) {
            if (sqlState(e) == "23505") throw duplicateLabel(e)   // gleichzeitige Anlage derselben Kennung: genau eine gewinnt
            throw e
        }
    }

    override fun get(ctx: RequestContext, vehicleId: UUID): VehicleMasterView = ctxTx.run(ctx, readOnly = true) { j -> load(j, vehicleId, lock = false) ?: notFound() }

    override fun list(ctx: RequestContext, active: Boolean?, limit: Int, after: Pair<Instant, UUID>?): List<VehicleMasterView> = ctxTx.run(ctx, readOnly = true) { j ->
        val afterTs = after?.first?.let(Timestamp::from)
        j.query(
            "SELECT $COLUMNS FROM taktovia.vehicle v WHERE (CAST(? AS boolean) IS NULL OR v.active = CAST(? AS boolean)) " +
                "AND (CAST(? AS timestamptz) IS NULL OR (v.created_at, v.id) > (CAST(? AS timestamptz), CAST(? AS uuid))) ORDER BY v.created_at, v.id LIMIT ?",
            MAPPER, active, active, afterTs, afterTs, after?.second, limit + 1,
        )
    }

    override fun update(ctx: RequestContext, vehicleId: UUID, expectedRevision: Long, input: VehicleMasterInput): VehicleMasterView {
        try {
            return ctxTx.run(ctx) { j -> doUpdate(j, actor(ctx), ctx.correlationId, vehicleId, expectedRevision, input) }
        } catch (e: DataIntegrityViolationException) {
            if (sqlState(e) == "23505") throw duplicateLabel(e)
            throw e
        }
    }

    override fun isActive(jdbc: JdbcOperations, vehicleId: UUID): Boolean =
        jdbc.query("SELECT active FROM taktovia.vehicle WHERE id = ?", { rs, _ -> rs.getBoolean(1) }, vehicleId).firstOrNull() ?: false

    @AuditRequired
    private fun doCreate(j: JdbcOperations, actor: ActorRef, correlationId: UUID, input: VehicleMasterInput): VehicleMasterView {
        requireOrganization(j, input.operatorOrganizationId)
        val id = UUID.randomUUID()
        val config = ConfiguredVector("BASE", input.seats, input.wheelchair, input.equipment.sorted()).toJson()
        val view = j.query(
            "INSERT INTO taktovia.vehicle AS v (tenant_id, id, owner_org_ref, capacity_config, capacity_schema_version, vehicle_label, vehicle_type, equipment, active, created_by) " +
                "VALUES (taktovia.current_tenant(), ?, ?, CAST(? AS jsonb), '1.0', ?, ?, $EQUIPMENT_PARAM, ?, ?) RETURNING $COLUMNS",
            MAPPER, id, input.operatorOrganizationId.toString(), config, input.label, input.type, equipmentParam(input.equipment), equipmentParam(input.equipment), input.active, actor.value,
        ).single()
        audit.record(j, AuditEntry(actor, AuditActions.VEHICLE_CREATED, ObjectRef("vehicle", id), correlationId, auditDetails(view)))
        outbox.append(j, OutboxEvent(id, view.revision, "vehicle.created", eventPayload(view), clock.instant(), correlationId = correlationId))
        return view
    }

    @AuditRequired
    private fun doUpdate(j: JdbcOperations, actor: ActorRef, correlationId: UUID, id: UUID, expected: Long, input: VehicleMasterInput): VehicleMasterView {
        val before = load(j, id, lock = true) ?: notFound()   // unbekannt oder fremder Mandant (RLS): 404; die Sperre serialisiert gleichzeitige Änderungen
        if (before.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Das Fahrzeug wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        requireOrganization(j, input.operatorOrganizationId)
        val layoutRow = before.hasCapacityLayout
        if (layoutRow && (before.seats != input.seats || before.wheelchair != input.wheelchair || before.equipment.toSet() != input.equipment)) {
            throw ProblemException(ErrorCode.CONFLICT, "Kapazität und Ausstattung werden über die Kapazitätskonfiguration gepflegt (/capacity), solange eine Konfiguration besteht.",
                listOf(Violation("/capacity", ViolationCode.INVALID_VALUE)))
        }
        // bei vorhandener Konfiguration bleiben capacity_config und equipment unverändert (aus der aktiven Kombination abgeleitet)
        val config = ConfiguredVector("BASE", input.seats, input.wheelchair, input.equipment.sorted()).toJson()
        val view = (if (layoutRow) {
            j.query(
                "UPDATE taktovia.vehicle AS v SET owner_org_ref = ?, vehicle_label = ?, vehicle_type = ?, active = ?, master_revision = master_revision + 1, updated_at = clock_timestamp() " +
                    "WHERE v.id = ? AND v.master_revision = ? RETURNING $COLUMNS",
                MAPPER, input.operatorOrganizationId.toString(), input.label, input.type, input.active, id, expected,
            )
        } else {
            j.query(
                "UPDATE taktovia.vehicle AS v SET owner_org_ref = ?, vehicle_label = ?, vehicle_type = ?, active = ?, capacity_config = CAST(? AS jsonb), equipment = $EQUIPMENT_PARAM, " +
                    "master_revision = master_revision + 1, updated_at = clock_timestamp() WHERE v.id = ? AND v.master_revision = ? RETURNING $COLUMNS",
                MAPPER, input.operatorOrganizationId.toString(), input.label, input.type, input.active, config, equipmentParam(input.equipment), equipmentParam(input.equipment), id, expected,
            )
        }).singleOrNull() ?: throw ProblemException(ErrorCode.REVISION_MISMATCH, "Das Fahrzeug wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        val deactivated = before.active && !view.active
        audit.record(j, AuditEntry(actor, if (deactivated) AuditActions.VEHICLE_DEACTIVATED else AuditActions.VEHICLE_CHANGED, ObjectRef("vehicle", id), correlationId,
            auditDetails(view) + mapOf("previous_revision" to before.revision, "was_active" to before.active)))
        outbox.append(j, OutboxEvent(id, view.revision, if (deactivated) "vehicle.deactivated" else "vehicle.changed", eventPayload(view), clock.instant(), correlationId = correlationId))
        return view
    }

    // ---------------- Kapazitätskonfiguration ----------------

    override fun resourceVectors(jdbc: JdbcOperations, vehicleId: UUID): List<ConfiguredVector> = capacity(jdbc, vehicleId, lock = false).vectors

    private fun capacity(j: JdbcOperations, vehicleId: UUID, lock: Boolean): CapacityView {
        val mapper = RowMapper { rs, _ -> Triple(rs.getString("layout"), rs.getString("config"), rs.getLong("master_revision")) }
        val row = (if (lock) j.query(CAPACITY_ROW_LOCK, mapper, vehicleId) else j.query(CAPACITY_ROW, mapper, vehicleId)).firstOrNull() ?: notFound()
        val effective = parseVector("BASE", row.second)
        if (row.first == null) return CapacityView(vehicleId, null, effective, listOf(effective), row.third)
        val layout = CapacityLayout.parse(readMap(row.first!!))
        return CapacityView(vehicleId, layout, layout.effective(), layout.vectors(), row.third)
    }

    override fun getCapacity(ctx: RequestContext, vehicleId: UUID): CapacityView = ctxTx.run(ctx, readOnly = true) { j -> capacity(j, vehicleId, lock = false) }

    override fun updateCapacity(ctx: RequestContext, vehicleId: UUID, expectedRevision: Long, patch: CapacityPatch): CapacityView =
        ctxTx.run(ctx) { j -> doUpdateCapacity(j, actor(ctx), ctx.correlationId, vehicleId, expectedRevision, patch) }

    @AuditRequired
    private fun doUpdateCapacity(j: JdbcOperations, actor: ActorRef, correlationId: UUID, id: UUID, expected: Long, patch: CapacityPatch): CapacityView {
        val before = capacity(j, id, lock = true)
        if (before.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Das Fahrzeug wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        val cur = before.layout
        val v = Violations()
        if (cur == null) {   // erste Konfiguration: Stellplätze und Kombinationen sind Pflicht
            if (patch.spaces == null) v.add("/spaces", ViolationCode.REQUIRED)
            if (patch.combinations == null) v.add("/combinations", ViolationCode.REQUIRED)
            if (patch.activeCombination == null) v.add("/active_combination", ViolationCode.REQUIRED)
            v.throwIfAny()
        }
        val equipment = patch.equipment ?: cur?.equipment ?: currentEquipment(j, id)
        val merged = CapacityLayout.of(
            patch.spaces ?: cur!!.spaces, equipment, patch.combinations ?: cur!!.combinations, patch.activeCombination ?: cur!!.activeCombination,
            patch.restrictions ?: cur?.restrictions ?: emptyList(),
        )
        val effective = merged.effective()
        val updated = j.update(
            "UPDATE taktovia.vehicle SET capacity_layout = CAST(? AS jsonb), capacity_config = CAST(? AS jsonb), capacity_schema_version = '1.0', equipment = $EQUIPMENT_PARAM, " +
                "master_revision = master_revision + 1, updated_at = clock_timestamp() WHERE id = ? AND master_revision = ?",
            MAPPER_JSON.writeValueAsString(merged.toMap()), effective.toJson(), equipmentParam(merged.equipment), equipmentParam(merged.equipment), id, expected,
        )
        if (updated != 1) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Das Fahrzeug wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        val newRevision = expected + 1
        val parts = listOfNotNull(patch.spaces?.let { "spaces" }, patch.equipment?.let { "equipment" }, patch.combinations?.let { "combinations" },
            patch.activeCombination?.let { "active_combination" }, patch.restrictions?.let { "restrictions" })
        audit.record(j, AuditEntry(actor, AuditActions.VEHICLE_CAPACITY_CHANGED, ObjectRef("vehicle", id), correlationId,
            mapOf("changed_parts" to parts, "active_combination" to merged.activeCombination, "seats" to effective.seats, "wheelchair" to effective.wheelchair, "previous_revision" to expected)))
        val view = load(j, id, lock = false)!!
        outbox.append(j, OutboxEvent(id, newRevision, "vehicle.changed", eventPayload(view) + mapOf("change_kind" to "CAPACITY"), clock.instant(), correlationId = correlationId))
        return CapacityView(id, merged, effective, merged.vectors(), newRevision)
    }

    // ---------------- Hilfen ----------------

    private fun currentEquipment(j: JdbcOperations, id: UUID): Set<String> =
        j.queryForObject("SELECT coalesce(array_to_string(equipment, ','), '') FROM taktovia.vehicle WHERE id = ?", String::class.java, id)!!.split(',').filter { it.isNotEmpty() }.toSet()

    private fun requireOrganization(j: JdbcOperations, org: UUID) {
        // Organisation des eigenen Mandanten (RLS): eine fremde ist nicht auffindbar und nicht speicherbar (Feldfehler, keine Existenzauskunft)
        if (j.queryForObject("SELECT count(*) FROM taktovia.organization WHERE id = ?", Int::class.java, org) != 1)
            throw ProblemException.invalid(Violation("/operator_organization_id", ViolationCode.INVALID_VALUE))
    }

    private fun load(j: JdbcOperations, id: UUID, lock: Boolean): VehicleMasterView? =
        (if (lock) j.query(VEHICLE_ROW_LOCK, MAPPER, id) else j.query(VEHICLE_ROW, MAPPER, id)).firstOrNull()

    private fun duplicateLabel(cause: Throwable) = ProblemException(ErrorCode.CONFLICT, "Die Kennung ist in diesem Mandanten bereits vergeben.", listOf(Violation("/vehicle_label", ViolationCode.NOT_UNIQUE)), cause = cause)

    private fun auditDetails(v: VehicleMasterView): Map<String, Any?> = linkedMapOf(
        "vehicle_label" to v.label, "vehicle_type" to v.type, "operator_organization_id" to v.operatorOrganizationId?.toString(), "seats" to v.seats, "wheelchair" to v.wheelchair,
        "equipment" to v.equipment, "active" to v.active, "revision" to v.revision,
    )

    private fun eventPayload(v: VehicleMasterView): Map<String, Any?> = linkedMapOf(
        "vehicle_id" to v.id.toString(), "vehicle_label" to v.label, "vehicle_type" to v.type, "operator_organization_id" to v.operatorOrganizationId?.toString(),
        "seats" to v.seats, "wheelchair" to v.wheelchair, "equipment" to v.equipment, "active" to v.active, "revision" to v.revision,
    )

    private fun sqlState(e: Throwable): String? = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    private fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    private fun equipmentParam(e: Set<String>) = e.sorted().joinToString(",")

    @Suppress("UNCHECKED_CAST")
    private fun readMap(json: String): Map<String, Any?> = MAPPER_JSON.readValue(json, Map::class.java) as Map<String, Any?>

    private fun parseVector(code: String, json: String): ConfiguredVector {
        val m = readMap(json)
        return ConfiguredVector(code, (m["seats"] as? Number)?.toInt() ?: 0, (m["wheelchair"] as? Number)?.toInt() ?: 0, (m["equipment"] as? List<*>)?.map { it.toString() } ?: emptyList())
    }

    private companion object {
        val MAPPER_JSON: JsonMapper = JsonMapper.builder().build()
        const val EQUIPMENT_PARAM = "CASE WHEN ? = '' THEN '{}'::text[] ELSE string_to_array(?, ',') END"
        const val COLUMNS = "v.id, v.vehicle_label, v.vehicle_type, v.owner_org_ref, v.capacity_config::text AS capacity_config, coalesce(array_to_string(v.equipment, ','), '') AS equipment, " +
            "v.active, v.active_block, v.safety_revision, (v.capacity_layout IS NOT NULL) AS has_layout, v.master_revision, v.created_at, v.updated_at"
        const val VEHICLE_ROW = "SELECT $COLUMNS FROM taktovia.vehicle v WHERE v.id = ?"
        const val VEHICLE_ROW_LOCK = "$VEHICLE_ROW FOR NO KEY UPDATE"
        const val CAPACITY_ROW = "SELECT v.capacity_layout::text AS layout, v.capacity_config::text AS config, v.master_revision FROM taktovia.vehicle v WHERE v.id = ?"
        const val CAPACITY_ROW_LOCK = "$CAPACITY_ROW FOR NO KEY UPDATE"
        val MAPPER = RowMapper { rs, _ ->
            val cfg = MAPPER_JSON.readValue(rs.getString("capacity_config"), Map::class.java)
            VehicleMasterView(
                rs.getObject("id", UUID::class.java), rs.getString("vehicle_label"), rs.getString("vehicle_type"),
                rs.getString("owner_org_ref")?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                (cfg["seats"] as? Number)?.toInt() ?: 0, (cfg["wheelchair"] as? Number)?.toInt() ?: 0,
                rs.getString("equipment").split(',').filter { it.isNotEmpty() }, rs.getBoolean("active"), rs.getBoolean("active_block"), rs.getLong("safety_revision"),
                rs.getBoolean("has_layout"), rs.getLong("master_revision"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
            )
        }
    }
}
