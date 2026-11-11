package de.taktovia.app.config

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.app.probe.Resp
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * LH-I.7-CFG-005 / LH-I.4-MUL-002 / WP-M1-21 über die echte Anwendung (Ressourcenserver mit Test-Issuer, echte Datenbank, Runtime-Rolle): `GET /v1/config/effective`
 * mit der Präzedenzkette, Herkunft und Vorrangregel, Fahrzeug-Override nur für freigegebene Parameter, Mandanten- und Dienstgrenzen. Regionen, Dienste und Entwürfe
 * entstehen über die API; die Veröffentlichung (WP-M1-22) legt der Testaufbau direkt in der Datenbank an.
 */
class EffectiveConfigApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "eff-" + UUID.randomUUID())

    private inner class World {
        val tenant: TenantId = env.tenant(); val admin = UUID.randomUUID(); val token: String
        init { env.grant(tenant, admin, "tenant_admin"); token = env.token(admin) }
        fun post(path: String, body: String) = env.post(path, body, token, extra = idem())
        fun id(path: String, body: String): String = post(path, body).also { check(it.status == 201) { "$path: ${it.text}" } }.json["id"].asString()
        fun region(name: String) = id("/v1/regions", """{"name":"$name"}""")
        fun operator(): String {
            val le = id("/v1/legal-entities", """{"name":"LE","country":"DE"}""")
            return id("/v1/organizations", """{"legal_entity_id":"$le","name":"Betreiber","federation_ref":"f-${UUID.randomUUID()}","roles":["TRANSPORT_COMPANY"]}""")
        }
        fun service(op: String, region: String?) = id("/v1/services", """{"operator_id":"$op","name":"Rufbus","timezone":"Europe/Berlin"${if (region != null) ""","region_id":"$region"""" else ""}}""")
        fun vehicle(): String { val v = UUID.randomUUID(); env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'x','{}')", tenant.value, v); return v.toString() }
        /** Entwurf über die API, danach veröffentlicht wie WP-M1-22 es tun wird (Testaufbau). */
        fun published(kind: String, scope: String, values: String, from: String = "2026-01-01T00:00:00Z"): String {
            val v = if (kind == "SERVICE" && !values.contains("service_config")) values.trimEnd('}') + ""","service_config":{"service_area_id":"${UUID.randomUUID()}","traffic_type":"AREA_ON_DEMAND","operator_id":"${UUID.randomUUID()}","operating_hours":[{"weekdays":["MON"],"from":"06:00","to":"22:00"}],"tariff_id":"${UUID.randomUUID()}"}}""" else values
            val r = post("/v1/config/revisions", """{"scope_kind":"$kind","scope_id":"$scope","schema_version":"1","values":$v,"effective_from":"$from","reason":"Gebietsregel"}""")
            check(r.status == 201) { r.text }
            val id = r.json["id"].asString()
            for ((state, extra) in listOf("VALIDATED" to "", "APPROVED" to ", approved_by = 'user:zweite-person'", "PUBLISHED" to ", published_at = clock_timestamp()"))
                env.tx.run(tenant) { j ->
                    if (state == "PUBLISHED") j.update("UPDATE taktovia.config_revision p SET effective_to = n.effective_from FROM taktovia.config_revision n WHERE n.id = ? AND p.tenant_id = n.tenant_id AND p.scope_kind = n.scope_kind AND p.scope_id = n.scope_id AND p.state = 'PUBLISHED' AND p.effective_to IS NULL AND p.effective_from < n.effective_from", UUID.fromString(id))
                    j.update("UPDATE taktovia.config_revision SET state = ?$extra WHERE id = ?", state, UUID.fromString(id))
                }
            return id
        }
        fun effective(query: String, tk: String = token): Resp = env.get("/v1/config/effective$query", tk)
    }

    @Test
    fun konflikt_ueber_die_api_liefert_wert_herkunft_und_vorrangregel() {
        val w = World(); val op = w.operator(); val region = w.region("Nord"); val service = w.service(op, region)
        val t = w.published("TENANT", w.tenant.toString(), """{"max_wait_min":30}""")
        val r = w.published("REGION", region, """{"max_wait_min":20}""")
        val s = w.published("SERVICE", service, """{"max_wait_min":10}""")
        val res = w.effective("?parameter=max_wait_min&service_id=$service")
        assertEquals(200, res.status, res.text)
        val item = res.json["items"][0]
        assertEquals("max_wait_min", item["parameter"].asString()); assertEquals(10, item["value"].asInt()); assertEquals("MINUTES", item["unit"].asString())
        assertEquals("EFFECTIVE", item["status"].asString()); assertEquals("SERVICE", item["level"].asString()); assertEquals(s, item["revision_id"].asString())
        assertEquals("MOST_SPECIFIC_LEVEL_WINS", item["rule"]["code"].asString()); assertTrue(item["rule"]["description"].asString().isNotBlank())
        assertEquals(10, res.json["values"]["max_wait_min"].asInt()); assertEquals(listOf(s), res.json["revision_ids"].values().map { it.asString() })
        // alle Ebenen mit ihrem Schicksal: Mandant (30) und Region (20) werden überstimmt
        val levels = item["precedence"].values().associate { it["level"].asString() to it }
        assertEquals(setOf("VEHICLE", "SERVICE", "REGION", "TENANT", "GLOBAL"), levels.keys)
        assertEquals("APPLIED", levels.getValue("SERVICE")["outcome"].asString()); assertEquals("OVERRIDDEN", levels.getValue("REGION")["outcome"].asString())
        assertEquals(20, levels.getValue("REGION")["value"].asInt()); assertEquals(r, levels.getValue("REGION")["revision_id"].asString())
        assertEquals(30, levels.getValue("TENANT")["value"].asInt()); assertEquals(t, levels.getValue("TENANT")["revision_id"].asString())
        assertEquals("NOT_IN_CONTEXT", levels.getValue("VEHICLE")["outcome"].asString())
        // dieselbe Anfrage mit Region statt Dienst und ohne Kontext: die Ebene der Herkunft wechselt
        assertEquals("REGION", w.effective("?parameter=max_wait_min&region_id=$region").json["items"][0]["level"].asString())
        assertEquals("TENANT", w.effective("?parameter=max_wait_min").json["items"][0]["level"].asString())
        // as_of: vor dem Wirksamkeitsbeginn gibt es keinen Wert (definierte Antwort ohne Wert)
        val early = w.effective("?parameter=max_wait_min&at=2025-01-01T00:00:00Z").json["items"][0]
        assertEquals("NO_VALUE", early["status"].asString()); assertTrue(early["value"].isNull); assertTrue(early["level"].isNull)
    }

    @Test
    fun fuenf_ebenen_global_mandant_region_dienst_fahrzeug_werden_unterschieden() {
        val w = World(); val op = w.operator(); val region = w.region("Nord"); val service = w.service(op, region); val vehicle = w.vehicle()
        w.published("TENANT", w.tenant.toString(), """{"pickup_window_min":15,"wheelchair_boarding_sec":120}""")
        w.published("REGION", region, """{"pickup_window_min":20}""")
        w.published("SERVICE", service, """{"pickup_window_min":25,"wheelchair_boarding_sec":100}""")
        w.published("VEHICLE", vehicle, """{"wheelchair_boarding_sec":80}""")
        fun level(q: String) = w.effective(q).json["items"][0]["level"].asString()
        assertEquals("GLOBAL", level("?parameter=vehicle_readiness_require_checks"))
        assertEquals("TENANT", level("?parameter=pickup_window_min"))
        assertEquals("REGION", level("?parameter=pickup_window_min&region_id=$region"))
        assertEquals("SERVICE", level("?parameter=pickup_window_min&service_id=$service"))
        assertEquals("VEHICLE", level("?parameter=wheelchair_boarding_sec&service_id=$service&vehicle_id=$vehicle"))
        assertEquals(80, w.effective("?parameter=wheelchair_boarding_sec&service_id=$service&vehicle_id=$vehicle").json["items"][0]["value"].asInt())
        assertEquals(100, w.effective("?parameter=wheelchair_boarding_sec&service_id=$service").json["items"][0]["value"].asInt())
        // ohne parameter: alle Parameter des Katalogs
        val all = w.effective("?service_id=$service&vehicle_id=$vehicle")
        assertTrue(all.json["items"].size() >= 20); assertEquals("1", all.json["schema_version"].asString()); assertEquals(80, all.json["values"]["wheelchair_boarding_sec"].asInt())
    }

    @Test
    fun fahrzeug_override_fuer_nicht_freigegebenen_parameter_wird_abgewiesen_422() {
        val w = World(); val vehicle = w.vehicle()
        val r = w.post("/v1/config/revisions", """{"scope_kind":"VEHICLE","scope_id":"$vehicle","schema_version":"1","values":{"max_wait_min":20},"effective_from":"2026-12-01T00:00:00Z","reason":"Override"}""")
        assertEquals(422, r.status, r.text)
        assertTrue(r.json["violations"].values().any { it["pointer"].asString() == "/values/max_wait_min" }, r.text)
        assertEquals(201, w.post("/v1/config/revisions", """{"scope_kind":"VEHICLE","scope_id":"$vehicle","schema_version":"1","values":{"wheelchair_boarding_sec":70},"effective_from":"2026-12-01T00:00:00Z","reason":"Override"}""").status)
        assertEquals(404, w.post("/v1/config/revisions", """{"scope_kind":"VEHICLE","scope_id":"${UUID.randomUUID()}","schema_version":"1","values":{"wheelchair_boarding_sec":70},"effective_from":"2026-12-01T00:00:00Z","reason":"Override"}""").status)
    }

    @Test
    fun zwei_gebiete_ein_login_wechsel_zeigt_jeweils_die_regeln_des_gewaehlten_gebiets() {
        val w = World(); val op = w.operator(); val r1 = w.region("Nord"); val r2 = w.region("Süd"); val s1 = w.service(op, r1); val s2 = w.service(op, r2)
        w.published("REGION", r1, """{"max_wait_min":15}"""); w.published("REGION", r2, """{"max_wait_min":40}""")
        w.published("SERVICE", s1, """{"max_wait_min":12}""")
        val dispatcher = UUID.randomUUID(); env.grant(w.tenant, dispatcher, "dispatcher", listOf(UUID.fromString(s1), UUID.fromString(s2)))
        val login = env.token(dispatcher)
        val a = w.effective("?parameter=max_wait_min&service_id=$s1", login).json["items"][0]; val b = w.effective("?parameter=max_wait_min&service_id=$s2", login).json["items"][0]
        assertEquals(12, a["value"].asInt()); assertEquals("SERVICE", a["level"].asString()); assertEquals(40, b["value"].asInt()); assertEquals("REGION", b["level"].asString())
        assertNotEquals(a["value"].asInt(), b["value"].asInt())
        // ein Disponent nur mit Dienst 1: Dienst 2 ist nicht sichtbar (404), auch wenn er im Mandanten existiert
        val only1 = UUID.randomUUID(); env.grant(w.tenant, only1, "dispatcher", listOf(UUID.fromString(s1)))
        assertEquals(200, w.effective("?parameter=max_wait_min&service_id=$s1", env.token(only1)).status)
        assertEquals(404, w.effective("?parameter=max_wait_min&service_id=$s2", env.token(only1)).status)
        val none = UUID.randomUUID(); env.grant(w.tenant, none, "dispatcher", null)
        assertEquals(404, w.effective("?parameter=max_wait_min&service_id=$s1", env.token(none)).status, "service_scope = NULL: kein Dienstzugriff")
        assertEquals(200, w.effective("?parameter=max_wait_min", env.token(none)).status, "ohne Dienstbezug gilt nur die Mandantengrenze")
    }

    @Test
    fun fehlerfaelle_fremder_mandant_unbekannter_parameter_rechte_und_formatfehler() {
        val a = World(); val b = World(); val opB = b.operator(); val regionB = b.region("B"); val serviceB = b.service(opB, regionB); val vehicleB = b.vehicle()
        for (q in listOf("region_id=$regionB", "service_id=$serviceB", "vehicle_id=$vehicleB", "region_id=${UUID.randomUUID()}"))
            assertEquals(404, a.effective("?parameter=max_wait_min&$q").status, q)
        assertEquals(404, a.effective("?parameter=gibt_es_nicht").status)
        val regionA = a.region("A"); val serviceA = a.service(a.operator(), regionA); val other = a.region("Andere")
        assertEquals(422, a.effective("?parameter=max_wait_min&region_id=$other&service_id=$serviceA").status)
        assertEquals(422, a.effective("?parameter=max_wait_min&region_id=keine-uuid").status)
        assertEquals(422, a.effective("?parameter=max_wait_min&at=gestern").status)
        val passenger = UUID.randomUUID(); env.grant(a.tenant, passenger, "passenger")
        assertEquals(403, a.effective("?parameter=max_wait_min", env.token(passenger)).status)
        assertEquals(401, env.server.get("/v1/config/effective").status)
        // der Mandantenrahmen eines anderen Mandanten wirkt nie
        b.published("TENANT", b.tenant.toString(), """{"max_wait_min":7}""")
        assertEquals("NO_VALUE", a.effective("?parameter=max_wait_min").json["items"][0]["status"].asString())
    }
}
