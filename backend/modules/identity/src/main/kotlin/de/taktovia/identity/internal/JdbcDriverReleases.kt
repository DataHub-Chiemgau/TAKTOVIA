package de.taktovia.identity.internal

import de.taktovia.identity.api.DriverReleaseProjection
import de.taktovia.identity.api.DriverReleaseState
import de.taktovia.identity.api.DriverReleaseView
import de.taktovia.identity.api.DriverReleases
import de.taktovia.identity.api.GrantDriverRelease
import de.taktovia.identity.api.ProjectedQualification
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
import de.taktovia.platform.tenant.TenantId
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Fahrerfreigabe (WP-M1-35, LH-I.4-MUL-005). Der Eigentümer erzeugt in EINER Transaktion Freigabe, Projektion (unter dem Mandanten des Empfängers; Policy
 * `owner_manage`), Audit und Ereignis; der Widerruf sperrt die Projektion in derselben Transaktion (Trigger). Der Empfänger liest nur `driver_release_projection`:
 * Zeitraum und Widerruf prüft die Policy `recipient_read` bei jedem Zugriff mit der Datenbankuhr, es gibt keinen Cache.
 */
internal class JdbcDriverReleases(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock,
) : DriverReleases {
    override fun grant(ctx: RequestContext, cmd: GrantDriverRelease): DriverReleaseView {
        val services = cmd.serviceIds.distinct()
        if (services.isEmpty() || services.size > MAX_SERVICES) throw ProblemException.invalid(Violation("/service_ids", ViolationCode.INVALID_VALUE))
        if (!cmd.validTo.isAfter(cmd.validFrom)) throw ProblemException.invalid(Violation("/valid_to", ViolationCode.INTERVAL_INVALID))
        if (!cmd.validTo.isAfter(clock.instant())) throw ProblemException.invalid(Violation("/valid_to", ViolationCode.INVALID_VALUE))   // bereits abgelaufen
        if (cmd.recipientTenant == ctx.tenant.value) throw ProblemException.invalid(Violation("/recipient_tenant_id", ViolationCode.INVALID_VALUE))
        try {
            return ctxTx.run(ctx) { j -> doGrant(j, actor(ctx), ctx.correlationId, ctx.tenant, cmd.copy(serviceIds = services)) }
        } catch (e: DataIntegrityViolationException) {
            // Empfängermandant unbekannt (Fremdschlüssel): gleichartig 404, kein Hinweis auf die Existenz anderer Mandanten
            if (sqlState(e) == "23503") throw ProblemException.notFound()
            throw e
        }
    }

    override fun revoke(ctx: RequestContext, releaseId: UUID, expectedRevision: Long): DriverReleaseView =
        ctxTx.run(ctx) { j -> doRevoke(j, actor(ctx), ctx.correlationId, releaseId, expectedRevision) }

    override fun get(ctx: RequestContext, releaseId: UUID): DriverReleaseView = ctxTx.run(ctx, readOnly = true) { j ->
        j.query("SELECT $COLUMNS FROM taktovia.driver_release r WHERE r.id = ?", MAPPER, releaseId).firstOrNull() ?: throw ProblemException.notFound()
    }

    override fun projection(ctx: RequestContext, releaseId: UUID, serviceId: UUID): DriverReleaseProjection = ctxTx.run(ctx, readOnly = true) { j ->
        // Die RLS-Policy `recipient_read` liefert nur Zeilen des eigenen Mandanten im Zeitraum und vor dem Widerruf; tenant_id wird zusätzlich ausdrücklich gefiltert,
        // damit der Eigentümer (der seine Projektionen unter fremdem Mandanten sehen darf, `owner_manage`) hier nie als Empfänger auftritt.
        // Eine Anweisung (ein Datenstand): die Qualifikationen der Projektion kommen als Zeilen aus dem jsonb-Feld.
        val rows = j.query(
            "SELECT p.id, p.service_ids, p.valid_from, p.valid_to, p.contact_channel, p.contact_value, q.ord, q.item ->> 'code' AS q_code, " +
                "CAST(q.item ->> 'valid_from' AS timestamptz) AS q_from, CAST(q.item ->> 'valid_to' AS timestamptz) AS q_to " +
                "FROM taktovia.driver_release_projection p LEFT JOIN LATERAL jsonb_array_elements(p.qualifications) WITH ORDINALITY AS q(item, ord) ON true " +
                "WHERE p.tenant_id = taktovia.current_tenant() AND p.id = ? AND ? = ANY(p.service_ids) ORDER BY q.ord",
            RowMapper { rs, _ ->
                Triple(
                    DriverReleaseProjection(rs.getObject("id", UUID::class.java), (rs.getArray("service_ids").array as Array<*>).map { it as UUID },
                        rs.getTimestamp("valid_from").toInstant(), rs.getTimestamp("valid_to").toInstant(), emptyList(), rs.getString("contact_channel"), rs.getString("contact_value")),
                    rs.getString("q_code"), rs.getTimestamp("q_from")?.toInstant() to rs.getTimestamp("q_to")?.toInstant(),
                )
            }, releaseId, serviceId,
        )
        val head = rows.firstOrNull()?.first ?: throw ProblemException.notFound()
        return@run head.copy(qualifications = rows.filter { it.second != null }.map { ProjectedQualification(it.second!!, it.third.first!!, it.third.second) })
    }

    @AuditRequired
    private fun doGrant(j: JdbcOperations, actor: ActorRef, correlationId: UUID, owner: TenantId, cmd: GrantDriverRelease): DriverReleaseView {
        // 1. Fahrer des Eigentümers (RLS: fremder Mandant und unbekannt nicht unterscheidbar); gesperrt, damit er nicht parallel deaktiviert wird
        val status = j.query("SELECT d.status FROM taktovia.driver d WHERE d.id = ? FOR SHARE", { rs, _ -> rs.getString(1) }, cmd.driverId).firstOrNull() ?: throw ProblemException.notFound()
        if (status != "ACTIVE") throw ProblemException(ErrorCode.CONFLICT, "Der Fahrer ist nicht aktiv und kann nicht freigegeben werden.")
        val from = Timestamp.from(cmd.validFrom); val to = Timestamp.from(cmd.validTo)

        // 2. Freigabe (Eigentümerkontext)
        val id = UUID.randomUUID()
        val view = j.query(
            "INSERT INTO taktovia.driver_release AS r (tenant_id, id, driver_id, recipient_tenant_id, service_ids, valid_from, valid_to, created_by) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, CAST(? AS uuid[]), ?, ?, ?) RETURNING $COLUMNS",
            MAPPER, id, cmd.driverId, cmd.recipientTenant, uuidArray(cmd.serviceIds), from, to, actor.value,
        ).single()

        // 3. Projektion: die feste Feldliste (Qualifikationen, Einsatzzeitraum, Kontaktkanal) wird beim Eigentümer aus dem Stamm gebildet und unter dem Empfänger
        //    gespeichert; je Qualifikation gilt die höchste Revision, sofern sie zu Beginn der Freigabe noch gültig ist. Kein Name, kein Nachweisverweis.
        j.update(
            "INSERT INTO taktovia.driver_release_projection (tenant_id, id, owner_tenant_id, service_ids, valid_from, valid_to, qualifications, contact_channel, contact_value) " +
                "SELECT r.recipient_tenant_id, r.id, r.tenant_id, r.service_ids, r.valid_from, r.valid_to, " +
                "COALESCE((SELECT jsonb_agg(jsonb_build_object('code', q.qualification_code, 'valid_from', q.valid_from, 'valid_to', q.valid_to) ORDER BY q.qualification_code) " +
                "FROM (SELECT DISTINCT ON (x.qualification_code) x.qualification_code, x.valid_from, x.valid_to FROM taktovia.qualification_revision x " +
                "WHERE x.driver_id = r.driver_id ORDER BY x.qualification_code, x.revision DESC) q WHERE q.valid_to IS NULL OR q.valid_to > r.valid_from), '[]'::jsonb), " +
                "d.contact_channel, d.contact_value FROM taktovia.driver_release r JOIN taktovia.driver d ON d.tenant_id = r.tenant_id AND d.id = r.driver_id WHERE r.id = ?",
            id,
        )

        // 4. genau ein Audit (ohne Fahrerdaten) und genau ein ausdrücklich an den Empfänger adressiertes Ereignis (ohne Fahrerangaben)
        audit.record(j, AuditEntry(actor, AuditActions.DRIVER_RELEASE_GRANTED, ObjectRef("driver_release", id), correlationId,
            mapOf("recipient_tenant_id" to cmd.recipientTenant.toString(), "service_count" to cmd.serviceIds.size, "valid_from" to cmd.validFrom.toString(), "valid_to" to cmd.validTo.toString())))
        outbox.append(j, OutboxEvent(id, view.revision, "driver.release_granted", payload(view), clock.instant(), recipient = TenantId(cmd.recipientTenant), correlationId = correlationId))
        return view
    }

    @AuditRequired
    private fun doRevoke(j: JdbcOperations, actor: ActorRef, correlationId: UUID, id: UUID, expectedRevision: Long): DriverReleaseView {
        // Zeile sperren: gleichzeitige Widerrufe derselben Freigabe werden nacheinander entschieden
        val current = j.query("SELECT $COLUMNS FROM taktovia.driver_release r WHERE r.id = ? FOR NO KEY UPDATE", MAPPER, id).firstOrNull() ?: throw ProblemException.notFound()
        if (current.revision != expectedRevision) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Freigabe wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        if (current.state != DriverReleaseState.PLANNED && current.state != DriverReleaseState.ACTIVE)
            throw ProblemException(ErrorCode.CONFLICT, "Die Freigabe ist bereits widerrufen oder abgelaufen.")
        // Der Trigger driver_release_revoke_projection sperrt die Projektion des Empfängers in derselben Transaktion: nach dem Commit sieht der Empfänger nichts mehr.
        val view = j.query("UPDATE taktovia.driver_release AS r SET revoked_at = clock_timestamp(), revoked_by = ? WHERE r.id = ? RETURNING $COLUMNS", MAPPER, actor.value, id).single()
        audit.record(j, AuditEntry(actor, AuditActions.DRIVER_RELEASE_REVOKED, ObjectRef("driver_release", id), correlationId,
            mapOf("recipient_tenant_id" to view.recipientTenant.toString(), "revision" to view.revision)))
        outbox.append(j, OutboxEvent(id, view.revision, "driver.release_revoked", payload(view), clock.instant(), recipient = TenantId(view.recipientTenant), correlationId = correlationId))
        return view
    }

    private fun payload(v: DriverReleaseView): Map<String, Any?> = linkedMapOf(
        "release_id" to v.id.toString(), "recipient_tenant_id" to v.recipientTenant.toString(), "service_ids" to v.serviceIds.map { it.toString() },
        "valid_from" to v.validFrom.toString(), "valid_to" to v.validTo.toString(), "revision" to v.revision,
    )

    private fun uuidArray(ids: List<UUID>) = ids.joinToString(",", "{", "}")
    private fun sqlState(e: Throwable): String? = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState
    private fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    private companion object {
        const val MAX_SERVICES = 50
        /** Der Zustand wird aus Zeit (Datenbankuhr) und `revoked_at` abgeleitet, nie gespeichert (`zustaende/driver-release.md`). */
        const val COLUMNS = "r.id, r.driver_id, r.recipient_tenant_id, r.service_ids, r.valid_from, r.valid_to, r.revoked_at, r.revision, " +
            "CASE WHEN r.revoked_at IS NOT NULL THEN 'REVOKED' WHEN clock_timestamp() < r.valid_from THEN 'PLANNED' WHEN clock_timestamp() < r.valid_to THEN 'ACTIVE' ELSE 'EXPIRED' END AS state"
        val MAPPER = RowMapper { rs, _ ->
            DriverReleaseView(
                rs.getObject("id", UUID::class.java), rs.getObject("driver_id", UUID::class.java), rs.getObject("recipient_tenant_id", UUID::class.java),
                (rs.getArray("service_ids").array as Array<*>).map { it as UUID }, rs.getTimestamp("valid_from").toInstant(), rs.getTimestamp("valid_to").toInstant(),
                rs.getTimestamp("revoked_at")?.toInstant(), rs.getLong("revision"), DriverReleaseState.valueOf(rs.getString("state")),
            )
        }
    }
}
