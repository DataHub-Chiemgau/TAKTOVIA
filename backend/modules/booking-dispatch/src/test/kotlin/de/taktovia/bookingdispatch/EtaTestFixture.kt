package de.taktovia.bookingdispatch

import com.zaxxer.hikari.HikariDataSource
import de.taktovia.bookingdispatch.api.EtaApi
import de.taktovia.bookingdispatch.api.EtaRoadDataVersionException
import de.taktovia.bookingdispatch.api.EtaRoadRoute
import de.taktovia.bookingdispatch.api.EtaRoadRouteSource
import de.taktovia.bookingdispatch.api.EtaRoadSegment
import de.taktovia.bookingdispatch.api.RoutingException
import de.taktovia.bookingdispatch.api.RoutingFailure
import de.taktovia.config.api.ConfigApi
import de.taktovia.config.api.CreateDraft
import de.taktovia.config.api.ScopeKind
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Testaufbau für das ETA-Grundmodell und die Prognose (WP-M4-61/-62) gegen die echte Datenbank unter der Runtime-Rolle: je Test ein eigener Mandant, Modellrevision und Konfigurationsrevisionen
 * wie im Betrieb (Anlage und Freigabe der Modellrevision durch die Migrator-Rolle, Konfiguration über die Config-API). Eigene Klasse, keine Duplikate der Fixtures anderer Pakete.
 */
class EtaTestFixture : AutoCloseable {
    init { BookingTestDb.require() }
    val ds: HikariDataSource = BookingTestDb.runtime(24)
    private val admin = BookingTestDb.admin()
    val tx = TenantTransactions(ds)
    val ctxTx = ContextTransactions(tx, object : ContextRevalidator { override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, lock: Boolean) {} })
    val config: ConfigApi = ConfigApi.create(ctxTx, JdbcAuditPort(), Outbox())

    fun eta(roads: EtaRoadRouteSource, clock: Clock = Clock.systemUTC()): EtaApi = EtaApi.create(ctxTx, config.effective, roads, clock)

    /** Neuer Mandant; [approve] legt die freigegebene Modellrevision (Version 1, Planungswerte) an. */
    fun tenant(approve: Boolean = true, roadDataVersion: String? = null): TenantId {
        val t = UUID.randomUUID()
        adminUpdate("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,'ACTIVE')", t, "eta-$t")
        val tenant = TenantId(t)
        if (approve) approveModel(tenant, 1, roadDataVersion = roadDataVersion)
        return tenant
    }

    /** Freigegebene Modellrevision (Betriebsweg über die Migrator-Rolle; die Runtime-Rolle darf nur lesen). */
    fun approveModel(tenant: TenantId, version: Int, buffers: String = """{"p50":5,"p75":10,"p85":15,"p90":20}""", minBufferSec: Int = 10, roadDataVersion: String? = null,
                     parameters: String = """{"default_stop_dwell_sec":30,"default_wheelchair_boarding_sec":120}""") {
        BookingTestDb.migrator().use { m -> m.connection.use { c ->
            c.autoCommit = false
            c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)").use { it.setString(1, tenant.toString()); it.execute() }
            c.prepareStatement("INSERT INTO taktovia.eta_model_revision(tenant_id,id,version,parameters,road_data_version,state,approved_at,approved_by,created_by,buffer_pct_by_quantile,min_buffer_sec) " +
                "VALUES (?,?,?,CAST(? AS jsonb),?,'APPROVED',clock_timestamp(),'user:freigeber','user:betrieb',CAST(? AS jsonb),?)").use { ps ->
                ps.setObject(1, tenant.value); ps.setObject(2, UUID.randomUUID()); ps.setInt(3, version); ps.setString(4, parameters); ps.setString(5, roadDataVersion)
                ps.setString(6, buffers); ps.setInt(7, minBufferSec); ps.execute()
            }
            c.commit()
        } }
    }

    fun context(tenant: TenantId, subject: String = "disponent-" + UUID.randomUUID(), scopes: Set<String> = setOf("config:read", "config:write", "config:publish")): RequestContext =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/booking", subject, "sid-$subject", true), tenant, setOf("tenant_admin"), scopes, emptySet(), UUID.randomUUID())

    /** Veröffentlicht Mandantenparameter (wie im Betrieb nach Prüfung und Freigabe durch eine zweite Person, hier über die Zustände der Zeile); liefert die Revisions-ID. */
    fun publish(tenant: TenantId, values: Map<String, Any?>, effectiveFrom: Instant = Instant.parse("2026-01-01T00:00:00Z"), effectiveTo: Instant? = null): UUID {
        val ctx = context(tenant)
        val v = config.revisions.createDraft(ctx, CreateDraft(ScopeKind.TENANT, tenant.value, "1", values, effectiveFrom, effectiveTo, "Testaufbau ETA"))
        // wie die Publikation im Betrieb: Zustände und Schließen des Vorgängerintervalls in einer Transaktion (sonst gäbe es kurz keine wirksame Revision)
        tx.run(tenant) { j ->
            j.update("UPDATE taktovia.config_revision SET state = 'VALIDATED' WHERE id = ?", v.id)
            j.update("UPDATE taktovia.config_revision SET state = 'APPROVED', approved_by = 'user:zweite-person' WHERE id = ?", v.id)
            j.update("UPDATE taktovia.config_revision SET effective_to = ? WHERE tenant_id = ? AND scope_kind = 'TENANT' AND state = 'PUBLISHED' AND effective_to IS NULL AND effective_from < ? AND id <> ?",
                java.sql.Timestamp.from(effectiveFrom), tenant.value, java.sql.Timestamp.from(effectiveFrom), v.id)
            j.update("UPDATE taktovia.config_revision SET state = 'PUBLISHED', published_at = clock_timestamp() WHERE id = ?", v.id)
        }
        return v.id
    }

    fun adminUpdate(sql: String, vararg args: Any?): Int = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeUpdate() }
    }

    fun adminInt(sql: String, vararg args: Any?): Int = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { it.next(); it.getInt(1) } }
    }

    override fun close() { ds.close(); admin.close() }
}

/**
 * Straßendaten für Tests: je Version eine Streckentabelle; [current] ist die Version, die ein Aufruf ohne Pin bekommt (der Wechsel wirkt nur für neue Aufrufe), ein Pin wird eingehalten.
 * [onRoute] läuft nach jeder ausgelieferten Strecke (Hook für den Wechsel während eines Laufs). [failure] simuliert einen Ausfall des Routingdienstes.
 */
class StubRoads(initialVersion: String, tables: Map<String, Map<Pair<String, String>, Triple<Int, Int, List<EtaRoadSegment>?>>>) : EtaRoadRouteSource {
    val tables = ConcurrentHashMap(tables)
    val current = AtomicReference(initialVersion)
    @Volatile var failure: RoutingFailure? = null
    @Volatile var onRoute: (() -> Unit)? = null
    val calls = java.util.concurrent.atomic.AtomicInteger()

    override fun route(ctx: RequestContext, originStop: String, destinationStop: String, vehicleProfile: String, at: Instant, roadDataVersion: String?): EtaRoadRoute {
        calls.incrementAndGet()
        failure?.let { throw RoutingException(it, "simuliert") }
        val version = roadDataVersion ?: current.get()
        val table = tables[version] ?: throw EtaRoadDataVersionException("Version $version nicht verfügbar")
        val (time, distance, segments) = table[originStop to destinationStop] ?: throw RoutingException(RoutingFailure.NO_ROUTE, "keine Strecke")
        return EtaRoadRoute(version, time, distance, segments, "stub").also { onRoute?.invoke() }
    }

    companion object {
        /** Strecke H1 -> H2: 1000 m bei 50 km/h und 2000 m bei 30 km/h (Mindestfahrzeit 72 + 240 = 312 s), Routingzeit 360 s. */
        val AB = listOf(EtaRoadSegment(1000, 50), EtaRoadSegment(2000, 30))
        /** Strecke H2 -> H3: 500 m bei 30 km/h (Mindestfahrzeit 60 s), Routingzeit 90 s. */
        val BC = listOf(EtaRoadSegment(500, 30))

        fun table(abTime: Int = 360, bcTime: Int = 90, ab: List<EtaRoadSegment>? = AB, bc: List<EtaRoadSegment>? = BC): Map<Pair<String, String>, Triple<Int, Int, List<EtaRoadSegment>?>> = mapOf(
            ("H1" to "H2") to Triple(abTime, 3000, ab), ("H2" to "H3") to Triple(bcTime, 500, bc),
        )
    }
}
