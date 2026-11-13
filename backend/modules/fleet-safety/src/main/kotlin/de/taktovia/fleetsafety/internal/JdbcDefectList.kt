package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.DefectListPage
import de.taktovia.fleetsafety.api.DefectListQuery
import de.taktovia.fleetsafety.api.DefectSortColumn
import de.taktovia.fleetsafety.api.DefectView
import de.taktovia.platform.api.KeyedCursorPosition
import de.taktovia.platform.authz.ServiceScopeAuthorizer
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Abfrage der Schadenliste (WP-M3-49, LH-6.22-DMG-009). Alle SQL-Texte sind `const val` (Sortierausdrücke stehen in einer geschlossenen Liste, kein Wert des Clients gelangt in den SQL-Text; die Werte
 * sind Platzhalter). **Momentaufnahme:** Status, Klasse und Zuständiger werden zum Stand `t` der ersten Seite aus den unveränderlichen Historien gelesen (`defect_status_history`,
 * `defect_classification_history`; ohne Historienzeile gilt der aktuelle Wert), Akten nach `t` gehören nicht dazu. Die Sortierung ist `(Sortierwert, id)` mit dem Tiebreaker `id` in derselben Richtung, die Paginierung ist
 * Keyset ab dem letzten gelieferten Element. Dadurch erscheint eine Akte, die während der Paginierung ihren Status wechselt, weder doppelt noch geht sie verloren.
 * Sichtbar ist eine Akte nur, wenn ihr Fahrzeug keinem Dienst zugeordnet ist oder einem Dienst im Scope des Aufrufers (wie [ServiceScopeAuthorizer]: leerer Scope = kein Dienstzugriff); Fahrer sehen nur eigene Meldungen.
 */
internal object JdbcDefectList {
    private const val COLS = JdbcDefects.COLUMNS

    private const val Q_STATUS_AS_OF = "COALESCE((SELECT h.to_status FROM taktovia.defect_status_history h WHERE h.defect_id = x.id AND h.created_at <= p.t ORDER BY h.created_at DESC, h.revision DESC LIMIT 1), x.status)"
    private const val Q_SEVERITY_AS_OF = "COALESCE((SELECT c.to_severity FROM taktovia.defect_classification_history c WHERE c.defect_id = x.id AND c.created_at <= p.t ORDER BY c.created_at DESC, c.id DESC LIMIT 1), x.severity)"
    private const val Q_ASSIGNEE_AS_OF = "(COALESCE((SELECT ARRAY[c.assignee_after] FROM taktovia.defect_classification_history c WHERE c.defect_id = x.id AND c.created_at <= p.t ORDER BY c.created_at DESC, c.id DESC LIMIT 1), ARRAY[x.assignee_ref]))[1]"

    private const val FROM_WHERE =
        "FROM (SELECT x.*, $Q_STATUS_AS_OF AS q_status, $Q_SEVERITY_AS_OF AS q_sev, $Q_ASSIGNEE_AS_OF AS q_asg FROM taktovia.defect_case x CROSS JOIN (SELECT CAST(? AS timestamptz) AS t) p WHERE x.created_at <= p.t) d " +
            "JOIN taktovia.vehicle v ON v.tenant_id = d.tenant_id AND v.id = d.vehicle_id " +
            "WHERE (CAST(? AS uuid) IS NULL OR d.vehicle_id = CAST(? AS uuid)) AND (CAST(? AS text) IS NULL OR d.q_status = CAST(? AS text)) " +
            "AND (CAST(? AS text[]) IS NULL OR d.q_sev = ANY (CAST(? AS text[]))) AND (CAST(? AS timestamptz) IS NULL OR d.reported_at >= CAST(? AS timestamptz)) " +
            "AND (CAST(? AS timestamptz) IS NULL OR d.reported_at < CAST(? AS timestamptz)) AND (CAST(? AS uuid) IS NULL OR v.depot_id = CAST(? AS uuid)) " +
            "AND (CAST(? AS uuid) IS NULL OR d.q_asg = CAST(? AS uuid)) AND (CAST(? AS uuid) IS NULL OR d.reported_by = CAST(? AS uuid)) " +
            "AND (NOT EXISTS (SELECT 1 FROM taktovia.vehicle_pool_assignment pa WHERE pa.vehicle_id = d.vehicle_id) " +
            "OR EXISTS (SELECT 1 FROM taktovia.vehicle_pool_assignment pa WHERE pa.vehicle_id = d.vehicle_id AND pa.service_id = ANY (CAST(? AS uuid[])))) "

    private const val E_CREATED = "(EXTRACT(EPOCH FROM d.created_at) * 1000000)::bigint"
    private const val E_REPORTED = "(EXTRACT(EPOCH FROM d.reported_at) * 1000000)::bigint"
    private const val E_CASE = "d.case_number COLLATE \"C\""
    private const val E_VEHICLE = "coalesce(v.vehicle_label, '') COLLATE \"C\""
    private const val E_PRIORITY = "CASE d.q_sev WHEN 'SAFETY_CRITICAL' THEN 1 WHEN 'OPERATIONALLY_RELEVANT' THEN 2 WHEN 'COSMETIC' THEN 3 WHEN 'OTHER' THEN 4 ELSE 5 END"
    private const val E_STATUS = "CASE d.q_status WHEN 'NEW' THEN 0 WHEN 'REVIEWED' THEN 1 WHEN 'IN_PROGRESS' THEN 2 WHEN 'REPAIR' THEN 3 WHEN 'CLOSED' THEN 4 ELSE 5 END"
    private const val E_ASSIGNEE = "coalesce(d.q_asg::text, '') COLLATE \"C\""

    private const val Q_CREATED_ASC = "SELECT $COLS, ($E_CREATED)::text AS sort_value $FROM_WHERE AND (? = false OR ($E_CREATED, d.id) > (CAST(? AS bigint), CAST(? AS uuid))) ORDER BY $E_CREATED ASC, d.id ASC LIMIT ?"
    private const val Q_CREATED_DESC = "SELECT $COLS, ($E_CREATED)::text AS sort_value $FROM_WHERE AND (? = false OR ($E_CREATED, d.id) < (CAST(? AS bigint), CAST(? AS uuid))) ORDER BY $E_CREATED DESC, d.id DESC LIMIT ?"
    private const val Q_REPORTED_ASC = "SELECT $COLS, ($E_REPORTED)::text AS sort_value $FROM_WHERE AND (? = false OR ($E_REPORTED, d.id) > (CAST(? AS bigint), CAST(? AS uuid))) ORDER BY $E_REPORTED ASC, d.id ASC LIMIT ?"
    private const val Q_REPORTED_DESC = "SELECT $COLS, ($E_REPORTED)::text AS sort_value $FROM_WHERE AND (? = false OR ($E_REPORTED, d.id) < (CAST(? AS bigint), CAST(? AS uuid))) ORDER BY $E_REPORTED DESC, d.id DESC LIMIT ?"
    private const val Q_CASE_ASC = "SELECT $COLS, ($E_CASE)::text AS sort_value $FROM_WHERE AND (? = false OR ($E_CASE, d.id) > (CAST(? AS text) COLLATE \"C\", CAST(? AS uuid))) ORDER BY $E_CASE ASC, d.id ASC LIMIT ?"
    private const val Q_CASE_DESC = "SELECT $COLS, ($E_CASE)::text AS sort_value $FROM_WHERE AND (? = false OR ($E_CASE, d.id) < (CAST(? AS text) COLLATE \"C\", CAST(? AS uuid))) ORDER BY $E_CASE DESC, d.id DESC LIMIT ?"
    private const val Q_VEHICLE_ASC = "SELECT $COLS, ($E_VEHICLE)::text AS sort_value $FROM_WHERE AND (? = false OR ($E_VEHICLE, d.id) > (CAST(? AS text) COLLATE \"C\", CAST(? AS uuid))) ORDER BY $E_VEHICLE ASC, d.id ASC LIMIT ?"
    private const val Q_VEHICLE_DESC = "SELECT $COLS, ($E_VEHICLE)::text AS sort_value $FROM_WHERE AND (? = false OR ($E_VEHICLE, d.id) < (CAST(? AS text) COLLATE \"C\", CAST(? AS uuid))) ORDER BY $E_VEHICLE DESC, d.id DESC LIMIT ?"
    private const val Q_PRIORITY_ASC = "SELECT $COLS, ($E_PRIORITY)::text AS sort_value $FROM_WHERE AND (? = false OR ($E_PRIORITY, d.id) > (CAST(? AS int), CAST(? AS uuid))) ORDER BY $E_PRIORITY ASC, d.id ASC LIMIT ?"
    private const val Q_PRIORITY_DESC = "SELECT $COLS, ($E_PRIORITY)::text AS sort_value $FROM_WHERE AND (? = false OR ($E_PRIORITY, d.id) < (CAST(? AS int), CAST(? AS uuid))) ORDER BY $E_PRIORITY DESC, d.id DESC LIMIT ?"
    private const val Q_STATUS_ASC = "SELECT $COLS, ($E_STATUS)::text AS sort_value $FROM_WHERE AND (? = false OR ($E_STATUS, d.id) > (CAST(? AS int), CAST(? AS uuid))) ORDER BY $E_STATUS ASC, d.id ASC LIMIT ?"
    private const val Q_STATUS_DESC = "SELECT $COLS, ($E_STATUS)::text AS sort_value $FROM_WHERE AND (? = false OR ($E_STATUS, d.id) < (CAST(? AS int), CAST(? AS uuid))) ORDER BY $E_STATUS DESC, d.id DESC LIMIT ?"
    private const val Q_ASSIGNEE_ASC = "SELECT $COLS, ($E_ASSIGNEE)::text AS sort_value $FROM_WHERE AND (? = false OR ($E_ASSIGNEE, d.id) > (CAST(? AS text) COLLATE \"C\", CAST(? AS uuid))) ORDER BY $E_ASSIGNEE ASC, d.id ASC LIMIT ?"
    private const val Q_ASSIGNEE_DESC = "SELECT $COLS, ($E_ASSIGNEE)::text AS sort_value $FROM_WHERE AND (? = false OR ($E_ASSIGNEE, d.id) < (CAST(? AS text) COLLATE \"C\", CAST(? AS uuid))) ORDER BY $E_ASSIGNEE DESC, d.id DESC LIMIT ?"

    private fun sqlFor(column: DefectSortColumn, desc: Boolean): String = when (column) {
        DefectSortColumn.CREATED -> if (desc) Q_CREATED_DESC else Q_CREATED_ASC
        DefectSortColumn.REPORTED_AT -> if (desc) Q_REPORTED_DESC else Q_REPORTED_ASC
        DefectSortColumn.CASE_NUMBER -> if (desc) Q_CASE_DESC else Q_CASE_ASC
        DefectSortColumn.VEHICLE -> if (desc) Q_VEHICLE_DESC else Q_VEHICLE_ASC
        DefectSortColumn.PRIORITY -> if (desc) Q_PRIORITY_DESC else Q_PRIORITY_ASC
        DefectSortColumn.STATUS -> if (desc) Q_STATUS_DESC else Q_STATUS_ASC
        DefectSortColumn.ASSIGNEE -> if (desc) Q_ASSIGNEE_DESC else Q_ASSIGNEE_ASC
    }

    private class Row(val view: DefectView, val sortValue: String)
    private val MAPPER = RowMapper { rs, n -> Row(JdbcDefects.MAPPER.mapRow(rs, n)!!, rs.getString("sort_value")) }

    /** Cursor-Schlüssel `<Stand t in µs>~<Sortierwert>`. */
    private fun key(asOf: Instant, sortValue: String) = "${asOf.epochSecond * 1_000_000 + asOf.nano / 1_000}~$sortValue"
    private fun bad() = ProblemException(ErrorCode.INVALID_CURSOR, "Der Cursor ist ungültig oder abgelaufen; Liste ohne Cursor neu beginnen.")

    fun run(j: JdbcOperations, ctx: RequestContext, q: DefectListQuery, now: Instant, ownOnly: UUID?): DefectListPage {
        var asOf = now; var keyed = false; var sortValue: String? = null; val afterId = q.after?.id
        if (q.after != null) {
            val parts = q.after.key.split('~', limit = 2)
            val micros = parts.getOrNull(0)?.toLongOrNull() ?: throw bad()
            if (parts.size != 2) throw bad()
            asOf = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000); sortValue = parts[1]; keyed = true
            // numerische Sortierwerte müssen Zahlen sein (ein manipulierter, aber gültig signierter Wert ist nicht möglich; zweite Schicht)
            if (q.sort.column != DefectSortColumn.CASE_NUMBER && q.sort.column != DefectSortColumn.VEHICLE && q.sort.column != DefectSortColumn.ASSIGNEE && sortValue.toLongOrNull() == null) throw bad()
        }
        val scope = ServiceScopeAuthorizer.effectiveServices(ctx, null, tenantWide = false).orEmpty().joinToString(",", "{", "}")   // nur UUIDs
        val severities = q.severities?.joinToString(",", "{", "}")
        val from = q.reportedFrom?.let(Timestamp::from); val to = q.reportedTo?.let(Timestamp::from)
        val args = arrayOf<Any?>(
            Timestamp.from(asOf), q.vehicleId, q.vehicleId, q.status, q.status, severities, severities, from, from, to, to, q.depotId, q.depotId, q.assigneeRef, q.assigneeRef, ownOnly, ownOnly, scope,
            keyed, sortValue, afterId, q.limit + 1,
        )
        val rows = j.query(sqlFor(q.sort.column, q.sort.descending), MAPPER, *args)
        val page = rows.take(q.limit)
        val blocking = blockingCases(j, page.map { it.view.caseNumber })
        val items = page.map { it.view.copy(description = null, evidence = emptyList(), evidencePending = 0, blocksVehicle = it.view.caseNumber in blocking) }
        val next = if (rows.size > q.limit) page.last().let { KeyedCursorPosition(key(asOf, it.sortValue), it.view.id) } else null
        return DefectListPage(items, next, asOf)
    }

    private fun blockingCases(j: JdbcOperations, caseNumbers: List<String>): Set<String> =
        if (caseNumbers.isEmpty()) emptySet() else j.query(
            "SELECT DISTINCT reason_ref FROM taktovia.vehicle_block WHERE status = 'ACTIVE' AND source = 'SAFETY' AND reason_code = 'DEFECT_CRITICAL' AND reason_ref = ANY (string_to_array(?, ','))",
            { rs, _ -> rs.getString(1) }, caseNumbers.joinToString(",")).toSet()
}
