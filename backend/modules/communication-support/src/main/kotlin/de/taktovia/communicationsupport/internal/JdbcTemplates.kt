package de.taktovia.communicationsupport.internal

import de.taktovia.communicationsupport.api.CreateTemplateRevision
import de.taktovia.communicationsupport.api.NotificationChannel
import de.taktovia.communicationsupport.api.NotificationEventTypes
import de.taktovia.communicationsupport.api.Placeholder
import de.taktovia.communicationsupport.api.TemplateQuery
import de.taktovia.communicationsupport.api.TemplateRenderer
import de.taktovia.communicationsupport.api.TemplateResolution
import de.taktovia.communicationsupport.api.TemplateRevisionView
import de.taktovia.communicationsupport.api.TemplateService
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
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
 * Vorlagenrevisionen und -auflösung (WP-M2-33). Revisionen sind unveränderlich (Trigger); die Revisionsnummer je (Ereignistyp, Sprache, Dienst, Kanal) vergibt die
 * Anlage unter einer transaktionslokalen Sperre dieses Schlüssels (der Unique-Schlüssel sichert zusätzlich ab). Die Auflösung liest in **einem** Statement,
 * damit eine parallel angelegte Revision nie einen Mischstand aus alter Kopfzeile und neuem Text ergibt.
 */
internal class JdbcTemplates(private val ctxTx: ContextTransactions, private val audit: AuditPort, private val clock: Clock) : TemplateService {

    override fun create(ctx: RequestContext, cmd: CreateTemplateRevision): TemplateRevisionView {
        validate(cmd)
        return ctxTx.run(ctx) { j -> doCreate(j, actor(ctx), ctx.correlationId, cmd) }
    }

    override fun get(ctx: RequestContext, id: UUID): TemplateRevisionView = ctxTx.run(ctx, readOnly = true) { j ->
        j.query("SELECT $COLUMNS FROM taktovia.channel_template_revision t WHERE t.id = ?", MAPPER, id).firstOrNull()
    } ?: throw ProblemException.notFound()

    override fun list(ctx: RequestContext, eventType: String?): List<TemplateRevisionView> = ctxTx.run(ctx, readOnly = true) { j ->
        j.query("SELECT $COLUMNS FROM taktovia.channel_template_revision t WHERE (CAST(? AS text) IS NULL OR t.event_type = CAST(? AS text)) ORDER BY t.created_at DESC, t.id LIMIT 200",
            MAPPER, eventType, eventType)
    }

    /** Lädt die gebundene Revision (unabhängig von ihrer Gültigkeit) in der Mandantentransaktion des Aufrufers. */
    fun byId(jdbc: JdbcOperations, id: UUID): TemplateRevisionView? = jdbc.query("SELECT $COLUMNS FROM taktovia.channel_template_revision t WHERE t.id = ?", MAPPER, id).firstOrNull()

    override fun resolve(jdbc: JdbcOperations, query: TemplateQuery): TemplateResolution {
        val wanted = query.language.lowercase().substringBefore('-').ifBlank { DEFAULT_LANGUAGE }
        val at = Timestamp.from(query.at)
        val rows = jdbc.query(
            "SELECT $COLUMNS FROM taktovia.channel_template_revision t WHERE t.event_type = ? AND t.channel = ? AND split_part(t.language, '-', 1) IN (?, ?) " +
                "AND (t.service_id IS NULL OR t.service_id = CAST(? AS uuid)) AND t.valid_from <= ? AND (t.valid_to IS NULL OR t.valid_to > ?)",
            MAPPER, query.eventType, query.channel.name, wanted, DEFAULT_LANGUAGE, query.serviceId, at, at,
        )
        // Rückfallkette: 1. Sprache + Dienst, 2. Sprache ohne Dienst, 3. Deutsch + Dienst, 4. Deutsch ohne Dienst. Innerhalb einer Stufe: voller Tag vor Hauptteil
        // vor anderer Region, dann die höchste Revision.
        fun rank(language: String): Int = when {
            language.equals(query.language, ignoreCase = true) -> 0
            language.equals(wanted, ignoreCase = true) -> 1
            else -> 2
        }
        fun step(group: (String) -> Boolean, withService: Boolean): TemplateRevisionView? = rows
            .filter { group(it.language) && (it.serviceId != null) == withService }
            .minWithOrNull(compareBy<TemplateRevisionView> { rank(it.language) }.thenByDescending { it.revision })
        val isWanted = { l: String -> l.lowercase().substringBefore('-') == wanted }
        val isDefault = { l: String -> l.lowercase().substringBefore('-') == DEFAULT_LANGUAGE }
        val found = step(isWanted, true) ?: step(isWanted, false) ?: step(isDefault, true) ?: step(isDefault, false)
        return if (found != null) TemplateResolution.Found(found) else TemplateResolution.Missing
    }

    override fun seedDefaults(ctx: RequestContext): Int = ctxTx.run(ctx) { j -> doSeed(j, actor(ctx), ctx) }

    @AuditRequired
    private fun doSeed(j: JdbcOperations, actor: ActorRef, ctx: RequestContext): Int {
        var created = 0
        for ((language, _) in DefaultTemplates.LANGUAGES) for (eventType in NotificationEventTypes.ALL.sorted()) for (channel in NotificationChannel.entries) {
            val exists = j.queryForObject("SELECT count(*) FROM taktovia.channel_template_revision WHERE event_type = ? AND language = ? AND service_id IS NULL AND channel = ?", Int::class.java, eventType, language, channel.name)!! > 0
            if (exists) continue
            val text = DefaultTemplates.text(eventType, language, channel)
            insert(j, actor, CreateTemplateRevision(eventType, language, null, channel, text.body, text.subject), clock.instant())
            created++
        }
        audit.record(j, AuditEntry(actor, AuditActions.COMMUNICATION_TEMPLATE_CREATED, ObjectRef("tenant", ctx.tenant.value), ctx.correlationId, mapOf("seed" to true, "count" to created)))
        return created
    }

    @AuditRequired
    private fun doCreate(j: JdbcOperations, actor: ActorRef, correlationId: UUID, cmd: CreateTemplateRevision): TemplateRevisionView {
        if (cmd.serviceId != null && j.queryForObject("SELECT count(*) FROM taktovia.service WHERE id = ?", Int::class.java, cmd.serviceId) != 1) throw ProblemException.notFound()
        val validFrom = cmd.validFrom ?: clock.instant()
        if (cmd.validTo != null && !cmd.validTo.isAfter(validFrom)) throw ProblemException.invalid(Violation("/valid_to", ViolationCode.INTERVAL_INVALID))
        val view = insert(j, actor, CreateTemplateRevision(cmd.eventType, cmd.language, cmd.serviceId, cmd.channel, cmd.bodyTemplate, cmd.subjectTemplate, validFrom, cmd.validTo), validFrom)
        audit.record(j, AuditEntry(actor, AuditActions.COMMUNICATION_TEMPLATE_CREATED, ObjectRef("channel_template", view.id), correlationId,
            mapOf("event_type" to view.eventType, "language" to view.language, "channel" to view.channel.name, "revision" to view.revision, "service_id" to view.serviceId?.toString(), "valid_from" to view.validFrom.toString())))
        return view
    }

    private fun insert(j: JdbcOperations, actor: ActorRef, cmd: CreateTemplateRevision, validFrom: Instant): TemplateRevisionView {
        // Revisionsnummer je Schlüssel: Sperre des Schlüssels, höchste Nummer + 1
        j.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", { _, _ -> 0 }, "tpl|${taktovia(j)}|${cmd.eventType}|${cmd.language}|${cmd.serviceId}|${cmd.channel}")
        val next = j.queryForObject(
            "SELECT coalesce(max(revision), 0) + 1 FROM taktovia.channel_template_revision WHERE event_type = ? AND language = ? AND service_id IS NOT DISTINCT FROM CAST(? AS uuid) AND channel = ?",
            Long::class.java, cmd.eventType, cmd.language, cmd.serviceId, cmd.channel.name)!!
        return j.query(
            "INSERT INTO taktovia.channel_template_revision AS t (tenant_id, id, event_type, language, service_id, channel, revision, subject_template, body_template, valid_from, valid_to, created_by) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING $COLUMNS",
            MAPPER, UUID.randomUUID(), cmd.eventType, cmd.language, cmd.serviceId, cmd.channel.name, next, cmd.subjectTemplate, cmd.bodyTemplate,
            Timestamp.from(validFrom), cmd.validTo?.let(Timestamp::from), actor.value,
        ).single()
    }

    private fun taktovia(j: JdbcOperations): UUID = j.queryForObject("SELECT taktovia.current_tenant()", UUID::class.java)!!

    /** Eingabeprüfung (422): Sprache, Ereignistyp, Platzhalter des Katalogs, Pflichtplatzhalter „Buchungsreferenz“ bei buchungsbezogenen Typen. */
    private fun validate(cmd: CreateTemplateRevision) {
        val violations = mutableListOf<Violation>()
        if (!LANGUAGE.matches(cmd.language)) violations += Violation("/language", ViolationCode.INVALID_FORMAT)
        if (!NotificationEventTypes.isKnown(cmd.eventType)) violations += Violation("/event_type", ViolationCode.NOT_IN_ENUM)
        else {
            val allowed = NotificationEventTypes.placeholders(cmd.eventType).map { it.key }.toSet()
            val used = TemplateRenderer.placeholdersIn(cmd.bodyTemplate) + (cmd.subjectTemplate?.let(TemplateRenderer::placeholdersIn) ?: emptySet())
            if (used.any { it !in allowed }) violations += Violation("/body_template", ViolationCode.NOT_IN_ENUM)
            if (NotificationEventTypes.isBookingRelated(cmd.eventType) && Placeholder.BOOKING_REFERENCE.key !in TemplateRenderer.placeholdersIn(cmd.bodyTemplate))
                violations += Violation("/body_template", ViolationCode.REQUIRED)   // Pflichtplatzhalter Buchungsreferenz fehlt im Text
        }
        if (cmd.bodyTemplate.isBlank() || cmd.bodyTemplate.length > 4000) violations += Violation("/body_template", ViolationCode.TOO_LONG)
        if (cmd.subjectTemplate != null && (cmd.subjectTemplate.isBlank() || cmd.subjectTemplate.length > 200)) violations += Violation("/subject_template", ViolationCode.TOO_LONG)
        if (violations.isNotEmpty()) throw ProblemException.invalid(*violations.toTypedArray())
    }

    private fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    internal companion object {
        const val DEFAULT_LANGUAGE = "de"
        val LANGUAGE = Regex("[a-z]{2,3}(-[A-Za-z0-9]{2,8})*")
        const val COLUMNS = "t.id, t.event_type, t.language, t.service_id, t.channel, t.revision, t.subject_template, t.body_template, t.valid_from, t.valid_to, t.created_at"
        val MAPPER = RowMapper { rs, _ ->
            TemplateRevisionView(
                rs.getObject("id", UUID::class.java), rs.getString("event_type"), rs.getString("language"), rs.getObject("service_id", UUID::class.java),
                NotificationChannel.valueOf(rs.getString("channel")), rs.getLong("revision"), rs.getString("subject_template"), rs.getString("body_template"),
                rs.getTimestamp("valid_from").toInstant(), rs.getTimestamp("valid_to")?.toInstant(), rs.getTimestamp("created_at").toInstant(),
            )
        }
    }
}
