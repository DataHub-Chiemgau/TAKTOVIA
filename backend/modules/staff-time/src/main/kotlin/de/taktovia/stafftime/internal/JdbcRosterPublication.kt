package de.taktovia.stafftime.internal

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
import de.taktovia.stafftime.api.OwnShift
import de.taktovia.stafftime.api.PublishRoster
import de.taktovia.stafftime.api.PublishedRoster
import de.taktovia.stafftime.api.PublishedRosters
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Veröffentlichung des Dienstplans (WP-M4-14). Reihenfolge der Sperren (kein Verklemmen mit Entwurfsänderungen und Abwesenheitsgenehmigungen): Absperrung gegen Abwesenheitsgenehmigungen, Depotrecht
 * (`FOR SHARE`), alle Revisionszeilen des Dienstplans (`FOR UPDATE`, aufsteigend), danach die Schichtzeilen. Bis zur Konfliktprüfung wird nichts geschrieben: ein Konflikt, ein veralteter Stand oder
 * ein Idempotenzkonflikt hinterlässt weder Revision noch Ereignis. Zwei parallele Publikationen desselben Entwurfs serialisieren an der Revisionszeile: die erste gewinnt, die zweite sieht den neuen
 * Stand (412).
 */
internal class JdbcRosterPublication(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock, private val conflicts: RosterConflictChecker, private val hints: RosterHints,
) : PublishedRosters {
    private val json = JsonMapper.builder().build()

    override fun publish(ctx: RequestContext, rosterId: UUID, cmd: PublishRoster): PublishedRoster {
        RosterAuthz.require(ctx, SCOPE_PUBLISH)
        val v = mutableListOf<Violation>()
        if (cmd.reason.trim().length !in 3..1000) v += Violation("/reason", if (cmd.reason.isBlank()) ViolationCode.REQUIRED else ViolationCode.TOO_LONG)
        if (cmd.idempotencyKey != null && cmd.idempotencyKey.length !in 1..255) v += Violation("/\$header/Idempotency-Key", ViolationCode.INVALID_FORMAT)
        if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray())
        return try {
            ctxTx.run(ctx) { j -> doPublish(j, ctx, rosterId, cmd) }
        } catch (e: DataIntegrityViolationException) {
            val msg = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
            if (msg.contains("overlaps", ignoreCase = true) || msg.contains("no_overlap")) throw ProblemException(ErrorCode.CONFLICT, "Eine Schicht überschneidet eine verbindliche Schicht derselben Person oder desselben Fahrzeugs; der Dienstplan ist nicht veröffentlichbar.")
            throw e
        }
    }

    private class Rev(val id: UUID, val revision: Long, val state: String, val etag: Long, val key: String?, val fingerprint: String?, val effectiveFrom: Instant?, val publishedAt: Instant?, val publishedBy: String?, val auditEventId: UUID?)

    @AuditRequired
    private fun doPublish(j: JdbcOperations, ctx: RequestContext, rosterId: UUID, cmd: PublishRoster): PublishedRoster {
        RosterDraftAccess.enable(j)
        // Absperrung gegen gleichzeitige Abwesenheitsgenehmigungen: die Prüfung wertet den committeten Stand (SCH-006)
        j.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('roster_absence|' || taktovia.current_tenant()::text, 0))", String::class.java)
        val (depotOfRoster, access) = DepotGuard.requireForRoster(j, ctx, rosterId, forWrite = true)
        val revs = j.query(
            "SELECT r.id, r.revision, r.state, r.entity_revision, r.publish_idempotency_key, r.publish_fingerprint, r.effective_from, r.published_at, r.published_by, r.publish_audit_event_id " +
                "FROM taktovia.roster_revision r WHERE r.roster_id = ? ORDER BY r.revision FOR UPDATE",
            { rs, _ -> Rev(rs.getObject(1, UUID::class.java), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7)?.toInstant(), rs.getTimestamp(8)?.toInstant(), rs.getString(9), rs.getObject(10, UUID::class.java)) },
            rosterId,
        )
        if (revs.isEmpty()) throw ProblemException.notFound()
        val fingerprint = fingerprint(cmd)
        // Wiederholung: derselbe Schlüssel liefert dieselbe Revision, anderer Inhalt unter demselben Schlüssel ist ein Konflikt
        cmd.idempotencyKey?.let { key ->
            revs.firstOrNull { it.key == key }?.let { done ->
                if (done.fingerprint != fingerprint) throw ProblemException(ErrorCode.IDEMPOTENCY_CONFLICT, "Der Idempotency-Key wurde mit anderem Inhalt verwendet.")
                return replay(rosterId, done)
            }
        }
        val draft = revs.firstOrNull { it.state == "DRAFT" }
        val published = revs.firstOrNull { it.state == "PUBLISHED" }
        val currentEtag = (draft ?: published)!!.etag
        if (cmd.expectedEtag != currentEtag) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Der Dienstplan wurde zwischenzeitlich geändert; aktuellen Stand lesen.")
        if (draft == null) throw ProblemException(ErrorCode.CONFLICT, "Der Dienstplan hat keinen Entwurf; es gibt nichts zu veröffentlichen.")
        // Konfliktprüfung unter Sperre erneut (WP-M4-07): blockierend, ohne jede Wirkung
        val visible = DepotGuard.visible(j, ctx)
        conflicts.requireNone(conflicts.check(j, rosterId, draft.revision) { d -> d == null || visible == null || d in visible })
        // Regelhinweise (WP-M4-13) sind Warnungen und sperren nicht
        val hintCount = hints.compute(j, ctx, rosterId, draft.revision).size

        val changed = changedPersons(j, rosterId, published?.revision, draft.revision)
        val shiftCount = j.queryForObject("SELECT count(*) FROM taktovia.staff_shift WHERE roster_id = ? AND roster_revision = ? AND state = 'DRAFT'", Int::class.java, rosterId, draft.revision)!!
        val actor = "user:" + ctx.actor.subject
        val auditId = audit.record(j, AuditEntry(ActorRef(actor), AuditActions.ROSTER_PUBLISHED, ObjectRef("roster", rosterId), ctx.correlationId, mapOf(
            "revision" to draft.revision, "effective_from" to cmd.effectiveFrom.toString(), "shift_count" to shiftCount, "changed_person_count" to changed.size, "hint_count" to hintCount,
            "impact_report_id" to cmd.impactReportId.toString())))

        // Vorgängerrevision ersetzen (bleibt unverändert erhalten), Schichten der neuen Revision verbindlich
        if (published != null) {
            j.update("UPDATE taktovia.staff_shift SET state = 'SUPERSEDED' WHERE roster_id = ? AND roster_revision = ? AND state = 'PUBLISHED'", rosterId, published.revision)
            j.update("UPDATE taktovia.roster_revision SET state = 'SUPERSEDED' WHERE id = ?", published.id)
        }
        j.update("UPDATE taktovia.staff_shift SET state = 'PUBLISHED' WHERE roster_id = ? AND roster_revision = ? AND state = 'DRAFT'", rosterId, draft.revision)
        j.update(
            "UPDATE taktovia.roster_revision SET state = 'PUBLISHED', published_at = clock_timestamp(), published_by = ?, effective_from = ?, publish_reason = ?, impact_report_id = ?, " +
                "publish_audit_event_id = ?, publish_idempotency_key = ?, publish_fingerprint = ? WHERE id = ?",
            actor, Timestamp.from(cmd.effectiveFrom), cmd.reason.trim(), cmd.impactReportId, auditId, cmd.idempotencyKey, fingerprint, draft.id,
        )
        ShiftChangeLog.recordPublished(j, rosterId, draft.revision, actor)
        val done = j.query(
            "SELECT r.entity_revision, r.published_at, r.depot_id, r.week_start FROM taktovia.roster_revision r WHERE r.id = ?",
            { rs, _ -> listOf(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getObject(3, UUID::class.java), rs.getDate(4).toLocalDate()) }, draft.id,
        ).single()
        val etag = done[0] as Long; val publishedAt = done[1] as Instant
        outbox.append(j, OutboxEvent(rosterId, draft.revision, "staff.roster_published", linkedMapOf(
            "roster_id" to rosterId.toString(), "depot_id" to (done[2] as UUID).toString(), "week_start" to (done[3] as LocalDate).toString(), "revision" to draft.revision,
            "effective_from" to cmd.effectiveFrom.toString(), "published_at" to publishedAt.toString(), "published_by" to actor,
            "changed_persons" to subjects(j, changed).map { (driver, subject) -> linkedMapOf("driver_ref" to driver.toString(), "subject" to subject) },
        ), clock.instant(), correlationId = ctx.correlationId))
        RosterAudit.crossDepot(audit, j, ctx, access, rosterId, depotOfRoster, "publish")
        return PublishedRoster(rosterId, draft.revision, etag, cmd.effectiveFrom, publishedAt, actor, auditId, changed.sorted(), hintCount, replayed = false)
    }

    /** Dieselbe Anfrage noch einmal: die bereits entstandene Revision, ohne neue Wirkung (kein Audit, kein Ereignis). */
    private fun replay(rosterId: UUID, done: Rev): PublishedRoster {
        return PublishedRoster(rosterId, done.revision, done.etag, done.effectiveFrom!!, done.publishedAt!!, done.publishedBy!!, done.auditEventId!!, emptyList(), 0, replayed = true)
    }

    private class Fp(val driver: UUID?, val print: String)

    /** Personen, deren Schichten sich gegenüber der vorherigen veröffentlichten Revision geändert haben (neu, entfallen oder inhaltlich verändert; alte und neue Person). */
    private fun changedPersons(j: JdbcOperations, rosterId: UUID, previous: Long?, draft: Long): Set<UUID> {
        fun load(rev: Long): Map<UUID, Fp> = j.query(
            "SELECT COALESCE(s.lineage_id, s.id), s.driver_ref, md5(concat_ws('|', s.driver_ref, s.vehicle_id, s.starts_at, s.ends_at, s.shift_function, s.required_qualification, s.staffing_need, s.break_plan::text, s.depot_id, s.operator_id)) " +
                "FROM taktovia.staff_shift s WHERE s.roster_id = ? AND s.roster_revision = ? AND s.state <> 'CANCELLED'",
            { rs, _ -> rs.getObject(1, UUID::class.java) to Fp(rs.getObject(2, UUID::class.java), rs.getString(3)) }, rosterId, rev).toMap()
        val old = if (previous == null) emptyMap() else load(previous); val new = load(draft)
        val out = mutableSetOf<UUID>()
        for (lineage in old.keys + new.keys) {
            val a = old[lineage]; val b = new[lineage]
            if (a?.print != b?.print) { a?.driver?.let(out::add); b?.driver?.let(out::add) }
        }
        return out
    }

    /** Konto-Subject je geplanter Person (Fahrerstamm über Mitgliedschaft; im Altbestand ist `driver_ref` selbst die Subject-Kennung). */
    private fun subjects(j: JdbcOperations, persons: Set<UUID>): List<Pair<UUID, String>> {
        if (persons.isEmpty()) return emptyList()
        val known = j.query("SELECT d.id, m.subject FROM taktovia.driver d JOIN taktovia.membership m ON m.tenant_id = d.tenant_id AND m.id = d.membership_id WHERE d.id = ANY (CAST(? AS uuid[]))",
            { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }, persons.joinToString(",", "{", "}")).toMap()
        return persons.sorted().map { it to (known[it] ?: it.toString()) }
    }

    private fun fingerprint(c: PublishRoster): String =
        MessageDigest.getInstance("SHA-256").digest("${c.effectiveFrom}|${c.reason.trim()}|${c.impactReportId}".toByteArray()).joinToString("") { "%02x".format(it) }

    override fun ownShifts(ctx: RequestContext, from: LocalDate, to: LocalDate): List<OwnShift> {
        if (!ctx.hasScope(SCOPE_READ_OWN)) throw ProblemException(ErrorCode.FORBIDDEN)
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) throw ProblemException.invalid(Violation("/\$query/to", ViolationCode.INVALID_VALUE))
        return ctxTx.run(ctx, readOnly = true) { j ->
            // keine Entwurfsfreigabe: auch eine Direktabfrage zeigt keine Entwurfszeilen; nur die aktuelle veröffentlichte Revision
            j.query(
                OWN_SQL,
                { rs, _ ->
                    OwnShift(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getLong(3), rs.getDate(4).toLocalDate(), rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant(),
                        ZoneId.of(rs.getString(7)), rs.getString(8), rs.getString(9), json.readTree(rs.getString(10)).get("breaks").values().sumOf { it.get("duration_min").asLong() })
                },
                *ShiftOwnership.args(ctx.actor.issuer, ctx.actor.subject), java.sql.Date.valueOf(from), java.sql.Date.valueOf(to),
            )
        }
    }

    private companion object {
        const val SCOPE_PUBLISH = "rosters:publish"
        const val SCOPE_READ_OWN = "time-events:read"
        const val MAX_DAYS = 62L
        const val OWN_SQL = "SELECT s.id, s.roster_id, s.roster_revision, s.operating_day, s.starts_at, s.ends_at, s.timezone, s.shift_function, s.state, s.break_plan::text FROM taktovia.staff_shift s " +
            "JOIN taktovia.roster_revision r ON r.tenant_id = s.tenant_id AND r.roster_id = s.roster_id AND r.revision = s.roster_revision WHERE " + ShiftOwnership.PREDICATE +
            " AND r.state = 'PUBLISHED' AND s.state IN ('PUBLISHED', 'STARTED', 'ENDED') AND s.operating_day BETWEEN ? AND ? ORDER BY s.starts_at, s.id LIMIT 500"
    }
}

/** Änderungsprotokoll je Schicht (WP-M4-15): Schreiben in der Fachtransaktion, nur anhängen. */
internal object ShiftChangeLog {
    private const val SNAPSHOT = "jsonb_build_object('shift_id', s.id, 'lineage_id', COALESCE(s.lineage_id, s.id), 'state', s.state, 'driver_ref', s.driver_ref, 'vehicle_id', s.vehicle_id, 'starts_at', s.starts_at, " +
        "'ends_at', s.ends_at, 'timezone', s.timezone, 'function', s.shift_function, 'required_qualification', s.required_qualification, 'staffing_need', s.staffing_need, 'break_plan', s.break_plan, " +
        "'depot_id', s.depot_id, 'operator_id', s.operator_id, 'operating_day', s.operating_day)"

    /** Protokolliert die Anlage oder Änderung einer einzelnen Schicht des Entwurfs. */
    fun record(j: JdbcOperations, shiftId: UUID, kind: String, actor: String) {
        j.update(
            "INSERT INTO taktovia.roster_shift_change (tenant_id, id, roster_id, roster_revision, lineage_id, shift_id, change_kind, shift_revision, actor, snapshot) " +
                "SELECT s.tenant_id, gen_random_uuid(), s.roster_id, s.roster_revision, COALESCE(s.lineage_id, s.id), s.id, ?, s.shift_revision, ?, $SNAPSHOT FROM taktovia.staff_shift s WHERE s.id = ? AND s.roster_id IS NOT NULL",
            kind, actor, shiftId,
        )
    }

    /** Protokolliert die Veröffentlichung aller Schichten der Revision. */
    fun recordPublished(j: JdbcOperations, rosterId: UUID, revision: Long, actor: String) {
        j.update(
            "INSERT INTO taktovia.roster_shift_change (tenant_id, id, roster_id, roster_revision, lineage_id, shift_id, change_kind, shift_revision, actor, snapshot) " +
                "SELECT s.tenant_id, gen_random_uuid(), s.roster_id, s.roster_revision, COALESCE(s.lineage_id, s.id), s.id, 'PUBLISHED', s.shift_revision, ?, $SNAPSHOT FROM taktovia.staff_shift s " +
                "WHERE s.roster_id = ? AND s.roster_revision = ? AND s.state = 'PUBLISHED'",
            actor, rosterId, revision,
        )
    }
}
