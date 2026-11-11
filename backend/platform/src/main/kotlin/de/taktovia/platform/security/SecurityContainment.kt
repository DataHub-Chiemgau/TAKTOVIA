package de.taktovia.platform.security

import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.secret.SecretRef
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.jdbc.core.JdbcOperations
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Ergebnis einer Eindämmungsmaßnahme. Wiederholung ist unschädlich: `ALREADY_IN_STATE` ist kein Fehler. */
enum class ContainmentOutcome { EXECUTED, ALREADY_IN_STATE }

/** Konto (Subject) im Mandanten des Kontexts sperren und seine Sitzungen beenden (Sitzungsverwaltung, WP-M1-10; Adapter im Modul `identity`). */
interface AccountContainment { fun lockAccount(ctx: RequestContext, subject: UUID, reason: String): ContainmentOutcome }

/** Mandant auf `SUSPENDED` setzen (WP-M1-05; Adapter im Modul `identity`, nur für Plattformbetreiberkonten). */
interface TenantContainment { fun suspendTenant(ctx: RequestContext, tenant: TenantId): ContainmentOutcome }

/** Secret rotieren, ohne den Wert preiszugeben (WP-M1-36). */
interface SecretRotation { fun rotate(ctx: RequestContext, ref: SecretRef): ContainmentOutcome }

/**
 * Eindämmung im Sicherheitsvorfall (WP-M1-41, LH-6.18-SEC-011). Nur mit dem Funktionsscope [SCOPE] (Rechtematrix-Rolle der Sicherheitsverantwortlichen);
 * eine Anfrage ohne ihn wird mit 403 abgewiesen und als `security.access.denied` auditiert, jede Ausführung als `security.containment.executed`
 * (Akteur, Maßnahme, Objekt, nie ein Secretwert oder Freitext). Die Wirkung selbst liegt hinter den drei Ports der besitzenden Bausteine.
 */
class SecurityContainment(
    private val tx: TenantTransactions,
    private val audit: AuditPort,
    private val accounts: AccountContainment,
    private val tenants: TenantContainment,
    private val secrets: SecretRotation,
) {
    fun lockAccount(ctx: RequestContext, subject: UUID, reason: String): ContainmentOutcome {
        authorize(ctx, "account_lock", ObjectRef("user", subject))
        return guarded(ctx, "account_lock", ObjectRef("user", subject)) { accounts.lockAccount(ctx, subject, reason) }.also { record(ctx, "account_lock", ObjectRef("user", subject), it) }
    }

    fun suspendTenant(ctx: RequestContext, tenant: TenantId): ContainmentOutcome {
        authorize(ctx, "tenant_suspend", ObjectRef("tenant", tenant.value))
        return guarded(ctx, "tenant_suspend", ObjectRef("tenant", tenant.value)) { tenants.suspendTenant(ctx, tenant) }.also { record(ctx, "tenant_suspend", ObjectRef("tenant", tenant.value), it) }
    }

    fun rotateSecret(ctx: RequestContext, ref: SecretRef): ContainmentOutcome {
        authorize(ctx, "secret_rotate", ObjectRef("secret", ref.name))
        return guarded(ctx, "secret_rotate", ObjectRef("secret", ref.name)) { secrets.rotate(ctx, ref) }.also { record(ctx, "secret_rotate", ObjectRef("secret", ref.name), it) }
    }

    /** Verweigert der besitzende Baustein (z. B. kein Betreiberkonto für die Mandantensperre) mit 403, ist auch das ein auditierter Verstoß. */
    private fun <T> guarded(ctx: RequestContext, measure: String, obj: ObjectRef, block: () -> T): T = try { block() } catch (e: ProblemException) {
        if (e.code == ErrorCode.FORBIDDEN) denied(ctx, measure, obj, "owner_denied")
        throw e
    }

    private fun authorize(ctx: RequestContext, measure: String, obj: ObjectRef) {
        if (ctx.hasScope(SCOPE)) return
        denied(ctx, measure, obj, "scope_missing")
        throw ProblemException(ErrorCode.FORBIDDEN, "Keine Berechtigung für Eindämmungsmaßnahmen.")
    }

    private fun denied(ctx: RequestContext, measure: String, obj: ObjectRef, reason: String) {
        tx.run(ctx.tenant) { j -> deniedIn(j, ctx, measure, obj, reason) }
    }

    @AuditRequired
    private fun deniedIn(j: JdbcOperations, ctx: RequestContext, measure: String, obj: ObjectRef, reason: String) {
        audit.record(j, AuditEntry(actor(ctx), AuditActions.SECURITY_ACCESS_DENIED, obj, ctx.correlationId,
            mapOf("surface" to "containment", "measure" to measure, "reason" to reason)))
    }

    private fun record(ctx: RequestContext, measure: String, obj: ObjectRef, outcome: ContainmentOutcome) {
        tx.run(ctx.tenant) { j -> recordIn(j, ctx, measure, obj, outcome) }
    }

    @AuditRequired
    private fun recordIn(j: JdbcOperations, ctx: RequestContext, measure: String, obj: ObjectRef, outcome: ContainmentOutcome) {
        audit.record(j, AuditEntry(actor(ctx), AuditActions.SECURITY_CONTAINMENT_EXECUTED, obj, ctx.correlationId,
            mapOf("measure" to measure, "outcome" to outcome.name.lowercase())))
    }

    private fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject.take(100))

    companion object { const val SCOPE = "security:contain" }
}

/**
 * Rotation für Secrets ohne Gegenstelle (z. B. Signaturschlüssel, Alarmkanal-Zugang): erzeugt einen neuen Zufallswert und ersetzt die Datei
 * unter `<root>/<domäne>/<name>` atomar; die Referenz bleibt, der Wert wechselt, Leser lesen bei jedem Zugriff neu ([de.taktovia.platform.secret.FileSecretStore]).
 * Zugänge mit Gegenstelle (Datenbankrollen) rotieren nach dem Wechselnutzer-Verfahren aus WP-M1-36 und hängen sich über [SecretRotation] ein.
 */
class FileSecretRotation(private val root: Path, private val random: SecureRandom = SecureRandom()) : SecretRotation {
    override fun rotate(ctx: RequestContext, ref: SecretRef): ContainmentOutcome {
        val dir = root.resolve(ref.domain)
        val target = dir.resolve(ref.name)
        if (!Files.exists(target)) throw de.taktovia.platform.secret.SecretNotFoundException(ref)
        val bytes = ByteArray(32).also(random::nextBytes)
        val tmp = Files.createTempFile(dir, ".rotate-", ".tmp")
        Files.writeString(tmp, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + "\n")
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return ContainmentOutcome.EXECUTED
    }
}
