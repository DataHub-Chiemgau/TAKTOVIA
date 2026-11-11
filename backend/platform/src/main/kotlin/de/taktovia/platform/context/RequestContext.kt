package de.taktovia.platform.context

import de.taktovia.platform.tenant.TenantId
import java.util.UUID

enum class ActorType { HUMAN, SERVICE }

/**
 * Geprüfter Akteur einer Anfrage (Kap. 02 §4.1). [issuer] und [subject] stammen ausschließlich aus dem verifizierten Token. Die Textform
 * enthält bewusst weder Subject noch Sitzung, damit ein versehentliches Protokollieren keine personenbezogenen Daten ausgibt.
 */
class Actor(val type: ActorType, val issuer: String, val subject: String, val sessionId: String?, val multiFactor: Boolean) {
    override fun toString() = "Actor(type=$type)"
    override fun equals(other: Any?) = other is Actor && type == other.type && issuer == other.issuer && subject == other.subject && sessionId == other.sessionId && multiFactor == other.multiFactor
    override fun hashCode() = java.util.Objects.hash(type, issuer, subject, sessionId, multiFactor)
}

/** Zweck der Anfrage (Kap. 02 §4.1). In P1 gibt es nur den Betriebszweck; Kooperationszwecke füllt M6. */
enum class Purpose { OPERATIONS }

/**
 * Unveränderlicher Anfragekontext (WP-M1-06, LH-I.4-MUL-006): Akteur, aktiver Mandant, Funktionsscopes, zulässige Dienste, Zweck und
 * Vertragsrevision (in P1 leer, von M6 befüllt). Der Mandant ergibt sich **ausschließlich** aus einer serverseitig geprüften aktiven
 * Mitgliedschaft (bzw. der Mandantenbindung eines Dienstkontos), nie aus Header, Pfad oder Body. Es gibt keinen öffentlichen Konstruktor
 * ohne Prüfung: Instanzen entstehen im Anfragefilter der Anwendung. Ohne Kontext gilt deny-by-default (die Anwendung antwortet 401/403).
 *
 * [toString] nennt keine personenbezogenen Daten (Akteur, Rollen); Personendaten gehören nicht in Logs.
 */
class RequestContext(
    val actor: Actor,
    val tenant: TenantId,
    roles: Set<String>,
    scopes: Set<String>,
    services: Set<UUID>,
    val correlationId: UUID,
    val purpose: Purpose = Purpose.OPERATIONS,
    val contractRevision: Long? = null,
    /** Ids der Mitgliedschaften, aus denen Rollen, Scopes und Dienste folgen (für die Wiederprüfung in der Fachtransaktion). */
    membershipIds: Set<UUID> = emptySet(),
) {
    val roles: Set<String> = roles.toSet()
    val scopes: Set<String> = scopes.toSet()
    /** Zulässige Dienste dieser Mitgliedschaft; leer = keine Dienstfreigabe (fail-closed, OP-04). */
    val services: Set<UUID> = services.toSet()
    val membershipIds: Set<UUID> = membershipIds.toSet()

    fun hasScope(scope: String) = scope in scopes
    fun mayUseService(service: UUID) = service in services

    override fun toString() = "RequestContext(tenant=$tenant, purpose=$purpose)"
}

/**
 * Kennzeichnet ein Anfrage-DTO, dessen `tenant_id` die **gewünschte Auswahl** eines Mandanten ist (Tenantwechsel), nie ein Nachweis.
 * Die Architekturregel `RequestContextRules` verbietet `tenant_id` in allen anderen Anfrage-DTOs.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class TenantSelectionRequest
