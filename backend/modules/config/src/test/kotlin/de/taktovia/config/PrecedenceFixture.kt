package de.taktovia.config

import de.taktovia.config.api.ConfigRevisionView
import de.taktovia.config.api.CreateDraft
import de.taktovia.config.api.ScopeKind
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import java.time.Instant
import java.util.UUID

/**
 * Testaufbau der Präzedenz (WP-M1-21) auf [ConfigFixture]: Gebiete (Region, Dienst, Fahrzeug) legt die Administration an (Stammdaten der Module `service-geo` und
 * `fleet-safety`, ohne vollständige Fremdschlüsselkette: `session_replication_role = replica`), Revisionen entstehen über die Dienste des Moduls und werden wie in WP-M1-22
 * veröffentlicht.
 */
class PrecedenceFixture(val fx: ConfigFixture) {
    fun region(tenant: TenantId, name: String = "Region"): UUID {
        val id = UUID.randomUUID()
        fx.adminSession { c ->
            c.createStatement().use { it.execute("SET session_replication_role = replica") }
            c.prepareStatement("INSERT INTO taktovia.region(tenant_id,id,name,created_by) VALUES (?,?,?,'user:test')").use { it.setObject(1, tenant.value); it.setObject(2, id); it.setString(3, name); it.executeUpdate() }
        }
        return id
    }

    fun service(tenant: TenantId, region: UUID?, name: String = "Dienst"): UUID {
        val id = UUID.randomUUID()
        fx.adminSession { c ->
            c.createStatement().use { it.execute("SET session_replication_role = replica") }
            c.prepareStatement("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,region_id,created_by) VALUES (?,?,?,?,'Europe/Berlin',?,?,'user:test')").use {
                it.setObject(1, tenant.value); it.setObject(2, id); it.setObject(3, UUID.randomUUID()); it.setString(4, name); it.setObject(5, UUID.randomUUID()); it.setObject(6, region); it.executeUpdate()
            }
        }
        return id
    }

    fun vehicle(tenant: TenantId): UUID {
        val id = UUID.randomUUID()
        fx.adminSession { c -> c.prepareStatement("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'x','{}')").use { it.setObject(1, tenant.value); it.setObject(2, id); it.executeUpdate() } }
        return id
    }

    fun serviceConfig(operator: UUID = UUID.randomUUID()) = mapOf("service_config" to mapOf(
        "service_area_id" to UUID.randomUUID().toString(), "traffic_type" to "AREA_ON_DEMAND", "operator_id" to operator.toString(),
        "operating_hours" to listOf(mapOf("weekdays" to listOf("MON"), "from" to "06:00", "to" to "22:00")), "tariff_id" to UUID.randomUUID().toString()))

    /** Entwurf anlegen und veröffentlichen (Testaufbau; Vier-Augen-Freigabe und Publikation liefert WP-M1-22). */
    fun publish(ctx: RequestContext, kind: ScopeKind, scope: UUID, values: Map<String, Any?>, from: String = "2026-01-01T00:00:00Z", to: String? = null): ConfigRevisionView {
        val all = if (kind == ScopeKind.SERVICE && "service_config" !in values) values + serviceConfig() else values
        val draft = fx.config.revisions.createDraft(ctx, CreateDraft(kind, scope, "1", all, Instant.parse(from), to?.let(Instant::parse), "Testaufbau"))
        fx.publish(ctx.tenant, draft.id)
        return fx.config.revisions.get(ctx, draft.id)
    }

    fun draft(ctx: RequestContext, kind: ScopeKind, scope: UUID, values: Map<String, Any?>): ConfigRevisionView =
        fx.config.revisions.createDraft(ctx, CreateDraft(kind, scope, "1", values, Instant.parse("2027-01-01T00:00:00Z"), null, "Vorschlag"))
}
