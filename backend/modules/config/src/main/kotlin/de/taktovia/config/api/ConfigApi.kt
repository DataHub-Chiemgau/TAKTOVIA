package de.taktovia.config.api

import de.taktovia.config.internal.ConfigServicesFactory
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditPort
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
    /** Wirkungsanalyse vor Publikation (WP-M1-23). */
    val impact: ImpactAnalysis

    companion object {
        /** [clock] bestimmt, welche Schema-Version „jetzt“ gilt (Tests setzen eine feste Uhr). */
        fun create(
            ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, clock: Clock = Clock.systemUTC(), impactProviders: List<ImpactProvider> = emptyList(),
        ): ConfigApi = ConfigServicesFactory.create(ctxTx, audit, outbox, clock, impactProviders)
    }
}

/** Ebene, auf der eine Konfigurationsrevision gilt (Schema `ConfigRevision.scope_kind` der API; OP-18). */
enum class ScopeKind { TENANT, REGION, DEPOT, SERVICE }

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
    /** Zeilenversion für `ETag`/`If-Match`; steigt mit jeder Änderung des Entwurfs. */
    val rowVersion: Long,
    val changeLog: ChangeLogEntry,
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

data class RevisionFilter(val scopeKind: ScopeKind? = null, val scopeId: UUID? = null, val state: RevisionState? = null)

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
