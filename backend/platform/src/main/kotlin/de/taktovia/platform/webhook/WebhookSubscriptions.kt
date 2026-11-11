package de.taktovia.platform.webhook

import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.net.SsrfGuard
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.secret.SecretRef
import org.springframework.jdbc.core.JdbcOperations
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** In P1 abonnierbare Ereignistypen (vorläufig, OP-17): Mitgliedschaft, Zustandswechsel der Operation, Zusageänderung. */
object WebhookEventTypes {
    val P1 = setOf("membership.granted", "membership.revoked", "operation.state_changed", "promise.changed")
}

/** Ein Abonnement. Enthält **nie** einen Secretwert, nur die Referenz. */
data class WebhookSubscription(val id: UUID, val url: String, val eventTypes: List<String>, val secretRef: String, val active: Boolean, val revision: Long, val createdAt: Instant)

/**
 * Abonnements verwalten (WP-M1-16): Anlegen, Lesen, Deaktivieren, je Mandant (RLS) und in der Mandantentransaktion des Aufrufers. Die Ziel-URL wird gegen
 * SSRF geprüft (https, öffentliche Adresse, Port 443; Auflösung beim Speichern, vor **jedem** Aufruf erneut). Das Secret wird nur als Referenz geführt
 * (`secret://<domäne>/<name>`, Domäne = Geheimnisdomäne der Umgebung); ohne Angabe wird `webhook_<id>` in der Domäne der Umgebung vergeben, der Wert
 * kommt aus der Secret-Verwaltung. Anlegen und Deaktivieren sind auditiert, in derselben Transaktion wie die Änderung.
 */
class WebhookSubscriptionService(private val audit: AuditPort, private val ssrf: SsrfGuard, private val secretDomain: String) {
    private fun map(rs: ResultSet) = WebhookSubscription(rs.getObject("id", UUID::class.java), rs.getString("url"), (rs.getArray("event_types").array as Array<*>).map { it as String },
        rs.getString("secret_ref"), rs.getBoolean("active"), rs.getLong("revision"), rs.getTimestamp("created_at").toInstant())

    @AuditRequired
    fun create(jdbc: JdbcOperations, actor: ActorRef, correlationId: UUID, url: String, eventTypes: List<String>, secretRef: String?): WebhookSubscription {
        val violations = mutableListOf<Violation>()
        if (ssrf.resolve(url).isFailure) violations += Violation("/url", ViolationCode.TARGET_NOT_ALLOWED)
        if (eventTypes.isEmpty() || eventTypes.size > 50) violations += Violation("/event_types", ViolationCode.INVALID_VALUE)
        eventTypes.forEachIndexed { i, t -> if (t !in WebhookEventTypes.P1) violations += Violation("/event_types/$i", ViolationCode.NOT_IN_ENUM) }
        if (eventTypes.toSet().size != eventTypes.size) violations += Violation("/event_types", ViolationCode.NOT_UNIQUE)
        val id = UUID.randomUUID()
        val ref = secretRef ?: "secret://$secretDomain/webhook_${id.toString().replace("-", "")}"
        if (secretRef != null && (!SecretRef.isRef(secretRef) || SecretRef.parse(secretRef).domain != secretDomain)) violations += Violation("/secret_ref", ViolationCode.INVALID_VALUE)
        if (violations.isNotEmpty()) throw ProblemException(ErrorCode.VALIDATION_FAILED, "Mindestens ein Feld ist ungültig.", violations)
        val now = Instant.now()
        jdbc.update("INSERT INTO taktovia.webhook_subscription(tenant_id,id,url,event_types,secret_ref,created_by,created_at) VALUES (taktovia.current_tenant(),?,?,?,?,?,?)",
            id, url, eventTypes.toTypedArray(), ref, actor.value, Timestamp.from(now))
        audit.record(jdbc, AuditEntry(actor, AuditActions.WEBHOOK_SUBSCRIPTION_CREATED, ObjectRef("webhook_subscription", id), correlationId,
            mapOf("event_types" to eventTypes, "secret_ref" to ref)))
        return WebhookSubscription(id, url, eventTypes, ref, true, 1, now)
    }

    fun get(jdbc: JdbcOperations, id: UUID): WebhookSubscription? =
        jdbc.query("SELECT id, url, event_types, secret_ref, active, revision, created_at FROM taktovia.webhook_subscription WHERE id = ?", { rs, _ -> map(rs) }, id).firstOrNull()

    /** Seite nach `(created_at, id)` aufsteigend (Cursor); höchstens [limit] + 1 Zeilen, damit der Aufrufer „weitere“ erkennt. */
    fun list(jdbc: JdbcOperations, limit: Int, after: CursorPosition?): List<WebhookSubscription> =
        if (after == null) jdbc.query("SELECT id, url, event_types, secret_ref, active, revision, created_at FROM taktovia.webhook_subscription ORDER BY created_at, id LIMIT ?", { rs, _ -> map(rs) }, limit + 1)
        else jdbc.query("SELECT id, url, event_types, secret_ref, active, revision, created_at FROM taktovia.webhook_subscription WHERE (created_at, id) > (?, ?) ORDER BY created_at, id LIMIT ?",
            { rs, _ -> map(rs) }, Timestamp.from(after.createdAt), after.id, limit + 1)

    /** Deaktiviert (nicht löschen): offene Zustellungen werden `CANCELLED`. 404 bei fremder oder unbekannter ID, 412 bei veralteter Revision, wiederholtes Deaktivieren 409. */
    @AuditRequired
    fun deactivate(jdbc: JdbcOperations, actor: ActorRef, correlationId: UUID, id: UUID, expectedRevision: Long): WebhookSubscription {
        val current = jdbc.query("SELECT id, url, event_types, secret_ref, active, revision, created_at FROM taktovia.webhook_subscription WHERE id = ? FOR UPDATE", { rs, _ -> map(rs) }, id).firstOrNull()
            ?: throw ProblemException.notFound()
        if (current.revision != expectedRevision) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Ressource wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        if (!current.active) throw ProblemException(ErrorCode.CONFLICT, "Das Abonnement ist bereits deaktiviert.")
        jdbc.update("UPDATE taktovia.webhook_subscription SET active = false, deactivated_at = now(), revision = revision + 1 WHERE id = ?", id)
        jdbc.update("UPDATE taktovia.webhook_delivery SET state = 'CANCELLED', claimed_until = NULL WHERE subscription_id = ? AND state = 'PENDING'", id)
        audit.record(jdbc, AuditEntry(actor, AuditActions.WEBHOOK_SUBSCRIPTION_DEACTIVATED, ObjectRef("webhook_subscription", id), correlationId, mapOf("revision" to current.revision + 1)))
        return current.copy(active = false, revision = current.revision + 1)
    }
}
