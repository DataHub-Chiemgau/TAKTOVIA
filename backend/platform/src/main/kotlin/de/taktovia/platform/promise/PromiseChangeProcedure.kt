package de.taktovia.platform.promise

import de.taktovia.platform.api.Etag
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.util.UUID

/** Befehl „Zusage ändern“ (Änderungsverfahren). Akteur und Rechte stammen aus dem Principal, nie aus dem Request-Body. */
class PromiseChangeCommand(
    val promiseId: UUID,
    /** Revision aus `If-Match`; `null` = Header fehlte (428). */
    val expectedRevision: Long?,
    val newContent: JsonNode,
    val procedure: String,
    val reason: String?,
    val actor: ActorRef,
    val scopes: Set<String>,
    val correlationId: UUID,
    /** Neue Konfigurations-/Tarifrevision, auf die sich die neue Revision bezieht; ohne Angabe bleibt die bisherige. */
    val configRevisionId: UUID? = null,
)

data class PromiseChangeResult(
    val promiseId: UUID, val previousRevision: Long, val newRevision: Long, val changedFields: Set<String>,
    val passengerInformationRequired: Boolean, val auditEventId: UUID, val outboxEventId: UUID?,
)

/** Informationsauftrag an den Benachrichtigungs-Port (Zustellung der Nachricht: M2). */
data class PassengerInformationOrder(
    val promiseId: UUID, val previousRevision: Long, val newRevision: Long, val relevantFields: List<String>, val procedure: ChangeProcedure, val correlationId: UUID,
)

/** Benachrichtigungs-Port der Plattform. In M1 reicht [OutboxPassengerInformationPort] den Auftrag als Ereignis weiter; den Versand übernimmt der Adapter `notification` (M2). */
fun interface PassengerInformationPort { fun order(jdbc: JdbcOperations, order: PassengerInformationOrder): UUID }

/** Schreibt den Informationsauftrag als Ereignis `promise.changed` in dieselbe Transaktion wie die Änderung. */
class OutboxPassengerInformationPort(private val outbox: Outbox, private val clock: Clock = Clock.systemUTC()) : PassengerInformationPort {
    override fun order(jdbc: JdbcOperations, order: PassengerInformationOrder): UUID = outbox.append(jdbc, OutboxEvent(
        order.promiseId, order.newRevision, PromiseChangeProcedure.EVENT_TYPE,
        mapOf("previous_revision" to order.previousRevision, "revision" to order.newRevision, "passenger_information_required" to true,
            "procedure" to order.procedure.name,
            "information_order" to mapOf("kind" to "promise_changed", "changed_fields" to order.relevantFields)),
        clock.instant(), correlationId = order.correlationId))
}

/**
 * Plattformbaustein für verbindliche Zusagen (WP-M1-27, LH-6.1-SYS-005). Der Snapshot einer Revision ist unveränderlich (Datenbank: Trigger
 * `promise_snapshot_immutable`); eine Änderung ist ausschließlich dieses Verfahren: berechtigt (`promise:change`), mit Grund, mit `If-Match`
 * auf die aktuelle Revision, schemakonform. Je Änderung entsteht genau ein Auditereignis (Akteur, Grund, alte und neue Revision) und, wenn ein
 * fahrgastrelevantes Feld betroffen ist, genau ein Informationsauftrag. Alles in einer Transaktion (`TenantTransactions.run`): eine abgewiesene Änderung
 * hinterlässt weder Revision noch Audit noch Ereignis. Das Testaggregat (`promise_test_aggregate`) wird in M2 durch `booking` ersetzt.
 */
class PromiseChangeProcedure(
    private val audit: AuditPort,
    private val information: PassengerInformationPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        const val SCOPE_CHANGE = "promise:change"
        const val EVENT_TYPE = "promise.changed"
        const val MAX_REASON = 500
    }

    /** Legt die erste Revision an („Zusage binden“) und auditiert das Binden. */
    @AuditRequired
    fun bind(jdbc: JdbcOperations, promiseId: UUID, content: JsonNode, configRevisionId: UUID, actor: ActorRef, correlationId: UUID): BoundSnapshot {
        val snapshot = BoundSnapshot.bind(promiseId, 1, PromiseSnapshotSchema.CURRENT, content, configRevisionId)
        jdbc.update("INSERT INTO taktovia.promise_test_aggregate(tenant_id, id, revision) VALUES (taktovia.current_tenant(), ?, 1)", promiseId)
        insertSnapshot(jdbc, snapshot)
        audit.record(jdbc, AuditEntry(actor, AuditActions.PROMISE_SNAPSHOT_BOUND, ObjectRef("promise", promiseId), correlationId,
            mapOf("revision" to 1, "schema_version" to snapshot.schemaVersion, "content_hash" to snapshot.contentHash), clock.instant()))
        return snapshot
    }

    @AuditRequired
    fun change(jdbc: JdbcOperations, cmd: PromiseChangeCommand): PromiseChangeResult {
        // 1. Berechtigung, 2. Pflichtangaben, 3. Revisionsbedingung (ohne Datenbankzugriff, damit eine Abweisung nichts schreibt)
        if (SCOPE_CHANGE !in cmd.scopes) throw ProblemException(ErrorCode.FORBIDDEN)
        val reason = cmd.reason?.trim()
        val problems = mutableListOf<Violation>()
        if (reason.isNullOrEmpty()) problems += Violation("/reason", ViolationCode.REQUIRED)
        else if (reason.length > MAX_REASON) problems += Violation("/reason", ViolationCode.TOO_LONG)
        val procedure = ChangeProcedure.entries.firstOrNull { it.name == cmd.procedure }
        if (procedure == null) problems += Violation("/procedure", ViolationCode.NOT_IN_ENUM)
        problems += PromiseSnapshotSchema.validate(PromiseSnapshotSchema.CURRENT, cmd.newContent)
        if (problems.isNotEmpty()) throw ProblemException.invalid(*problems.toTypedArray())
        val expected = cmd.expectedRevision ?: throw ProblemException(ErrorCode.PRECONDITION_REQUIRED, "Vor einer Änderung die Ressource lesen und ihren ETag als If-Match senden.")

        // 4. Kopf sperren: gleichzeitige Änderungen derselben Zusage laufen nacheinander; die zweite sieht die neue Revision und erhält 412.
        val head = jdbc.query("SELECT revision FROM taktovia.promise_test_aggregate WHERE tenant_id = taktovia.current_tenant() AND id = ? FOR UPDATE",
            { rs, _ -> rs.getLong(1) }, cmd.promiseId).firstOrNull() ?: throw ProblemException.notFound()
        if (head != expected) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Zusage wurde zwischenzeitlich geändert; aktuelle Revision lesen.")

        val current = load(jdbc, cmd.promiseId, head)
        val changed = changedFields(current.content, cmd.newContent)
        if (changed.isEmpty() && (cmd.configRevisionId == null || cmd.configRevisionId == current.configRevisionId))
            throw ProblemException.invalid(Violation("/content", ViolationCode.INVALID_VALUE, "Die Änderung enthält keine Abweichung zur aktuellen Revision"))
        val next = BoundSnapshot.bind(cmd.promiseId, head + 1, PromiseSnapshotSchema.CURRENT, cmd.newContent, cmd.configRevisionId ?: current.configRevisionId)

        insertSnapshot(jdbc, next)
        Etag.requireUpdated(jdbc.update("UPDATE taktovia.promise_test_aggregate SET revision = revision + 1 WHERE tenant_id = taktovia.current_tenant() AND id = ? AND revision = ?",
            cmd.promiseId, head)) { true }

        val relevant = changed.filter { it in PromiseSnapshotSchema.passengerRelevant(current.schemaVersion) }.sorted()
        val auditId = audit.record(jdbc, AuditEntry(cmd.actor, AuditActions.PROMISE_SNAPSHOT_CHANGED, ObjectRef("promise", cmd.promiseId), cmd.correlationId,
            mapOf("reason" to reason, "procedure" to procedure!!.name, "previous_revision" to head, "new_revision" to next.revision,
                "changed_fields" to changed.sorted(), "information_required" to relevant.isNotEmpty(), "content_hash" to next.contentHash), clock.instant()))
        val eventId = if (relevant.isEmpty()) null
            else information.order(jdbc, PassengerInformationOrder(cmd.promiseId, head, next.revision, relevant, procedure, cmd.correlationId))
        return PromiseChangeResult(cmd.promiseId, head, next.revision, changed, relevant.isNotEmpty(), auditId, eventId)
    }

    /** Lesen einer Revision (Standard: die aktuelle); Hash und Schema-Version werden beim Lesen erneut geprüft. Fremder Mandant oder unbekannt: 404. */
    fun read(jdbc: JdbcOperations, promiseId: UUID, revision: Long? = null): BoundSnapshot {
        val rev = revision ?: jdbc.query("SELECT revision FROM taktovia.promise_test_aggregate WHERE tenant_id = taktovia.current_tenant() AND id = ?",
            { rs, _ -> rs.getLong(1) }, promiseId).firstOrNull() ?: throw ProblemException.notFound()
        return load(jdbc, promiseId, rev)
    }

    private fun load(jdbc: JdbcOperations, promiseId: UUID, revision: Long): BoundSnapshot =
        jdbc.query("SELECT schema_version, content::text, content_hash, config_revision_id FROM taktovia.promise_test_snapshot " +
            "WHERE tenant_id = taktovia.current_tenant() AND promise_id = ? AND revision = ?",
            { rs, _ -> BoundSnapshot.fromStore(promiseId, revision, rs.getString(1), EventEnvelope.parsePayload(rs.getString(2)), rs.getString(3), rs.getObject(4, UUID::class.java)) },
            promiseId, revision).firstOrNull() ?: throw ProblemException.notFound()

    private fun insertSnapshot(jdbc: JdbcOperations, s: BoundSnapshot) {
        jdbc.update("INSERT INTO taktovia.promise_test_snapshot(tenant_id, promise_id, revision, schema_version, content, content_hash, config_revision_id) " +
            "VALUES (taktovia.current_tenant(), ?, ?, ?, ?::jsonb, ?, ?)", s.promiseId, s.revision, s.schemaVersion, EventEnvelope.canonical(s.content), s.contentHash, s.configRevisionId)
    }

    private fun changedFields(old: JsonNode, new: JsonNode): Set<String> =
        (old.propertyNames() + new.propertyNames()).filter { canonicalOrNull(old.get(it)) != canonicalOrNull(new.get(it)) }.toSortedSet()

    /** Vergleich über die kanonische Darstellung: `IntNode(1)` und `LongNode(1)` sind derselbe Wert. */
    private fun canonicalOrNull(n: JsonNode?): String? = n?.takeIf { !it.isNull }?.let { EventEnvelope.canonical(it) }
}
