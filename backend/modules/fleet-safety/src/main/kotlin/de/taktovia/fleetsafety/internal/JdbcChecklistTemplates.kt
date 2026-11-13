package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.ChecklistImage
import de.taktovia.fleetsafety.api.ChecklistInputKind
import de.taktovia.fleetsafety.api.ChecklistStepInput
import de.taktovia.fleetsafety.api.ChecklistTemplateType
import de.taktovia.fleetsafety.api.MandatoryChecklistPoints
import de.taktovia.fleetsafety.api.ChecklistStepView
import de.taktovia.fleetsafety.api.ChecklistTemplateInput
import de.taktovia.fleetsafety.api.ChecklistTemplateState
import de.taktovia.fleetsafety.api.ChecklistTemplateView
import de.taktovia.fleetsafety.api.ChecklistTemplates
import de.taktovia.fleetsafety.api.notFound
import de.taktovia.platform.approval.FourEyes
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Checklistenvorlagen (WP-M3-10, LH-6.21-CHK-003). Mandantentrennung: RLS und zusammengesetzte Schlüssel; die Unveränderlichkeit der freigegebenen Revision hält zusätzlich die Datenbank
 * (Trigger `checklist_template_guard`, `checklist_step_guard`), die Vier-Augen-Regel ein CHECK (`approved_by <> created_by`). Jede Änderung sperrt die Revisionszeile (`FOR UPDATE`),
 * damit Änderung und Freigabe sowie zwei Freigaben nacheinander entschieden werden. Beispielbilder liegen im mandantengetrennten Objektspeicher (nur über [ObjectKey]).
 */
internal class JdbcChecklistTemplates(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val store: TenantObjectStore, private val clock: Clock,
) : ChecklistTemplates {

    override fun create(ctx: RequestContext, input: ChecklistTemplateInput): ChecklistTemplateView =
        ctxTx.run(ctx) { j -> doCreate(j, FourEyes.refOf(ctx), ctx.correlationId, input) }

    override fun createStandard(ctx: RequestContext, type: ChecklistTemplateType, vehicleClass: String, serviceMode: String): ChecklistTemplateView {
        val points = MandatoryChecklistPoints.required(type)
        if (points.isEmpty()) throw ProblemException.invalid(Violation("/template_type", ViolationCode.NOT_IN_ENUM))
        val steps = points.map { ChecklistStepInput(it.key, it.title, it.help, ChecklistInputKind.OK_DEFECT_NA, mandatory = true, safetyCritical = false, naRequiresReason = false, photoRequiredOnDefect = false, numberMin = null, numberMax = null, numberUnit = null) }
        val name = if (type == ChecklistTemplateType.VEHICLE_TECHNICAL) "Technische Fahrzeugprüfung" else "Prüfung vor Fahrgastaufnahme"
        val input = ChecklistTemplateInput.parse(linkedMapOf("name" to name, "vehicle_class" to vehicleClass, "service_mode" to serviceMode, "template_type" to type.name,
            "steps" to steps.map { linkedMapOf("step_code" to it.code, "title" to it.title, "help_text" to it.helpText, "input_kind" to it.kind.code, "mandatory" to true, "safety_critical" to false) }))
        return ctxTx.run(ctx) { j -> doCreate(j, ActorRef("system:standard-seed"), ctx.correlationId, input) }
    }

    override fun get(ctx: RequestContext, revisionId: UUID): ChecklistTemplateView = ctxTx.run(ctx, readOnly = true) { j -> load(j, revisionId, lock = false) ?: notFound() }

    override fun list(ctx: RequestContext, state: ChecklistTemplateState?, vehicleClass: String?, serviceMode: String?, limit: Int, after: Pair<Instant, UUID>?): List<ChecklistTemplateView> =
        ctxTx.run(ctx, readOnly = true) { j ->
            val afterTs = after?.first?.let(Timestamp::from)
            // feste Anweisung mit optionalen Filtern (NULL = kein Filter); alle Werte gebunden
            val ids = j.query(
                "SELECT r.id FROM taktovia.checklist_template_revision r WHERE (CAST(? AS text) IS NULL OR r.state = CAST(? AS text)) " +
                    "AND (CAST(? AS text) IS NULL OR r.vehicle_class = CAST(? AS text)) AND (CAST(? AS text) IS NULL OR r.service_mode = CAST(? AS text)) " +
                    "AND (CAST(? AS timestamptz) IS NULL OR (r.created_at, r.id) > (CAST(? AS timestamptz), CAST(? AS uuid))) ORDER BY r.created_at, r.id LIMIT ?",
                { rs, _ -> rs.getObject(1, UUID::class.java) }, state?.name, state?.name, vehicleClass, vehicleClass, serviceMode, serviceMode, afterTs, afterTs, after?.second, limit + 1,
            )
            ids.map { load(j, it, lock = false)!! }
        }

    override fun updateDraft(ctx: RequestContext, revisionId: UUID, expectedRevision: Long, input: ChecklistTemplateInput): ChecklistTemplateView =
        ctxTx.run(ctx) { j -> doUpdate(j, FourEyes.refOf(ctx), ctx.correlationId, revisionId, expectedRevision, input) }

    override fun newRevision(ctx: RequestContext, sourceRevisionId: UUID, expectedRevision: Long): ChecklistTemplateView {
        val source = get(ctx, sourceRevisionId)
        if (source.revision != expectedRevision) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Vorlage wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        if (source.state == ChecklistTemplateState.DRAFT) throw ProblemException(ErrorCode.CONFLICT, "Ein Entwurf ist schon die offene Revision; er wird geändert, nicht erneut revisioniert.")
        val newId = UUID.randomUUID()
        val copied = copyImages(ctx, source, newId)   // Beispielbilder gehören zur Revision: Kopie unter eigenem Namen, die freigegebene Revision bleibt unberührt
        try {
            return ctxTx.run(ctx) { j -> doNewRevision(j, FourEyes.refOf(ctx), ctx.correlationId, sourceRevisionId, expectedRevision, newId, copied) }
        } catch (e: DataIntegrityViolationException) {
            copied.values.forEach { store.delete(ObjectKey(ctx.tenant, it)) }
            if (sqlState(e) == "23505") throw ProblemException(ErrorCode.CONFLICT, "Für diese Vorlage besteht bereits ein Entwurf.", cause = e)
            throw e
        } catch (e: ProblemException) {
            copied.values.forEach { store.delete(ObjectKey(ctx.tenant, it)) }
            throw e
        }
    }

    override fun publish(ctx: RequestContext, revisionId: UUID, expectedRevision: Long): ChecklistTemplateView =
        try {
            ctxTx.run(ctx) { j -> doPublish(j, ctx, ctx.correlationId, revisionId, expectedRevision) }
        } catch (e: DataIntegrityViolationException) {
            if (sqlState(e) == "23505" || sqlState(e) == "23514") throw ProblemException(ErrorCode.CONFLICT, "Die Revision konnte nicht freigegeben werden (gleichzeitige Freigabe).", cause = e)
            throw e
        }

    override fun putExampleImage(ctx: RequestContext, revisionId: UUID, stepCode: String, expectedRevision: Long, bytes: ByteArray, contentType: String): ChecklistTemplateView {
        val ext = IMAGE_TYPES[contentType] ?: throw ProblemException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Nur PNG, JPEG oder WebP.")
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) throw ProblemException.invalid(Violation("/body", if (bytes.isEmpty()) ViolationCode.TOO_SHORT else ViolationCode.TOO_LONG))
        if (!magicMatches(contentType, bytes)) throw ProblemException.invalid(Violation("/body", ViolationCode.INVALID_FORMAT))
        val before = get(ctx, revisionId)
        requireDraft(before)
        if (before.revision != expectedRevision) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Vorlage wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        if (before.steps.none { it.code == stepCode }) notFound()
        val name = "checklist-examples/$revisionId/$stepCode-${UUID.randomUUID().toString().take(8)}.$ext"
        store.put(ObjectKey(ctx.tenant, name), bytes, contentType)
        try {
            return ctxTx.run(ctx) { j -> doSetImage(j, FourEyes.refOf(ctx), ctx.correlationId, revisionId, expectedRevision, stepCode, name) }
        } catch (e: RuntimeException) {
            store.delete(ObjectKey(ctx.tenant, name)); throw e
        }
    }

    override fun exampleImage(ctx: RequestContext, revisionId: UUID, stepCode: String): ChecklistImage {
        val name = get(ctx, revisionId).steps.firstOrNull { it.code == stepCode }?.exampleImageName ?: notFound()
        val bytes = store.get(ObjectKey(ctx.tenant, name)) ?: notFound()   // Schlüssel enthält immer den Mandanten des Kontexts
        return ChecklistImage(bytes, IMAGE_TYPES.entries.first { name.endsWith("." + it.value) }.key)
    }

    override fun steps(jdbc: JdbcOperations, revisionId: UUID): List<ChecklistStepView> {
        if (jdbc.queryForObject("SELECT count(*) FROM taktovia.checklist_template_revision WHERE id = ?", Int::class.java, revisionId) != 1) notFound()
        return loadSteps(jdbc, revisionId)
    }

    // ---------------- Schreibpfade ----------------

    @AuditRequired
    private fun doCreate(j: JdbcOperations, actor: ActorRef, correlationId: UUID, input: ChecklistTemplateInput): ChecklistTemplateView {
        val id = UUID.randomUUID()
        j.update(
            "INSERT INTO taktovia.checklist_template_revision (tenant_id, id, template_id, version, vehicle_class, service_mode, name, template_type, created_by) VALUES (taktovia.current_tenant(), ?, ?, 1, ?, ?, ?, ?, ?)",
            id, UUID.randomUUID(), input.vehicleClass, input.serviceMode, input.name, input.templateType.name, actor.value,
        )
        insertSteps(j, id, input.steps, emptyMap(), actor)
        val view = load(j, id, lock = false)!!
        audit.record(j, AuditEntry(actor, AuditActions.CHECKLIST_TEMPLATE_CREATED, ObjectRef("checklist_template", id), correlationId, auditDetails(view)))
        return view
    }

    @AuditRequired
    private fun doUpdate(j: JdbcOperations, actor: ActorRef, correlationId: UUID, id: UUID, expected: Long, input: ChecklistTemplateInput): ChecklistTemplateView {
        val before = load(j, id, lock = true) ?: notFound()
        requireDraft(before)
        if (before.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Vorlage wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        val images = before.steps.filter { it.exampleImageName != null }.associate { it.code to it.exampleImageName!! }
        j.update("DELETE FROM taktovia.checklist_step WHERE template_revision_id = ?", id)
        j.update("UPDATE taktovia.checklist_template_revision SET name = ?, vehicle_class = ?, service_mode = ?, template_type = ?, revision = revision + 1 WHERE id = ? AND revision = ?",
            input.name, input.vehicleClass, input.serviceMode, input.templateType.name, id, expected)
        insertSteps(j, id, input.steps, images, actor)
        val view = load(j, id, lock = false)!!
        audit.record(j, AuditEntry(actor, AuditActions.CHECKLIST_TEMPLATE_CHANGED, ObjectRef("checklist_template", id), correlationId, auditDetails(view) + mapOf("previous_revision" to expected)))
        return view
    }

    @AuditRequired
    private fun doNewRevision(j: JdbcOperations, actor: ActorRef, correlationId: UUID, sourceId: UUID, expected: Long, newId: UUID, images: Map<String, String>): ChecklistTemplateView {
        val src = load(j, sourceId, lock = true) ?: notFound()
        if (src.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Vorlage wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        if (src.state == ChecklistTemplateState.DRAFT) throw ProblemException(ErrorCode.CONFLICT, "Ein Entwurf ist schon die offene Revision.")
        val next = j.queryForObject("SELECT max(version) + 1 FROM taktovia.checklist_template_revision WHERE template_id = ?", Int::class.java, src.templateId)!!
        j.update(
            "INSERT INTO taktovia.checklist_template_revision (tenant_id, id, template_id, version, vehicle_class, service_mode, name, template_type, created_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?)",
            newId, src.templateId, next, src.vehicleClass, src.serviceMode, src.name, src.templateType.name, actor.value,
        )
        insertSteps(j, newId, src.steps.map { it.toInput() }, images, actor)
        val view = load(j, newId, lock = false)!!
        audit.record(j, AuditEntry(actor, AuditActions.CHECKLIST_TEMPLATE_REVISION_CREATED, ObjectRef("checklist_template", newId), correlationId, auditDetails(view) + mapOf("source_revision_id" to sourceId.toString())))
        return view
    }

    @AuditRequired
    private fun doPublish(j: JdbcOperations, ctx: RequestContext, correlationId: UUID, id: UUID, expected: Long): ChecklistTemplateView {
        val before = load(j, id, lock = true) ?: notFound()
        requireDraft(before)   // zweite gleichzeitige Freigabe: die erste hat den Entwurf schon verbraucht (409)
        val reviewer = FourEyes.requireDistinctReviewer(ctx, before.createdBy)
        if (before.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Vorlage wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        if (before.steps.isEmpty()) throw ProblemException.invalid(Violation("/steps", ViolationCode.TOO_FEW_ITEMS))
        // Pflichtpunkte vollständig (CHK-005, CHK-006): fehlt einer, ist die Revision nicht freigebbar; 422 mit Liste der fehlenden Schlüssel
        val missing = MandatoryChecklistPoints.missing(before.templateType, before.steps)
        if (missing.isNotEmpty()) throw ProblemException(ErrorCode.VALIDATION_FAILED, "MANDATORY_POINTS_MISSING: " + missing.joinToString(", "), missing.map { Violation("/steps/$it", ViolationCode.REQUIRED) })
        val retired = j.update("UPDATE taktovia.checklist_template_revision SET state = 'RETIRED', revision = revision + 1 WHERE template_id = ? AND state = 'PUBLISHED'", before.templateId)
        j.update("UPDATE taktovia.checklist_template_revision SET state = 'PUBLISHED', published_at = clock_timestamp(), approved_by = ?, revision = revision + 1 WHERE id = ? AND revision = ?", reviewer.value, id, expected)
        val view = load(j, id, lock = false)!!
        audit.record(j, AuditEntry(reviewer, AuditActions.CHECKLIST_TEMPLATE_PUBLISHED, ObjectRef("checklist_template", id), correlationId,
            auditDetails(view) + mapOf("created_by" to before.createdBy, "retired_previous" to retired)))
        outbox.append(j, OutboxEvent(id, view.revision, "checklist_template.published", linkedMapOf(
            "template_revision_id" to id.toString(), "template_id" to view.templateId.toString(), "version" to view.version, "vehicle_class" to view.vehicleClass,
            "service_mode" to view.serviceMode, "step_count" to view.steps.size, "revision" to view.revision), clock.instant(), correlationId = correlationId))
        return view
    }

    @AuditRequired
    private fun doSetImage(j: JdbcOperations, actor: ActorRef, correlationId: UUID, id: UUID, expected: Long, stepCode: String, name: String): ChecklistTemplateView {
        val before = load(j, id, lock = true) ?: notFound()
        requireDraft(before)
        if (before.revision != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Vorlage wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        if (j.update("UPDATE taktovia.checklist_step SET example_image_name = ?, revision = revision + 1 WHERE template_revision_id = ? AND step_code = ?", name, id, stepCode) != 1) notFound()
        j.update("UPDATE taktovia.checklist_template_revision SET revision = revision + 1 WHERE id = ?", id)
        val view = load(j, id, lock = false)!!
        audit.record(j, AuditEntry(actor, AuditActions.CHECKLIST_TEMPLATE_IMAGE_CHANGED, ObjectRef("checklist_template", id), correlationId, mapOf("step_code" to stepCode, "revision" to view.revision)))
        return view
    }

    // ---------------- Hilfen ----------------

    private fun requireDraft(v: ChecklistTemplateView) {
        if (v.state != ChecklistTemplateState.DRAFT) throw ProblemException(ErrorCode.IMMUTABLE_RECORD, "Die freigegebene Revision ist unveränderlich; eine Änderung erzeugt eine neue Revision.")
    }

    private fun insertSteps(j: JdbcOperations, revisionId: UUID, steps: List<ChecklistStepInput>, images: Map<String, String>, actor: ActorRef) {
        steps.forEachIndexed { i, s ->
            j.update(
                "INSERT INTO taktovia.checklist_step (tenant_id, id, template_revision_id, step_code, position, title, help_text, example_image_name, mandatory, safety_critical, answer_kind, " +
                    "na_requires_reason, photo_required_on_defect, number_min, number_max, number_unit, equipment_condition, restricts_dimension, block_kind, created_by) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), revisionId, s.code, i + 1, s.title, s.helpText, images[s.code], s.mandatory, s.safetyCritical, s.kind.code, s.naRequiresReason, s.photoRequiredOnDefect,
                s.numberMin, s.numberMax, s.numberUnit, s.equipmentCondition, s.restrictsDimension, s.blockKind, actor.value,
            )
        }
    }

    private fun copyImages(ctx: RequestContext, source: ChecklistTemplateView, newId: UUID): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (s in source.steps) {
            val old = s.exampleImageName ?: continue
            val bytes = store.get(ObjectKey(ctx.tenant, old)) ?: continue
            val name = "checklist-examples/$newId/${s.code}-${UUID.randomUUID().toString().take(8)}.${old.substringAfterLast('.')}"
            store.put(ObjectKey(ctx.tenant, name), bytes, IMAGE_TYPES.entries.first { old.endsWith("." + it.value) }.key)
            out[s.code] = name
        }
        return out
    }

    private fun ChecklistStepView.toInput() = ChecklistStepInput(code, title, helpText, kind, mandatory, safetyCritical, naRequiresReason, photoRequiredOnDefect, numberMin, numberMax, numberUnit, equipmentCondition, restrictsDimension, blockKind)

    private fun load(j: JdbcOperations, id: UUID, lock: Boolean): ChecklistTemplateView? {
        val head = (if (lock) j.query(REVISION_LOCK, HEAD, id) else j.query(REVISION_ROW, HEAD, id)).firstOrNull() ?: return null
        return head.copy(steps = loadSteps(j, id))
    }

    private fun loadSteps(j: JdbcOperations, revisionId: UUID): List<ChecklistStepView> = j.query(
        "SELECT step_code, position, title, help_text, example_image_name, mandatory, safety_critical, answer_kind, na_requires_reason, photo_required_on_defect, number_min, number_max, number_unit, " +
            "equipment_condition, restricts_dimension, block_kind " +
            "FROM taktovia.checklist_step WHERE template_revision_id = ? ORDER BY position", STEP, revisionId,
    )

    private fun auditDetails(v: ChecklistTemplateView): Map<String, Any?> = linkedMapOf(
        "template_id" to v.templateId.toString(), "version" to v.version, "state" to v.state.name, "vehicle_class" to v.vehicleClass, "service_mode" to v.serviceMode,
        "step_count" to v.steps.size, "revision" to v.revision,
    )

    private fun sqlState(e: Throwable): String? = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    private fun magicMatches(type: String, b: ByteArray): Boolean = when (type) {
        "image/png" -> b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
        "image/jpeg" -> b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
        "image/webp" -> b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP"
        else -> false
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 512 * 1024
        val IMAGE_TYPES = linkedMapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp")
        const val COLS = "r.id, r.template_id, r.version, r.name, r.vehicle_class, r.service_mode, r.state, r.created_by, r.approved_by, r.published_at, r.revision, r.created_at, r.template_type"
        const val REVISION_ROW = "SELECT $COLS FROM taktovia.checklist_template_revision r WHERE r.id = ?"
        const val REVISION_LOCK = "$REVISION_ROW FOR UPDATE"
        val HEAD = RowMapper { rs, _ ->
            ChecklistTemplateView(rs.getObject("id", UUID::class.java), rs.getObject("template_id", UUID::class.java), rs.getInt("version"), rs.getString("name"), rs.getString("vehicle_class"),
                rs.getString("service_mode"), ChecklistTemplateState.valueOf(rs.getString("state")), emptyList(), rs.getString("created_by"), rs.getString("approved_by"),
                rs.getTimestamp("published_at")?.toInstant(), rs.getLong("revision"), rs.getTimestamp("created_at").toInstant(), ChecklistTemplateType.valueOf(rs.getString("template_type")))
        }
        val STEP = RowMapper { rs, _ ->
            ChecklistStepView(rs.getString("step_code"), rs.getInt("position"), rs.getString("title"), rs.getString("help_text"), ChecklistInputKind.parse(rs.getString("answer_kind"))!!,
                rs.getBoolean("mandatory"), rs.getBoolean("safety_critical"), rs.getBoolean("na_requires_reason"), rs.getBoolean("photo_required_on_defect"),
                rs.getBigDecimal("number_min"), rs.getBigDecimal("number_max"), rs.getString("number_unit"), rs.getString("example_image_name"),
                rs.getString("equipment_condition"), rs.getString("restricts_dimension"), rs.getString("block_kind"))
        }
    }
}
