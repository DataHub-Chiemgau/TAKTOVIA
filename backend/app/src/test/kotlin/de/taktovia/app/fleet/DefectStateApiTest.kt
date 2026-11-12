package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * LH-6.22-DMG-007, WP-M3-47 über die echte Anwendung: Übergänge per `POST /v1/defects/{id}/transitions` mit `If-Match` (428/412), 409 bei unzulässigem Sprung auch bei direktem API-Aufruf,
 * 403 ohne Rolle, 404 für fremde Akten, Statushistorie, Wiedereröffnung, Sperre bleibt.
 */
class DefectStateApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    private fun report(w: DefectApiTest.World): Pair<String, String> {
        val r = env.post("/v1/defects", w.body(), w.dispatcherToken, null, idem()); assertEquals(201, r.status, r.text)
        return r.json["id"].asString() to r.header("ETag")!!
    }

    private fun transition(id: String, token: String, body: String, ifMatch: String? = null) =
        env.server.send("POST", "/v1/defects/$id/transitions", body, env.headers(token, extra = idem() + (if (ifMatch != null) mapOf("If-Match" to ifMatch) else emptyMap())))

    @Test
    fun uebergaenge_mit_if_match_etag_und_historie() {
        val w = DefectApiTest.World(env); val (id, etag) = report(w)
        assertEquals("\"1\"", etag)
        val r1 = transition(id, w.dispatcherToken, """{"to":"REVIEWED"}""", etag)
        assertEquals(200, r1.status, r1.text); assertEquals("REVIEWED", r1.json["status"].asString()); assertEquals("\"2\"", r1.header("ETag"))
        env.exec("UPDATE taktovia.defect_case SET severity='COSMETIC' WHERE id=?", UUID.fromString(id))
        val r2 = transition(id, w.workshopToken, """{"to":"CLOSED"}""", "\"2\"")
        assertEquals(200, r2.status, r2.text); assertEquals("CLOSED", r2.json["status"].asString()); assertEquals(3, r2.json["revision"].asInt())
        // Wiedereröffnung: dokumentiert, Betriebsleitung darf
        val r3 = transition(id, w.adminToken, """{"to":"REVIEWED","reopen":true,"reason":"Neuer Befund"}""", "\"3\"")
        assertEquals(200, r3.status, r3.text); assertEquals("REVIEWED", r3.json["status"].asString())
        val h = env.get("/v1/defects/$id/status-history", w.workshopToken)
        assertEquals(200, h.status, h.text)
        assertEquals(listOf("NEW", "REVIEWED", "CLOSED", "REVIEWED"), h.json["items"].values().map { it["to_status"].asString() })
        assertEquals(listOf(false, false, false, true), h.json["items"].values().map { it["reopening"].asBoolean() })
        assertTrue(h.json["items"].values().all { it["actor"].asString().startsWith("user:") })
        assertEquals("Neuer Befund", h.json["items"][3]["reason"].asString())
        assertEquals(403, env.get("/v1/defects/$id/status-history", w.driverToken).status)
        assertEquals(403, env.get("/v1/defects/$id/status-history", w.supportToken).status)
    }

    @Test
    fun ohne_if_match_428_veraltet_412_unzulaessig_409_ohne_rolle_403_fremder_mandant_404() {
        val w = DefectApiTest.World(env); val (id, _) = report(w)
        assertEquals(428, transition(id, w.dispatcherToken, """{"to":"REVIEWED"}""").status)
        assertEquals(412, transition(id, w.dispatcherToken, """{"to":"REVIEWED"}""", "\"7\"").status)
        // unzulässiger Sprung über direkten API-Aufruf: NEW -> CLOSED und NEW -> REPAIR
        for (to in listOf("CLOSED", "REPAIR", "IN_PROGRESS")) {
            val r = transition(id, w.workshopToken, """{"to":"$to"}""", "\"1\"")
            assertEquals(409, r.status, "$to: ${r.text}"); assertEquals("INVALID_STATE_TRANSITION", r.json["code"].asString())
        }
        assertEquals(422, transition(id, w.workshopToken, """{"to":"NEW"}""", "\"1\"").status)
        assertEquals(422, transition(id, w.workshopToken, """{"to":"DISMISSED"}""", "\"1\"").also { assertEquals("/reason", it.json["violations"][0]["pointer"].asString()) }.status)
        assertEquals(403, transition(id, w.driverToken, """{"to":"REVIEWED"}""", "\"1\"").status)
        assertEquals(403, transition(id, w.passengerToken, """{"to":"REVIEWED"}""", "\"1\"").status)
        assertEquals(403, transition(id, w.dispatcherToken, """{"to":"DISMISSED","reason":"x"}""", "\"1\"").status)   // Verwerfen: Werkstatt
        val b = DefectApiTest.World(env)
        assertEquals(404, transition(id, b.workshopToken, """{"to":"REVIEWED"}""", "\"1\"").status)
        assertEquals(404, env.get("/v1/defects/$id/status-history", b.workshopToken).status)
        assertEquals("NEW", env.string("SELECT status FROM taktovia.defect_case WHERE id=?", UUID.fromString(id)).single())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.defect_status_history WHERE defect_id=?", UUID.fromString(id)))
    }

    @Test
    fun verwerfen_mit_begruendung_und_abschluss_hebt_sperre_nicht_auf() {
        val w = DefectApiTest.World(env); val (id, _) = report(w)
        val blockId = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.vehicle_block(tenant_id,id,vehicle_id,source,reason_code,set_by) VALUES (?,?,?,'SAFETY','DEFECT_CRITICAL','user:11111111-1111-4111-8111-111111111111')", w.tenant.value, blockId, w.vehicle)
        val r = transition(id, w.workshopToken, """{"to":"DISMISSED","reason":"Fehlmeldung"}""", "\"1\"")
        assertEquals(200, r.status, r.text); assertEquals("DISMISSED", r.json["status"].asString())
        assertEquals("ACTIVE", env.string("SELECT status FROM taktovia.vehicle_block WHERE id=?", blockId).single())
        assertFalse(env.string("SELECT status FROM taktovia.defect_case WHERE id=?", UUID.fromString(id)).single() == "NEW")
        // Endzustand: ohne Wiedereröffnung 409
        assertEquals(409, transition(id, w.workshopToken, """{"to":"REVIEWED","reason":"nochmal"}""", "\"2\"").status)
    }
}
