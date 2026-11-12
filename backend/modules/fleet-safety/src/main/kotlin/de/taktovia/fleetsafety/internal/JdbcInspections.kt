package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.ChecklistTemplates
import de.taktovia.fleetsafety.api.InspectionAnswerView
import de.taktovia.fleetsafety.api.InspectionCatalog
import de.taktovia.fleetsafety.api.InspectionReleaseView
import de.taktovia.fleetsafety.api.InspectionTemplateView
import de.taktovia.fleetsafety.api.InspectionView
import de.taktovia.fleetsafety.api.Inspections
import de.taktovia.fleetsafety.api.StartInspection
import de.taktovia.fleetsafety.api.StartedInspection
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
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Prüflauf-Start (WP-M3-13, siehe [Inspections]). Eine Transaktion je Aufruf. Vorlagenwahl und Anlage laufen unter einer Lesesperre (`FOR SHARE`) auf den gewählten Revisionszeilen:
 * eine gleichzeitige Freigabe (Ablösung per `UPDATE`) wartet bis zum Ende des Starts oder wird vom Start gesehen; der Lauf hält immer genau eine vollständige, unveränderliche
 * Revision. Doppelstarts entscheidet der Teil-Unique-Index `inspection_one_open` über `ON CONFLICT DO NOTHING`, ohne die Transaktion zu verlieren.
 */
internal class JdbcInspections(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val checklists: ChecklistTemplates, private val clock: Clock,
) : Inspections {

    private sealed interface Attempt {
        class Done(val result: StartedInspection) : Attempt
        class Unavailable(val reason: String) : Attempt
    }

    override fun start(ctx: RequestContext, cmd: StartInspection): StartedInspection {
        if (InspectionRoles.STARTERS.none { it in ctx.roles }) throw ProblemException(ErrorCode.FORBIDDEN, "Einen Prüflauf starten Fahrer und Werkstatt.")
        return when (val a = ctxTx.run(ctx) { j -> doStart(j, ctx, cmd) }) {
            is Attempt.Done -> a.result
            is Attempt.Unavailable -> throw ProblemException(ErrorCode.CONFLICT,
                (if (a.reason == REASON_AMBIGUOUS) InspectionCatalog.TEMPLATE_AMBIGUOUS else InspectionCatalog.TEMPLATE_UNAVAILABLE) +
                    ": Für dieses Fahrzeug und diesen Dienst gibt es keine eindeutig freigegebene Prüfvorlage; die Leitstelle wurde informiert, die Einsatzfreigabe bleibt aus.")
        }
    }

    @AuditRequired
    private fun doStart(j: JdbcOperations, ctx: RequestContext, cmd: StartInspection): Attempt {
        val now = clock.instant()
        if (cmd.clientStartedAt.isAfter(now.plusSeconds(InspectionCatalog.FUTURE_TOLERANCE_SECONDS))) throw ProblemException.invalid(Violation("/client_started_at", ViolationCode.INVALID_VALUE))
        // Nur die eigene Schicht (fremd, fremder Mandant oder nicht zugewiesen: 404 ohne Hinweis auf die Existenz); Lesesperre gegen gleichzeitige Änderungen der Schicht
        val shift = j.query(
            "SELECT s.id, s.vehicle_id, s.driver_ref, s.state, s.ends_at FROM taktovia.staff_shift s WHERE s.id = ? AND $OWN_SHIFT FOR SHARE OF s",
            { rs, _ -> Shift(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getString(4), rs.getTimestamp(5).toInstant()) },
            cmd.shiftId, *ShiftScope.args(ctx.actor.issuer, ctx.actor.subject),
        ).firstOrNull() ?: throw ProblemException.notFound()
        if (cmd.vehicleId != shift.vehicleId) throw ProblemException.invalid(Violation("/vehicle_id", ViolationCode.INVALID_VALUE))
        // „nach Anmeldung“: nur eine angetretene, noch laufende Schicht (WP-M3-25: Zustand STARTED)
        if (shift.state != "STARTED" || !now.isBefore(shift.endsAt))
            throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Ein Prüflauf beginnt erst nach der Dienstanmeldung und nur innerhalb der laufenden Schicht.")
        openRun(j, shift.id, shift.vehicleId, cmd.kind)?.let { return Attempt.Done(StartedInspection(load(j, ctx, it)!!, created = false)) }

        // Vorlagenwahl allein durch den Server: Fahrzeugklasse und Bedienart des Fahrzeugs -> genau eine freigegebene Revision (kein Clientwert fließt ein)
        val pair = j.query("SELECT vehicle_type, operation_kind FROM taktovia.vehicle WHERE id = ?", { rs, _ -> rs.getString(1) to rs.getString(2) }, shift.vehicleId).firstOrNull() ?: throw ProblemException.notFound()
        val candidates: List<UUID> = pair.first?.let { cls -> selectPublished(j, cls, pair.second) } ?: emptyList()
        if (candidates.size != 1) {
            val reason = if (candidates.isEmpty()) REASON_NONE else REASON_AMBIGUOUS
            notify(j, ctx, shift, pair, reason, candidates.size, now)
            return Attempt.Unavailable(reason)
        }

        val id = UUID.randomUUID()
        val inserted = j.update(
            "INSERT INTO taktovia.inspection_run (tenant_id, id, shift_id, vehicle_id, driver_ref, template_revision_id, kind, started_at, created_by) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, shift_id, vehicle_id, kind) WHERE status = 'IN_PROGRESS' DO NOTHING",
            id, shift.id, shift.vehicleId, shift.driverRef, candidates.single(), cmd.kind, Timestamp.from(cmd.clientStartedAt), actor(ctx).value,
        )
        if (inserted == 0) {   // gleichzeitiger Doppelstart (anderes Gerät, anderer Schlüssel): der andere hat gewonnen, dessen Lauf wird geliefert
            val existing = openRun(j, shift.id, shift.vehicleId, cmd.kind) ?: throw ProblemException(ErrorCode.CONFLICT, "Der Prüflauf konnte nicht angelegt werden; Start wiederholen.")
            return Attempt.Done(StartedInspection(load(j, ctx, existing)!!, created = false))
        }
        audit.record(j, AuditEntry(actor(ctx), AuditActions.INSPECTION_STARTED, ObjectRef("inspection", id), ctx.correlationId,
            mapOf("shift_id" to shift.id.toString(), "vehicle_id" to shift.vehicleId.toString(), "kind" to cmd.kind, "template_revision_id" to candidates.single().toString())))
        return Attempt.Done(StartedInspection(load(j, ctx, id)!!, created = true))
    }

    /** Hinweis an die Leitstelle: Audit und Ereignis in derselben Transaktion; ohne Freitext, ohne Personen. */
    private fun notify(j: JdbcOperations, ctx: RequestContext, shift: Shift, pair: Pair<String?, String?>, reason: String, count: Int, now: Instant) {
        audit.record(j, AuditEntry(actor(ctx), AuditActions.INSPECTION_TEMPLATE_UNAVAILABLE, ObjectRef("staff_shift", shift.id), ctx.correlationId,
            mapOf("vehicle_id" to shift.vehicleId.toString(), "reason" to reason, "candidate_count" to count)))
        outbox.append(j, OutboxEvent(UUID.randomUUID(), 1, "inspection.template_unavailable", linkedMapOf(
            "shift_id" to shift.id.toString(), "vehicle_id" to shift.vehicleId.toString(), "vehicle_class" to pair.first, "service_mode" to pair.second,
            "reason" to reason, "candidate_count" to count), now, correlationId = ctx.correlationId))
    }

    /**
     * Freigegebene Revisionen für das Paar unter Lesesperre. Wartet eine gleichzeitige Freigabe (Ablösung der alten, Veröffentlichung der neuen Revision in einer Transaktion), liefert die
     * erste Abfrage nach deren Ende leer, weil ihr Stichtag vor der neuen Revision lag; deshalb wird bei leerem Ergebnis mit neuem Stichtag wiederholt (höchstens [SELECT_ATTEMPTS] Versuche).
     */
    private fun selectPublished(j: JdbcOperations, vehicleClass: String, serviceMode: String?): List<UUID> {
        repeat(SELECT_ATTEMPTS) {
            val found = j.query("SELECT r.id FROM taktovia.checklist_template_revision r WHERE r.state = 'PUBLISHED' AND r.vehicle_class = ? AND r.service_mode = ? ORDER BY r.id FOR SHARE OF r",
                { rs, _ -> rs.getObject(1, UUID::class.java) }, vehicleClass, serviceMode)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun openRun(j: JdbcOperations, shiftId: UUID, vehicleId: UUID, kind: String): UUID? =
        j.query("SELECT id FROM taktovia.inspection_run WHERE shift_id = ? AND vehicle_id = ? AND kind = ? AND status = 'IN_PROGRESS'", { rs, _ -> rs.getObject(1, UUID::class.java) }, shiftId, vehicleId, kind).firstOrNull()

    override fun get(ctx: RequestContext, id: UUID): InspectionView = ctxTx.run(ctx, readOnly = true) { j -> load(j, ctx, id) ?: throw ProblemException.notFound() }

    private fun load(j: JdbcOperations, ctx: RequestContext, id: UUID): InspectionView? {
        val driverOnly = InspectionRoles.driverOnly(ctx)
        val mapper = RowMapper { rs, _ ->
            Row(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getObject(4, UUID::class.java), rs.getObject(5, UUID::class.java),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getTimestamp(9).toInstant(), rs.getTimestamp(10)?.toInstant(), rs.getTimestamp(11)?.toInstant(), rs.getLong(12))
        }
        // Fahrer sehen nur Läufe eigener Schichten (sonst 404); Disposition und Werkstatt alle Läufe des Mandanten
        val row = (if (driverOnly) j.query("SELECT $RUN_COLUMNS FROM taktovia.inspection_run i WHERE i.id = ? AND EXISTS (SELECT 1 FROM taktovia.staff_shift s WHERE s.id = i.shift_id AND $OWN_SHIFT)",
            mapper, id, *ShiftScope.args(ctx.actor.issuer, ctx.actor.subject))
        else j.query("SELECT $RUN_COLUMNS FROM taktovia.inspection_run i WHERE i.id = ?", mapper, id)).firstOrNull() ?: return null
        val tpl = j.query("SELECT template_id, version, vehicle_class, service_mode FROM taktovia.checklist_template_revision WHERE id = ?",
            { rs, _ -> InspectionTemplateView(row.revisionId, rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getString(3), rs.getString(4), emptyList()) }, row.revisionId).single()
            .let { it.copy(steps = checklists.steps(j, row.revisionId)) }
        val answers = j.query("SELECT step_code, answer, number_value, note, answered_at FROM taktovia.inspection_answer WHERE inspection_id = ? ORDER BY answered_at, step_code",
            { rs, _ -> InspectionAnswerView(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4), rs.getTimestamp(5).toInstant()) }, id)
        val release = j.query("SELECT id, valid_until, safety_revision FROM taktovia.resource_release WHERE inspection_ref = ? AND revoked_at IS NULL ORDER BY valid_until DESC LIMIT 1",
            { rs, _ -> InspectionReleaseView(rs.getObject(1, UUID::class.java), rs.getTimestamp(2).toInstant(), rs.getLong(3)) }, id).firstOrNull()
        return InspectionView(row.id, row.shiftId, row.vehicleId, row.driverRef, row.revisionId, row.kind, row.status, row.outcome, row.startedAt, row.completedAt, row.receivedAt, row.revision, tpl, answers, release)
    }

    private class Shift(val id: UUID, val vehicleId: UUID, val driverRef: UUID, val state: String, val endsAt: Instant)
    private class Row(val id: UUID, val shiftId: UUID, val vehicleId: UUID, val driverRef: UUID, val revisionId: UUID, val kind: String, val status: String, val outcome: String?,
                      val startedAt: Instant, val completedAt: Instant?, val receivedAt: Instant?, val revision: Long)

    private fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    private companion object {
        const val OWN_SHIFT = ShiftScope.OWN
        const val RUN_COLUMNS = "i.id, i.shift_id, i.vehicle_id, i.driver_ref, i.template_revision_id, i.kind, i.status, i.outcome, i.started_at, i.completed_at, i.received_at, i.revision"
        const val SELECT_ATTEMPTS = 3
        const val REASON_NONE = "NO_TEMPLATE"
        const val REASON_AMBIGUOUS = "AMBIGUOUS_TEMPLATE"
    }
}

/** Rollen des Prüflaufs (Rechtematrix `createInspection`: Fahrer, Werkstatt; `getInspection` zusätzlich Disposition). */
internal object InspectionRoles {
    val STARTERS = setOf("driver", "workshop")
    /** Ein Konto, das nur als Fahrer auftritt, sieht nur Läufe eigener Schichten. */
    fun driverOnly(ctx: RequestContext) = "driver" in ctx.roles && ctx.roles.none { it == "dispatcher" || it == "workshop" || it == "tenant_admin" }
}

/** „Eigene Schicht“ des Fahrers: `driver_ref` ist die Fahrerkennung (Fahrerstamm, Mitgliedschaft des Kontos) oder, für ältere Zuweisungen, direkt die IdP-Kennung (wie in `staff-time`, nur lesend). */
internal object ShiftScope {
    const val OWN = "(s.driver_ref::text = lower(?) OR EXISTS (SELECT 1 FROM taktovia.driver dr JOIN taktovia.membership ms ON ms.tenant_id = dr.tenant_id AND ms.id = dr.membership_id " +
        "WHERE dr.tenant_id = s.tenant_id AND dr.id = s.driver_ref AND ms.issuer = ? AND ms.subject = ? AND ms.revoked_at IS NULL))"
    fun args(issuer: String, subject: String): Array<Any> = arrayOf(subject, issuer, subject)
}
