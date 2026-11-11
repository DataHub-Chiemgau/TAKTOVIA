package de.taktovia.servicegeo.api

import de.taktovia.config.api.ConfigApi
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
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

    companion object {
        /** [config] liefert die erste Konfigurationsrevision eines neuen Dienstes (OP-35) in derselben Transaktion. */
        fun create(ctxTx: ContextTransactions, audit: AuditPort, config: ConfigApi, clock: Clock = Clock.systemUTC()): ServicegeoApi =
            ServicegeoServicesFactory.create(ctxTx, audit, config, clock)
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
    /** [visible] = `null`: alle Dienste des Mandanten; sonst nur die genannten (Dienstscope, WP-M1-08). */
    fun listServices(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?, visible: Set<UUID>? = null): List<ServiceView>
}
