package de.taktovia.config.api

import de.taktovia.config.internal.ConfigServicesFactory
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.authz.ObjectAuthorizer
import de.taktovia.platform.authz.ServiceScopeAuthorizer
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.problem.Violation
import org.springframework.jdbc.core.JdbcOperations
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Öffentliche Ports des Moduls `config` (WP-M1-19, WP-M1-20): Parameterkatalog mit Systemgrenzen ([parameters]) und
 * Konfigurationsrevisionen mit Entwurf, Historie und Rekonstruktion ([revisions]). Präzedenz und wirksame Konfiguration (WP-M1-21) sowie
 * Prüfung, Freigabe und Publikation (WP-M1-22) bauen auf diesen Ports auf.
 */
interface ConfigApi {
    /** Unternehmensprofil des Mandanten (WP-M1-24): Kontakt, Abrechnung, Betriebsart, Rechtsinformationen, Standardwährung. */
    val companyProfile: CompanyProfileService
    val parameters: ParameterRegistry
    val revisions: ConfigRevisions
    /** Präzedenzkette und wirksame Konfiguration (WP-M1-21). */
    val effective: EffectiveConfig
    /** Wirkungsanalyse vor Publikation (WP-M1-23). */
    val impact: ImpactAnalysis
    /** Validierung, Vier-Augen-Freigabe, Publikation und Rücknahme (WP-M1-22). */
    val publication: ConfigPublication

    companion object {
        /**
         * [clock] bestimmt, welche Schema-Version „jetzt“ gilt und ab wann eine Publikation wirken darf (Tests setzen eine feste Uhr). [revisionValidators] sind die
         * fachlichen Prüfregeln der Module (z. B. Modellregeln der Dienstkonfiguration, WP-M1-25), die bei der Validierung **und** noch einmal bei der Publikation laufen.
         */
        fun create(
            ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, clock: Clock = Clock.systemUTC(), authorizer: ObjectAuthorizer = ServiceScopeAuthorizer,
            impactProviders: List<ImpactProvider> = emptyList(), revisionValidators: List<RevisionValidator> = emptyList(),
        ): ConfigApi = ConfigServicesFactory.create(ctxTx, audit, outbox, clock, authorizer, impactProviders, revisionValidators)
    }
}

/** Ebene, auf der eine Konfigurationsrevision gilt (Schema `ConfigRevision.scope_kind` der API; OP-18). */
enum class ScopeKind { TENANT, REGION, DEPOT, SERVICE, VEHICLE }

/** Lebenszyklus einer Revision (Entwurf → geprüft → freigegeben → publiziert); Publikation liefert WP-M1-22. */
enum class RevisionState { DRAFT, VALIDATED, APPROVED, PUBLISHED }

/**
 * Ein Eintrag des Parameterkatalogs (`policy_schema`): technischer Name, Datentyp, Einheit, gültiger Bereich, Beschreibung. [minimum] und
 * [maximum] sind zugleich die **Systemgrenzen**: kein Mandant kann sie überschreiben (LH-I.7-CFG-004). [securityClass] `SAFETY_INVARIANT`
 * kennzeichnet nicht abschaltbare Sicherheitsparameter; [limitSource] `PLANUNGSANNAHME` kennzeichnet Grenzen, die der Katalog nicht beziffert (OP-03).
 */
data class ParameterDefinition(
    val name: String,
    val type: String,
    val unit: String,
    val minimum: BigDecimal?,
    val maximum: BigDecimal?,
    val default: Any?,
    val allowedLevels: Set<ScopeKind>,
    val securityClass: String,
    val limitSource: String,
    val description: String,
    /** Das vollständige JSON-Schema (Draft 2020-12) des Parameters. */
    val schema: Map<String, Any?>,
    /**
     * Kennzeichen „wichtig/impact-pflichtig“ (WP-M1-23): eine Änderung kann bestehende Zusagen berühren und löst die Wirkungsanalyse aus.
     * Die Liste ist vorläufig (OP-19); ein späteres Schema darf sie als `x-impact-required` im JSON-Schema führen (wird dann bevorzugt gelesen).
     */
    val impactRequired: Boolean = false,
    /** Hinweistext zur Prüfung, z. B. „bei Änderung bereits bestätigte Buchungen prüfen“. */
    val impactHint: String? = null,
)

data class ParameterCatalog(val schemaVersion: String, val validFrom: Instant, val parameters: List<ParameterDefinition>)

sealed interface ValidationOutcome {
    /** Gültig; [values] sind normalisiert (Einheitenhülle `{value, unit}` aufgelöst). */
    data class Valid(val schemaVersion: String, val values: Map<String, Any?>) : ValidationOutcome
    data class Invalid(val schemaVersion: String, val violations: List<Violation>) : ValidationOutcome
}

interface ParameterRegistry {
    /** Katalog der aktuell gültigen Schema-Version; für jeden Mandanten lesbar. Der Katalog ist nur per Migration änderbar (es gibt keinen Schreibweg). */
    fun catalog(ctx: RequestContext): ParameterCatalog

    /**
     * Prüft Werte (Parametername → Wert) einer Revision der Ebene [scope] gegen die Schema-Version [schemaVersion] (Standard: die jetzt gültige):
     * bekannter Name auf zulässiger Ebene, Datentyp, Einheit, Bereich bzw. Systemgrenze, Sicherheitsinvarianten, Format. Ein Wert darf als
     * Hülle `{"value": 5, "unit": "MINUTES"}` kommen; die Einheit muss dann zum Parameter passen. Es werden alle Verletzungen gesammelt.
     */
    fun validate(ctx: RequestContext, scope: ScopeKind, values: Map<String, Any?>, schemaVersion: String? = null): ValidationOutcome
}

/** Eine Änderung eines Werts gegenüber der Vorgängerrevision (JSON-Pointer, Altwert, Neuwert). */
data class ConfigChange(val pointer: String, val kind: ChangeKind, val oldValue: Any?, val newValue: Any?)
enum class ChangeKind { ADDED, CHANGED, REMOVED }

/** Änderungsprotokoll je Revision (LH-I.7-CFG-003): Benutzer, Zeitpunkt, Grund, Gültigkeitsbeginn und Diff gegen die Vorgängerrevision. */
data class ChangeLogEntry(
    val changedBy: String,
    val changedAt: Instant,
    val reason: String,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
    /** Vorgängerrevision: die höchste publizierte Revision desselben Scopes mit kleinerer Nummer; ohne sie ist jeder Wert „hinzugefügt“. */
    val predecessorId: UUID?,
    val predecessorRevision: Long?,
    val changes: List<ConfigChange>,
)

data class ConfigRevisionView(
    val id: UUID,
    val scopeKind: ScopeKind,
    val scopeId: UUID,
    /** Nummer der Revision im Scope (1, 2, 3 …). */
    val revision: Long,
    val state: RevisionState,
    val schemaVersion: String,
    val values: Map<String, Any?>,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
    val createdBy: String,
    val createdAt: Instant,
    val approvedBy: String?,
    val publishedAt: Instant?,
    val reason: String,
    /** Zeilenversion für `ETag`/`If-Match`; steigt mit jeder Änderung des Entwurfs (Validierung, Freigabe, Publikation, Schließen des Intervalls). */
    val rowVersion: Long,
    val changeLog: ChangeLogEntry,
    /** Prüfende Person und Zeitpunkt der erfolgreichen Validierung (WP-M1-22); `null` solange nicht validiert. */
    val validatedBy: String? = null,
    val validatedAt: Instant? = null,
    val approvedAt: Instant? = null,
    val publishedBy: String? = null,
    /** Die fehlerhafte publizierte Revision, die diese Revision zurücknimmt (Rollback als neue Revision); sonst `null`. */
    val rolledBackFrom: UUID? = null,
)

/** Entwurf anlegen. Ersteller ist immer der Akteur des Kontexts, nie ein Wert der Anfrage. */
data class CreateDraft(
    val scopeKind: ScopeKind,
    val scopeId: UUID,
    val schemaVersion: String,
    val values: Map<String, Any?>,
    val effectiveFrom: Instant,
    val effectiveTo: Instant? = null,
    /** Pflicht (3 bis 1000 Zeichen); `null` ergibt 422. */
    val reason: String?,
)

/** Entwurf ändern (JSON-Merge auf oberster Ebene): nicht gesetzte Felder bleiben, `values` ersetzt die Werte vollständig. */
data class UpdateDraft(
    val values: Map<String, Any?>? = null,
    val effectiveFrom: Instant? = null,
    val effectiveTo: Instant? = null,
    /** `true`: [effectiveTo] wurde gesendet (auch `null` = Ende löschen). */
    val effectiveToSet: Boolean = false,
    val reason: String? = null,
)

/** [superseded] `true`: nur abgelöste Revisionen (publiziert, Intervall durch einen Nachfolger geschlossen und beendet); der Zustand „abgelöst“ wird nicht gespeichert (V1200). */
data class RevisionFilter(val scopeKind: ScopeKind? = null, val scopeId: UUID? = null, val state: RevisionState? = null, val superseded: Boolean = false)

/** Ergebnis der erneuten Prüfung eines Entwurfs gegen die zum Prüfzeitpunkt gültige Schema-Version. */
data class RevalidationReport(val revisionId: UUID, val checkedAgainstVersion: String, val valid: Boolean, val violations: List<Violation>)

interface ConfigRevisions {
    /**
     * Legt einen Entwurf an: Pflichtangaben und Werte werden **vor** dem Schreiben geprüft (422 mit Feldverletzungen, keine Zeile). Revisionsnummer
     * je Scope fortlaufend; legen zwei Anfragen gleichzeitig dieselbe Nummer an, gelingt genau eine, die andere erhält 409 `CONFLICT`. Zeile, Audit
     * `config.revision.created` und Outbox-Ereignis `config.revision_created` entstehen in einer Transaktion. TENANT-Scope: `scopeId` muss der Mandant des Kontexts sein (sonst 404).
     */
    fun createDraft(ctx: RequestContext, cmd: CreateDraft): ConfigRevisionView

    /** Ändert einen Entwurf (`If-Match` = [expectedRowVersion]): 404 unbekannt oder fremd, 412 veraltet, 409 wenn kein Entwurf mehr; Audit `config.revision.changed`. */
    fun updateDraft(ctx: RequestContext, id: UUID, expectedRowVersion: Long, cmd: UpdateDraft): ConfigRevisionView

    /** 404, wenn die Revision unbekannt ist oder zu einem anderen Mandanten gehört. */
    fun get(ctx: RequestContext, id: UUID): ConfigRevisionView

    /** Sortiert nach (`createdAt`, `id`); höchstens `limit + 1` Zeilen (der Aufrufer schneidet). */
    fun list(ctx: RequestContext, filter: RevisionFilter, limit: Int, after: Pair<Instant, UUID>?): List<ConfigRevisionView>

    /**
     * Die zum Zeitpunkt [at] wirksame **publizierte** Revision des Scopes samt Revisions-ID (Rekonstruktion für einen historischen Fall):
     * Beginn <= [at] und kein Ende oder Ende > [at]; bei mehreren gilt die mit dem spätesten Beginn, dann der höchsten Nummer. `null`, wenn keine wirkte.
     */
    fun asOf(ctx: RequestContext, scopeKind: ScopeKind, scopeId: UUID, at: Instant): ConfigRevisionView?

    /**
     * Prüft die Werte einer Revision erneut gegen die **jetzt** gültige Schema-Version (nicht gegen die beim Anlegen gespeicherte): so wird ein
     * Entwurf bei der Publikation (WP-M1-22) gegen eine zwischenzeitlich angehobene Version geprüft.
     */
    fun revalidate(ctx: RequestContext, id: UUID): RevalidationReport

    /**
     * Erste Konfigurationsrevision eines **neuen Dienstes** in der laufenden Transaktion des Aufrufers (WP-M1-09, OP-35): `service.config_revision_id`
     * ist Pflichtfeld, die Dienstanlage erzeugt deshalb zugleich den leeren Entwurf (Revision 1, Scope `SERVICE`, `scope_id` = [serviceId], Werte `{}`)
     * mit Audit und Ereignis. Eine leere Dienstkonfiguration ist nur als Entwurf zulässig; die Vollständigkeit (`service_config`) verlangt die Publikation.
     * Mandant = Tenantkontext der Transaktion. Liefert die Revisions-ID.
     */
    fun seedServiceHead(jdbc: JdbcOperations, actor: ActorRef, correlationId: UUID, serviceId: UUID, effectiveFrom: Instant): UUID
}

/**
 * Felder des Unternehmensprofils (Planungsannahme PA-11, OP-27): Pflichtfelder sind [legalName], [contactEmail] und [defaultCurrency];
 * die Währung muss im Katalog `currency` stehen. [operatingMode] (Betriebsart) ist ein Code im Muster `^[A-Z][A-Z0-9_]{1,63}$`; ein
 * fester Katalog ist nicht festgelegt (OP-27). Abrechnungsangaben sind nur Adresse und E-Mail; Kontenrahmen und Rechnungsstellung gehören nach M4 (D-06).
 */
data class CompanyProfileInput(
    val legalName: String, val contactEmail: String, val defaultCurrency: String,
    val address: String? = null, val contactPhone: String? = null, val billingAddress: String? = null, val billingEmail: String? = null,
    val operatingMode: String? = null, val legalNotice: String? = null,
)

data class CompanyProfileView(val input: CompanyProfileInput, val revision: Long)

interface CompanyProfileService {
    /** Aktuelles Profil des Mandanten im Kontext; 404, wenn noch keines angelegt ist. */
    fun get(ctx: RequestContext): CompanyProfileView

    /**
     * Ändert das Profil (vollständiger Ersatz) mit der gelesenen Revision (`If-Match`): 412 bei veralteter Revision (auch bei zwei gleichzeitigen
     * Änderungen genau eine Gewinnerin), 404 bei unbekannter Revision ohne Profil, 422 mit Feldfehler bei ungültiger Währung. Ist `expectedRevision` 0
     * und noch kein Profil vorhanden, entsteht Revision 1. Je Änderung genau ein Auditeintrag und ein Ereignis `tenant.profile_changed` in derselben
     * Transaktion; eine Änderung ohne Wirkung erzeugt weder Revision noch Audit noch Ereignis. Das Profil hängt nur am Mandanten des Kontexts: ohne
     * Mitwirkung einer Plattformbetreiberrolle.
     */
    fun update(ctx: RequestContext, expectedRevision: Long, input: CompanyProfileInput): CompanyProfileView

    /** Erste Profilrevision in der laufenden Transaktion des Aufrufers (Erstausstattung bei der Mandantenanlage, WP-M1-05); Mandant = Tenantkontext der Transaktion. */
    fun seed(jdbc: JdbcOperations, actor: ActorRef, correlationId: UUID, input: CompanyProfileInput): CompanyProfileView
}


/**
 * Ebenen der Präzedenzkette (Kap. 01 §1.6, LH-I.7-CFG-005), aufsteigend nach Spezifität: [GLOBAL] globale Vorgabe und Sicherheitsgrenze (Parameterkatalog,
 * `policy_schema`), [TENANT] freigegebener Mandantenrahmen, [REGION], [SERVICE], [VEHICLE]. Die Stufe "konkretes Produkt" fehlt bis M2 (kein Produktobjekt);
 * `DEPOT` ist ein Scope für Revisionen, aber keine Ebene der Präzedenz (der Betriebshof ist kein Geltungsbereich für Betriebsregeln, OP-22).
 */
enum class ConfigLevel { GLOBAL, TENANT, REGION, SERVICE, VEHICLE }

/**
 * Anfrage der wirksamen Konfiguration: [parameter] `null` = alle Parameter des Katalogs. Der Kontext besteht aus [regionId], [serviceId] und [vehicleId];
 * ohne Region, aber mit Dienst gilt die Region des Dienstes. [at] `null` = jetzt.
 */
data class EffectiveRequest(
    val parameter: String? = null, val regionId: UUID? = null, val serviceId: UUID? = null, val vehicleId: UUID? = null, val at: Instant? = null,
    /** Teilmenge des Katalogs (Regelprüfung): ohne [parameter] und ohne [parameters] werden alle Parameter aufgelöst. */
    val parameters: Set<String>? = null,
)

enum class EffectiveStatus { EFFECTIVE, NO_VALUE }

/** Was auf einer Ebene mit dem Wert geschah: [APPLIED] gilt, [OVERRIDDEN] wird von einer spezifischeren Ebene überstimmt, [SKIPPED_LIMIT] verletzt die globale Sicherheitsgrenze, [SKIPPED_NOT_ALLOWED] ist auf dieser Ebene nicht zulässig bzw. nicht freigegeben, [NOT_SET] hat keinen Wert, [NOT_IN_CONTEXT] gehört nicht zum Kontext der Anfrage. */
enum class LevelOutcome { APPLIED, OVERRIDDEN, SKIPPED_LIMIT, SKIPPED_NOT_ALLOWED, NOT_SET, NOT_IN_CONTEXT }

/** Vorgeschlagener (noch nicht publizierter) Wert: der jüngste Entwurf, geprüft oder freigegebene Stand dieses Scopes, der den Parameter nennt. */
data class Proposal(val revisionId: UUID, val state: RevisionState, val value: Any?)

data class PrecedenceEntry(
    val level: ConfigLevel, val outcome: LevelOutcome, val scopeId: UUID?, val value: Any?, val revisionId: UUID?, val revision: Long?,
    /** Verletzungscode (`ABOVE_MAXIMUM`, `UNKNOWN_FIELD`, …), wenn der Wert übersprungen wurde. */
    val violation: String?, val proposal: Proposal?,
)

/** Angewandte Vorrangregel: stabiler [code] und Klartext. */
data class PrecedenceRule(val code: String, val description: String)

/** Wirksamer Wert eines Parameters mit Einheit, Herkunft (Ebene, Revision, Wirksamkeit), Vorrangregel und der Reihenfolge aller Ebenen (spezifischste zuerst). */
data class EffectiveParameter(
    val parameter: String, val unit: String, val status: EffectiveStatus, val value: Any?, val level: ConfigLevel?, val revisionId: UUID?, val revision: Long?,
    val scopeId: UUID?, val effectiveFrom: Instant?, val effectiveTo: Instant?, val schemaVersion: String, val rule: PrecedenceRule, val precedence: List<PrecedenceEntry>,
)

data class EffectiveConfigView(val asOf: Instant, val schemaVersion: String, val items: List<EffectiveParameter>)

/**
 * Auflösung der Präzedenzkette (WP-M1-21, LH-I.7-CFG-005): für Parameter und Kontext zum Zeitpunkt T der wirksame Wert. **Regel:** Die spezifischste Ebene mit einem
 * veröffentlichten, zu T wirksamen und zulässigen Wert gilt (Fahrzeug vor Dienst vor Region vor Mandant vor globaler Vorgabe). Zulässig heißt: der Wert besteht die Prüfung
 * gegen die zu T gültige Schema-Version (Systemgrenzen und Sicherheitsinvarianten sind die **globale Sicherheitsgrenze**; ein Wert, der sie verletzt, wird nie als
 * wirksam geliefert, die nächste Ebene gilt) und die Ebene ist für den Parameter zulässig (Fahrzeug nur bei freigegebenen Parametern). Jede Antwort stammt aus genau einem
 * konsistenten Lesestand der Revisionen. Der Zwischenspeicher trägt die beteiligten Revisionen und die Schema-Version im Schlüssel und liefert nach einer Publikation nie den abgelösten Wert.
 */
interface EffectiveConfig {
    /**
     * Für die API: eigene lesende Transaktion im Kontext. Region, Dienst und Fahrzeug des Kontexts müssen zum Mandanten gehören (sonst 404), ein Parameter muss im Katalog stehen
     * (404), Dienst und Region müssen zusammenpassen (422). Ein Dienst außerhalb des Dienstscopes ist nicht sichtbar (404) für Rollen ohne `config:write`.
     */
    fun resolve(ctx: RequestContext, request: EffectiveRequest): EffectiveConfigView

    /**
     * Für Befehle (Regelprüfung, WP-M1-26) in der **laufenden Fachtransaktion** des Aufrufers: dieselbe Auflösung, aber ohne Dienstscope-Prüfung (der Befehl autorisiert selbst);
     * Mandantengrenze und Kontextprüfung bleiben.
     */
    fun resolveInTransaction(jdbc: JdbcOperations, ctx: RequestContext, request: EffectiveRequest): EffectiveConfigView
}


/**
 * Fachliche Prüfregel eines Moduls für Konfigurationsrevisionen (WP-M1-22, WP-M1-25). Läuft bei der Validierung **und** noch einmal in der Transaktion der
 * Publikation (der Zustand kann sich zwischenzeitlich geändert haben, z. B. die Schema-Version). Gibt es Verletzungen, wird nicht validiert bzw. nicht publiziert.
 * Die Regel liest nur (der Aufrufer hält die Transaktion mit Mandantenkontext) und meldet Feldverletzungen mit JSON-Pointer relativ zur Revision (`/values/...`).
 */
interface RevisionValidator {
    /** Stabiler Name (nur für Logs). */
    val name: String
    fun check(j: JdbcOperations, ctx: RequestContext, revision: ConfigRevisionView): List<Violation>
}

/** Ergebnis der Validierung: bei [violations] leer ist die Revision jetzt `VALIDATED`, sonst unverändert (`DRAFT`). [auditEventId] ist der Auditeintrag der Prüfung. */
data class ValidationResult(val revision: ConfigRevisionView, val violations: List<Violation>, val auditEventId: java.util.UUID) {
    val valid: Boolean get() = violations.isEmpty()
}

/**
 * Publikationsbefehl: [effectiveFrom] muss dem bei der Freigabe gesehenen Wirksamkeitsbeginn der Revision entsprechen (die Freigabe gilt für genau diesen Stand
 * einschließlich Datum; eine Datumsänderung ist eine Änderung des Entwurfs und braucht neue Prüfung und Freigabe), [reason] ist Pflicht (3 bis 1000 Zeichen),
 * [impactReportId] der Wirkungsbericht zum unveränderten Revisionsstand (WP-M1-23).
 */
data class PublishCommand(val effectiveFrom: Instant, val reason: String?, val impactReportId: UUID?)

/** Ergebnis der Publikation: die publizierte Revision und der Auditeintrag der Publikation (`audit_event_id` der API-Antwort). */
data class PublicationResult(val revision: ConfigRevisionView, val auditEventId: UUID, val closedPredecessorId: UUID?)

/**
 * Rücknahme einer fehlerhaften publizierten Revision als **neue** Revision mit den Werten einer früheren: [targetRevisionId] `null` = die Vorgängerrevision der
 * fehlerhaften (höchste publizierte Revision desselben Scopes mit kleinerer Nummer). [effectiveFrom] ist der gewünschte Wirksamkeitsbeginn der Rücknahme.
 */
data class RollbackCommand(val effectiveFrom: Instant, val reason: String?, val targetRevisionId: UUID? = null)

/**
 * Lebenszyklus nach dem Entwurf (WP-M1-22, LH-I.7-CFG-006): Entwurf → geprüft (`validate`) → freigegeben (`approve`, durch eine **zweite** Person) → publiziert
 * (`publish`, zum Wirksamkeitsbeginn) → abgelöst (abgeleitet: durch Nachfolger geschlossen). Eine Änderung wirkt erst nach Publikation; ein Rollback ist eine neue Revision
 * mit früheren Werten, die denselben Weg geht (kein Umgehen der Vier-Augen-Regel), und verändert weder die fehlerhafte Revision noch bestehende Zusagen
 * (gebundene Revisionsreferenzen zeigen weiter ihre Ursprungsversion). Alle Befehle: 404 unbekannt/fremd, 412 veraltete Zeilenversion, 409 unzulässiger Zustand.
 */
interface ConfigPublication {
    /** Funktionsscope `config:write`. Prüft gegen die jetzt gültige Schema-Version (Systemgrenzen, Sicherheitsinvarianten) und die [RevisionValidator]; Audit in jedem Fall. */
    fun validate(ctx: RequestContext, id: UUID, expectedRowVersion: Long): ValidationResult

    /** Funktionsscope `config:publish`. Nur `VALIDATED`; Prüfer ≠ Ersteller (403), und nur ein Mensch. Audit `config.revision.approved`. */
    fun approve(ctx: RequestContext, id: UUID, expectedRowVersion: Long): ConfigRevisionView

    /**
     * Funktionsscope `config:publish`. Nur `APPROVED`; erneute Prüfung (422), Wirkungsbericht (422/409), Wirksamkeitsbeginn nicht rückwirkend (422), Scope-Sperre und
     * Intervallprüfung (409 bei Überlappung mit einer später beginnenden publizierten Revision); schließt das Intervall der Vorgängerrevision. Zeile, Audit
     * `config.revision.published` und Ereignis `config.revision_published` (bei einer Rücknahme zusätzlich `config.revision_rolled_back`) in einer Transaktion.
     */
    fun publish(ctx: RequestContext, id: UUID, expectedRowVersion: Long, cmd: PublishCommand): PublicationResult

    /** Funktionsscope `config:publish`. [id] ist die fehlerhafte **publizierte** Revision; erzeugt die neue Revision als Entwurf (Audit `config.revision.reverted`). */
    fun rollback(ctx: RequestContext, id: UUID, expectedRowVersion: Long, cmd: RollbackCommand): ConfigRevisionView
}
