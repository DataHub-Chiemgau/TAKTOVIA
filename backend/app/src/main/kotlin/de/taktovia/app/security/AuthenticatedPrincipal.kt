package de.taktovia.app.security

import jakarta.servlet.http.HttpServletRequest
import java.util.UUID

enum class PrincipalType { HUMAN, SERVICE }

/**
 * Geprüfter Akteur einer Anfrage, ausschließlich aus einem verifizierten Token abgeleitet (Claims-Vertrag
 * `docs/umsetzungsplan/vertraege/schemas/token-claims.v1.schema.json`). Mandantenwahl und Rechte kommen erst mit WP-M1-06/-07;
 * [tenantIds] ist nur ein Hinweis des IdP.
 */
data class AuthenticatedPrincipal(
    /** Verifizierter Issuer (`iss`) des Tokens; zusammen mit [subject] die Identität einer Mitgliedschaft (WP-M1-06). */
    val issuer: String,
    val subject: UUID,
    val type: PrincipalType,
    val clientId: String,
    /** Session-ID des IdP; nur bei Menschen. */
    val sessionId: String?,
    /** `loa1` (ein Faktor) oder `loa2` (zweiter Faktor durchgeführt). */
    val acr: String,
    val scopes: Set<String>,
    val roles: Set<String>,
    val tenantIds: List<UUID>,
    /** Ausstellungszeit des Tokens (`iat`); Grundlage des Vergleichs mit der Abmeldezeitmarke (WP-M1-10). */
    val issuedAt: java.time.Instant = java.time.Instant.EPOCH,
) {
    val multiFactor: Boolean get() = acr == ACR_MFA

    companion object {
        const val ACR_BASIC = "loa1"
        const val ACR_MFA = "loa2"
        const val ATTRIBUTE = "taktovia.principal"
        fun of(request: HttpServletRequest): AuthenticatedPrincipal? = request.getAttribute(ATTRIBUTE) as? AuthenticatedPrincipal
    }
}

/**
 * Quelle zusätzlicher Rollen eines Kontos außerhalb des Tokens (später `membership.role_code`, WP-M1-06/-07). Wird bei jeder Anfrage
 * befragt: Weist jemand einem Konto während einer laufenden Sitzung eine privilegierte Rolle zu, gilt die Mehrfaktor-Pflicht sofort.
 */
fun interface PrivilegedRoleDirectory {
    fun rolesOf(subject: UUID): Set<String>

    object None : PrivilegedRoleDirectory { override fun rolesOf(subject: UUID): Set<String> = emptySet() }
}
