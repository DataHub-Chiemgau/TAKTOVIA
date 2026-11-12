package de.taktovia.servicegeo.api

import de.taktovia.config.api.ConfigApi
import de.taktovia.config.api.RevisionValidator
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.authz.ObjectAuthorizer
import de.taktovia.platform.authz.ServiceScopeAuthorizer
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.servicegeo.internal.ServicegeoServicesFactory
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Öffentliche Ports des Moduls `service-geo`: bisher die Organisationsstruktur Region, Betriebshof und Dienstkopf (WP-M1-09). Bediengebiet
 * und Haltepunkte folgen mit M2.
 */
interface ServicegeoApi {
    val structure: OrganizationStructure
    /** Dienstkonfiguration und Angebotsmodelle (WP-M1-25). */
    val configurations: ServiceConfigurations
    /** Dienstversionen mit Betriebszeiten (WP-M2-03). */
    val serviceRevisions: ServiceRevisions
    /** Betriebsstatus eines Dienstes für Fahrgäste und die Angebotsprüfung (WP-M2-03). */
    val availability: ServiceAvailabilities
    /** Bediengebiete als valide Polygone mit Aktivierungszeitraum (WP-M2-04). */
    val areas: ServiceAreas
    /** Haltepunktrevisionen mit Aktivstatus und zulässigen Verkehrsprodukten (WP-M2-05). */
    val stops: Stops
    /** Lesesicht für Fahrgäste auf Bediengebiet und Haltepunkte (WP-M2-40). */
    val passenger: PassengerGeo
    /** Start-/Zielzulässigkeit nach Zonen und erlaubten Zielpunkten (WP-M2-06). */
    val relations: RelationRules

    companion object {
        /** Modellregel-Validator der Dienstkonfiguration (WP-M1-25) für `ConfigApi.create(revisionValidators = …)`; ohne Abhängigkeit von `ConfigApi`, damit es keinen Verdrahtungszyklus gibt. */
        fun offerModelValidator(): RevisionValidator = de.taktovia.servicegeo.internal.ServicegeoServicesFactory.offerModelValidator()

        /** ImpactProvider „Bediengebiet“ (WP-M2-04) für `ConfigApi.create(impactProviders = …)`: nennt bestätigte Buchungen außerhalb des neuen Gebiets. */
        fun areaImpactProvider(): de.taktovia.config.api.ImpactProvider = de.taktovia.servicegeo.internal.ServicegeoServicesFactory.areaImpactProvider()

        /** [config] liefert die erste Konfigurationsrevision eines neuen Dienstes (OP-35) in derselben Transaktion. */
        fun create(ctxTx: ContextTransactions, audit: AuditPort, config: ConfigApi, clock: Clock = Clock.systemUTC(), authorizer: ObjectAuthorizer = ServiceScopeAuthorizer,
            outbox: Outbox = Outbox()): ServicegeoApi =
            ServicegeoServicesFactory.create(ctxTx, audit, config, clock, authorizer, outbox)
    }
}

data class RegionView(val id: UUID, val name: String, val revision: Long, val createdAt: Instant)
data class DepotView(val id: UUID, val regionId: UUID, val name: String, val revision: Long, val createdAt: Instant)

/** Dienstkopf; [regionId] ist die (optionale) Zuordnung Dienst -> Region, [configRevisionId] die erste Konfigurationsrevision des Dienstes. */
data class ServiceView(
    val id: UUID, val operatorId: UUID, val regionId: UUID?, val name: String, val timezone: String, val configRevisionId: UUID,
    val revision: Long, val createdAt: Instant,
)

data class CreateService(val operatorId: UUID, val name: String, val timezone: String, val regionId: UUID? = null)
/** Der Betreiber (`operator_id`) ist nach dem Anlegen unveränderlich. */
data class UpdateService(val name: String, val timezone: String, val regionId: UUID? = null)

/**
 * Verwaltung der Organisationsstruktur des Mandanten im Kontext (WP-M1-09, LH-I.4-MUL-002): Fachzeile und genau ein Auditeintrag in **einer**
 * Transaktion. Fremde Mandanten sind weder lesbar noch als Verweis verwendbar (404); veraltete Revision 412; ungültige Eingaben 422 mit
 * Feldpointern. Änderungen ohne Wirkung erhöhen die Revision nicht und erzeugen kein Audit. Die Berechtigung (Funktionsscope) entscheidet die
 * Anwendung vor dem Aufruf; die Strukturverwaltung ist mandantenweit und hängt nicht am Dienstscope der Mitgliedschaft.
 */
interface OrganizationStructure {
    fun createRegion(ctx: RequestContext, name: String): RegionView
    fun updateRegion(ctx: RequestContext, id: UUID, expectedRevision: Long, name: String): RegionView
    fun listRegions(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<RegionView>

    fun createDepot(ctx: RequestContext, regionId: UUID, name: String): DepotView
    fun updateDepot(ctx: RequestContext, id: UUID, expectedRevision: Long, regionId: UUID, name: String): DepotView
    fun listDepots(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<DepotView>

    /** Legt den Dienst samt erster Konfigurationsrevision (Entwurf) an: Anlage scheitert als Ganzes (keine halb angelegten Dienste). */
    fun createService(ctx: RequestContext, cmd: CreateService): ServiceView
    fun updateService(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: UpdateService): ServiceView
    /**
     * Dienste des Mandanten (Dienstbezug, WP-M1-08): verwaltende Rollen (`services:write`) sehen alle, alle anderen nur die Dienste ihres Dienstscopes; ein
     * Filterwunsch [requested] schränkt nur weiter ein (fremde Dienste: leere Liste).
     */
    fun listServices(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?, requested: Set<UUID>? = null): List<ServiceView>
}
