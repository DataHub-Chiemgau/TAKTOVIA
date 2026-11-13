package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.ReplacementSearchPort
import de.taktovia.fleetsafety.api.ReplacementSearchResult
import de.taktovia.fleetsafety.api.WorkflowEvent
import de.taktovia.fleetsafety.api.WorkflowEventKinds
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.operation.OperationRunner
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.outbox.InboxProcessor
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.SnapshotFetcher
import de.taktovia.platform.outbox.SnapshotNotYetAvailableException
import de.taktovia.platform.tenant.TenantId
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** Testhilfen des Incident-Workflows und des Ablaufprotokolls (WP-M3-53/-55), eigene Klasse je Cluster: Mandant, Fahrzeug, Ereignisse, steuerbarer Suchport. */
class IncidentWorld(val fx: FleetFixture) {
    @Volatile var searchResult: ReplacementSearchResult = ReplacementSearchResult.Proposed(2)
    val fleet: FleetsafetyApi = FleetsafetyApi.create(fx.ctxTx, JdbcAuditPort(), Outbox(), replacementSearch = ReplacementSearchPort { _, _, _ -> searchResult })
    val w = fx.world(1)
    val tenant: TenantId = w.tenant
    val vehicle: UUID = w.vehicle
    val workflow = fleet.incidentWorkflow
    val security = SecurityEventPort(JdbcAuditPort())
    private val mapper = JsonMapper.builder().build()

    fun ctx(vararg roles: String, services: Set<UUID> = emptySet(), tenant: TenantId = this.tenant, scopes: Set<String> = setOf("operator:read", "operator:write")) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/fleet", UUID.randomUUID().toString(), "sid-" + UUID.randomUUID(), true), tenant, roles.toSet(), scopes, services, UUID.randomUUID())

    fun event(kind: String = WorkflowEventKinds.VEHICLE_BLOCKED, vehicle: UUID? = this.vehicle, id: UUID = UUID.randomUUID(), subject: String? = null, defect: UUID? = null) =
        WorkflowEvent(id, kind, vehicle, Instant.now(), subject, defect, UUID.randomUUID())

    /** Echte Schadenakte des Mandanten (Meldung durch die Disposition), damit die Verknüpfung am Incident nachweisbar ist. */
    fun defect(vehicle: UUID = this.vehicle): UUID = fleet.defects.report(ctx("dispatcher", scopes = setOf("defects:read", "defects:write")),
        de.taktovia.fleetsafety.api.ReportDefect.parse(mapOf("vehicle_id" to vehicle.toString(), "occurred_at" to Instant.now().minusSeconds(600).toString(), "category_code" to "BODY", "description" to "Kratzer"))).defect.id

    fun handle(e: WorkflowEvent, tenant: TenantId = this.tenant) = fx.tx.run(tenant) { j -> workflow.handle(j, e) }

    fun runner() = OperationRunner(fx.tx, listOf(workflow.searchHandler))

    /** Ereignis am Bus (Hülle) für den Mandanten; `owner`/`recipient` getrennt, um fremde Mandanten zu prüfen. */
    fun envelope(type: String, payload: Map<String, Any?>, eventId: UUID = UUID.randomUUID(), owner: TenantId = tenant, recipient: TenantId = tenant): String {
        val at = Instant.now()
        return EventEnvelope(eventId, type, "1.0", eventId, 1, at, at, owner.value, recipient.value, UUID.randomUUID(), mapper.valueToTree(payload)).toJson()
    }

    fun processor() = InboxProcessor(fx.tx, security, SnapshotFetcher { _, _, _ -> throw SnapshotNotYetAvailableException("im Test nicht vorgesehen") })

    fun incidents(tenant: TenantId = this.tenant): List<String> = fx.strings("SELECT id::text FROM taktovia.incident WHERE tenant_id=? ORDER BY opened_at", tenant.value)
    fun operations(tenant: TenantId = this.tenant) = fx.count("SELECT count(*) FROM taktovia.operation WHERE tenant_id=? AND kind='REPLACEMENT_SEARCH'", tenant.value)
    fun outbox(type: String, tenant: TenantId = this.tenant) = fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type=?", tenant.value, type)
    fun logActions(incident: String): List<String> = fx.strings("SELECT action || ':' || outcome FROM taktovia.workflow_run_log WHERE incident_id = CAST(? AS uuid) ORDER BY created_at, id", incident)

    /** Dienst mit Konfigurationsrevision (Fixture) und Poolzuordnung des Fahrzeugs: der Incident erhält den Dienstbezug. */
    fun service(): UUID {
        val t = tenant.value; val cfg = UUID.randomUUID(); val svc = UUID.randomUUID()
        fx.exec("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'SERVICE',?,1,'1',CAST('{}' AS jsonb),'2026-01-01T00:00:00Z','user:t','Test')", t, cfg, svc)
        fx.exec("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,created_by) VALUES (?,?,?,'Dienst','Europe/Berlin',?,'user:t')", t, svc, w.orgs[0], cfg)
        return svc
    }

    fun pool(service: UUID, vehicle: UUID = this.vehicle) =
        fx.exec("INSERT INTO taktovia.vehicle_pool_assignment(tenant_id,id,service_id,vehicle_id,valid_from,created_by) VALUES (?,?,?,?,'2026-01-01T00:00:00Z','user:t')", tenant.value, UUID.randomUUID(), service, vehicle)
}
