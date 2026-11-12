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
    /** Organisationsstruktur angelegt oder geändert (WP-M1-09): Region, Betriebshof, Dienstkopf, Kostenstelle; Objekt `region:`, `depot:`, `service:`, `cost_center:`. */
    const val REGION_CHANGED = "identity.region.changed"
    const val DEPOT_CHANGED = "identity.depot.changed"
    const val SERVICE_CHANGED = "identity.service.changed"
    const val COST_CENTER_CHANGED = "finance.cost_center.changed"
    /** Serverseitige Regelprüfung eines bestätigenden Befehls bestanden (WP-M1-26): Details `command`, `rule_revisions`, `schema_version`, `as_of`, `rule_levels`. */
    const val POLICY_CHECKED = "policy.command.checked"
    /** Mandantenanlage und Statuswechsel durch die Plattformverwaltung (WP-M1-05). */
    const val TENANT_CREATED = "identity.tenant.created"
    const val TENANT_STATUS_CHANGED = "identity.tenant.status_changed"
    /** Serverseitiger Tenantwechsel (WP-M1-06). */
    const val TENANT_SWITCHED = "identity.tenant.switched"
    /** Unternehmensprofil des Mandanten geändert (WP-M1-24). */
    const val TENANT_PROFILE_CHANGED = "config.tenant_profile.changed"
    /** Rolle am Fahrzeug zugeordnet oder gewechselt (WP-M1-34). */
    const val VEHICLE_ROLE_ASSIGNMENT_CHANGED = "fleet.vehicle_role.changed"
    /** Fahrzeugstamm angelegt, geändert, deaktiviert; Kapazitätskonfiguration geändert (WP-M3-03/-04); Objekt `vehicle:<id>`. */
    const val VEHICLE_CREATED = "fleet.vehicle.created"
    const val VEHICLE_CHANGED = "fleet.vehicle.changed"
    const val VEHICLE_DEACTIVATED = "fleet.vehicle.deactivated"
    const val VEHICLE_CAPACITY_CHANGED = "fleet.vehicle.capacity_changed"
    /** Dienstzuordnung des Fahrzeugpools angelegt bzw. beendet (WP-M3-06); Objekt `vehicle:<id>`. */
    const val POOL_ASSIGNMENT_CREATED = "fleet.pool_assignment.created"
    const val POOL_ASSIGNMENT_ENDED = "fleet.pool_assignment.ended"
    /** Fahrerstamm angelegt bzw. geändert und Qualifikationsrevision angelegt (WP-M3-05); Objekt `driver:<id>`, Details ohne Klarnamen und Kontaktdaten. */
    const val DRIVER_CREATED = "staff.driver.created"
    const val DRIVER_CHANGED = "staff.driver.changed"
    const val QUALIFICATION_CHANGED = "staff.qualification.changed"
    /** Fahrer-Gerät an Konto und Sitzung gebunden bzw. Bindung beendet (WP-M3-24); Objekt `driver_device:<id>`. */
    const val DRIVER_DEVICE_REGISTERED = "driver.device.registered"
    const val DRIVER_DEVICE_REVOKED = "driver.device.revoked"
    /** Schichtzuweisung der Leitstelle angelegt bzw. geändert (WP-M3-07), Dienstantritt und Dienstende des Fahrers (WP-M3-25); Objekt `staff_shift:<id>`, Details mit Gerät, ohne Namen. */
    const val SHIFT_CREATED = "staff.shift.created"
    const val SHIFT_CHANGED = "staff.shift.changed"
    const val SHIFT_STARTED = "staff.shift.started"
    const val SHIFT_ENDED = "staff.shift.ended"
    /** Schadenakte (WP-M3-43/-47/-48): gemeldet, Statuswechsel, Nachtrag, Historie gelesen; Objekt `defect:<id>`, Details nennen nie Beschreibungstexte. */
    const val DEFECT_REPORTED = "fleet.defect.reported"
    const val DEFECT_STATE_CHANGED = "fleet.defect.state_changed"
    const val DEFECT_AMENDED = "fleet.defect.amended"
    const val DEFECT_HISTORY_READ = "fleet.defect.history_read"
    /** Prüflauf gestartet bzw. kein Start möglich, weil keine eindeutig passende freigegebene Vorlage besteht (WP-M3-13); Objekt `inspection:<id>` bzw. `staff_shift:<id>`, Details nur Kennungen und Codes. */
    const val INSPECTION_STARTED = "fleet.inspection.started"
    const val INSPECTION_TEMPLATE_UNAVAILABLE = "fleet.inspection.template_unavailable"
    /** Fotoevidenz einer Schadenakte (WP-M3-44): freigegeben bzw. in der Medienprüfung abgewiesen; Integritätsverletzung (Objekt ≠ gespeicherter Hash) als Sicherheitsereignis. */
    const val DEFECT_EVIDENCE_RELEASED = "fleet.defect_evidence.released"
    const val DEFECT_EVIDENCE_REJECTED = "fleet.defect_evidence.rejected"
    const val SECURITY_DEFECT_EVIDENCE_TAMPERED = "security.defect_evidence.integrity_violation"
    /** Vertraulichkeit der Schadenakte (WP-M3-50): Zugriff auf ein vertrauliches Dokument (Link-Ausstellung und Abruf, Details `stage`) bzw. abgewiesener Zugriff als Sicherheitsereignis ohne Fallinhalt. */
    const val DEFECT_CONFIDENTIAL_READ = "fleet.defect_evidence.confidential_read"
    const val SECURITY_DEFECT_CONFIDENTIAL_DENIED = "security.defect_confidential.access_denied"
    /** Fahrerfreigabe erteilt bzw. widerrufen (WP-M1-35); `details` nennen nie Fahrerdaten. */
    const val DRIVER_RELEASE_GRANTED = "staff.driver_release.granted"
    const val DRIVER_RELEASE_REVOKED = "staff.driver_release.revoked"
    /** Aufbewahrungsregeln und Löschläufe (WP-M1-33): Regel angelegt oder geändert, freigegeben; Lauf gestartet, beendet; Archivdatensatz gelesen. */
    const val RETENTION_RULE_CHANGED = "retention.rule.changed"
    const val RETENTION_RULE_APPROVED = "retention.rule.approved"
    const val RETENTION_ERASURE_STARTED = "retention.erasure.started"
    const val RETENTION_ERASURE_FINISHED = "retention.erasure.finished"
    const val RETENTION_ARCHIVE_READ = "retention.archive.read"
    const val CONFIG_PUBLISHED = "config.revision.published"
    const val CONFIG_REVERTED = "config.revision.reverted"
    /** Webhook-Abonnement angelegt bzw. deaktiviert (WP-M1-16, SEC-009-Bausteine). */
    const val WEBHOOK_SUBSCRIPTION_CREATED = "webhook.subscription.created"
    const val WEBHOOK_SUBSCRIPTION_DEACTIVATED = "webhook.subscription.deactivated"
    /** Entwurf einer Konfigurationsrevision angelegt bzw. geändert (WP-M1-20). */
    const val CONFIG_DRAFT_CREATED = "config.revision.created"
    const val CONFIG_DRAFT_CHANGED = "config.revision.changed"
    /** Wirkungsanalyse einer Revision durchgeführt bzw. Bericht bei der Publikation an die unveränderte Revision gebunden (WP-M1-23). */
    /** Validierung (mit Ergebnis), Vier-Augen-Freigabe und Rücknahme einer Konfigurationsrevision (WP-M1-22); Veröffentlichung ist [CONFIG_PUBLISHED], Rücknahme [CONFIG_REVERTED]. */
    const val CONFIG_VALIDATED = "config.revision.validated"
    const val CONFIG_APPROVED = "config.revision.approved"
    const val CONFIG_IMPACT_ANALYZED = "config.impact.analyzed"
    const val CONFIG_IMPACT_BOUND = "config.impact.bound"
    const val PROMISE_SNAPSHOT_BOUND = "promise.snapshot.bound"
    const val PROMISE_SNAPSHOT_CHANGED = "promise.snapshot.changed"
    /** Lesezugriff auf ein geschütztes Fahrgastfeld mit Zweck (WP-M1-31, SEC-005): `<feld>.read`; Details Akteur, Zweck. */
    const val PASSENGER_SUPPORT_NEEDS_READ = "passenger.support_needs.read"
    const val PASSENGER_CONTACT_READ = "passenger.contact.read"
    /** Eingabe mit kartennummernähnlichem Muster abgewiesen (WP-M1-37, SEC-008); nie mit dem Wert. */
    const val SECURITY_CARD_DATA_REJECTED = "security.input.card_data_rejected"
    const val SECURITY_TENANT_MISMATCH = "security.tenant.mismatch"
    const val SECURITY_PAYLOAD_HASH_MISMATCH = "security.event.payload_hash_mismatch"
    const val SECURITY_RECIPIENT_MISMATCH = "security.event.recipient_mismatch"
    const val SECURITY_ACCESS_DENIED = "security.access.denied"
    /** Privilegiertes Konto ohne zweiten Faktor an einer geschützten API abgewiesen (WP-M1-04, LH-6.2-IAM-010). */
    const val SECURITY_MFA_MISSING = "security.auth.mfa_missing"
    /** Sitzungsverwaltung und Kontosperre (WP-M1-10, LH-6.2-IAM-009). */
    const val SECURITY_SESSION_LOGOUT = "security.session.logout"
    const val SECURITY_SESSION_LOGOUT_ALL = "security.session.logout_all"
    const val SECURITY_SESSION_REVOKED = "security.session.revoked"
    const val SECURITY_ACCOUNT_RECOVERY_STARTED = "security.account.recovery_started"
    const val SECURITY_ACCOUNT_RECOVERY_DENIED = "security.account.recovery_denied"
    // WP-M1-12: Anmeldeereignisse des IdP (Keycloak-Ereignisschnittstelle), Sperre und der Auditzugriff selbst.
    const val SECURITY_LOGIN_SUCCEEDED = "security.auth.login_succeeded"
    const val SECURITY_LOGIN_FAILED = "security.auth.login_failed"
    const val SECURITY_LOGOUT = "security.auth.logout"
    /** Zweiter Faktor falsch eingegeben (WP-M1-04), im Unterschied zu [SECURITY_MFA_MISSING] (zweiter Faktor fehlt im Token). */
    const val SECURITY_MFA_FAILED = "security.auth.mfa_failed"
    /** Sitzung oder Konto gesperrt bzw. entsperrt (WP-M1-10). */
    const val SECURITY_ACCOUNT_LOCKED = "security.account.locked"
    const val SECURITY_ACCOUNT_UNLOCKED = "security.account.unlocked"
    /** Fahrgastkonto, Kontaktverifikation, Profil und Einwilligung (WP-M1-28, -29, -30); nie mit Kontaktwert oder Code. */
    const val PASSENGER_REGISTERED = "identity.passenger.registered"
    const val PASSENGER_VERIFICATION_STARTED = "identity.passenger.verification_started"
    const val PASSENGER_CONTACT_VERIFIED = "identity.passenger.contact_verified"
    const val PASSENGER_PROFILE_CHANGED = "identity.passenger.profile_changed"
    const val PASSENGER_SUPPORT_NEEDS_CHANGED = "passenger.support_needs.changed"
    const val CONSENT_RECORDED = "identity.consent.recorded"
    /** Verifikationsversuch falsch bzw. Versuchs-/Startlimit überschritten (WP-M1-28, AK 4). */
    const val SECURITY_CONTACT_VERIFICATION_FAILED = "security.contact.verification_failed"
    const val SECURITY_CONTACT_VERIFICATION_LIMITED = "security.contact.verification_limited"
    /** Eindämmungsmaßnahme im Sicherheitsvorfall ausgeführt: Konto sperren, Mandant sperren, Secret rotieren (WP-M1-41, SEC-011). */
    const val SECURITY_CONTAINMENT_EXECUTED = "security.containment.executed"
    /** Nachrichtenvorlage angelegt (neue Revision) bzw. Standardvorlagen eingespielt (WP-M2-33); Objekt `channel_template:<uuid>` bzw. `tenant:<uuid>`; nie mit Vorlagentext. */
    const val COMMUNICATION_TEMPLATE_CREATED = "communication.template.created"
    /** Dienstversion mit Betriebszeiten veröffentlicht (WP-M2-03); Objekt `service:<uuid>`, Details nennen Verkehrsprodukt, Wirksamkeit und Grund. */
    const val GEO_SERVICE_REVISION_PUBLISHED = "geo.service_revision.published"
    /** Bediengebiet angelegt bzw. neue Revision (WP-M2-04); Objekt `service_area:<Gebietsschlüssel>`, Details nennen Dienst, Revision und Wirksamkeit, nie die Geometrie. */
    const val GEO_SERVICE_AREA_CHANGED = "geo.service_area.changed"
    /** Haltepunkt angelegt, neue Revision oder Deaktivierung (WP-M2-05); Objekt `stop:<stop_id>`, Details nennen Revision, Aktivstatus, Verkehrsprodukte und `approved_by`. */
    const val GEO_STOP_CHANGED = "geo.stop.changed"
    /** Checklistenvorlage (WP-M3-10, LH-6.21-CHK-003): Entwurf angelegt, geändert, neue Revision angelegt, Beispielbild gesetzt, freigegeben; Objekt `checklist_template:<revision>`, Details ohne Personen- oder Bilddaten. */
    const val CHECKLIST_TEMPLATE_CREATED = "fleet.checklist_template.created"
    const val CHECKLIST_TEMPLATE_CHANGED = "fleet.checklist_template.changed"
    const val CHECKLIST_TEMPLATE_REVISION_CREATED = "fleet.checklist_template.revision_created"
    const val CHECKLIST_TEMPLATE_IMAGE_CHANGED = "fleet.checklist_template.image_changed"
    const val CHECKLIST_TEMPLATE_PUBLISHED = "fleet.checklist_template.published"
    /** Mengenversion der erlaubten Zielpunkte eines Dienstes veröffentlicht (WP-M2-06); Objekt `service:<uuid>`, Details nennen Anzahl, Wirksamkeit und Grund. */
    const val GEO_RELATION_RULE_PUBLISHED = "geo.relation_rule.published"
    /** Tarifrevision angelegt, bearbeitet oder geprüft (WP-M2-07); Objekt `tariff_revision:<uuid>`, Details nennen Dienst, Tarifklasse, Wirksamkeit, Zustand und Revisionsnummer, nie Preise im Klartext der Regeln. */
    const val TARIFF_REVISION_CHANGED = "tariff.revision.changed"
    /** Tarifrevision veröffentlicht (WP-M2-07, Vier-Augen); Details nennen Ersteller, Prüfer und Veröffentlichenden. */
    const val TARIFF_REVISION_PUBLISHED = "tariff.revision.published"
    /** Abwesenheit (WP-M4-06) erfasst bzw. zurückgenommen; Objekt `absence:<id>`, `details`: `revision`, `state` (nie Person oder Art, HR-Daten). */
    const val ABSENCE_CREATED = "staff.absence.created"
    const val ABSENCE_WITHDRAWN = "staff.absence.withdrawn"
    /** Schicht eines Dienstplans angelegt bzw. geändert (WP-M4-03); Objekt `shift:<id>`, `details`: `revision`, `state`, `operating_day` (nie Person). */
    const val ROSTER_SHIFT_CREATED = "staff.roster_shift.created"
    const val ROSTER_SHIFT_CHANGED = "staff.roster_shift.changed"
    /** Dienstplan (Depot und Woche) angelegt bzw. neuer Entwurf nach Veröffentlichung eröffnet (WP-M4-04); Objekt `roster:<id>`, `details`: `depot_id`, `week_start`, `revision` (nie Person). */
    const val ROSTER_CREATED = "staff.roster.created"
    const val ROSTER_DRAFT_OPENED = "staff.roster.draft_opened"
    /** Depotrecht im Dienstplan vergeben bzw. entzogen (WP-M4-16); Objekt `roster_depot_grant:<id>`, `details`: `scope_kind`, `depot_id` (nie die Person). */
    const val ROSTER_DEPOT_GRANTED = "staff.roster_depot_grant.granted"
    const val ROSTER_DEPOT_REVOKED = "staff.roster_depot_grant.revoked"
    /** Bearbeitung eines Dienstplans allein aufgrund des ausdrücklichen depotübergreifenden Rechts (WP-M4-16); Objekt `roster:<id>`, `details`: `depot_id`, `operation`. */
    const val ROSTER_CROSS_DEPOT_EDIT = "staff.roster.cross_depot_edit"
    /** Nacherfassung eines Zeitereignisses durch die berechtigte Rolle (WP-M4-08); Objekt `time_event:<id>`, `details`: `kind`, `source` (nie Begründungstext oder Person). */
    const val TIME_EVENT_BACKFILLED = "staff.time_event.backfilled"
    /** Malware in einem hochgeladenen Dokument erkannt (WP-M4-21): bleibt in Quarantäne; Objekt `document:<id>`, `details`: `engine`, `purpose` (nie Dateiinhalt oder -name). */
    const val SECURITY_MALWARE_DETECTED = "security.document.malware_detected"
    /** Beleg angelegt (WP-M4-22): Objekt `expense:<id>`, `details`: `source_channel`, `document_kind` (nie Dateiname, Absender oder Inhalt). */
    const val FINANCE_EXPENSE_CREATED = "finance.expense.created"
    /** Belegfelder korrigiert oder bestätigt, neue Revision (WP-M4-23): Objekt `expense:<id>`, `details`: `revision`, `fields` (nur Feldnamen; Werte stehen in `document_revision`). */
    const val FINANCE_EXPENSE_REVISED = "finance.expense.revised"
    /** Beleg-Einstellungen geändert (WP-M4-22): Objekt `document_settings:<tenant>`, `details`: `revision`. */
    const val FINANCE_DOCUMENT_SETTINGS_CHANGED = "finance.document_settings.changed"
    /** Integritätsverletzung eines Belegoriginals festgestellt (WP-M4-24): Objekt `document:<id>`, `details`: `trigger`, `failure` (nie Hashwerte im Klartext der Meldung, sie stehen im Vorfall). */
    const val SECURITY_DOCUMENT_INTEGRITY_VIOLATED = "security.document.integrity_violated"
    /** Lesezugriff auf das Audit selbst (Auditabfrage mit Zweck). */
    const val AUDIT_READ = "audit.events.read"
    val CATALOG = setOf(FINANCE_EXPENSE_CREATED, FINANCE_EXPENSE_REVISED, FINANCE_DOCUMENT_SETTINGS_CHANGED, SECURITY_DOCUMENT_INTEGRITY_VIOLATED, DEFECT_REPORTED, DEFECT_STATE_CHANGED, DEFECT_AMENDED, DEFECT_HISTORY_READ, INSPECTION_STARTED, INSPECTION_TEMPLATE_UNAVAILABLE, DEFECT_EVIDENCE_RELEASED, DEFECT_EVIDENCE_REJECTED, SECURITY_DEFECT_EVIDENCE_TAMPERED, CHECKLIST_TEMPLATE_CREATED, CHECKLIST_TEMPLATE_CHANGED, CHECKLIST_TEMPLATE_REVISION_CREATED, CHECKLIST_TEMPLATE_IMAGE_CHANGED, CHECKLIST_TEMPLATE_PUBLISHED, SHIFT_CREATED, SHIFT_CHANGED, SHIFT_STARTED, SHIFT_ENDED, POOL_ASSIGNMENT_CREATED, POOL_ASSIGNMENT_ENDED, ROSTER_SHIFT_CREATED, ROSTER_SHIFT_CHANGED, ABSENCE_CREATED, ABSENCE_WITHDRAWN, TIME_EVENT_BACKFILLED, SECURITY_MALWARE_DETECTED, DRIVER_CREATED, DRIVER_CHANGED, QUALIFICATION_CHANGED, VEHICLE_CREATED, VEHICLE_CHANGED, VEHICLE_DEACTIVATED, VEHICLE_CAPACITY_CHANGED, DRIVER_DEVICE_REGISTERED, DRIVER_DEVICE_REVOKED, DRIVER_RELEASE_GRANTED, DRIVER_RELEASE_REVOKED, POLICY_CHECKED, REGION_CHANGED, DEPOT_CHANGED, SERVICE_CHANGED, COST_CENTER_CHANGED, MEMBERSHIP_GRANTED, MEMBERSHIP_REVOKED, TENANT_SWITCHED, ORGANIZATION_CHANGED, TENANT_CREATED, TENANT_STATUS_CHANGED, TENANT_PROFILE_CHANGED, VEHICLE_ROLE_ASSIGNMENT_CHANGED, RETENTION_RULE_CHANGED, RETENTION_RULE_APPROVED, RETENTION_ERASURE_STARTED, RETENTION_ERASURE_FINISHED, RETENTION_ARCHIVE_READ, CONFIG_PUBLISHED, CONFIG_REVERTED,
    val CATALOG = setOf(FINANCE_EXPENSE_CREATED, FINANCE_EXPENSE_REVISED, FINANCE_DOCUMENT_SETTINGS_CHANGED, SECURITY_DOCUMENT_INTEGRITY_VIOLATED, DEFECT_REPORTED, DEFECT_STATE_CHANGED, DEFECT_AMENDED, DEFECT_HISTORY_READ, DEFECT_EVIDENCE_RELEASED, DEFECT_EVIDENCE_REJECTED, SECURITY_DEFECT_EVIDENCE_TAMPERED, DEFECT_CONFIDENTIAL_READ, SECURITY_DEFECT_CONFIDENTIAL_DENIED, CHECKLIST_TEMPLATE_CREATED, CHECKLIST_TEMPLATE_CHANGED, CHECKLIST_TEMPLATE_REVISION_CREATED, CHECKLIST_TEMPLATE_IMAGE_CHANGED, CHECKLIST_TEMPLATE_PUBLISHED, SHIFT_CREATED, SHIFT_CHANGED, SHIFT_STARTED, SHIFT_ENDED, POOL_ASSIGNMENT_CREATED, POOL_ASSIGNMENT_ENDED, ROSTER_SHIFT_CREATED, ROSTER_SHIFT_CHANGED, ABSENCE_CREATED, ABSENCE_WITHDRAWN, TIME_EVENT_BACKFILLED, SECURITY_MALWARE_DETECTED, DRIVER_CREATED, DRIVER_CHANGED, QUALIFICATION_CHANGED, VEHICLE_CREATED, VEHICLE_CHANGED, VEHICLE_DEACTIVATED, VEHICLE_CAPACITY_CHANGED, DRIVER_DEVICE_REGISTERED, DRIVER_DEVICE_REVOKED, DRIVER_RELEASE_GRANTED, DRIVER_RELEASE_REVOKED, POLICY_CHECKED, REGION_CHANGED, DEPOT_CHANGED, SERVICE_CHANGED, COST_CENTER_CHANGED, MEMBERSHIP_GRANTED, MEMBERSHIP_REVOKED, TENANT_SWITCHED, ORGANIZATION_CHANGED, TENANT_CREATED, TENANT_STATUS_CHANGED, TENANT_PROFILE_CHANGED, VEHICLE_ROLE_ASSIGNMENT_CHANGED, RETENTION_RULE_CHANGED, RETENTION_RULE_APPROVED, RETENTION_ERASURE_STARTED, RETENTION_ERASURE_FINISHED, RETENTION_ARCHIVE_READ, CONFIG_PUBLISHED, CONFIG_REVERTED,
    val CATALOG = setOf(FINANCE_EXPENSE_CREATED, FINANCE_EXPENSE_REVISED, FINANCE_DOCUMENT_SETTINGS_CHANGED, SECURITY_DOCUMENT_INTEGRITY_VIOLATED, DEFECT_REPORTED, DEFECT_STATE_CHANGED, DEFECT_AMENDED, DEFECT_HISTORY_READ, DEFECT_EVIDENCE_RELEASED, DEFECT_EVIDENCE_REJECTED, SECURITY_DEFECT_EVIDENCE_TAMPERED, CHECKLIST_TEMPLATE_CREATED, CHECKLIST_TEMPLATE_CHANGED, CHECKLIST_TEMPLATE_REVISION_CREATED, CHECKLIST_TEMPLATE_IMAGE_CHANGED, CHECKLIST_TEMPLATE_PUBLISHED, SHIFT_CREATED, SHIFT_CHANGED, SHIFT_STARTED, SHIFT_ENDED, POOL_ASSIGNMENT_CREATED, POOL_ASSIGNMENT_ENDED, ROSTER_SHIFT_CREATED, ROSTER_SHIFT_CHANGED, ROSTER_CREATED, ROSTER_DRAFT_OPENED, ROSTER_DEPOT_GRANTED, ROSTER_DEPOT_REVOKED, ROSTER_CROSS_DEPOT_EDIT, ABSENCE_CREATED, ABSENCE_WITHDRAWN, TIME_EVENT_BACKFILLED, SECURITY_MALWARE_DETECTED, DRIVER_CREATED, DRIVER_CHANGED, QUALIFICATION_CHANGED, VEHICLE_CREATED, VEHICLE_CHANGED, VEHICLE_DEACTIVATED, VEHICLE_CAPACITY_CHANGED, DRIVER_DEVICE_REGISTERED, DRIVER_DEVICE_REVOKED, DRIVER_RELEASE_GRANTED, DRIVER_RELEASE_REVOKED, POLICY_CHECKED, REGION_CHANGED, DEPOT_CHANGED, SERVICE_CHANGED, COST_CENTER_CHANGED, MEMBERSHIP_GRANTED, MEMBERSHIP_REVOKED, TENANT_SWITCHED, ORGANIZATION_CHANGED, TENANT_CREATED, TENANT_STATUS_CHANGED, TENANT_PROFILE_CHANGED, VEHICLE_ROLE_ASSIGNMENT_CHANGED, RETENTION_RULE_CHANGED, RETENTION_RULE_APPROVED, RETENTION_ERASURE_STARTED, RETENTION_ERASURE_FINISHED, RETENTION_ARCHIVE_READ, CONFIG_PUBLISHED, CONFIG_REVERTED,
        CONFIG_DRAFT_CREATED, CONFIG_DRAFT_CHANGED, CONFIG_VALIDATED, CONFIG_APPROVED, CONFIG_IMPACT_ANALYZED, CONFIG_IMPACT_BOUND, WEBHOOK_SUBSCRIPTION_CREATED, WEBHOOK_SUBSCRIPTION_DEACTIVATED, PASSENGER_SUPPORT_NEEDS_READ, PASSENGER_CONTACT_READ, SECURITY_CARD_DATA_REJECTED,
        PROMISE_SNAPSHOT_BOUND, PROMISE_SNAPSHOT_CHANGED,
        SECURITY_TENANT_MISMATCH, SECURITY_PAYLOAD_HASH_MISMATCH, SECURITY_RECIPIENT_MISMATCH, SECURITY_ACCESS_DENIED, SECURITY_MFA_MISSING,
        SECURITY_SESSION_LOGOUT, SECURITY_SESSION_LOGOUT_ALL, SECURITY_SESSION_REVOKED, SECURITY_ACCOUNT_LOCKED, SECURITY_ACCOUNT_UNLOCKED,
        SECURITY_ACCOUNT_RECOVERY_STARTED, SECURITY_ACCOUNT_RECOVERY_DENIED,
        SECURITY_LOGIN_SUCCEEDED, SECURITY_LOGIN_FAILED, SECURITY_LOGOUT, SECURITY_MFA_FAILED, SECURITY_CONTAINMENT_EXECUTED, AUDIT_READ,
        PASSENGER_REGISTERED, PASSENGER_VERIFICATION_STARTED, PASSENGER_CONTACT_VERIFIED, PASSENGER_PROFILE_CHANGED, PASSENGER_SUPPORT_NEEDS_CHANGED, CONSENT_RECORDED,
        SECURITY_CONTACT_VERIFICATION_FAILED, SECURITY_CONTACT_VERIFICATION_LIMITED, COMMUNICATION_TEMPLATE_CREATED,
        GEO_SERVICE_REVISION_PUBLISHED, GEO_SERVICE_AREA_CHANGED, GEO_STOP_CHANGED, GEO_RELATION_RULE_PUBLISHED, TARIFF_REVISION_CHANGED, TARIFF_REVISION_PUBLISHED)
}

/**
 * Kennzeichnet einen Befehl als auditpflichtig. Architekturregel (ArchitectureRules.auditRequiredCallsAuditPort): eine so markierte
 * Methode muss `AuditPort.record` aufrufen (direkt oder über Methoden derselben Klasse), sonst scheitert der Build.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class AuditRequired
