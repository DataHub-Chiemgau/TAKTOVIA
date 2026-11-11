package de.taktovia.identity.api

import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import de.taktovia.identity.internal.IdentityServicesFactory
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Öffentliche Ports des Moduls `identity` (WP-M1-06). Andere Module und die Anwendung sprechen nur über dieses Paket mit der Identität:
 * [MembershipLookup] (wählbare Mandanten, aktive Mitgliedschaft), [TenantSwitch], [MembershipAdmin] und [ContextRevalidator].
 * Den unveränderlichen `RequestContext` selbst definiert `platform.context`, damit jedes Modul ihn nutzen kann, ohne `identity` zu kennen.
 */
interface IdentityApi {
    val lookup: MembershipLookup
    val tenantSwitch: TenantSwitch
    val admin: MembershipAdmin
    /** Prüft in der Fachtransaktion, dass der Kontext noch gilt (siehe [ContextTransactions]). */
    val revalidator: ContextRevalidator
    /** Rollen -> Funktionsscopes (aus der Rechtematrix abgeleitet). */
    val scopePolicy: ScopePolicy
    /** Die versionierte Rechtematrix (WP-M1-07). */
    val rights: RightsMatrix
    /** Zentrale Autorisierungskomponente. */
    val authorizer: Authorizer
    /** Protokoll des Ausrollens der Rechtematrix. */
    val rollout: RightsMatrixRollout
    /** Rechtsträger und Organisationen mit vertraglichen Rollen (WP-M1-05). */
    val organizations: OrganizationAdmin
    /** Mandantenanlage und Statuswechsel durch die Plattformverwaltung (WP-M1-05). */
    val platform: PlatformAdmin

    companion object {
        fun create(
            tx: TenantTransactions, audit: AuditPort, security: SecurityEventPort, outbox: Outbox,
            clock: Clock = Clock.systemUTC(), rights: RightsMatrix = RightsMatrix.load(),
        ): IdentityApi = IdentityServicesFactory.create(tx, audit, security, outbox, clock, rights)
    }
}

/** Das geprüfte Subject: `issuer` und `subject` des verifizierten Tokens. */
data class SubjectRef(val issuer: String, val subject: String) {
    override fun toString() = "SubjectRef"   // keine Personendaten in Logs
}

/** Aktive Mitgliedschaften eines Subjects in **einem** Mandanten, zusammengefasst. */
class TenantMembership(
    val tenant: TenantId, val tenantName: String, val roles: Set<String>,
    /** Vereinigung der ausdrücklichen Dienstlisten; `null` = keine Dienstfreigabe (fail-closed, OP-04). */
    val serviceScope: Set<UUID>?, val membershipIds: Set<UUID>,
)

interface MembershipLookup {
    /**
     * Mandanten, in denen das Subject **jetzt** eine aktive Mitgliedschaft hat, nach Mandanten-ID sortiert (höchstens 100). Sie stammen
     * aus dem subjektgebundenen Verzeichnisindex (OP-06) und werden je Mandant gegen `membership` geprüft. Fremde Mandanten erscheinen nie.
     */
    fun selectableTenants(who: SubjectRef): List<TenantMembership>

    /** Standardmandant ohne ausdrückliche Wahl: der Mandant mit der kleinsten ID unter den aktiven Mitgliedschaften; `null` ohne eine. */
    fun defaultTenant(who: SubjectRef): TenantMembership?

    /** Aktive Mitgliedschaft im Mandanten oder `null` (nie vorhanden, widerrufen, abgelaufen, Mandant gesperrt: nicht unterscheidbar). */
    fun activeIn(who: SubjectRef, tenant: TenantId): TenantMembership?
}

sealed interface SwitchOutcome {
    class Switched(val membership: TenantMembership, val service: UUID?) : SwitchOutcome
    /** Keine aktive Mitgliedschaft im Zielmandanten (auch: Mandant existiert nicht); beides ist nicht unterscheidbar. */
    data object NoMembership : SwitchOutcome
    /** Mitgliedschaft vorhanden, aber der verlangte Dienst ist nicht freigegeben. */
    data object ServiceNotAllowed : SwitchOutcome
}

interface TenantSwitch {
    /**
     * Prüft einen Wechsel in [target] (optional mit Dienst [service]) für den Akteur des Kontexts [current]. Erfolg: Auditereignis
     * `identity.tenant.switched` im Zielmandanten. Verweigerung: Sicherheitsereignis `security.access.denied` im aktuellen Mandanten.
     * Der Wechsel ändert keinen Zustand; er liefert die Bestätigung, aus der die Anwendung die Kontextbindung ausstellt.
     */
    fun switch(current: RequestContext, target: TenantId, service: UUID?): SwitchOutcome
}

data class MembershipView(
    val id: UUID, val issuer: String, val subject: String, val roleCode: String, val serviceScope: List<UUID>?,
    val validFrom: Instant, val validTo: Instant?, val revokedAt: Instant?, val revision: Long, val createdAt: Instant,
)

data class GrantMembership(
    val issuer: String, val subject: String, val roleCode: String, val serviceScope: List<UUID>?,
    val validFrom: Instant? = null, val validTo: Instant? = null,
)

interface MembershipAdmin {
    /** Erteilt eine Mitgliedschaft im Mandanten des Kontexts: Zeile, Auditereignis und Outbox-Ereignis `membership.granted` in einer Transaktion. Doppelte Mitgliedschaft: `CONFLICT`. */
    fun grant(ctx: RequestContext, cmd: GrantMembership): MembershipView

    /** Entzieht eine Mitgliedschaft (`If-Match`-Revision): 404 unbekannt, 412 veraltet, 409 bereits entzogen. Auditereignis und `membership.revoked` in derselben Transaktion. */
    fun revoke(ctx: RequestContext, id: UUID, expectedRevision: Long)

    /** Mitgliedschaften des Mandanten, sortiert nach (`created_at`, `id`), je Seite höchstens [limit]+1 Zeilen (Aufrufer schneidet). */
    fun list(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<MembershipView>
}

/** Abbildung Rolle -> Funktionsscopes. Unbekannte Rollen haben keine Scopes (deny-by-default). */
fun interface ScopePolicy {
    fun scopesFor(roles: Set<String>): Set<String>
}
