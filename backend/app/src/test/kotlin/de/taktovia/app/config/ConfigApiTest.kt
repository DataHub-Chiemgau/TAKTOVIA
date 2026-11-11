package de.taktovia.app.config

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.app.probe.Resp
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M1-19/-20 über die echte Anwendung (Ressourcenserver mit Test-Issuer, echte Datenbank, Runtime-Rolle): Parameterkatalog, Entwürfe, Änderungsprotokoll,
 * As-of-Rekonstruktion, Fehlermodell (422 mit Feldverletzungen, 403, 404, 409, 412, 428). Mandanten und Veröffentlichung (WP-M1-22) legt der Testaufbau direkt an.
 */
class ConfigApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "cfg-" + UUID.randomUUID())

    private class Actor(val tenant: TenantId, val subject: UUID, val token: String)

    private fun admin(tenant: TenantId = env.tenant(), role: String = "tenant_admin"): Actor {
        val sub = UUID.randomUUID(); env.grant(tenant, sub, role); return Actor(tenant, sub, env.token(sub))
    }

    private fun create(a: Actor, body: String): Resp = env.post("/v1/config/revisions", body, a.token, extra = idem())
    private fun body(a: Actor, values: String, scopeKind: String = "TENANT", scopeId: UUID = a.tenant.value, extra: String = ""","reason":"Wartezeit angepasst"""") =
        """{"scope_kind":"$scopeKind","scope_id":"$scopeId","schema_version":"1","values":$values,"effective_from":"2026-12-01T00:00:00Z"$extra}"""

    private fun publish(t: TenantId, id: String) {
        for ((state, extra) in listOf("VALIDATED" to "", "APPROVED" to ", approved_by = 'user:zweite-person'", "PUBLISHED" to ", published_at = clock_timestamp()"))
            env.tx.run(t) { j ->
                if (state == "PUBLISHED") j.update("UPDATE taktovia.config_revision p SET effective_to = n.effective_from FROM taktovia.config_revision n WHERE n.id = ? AND p.tenant_id = n.tenant_id AND p.scope_kind = n.scope_kind AND p.scope_id = n.scope_id AND p.state = 'PUBLISHED' AND p.effective_to IS NULL AND p.effective_from < n.effective_from", UUID.fromString(id))
                j.update("UPDATE taktovia.config_revision SET state = ?$extra WHERE id = ?", state, UUID.fromString(id))
            }
    }

    @Test
    fun parameterkatalog_ist_lesbar_und_nicht_aenderbar() {
        val a = admin(role = "dispatcher")
        val r = env.get("/v1/config/parameters", a.token)
        assertEquals(200, r.status, r.text)
        assertEquals("1", r.json["schema_version"].asString())
        val items = r.json["items"].values().associateBy { it["name"].asString() }
        assertTrue(items.keys.containsAll(listOf("max_wait_min", "vehicle_readiness_require_checks", "reporting_timezone", "service_config")))
        items.getValue("max_wait_min").let {
            assertEquals(0, it["minimum"].asInt()); assertEquals(240, it["maximum"].asInt()); assertEquals("MINUTES", it["unit"].asString()); assertEquals("integer", it["type"].asString())
            assertEquals("Maximale Wartezeit", it["description"].asString()); assertEquals("KATALOG", it["limit_source"].asString())
        }
        assertEquals("SAFETY_INVARIANT", items.getValue("vehicle_readiness_require_checks")["security_class"].asString())
        // jeder Mandant liest denselben Katalog; ohne Leserecht (Fahrgast) nicht
        assertEquals(r.json["items"].size(), env.get("/v1/config/parameters", admin().token).json["items"].size())
        assertEquals(403, env.get("/v1/config/parameters", admin(role = "passenger").token).status)
        assertEquals(401, env.server.get("/v1/config/parameters").status)

        // Ein Mandant kann Schema und Systemgrenze nicht ändern: es gibt keinen schreibenden Weg
        val t = admin()
        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
            val x = env.server.send(method, "/v1/config/parameters", """{"name":"max_wait_min","maximum":99999}""", env.headers(t.token, extra = idem() + ("If-Match" to "\"1\"")))
            assertTrue(x.status in listOf(404, 405), "$method: ${x.status}")
        }
        assertEquals(240, env.get("/v1/config/parameters", t.token).json["items"].values().first { it["name"].asString() == "max_wait_min" }["maximum"].asInt())
    }

    @Test
    fun unzulaessige_werte_werden_mit_422_und_feldverletzung_abgewiesen_ohne_zeile() {
        val a = admin()
        fun rejected(values: String, code: String, pointer: String) {
            val r = create(a, body(a, values))
            assertEquals(422, r.status, r.text); assertTrue(r.header("Content-Type")!!.startsWith("application/problem+json"))
            assertEquals("VALIDATION_FAILED", r.json["code"].asString())
            val v = r.json["violations"].values().single()
            assertEquals(pointer, v["pointer"].asString()); assertEquals(code, v["code"].asString())
        }
        rejected("""{"max_wait_min":-5}""", "BELOW_MINIMUM", "/values/max_wait_min")                       // CFG-002 Abnahme
        rejected("""{"max_wait_min":241}""", "ABOVE_MAXIMUM", "/values/max_wait_min")                       // CFG-004 Abnahme, durch den Mandantenadministrator
        rejected("""{"vehicle_readiness_require_checks":false}""", "SAFETY_INVARIANT_VIOLATED", "/values/vehicle_readiness_require_checks")
        rejected("""{"max_wait_min":"zehn"}""", "TYPE_MISMATCH", "/values/max_wait_min")
        rejected("""{"max_wait_min":{"value":5,"unit":"SECONDS"}}""", "UNIT_MISMATCH", "/values/max_wait_min")
        rejected("""{"erfunden":1}""", "UNKNOWN_FIELD", "/values/erfunden")
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id=?", a.tenant.value), "keine Zeile in config_revision")
        assertEquals(0, env.events(a.tenant, "config.revision.created"))
        assertEquals(201, create(a, body(a, """{"max_wait_min":{"value":240,"unit":"MINUTES"}}""")).status, "Grenze mit Einheit")
    }

    @Test
    fun pflichtangaben_ersteller_und_fehlerfaelle() {
        val a = admin(); val sub = a.subject
        val ok = create(a, body(a, """{"max_wait_min":20}"""))
        assertEquals(201, ok.status, ok.text)
        assertEquals("\"1\"", ok.header("ETag")); assertEquals("/v1/config/revisions/${ok.json["id"].asString()}", ok.header("Location"))
        assertEquals("user:$sub", ok.json["created_by"].asString(), "Ersteller aus dem Token")
        assertEquals("DRAFT", ok.json["state"].asString()); assertEquals(1, ok.json["revision"].asInt()); assertEquals("Wartezeit angepasst", ok.json["reason"].asString())
        assertEquals("2026-12-01T00:00:00Z", ok.json["effective_from"].asString()); assertTrue(ok.json["effective_to"].isNull); assertTrue(ok.json["created_at"].asString().isNotEmpty())
        assertEquals(1, env.events(a.tenant, "config.revision.created"))

        fun code(r: Resp, status: Int, code: String, pointer: String? = null) {
            assertEquals(status, r.status, r.text); assertEquals(code, r.json["code"].asString())
            if (pointer != null) assertEquals(pointer, r.json["violations"].values().first()["pointer"].asString())
        }
        code(create(a, body(a, """{"max_wait_min":20}""", extra = "")), 422, "VALIDATION_FAILED", "/reason")                                     // Grund fehlt
        code(create(a, body(a, """{"max_wait_min":20}""", extra = ""","reason":"ab"""")), 422, "VALIDATION_FAILED", "/reason")
        code(create(a, """{"scope_kind":"TENANT","scope_id":"${a.tenant.value}","schema_version":"1","values":{"max_wait_min":20},"reason":"Grund"}"""), 422, "VALIDATION_FAILED", "/effective_from") // Beginn fehlt
        code(create(a, body(a, """{"max_wait_min":20}""", extra = ""","reason":"Grund","effective_to":"2026-11-01T00:00:00Z"""")), 422, "VALIDATION_FAILED", "/effective_to")
        code(create(a, body(a, """{"max_wait_min":20}""", extra = ""","reason":"Grund","created_by":"user:mallory"""")), 422, "VALIDATION_FAILED", "/created_by")   // Ersteller nie aus der Anfrage
        code(create(a, body(a, """{"max_wait_min":20}""", extra = ""","reason":"Grund","tenant_id":"${UUID.randomUUID()}"""")), 404, "NOT_FOUND")   // Mandant nie aus dem Body: 404 und Sicherheitsereignis (WP-M1-06)
        assertEquals(1, env.events(a.tenant, "security.tenant.mismatch"))
        code(create(a, body(a, """{"max_wait_min":20}""", scopeId = UUID.randomUUID())), 404, "NOT_FOUND")                                         // Mandantenscope eines anderen
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id=?", a.tenant.value))

        val passenger = admin(a.tenant, "passenger")
        assertEquals(403, create(passenger, body(a, """{"max_wait_min":20}""")).status, "Rolle ohne Recht")
        assertEquals(403, env.get("/v1/config/revisions", passenger.token).status)
        assertEquals(401, env.post("/v1/config/revisions", body(a, "{}"), "ungültig", extra = idem()).status)
        val reader = admin(a.tenant, "dispatcher")   // Leserecht, kein Schreibrecht
        assertEquals(403, create(reader, body(a, """{"max_wait_min":20}""")).status)
        assertEquals(200, env.get("/v1/config/revisions/${ok.json["id"].asString()}", reader.token).status)
    }

    @Test
    fun dienstkonfiguration_ueber_die_api() {
        val a = admin(); val service = UUID.randomUUID()
        val cfg = """{"service_area_id":"${UUID.randomUUID()}","traffic_type":"LINE_ON_DEMAND","operator_id":"${UUID.randomUUID()}","operating_hours":[{"weekdays":["MON","FRI"],"from":"05:30","to":"24:00"}],"tariff_id":"${UUID.randomUUID()}"}"""
        val ok = create(a, body(a, """{"service_config":$cfg,"max_wait_min":15}""", "SERVICE", service))
        assertEquals(201, ok.status, ok.text)
        assertEquals(5, ok.json["values"]["service_config"].size(), "alle fünf Bestandteile derselben Revision")
        val missing = create(a, body(a, """{"max_wait_min":15}""", "SERVICE", service))
        assertEquals(422, missing.status); assertEquals("/values/service_config", missing.json["violations"].values().first()["pointer"].asString())
        val partial = create(a, body(a, """{"service_config":${cfg.replace(Regex(",\"tariff_id\":\"[^\"]+\""), "")}}""", "SERVICE", service))
        assertEquals(422, partial.status); assertEquals("REQUIRED", partial.json["violations"].values().first()["code"].asString())
    }

    @Test
    fun entwurf_aendern_mit_if_match_412_428_und_nach_publikation_409() {
        val a = admin()
        val c = create(a, body(a, """{"max_wait_min":20,"max_detour_pct":30}""")); val id = c.json["id"].asString()
        fun patch(json: String, ifMatch: String?, token: String = a.token) = env.server.send("PATCH", "/v1/config/revisions/$id", json,
            env.headers(token, extra = idem() + (if (ifMatch != null) mapOf("If-Match" to ifMatch) else emptyMap())))

        assertEquals(428, patch("""{"reason":"Neuer Grund"}""", null).status)
        assertEquals(412, patch("""{"reason":"Neuer Grund"}""", "\"7\"").status)
        val ok = patch("""{"values":{"max_wait_min":25},"reason":"Neuer Grund"}""", "\"1\"")
        assertEquals(200, ok.status, ok.text); assertEquals("\"2\"", ok.header("ETag"))
        assertEquals(25, ok.json["values"]["max_wait_min"].asInt()); assertFalse(ok.json["values"].has("max_detour_pct")); assertEquals("Neuer Grund", ok.json["reason"].asString())
        assertEquals(1, ok.json["revision"].asInt(), "die Revisionsnummer bleibt, die Zeilenversion steigt")
        assertEquals(412, patch("""{"reason":"Veraltet"}""", "\"1\"").status)
        assertEquals(422, patch("""{"values":{"max_wait_min":241}}""", "\"2\"").status)
        assertEquals(422, patch("""{"effective_to":"2026-01-01T00:00:00Z"}""", "\"2\"").status)
        assertEquals(422, patch("""{}""", "\"2\"").status)
        val noEnd = patch("""{"effective_to":null}""", "\"2\"")
        assertEquals(200, noEnd.status, noEnd.text); assertTrue(noEnd.json["effective_to"].isNull)
        assertEquals(1, env.events(a.tenant, "config.revision.changed") - 1, "zwei Änderungen auditiert (Werte, Ende)")
        assertEquals(404, patch("""{"reason":"Fremd"}""", "\"1\"", admin().token).status, "fremder Mandant")
        assertEquals(403, patch("""{"reason":"Zu schwach"}""", "\"3\"", admin(a.tenant, "dispatcher").token).status)

        publish(a.tenant, id)
        val after = env.get("/v1/config/revisions/$id", a.token)
        assertEquals("PUBLISHED", after.json["state"].asString())
        val locked = patch("""{"reason":"Heimlich"}""", after.header("ETag"))
        assertEquals(409, locked.status); assertEquals("IMMUTABLE_RECORD", locked.json["code"].asString())
        assertEquals("Neuer Grund", env.get("/v1/config/revisions/$id", a.token).json["reason"].asString())
    }

    @Test
    fun liste_diff_und_as_of_rekonstruktion() {
        val a = admin(); val other = admin()
        val ids = (1..3).map { n ->
            val r = create(a, """{"scope_kind":"TENANT","scope_id":"${a.tenant.value}","schema_version":"1","values":{"max_wait_min":${n * 10}},"effective_from":"2026-0${n * 2}-01T00:00:00Z","reason":"Stand $n"}""")
            assertEquals(201, r.status, r.text); r.json["id"].asString().also { publish(a.tenant, it) }
        }
        val list = env.get("/v1/config/revisions?scope_kind=TENANT&scope_id=${a.tenant.value}&limit=2", a.token)
        assertEquals(200, list.status, list.text); assertEquals(2, list.json["items"].size()); assertTrue(list.json["next_cursor"].isString)
        val rest = env.get("/v1/config/revisions?scope_kind=TENANT&scope_id=${a.tenant.value}&limit=2&cursor=${list.json["next_cursor"].asString()}", a.token)
        assertEquals(listOf(ids[2]), rest.json["items"].values().map { it["id"].asString() })
        // Änderungsprotokoll: Benutzer, Zeitpunkt, Grund, Gültigkeitsbeginn, Altwert/Neuwert
        val r3 = env.get("/v1/config/revisions/${ids[2]}", a.token).json["change_log"]
        assertEquals("user:${a.subject}", r3["changed_by"].asString()); assertEquals("Stand 3", r3["reason"].asString()); assertEquals("2026-06-01T00:00:00Z", r3["effective_from"].asString())
        assertEquals(ids[1], r3["predecessor_id"].asString()); assertEquals(2, r3["predecessor_revision"].asInt())
        r3["changes"].values().single().let { assertEquals("/max_wait_min", it["pointer"].asString()); assertEquals("CHANGED", it["kind"].asString()); assertEquals(20, it["old_value"].asInt()); assertEquals(30, it["new_value"].asInt()) }
        // As-of: die damals wirksame Revision samt ID
        fun asOf(at: String) = env.get("/v1/config/revisions?scope_kind=TENANT&scope_id=${a.tenant.value}&as_of=$at", a.token)
        assertEquals(listOf(ids[0]), asOf("2026-03-15T00:00:00%2B01:00").json["items"].values().map { it["id"].asString() })
        assertEquals(listOf(ids[1]), asOf("2026-05-31T23:59:59Z").json["items"].values().map { it["id"].asString() })
        assertEquals(listOf(ids[2]), asOf("2027-01-01T00:00:00Z").json["items"].values().map { it["id"].asString() })
        assertEquals(0, asOf("2026-01-31T23:59:59Z").json["items"].size(), "vor der ersten Revision wirkte keine")
        assertEquals(422, env.get("/v1/config/revisions?as_of=2026-03-15T00:00:00Z", a.token).status, "as_of verlangt scope_kind und scope_id")
        assertEquals(422, env.get("/v1/config/revisions?scope_kind=TENANT&scope_id=${a.tenant.value}&as_of=2026-03-15T00:00:00", a.token).status, "ohne Offset")
        assertEquals(422, env.get("/v1/config/revisions?scope_kind=GALAXIE", a.token).status)
        // Mandantentrennung: fremder Mandant sieht nichts und kann nichts lesen
        assertEquals(0, env.get("/v1/config/revisions", other.token).json["items"].size())
        assertEquals(404, env.get("/v1/config/revisions/${ids[0]}", other.token).status)
        assertNotEquals(0, env.get("/v1/config/revisions", a.token).json["items"].size())
        assertEquals(0, asOfOther(other, a, "2027-01-01T00:00:00Z"))
    }

    private fun asOfOther(o: Actor, a: Actor, at: String) = env.get("/v1/config/revisions?scope_kind=TENANT&scope_id=${a.tenant.value}&as_of=$at", o.token).json["items"].size()
}
