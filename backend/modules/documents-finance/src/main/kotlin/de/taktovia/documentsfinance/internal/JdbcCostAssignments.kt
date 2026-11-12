package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.AssignCosts
import de.taktovia.documentsfinance.api.CostAssignments
import de.taktovia.documentsfinance.api.CostObjectKind
import de.taktovia.documentsfinance.api.CostObjectView
import de.taktovia.documentsfinance.api.ExpenseView
import de.taktovia.documentsfinance.api.IntegrityState
import de.taktovia.documentsfinance.api.ReleaseBlocker
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_READ
import de.taktovia.documentsfinance.internal.DocumentRows.SCOPE_WRITE
import de.taktovia.documentsfinance.internal.DocumentRows.isFinance
import de.taktovia.documentsfinance.internal.DocumentRows.require
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.authz.ScopedObject
import de.taktovia.platform.authz.ServiceScopeAuthorizer
import de.taktovia.platform.authz.ServiceScoped
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.time.Clock
import java.util.UUID

/** Lesen der Kostenzuordnung eines Belegs (Freigabehindernisse und Kostenobjekte), geteilt von Beleglesen und Zuordnung. */
internal object CostAssignmentRows {
    const val COLUMNS = "o.id, o.kind, o.business_ref, o.name, o.legal_entity_id, o.vehicle_id, o.service_id, o.active"

    val MAPPER = RowMapper { rs, _ ->
        CostObjectView(rs.getObject("id", UUID::class.java), CostObjectKind.valueOf(rs.getString("kind")), rs.getString("business_ref"), rs.getString("name"),
            rs.getObject("legal_entity_id", UUID::class.java), rs.getObject("vehicle_id", UUID::class.java), rs.getObject("service_id", UUID::class.java), rs.getBoolean("active"))
    }

    fun assigned(j: JdbcOperations, documentId: UUID): List<CostObjectView> =
        j.query("SELECT $COLUMNS FROM taktovia.expense_cost_assignment a JOIN taktovia.cost_object o ON o.tenant_id = a.tenant_id AND o.id = a.cost_object_id WHERE a.document_id = ? ORDER BY o.id", MAPPER, documentId)

    /** Freigabehindernisse aus der Kostenzuordnung: keine Zuordnung, oder ein zugeordnetes Kostenobjekt wurde inzwischen deaktiviert (nie ein freigabefähiger Beleg mit inaktivem Kostenobjekt). */
    fun blockers(objects: List<CostObjectView>): List<ReleaseBlocker> = buildList {
        if (objects.isEmpty()) add(ReleaseBlocker.COST_ASSIGNMENT_MISSING)
        if (objects.any { !it.active }) add(ReleaseBlocker.COST_OBJECT_INACTIVE)
    }
}

/**
 * Kostenzuordnung (WP-M4-29). Die Zuordnung sperrt die Belegzeile (`FOR UPDATE`, serialisiert parallele Zuordnungen und Korrekturen) und die betroffenen Kostenobjekte (`FOR SHARE`, ein
 * gleichzeitiges Deaktivieren wartet); Mandanten-, Rechtsträger- und Artbindung sichern zusätzlich zusammengesetzte Fremdschlüssel in der Datenbank.
 */
@ServiceScoped
internal class JdbcCostAssignments(private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock) : CostAssignments {

    override fun assign(ctx: RequestContext, documentId: UUID, expectedRevision: Long, cmd: AssignCosts): ExpenseView {
        require(ctx, SCOPE_WRITE)
        if (cmd.costObjectIds.isEmpty() && cmd.vehicleIds.isEmpty()) throw ProblemException.invalid(Violation("/cost_object_ids", ViolationCode.TOO_FEW_ITEMS))
        if (cmd.costObjectIds.size + cmd.vehicleIds.size > MAX_OBJECTS) throw ProblemException.invalid(Violation("/cost_object_ids", ViolationCode.TOO_MANY_ITEMS))
        return ctxTx.run(ctx) { j -> doAssign(j, ctx, documentId, expectedRevision, cmd) }
    }

    @AuditRequired
    private fun doAssign(j: JdbcOperations, ctx: RequestContext, documentId: UUID, expected: Long, cmd: AssignCosts): ExpenseView {
        val row = DocumentRows.visible(j, ctx, documentId, lock = true)
        if (row.view.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Beleg wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        when {
            row.stateRaw == "QUARANTINED" || row.stateRaw == "QUARANTINE_REJECTED" -> throw ProblemException(ErrorCode.CONFLICT, "Ein Beleg in Quarantäne erlaubt keine Kostenzuordnung.")
            row.stateRaw != "STORED" -> throw ProblemException(ErrorCode.IMMUTABLE_RECORD, "Der Beleg ist freigegeben oder gebucht; die Kostenzuordnung ist unveränderlich.")
            row.view.integrity == IntegrityState.VIOLATED -> throw IntegrityChecksSupport.violation()
        }
        val entity = row.view.legalEntityId ?: throw ProblemException(ErrorCode.CONFLICT, "Der Beleg hat keinen Rechtsträger.")
        val ids = LinkedHashSet(cmd.costObjectIds)
        for (vehicle in cmd.vehicleIds) ids += vehicleCostObject(j, ctx, vehicle, entity)
        val objects = loadLocked(j, ids)
        val finance = isFinance(ctx)
        objects.forEach { o ->
            // nicht erlaubte oder nicht sichtbare Objekte verhalten sich wie nicht vorhanden (404), nie als Hinweis auf Existenz
            if (!finance && o.kind != CostObjectKind.VEHICLE) throw ProblemException.notFound()
            if (o.serviceId != null) ServiceScopeAuthorizer.requireWrite(j, ctx, ScopedObject("cost_object", o.id, ctx.tenant, o.serviceId))
        }
        objects.forEach { o ->
            val pointer = "/cost_object_ids"
            if (o.legalEntityId != entity) throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_VALUE, "Kostenobjekt gehört zu einem anderen Rechtsträger als der Beleg"))
            if (!o.active) throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_VALUE, "Kostenobjekt ist inaktiv"))
        }
        val before = CostAssignmentRows.assigned(j, documentId)
        if (before.map { it.id }.toSet() == objects.map { it.id }.toSet()) return view(j, ctx, documentId)   // keine Änderung: keine neue Revision
        j.update("DELETE FROM taktovia.expense_cost_assignment WHERE document_id = ?", documentId)
        val by = ctx.actor.subject.take(200)
        objects.forEach { o ->
            j.update("INSERT INTO taktovia.expense_cost_assignment (tenant_id, document_id, cost_object_id, legal_entity_id, cost_object_kind, assigned_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?)",
                documentId, o.id, entity, o.kind.name, by)
        }
        val revision = expected + 1
        check(j.update("UPDATE taktovia.expense_document SET revision = ? WHERE id = ? AND revision = ? AND state = 'STORED'", revision, documentId, expected) == 1)
        val change = linkedMapOf<String, Any?>("old" to before.map { it.id.toString() }, "new" to objects.map { it.id.toString() })
        j.update("INSERT INTO taktovia.document_revision (tenant_id, id, document_id, revision, field_values, edited_by, reason) VALUES (taktovia.current_tenant(),?,?,?,?::jsonb,?,?)",
            UUID.randomUUID(), documentId, revision, ExpenseFieldCodec.toJson(mapOf("cost_assignment" to change)), by, "COST_ASSIGNMENT")
        audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_EXPENSE_COST_ASSIGNED, ObjectRef("expense", documentId), ctx.correlationId,
            mapOf("revision" to revision, "cost_object_count" to objects.size)))
        outbox.append(j, OutboxEvent(documentId, revision, "expense.cost_assigned", linkedMapOf("document_id" to documentId.toString(), "revision" to revision, "cost_object_count" to objects.size,
            "kinds" to objects.map { it.kind.name }.toSortedSet().toList(), "assigned_at" to clock.instant().toString()), clock.instant(), correlationId = ctx.correlationId))
        return view(j, ctx, documentId)
    }

    /** Fahrzeug-Kostenobjekt zum Rechtsträger: vorhanden oder angelegt (paralleles Anlegen: der Unique-Index lässt genau eines zu). Ein fremdes oder unbekanntes Fahrzeug ist 404. */
    private fun vehicleCostObject(j: JdbcOperations, ctx: RequestContext, vehicleId: UUID, entity: UUID): UUID {
        if (j.queryForObject("SELECT count(*) FROM taktovia.vehicle WHERE id = ?", Int::class.java, vehicleId) != 1) throw ProblemException.notFound()
        j.update("INSERT INTO taktovia.cost_object (tenant_id, id, legal_entity_id, kind, business_ref, vehicle_id, created_by) VALUES (taktovia.current_tenant(), ?, ?, 'VEHICLE', ?, ?, ?) ON CONFLICT DO NOTHING",
            UUID.randomUUID(), entity, "$vehicleId/$entity", vehicleId, DocumentRows.actor(ctx).value)
        return j.queryForObject("SELECT id FROM taktovia.cost_object WHERE kind = 'VEHICLE' AND vehicle_id = ? AND legal_entity_id = ?", UUID::class.java, vehicleId, entity)!!
    }

    /** Kostenobjekte sperren (`FOR SHARE`) und lesen; fehlende (auch fremde) Kennungen und nicht zuordenbare Arten: 404; nur Kostenstellen und Fahrzeuge sind zuordenbar. */
    private fun loadLocked(j: JdbcOperations, ids: Set<UUID>): List<CostObjectView> {
        val arr = ids.joinToString(",", "{", "}")
        val found = j.query("SELECT $COLUMNS FROM taktovia.cost_object o WHERE o.id = ANY(CAST(? AS uuid[])) AND o.kind IN ('VEHICLE', 'COST_CENTER') ORDER BY o.id FOR SHARE OF o", CostAssignmentRows.MAPPER, arr)
        if (found.size != ids.size) throw ProblemException.notFound()
        return found
    }

    override fun assigned(ctx: RequestContext, documentId: UUID): List<CostObjectView> {
        require(ctx, SCOPE_READ)
        return ctxTx.run(ctx, readOnly = true) { j -> DocumentRows.visible(j, ctx, documentId); CostAssignmentRows.assigned(j, documentId).filter { visible(j, ctx, it) } }
    }

    override fun costObjects(ctx: RequestContext, kind: CostObjectKind?, limit: Int, after: UUID?): List<CostObjectView> {
        require(ctx, SCOPE_READ)
        if (limit !in 1..200) throw ProblemException.invalid(Violation("/limit", ViolationCode.INVALID_VALUE))
        val finance = isFinance(ctx)
        // Objektberechtigung als Listenfilter (nur einschränken, nie erweitern): Kostenstellen mit Dienstbezug nur im Dienstscope; Nichtfinanzrollen sehen nur Fahrzeuge
        val services = ServiceScopeAuthorizer.effectiveServices(ctx).orEmpty().joinToString(",", "{", "}")
        return ctxTx.run(ctx, readOnly = true) { j ->
            j.query(LIST_SQL, CostAssignmentRows.MAPPER, kind?.name, kind?.name, after, after, services, finance, limit)
        }
    }

    private fun visible(j: JdbcOperations, ctx: RequestContext, o: CostObjectView): Boolean =
        o.serviceId == null || ServiceScopeAuthorizer.canRead(j, ctx, ScopedObject("cost_object", o.id, ctx.tenant, o.serviceId))

    override fun deactivate(ctx: RequestContext, costObjectId: UUID): CostObjectView {
        require(ctx, SCOPE_WRITE)
        if (!isFinance(ctx)) throw ProblemException(ErrorCode.FORBIDDEN)
        return ctxTx.run(ctx) { j -> doDeactivate(j, ctx, costObjectId) }
    }

    @AuditRequired
    private fun doDeactivate(j: JdbcOperations, ctx: RequestContext, id: UUID): CostObjectView {
        val o = j.query("SELECT $COLUMNS FROM taktovia.cost_object o WHERE o.id = ? AND o.kind IN ('VEHICLE', 'COST_CENTER') FOR UPDATE OF o", CostAssignmentRows.MAPPER, id).firstOrNull() ?: throw ProblemException.notFound()
        if (o.serviceId != null) ServiceScopeAuthorizer.requireWrite(j, ctx, ScopedObject("cost_object", o.id, ctx.tenant, o.serviceId))
        if (!o.active) return o
        j.update("UPDATE taktovia.cost_object SET active = false WHERE id = ?", id)
        audit.record(j, AuditEntry(DocumentRows.actor(ctx), AuditActions.FINANCE_COST_OBJECT_DEACTIVATED, ObjectRef("cost_object", id), ctx.correlationId, mapOf("kind" to o.kind.name)))
        return o.copy(active = false)
    }

    private fun view(j: JdbcOperations, ctx: RequestContext, id: UUID): ExpenseView = ExpenseViews.build(j, DocumentRows.visible(j, ctx, id))

    private companion object {
        const val MAX_OBJECTS = 20
        const val COLUMNS = CostAssignmentRows.COLUMNS
        const val LIST_SQL = "SELECT $COLUMNS FROM taktovia.cost_object o WHERE o.kind IN ('VEHICLE', 'COST_CENTER') AND (CAST(? AS text) IS NULL OR o.kind = ?) AND (CAST(? AS uuid) IS NULL OR o.id > ?) " +
            "AND (o.service_id IS NULL OR o.service_id = ANY(CAST(? AS uuid[]))) AND (? OR o.kind = 'VEHICLE') ORDER BY o.id LIMIT ?"
    }
}
