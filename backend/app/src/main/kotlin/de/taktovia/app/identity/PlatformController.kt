package de.taktovia.app.identity

import de.taktovia.app.security.AuthenticatedPrincipal
import de.taktovia.app.web.Correlation
import de.taktovia.identity.api.IdentityApi
import de.taktovia.identity.api.NewTenant
import de.taktovia.identity.api.PlatformActor
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Anfrage-DTO „Mandant anlegen“. Der neue Mandant steht nicht darin; seine Kennung vergibt der Server. */
class TenantCreateRequest(
    val name: String, val legalEntityName: String, val legalEntityCountry: String, val taxReferenceToken: String?,
    val adminIssuer: String, val adminSubject: String, val contactEmail: String, val defaultCurrency: String?,
) {
    fun toCommand() = NewTenant(name, legalEntityName, legalEntityCountry, taxReferenceToken, adminIssuer, adminSubject)

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun from(b: Map<String, Any?>): TenantCreateRequest {
            val le = b["legal_entity"] as Map<String, Any?>; val admin = b["first_admin"] as Map<String, Any?>
            return TenantCreateRequest(b["name"] as String, le["name"] as String, le["country"] as String, le["tax_reference_token"] as String?,
                admin["issuer"] as String, admin["subject"] as String, b["contact_email"] as String, b["default_currency"] as String?)
        }
    }
}

/** Erstausstattung in der Transaktion der Mandantenanlage (Unternehmensprofil, Modul `config`); von der Anwendung verdrahtet, hier nur der Port. */
fun interface TenantSeed {
    fun seed(jdbc: JdbcOperations, tenant: TenantId, legalName: String, contactEmail: String, defaultCurrency: String, actor: String, correlationId: UUID)
}

/**
 * Plattformverwaltung (WP-M1-05, OP-36 als Planungsannahme): Mandanten anlegen und ihren Status ändern. Diese Operationen tragen **keinen**
 * Mandantenkontext ([RequestContextFilter.EXEMPT]): Das Konto muss ein Plattformbetreiberkonto mit zweitem Faktor sein (Prüfung im Modul
 * `identity`), sonst 403 ohne Details. Eine Betreiberrolle ist keine Mitgliedschaft und sieht keine Fachdaten eines Mandanten.
 */
@RestController
class PlatformController(private val identity: IdentityApi, private val seed: TenantSeed) {

    @PostMapping("/v1/platform/tenants")
    fun createTenant(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val req = TenantCreateRequest.from(body)
        val actor = actor(request)
        val currency = req.defaultCurrency ?: "EUR"
        val created = identity.platform.createTenant(actor, req.toCommand()) { j, t ->
            seed.seed(j, t, req.legalEntityName, req.contactEmail, currency, "user:" + actor.subject.take(100), actor.correlationId)
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(linkedMapOf(
            "id" to created.tenantId.toString(), "name" to created.name, "status" to created.status,
            "legal_entity_id" to created.legalEntityId.toString(), "admin_membership_id" to created.adminMembershipId.toString(),
        ))
    }

    @PostMapping("/v1/platform/tenant-status-changes")
    fun changeStatus(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): Map<String, Any?> {
        val c = identity.platform.changeTenantStatus(actor(request), TenantId(UUID.fromString(body["managed_tenant_id"] as String)), body["status"] as String)
        return linkedMapOf("managed_tenant_id" to c.tenantId.toString(), "from" to c.from, "status" to c.to)
    }

    private fun actor(request: HttpServletRequest): PlatformActor {
        val p = AuthenticatedPrincipal.of(request) ?: throw ProblemException(ErrorCode.UNAUTHENTICATED, "Anmeldung fehlt oder ist ungültig.")
        if (p.type != de.taktovia.app.security.PrincipalType.HUMAN) throw ProblemException(ErrorCode.FORBIDDEN)
        return PlatformActor(p.issuer, p.subject.toString(), Correlation.of(request), p.multiFactor)
    }
}
