package de.taktovia.app.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * LH-I.4-MUL-002 / WP-M1-09 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak): Regionen, Betriebshöfe, Kostenstellen, Dienste;
 * Rechte (403), `If-Match` (428/412), Mandantentrennung (404), Audit, parallele Anlage.
 */
class OrganizationStructureApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    private class World(val env: IdentityEnv) {
        val tenant = env.tenant(); val admin = UUID.randomUUID(); val passenger = UUID.randomUUID(); val dispatcher = UUID.randomUUID()
        init { env.grant(tenant, admin, "tenant_admin"); env.grant(tenant, passenger, "passenger") }
        val token = env.token(admin); val passengerToken = env.token(passenger)
        private fun post(path: String, body: String) = env.post(path, body, token, null, mapOf("Idempotency-Key" to "k-" + UUID.randomUUID()))
        fun region(name: String = "Region"): String = post("/v1/regions", """{"name":"$name"}""").also { check(it.status == 201) { it.text } }.json["id"].asString()
        fun legalEntity(): String = post("/v1/legal-entities", """{"name":"LE","country":"DE"}""").json["id"].asString()
        fun operator(le: String = legalEntity()): String = post("/v1/organizations", """{"legal_entity_id":"$le","name":"Betreiber","federation_ref":"f-${UUID.randomUUID()}","roles":["TRANSPORT_COMPANY"]}""").json["id"].asString()
        fun service(op: String, name: String = "Rufbus", region: String? = null) = post("/v1/services",
            """{"operator_id":"$op","name":"$name","timezone":"Europe/Berlin"${if (region != null) ""","region_id":"$region"""" else ""}}""").also { check(it.status == 201) { it.text } }.json["id"].asString()
    }

    @Test
    fun zwei_regionen_eigene_regeln_ein_login_ueber_die_api() {
        val w = World(env); val op = w.operator()
        val nord = w.region("Region Nord"); val sued = w.region("Region Süd")
        val dNord = w.service(op, "Rufbus Nord", nord); val dSued = w.service(op, "Rufbus Süd", sued)
        // jeder Dienst trägt seine erste Konfigurationsrevision (Entwurf), die API nennt sie
        val list = env.get("/v1/services?limit=10", w.token); assertEquals(200, list.status, list.text)
        assertEquals(setOf(dNord, dSued), list.json["items"].values().map { it["id"].asString() }.toSet())
        list.json["items"].values().forEach { assertTrue(it["config_revision_id"].asString().isNotEmpty()) }
        assertEquals(nord, list.json["items"].values().single { it["id"].asString() == dNord }["region_id"].asString())
        // eigene Regeln je Gebiet: Konfigurationsentwürfe pro Region und Dienst mit unterschiedlichem max_wait_min (Veröffentlichung folgt WP-M1-22/21)
        fun draft(kind: String, scope: String, wait: Int) = env.post("/v1/config/revisions",
            """{"scope_kind":"$kind","scope_id":"$scope","schema_version":"1","values":{"max_wait_min":$wait},"effective_from":"2026-12-01T00:00:00Z","reason":"Gebietsregel"}""", w.token, null, idem())
        assertEquals(201, draft("REGION", nord, 15).status); assertEquals(201, draft("REGION", sued, 40).status)
        val revs = env.get("/v1/config/revisions?scope_kind=REGION&limit=10", w.token).json["items"].values().associate { it["scope_id"].asString() to it["values"]["max_wait_min"].asInt() }
        assertEquals(mapOf(nord to 15, sued to 40), revs)
        // ein Nutzer mit beiden Diensten im Dienstscope arbeitet in einem Login in beiden Gebieten (Lesezugriff nur auf seine Dienste)
        env.grant(w.tenant, w.dispatcher, "dispatcher", listOf(UUID.fromString(dNord), UUID.fromString(dSued)))
        val login = env.token(w.dispatcher)
        assertEquals(setOf(dNord, dSued), env.get("/v1/services", login).json["items"].values().map { it["id"].asString() }.toSet())
        val onlyNord = UUID.randomUUID(); env.grant(w.tenant, onlyNord, "dispatcher", listOf(UUID.fromString(dNord)))
        assertEquals(listOf(dNord), env.get("/v1/services", env.token(onlyNord)).json["items"].values().map { it["id"].asString() })
        val noScope = UUID.randomUUID(); env.grant(w.tenant, noScope, "dispatcher", null)
        assertTrue(env.get("/v1/services", env.token(noScope)).json["items"].values().isEmpty(), "service_scope = NULL: kein Dienstzugriff (fail-closed)")
    }

    @Test
    fun mehrere_betriebshoefe_und_kostenstellen_je_mandant_zuordenbar() {
        val w = World(env); val op = w.operator(); val r1 = w.region("R1"); val r2 = w.region("R2"); val d = w.service(op, region = r1); val le = w.legalEntity()
        fun post(p: String, b: String) = env.post(p, b, w.token, null, idem())
        val h1 = post("/v1/depots", """{"region_id":"$r1","name":"Hof 1"}"""); val h2 = post("/v1/depots", """{"region_id":"$r1","name":"Hof 2"}""")
        assertEquals(201, h1.status, h1.text); assertEquals(201, h2.status); assertEquals("\"1\"", h1.header("ETag")); assertEquals("/v1/depots/${h1.json["id"].asString()}", h1.header("Location"))
        assertEquals(201, post("/v1/depots", """{"region_id":"$r2","name":"Hof 3"}""").status)
        val k1 = post("/v1/cost-centers", """{"code":"KST-1","name":"Linie","legal_entity_id":"$le","region_id":"$r1"}""")
        val k2 = post("/v1/cost-centers", """{"code":"KST-2","name":"Rufbus","legal_entity_id":"$le","service_id":"$d"}""")
        assertEquals(201, k1.status, k1.text); assertEquals(201, k2.status, k2.text)
        assertEquals(r1, k1.json["region_id"].asString()); assertEquals(d, k2.json["service_id"].asString())
        assertEquals(3, env.get("/v1/depots", w.token).json["items"].size()); assertEquals(2, env.get("/v1/cost-centers", w.token).json["items"].size())
        // Zuordnung ändern (If-Match)
        val moved = env.put("/v1/cost-centers/${k1.json["id"].asString()}", """{"name":"Linie neu","region_id":"$r2"}""", w.token, "\"1\"")
        assertEquals(200, moved.status, moved.text); assertEquals(r2, moved.json["region_id"].asString()); assertEquals("\"2\"", moved.header("ETag"))
        // Betriebshof einer anderen Region zuordnen
        val dm = env.put("/v1/depots/${h1.json["id"].asString()}", """{"region_id":"$r2","name":"Hof 1"}""", w.token, "\"1\"")
        assertEquals(200, dm.status, dm.text); assertEquals(r2, dm.json["region_id"].asString())
    }

    @Test
    fun anlage_ohne_recht_403_und_aenderung_ohne_if_match_428_veraltet_412() {
        val w = World(env); val op = w.operator(); val r = w.region()
        for ((path, body) in listOf("/v1/regions" to """{"name":"x"}""", "/v1/depots" to """{"region_id":"$r","name":"x"}""",
            "/v1/cost-centers" to """{"code":"X","name":"x","legal_entity_id":"${UUID.randomUUID()}"}""", "/v1/services" to """{"operator_id":"$op","name":"x","timezone":"Europe/Berlin"}"""))
            assertEquals(403, env.post(path, body, w.passengerToken, null, idem()).status, path)
        for (path in listOf("/v1/regions", "/v1/depots", "/v1/cost-centers", "/v1/services")) assertEquals(403, env.get(path, w.passengerToken).status, path)
        assertEquals(403, env.put("/v1/regions/$r", """{"name":"x"}""", w.passengerToken, "\"1\"").status)
        // ohne If-Match 428, veraltet 412, passend 200
        assertEquals(428, env.server.send("PUT", "/v1/regions/$r", """{"name":"Neu"}""", env.headers(w.token, extra = idem())).status)
        assertEquals(412, env.put("/v1/regions/$r", """{"name":"Neu"}""", w.token, "\"7\"").status)
        val ok = env.put("/v1/regions/$r", """{"name":"Neu"}""", w.token, "\"1\""); assertEquals(200, ok.status); assertEquals("\"2\"", ok.header("ETag"))
        assertEquals(412, env.put("/v1/regions/$r", """{"name":"Alt"}""", w.token, "\"1\"").status)
        val s = w.service(op)
        assertEquals(428, env.server.send("PUT", "/v1/services/$s", """{"name":"Neu","timezone":"Europe/Berlin"}""", env.headers(w.token, extra = idem())).status)
        assertEquals(412, env.put("/v1/services/$s", """{"name":"Neu","timezone":"Europe/Berlin"}""", w.token, "\"4\"").status)
        // Audit: je Änderung genau ein Eintrag (Anlage + eine erfolgreiche Änderung der Region)
        assertEquals(2, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND object_ref=? AND action='identity.region.changed'", w.tenant.value, "region:$r"))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND object_ref=? AND action='identity.service.changed'", w.tenant.value, "service:$s"))
    }

    @Test
    fun ungueltige_iana_zone_ergibt_422() {
        val w = World(env); val op = w.operator()
        for (tz in listOf("Mars/Olympus", "CET", "Etc/GMT+1")) {
            val r = env.post("/v1/services", """{"operator_id":"$op","name":"x","timezone":"$tz"}""", w.token, null, idem())
            assertEquals(422, r.status, "$tz: ${r.text}")
        }
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.service WHERE tenant_id=?", w.tenant.value))
    }

    @Test
    fun fremder_mandant_region_depot_dienst_sind_404_und_gleichnamige_kostenstellen_getrennt() {
        val a = World(env); val b = World(env)
        val rB = b.region(); val opB = b.operator(); val sB = b.service(opB, region = rB); val leA = a.legalEntity(); val leB = b.legalEntity()
        val depotB = env.post("/v1/depots", """{"region_id":"$rB","name":"H"}""", b.token, null, idem()).json["id"].asString()
        fun postA(p: String, body: String) = env.post(p, body, a.token, null, idem())
        assertEquals(404, postA("/v1/depots", """{"region_id":"$rB","name":"H"}""").status)
        assertEquals(404, postA("/v1/services", """{"operator_id":"${a.operator()}","name":"x","timezone":"Europe/Berlin","region_id":"$rB"}""").status)
        assertEquals(404, postA("/v1/services", """{"operator_id":"$opB","name":"x","timezone":"Europe/Berlin"}""").status)
        assertEquals(404, postA("/v1/cost-centers", """{"code":"X","name":"x","legal_entity_id":"$leA","service_id":"$sB"}""").status)
        assertEquals(404, env.put("/v1/regions/$rB", """{"name":"x"}""", a.token, "\"1\"").status)
        assertEquals(404, env.put("/v1/depots/$depotB", """{"region_id":"${a.region()}","name":"x"}""", a.token, "\"1\"").status)
        assertEquals(404, env.put("/v1/services/$sB", """{"name":"x","timezone":"Europe/Berlin"}""", a.token, "\"1\"").status)
        assertTrue(env.get("/v1/regions", a.token).json["items"].values().none { it["id"].asString() == rB })
        // gleichnamige Kostenstellen in A und B sind getrennte Objekte
        val ka = postA("/v1/cost-centers", """{"code":"KST-7","name":"Gleich","legal_entity_id":"$leA"}""")
        val kb = env.post("/v1/cost-centers", """{"code":"KST-7","name":"Gleich","legal_entity_id":"$leB"}""", b.token, null, idem())
        assertEquals(201, ka.status, ka.text); assertEquals(201, kb.status, kb.text)
        assertNotEquals(ka.json["id"].asString(), kb.json["id"].asString())
        assertEquals(404, env.put("/v1/cost-centers/${kb.json["id"].asString()}", """{"name":"x"}""", a.token, "\"1\"").status)
        assertEquals(listOf(ka.json["id"].asString()), env.get("/v1/cost-centers", a.token).json["items"].values().map { it["id"].asString() })
    }

    @Test
    fun doppelte_kostenstelle_409_und_paralleles_anlegen_ueber_http_genau_ein_erfolg() {
        val w = World(env); val le = w.legalEntity()
        val body = """{"code":"PAR-1","name":"Parallel","legal_entity_id":"$le"}"""
        val pool = Executors.newFixedThreadPool(6); val start = CountDownLatch(1)
        val fut = (1..6).map { pool.submit<Int> { start.await(); env.post("/v1/cost-centers", body, w.token, null, idem()).status } }
        start.countDown()
        val r = fut.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, r.count { it == 201 }, r.toString()); assertEquals(5, r.count { it == 409 }, r.toString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.cost_object WHERE tenant_id=? AND business_ref='PAR-1'", w.tenant.value))
    }
}
