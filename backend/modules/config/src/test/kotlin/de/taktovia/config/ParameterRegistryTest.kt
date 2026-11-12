package de.taktovia.config

import de.taktovia.config.api.CreateDraft
import de.taktovia.config.api.ScopeKind
import de.taktovia.config.api.ValidationOutcome
import de.taktovia.platform.problem.DbErrorMapper
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataAccessException
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * WP-M1-19 / LH-I.7-CFG-002, LH-I.7-CFG-004: Parameterkatalog (`policy_schema`) und Systemgrenzen. Echte Datenbank, Runtime-Rolle; Tests, die eine neue
 * Katalogversion brauchen, legen sie in der fernen Zukunft an (gültig ab dem Jahr 2999), damit sie die Version „jetzt“ nie verändern.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParameterRegistryTest {
    private lateinit var fx: ConfigFixture
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))

    @BeforeAll fun up() { fx = ConfigFixture() }
    @AfterAll fun down() = fx.close()

    private val start = listOf(
        "max_wait_min", "max_detour_pct", "min_booking_lead_min", "max_booking_horizon_days", "pickup_window_min", "arrival_grace_min", "no_show_grace_min",
        "stop_walk_max_m", "advance_arrival_buffer_min", "pooling_allowed", "max_reassignments", "protected_stop_horizon_min", "min_capacity_reserve",
        "transfer_min_time_min", "wheelchair_boarding_sec", "stop_dwell_sec", "vehicle_readiness_require_checks", "customer_notify_before_pickup_min",
        "reporting_timezone", "notification_quiet_hours",
    )

    private fun validate(values: Map<String, Any?>, scope: ScopeKind = ScopeKind.TENANT, version: String? = null): ValidationOutcome {
        val ctx = fx.context(fx.tenant())
        return fx.config.parameters.validate(ctx, scope, values, version)
    }

    private fun violations(values: Map<String, Any?>, scope: ScopeKind = ScopeKind.TENANT): List<Violation> =
        (validate(values, scope) as? ValidationOutcome.Invalid)?.violations ?: emptyList()

    private fun draft(values: Map<String, Any?>, tenant: de.taktovia.platform.tenant.TenantId) = fx.config.revisions.createDraft(
        fx.context(tenant), CreateDraft(ScopeKind.TENANT, tenant.value, "1", values, Instant.parse("2026-12-01T00:00:00Z"), null, "Test"))

    // ---- CFG-002 ----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun parameternamen_stimmen_mit_katalog_ueberein() {
        val catalog = fx.config.parameters.catalog(fx.context(fx.tenant()))
        assertEquals("1", catalog.schemaVersion)
        val byName = catalog.parameters.associateBy { it.name }
        assertTrue(byName.keys.containsAll(start), "Startmenge fehlt: ${start - byName.keys}")

        // kataloge.csv (Spalte name_original) ist die Quelle der Namen; Typ/Einheit/Bereich werden dagegen geprüft, nicht nur der Name.
        val rows = csv(repo.resolve("docs/pflichtenheft/kataloge.csv")).filter { it["katalogtyp"] == "Parameter" }.associateBy { it["name_original"]!! }
        val unitOf = mapOf("Minuten" to "MINUTES", "Prozent" to "PERCENT", "Tage" to "DAYS", "Meter" to "METERS", "Sekunden" to "SECONDS", "Anzahl" to "COUNT",
            "Plätze" to "SEATS", "Bool" to "BOOLEAN", "IANA TZ" to "IANA_TZ", "Zeitspanne" to "TIME_SPAN")
        for (name in start) {
            val row = rows[name] ?: throw AssertionError("$name steht nicht in kataloge.csv")
            val p = byName.getValue(name)
            assertEquals(unitOf.getValue(row["typ_oder_nenner_original"]!!), p.unit, "Einheit von $name")
            assertTrue(p.description.isNotBlank() && p.type.isNotBlank(), name)
            Regex("^(\\d+)\\.\\.(\\d+)").find(row["validierung_oder_ausschluss_original"]!!)?.let { m ->   // Katalogbereich gilt als Systemgrenze (OP-03)
                assertEquals(BigDecimal(m.groupValues[1]), p.minimum, "Minimum von $name"); assertEquals(BigDecimal(m.groupValues[2]), p.maximum, "Maximum von $name")
                assertEquals("KATALOG", p.limitSource, name)
            }
            assertEquals(row["definition_original"], p.description, "Beschreibung von $name")
        }
        // Jeder Parameter des Registers (außer der Dienstkonfiguration den vier Gewichten der Mehrzielbewertung (WP-M2-20, OP-18) und den Stornoparametern (WP-M2-26, OP-23), die der Katalog nicht kennt) existiert im Katalog des Pflichtenhefts
        assertEquals(emptySet<String>(), byName.keys - "service_config" - rows.keys - setOf("opt_weight_wait", "opt_weight_detour", "opt_weight_empty", "opt_weight_quality", "cancellation_deadline_min", "cancellation_reasons"))
        // und das JSON-Schema im Register ist das dokumentierte (docs/umsetzungsplan/vertraege/schemas)
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        byName.values.forEach { p ->
            val file = if (p.name == "service_config") "service-config.v1.schema.json" else "${p.name}.v1.schema.json"
            assertEquals(mapper.readTree(Files.readString(repo.resolve("docs/umsetzungsplan/vertraege/schemas/$file"))), mapper.valueToTree(p.schema), file)
        }
    }

    @Test
    fun alle_startparameter_an_den_bereichsgrenzen_gueltig() {
        val catalog = fx.config.parameters.catalog(fx.context(fx.tenant())).parameters.associateBy { it.name }
        for (name in start) {
            val p = catalog.getValue(name)
            val tenantLevel = ScopeKind.TENANT in p.allowedLevels
            assertTrue(tenantLevel, "$name ist auf Mandantenebene nicht setzbar") // Mandantenrahmen: alle Startparameter sind dort zulässig
            when (p.type) {
                "integer" -> listOf(p.minimum!!.toLong(), p.maximum!!.toLong()).forEach { v ->
                    assertEquals(ValidationOutcome.Valid("1", mapOf(name to v)), validate(mapOf(name to v)), "$name=$v")
                    // dieselbe Grenze als Wert mit Einheit
                    assertEquals(ValidationOutcome.Valid("1", mapOf(name to v)), validate(mapOf(name to mapOf("value" to v, "unit" to p.unit))), "$name=$v mit Einheit")
                }
                "boolean" -> assertTrue(validate(mapOf(name to true)) is ValidationOutcome.Valid, name)
            }
        }
        assertTrue(validate(mapOf("pooling_allowed" to false)) is ValidationOutcome.Valid)
        assertTrue(validate(mapOf("reporting_timezone" to "Europe/Berlin", "notification_quiet_hours" to mapOf("from" to "22:00", "to" to "06:00"))) is ValidationOutcome.Valid)
    }

    @Test
    fun werte_ausserhalb_bereich_typ_einheit_unbekannter_name_422() {
        fun one(values: Map<String, Any?>, scope: ScopeKind = ScopeKind.TENANT) = violations(values, scope).single()
        assertEquals(Violation("/max_wait_min", ViolationCode.BELOW_MINIMUM), one(mapOf("max_wait_min" to -5)))
        assertEquals(Violation("/max_wait_min", ViolationCode.ABOVE_MAXIMUM), one(mapOf("max_wait_min" to 241)))
        assertEquals(Violation("/pickup_window_min", ViolationCode.BELOW_MINIMUM), one(mapOf("pickup_window_min" to 0)))   // „Positiv“ (Planungsannahme)
        assertEquals(Violation("/max_wait_min", ViolationCode.TYPE_MISMATCH), one(mapOf("max_wait_min" to "fünf")))
        assertEquals(Violation("/max_wait_min", ViolationCode.TYPE_MISMATCH), one(mapOf("max_wait_min" to 5.5)))
        assertEquals(Violation("/max_wait_min", ViolationCode.TYPE_MISMATCH), one(mapOf("max_wait_min" to true)))
        assertEquals(Violation("/max_wait_min", ViolationCode.TYPE_MISMATCH), one(mapOf("max_wait_min" to null)))
        assertEquals(Violation("/pooling_allowed", ViolationCode.TYPE_MISMATCH), one(mapOf("pooling_allowed" to "ja")))
        assertEquals(Violation("/max_wait_min", ViolationCode.UNIT_MISMATCH), one(mapOf("max_wait_min" to mapOf("value" to 5, "unit" to "SECONDS"))))
        assertEquals(Violation("/erfundener_parameter", ViolationCode.UNKNOWN_FIELD), one(mapOf("erfundener_parameter" to 1)))
        assertEquals(Violation("/reporting_timezone", ViolationCode.UNKNOWN_FIELD), one(mapOf("reporting_timezone" to "Europe/Berlin"), ScopeKind.REGION))   // Ebene Mandant
        assertEquals(Violation("/service_config", ViolationCode.UNKNOWN_FIELD), one(mapOf("service_config" to emptyMap<String, Any?>())))                    // nur Ebene Dienst
        assertEquals(Violation("/reporting_timezone", ViolationCode.ZONE_UNKNOWN), one(mapOf("reporting_timezone" to "Mars/Olympus")))
        assertEquals(Violation("/notification_quiet_hours/to", ViolationCode.REQUIRED), one(mapOf("notification_quiet_hours" to mapOf("from" to "22:00"))))
        assertEquals(Violation("/notification_quiet_hours/from", ViolationCode.PATTERN_MISMATCH), one(mapOf("notification_quiet_hours" to mapOf("from" to "25:00", "to" to "06:00"))))
        assertEquals(Violation("/notification_quiet_hours/x", ViolationCode.UNKNOWN_FIELD), one(mapOf("notification_quiet_hours" to mapOf("from" to "22:00", "to" to "06:00", "x" to 1))))
        // alle Verletzungen werden gesammelt; keine Eingabewerte in den Meldungen
        val many = violations(mapOf("max_wait_min" to -5, "max_detour_pct" to 999, "unbekannt" to 1))
        assertEquals(setOf("/max_wait_min", "/max_detour_pct", "/unbekannt"), many.map { it.pointer }.toSet())
        assertFalse(many.any { it.message.contains("999") || it.message.contains("-5") })

        // als Revisionsentwurf: 422 mit denselben Feldverletzungen, Zeiger unter /values
        val t = fx.tenant()
        val e = assertThrows(ProblemException::class.java) { draft(mapOf("max_wait_min" to 5.5), t) }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code); assertEquals(422, e.code.status)
        assertEquals(listOf(Violation("/values/max_wait_min", ViolationCode.TYPE_MISMATCH)), e.violations)
        assertEquals(0, fx.revisionRows(t))
    }

    @Test
    fun negative_wartezeit_wird_vor_speicherung_abgewiesen() {  // CFG-002 Originalabnahme
        val t = fx.tenant()
        val e = assertThrows(ProblemException::class.java) { draft(mapOf("max_wait_min" to -5), t) }
        assertEquals(422, e.code.status)
        assertEquals(listOf(Violation("/values/max_wait_min", ViolationCode.BELOW_MINIMUM)), e.violations)
        assertEquals(0, fx.revisionRows(t), "keine Zeile in config_revision")
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ?", t.value))
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action LIKE 'config.%'", t.value))
        assertEquals(1, draft(mapOf("max_wait_min" to 0), t).revision, "Gegenprobe: die Grenze 0 ist gültig")
    }

    @Test
    fun datenbank_und_anwendung_pruefen_dieselben_regeln() {
        // Schutz in der Tiefe: dieselben Werte ergeben in der Anwendung (Feldverletzungen) und in der Datenbank (config_value_violations) dieselben Zeiger und Codes.
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        val cases: List<Pair<ScopeKind, Map<String, Any?>>> = listOf(
            ScopeKind.TENANT to mapOf("max_wait_min" to -5), ScopeKind.TENANT to mapOf("max_wait_min" to 241), ScopeKind.TENANT to mapOf("max_wait_min" to "5"),
            ScopeKind.TENANT to mapOf("max_wait_min" to 5.5), ScopeKind.TENANT to mapOf("pooling_allowed" to 1), ScopeKind.TENANT to mapOf("nope" to 1),
            ScopeKind.REGION to mapOf("reporting_timezone" to "Europe/Berlin"), ScopeKind.TENANT to mapOf("reporting_timezone" to "Mars/Olympus"),
            ScopeKind.TENANT to mapOf("vehicle_readiness_require_checks" to false), ScopeKind.TENANT to mapOf("vehicle_readiness_require_checks" to true),
            ScopeKind.TENANT to mapOf("notification_quiet_hours" to mapOf("from" to "22:00")), ScopeKind.TENANT to mapOf("notification_quiet_hours" to mapOf("from" to "22:00", "to" to "06:00", "x" to 1)),
            ScopeKind.TENANT to mapOf("max_wait_min" to 240, "max_detour_pct" to 201, "stop_dwell_sec" to 0), ScopeKind.SERVICE to mapOf("service_config" to mapOf("traffic_type" to "AREA_ON_DEMAND", "nope" to 1)),
        )
        for ((scope, values) in cases) {
            val app = violations(values, scope).map { it.pointer to it.code.name }.toSet()
            val db = fx.adminStrings("SELECT pointer || '|' || code FROM taktovia.config_value_violations(?, '1', ?::jsonb)", scope.name, mapper.writeValueAsString(values))
                .map { it.substringBefore('|') to it.substringAfter('|') }.toSet()
            assertEquals(app, db, "$scope $values")
        }
    }

    // ---- CFG-004 (Wert über dem Sicherheitsmaximum: ParameterRegisterTest) ----------------------------------------------------------------------

    @Test
    fun vehicle_readiness_require_checks_false_abgewiesen() {  // CFG-004 AK 4
        val t = fx.tenant()
        val e = assertThrows(ProblemException::class.java) { draft(mapOf("vehicle_readiness_require_checks" to false), t) }
        assertEquals(listOf(Violation("/values/vehicle_readiness_require_checks", ViolationCode.SAFETY_INVARIANT_VIOLATED)), e.violations)
        assertEquals("Sicherheitsprüfungen dürfen nicht abgeschaltet werden", e.violations.single().message)
        assertEquals(0, fx.revisionRows(t))
        assertEquals(Violation("/vehicle_readiness_require_checks", ViolationCode.TYPE_MISMATCH), violations(mapOf("vehicle_readiness_require_checks" to "false")).single())
        assertTrue(validate(mapOf("vehicle_readiness_require_checks" to true)) is ValidationOutcome.Valid)
        // auch ein Schreibversuch unter Umgehung der Anwendung scheitert in der Datenbank (Schutz in der Tiefe)
        val ex = assertThrows(DataAccessException::class.java) {
            fx.tx.run(t) { j -> j.update("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) " +
                "VALUES (taktovia.current_tenant(),?, 'TENANT', ?, 1, '1', '{\"vehicle_readiness_require_checks\": false}'::jsonb, now(), 'user:x', 'Test')", UUID.randomUUID(), t.value) }
        }
        assertEquals(ErrorCode.VALIDATION_FAILED, DbErrorMapper.map(ex).code)
        assertEquals(0, fx.revisionRows(t))
    }

    @Test
    fun mandant_kann_schema_und_systemgrenze_nicht_aendern_403() {
        val t = fx.tenant()
        val before = fx.adminStrings("SELECT json_schema::text FROM taktovia.policy_schema WHERE schema_version = '1' AND parameter = 'max_wait_min'").single()
        val attempts = listOf(
            "UPDATE taktovia.policy_schema SET json_schema = jsonb_set(json_schema, '{maximum}', '99999') WHERE parameter = 'max_wait_min'",
            "DELETE FROM taktovia.policy_schema WHERE parameter = 'vehicle_readiness_require_checks'",
            "INSERT INTO taktovia.policy_schema(schema_version, parameter, json_schema) VALUES ('1', 'eigener_parameter', '{}'::jsonb)",
            "INSERT INTO taktovia.policy_schema_version(schema_version, valid_from, description) VALUES ('2', now(), 'vom Mandanten angehoben')",
        )
        for (sql in attempts) {
            val ex = assertThrows(DataAccessException::class.java, { fx.tx.run(t) { j -> j.update(sql) } }, sql)
            assertEquals(ErrorCode.FORBIDDEN, DbErrorMapper.map(ex).code, sql)
            assertEquals(403, DbErrorMapper.map(ex).code.status)
        }
        assertEquals(before, fx.adminStrings("SELECT json_schema::text FROM taktovia.policy_schema WHERE schema_version = '1' AND parameter = 'max_wait_min'").single())
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.policy_schema_version WHERE schema_version = '1'"))
        // Lesen bleibt jedem Mandanten möglich
        assertTrue(fx.config.parameters.catalog(fx.context(t, roles = setOf("passenger"), scopes = setOf("config:read"))).parameters.isNotEmpty())
    }

    @Test
    fun schemaversion_parallel_zu_entwurf_wird_neu_validiert() {
        // Entwurf unter Version 1: max_wait_min = 200 ist gültig (Systemgrenze 240).
        val t = fx.tenant()
        val ctx = fx.context(t)
        val d = fx.config.revisions.createDraft(ctx, CreateDraft(ScopeKind.TENANT, t.value, "1", mapOf("max_wait_min" to 200), Instant.parse("2026-12-01T00:00:00Z"), null, "Wartezeit"))
        assertTrue(fx.config.revisions.revalidate(ctx, d.id).valid)

        // Die Plattform hebt die Schema-Version an (Migration; hier als Administrator in der fernen Zukunft, Systemgrenze für max_wait_min jetzt 120).
        val raised = (fx.adminCount("SELECT max(schema_version::int) FROM taktovia.policy_schema_version") + 1).toString()   // höher als jede vorhandene, auch aus früheren Läufen
        fx.adminUpdate("INSERT INTO taktovia.policy_schema_version(schema_version, valid_from, description) VALUES (?, '2999-01-01T00:00:00Z', 'Testversion: strengere Wartezeit')", raised)
        fx.adminUpdate("INSERT INTO taktovia.policy_schema(schema_version, parameter, json_schema) SELECT ?, parameter, " +
            "CASE WHEN parameter = 'max_wait_min' THEN jsonb_set(json_schema, '{maximum}', '120') ELSE json_schema END FROM taktovia.policy_schema WHERE schema_version = '1'", raised)

        // Zur Zeit der Publikation (hier: Uhr im Jahr 3000) gilt die neue Version: der Entwurf wird gegen sie erneut validiert und verworfen.
        val future = ConfigFixtureClock.at("3000-01-01T00:00:00Z")
        val api3000 = de.taktovia.config.api.ConfigApi.create(fx.ctxTx, fx.audit, fx.outbox, future)
        val report = api3000.revisions.revalidate(ctx, d.id)
        assertEquals(raised, report.checkedAgainstVersion)
        assertFalse(report.valid)
        assertEquals(listOf(Violation("/max_wait_min", ViolationCode.ABOVE_MAXIMUM)), report.violations)
        // Jetzt (Version 1) bleibt er gültig; der gespeicherte Entwurf wurde nicht verändert.
        assertTrue(fx.config.revisions.revalidate(ctx, d.id).valid)
        assertEquals("1", fx.config.revisions.get(ctx, d.id).schemaVersion)
        assertEquals("1", fx.config.parameters.catalog(ctx).schemaVersion, "die Version „jetzt“ bleibt 1")
        // Neue Entwürfe unter der Version 1 sind in der Zukunft nicht mehr zulässig, solche unter der neuen schon.
        assertThrows(ProblemException::class.java) {
            api3000.revisions.createDraft(ctx, CreateDraft(ScopeKind.TENANT, t.value, "1", mapOf("max_wait_min" to 100), Instant.parse("3000-02-01T00:00:00Z"), null, "veraltete Version"))
        }
        assertEquals(raised, api3000.revisions.createDraft(ctx, CreateDraft(ScopeKind.TENANT, t.value, raised, mapOf("max_wait_min" to 100), Instant.parse("3000-02-01T00:00:00Z"), null, "neue Version")).schemaVersion)
    }

    // ---- Hilfen ----------------------------------------------------------------------------------------------------------------------------

    /** Minimaler CSV-Leser (RFC 4180, Trenner `;`, Felder mit Anführungszeichen und Zeilenumbrüchen) für kataloge.csv. */
    private fun csv(path: Path): List<Map<String, String>> {
        val text = Files.readString(path); val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val f = StringBuilder(); var q = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                q && c == '"' && text.getOrNull(i + 1) == '"' -> { f.append('"'); i++ }
                c == '"' -> q = !q
                !q && c == ';' -> { row += f.toString(); f.clear() }
                !q && (c == '\n' || c == '\r') -> { if (c == '\r' && text.getOrNull(i + 1) == '\n') i++; row += f.toString(); f.clear(); rows += row; row = mutableListOf() }
                else -> f.append(c)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) { row += f.toString(); rows += row }
        val header = rows.first()
        return rows.drop(1).filter { it.size == header.size }.map { header.zip(it).toMap() }
    }
}

/** Feste Uhr für Tests, die „jetzt“ in der fernen Zukunft brauchen (Schema-Versionen mit Gültigkeit ab dem Jahr 2999). */
object ConfigFixtureClock { fun at(iso: String): Clock = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC) }
