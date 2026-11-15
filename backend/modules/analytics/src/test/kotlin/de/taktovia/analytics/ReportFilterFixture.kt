package de.taktovia.analytics

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.analytics.api.MetricFilter
import de.taktovia.analytics.api.MetricQuery
import de.taktovia.analytics.api.MetricValue
import de.taktovia.analytics.api.Metrics
import de.taktovia.analytics.api.ReportScope
import de.taktovia.analytics.api.ReportingPeriod
import de.taktovia.platform.tenant.TenantId
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Testhilfe des Clusters wave15-c4 (WP-M4-58, Filterdimensionen der Berichte): ein Mandant mit zwei Betreibern, zwei Regionen, drei Diensten und zwei Bediengebieten, Versuchsereignisse in bekannter
 * Verteilung über Dienste und Ortsstunden (Europe/Berlin) und ein **unabhängiges Referenzmodell** [Att], aus dem die erwarteten Zahlen gezählt werden (nicht aus den Fakten). Läuft auf der echten Datenbank
 * (Runtime-Rolle, RLS); der Aufbau der Stammdaten und das Lesen laufen als Administrator.
 */
class ReportFilterFixture(minGroup: Int = 3) : AutoCloseable {
    val kpi = KpiFixture()
    val metrics: Metrics = AnalyticsApi.createMetrics(kpi.base.tx, minGroup)
    private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }
    private val admin = HikariDataSource(HikariConfig().also {
        it.jdbcUrl = "jdbc:postgresql://${env("TAKTOVIA_DB_HOST") ?: "127.0.0.1"}:${env("TAKTOVIA_DB_PORT") ?: "5432"}/${env("TAKTOVIA_DB_NAME") ?: "taktovia"}"
        it.username = env("TAKTOVIA_DB_ADMIN_USER") ?: "dev_admin"; it.password = env("TAKTOVIA_DB_ADMIN_PASSWORD"); it.maximumPoolSize = 2
    })
    val zone: ZoneId = ZoneId.of("Europe/Berlin")
    val day: ReportingPeriod = kpi.day

    /** Ein Versuch des Referenzmodells: Dienst, Ortsstunde, Ergebnis. */
    class Att(val service: UUID, val operator: UUID, val region: UUID, val area: UUID?, val localHour: Int, val localDay: LocalDate, val rejected: Boolean)

    class World(
        val tenant: TenantId, val o1: UUID, val o2: UUID, val r1: UUID, val r2: UUID, val s1: UUID, val s2: UUID, val s3: UUID, val a1: UUID, val a2: UUID,
        val reference: MutableList<Att> = mutableListOf(),
    ) {
        fun ref(f: (Att) -> Boolean) = reference.filter(f)
    }

    /** Administrator mit abgeschaltetem Fremdschlüsselprüfen (Stammdaten-Fixture, wie die Konfigurationstests). */
    fun exec(sql: String, vararg args: Any?) {
        admin.connection.use { c ->
            c.createStatement().use { it.execute("SET session_replication_role = replica") }
            c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeUpdate() }
        }
    }

    fun strings(sql: String, vararg args: Any?): List<String> = kpi.base.strings(sql, *args)

    fun count(sql: String, vararg args: Any?): Int = admin.connection.use { c -> c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { it.next(); it.getInt(1) } } }

    /** Mandant mit Stammdaten: S1 (Betreiber 1, Region 1, Gebiet 1), S2 (Betreiber 1, Region 2, Gebiet 2), S3 (Betreiber 2, Region 2, ohne Gebietsangabe). */
    fun world(): World {
        val t = kpi.tenant()
        val w = World(t, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        listOf(w.r1 to "Region 1", w.r2 to "Region 2").forEach { (id, n) -> exec("INSERT INTO taktovia.region(tenant_id,id,name,created_by) VALUES (?,?,?,'user:test')", t.value, id, n) }
        listOf(Triple(w.s1, w.o1, w.r1), Triple(w.s2, w.o1, w.r2), Triple(w.s3, w.o2, w.r2)).forEach { (s, o, r) ->
            exec("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,region_id,created_by) VALUES (?,?,?,'Dienst','Europe/Berlin',?,?,'user:test')", t.value, s, o, UUID.randomUUID(), r)
        }
        return w
    }

    /** Einen Versuch zur Ortszeit [localHour] am [localDay] (Europe/Berlin) einspielen und im Referenzmodell vermerken; Bediengebiet nur, wenn angegeben (Nutzlastfeld `service_area_id`). */
    fun attempt(w: World, service: UUID, localHour: Int, rejected: Boolean = false, localDay: LocalDate = LocalDate.of(2026, 12, 1), minute: Int = 10, withArea: Boolean = true): UUID {
        val operator = if (service == w.s3) w.o2 else w.o1; val region = if (service == w.s1) w.r1 else w.r2
        val area = if (!withArea) null else when (service) { w.s1 -> w.a1; w.s2 -> w.a2; else -> null }
        val at = ZonedDateTime.of(localDay, java.time.LocalTime.of(localHour, minute), zone).toInstant().toString()
        return attemptAtInstant(w, service, at, rejected, area, operator, region, localHour, localDay)
    }

    /** Einen Versuch zu einem absoluten Zeitpunkt (für die Zeitumstellung); [localHour] und [localDay] sind der erwartete Ortsbezug im Referenzmodell. */
    fun attemptAtInstant(w: World, service: UUID, at: String, rejected: Boolean, area: UUID?, operator: UUID, region: UUID, localHour: Int, localDay: LocalDate): UUID {
        val id = UUID.randomUUID()
        val payload = buildString {
            append("""{"attempt_id":"$id","service_id":"$service","outcome":"${if (rejected) "REJECTED" else "RECORDED"}","time_mode":"DEPARTURE","requested_at":"$at","passengers":1,"wheelchair":0,"coarse_origin":"Z-1","coarse_destination":"Z-2","channel":"WEB"""")
            if (rejected) append(""","primary_reason":"NO_CAPACITY"""")
            if (area != null) append(""","service_area_id":"$area"""")
            append("}")
        }
        val (_, json) = kpi.base.envelope(w.tenant, "booking.attempt_recorded", id, 1, at, payload)
        kpi.base.analytics.facts.consume(w.tenant, json)
        w.reference += Att(service, operator, region, area, localHour, localDay, rejected)
        return id
    }

    /** Verteilung: S1 5x08h + 4x17h, S2 6x08h + 3x17h, S3 4x08h (insgesamt 22; jeder dritte Versuch abgelehnt). */
    fun standardData(w: World) {
        var n = 0
        fun rep(service: UUID, hour: Int, times: Int) = repeat(times) { attempt(w, service, hour, rejected = (n++ % 3 == 0), minute = 5 + it) }
        rep(w.s1, 8, 5); rep(w.s1, 17, 4); rep(w.s2, 8, 6); rep(w.s2, 17, 3); rep(w.s3, 8, 4)
    }

    fun eval(w: World, metric: String, f: MetricFilter = MetricFilter(), period: ReportingPeriod = day, scope: ReportScope = ReportScope.TENANT_WIDE): MetricValue =
        metrics.evaluate(w.tenant, MetricQuery(period, metric, null, f, scope)).single()

    override fun close() { admin.close(); kpi.close() }
}
