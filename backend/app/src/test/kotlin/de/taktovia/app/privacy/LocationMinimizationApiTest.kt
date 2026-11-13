package de.taktovia.app.privacy

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import de.taktovia.TaktoviaTestSupport
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.app.probe.Resp
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * WP-M5-20 / AT-59 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer, Datenbank): Standortminimierung beim Stempeln und Freigabe der Geozone in der Konfiguration.
 *
 * - AK 1: ohne Freigabe trägt kein Zeitereignis einen Standort (kein Standortfeld in Tabelle und Antwort). Die Standortberechtigung der App ist **nicht ausgeführt** (kein Flutter, siehe `mobile/`).
 * - Negativ: sendet ein Client trotz deaktivierter Geozone Koordinaten, nimmt der Server den Stempel an und verwirft sie; die Koordinaten stehen weder in einer Tabelle der Datenbank
 *   (Volltextsuche über alle Tabellen) noch in Antwort, Ausgangsnachrichten (Outbox), Audit oder Logs (Prüfung wie im Skript `tools/log_stichprobe.py` aus WP-M5-06: Mustersuche, kein Klartext im Befund).
 * - AK 2: die Aktivierung der Geozone ohne dokumentierte Freigabe weist die Konfigurationsvalidierung ab; mit Freigabeverweis ist sie freigeb- und publizierbar.
 * - Konkurrierend (Sicherheit): ein Konfigurationswechsel der Geozone während der laufenden Schicht wirkt erst ab dem Wirksamkeitszeitpunkt, nie früher.
 */
class LocationMinimizationApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())
    private val lat = "51.050407"; private val lon = "13.737262"

    private class Driver(val env: IdentityEnv) {
        val tenant = env.tenant("Standort-" + UUID.randomUUID()); val driver = UUID.randomUUID()
        init { env.grant(tenant, driver, "driver") }
        val token = env.token(driver)
    }

    private fun body(kind: String, at: String, id: UUID = UUID.randomUUID(), extra: String = "") =
        """{"event_id":"$id","kind":"$kind","occurred_at":"$at","device_recorded_at":"$at","channel":"APP","shift_id":null$extra}"""

    private fun adminRows(sql: String, vararg args: Any?): Int = TaktoviaTestSupport.adminDataSourceOrNull()!!.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { it.next(); it.getInt(1) } }
    }

    /** Anzahl Zeilen über **alle** Tabellen des Schemas `taktovia`, deren JSON-Darstellung [needle] enthält. */
    private fun rowsContaining(needle: String): Int = TaktoviaTestSupport.adminDataSourceOrNull()!!.connection.use { c ->
        c.createStatement().use { it.execute("SET row_security = off") }
        val tables = c.createStatement().use { st -> st.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema='taktovia' AND table_type='BASE TABLE'").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
        tables.sumOf { t -> c.prepareStatement("SELECT count(*) FROM taktovia.\"$t\" x WHERE to_jsonb(x)::text LIKE ?").use { ps -> ps.setString(1, "%$needle%"); ps.executeQuery().use { it.next(); it.getInt(1) } } }   // sql-lint: ok Tabellenname aus information_schema, Suchwert als Parameter
    }

    @Test
    fun stempeln_ohne_standort_kein_standortfeld_in_zeitereignis_und_antwort() {
        val w = Driver(env)
        listOf("WORK_START" to "06:00", "BREAK_START" to "10:00", "BREAK_END" to "10:30", "WORK_END" to "14:00").forEach { (k, t) ->
            val r = env.post("/v1/time-events", body(k, "2026-12-01T$t:00Z"), w.token, extra = idem())
            assertEquals(201, r.status, r.text)
            assertTrue(r.json.propertyNames().none { it.contains("lat") || it.contains("lon") || it.contains("location") || it.contains("geo") }, "Antwortfelder: ${r.json.propertyNames()}")
        }
        // Struktur: die Tabelle hat gar kein Standortfeld (in P1 ist die Geozone aus; das standortgestützte Stempeln ist P2, WP-M7d-40)
        assertEquals(0, adminRows("SELECT count(*) FROM information_schema.columns WHERE table_schema='taktovia' AND table_name='time_event' AND column_name ~* '(lat|lon|geo|location|position|gps|coord)'"))
        assertEquals(4, env.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=?", w.tenant.value))
    }

    @Test
    fun koordinaten_trotz_deaktivierter_geozone_werden_verworfen_nirgends_gespeichert_und_nie_geloggt() {
        val w = Driver(env)
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger; val app = LoggerFactory.getLogger("de.taktovia") as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }; val old = app.level
        root.addAppender(appender); app.level = Level.DEBUG
        val responses = mutableListOf<Resp>()
        try {
            val loc = ""","location":{"latitude":$lat,"longitude":$lon,"accuracy_m":7.5}"""
            responses += env.post("/v1/time-events", body("WORK_START", "2026-12-01T06:00:00Z", extra = loc), w.token, extra = idem())
            responses += env.post("/v1/time-events", body("WORK_END", "2026-12-01T14:00:00Z", extra = loc), w.token, extra = idem())
            // Kontrolle: das Wiederholen mit denselben Koordinaten ist idempotent und bleibt ohne Standort
            val id = UUID.randomUUID()
            responses += env.post("/v1/time-events", body("WORK_START", "2026-12-02T06:00:00Z", id, loc), w.token, extra = idem())
            responses += env.post("/v1/time-events", body("WORK_START", "2026-12-02T06:00:00Z", id, loc), w.token, extra = idem())
            // Fehlerpfade mit Koordinaten im Body: abgewiesener Stempel für eine fremde Person (403), ungültiges Token (401), kaputtes JSON, unzulässiges Standortfeld (422)
            responses += env.post("/v1/time-events", body("WORK_START", "2026-12-03T06:00:00Z", extra = loc + ""","person_ref":"user:fremd""""), w.token, extra = idem())
            responses += env.post("/v1/time-events", body("WORK_START", "2026-12-03T06:00:00Z", extra = loc), w.token.dropLast(5) + "AAAAA", extra = idem())
            responses += env.post("/v1/time-events", """{"event_id":"x","location":{"latitude":$lat,"longitude":$lon""", w.token, extra = idem())
            responses += env.post("/v1/time-events", body("WORK_START", "2026-12-03T06:00:00Z", extra = ""","latitude":$lat,"longitude":$lon"""), w.token, extra = idem())
        } finally { root.detachAppender(appender); app.level = old }
        assertEquals(listOf(201, 201, 201, 200), responses.take(4).map { it.status }, responses.joinToString { it.text })
        assertTrue(responses.drop(4).all { it.status in 400..499 }, "Fehlerpfade: ${responses.drop(4).map { it.status }}")
        responses.forEach { assertFalse(lat in it.text || lon in it.text, "Antwort ohne Koordinaten") }
        assertEquals(3, env.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=?", w.tenant.value))
        // nirgends in der Datenbank: Volltextsuche über alle Tabellen (Zeitereignis, Outbox, Audit, Idempotenzregister, Sicherheitsereignisse ...)
        assertTrue(rowsContaining(w.driver.toString()) > 0, "Positivkontrolle: die Suche sieht die mandantengebundenen Zeilen (sonst wäre der Nachweis leer)")
        assertEquals(0, rowsContaining(lat), "Breitengrad in der Datenbank"); assertEquals(0, rowsContaining(lon), "Längengrad in der Datenbank")
        // nicht in Logs und Traces des Prozesses (Logzeilen: Nachricht, Argumente, Ausnahme, MDC)
        val lines = appender.list.map { e -> e.formattedMessage + " " + (e.argumentArray?.joinToString(" ") ?: "") + " " + (e.throwableProxy?.let { ThrowableProxyUtil.asString(it) } ?: "") + " " + e.mdcPropertyMap.values.joinToString(" ") }
        assertTrue(lines.isNotEmpty(), "der Test belegt nichts, wenn nichts mitgeschnitten wurde")
        assertTrue(lines.none { lat in it || lon in it }, "Koordinaten in einer Logzeile")
    }

    @Test
    fun unbekannte_standortfelder_ausserhalb_des_dokumentierten_blocks_werden_mit_422_abgewiesen_und_nichts_gespeichert() {
        val w = Driver(env)
        val r = env.post("/v1/time-events", body("WORK_START", "2026-12-01T06:00:00Z", extra = ""","latitude":$lat,"longitude":$lon"""), w.token, extra = idem())
        assertEquals(422, r.status, r.text)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=?", w.tenant.value))
        assertFalse(lat in r.text, "die Fehlerantwort gibt den Wert nicht zurück")
        assertEquals(0, rowsContaining(lat))
    }

    // ---- Konfiguration: Geozone nur mit dokumentierter Freigabe, wirksam erst zum Wirksamkeitszeitpunkt ----

    private class Team(val tenant: TenantId, val alice: String, val bob: String)
    private fun team(): Team { val t = env.tenant(); val a = UUID.randomUUID(); val b = UUID.randomUUID(); env.grant(t, a, "tenant_admin"); env.grant(t, b, "planner"); return Team(t, env.token(a), env.token(b)) }
    private fun call(token: String, path: String, ifMatch: Long?, body: String? = null): Resp =
        env.server.send("POST", path, body, env.headers(token, extra = idem() + (if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap())))
    private fun impact(t: Team, id: String): String = call(t.alice, "/v1/config/revisions/$id/impact", 1).also { assertEquals(200, it.status, it.text) }.json["id"].asString()
    private fun draft(t: Team, values: String, from: Instant): Resp = env.post("/v1/config/revisions",
        """{"scope_kind":"TENANT","scope_id":"${t.tenant}","schema_version":"1","values":$values,"effective_from":"$from","reason":"Geozone"}""", t.alice, extra = idem())
    private fun effective(t: Team, at: Instant): Resp = env.get("/v1/config/effective?parameter=clock_in_geofence_enabled&at=$at", t.alice)

    @Test
    fun aktivierung_der_geozone_ohne_dokumentierte_freigabe_wird_von_der_validierung_abgewiesen() {
        val t = team(); val from = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        for (values in listOf("""{"clock_in_geofence_enabled":true}""", """{"clock_in_geofence_enabled":true,"clock_in_geofence_release_ref":"   "}""")) {
            val d = draft(t, values, from)
            if (d.status != 201 && d.status != 200) { assertEquals(422, d.status, d.text); continue }   // Katalog lässt den Wert zu; die Freigaberegel greift bei der Validierung
            val id = d.json["id"].asString()
            val v = call(t.alice, "/v1/config/revisions/$id/validate", 1)
            assertTrue(v.status == 422 || (v.status == 200 && !v.json["valid"].asBoolean()), "Validierung muss abweisen: ${v.status} ${v.text}")
            assertTrue(v.text.contains("clock_in_geofence_release_ref"), v.text)
            assertEquals("DRAFT", env.get("/v1/config/revisions/$id", t.alice).json["state"].asString(), "nicht validiert")
            assertEquals(409, call(t.bob, "/v1/config/revisions/$id/approve", 1).status, "ein nicht validierter Entwurf ist nicht freigebbar")
        }
        // ausgeschaltet braucht keinen Verweis
        val off = draft(t, """{"clock_in_geofence_enabled":false}""", from)
        assertEquals(201, off.status, off.text)
        val v = call(t.alice, "/v1/config/revisions/${off.json["id"].asString()}/validate", 1)
        assertEquals(200, v.status, v.text); assertTrue(v.json["valid"].asBoolean())
    }

    @Test
    fun konfigurationswechsel_der_geozone_wirkt_erst_ab_dem_wirksamkeitszeitpunkt() {
        val t = team(); val now = Instant.now().truncatedTo(ChronoUnit.SECONDS); val from = now.plus(2, ChronoUnit.DAYS)
        // Ausgangslage: aus (publiziert, wirksam ab jetzt)
        fun publish(values: String, at: Instant): String {
            val d = draft(t, values, at); assertEquals(201, d.status, d.text); val id = d.json["id"].asString()
            val rep = impact(t, id)
            val v = call(t.alice, "/v1/config/revisions/$id/validate", 1); assertEquals(200, v.status, v.text); assertTrue(v.json["valid"].asBoolean(), v.text)
            assertEquals(200, call(t.bob, "/v1/config/revisions/$id/approve", 2).status)
            val p = call(t.bob, "/v1/config/revisions/$id/publish", 3, """{"effective_from":"$at","reason":"Geozone","impact_report_id":"$rep"}""")
            assertEquals(200, p.status, p.text); return id
        }
        publish("""{"clock_in_geofence_enabled":false}""", now)
        assertEquals(false, effective(t, now.plusSeconds(60)).json["values"]["clock_in_geofence_enabled"].asBoolean())
        // Wechsel auf „an“ mit Freigabeverweis, wirksam erst in zwei Tagen (die Schicht läuft heute)
        publish("""{"clock_in_geofence_enabled":true,"clock_in_geofence_release_ref":"BV-2026-017 Zweckbindung Zeiterfassung"}""", from)
        assertEquals(false, effective(t, now.plusSeconds(3600)).json["values"]["clock_in_geofence_enabled"].asBoolean(), "während der laufenden Schicht unverändert aus")
        assertEquals(false, effective(t, from.minusSeconds(1)).json["values"]["clock_in_geofence_enabled"].asBoolean(), "eine Sekunde vor dem Wirksamkeitszeitpunkt noch aus")
        assertEquals(true, effective(t, from).json["values"]["clock_in_geofence_enabled"].asBoolean(), "ab dem Wirksamkeitszeitpunkt an")
        // Stempeln bleibt davon unberührt: in P1 wird auch bei aktiver Geozone kein Standort gespeichert (Speicherung ist P2, WP-M7d-40)
        val w = Driver(env)
        val r = env.post("/v1/time-events", body("WORK_START", "2026-12-01T06:00:00Z", extra = ""","location":{"latitude":$lat,"longitude":$lon}"""), w.token, extra = idem())
        assertEquals(201, r.status, r.text); assertEquals(0, rowsContaining(lat))
    }
}
