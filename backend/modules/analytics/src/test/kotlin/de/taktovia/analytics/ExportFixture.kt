package de.taktovia.analytics

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Veränderbare Uhr für Datenstand und Linkablauf (WP-M5-19). */
class ExportClock(@Volatile var now: Instant = Instant.parse("2026-10-10T10:00:00Z")) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/**
 * Fixture der Exporttests (WP-M5-19): echte Runtime-Rolle für die Anwendung, Administrator für Stammdaten und Auswertungen. Eigene Klasse je Cluster, keine Duplikate fremder Fixtures. Ohne
 * Datenbankzugang wird übersprungen, mit `TAKTOVIA_REQUIRE_DB=true` schlägt der Test fehl.
 */
class ExportFixture(val clock: ExportClock = ExportClock()) : AutoCloseable {
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
    val secret = ByteArray(32) { (it * 7 + 3).toByte() }
    val api: AnalyticsApi = AnalyticsApi.create(ctxTx, tx, JdbcAuditPort(), secret, clock)

    fun exec(sql: String, vararg args: Any?) { admin.connection.use { c -> c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeUpdate() } } }
    fun strings(sql: String, vararg args: Any?): List<String> = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
    }
    fun count(sql: String, vararg args: Any?): Int = strings(sql, *args).single().toInt()

    class World(val tenant: TenantId, val vehicle: UUID, val depot: UUID)

    fun world(): World {
        val t = UUID.randomUUID(); val v = UUID.randomUUID(); val region = UUID.randomUUID(); val depot = UUID.randomUUID()
        exec("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,'ACTIVE')", t, "t-$t")
        exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label,vehicle_type,created_at) VALUES (?,?,'fixture','{}'::jsonb,'V-1','PKW',CAST('2026-01-01T00:00:00Z' AS timestamptz))", t, v)
        exec("INSERT INTO taktovia.region(tenant_id,id,name,created_by) VALUES (?,?,'R','user:t')", t, region)
        exec("INSERT INTO taktovia.depot(tenant_id,id,region_id,name,created_by) VALUES (?,?,?,'D','user:t')", t, depot, region)
        return World(TenantId(t), v, depot)
    }

    fun ctx(tenant: TenantId, scopes: Set<String>, roles: Set<String> = setOf("dispatcher"), subject: String = UUID.randomUUID().toString()) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/m5", subject, "sid-$subject", true), tenant, roles, scopes, emptySet(), UUID.randomUUID())

    /** Prüflauf mit [answers] Antworten (Zeitpunkte einheitlich [at]); liefert die Prüflaufkennung. */
    fun inspection(w: World, answers: Int = 3, at: String = "2026-10-01T05:00:00Z"): UUID {
        val template = UUID.randomUUID(); val shift = UUID.randomUUID(); val run = UUID.randomUUID(); val drv = UUID.randomUUID()
        exec("INSERT INTO taktovia.checklist_template_revision(tenant_id,id,template_id,version,vehicle_class,service_mode,state,published_at,created_by,approved_by) VALUES (?,?,?,1,'PKW','STANDARD','PUBLISHED',now(),'user:a','user:b')", w.tenant.value, template, UUID.randomUUID())
        exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,CAST(? AS timestamptz),CAST(? AS timestamptz) + interval '8 hours','Europe/Berlin',1,'PUBLISHED')", w.tenant.value, shift, drv, w.vehicle, at, at)
        exec("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,started_at,received_at,created_by) VALUES (?,?,?,?,?,?,'PRE_SHIFT',CAST(? AS timestamptz),CAST(? AS timestamptz) + interval '6 minutes','user:t')",
            w.tenant.value, run, shift, w.vehicle, drv, template, at, at)
        repeat(answers) { i -> exec("INSERT INTO taktovia.inspection_answer(tenant_id,id,inspection_id,step_code,answer,answered_at,created_by,created_at) VALUES (?,?,?,?,'OK',CAST(? AS timestamptz),'user:t',CAST(? AS timestamptz))", w.tenant.value, UUID.randomUUID(), run, "STEP_${i + 1}", at, at) }
        exec("UPDATE taktovia.inspection_run SET status = 'COMPLETED', outcome = 'PASSED', completed_at = CAST(? AS timestamptz) + interval '5 minutes', completed_by = 'user:t' WHERE id = ?", at, run)
        return run
    }

    fun defect(w: World, confidentialEvidence: Boolean = false): UUID {
        val d = UUID.randomUUID()
        exec("INSERT INTO taktovia.defect_case(tenant_id,id,case_number,vehicle_id,reported_by,occurred_at,category_code,description,created_by,created_at) VALUES (?,?,?,?,?,CAST('2026-10-01T06:00:00Z' AS timestamptz),'BRAKES','Bremse zieht',?,CAST('2026-10-01T06:00:00Z' AS timestamptz))", w.tenant.value, d, "DC-" + d.toString().take(8), w.vehicle, UUID.randomUUID(), "user:t")
        exec("INSERT INTO taktovia.defect_evidence(tenant_id,id,defect_id,media_ref,sha256,captured_at,created_by,received_at,confidentiality) VALUES (?,?,?,?,?,CAST('2026-10-01T06:01:00Z' AS timestamptz),'user:t',CAST('2026-10-01T06:01:00Z' AS timestamptz),'STANDARD')", w.tenant.value, UUID.randomUUID(), d, "media/" + UUID.randomUUID(), "a".repeat(64))
        if (confidentialEvidence) exec("INSERT INTO taktovia.defect_evidence(tenant_id,id,defect_id,media_ref,sha256,captured_at,created_by,received_at,confidentiality) VALUES (?,?,?,?,?,CAST('2026-10-01T06:02:00Z' AS timestamptz),'user:t',CAST('2026-10-01T06:02:00Z' AS timestamptz),'CONFIDENTIAL')", w.tenant.value, UUID.randomUUID(), d, "media/" + UUID.randomUUID(), "b".repeat(64))
        return d
    }

    fun timeEvent(w: World, person: String, at: String, kind: String = "WORK_START") =
        exec("INSERT INTO taktovia.time_event(tenant_id,id,person_ref,kind,occurred_at,device_recorded_at,received_at,source,recorded_by,payload_sha256,timezone) VALUES (?,?,?,?,CAST(? AS timestamptz),CAST(? AS timestamptz),CAST(? AS timestamptz),'APP',?,?,'Europe/Berlin')",
            w.tenant.value, UUID.randomUUID(), person, kind, at, at, at, person, "c".repeat(64))

    /** Dienstplan des Depots (Woche ab 2026-11-30) mit veröffentlichter Revision 1 (danach ersetzt) und Revision 2 (veröffentlicht), je eine Schicht. */
    fun rosters(w: World): UUID {
        val roster = UUID.randomUUID()
        for (rev in 1..2) {
            exec("INSERT INTO taktovia.roster_revision(tenant_id,id,roster_id,depot_id,week_start,revision,author) VALUES (?,?,?,?,DATE '2026-09-28',?,'user:planer')", w.tenant.value, UUID.randomUUID(), roster, w.depot, rev)
            exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state,roster_id,depot_id,shift_function) VALUES (?,?,?,?,CAST('2026-10-02T05:00:00Z' AS timestamptz),CAST('2026-10-02T13:00:00Z' AS timestamptz),'Europe/Berlin',?,'DRAFT',?,?,'Fahrdienst')",
                w.tenant.value, UUID.randomUUID(), UUID.randomUUID(), w.vehicle, rev, roster, w.depot)
            exec("UPDATE taktovia.staff_shift SET state = 'PUBLISHED' WHERE tenant_id = ? AND roster_id = ? AND roster_revision = ?", w.tenant.value, roster, rev)
            exec("UPDATE taktovia.roster_revision SET state = 'PUBLISHED', published_at = CAST(? AS timestamptz), published_by = 'user:planer', effective_from = CAST(? AS timestamptz), publish_reason = 'Freigabe' WHERE roster_id = ? AND revision = ?",
                "2026-09-2${rev}T08:00:00Z", "2026-09-2${rev}T08:00:00Z", roster, rev)
            if (rev == 1) {
                exec("UPDATE taktovia.staff_shift SET state = 'SUPERSEDED' WHERE roster_id = ? AND roster_revision = 1", roster)
                exec("UPDATE taktovia.roster_revision SET state = 'SUPERSEDED' WHERE roster_id = ? AND revision = 1", roster)
            }
        }
        return roster
    }

    override fun close() { ds.close(); admin.close() }
}
