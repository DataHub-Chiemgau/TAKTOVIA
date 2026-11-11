package de.taktovia.platform.fieldprotection

import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.audit.SecurityEvent
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevokedException
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantTransactions
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import java.util.UUID
import javax.sql.DataSource

/**
 * Warum ein Feldzugriff scheitert. Abbildung in der API: [PURPOSE_REQUIRED] → 422 (nur für sonst Berechtigte), [DENIED] und [NOT_FOUND] → 404
 * ohne Unterschied in der Antwort (Kap. 05 §5.1: kein Hinweis, dass es das Feld gibt; Fremdmandant 404).
 */
class FieldAccessException(val kind: Kind) : RuntimeException(kind.name) {
    enum class Kind { PURPOSE_REQUIRED, DENIED, NOT_FOUND }
}

/**
 * Einziger Lesepfad auf geschützte Felder (WP-M1-31, SEC-005). Ablauf in **einer** Fachtransaktion des Mandanten ([ContextTransactions]: die
 * Mitgliedschaften des Kontexts werden mit `FOR SHARE` gesperrt und gehalten):
 * 1. Entscheidung aus Rollen×Feld-Matrix, Feldscope und Zweck ([FieldRights.decide]); Verweigerung wird als Sicherheitsereignis auditiert;
 * 2. Auditeintrag „gelesen“ mit Akteur und Zweck (ohne Wert) in derselben Transaktion, vor dem Lesen;
 * 3. Lesen und Entschlüsseln über die **getrennte** Verbindung des Modulzugangs [fieldTx] (`taktovia_fields`).
 * Ein gleichzeitiger Entzug der Rolle (UPDATE der Mitgliedschaft) wartet, bis diese Transaktion endet; danach scheitert jeder Zugriff.
 * Klartext verlässt den Baustein nur als Rückgabewert; er wird weder geloggt noch als Metrik, Audit- oder Ereigniswert verwendet.
 */
class FieldAccess(
    private val ctxTx: ContextTransactions,
    private val fieldTx: TenantTransactions,
    private val rights: FieldRights,
    private val store: ProtectedColumnStore,
    private val audit: AuditPort,
    private val security: SecurityEventPort,
    private val meters: MeterRegistry? = null,
) {
    private val log = LoggerFactory.getLogger("taktovia.fieldprotection")

    /** Unterstützungsbedarf des Fahrgasts oder `null` (keine Angabe hinterlegt). Wirft [FieldAccessException]. */
    fun readSupportNeeds(ctx: RequestContext, passengerId: UUID, purpose: String?): SupportNeeds? =
        (read(ctx, ProtectedColumns.SUPPORT_NEEDS, passengerId, purpose) as? ProtectedColumnStore.Read.Value)?.let { SupportNeeds.fromBytes(it.plaintext) }

    /** Kontaktdaten des Fahrgasts (Klartext-Bytes, Format legt der Kontaktbaustein fest, WP-M1-28) oder `null`. */
    fun readContact(ctx: RequestContext, passengerId: UUID, purpose: String?): ByteArray? =
        (read(ctx, ProtectedColumns.CONTACT, passengerId, purpose) as? ProtectedColumnStore.Read.Value)?.plaintext

    private fun read(ctx: RequestContext, col: ProtectedColumn, rowId: UUID, purpose: String?): ProtectedColumnStore.Read {
        when (val d = rights.decide(ctx, col.field, purpose)) {
            FieldDecision.Allowed -> Unit
            FieldDecision.PurposeRequired -> { count(col, "purpose_required"); throw FieldAccessException(FieldAccessException.Kind.PURPOSE_REQUIRED) }
            is FieldDecision.Denied -> { reportDenied(ctx, col, rowId, d.reason); count(col, "denied"); throw FieldAccessException(FieldAccessException.Kind.DENIED) }
        }
        val result = ctxTx.run(ctx) { jdbc ->
            audit.record(jdbc, AuditEntry(actor(ctx), readAction(col), ObjectRef("passenger", rowId), ctx.correlationId, mapOf("purpose" to purpose, "field" to col.field.value)))
            val r = fieldTx.run(ctx.tenant, readOnly = true) { fj -> store.read(fj, ctx.tenant.value, col, rowId) }
            // Existiert die Zeile nicht (auch im fremden Mandanten), wird die Transaktion zurückgerollt: kein „gelesen“-Eintrag ohne Zugriff.
            if (r is ProtectedColumnStore.Read.Missing) throw FieldAccessException(FieldAccessException.Kind.NOT_FOUND)
            r
        }
        count(col, "allowed")
        return result
    }

    private fun reportDenied(ctx: RequestContext, col: ProtectedColumn, rowId: UUID, reason: String) {
        try {
            ctxTx.run(ctx) { jdbc ->
                security.report(jdbc, SecurityEvent(actor(ctx), AuditActions.SECURITY_ACCESS_DENIED, ObjectRef("passenger", rowId), ctx.correlationId,
                    mapOf("field" to col.field.value, "reason" to reason)))
            }
        } catch (e: ContextRevokedException) {
            throw e
        } catch (e: RuntimeException) {
            log.warn("{\"event\":\"field_denial_not_audited\",\"field\":\"{}\",\"error\":\"{}\"}", col.field, e.javaClass.simpleName)
        }
    }

    private fun readAction(col: ProtectedColumn) = col.field.value + ".read"

    private fun actor(ctx: RequestContext) = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    private fun count(col: ProtectedColumn, outcome: String) { meters?.counter("taktovia.field.access", "field", col.field.value, "outcome", outcome)?.increment() }
}

/**
 * Startprüfung der Datenbankverbindungen (analog `RuntimeRoleGuard`): der Modulzugang darf die Ciphertext-Spalten lesen und ist weder
 * Superuser noch `BYPASSRLS`; die allgemeine Runtime-Verbindung darf sie nicht lesen. Leere Liste = in Ordnung.
 */
object FieldAccessRoleGuard {
    private const val SQL = """SELECT r.rolsuper, r.rolbypassrls,
        has_column_privilege(current_user, 'taktovia.passenger_private', 'support_ciphertext', 'SELECT'),
        has_column_privilege(current_user, 'taktovia.passenger_private', 'contact_ciphertext', 'SELECT')
        FROM pg_roles r WHERE r.rolname = current_user"""

    fun problems(fieldAccess: DataSource, runtime: DataSource): List<String> = buildList {
        for ((name, ds, expectRead) in listOf(Triple("Modulzugang", fieldAccess, true), Triple("Runtime-Verbindung", runtime, false))) {
            ds.connection.use { c -> c.createStatement().use { st -> st.executeQuery(SQL).use { rs ->
                check(rs.next())
                if (rs.getBoolean(1)) add("$name ist Superuser")
                if (rs.getBoolean(2)) add("$name umgeht RLS (BYPASSRLS)")
                if (rs.getBoolean(3) != expectRead || rs.getBoolean(4) != expectRead)
                    add(if (expectRead) "$name darf die Ciphertext-Spalten nicht lesen" else "$name darf die Ciphertext-Spalten lesen (nur der Modulzugang darf das)")
            } } }
        }
    }
}
