package de.taktovia.app.analytics

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.analytics.api.MetricFilter
import de.taktovia.analytics.api.MetricQuery
import de.taktovia.analytics.api.ReportingPeriod
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.config.api.ConfigApi
import de.taktovia.config.api.CreateDraft
import de.taktovia.config.api.ScopeKind
import de.taktovia.TaktoviaTestSupport
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Grenzwert der Abholpünktlichkeit aus der **echten** wirksamen Konfiguration (WP-M4-53, gebietsspezifisch, LH-I.6-KPI-007): `on_time_pickup_tolerance_min` mit Präzedenz Dienst vor Region vor Mandant vor
 * Katalogstandard, Wirksamkeit zum Vergleichsstichtag und Ende-zu-Ende über die Kennzahl: zwei Gebiete mit Grenzwert 3 und 2 klassifizieren dieselbe 3-Minuten-Überschreitung unterschiedlich.
 */
class ConfigPickupToleranceSourceTest {
    private val tx = IdentityEnv.tx
    private val admin by lazy { TaktoviaTestSupport.adminDataSourceOrNull() ?: error("Administratorzugang nicht konfiguriert") }
    private val ctxTx = ContextTransactions(tx, object : ContextRevalidator { override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, lock: Boolean) = Unit })
    private val config = ConfigApi.create(ctxTx, JdbcAuditPort(), Outbox())
    private val mapper = JsonMapper.builder().build()

    private fun ctx(t: TenantId) = RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/config", "u-" + UUID.randomUUID(), "sid", true), t, setOf("tenant_admin"), setOf("config:read", "config:write", "config:publish"), emptySet(), UUID.randomUUID())

    private fun region(t: TenantId): UUID = UUID.randomUUID().also { id ->
        admin.connection.use { c -> c.createStatement().use { it.execute("SET session_replication_role = replica") }; c.prepareStatement("INSERT INTO taktovia.region(tenant_id,id,name,created_by) VALUES (?,?,'Gebiet','user:test')").use { it.setObject(1, t.value); it.setObject(2, id); it.executeUpdate() } }
    }

    private fun service(t: TenantId, region: UUID): UUID = UUID.randomUUID().also { id ->
        admin.connection.use { c -> c.createStatement().use { it.execute("SET session_replication_role = replica") }
            c.prepareStatement("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,region_id,created_by) VALUES (?,?,?,'Dienst','Europe/Berlin',?,?,'user:test')").use {
                it.setObject(1, t.value); it.setObject(2, id); it.setObject(3, UUID.randomUUID()); it.setObject(4, UUID.randomUUID()); it.setObject(5, region); it.executeUpdate() } }
    }

    /** Entwurf anlegen und wie die Publikation durch die Zustände führen (Testaufbau; die Vier-Augen-Publikation selbst gehört WP-M1-22). */
    private fun publish(t: TenantId, kind: ScopeKind, scope: UUID, minutes: Int, from: String): UUID {
        val extra = if (kind == ScopeKind.SERVICE) mapOf("service_config" to mapOf("service_area_id" to UUID.randomUUID().toString(), "traffic_type" to "AREA_ON_DEMAND", "operator_id" to UUID.randomUUID().toString(),
            "operating_hours" to listOf(mapOf("weekdays" to listOf("MON"), "from" to "06:00", "to" to "22:00")), "tariff_id" to UUID.randomUUID().toString())) else emptyMap()
        val draft = config.revisions.createDraft(ctx(t), CreateDraft(kind, scope, "1", mapOf("on_time_pickup_tolerance_min" to minutes) + extra, Instant.parse(from), null, "Testaufbau Abholgrenzwert"))
        for ((state, add) in listOf("VALIDATED" to "", "APPROVED" to ", approved_by = 'user:zweite-person'", "PUBLISHED" to ", published_at = clock_timestamp()")) tx.run(t) { j ->
            if (state == "PUBLISHED") j.update("UPDATE taktovia.config_revision p SET effective_to = n.effective_from FROM taktovia.config_revision n WHERE n.id = ? AND p.tenant_id = n.tenant_id AND p.scope_kind = n.scope_kind AND p.scope_id = n.scope_id AND p.state = 'PUBLISHED' AND p.effective_to IS NULL AND p.effective_from < n.effective_from", draft.id)
            j.update("UPDATE taktovia.config_revision SET state = ?$add WHERE id = ?", state, draft.id)
        }
        return draft.id
    }

    private fun resolve(t: TenantId, service: UUID?, at: String) = tx.run(t) { j -> ConfigPickupToleranceSource(config, ctx(t)).resolve(j, t, service, Instant.parse(at)) }

    @Test
    fun aufloesung_nach_praezedenz_und_stichtag() {
        val t = IdentityEnv.tenant(); val r1 = region(t); val r2 = region(t); val s1 = service(t, r1); val s2 = service(t, r2); val s3 = service(t, r1)
        // ohne jede Revision gilt der Katalogstandard (GLOBAL, keine Konfigurationsrevision)
        resolve(t, s1, "2026-12-01T06:00:00Z")!!.also { assertEquals(0, it.minutes); assertEquals("GLOBAL", it.level); assertNull(it.configRevisionId) }
        val tenantRev = publish(t, ScopeKind.TENANT, t.value, 5, "2026-01-01T00:00:00Z")
        val rev1 = publish(t, ScopeKind.REGION, r1, 3, "2026-01-01T00:00:00Z"); val rev2 = publish(t, ScopeKind.REGION, r2, 2, "2026-01-01T00:00:00Z")
        val svc = publish(t, ScopeKind.SERVICE, s3, 4, "2026-01-01T00:00:00Z")
        resolve(t, s1, "2026-12-01T06:00:00Z")!!.also { assertEquals(3, it.minutes); assertEquals("REGION", it.level); assertEquals(rev1, it.configRevisionId) }
        resolve(t, s2, "2026-12-01T06:00:00Z")!!.also { assertEquals(2, it.minutes); assertEquals(rev2, it.configRevisionId) }
        resolve(t, s3, "2026-12-01T06:00:00Z")!!.also { assertEquals(4, it.minutes); assertEquals("SERVICE", it.level); assertEquals(svc, it.configRevisionId) }
        resolve(t, null, "2026-12-01T06:00:00Z")!!.also { assertEquals(5, it.minutes); assertEquals("TENANT", it.level); assertEquals(tenantRev, it.configRevisionId) }
        // Vergleichsstichtag: eine neue Revision des Gebiets ab 2027 verändert den Grenzwert zum Stichtag 2026 nicht
        val rev1b = publish(t, ScopeKind.REGION, r1, 1, "2027-01-01T00:00:00Z")
        resolve(t, s1, "2026-12-01T06:00:00Z")!!.also { assertEquals(3, it.minutes); assertEquals(rev1, it.configRevisionId) }
        resolve(t, s1, "2027-02-01T06:00:00Z")!!.also { assertEquals(1, it.minutes); assertEquals(rev1b, it.configRevisionId) }
        // unbekannter Dienst und Dienst eines fremden Mandanten sind nicht auflösbar
        assertNull(resolve(t, UUID.randomUUID(), "2026-12-01T06:00:00Z"))
        val other = IdentityEnv.tenant(); assertNull(resolve(other, s1, "2026-12-01T06:00:00Z"))
    }

    @Test
    fun zwei_gebiete_klassifizieren_dieselbe_drei_minuten_ueberschreitung_unterschiedlich_ende_zu_ende() {
        val t = IdentityEnv.tenant(); val r1 = region(t); val r2 = region(t); val s1 = service(t, r1); val s2 = service(t, r2)
        publish(t, ScopeKind.REGION, r1, 3, "2026-01-01T00:00:00Z"); publish(t, ScopeKind.REGION, r2, 2, "2026-01-01T00:00:00Z")
        val tf = AnalyticsApi.createTimeFacts(tx); val metrics = AnalyticsApi.createMetrics(tx, 1); val source = ConfigPickupToleranceSource(config, ctx(t))
        fun env(type: String, aggregate: UUID, rev: Long, at: String, payload: Map<String, Any?>) = EventEnvelope(UUID.randomUUID(), type, "1.0", aggregate, rev, Instant.parse(at), Instant.parse(at), t.value, t.value, UUID.randomUUID(), mapper.valueToTree(payload)).toJson()
        for (s in listOf(s1, s2)) {
            val b = UUID.randomUUID()
            tf.consume(t, env("booking.confirmed", b, 1, "2026-12-01T06:00:00Z", mapOf("booking_id" to b.toString(), "hold_id" to UUID.randomUUID().toString(), "service_id" to s.toString(), "run_id" to UUID.randomUUID().toString(),
                "run_revision" to 1, "pickup_earliest" to "2026-12-01T08:10:00Z", "pickup_latest" to "2026-12-01T08:15:00Z", "gross_minor" to 850, "currency" to "EUR", "capacity_vector" to mapOf("seats" to 1))))
            tf.consume(t, env("driver.event_recorded", UUID.randomUUID(), 1, "2026-12-01T08:18:00Z", mapOf("event_id" to UUID.randomUUID().toString(), "shift_id" to UUID.randomUUID().toString(), "vehicle_id" to UUID.randomUUID().toString(),
                "event_type" to "CHECK_IN", "outcome" to "APPLIED", "route_stop_id" to UUID.randomUUID().toString(), "received_at" to "2026-12-01T08:18:00Z", "booking_id" to b.toString(), "reason_code" to null,
                "effective_at" to "2026-12-01T08:18:00Z", "offline_recorded" to false, "no_show_reason" to null, "no_show_wait_seconds" to null, "no_show_grace_min" to null)))
        }
        val day = ReportingPeriod.ofDays(ZoneId.of("Europe/Berlin"), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1))
        fun v(s: UUID) = metrics.evaluate(t, MetricQuery(day, "pickup_on_time", null, MetricFilter(serviceId = s)), source).single()
        assertEquals(0, BigDecimal.ONE.compareTo(v(s1).value), "Gebiet 1 (Grenzwert 3): pünktlich"); assertEquals(0, BigDecimal.ZERO.compareTo(v(s2).value), "Gebiet 2 (Grenzwert 2): verspätet")
        val rev = tx.run(t) { j -> j.queryForList("SELECT config_revision_id::text FROM taktovia.pickup_tolerance_binding ORDER BY tolerance_min", String::class.java) }
        assertEquals(2, rev.size); assertEquals(2, rev.distinct().size, "jede Bindung nennt die Revision ihres Gebiets")
    }
}
