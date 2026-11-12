package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * LH-6.8-FLT-001 / LH-6.8-FLT-004, WP-M3-03/-04 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): Rechte (Disponent und Werkstatt schreiben, Support liest, Fahrgast nichts),
 * Anlegen und unverändert Lesen, 422 mit Feldverweis (Originalabnahme), 409 doppelte Kennung, 428/412/404, Deaktivieren, Kapazitätskonfiguration, genau ein Audit und ein Ereignis.
 */
class VehicleMasterApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    class World(val env: IdentityEnv) {
        val tenant: TenantId = env.tenant(); val dispatcher = UUID.randomUUID(); val workshop = UUID.randomUUID(); val support = UUID.randomUUID(); val passenger = UUID.randomUUID()
        val orgs = List(2) { UUID.randomUUID() }
        init {
            env.grant(tenant, dispatcher, "dispatcher"); env.grant(tenant, workshop, "workshop"); env.grant(tenant, support, "support"); env.grant(tenant, passenger, "passenger")
            val le = UUID.randomUUID()
            env.tx.run(tenant) { j ->
                j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", tenant.value, le)
                orgs.forEachIndexed { i, o -> j.update("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,?,?,CAST(? AS text[]))", tenant.value, o, le, "Org $i", "f-$o", "{TRANSPORT_COMPANY}") }
            }
        }
        val dispatcherToken = env.token(dispatcher); val workshopToken = env.token(workshop); val supportToken = env.token(support); val passengerToken = env.token(passenger)
        fun body(label: String = "KA-" + UUID.randomUUID().toString().take(8), seats: String = "4", wheelchair: String = "1", org: UUID = orgs[0], active: Boolean = true, type: String = "PKW_BARRIEREFREI") =
            """{"vehicle_label":"$label","vehicle_type":"$type","operator_organization_id":"$org","capacity":{"seats":$seats,"wheelchair":$wheelchair},"equipment":["RAMP"],"active":$active}"""
    }

    private fun create(w: World, body: String = w.body(), token: String = w.dispatcherToken) = env.post("/v1/operator/vehicle-master", body, token, null, idem())

    @Test
    fun anlegen_lesen_aendern_deaktivieren_ueber_die_api() {
        val w = World(env)
        val c = create(w, w.body("KA-1000", seats = "5", wheelchair = "2"))
        assertEquals(201, c.status, c.text); assertEquals("\"1\"", c.header("ETag"))
        val id = c.json["id"].asString(); assertEquals("/v1/operator/vehicle-master/$id", c.header("Location"))
        // alle Pflichtangaben unverändert lesbar; ohne Einsatzfreigabe nicht disponierbar
        val g = env.get("/v1/operator/vehicle-master/$id", w.supportToken)
        assertEquals(200, g.status); assertEquals("\"1\"", g.header("ETag"))
        assertEquals("KA-1000", g.json["vehicle_label"].asString()); assertEquals("PKW_BARRIEREFREI", g.json["vehicle_type"].asString()); assertEquals(w.orgs[0].toString(), g.json["operator_organization_id"].asString())
        assertEquals(5, g.json["capacity"]["seats"].asInt()); assertEquals(2, g.json["capacity"]["wheelchair"].asInt()); assertEquals("RAMP", g.json["equipment"].values().single().asString())
        assertTrue(g.json["active"].asBoolean()); assertTrue(g.json["dispatch_blocked"].asBoolean()); assertFalse(g.json["dispatchable"].asBoolean())
        // Ändern mit If-Match; ohne If-Match 428; veraltet 412
        val noMatch = env.server.send("PUT", "/v1/operator/vehicle-master/$id", w.body("KA-1000", seats = "6"), env.headers(w.dispatcherToken, extra = idem()))
        assertEquals(428, noMatch.status, noMatch.text)
        val u = env.put("/v1/operator/vehicle-master/$id", w.body("KA-1000", seats = "6"), w.dispatcherToken, "\"1\"")
        assertEquals(200, u.status, u.text); assertEquals("\"2\"", u.header("ETag")); assertEquals(6, u.json["capacity"]["seats"].asInt())
        val stale = env.put("/v1/operator/vehicle-master/$id", w.body("KA-1000", seats = "7"), w.dispatcherToken, "\"1\"")
        assertEquals(412, stale.status, stale.text)
        // Deaktivieren (Werkstatt darf schreiben): Audit mit Akteur, Ereignis, weiter lesbar und in der Liste
        val off = env.put("/v1/operator/vehicle-master/$id", w.body("KA-1000", seats = "6", active = false), w.workshopToken, "\"2\"")
        assertEquals(200, off.status, off.text); assertFalse(off.json["active"].asBoolean()); assertFalse(off.json["dispatchable"].asBoolean())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.vehicle.deactivated' AND object_ref=? AND actor_ref=?", w.tenant.value, "vehicle:$id", "user:${w.workshop}"))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.vehicle.created' AND object_ref=? AND actor_ref=?", w.tenant.value, "vehicle:$id", "user:${w.dispatcher}"))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='vehicle.deactivated'", w.tenant.value, UUID.fromString(id)))
        val all = env.get("/v1/operator/vehicle-master", w.supportToken); assertEquals(200, all.status)
        assertTrue(all.json["items"].values().any { it["id"].asString() == id && !it["active"].asBoolean() })
        assertEquals(1, env.get("/v1/operator/vehicle-master?active=false", w.supportToken).json["items"].size()); assertEquals(0, env.get("/v1/operator/vehicle-master?active=true", w.supportToken).json["items"].size())
        assertEquals(422, env.get("/v1/operator/vehicle-master?active=vielleicht", w.supportToken).status)
    }

    @Test
    fun ungueltige_kapazitaet_ergibt_422_mit_feldverweis_und_wird_nicht_gespeichert() {
        val w = World(env)
        val cases = listOf(
            "negativ" to (w.body("NEG-1", seats = "-1") to "/capacity/seats"), "nicht ganzzahlig" to (w.body("NEG-2", wheelchair = "1.5") to "/capacity/wheelchair"),
            "Text" to (w.body("NEG-3", seats = "\"vier\"") to "/capacity/seats"), "fehlend" to (w.body("NEG-4").replace(""""capacity":{"seats":4,"wheelchair":1},""", "") to "/capacity"),
        )
        for ((name, c) in cases) {
            val r = create(w, c.first)
            assertEquals(422, r.status, "$name: ${r.text}")
            assertTrue(r.json["violations"].values().any { it["pointer"].asString().startsWith(c.second) }, "$name: Feldverweis ${c.second} fehlt: ${r.text}")
        }
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.vehicle WHERE tenant_id=? AND vehicle_label LIKE 'NEG-%'", w.tenant.value))
        // Pflichtfeld Betreiber, Betreiber aus fremdem Mandanten, unbekanntes Feld und mandantenfremder Body
        assertEquals(422, create(w, w.body("REQ-1").replace(""""operator_organization_id":"${w.orgs[0]}",""", "")).status)
        val other = World(env)
        assertEquals(422, create(w, w.body("FOREIGN-1", org = other.orgs[0])).status)
        assertEquals(422, create(w, w.body("EXTRA-1").replace("\"active\":true", "\"active\":true,\"tenant_id\":\"${w.tenant.value}\"")).status)
    }

    @Test
    fun doppelte_kennung_409_rechte_und_fremder_mandant_404() {
        val w = World(env); val other = World(env)
        val first = create(w, w.body("DUP-1")); assertEquals(201, first.status, first.text)
        val dup = create(w, w.body("DUP-1")); assertEquals(409, dup.status, dup.text); assertTrue(dup.json["violations"].values().any { it["pointer"].asString() == "/vehicle_label" })
        assertEquals(201, create(other, other.body("DUP-1"), other.dispatcherToken).status, "gleiche Kennung in anderem Mandanten zulässig")
        val id = first.json["id"].asString()
        // Fahrgast und Support dürfen nicht schreiben, Fahrgast auch nicht lesen
        assertEquals(403, create(w, w.body("PERM-1"), w.supportToken).status); assertEquals(403, create(w, w.body("PERM-2"), w.passengerToken).status)
        assertEquals(403, env.get("/v1/operator/vehicle-master", w.passengerToken).status)
        // anderer Mandant: 404 ohne Existenzauskunft
        assertEquals(404, env.get("/v1/operator/vehicle-master/$id", other.supportToken).status)
        assertEquals(404, env.put("/v1/operator/vehicle-master/$id", other.body("DUP-1", org = other.orgs[0]), other.dispatcherToken, "\"1\"").status)
        assertEquals(404, env.get("/v1/operator/vehicle-master/$id/capacity", other.supportToken).status)
        assertTrue(env.get("/v1/operator/vehicle-master", other.supportToken).json["items"].values().none { it["id"].asString() == id })
    }

    @Test
    fun gleichzeitige_aenderung_ueber_http_genau_eine_gewinnt() {
        val w = World(env)
        val id = create(w, w.body("RACE-1")).json["id"].asString()
        val pool = Executors.newFixedThreadPool(4); val start = CountDownLatch(1)
        try {
            val f = (0 until 4).map { i -> pool.submit<Int> { start.await(); env.put("/v1/operator/vehicle-master/$id", w.body("RACE-1", seats = "${10 + i}"), w.dispatcherToken, "\"1\"").status } }
            start.countDown()
            val codes = f.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, codes.count { it == 200 }, codes.toString()); assertEquals(3, codes.count { it == 412 }, codes.toString())
        } finally { pool.shutdownNow() }
    }

    @Test
    fun kapazitaetskonfiguration_ueber_die_api() {
        val w = World(env)
        val c = create(w, w.body("CAP-1", seats = "6", wheelchair = "0")); val id = c.json["id"].asString()
        val before = env.get("/v1/operator/vehicle-master/$id/capacity", w.supportToken)
        assertEquals(200, before.status); assertTrue(before.json["layout"].isNull); assertEquals(6, before.json["effective"]["seats"].asInt())
        fun patch(body: String, ifMatch: String? = "\"1\"") = env.server.send("PATCH", "/v1/operator/vehicle-master/$id/capacity", body, env.headers(w.dispatcherToken, extra = idem() + (if (ifMatch != null) mapOf("If-Match" to ifMatch) else emptyMap())))
        val body = """{"spaces":{"seat_places":6,"wheelchair_spaces":2,"seats_replaced_per_wheelchair":2},"combinations":[{"code":"STANDARD","wheelchair_places":0},{"code":"ONE_WC","wheelchair_places":1}],"active_combination":"ONE_WC","restrictions":[{"code":"MAX_TOTAL_PERSONS","value":5}]}"""
        assertEquals(428, patch(body, null).status)
        val ok = patch(body); assertEquals(200, ok.status, ok.text); assertEquals("\"2\"", ok.header("ETag"))
        assertEquals(4, ok.json["effective"]["seats"].asInt()); assertEquals(1, ok.json["effective"]["wheelchair"].asInt())   // ein Rollstuhlplatz ersetzt 2 Sitzplätze
        assertEquals(2, ok.json["vectors"].size())
        assertEquals(412, patch(body, "\"1\"").status)
        // widersprüchliche Kombination und unbekannte Dimension: 422, nichts geändert
        val bad = patch("""{"spaces":{"seat_places":6,"wheelchair_spaces":2,"seats_replaced_per_wheelchair":4}}""", "\"2\"")
        assertEquals(422, bad.status, bad.text); assertTrue(bad.json["violations"].values().any { it["pointer"].asString() == "/spaces/seats_replaced_per_wheelchair" })
        assertEquals(422, patch("""{"spaces":{"seat_places":6,"wheelchair_spaces":2,"seats_replaced_per_wheelchair":1,"stehplaetze":4}}""", "\"2\"").status)
        assertEquals(422, patch("""{"restrictions":[{"code":"MAX_WHEELCHAIR_SPACES","value":9}]}""", "\"2\"").status)
        assertEquals("\"2\"", env.get("/v1/operator/vehicle-master/$id/capacity", w.supportToken).header("ETag"))
        // Teil ändern: nur Restriktionen, Stellplätze und Kombinationen bleiben
        val r = patch("""{"restrictions":[{"code":"MAX_TOTAL_PERSONS","value":3}]}""", "\"2\"")
        assertEquals(200, r.status, r.text); assertEquals(2, r.json["layout"]["spaces"]["wheelchair_spaces"].asInt()); assertEquals(3, r.json["layout"]["restrictions"].values().single()["value"].asInt())
        // der Stamm trägt den wirksamen Vektor; eine abweichende Stammkapazität ist ein Konflikt
        val stamm = env.get("/v1/operator/vehicle-master/$id", w.supportToken)
        assertEquals(4, stamm.json["capacity"]["seats"].asInt()); assertTrue(stamm.json["has_capacity_layout"].asBoolean())
        assertEquals(409, env.put("/v1/operator/vehicle-master/$id", w.body("CAP-1", seats = "3", wheelchair = "0"), w.dispatcherToken, "\"3\"").status)
        assertEquals(2, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.vehicle.capacity_changed' AND object_ref=?", w.tenant.value, "vehicle:$id"))
    }
}
