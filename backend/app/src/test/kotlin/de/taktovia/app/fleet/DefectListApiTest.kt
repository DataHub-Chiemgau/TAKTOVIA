package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * LH-6.22-DMG-009 / WP-M3-49 über die echte Anwendung: `GET /v1/defects` mit den sechs Filtern, Sortierung, Seitenwechsel über den signierten Cursor, 422 bei ungültigen Werten, 400 bei manipuliertem
 * oder fremdem Cursor, 403 ohne Funktionsrecht, fremder Mandant, Listenprojektion ohne Beschreibungstext; Betriebshof des Fahrzeugs über den Fahrzeugstamm.
 */
class DefectListApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    private fun report(w: DefectApiTest.World, vehicle: UUID = w.vehicle, category: String = "BODY", extra: String = ""): Pair<String, String> {
        val r = env.post("/v1/defects", w.body(vehicle = vehicle, category = category, extra = extra), w.dispatcherToken, null, idem()); assertEquals(201, r.status, r.text)
        return r.json["id"].asString() to r.json["case_number"].asString()
    }

    private fun list(w: DefectApiTest.World, query: String, token: String = w.dispatcherToken) = env.get("/v1/defects$query", token)
    private fun idsOf(r: de.taktovia.app.probe.Resp) = r.json["items"].values().map { it["id"].asString() }

    @Test
    fun sechs_filter_sortierung_und_seitenwechsel() {
        val w = DefectApiTest.World(env)
        val v2 = w.vehicle(); val v3 = w.vehicle()
        val (cosmetic, _) = report(w)
        val (critical, _) = report(w, v2, "BRAKES", ""","kind":"MALFUNCTION","vehicle_area":"WHEELS_TIRES","observed_effect":"NOT_ROADWORTHY"""")
        val (relevant, _) = report(w, v3, "LIGHTS", ""","kind":"MALFUNCTION","vehicle_area":"FRONT","observed_effect":"FUNCTION_LIMITED"""")
        val assignee = UUID.randomUUID()
        val t = env.server.send("POST", "/v1/defects/$relevant/triage", """{"severity":"OPERATIONALLY_RELEVANT","decision":"REVIEWED","assignee_ref":"$assignee"}""", env.headers(w.dispatcherToken, extra = idem() + ("If-Match" to "\"1\"")))
        assertEquals(200, t.status, t.text)
        // Betriebshof über den Fahrzeugstamm (Arbeitsannahme OP-22): Fahrzeug 2 im Depot, fremder Betriebshof wird abgewiesen
        val depot = UUID.randomUUID(); val region = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.region(tenant_id,id,name,created_by) VALUES (?,?,'R','user:t')", w.tenant.value, region)
        env.exec("INSERT INTO taktovia.depot(tenant_id,id,region_id,name,created_by) VALUES (?,?,?,'D','user:t')", w.tenant.value, depot, region)
        env.exec("UPDATE taktovia.vehicle SET depot_id = ? WHERE id = ?", depot, v2)

        assertEquals(listOf(cosmetic), idsOf(list(w, "?vehicle_id=${w.vehicle}")))
        assertEquals(listOf(critical), idsOf(list(w, "?priority=1")))
        assertEquals(listOf(relevant), idsOf(list(w, "?status=REVIEWED")))
        assertEquals(listOf(critical), idsOf(list(w, "?depot_id=$depot")))
        assertEquals(listOf(relevant), idsOf(list(w, "?assignee_ref=$assignee")))
        assertEquals(setOf(critical, relevant), idsOf(list(w, "?reported_from=${java.time.Instant.now().minusSeconds(3600)}&priority=1")).toSet() + setOf(relevant))
        assertEquals(3, list(w, "?reported_from=${java.time.Instant.now().minusSeconds(3600)}&reported_to=${java.time.Instant.now().plusSeconds(60)}").json["items"].size())
        assertEquals(0, list(w, "?reported_to=2020-01-01T00:00:00Z").json["items"].size())
        // Sortierung nach Priorität: kritisch, einsatzrelevant, kosmetisch; absteigend umgekehrt; Seitenwechsel über den Cursor (limit 2)
        assertEquals(listOf(critical, relevant, cosmetic), idsOf(list(w, "?sort=priority")))
        assertEquals(listOf(cosmetic, relevant, critical), idsOf(list(w, "?sort=-priority")))
        val first = list(w, "?sort=priority&limit=2"); assertEquals(200, first.status, first.text)
        assertEquals(2, first.json["items"].size()); assertNotNull(first.json["as_of"].asString())
        val cursor = first.json["next_cursor"].asString()
        val second = list(w, "?sort=priority&limit=2&cursor=$cursor")
        assertEquals(listOf(cosmetic), idsOf(second)); assertTrue(second.json["next_cursor"].isNull)
        // Listenprojektion: keine Beschreibung, Anzeigewerte der Spalten vorhanden
        val item = first.json["items"][0]
        assertTrue(item["description"].isNull); assertEquals("SAFETY_CRITICAL", item["severity"].asString()); assertTrue(item["blocks_vehicle"].asBoolean())
        // ungültige Werte: 422 mit Feldverweis; manipulierter oder fremder Cursor: 400 INVALID_CURSOR
        for ((q, pointer) in listOf("?limit=0" to "limit", "?limit=101" to "limit", "?limit=abc" to "limit", "?priority=9" to "priority", "?sort=foo" to "sort", "?status=ERLEDIGT" to "status",
            "?vehicle_id=x" to "vehicle_id", "?depot_id=x" to "depot_id", "?assignee_ref=x" to "assignee_ref", "?reported_from=gestern" to "reported_from")) {
            val r = list(w, q); assertEquals(422, r.status, "$q: ${r.text}"); assertEquals("/\$query/$pointer", r.json["violations"][0]["pointer"].asString(), q)
        }
        for (bad in listOf("$cursor".dropLast(3) + "xyz", "v2.abc.def", "garbage")) { val r = list(w, "?sort=priority&limit=2&cursor=$bad"); assertEquals(400, r.status, bad); assertEquals("INVALID_CURSOR", r.json["code"].asString()) }
        assertEquals(400, list(w, "?sort=-priority&limit=2&cursor=$cursor").status, "Cursor einer anderen Sortierung")
        assertEquals(400, list(w, "?sort=priority&limit=2&cursor=$cursor", w.workshopToken).status, "Cursor eines anderen Akteurs")
        // Rechte und Mandant
        assertEquals(403, list(w, "", w.passengerToken).status); assertEquals(403, list(w, "", w.supportToken).status)
        assertEquals(0, list(DefectApiTest.World(env), "").json["items"].size(), "fremder Mandant: keine Akten")
        assertEquals(0, list(DefectApiTest.World(env), "?depot_id=$depot").json["items"].size(), "fremdes Depot")
    }

    @Test
    fun betriebshof_im_fahrzeugstamm_pflegen_und_fremden_abweisen() {
        val w = DefectApiTest.World(env); val foreign = DefectApiTest.World(env)
        val depot = UUID.randomUUID(); val region = UUID.randomUUID(); val fdepot = UUID.randomUUID(); val fregion = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.region(tenant_id,id,name,created_by) VALUES (?,?,'R','user:t')", w.tenant.value, region)
        env.exec("INSERT INTO taktovia.depot(tenant_id,id,region_id,name,created_by) VALUES (?,?,?,'D','user:t')", w.tenant.value, depot, region)
        env.exec("INSERT INTO taktovia.region(tenant_id,id,name,created_by) VALUES (?,?,'R','user:t')", foreign.tenant.value, fregion)
        env.exec("INSERT INTO taktovia.depot(tenant_id,id,region_id,name,created_by) VALUES (?,?,?,'D','user:t')", foreign.tenant.value, fdepot, fregion)
        val orgId = UUID.randomUUID(); val le = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", w.tenant.value, le)
        env.exec("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,'Org',?,CAST(? AS text[]))", w.tenant.value, orgId, le, "f-$orgId", "{TRANSPORT_COMPANY}")
        fun body(extra: String) = """{"vehicle_label":"V-${UUID.randomUUID().toString().take(8)}","vehicle_type":"PKW","operator_organization_id":"$orgId","capacity":{"seats":4,"wheelchair":0},"equipment":[],"active":true$extra}"""
        fun put(id: String, etag: String, extra: String) = env.server.send("PUT", "/v1/operator/vehicle-master/$id", body(extra), env.headers(w.dispatcherToken, extra = idem() + ("If-Match" to etag)))
        val created = env.post("/v1/operator/vehicle-master", body(""","depot_id":"$depot""""), w.dispatcherToken, null, idem()); assertEquals(201, created.status, created.text)
        assertEquals(depot.toString(), created.json["depot_id"].asString())
        val id = created.json["id"].asString()
        val bad = env.post("/v1/operator/vehicle-master", body(""","depot_id":"$fdepot""""), w.dispatcherToken, null, idem())
        assertEquals(422, bad.status, bad.text); assertEquals("/depot_id", bad.json["violations"][0]["pointer"].asString())
        // Änderung ohne depot_id lässt den Betriebshof unverändert; mit null entfernt ihn; ein fremder Betriebshof ist nicht speicherbar
        val keep = put(id, created.header("ETag")!!, ""); assertEquals(200, keep.status, keep.text); assertEquals(depot.toString(), keep.json["depot_id"].asString())
        assertEquals(422, put(id, keep.header("ETag")!!, ""","depot_id":"$fdepot"""").status)
        val cleared = put(id, keep.header("ETag")!!, ""","depot_id":null"""); assertEquals(200, cleared.status, cleared.text); assertTrue(cleared.json["depot_id"].isNull)
        assertTrue(env.get("/v1/operator/vehicle-master/$id", w.dispatcherToken).json["depot_id"].isNull)
    }
}
