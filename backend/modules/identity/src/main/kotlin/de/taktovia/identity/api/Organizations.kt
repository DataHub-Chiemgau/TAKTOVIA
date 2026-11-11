package de.taktovia.identity.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/**
 * Die sechs vertraglichen Rollen einer Organisation (LH-I.4-MUL-003). Getrennte Werte, keine Rolle wird aus einer anderen abgeleitet;
 * eine Organisation kann mehrere tragen. Werteliste und Abbildung auf die Begriffe aus Kap. 02 §2 sind Planungsannahme (OP-05); DB-CHECK
 * (`organization_roles_valid`), OpenAPI-Enum und diese Liste werden per Test auf Gleichheit geprüft.
 */
object OrganizationRole {
    val ALL: List<String> = listOf("PLATFORM_OPERATOR", "TRANSPORT_COMPANY", "TASK_AUTHORITY", "CLIENT", "BROKER", "ACTUAL_CARRIER")
    fun isValid(code: String) = code in ALL
}

data class LegalEntityView(val id: UUID, val name: String, val country: String, val taxReferenceToken: String?, val revision: Long, val createdAt: Instant)
data class CreateLegalEntity(val name: String, val country: String, val taxReferenceToken: String? = null)
data class UpdateLegalEntity(val name: String, val country: String)

data class OrganizationView(
    val id: UUID, val legalEntityId: UUID, val name: String, val federationRef: String, val roles: List<String>,
    val rolesValidFrom: Instant, val rolesValidTo: Instant?, val revision: Long, val createdAt: Instant,
)
data class CreateOrganization(
    val legalEntityId: UUID, val name: String, val federationRef: String, val roles: List<String>,
    val rolesValidFrom: Instant? = null, val rolesValidTo: Instant? = null,
)
/** Die `federation_ref` ist nach dem Anlegen unveränderlich (Schlüssel gegenüber Partnern). */
data class UpdateOrganization(val legalEntityId: UUID, val name: String, val roles: List<String>, val rolesValidFrom: Instant? = null, val rolesValidTo: Instant? = null)

/**
 * Verwaltung von Rechtsträgern und Organisationen im Mandanten des Kontexts (WP-M1-05). Jede Änderung schreibt Zeile, genau ein Auditereignis
 * (`identity.organization.changed`) und genau ein Outbox-Ereignis (`organization.changed`) in **einer** Transaktion. Fremde Mandanten sind
 * nicht sichtbar (404, auch als Verweis im Body); unbekannte Rollenwerte ergeben 422; veraltete Revision 412; doppelte `federation_ref` 409.
 */
interface OrganizationAdmin {
    fun createLegalEntity(ctx: RequestContext, cmd: CreateLegalEntity): LegalEntityView
    fun updateLegalEntity(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: UpdateLegalEntity): LegalEntityView
    fun listLegalEntities(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<LegalEntityView>

    fun createOrganization(ctx: RequestContext, cmd: CreateOrganization): OrganizationView
    fun updateOrganization(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: UpdateOrganization): OrganizationView
    fun getOrganization(ctx: RequestContext, id: UUID): OrganizationView
    fun listOrganizations(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<OrganizationView>
}

/** Betreiberkonto der Plattformverwaltung (aus dem geprüften Token). Kein Mandantenkontext, keine Mitgliedschaft (Kap. 02 §4.3). */
class PlatformActor(val issuer: String, val subject: String, val correlationId: UUID, val multiFactor: Boolean) {
    override fun toString() = "PlatformActor"   // keine Personendaten in Logs
}

data class NewTenant(
    val name: String, val legalEntityName: String, val legalEntityCountry: String, val taxReferenceToken: String?,
    val firstAdminIssuer: String, val firstAdminSubject: String,
)
data class CreatedTenant(val tenantId: UUID, val name: String, val status: String, val legalEntityId: UUID, val adminMembershipId: UUID)
data class TenantStatusChange(val tenantId: UUID, val from: String, val to: String)

/**
 * Plattformverwaltung (WP-M1-05, OP-36 als Planungsannahme): Mandanten anlegen und ihren Status ändern. Wer das darf, steht in der globalen
 * Tabelle `platform_operator_account` (nur Administration schreibt); Betreiberkonten sind **keine** Mitgliedschaften und sehen keine
 * Fachdaten eines Mandanten. Ohne Betreiberkonto oder ohne zweiten Faktor: 403 ohne Details. Die Anlage läuft in einer Transaktion im Kontext
 * der **neuen** Mandanten-ID (Mandant, Rechtsträger, erste Administrator-Mitgliedschaft, Audit, Ereignisse); [seed] darf in derselben
 * Transaktion weitere Erstausstattung schreiben (z. B. das Unternehmensprofil aus dem Modul `config`, durch die Anwendung verdrahtet).
 */
interface PlatformAdmin {
    fun isOperator(issuer: String, subject: String): Boolean
    fun createTenant(actor: PlatformActor, cmd: NewTenant, seed: (JdbcOperations, TenantId) -> Unit = { _, _ -> }): CreatedTenant
    /** Statuswechsel nach dem Automaten `tenant` (409 bei unzulässigem Übergang, 404 unbekannt), auditiert. */
    fun changeTenantStatus(actor: PlatformActor, tenant: TenantId, to: String): TenantStatusChange
}
