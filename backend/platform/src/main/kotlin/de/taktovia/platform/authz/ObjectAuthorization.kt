package de.taktovia.platform.authz

import de.taktovia.platform.context.Purpose
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import org.springframework.jdbc.core.JdbcOperations
import java.util.UUID

/**
 * Verweis auf ein Objekt für die Objektberechtigung (WP-M1-08, LH-6.2-IAM-005): Art, Kennung, **Mandant des Objekts** und optional der Dienst, zu dem das
 * Objekt gehört. [serviceId] `null` = Objekt ohne Dienstbezug (nur Mandantengrenze).
 */
data class ScopedObject(val type: String, val id: UUID, val tenant: TenantId, val serviceId: UUID? = null) {
    override fun toString() = "ScopedObject($type)"   // keine Kennungen in Logs
}

/**
 * Objekt- und Dienstberechtigung zusätzlich zu Rolle und Funktionsscope: Berechtigungen sind auf Mandant, Dienst und konkretes Objekt begrenzt.
 *
 * **Regeln** (Planungsannahme OP-04, fail-closed):
 * - Objekt eines anderen Mandanten: nie lesbar, nie änderbar, unabhängig vom Dienstscope (die Datenbank trennt zusätzlich per RLS).
 * - Objekt mit Dienstbezug: nur, wenn der Dienst im Dienstscope der Mitgliedschaft(en) steht. `service_scope = NULL` (und die leere Liste) bedeutet
 *   **keinen** Dienstzugriff, nicht „alle Dienste“.
 * - Objekt ohne Dienstbezug: nur die Mandantengrenze.
 * - Nicht sichtbare Objekte verhalten sich für den Aufrufer wie nicht vorhanden: [requireRead] und [requireWrite] werfen 404, nie 403 (kein Hinweis auf Existenz).
 *
 * Die Varianten ohne `jdbc` entscheiden aus der Momentaufnahme des [RequestContext]; die Varianten mit `jdbc` lesen den **aktuellen** Dienstscope der Mitgliedschaften
 * des Kontexts in der laufenden Fachtransaktion, schreibend mit `FOR SHARE`: Ein Entzug des Scopes, der vor der Änderung committet, wird gesehen; ein gleichzeitiger
 * Entzug wartet bis zum Ende der Änderung. Damit gibt es keine erfolgreiche Änderung **nach** dem Commit eines Scope-Entzugs.
 */
interface ObjectAuthorizer {
    fun canRead(ctx: RequestContext, obj: ScopedObject, purpose: Purpose = Purpose.OPERATIONS): Boolean
    fun canWrite(ctx: RequestContext, obj: ScopedObject, purpose: Purpose = Purpose.OPERATIONS): Boolean
    fun canRead(jdbc: JdbcOperations, ctx: RequestContext, obj: ScopedObject, purpose: Purpose = Purpose.OPERATIONS): Boolean
    fun canWrite(jdbc: JdbcOperations, ctx: RequestContext, obj: ScopedObject, purpose: Purpose = Purpose.OPERATIONS): Boolean

    /** Wie [canRead]/[canWrite] mit Abbruch: 404 für nicht sichtbare Objekte (auch im selben Mandanten bei fremdem Dienst). */
    fun requireRead(jdbc: JdbcOperations, ctx: RequestContext, obj: ScopedObject, purpose: Purpose = Purpose.OPERATIONS) {
        if (!canRead(jdbc, ctx, obj, purpose)) throw ProblemException.notFound()
    }
    fun requireWrite(jdbc: JdbcOperations, ctx: RequestContext, obj: ScopedObject, purpose: Purpose = Purpose.OPERATIONS) {
        if (!canWrite(jdbc, ctx, obj, purpose)) throw ProblemException.notFound()
    }

    /**
     * Dienstfilter für Listen: Filter dürfen die Autorisierung nur **einschränken** (Kap. 05 §5.1). Liefert die Menge der Dienste, auf die eine Liste
     * höchstens zugreifen darf: ohne Wunsch [requested] der ganze Dienstscope, mit Wunsch der Schnitt. Ein Wunsch auf fremde Dienste ergibt die leere Menge
     * (leere Liste, kein Fehler, kein Hinweis auf Existenz). [tenantWide] (verwaltende Funktionsscopes, z. B. `services:write`): keine Dienstbeschränkung,
     * Ergebnis `null` = alle Dienste des Mandanten, sonst höchstens [requested].
     */
    fun effectiveServices(ctx: RequestContext, requested: Set<UUID>? = null, tenantWide: Boolean = false): Set<UUID>?
}

/**
 * Kennzeichnet ein Repository (oder einen Dienst), das Objekte **mit Dienstbezug** liest oder ändert. Architekturregel (`ServiceScopeRules`): eine so markierte
 * Klasse ruft den [ObjectAuthorizer] auf, sonst scheitert der Build.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ServiceScoped

/** Abbildung der Sicht auf Cursor: ein Cursor gilt nur in der Sicht (Dienstscope), in der er ausgestellt wurde. */
object ServiceViews {
    /** Stabiler Fingerabdruck der wirksamen Dienstmenge; `null` (mandantenweit) und die leere Menge sind verschieden. */
    fun fingerprint(services: Set<UUID>?): String =
        if (services == null) "all" else java.security.MessageDigest.getInstance("SHA-256").digest(services.map { it.toString() }.sorted().joinToString(",").toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
}
