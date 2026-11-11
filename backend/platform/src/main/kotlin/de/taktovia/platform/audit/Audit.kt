package de.taktovia.platform.audit

import java.time.Instant
import java.util.UUID

/**
 * Akteur eines Auditereignisses. Format (Detailvertrag WP-M1-46): `<art>:<kennung>` mit Art `user` (Subject-UUID), `service`
 * (Dienst-/Adapterkennung), `system` (interne Komponente) oder `partner` (Partnerkennung). Nie Namen, Mailadressen oder Token.
 */
@JvmInline
value class ActorRef(val value: String) {
    init { require(FORMAT.matches(value)) { "ungültiger actor_ref" } }
    override fun toString() = value
    companion object {
        private val FORMAT = Regex("(user|service|system|partner):[A-Za-z0-9._:-]{1,120}")
        fun user(subject: UUID) = ActorRef("user:$subject")
        fun service(name: String) = ActorRef("service:$name")
        fun system(component: String) = ActorRef("system:$component")
        fun partner(id: String) = ActorRef("partner:$id")
    }
}

/** Objekt eines Auditereignisses: `<typ>:<kennung>`, Typ in Kleinbuchstaben (z. B. `membership:<uuid>`). */
data class ObjectRef(val type: String, val id: String) {
    init {
        require(Regex("[a-z][a-z0-9_]{1,62}").matches(type)) { "ungültiger Objekttyp" }
        require(Regex("[A-Za-z0-9._:-]{1,120}").matches(id)) { "ungültige Objektkennung" }
    }
    constructor(type: String, id: UUID) : this(type, id.toString())
    override fun toString() = "$type:$id"
}

/** Pflichtangaben: Akteur, Aktion, Objekt, Zeitpunkt (Standard: jetzt), `correlation_id`. Änderungen werden vor dem Schreiben maskiert. */
data class AuditEntry(
    val actor: ActorRef,
    val action: String,
    val obj: ObjectRef,
    val correlationId: UUID,
    val changes: Map<String, Any?> = emptyMap(),
    val occurredAt: Instant? = null,
) {
    init { require(AuditActions.FORMAT.matches(action)) { "ungültige Aktion '$action' (erwartet bereich.objekt.verb)" } }
}

/** Sicherheitsereignis (Aktion beginnt mit `security.`). */
data class SecurityEvent(
    val actor: ActorRef, val action: String, val obj: ObjectRef, val correlationId: UUID, val details: Map<String, Any?> = emptyMap(),
) {
    init { require(action.startsWith("security.") && AuditActions.FORMAT.matches(action)) { "Sicherheitsereignis braucht Aktion security.<…>" } }
}

/**
 * Startkatalog auditpflichtiger Aktionen (WP-M1-46; fortgeschrieben in WP-M1-12). Format `bereich.objekt.verb`, Kleinbuchstaben.
 * Neue Aktionen werden hier und in docs/umsetzungsplan/vertraege/audit-katalog.md ergänzt.
 */
object AuditActions {
    val FORMAT = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){1,3}")
    const val MEMBERSHIP_GRANTED = "identity.membership.granted"
    const val MEMBERSHIP_REVOKED = "identity.membership.revoked"
    const val ORGANIZATION_CHANGED = "identity.organization.changed"
    /** Mandantenanlage und Statuswechsel durch die Plattformverwaltung (WP-M1-05). */
    const val TENANT_CREATED = "identity.tenant.created"
    const val TENANT_STATUS_CHANGED = "identity.tenant.status_changed"
    /** Serverseitiger Tenantwechsel (WP-M1-06). */
    const val TENANT_SWITCHED = "identity.tenant.switched"
    /** Unternehmensprofil des Mandanten geändert (WP-M1-24). */
    const val TENANT_PROFILE_CHANGED = "config.tenant_profile.changed"
    /** Rolle am Fahrzeug zugeordnet oder gewechselt (WP-M1-34). */
    const val VEHICLE_ROLE_ASSIGNMENT_CHANGED = "fleet.vehicle_role.changed"
    const val CONFIG_PUBLISHED = "config.revision.published"
    const val CONFIG_REVERTED = "config.revision.reverted"
    const val PROMISE_SNAPSHOT_BOUND = "promise.snapshot.bound"
    const val PROMISE_SNAPSHOT_CHANGED = "promise.snapshot.changed"
    const val SECURITY_TENANT_MISMATCH = "security.tenant.mismatch"
    const val SECURITY_PAYLOAD_HASH_MISMATCH = "security.event.payload_hash_mismatch"
    const val SECURITY_RECIPIENT_MISMATCH = "security.event.recipient_mismatch"
    const val SECURITY_ACCESS_DENIED = "security.access.denied"
    /** Privilegiertes Konto ohne zweiten Faktor an einer geschützten API abgewiesen (WP-M1-04, LH-6.2-IAM-010). */
    const val SECURITY_MFA_MISSING = "security.auth.mfa_missing"
    val CATALOG = setOf(MEMBERSHIP_GRANTED, MEMBERSHIP_REVOKED, TENANT_SWITCHED, ORGANIZATION_CHANGED, TENANT_CREATED, TENANT_STATUS_CHANGED, TENANT_PROFILE_CHANGED, VEHICLE_ROLE_ASSIGNMENT_CHANGED, CONFIG_PUBLISHED, CONFIG_REVERTED,
        PROMISE_SNAPSHOT_BOUND, PROMISE_SNAPSHOT_CHANGED,
        SECURITY_TENANT_MISMATCH, SECURITY_PAYLOAD_HASH_MISMATCH, SECURITY_RECIPIENT_MISMATCH, SECURITY_ACCESS_DENIED, SECURITY_MFA_MISSING)
}

/**
 * Kennzeichnet einen Befehl als auditpflichtig. Architekturregel (ArchitectureRules.auditRequiredCallsAuditPort): eine so markierte
 * Methode muss `AuditPort.record` aufrufen (direkt oder über Methoden derselben Klasse), sonst scheitert der Build.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class AuditRequired
