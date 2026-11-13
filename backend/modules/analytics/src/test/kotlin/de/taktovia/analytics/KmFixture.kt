package de.taktovia.analytics

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.analytics.api.KmClassification
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.Instant
import java.util.UUID

/**
 * Fixture der Kilometerklassifikation (WP-M4-42): Anwendung unter der echten Runtime-Rolle (RLS), Stammdaten, Positionen, Fahrtenplan und Fahrerereignisse legt der Administrator an (die führenden
 * Module liefern sie in der Produktion). Ohne Datenbankzugang übersprungen, mit `TAKTOVIA_REQUIRE_DB=true` schlägt der Test fehl.
 */
class KmFixture : AutoCloseable {
    private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }
    private val url get() = "jdbc:postgresql://${env("TAKTOVIA_DB_HOST") ?: "127.0.0.1"}:${env("TAKTOVIA_DB_PORT") ?: "5432"}/${env("TAKTOVIA_DB_NAME") ?: "taktovia"}"
    private fun pool(user: String, pw: String, size: Int) = HikariDataSource(HikariConfig().also { it.jdbcUrl = url; it.username = user; it.password = pw; it.maximumPoolSize = size; it.minimumIdle = 1 })

    init {
        if (env("TAKTOVIA_DB_RUNTIME_PASSWORD") == null || env("TAKTOVIA_DB_ADMIN_PASSWORD") == null) {
            if (env("TAKTOVIA_REQUIRE_DB") == "true") throw AssertionError("Datenbank erforderlich (TAKTOVIA_REQUIRE_DB=true)")
            assumeTrue(false, "Datenbank-Zugangsdaten nicht gesetzt")
        }
    }

    private val ds = pool("taktovia_runtime", env("TAKTOVIA_DB_RUNTIME_PASSWORD")!!, 24)
    private val admin = pool(env("TAKTOVIA_DB_ADMIN_USER") ?: "dev_admin", env("TAKTOVIA_DB_ADMIN_PASSWORD")!!, 2)
    val tx = TenantTransactions(ds)
    val ctxTx = ContextTransactions(tx, object : ContextRevalidator { override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, lock: Boolean) {} })
    val km: KmClassification = AnalyticsApi.createKm(ctxTx, tx)

    fun exec(sql: String, vararg args: Any?) { admin.connection.use { c -> c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeUpdate() } } }
    fun strings(sql: String, vararg args: Any?): List<String> = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
    }
    fun count(sql: String, vararg args: Any?): Int = strings(sql, *args).single().toInt()

    class World(val tenant: TenantId, val vehicle: UUID)

    fun world(): World {
        val t = UUID.randomUUID(); val v = UUID.randomUUID()
        exec("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,'ACTIVE')", t, "km-$t")
        exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label,vehicle_type,created_at) VALUES (?,?,'fixture','{}'::jsonb,'V-1','PKW',CAST('2026-01-01T00:00:00Z' AS timestamptz))", t, v)
        return World(TenantId(t), v)
    }

    fun ctx(tenant: TenantId, scopes: Set<String>) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/m442", "u-1", "sid-1", true), tenant, setOf("finance"), scopes, emptySet(), UUID.randomUUID())

    /** Veröffentlichte Schicht des Fahrzeugs; mit [plan] einen Umlauf mit einem Stopp in Planrevision 1. */
    fun shift(w: World, from: String, to: String, function: String = "Fahrdienst", plan: Boolean = true): UUID {
        val shift = UUID.randomUUID()
        exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state,shift_function) VALUES (?,?,?,?,CAST(? AS timestamptz),CAST(? AS timestamptz),'Europe/Berlin',1,'PUBLISHED',?)",
            w.tenant.value, shift, UUID.randomUUID(), w.vehicle, from, to, function)
        if (plan) {
            val run = UUID.randomUUID(); val rev = UUID.randomUUID()
            exec("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (?,?,?,?,1,'PLANNED')", w.tenant.value, run, w.vehicle, shift)
            exec("INSERT INTO taktovia.stop_revision(tenant_id,id,stop_id,revision,point,name,accessibility,approved_by,valid_from) VALUES (?,?,?,1,ST_SetSRID(ST_MakePoint(9.0,48.0),4326),'Stopp','{}'::jsonb,'test',now() - interval '1 year')", w.tenant.value, rev, UUID.randomUUID())
            exec("INSERT INTO taktovia.route_stop(tenant_id,id,run_id,run_revision,seq,stop_revision_id,planned_at,leg_duration_sec,leg_distance_m) VALUES (?,?,?,1,0,?,CAST(? AS timestamptz),600,5000)", w.tenant.value, UUID.randomUUID(), run, rev, from)
        }
        return shift
    }

    /** Position des Fahrzeugs; Breitengrad 48 + [step] / 1000 Grad (ein Schritt etwa 111 m). [receivedAt] steuert den Eingang (Datenstand). */
    fun position(w: World, shift: UUID, at: String, step: Int, receivedAt: String = at): UUID {
        val id = UUID.randomUUID()
        exec("INSERT INTO taktovia.position_sample(tenant_id,id,vehicle_id,shift_id,position,accuracy_m,measured_at,received_at,created_by) VALUES (?,?,?,?,ST_SetSRID(ST_MakePoint(9.0, 48.0 + ? / 1000.0),4326)::geography,5,CAST(? AS timestamptz),CAST(? AS timestamptz),'service:test')",
            w.tenant.value, id, w.vehicle, shift, step, at, receivedAt)
        return id
    }

    /** Abstand zwischen zwei Schritten in Metern, unabhängig von der Klassifikation berechnet. */
    fun meters(a: Int, b: Int): Long = strings("SELECT round(ST_Distance(ST_SetSRID(ST_MakePoint(9.0, 48.0 + ? / 1000.0),4326)::geography, ST_SetSRID(ST_MakePoint(9.0, 48.0 + ? / 1000.0),4326)::geography))::bigint", a, b).single().toLong()

    /** Fahrerereignis (Einstieg `CHECK_IN`, Ausstieg `CHECK_OUT`) in der Faktenbasis. */
    fun driverEvent(w: World, shift: UUID, booking: UUID, type: String, at: String) =
        exec("INSERT INTO taktovia.fact_stop_event(tenant_id,event_id,shift_id,vehicle_id,booking_id,event_type,outcome,effective_at,occurred_at,offline_recorded) VALUES (?,?,?,?,?,?,'APPLIED',CAST(? AS timestamptz),CAST(? AS timestamptz),false)",
            w.tenant.value, UUID.randomUUID(), shift, w.vehicle, booking, type, at, at)

    override fun close() { ds.close(); admin.close() }
}
