package de.taktovia.analytics

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.Assumptions.assumeTrue
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * Datenbankabhängige Tests des Moduls `analytics` (WP-M4-45): Zugangsdaten aus der Umgebung; fehlen sie, werden die Tests übersprungen, mit `TAKTOVIA_REQUIRE_DB=true` (CI) schlagen sie fehl.
 * Die Faktenbasis läuft unter der Runtime-Rolle (RLS), Mandanten legt der Administrator an.
 */
/** Nur die Faktenbasis (ohne Exportaufträge, WP-M4-45). */
class Facts(val facts: de.taktovia.analytics.api.FactBase)

class AnalyticsFixture : AutoCloseable {
    private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }
    init {
        if (env("TAKTOVIA_DB_RUNTIME_PASSWORD") == null || env("TAKTOVIA_DB_ADMIN_PASSWORD") == null) {
            if (env("TAKTOVIA_REQUIRE_DB") == "true") throw AssertionError("Datenbank erforderlich (TAKTOVIA_REQUIRE_DB=true)")
            assumeTrue(false, "Datenbank-Zugangsdaten nicht gesetzt")
        }
    }
    private val url = "jdbc:postgresql://${env("TAKTOVIA_DB_HOST") ?: "127.0.0.1"}:${env("TAKTOVIA_DB_PORT") ?: "5432"}/${env("TAKTOVIA_DB_NAME") ?: "taktovia"}"
    private fun pool(user: String, password: String, size: Int) = HikariDataSource(HikariConfig().also { it.jdbcUrl = url; it.username = user; it.password = password; it.maximumPoolSize = size; it.minimumIdle = 1 })
    val ds = pool("taktovia_runtime", env("TAKTOVIA_DB_RUNTIME_PASSWORD")!!, 24)
    private val admin = pool(env("TAKTOVIA_DB_ADMIN_USER") ?: "dev_admin", env("TAKTOVIA_DB_ADMIN_PASSWORD")!!, 1)
    val tx = TenantTransactions(ds)
    val analytics = Facts(AnalyticsApi.createFacts(tx))
    val mapper = JsonMapper.builder().build()

    fun tenant(): TenantId = UUID.randomUUID().let { t -> exec("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,'ACTIVE')", t, "t-$t"); TenantId(t) }

    fun exec(sql: String, vararg args: Any?) { admin.connection.use { c -> c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeUpdate() } } }
    fun count(sql: String, vararg args: Any?): Int = admin.connection.use { c -> c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { it.next(); it.getInt(1) } } }
    fun strings(sql: String, vararg args: Any?): List<String> = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
    }

    /** Ereignishülle (Eigentümer = Empfänger = [tenant], sofern nicht anders angegeben). */
    fun envelope(tenant: TenantId, type: String, aggregate: UUID, revision: Long, at: String, payload: String, eventId: UUID = UUID.randomUUID(), recipient: TenantId = tenant): Pair<UUID, String> =
        eventId to EventEnvelope(eventId, type, "1.0", aggregate, revision, Instant.parse(at), Instant.parse(at), tenant.value, recipient.value, UUID.randomUUID(), mapper.readTree(payload)).toJson()

    /**
     * Abzug aller Fakten eines Mandanten (ohne Spalten, die von der Verarbeitung abhängen: Eingangszeit, Stand der Verarbeitung) in fester Reihenfolge, zum Vergleich zweier Mandanten mit
     * derselben Ereignismenge. Die Mandanten-ID selbst gehört nicht dazu.
     */
    fun factDump(tenant: TenantId): List<String> {
        fun q(sql: String) = strings(sql, tenant.value)
        return q("SELECT 'attempt|' || concat_ws('|', attempt_id, service_id, outcome, primary_reason, time_mode, requested_at, passengers, wheelchair, coarse_origin, coarse_destination, channel) FROM taktovia.fact_attempt WHERE tenant_id = ? ORDER BY attempt_id") +
            q("SELECT 'offer|' || concat_ws('|', offer_id, attempt_id, service_id, created_at, expires_at, pickup_earliest, pickup_latest, gross_minor, currency) FROM taktovia.fact_offer WHERE tenant_id = ? ORDER BY offer_id") +
            q("SELECT 'booking|' || concat_ws('|', booking_id, service_id, confirmed_at, pickup_earliest, pickup_latest, gross_minor, currency, cancelled_at, cancel_reason, cause_class, boarded_at, alighted_at, no_show_at, no_show_wait_seconds, state) FROM taktovia.fact_booking WHERE tenant_id = ? ORDER BY booking_id") +
            q("SELECT 'stop|' || concat_ws('|', event_id, shift_id, vehicle_id, booking_id, route_stop_id, event_type, outcome, reason_code, effective_at, occurred_at, offline_recorded, no_show_wait_seconds, no_show_grace_min) FROM taktovia.fact_stop_event WHERE tenant_id = ? ORDER BY event_id") +
            q("SELECT 'vehicle|' || concat_ws('|', event_id, vehicle_id, kind, ref_id, effective_at, safety_revision) FROM taktovia.fact_vehicle_state WHERE tenant_id = ? ORDER BY event_id") +
            q("SELECT 'watermark|' || concat_ws('|', source, processed_until, completeness, gap_count, event_count) FROM taktovia.dataset_watermark WHERE tenant_id = ? ORDER BY source")
    }

    override fun close() { ds.close(); admin.close() }
}
