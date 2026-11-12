package de.taktovia.app.documents

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * WP-M4-25 und WP-M4-26 über die echte Anwendung (HTTP, Test-Issuer, Runtime-Rolle): idempotente Anlage über die Client-UUID (201 neu, 200 vorhanden/Wiederaufnahme, 409 bei anderem Hash),
 * Zuordnung nur zu zugewiesenen Vorgängen (403), bearbeitete Ansicht getrennt vom Rohbild, 401 ohne Anmeldung.
 */
class ReceiptCaptureApiTest {
    private val env = IdentityEnv
    private val store: TenantObjectStore get() = env.server.ctx.getBean(TenantObjectStore::class.java)
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    private class World(val env: IdentityEnv) {
        val tenant = env.tenant("Kamera-" + UUID.randomUUID()); val le = UUID.randomUUID()
        val driver = UUID.randomUUID(); val other = UUID.randomUUID()
        val ownVehicle = UUID.randomUUID(); val foreignVehicle = UUID.randomUUID()
        init {
            env.grant(tenant, driver, "driver"); env.grant(tenant, other, "driver")
            env.tx.run(tenant) { j ->
                j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", tenant.value, le)
                for ((v, d) in listOf(ownVehicle to driver, foreignVehicle to other)) {
                    j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,active_block,safety_revision) VALUES (?,?,'org-c7',CAST(? AS jsonb),false,1)", tenant.value, v, """{"seats":4,"wheelchair":0}""")
                    j.update("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,'2026-12-01 04:00:00+00','2026-12-01 14:00:00+00','Europe/Berlin',1,'PUBLISHED')", tenant.value, UUID.randomUUID(), d, v)
                }
            }
        }
        val driverToken = env.token(driver); val otherToken = env.token(other)
    }

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun jpeg(seed: Int) = ByteArray(2048) { ((it * 31 + seed) and 0xFF).toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
    private fun body(w: World, bytes: ByteArray, client: UUID, vehicle: UUID?, hash: String = b64(bytes)) =
        """{"legal_entity_id":"${w.le}","purpose":"RECEIPT","mime":"image/jpeg","size_bytes":${bytes.size},"sha256":"$hash","source_channel":"DRIVER_APP","document_kind":"FUEL_RECEIPT",""" +
            """"client_uuid":"$client","captured_at":"2026-01-01T08:00:00Z"${if (vehicle != null) ""","case_ref":{"kind":"VEHICLE","id":"$vehicle"}""" else ""}}"""

    @Test
    fun idempotente_anlage_ueber_client_uuid_ueber_http() {
        val w = World(env); val bytes = jpeg(1); val client = UUID.randomUUID()
        val t = env.post("/v1/expenses/receipts", body(w, bytes, client, w.ownVehicle), w.driverToken, extra = idem())
        assertEquals(201, t.status, t.text); assertEquals(client.toString(), t.json["client_uuid"].asString()); assertTrue(t.json["document_id"].isNull)
        // abgebrochener Upload: dasselbe Ticket wird wieder ausgegeben (200)
        val resumed = env.post("/v1/expenses/receipts", body(w, bytes, client, w.ownVehicle), w.driverToken, extra = idem())
        assertEquals(200, resumed.status, resumed.text); assertEquals(t.json["ticket_id"].asString(), resumed.json["ticket_id"].asString())
        store.put(ObjectKey(w.tenant, t.json["upload_key"].asString()), bytes, "image/jpeg")
        val done = env.post("/v1/expenses/receipts/${t.json["ticket_id"].asString()}/complete", "", w.driverToken, extra = idem() + ("If-Match" to "\"1\""))
        assertEquals(201, done.status, done.text); assertEquals("STORED", done.json["state"].asString()); assertEquals(b64(bytes), done.json["sha256"].asString()); assertEquals(client.toString(), done.json["client_uuid"].asString())
        assertTrue(done.json["captured_at"].asString().startsWith("2026-01-01T08:00:00"), "Erfassungszeit des Geräts bleibt erhalten")
        assertNotEquals(done.json["captured_at"].asString(), done.json["received_at"].asString())
        // wiederholter Abschluss und wiederholte Anlage: derselbe Beleg, 200
        val again = env.post("/v1/expenses/receipts/${t.json["ticket_id"].asString()}/complete", "", w.driverToken, extra = idem() + ("If-Match" to "\"1\""))
        assertEquals(200, again.status, again.text); assertEquals(done.json["document_id"].asString(), again.json["document_id"].asString())
        val known = env.post("/v1/expenses/receipts", body(w, bytes, client, w.ownVehicle), w.driverToken, extra = idem())
        assertEquals(200, known.status, known.text); assertEquals("CONSUMED", known.json["state"].asString()); assertEquals(done.json["document_id"].asString(), known.json["document_id"].asString())
        // dieselbe UUID mit anderem Hash: 409, nichts überschrieben
        assertEquals(409, env.post("/v1/expenses/receipts", body(w, jpeg(2), client, w.ownVehicle), w.driverToken, extra = idem()).status)
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=? AND client_uuid=?", w.tenant.value, client))
    }

    @Test
    fun fremdes_fahrzeug_403_und_bearbeitete_ansicht_getrennt_vom_rohbild() {
        val w = World(env); val bytes = jpeg(3); val client = UUID.randomUUID()
        assertEquals(403, env.post("/v1/expenses/receipts", body(w, bytes, client, w.foreignVehicle), w.driverToken, extra = idem()).status, "fremdes Fahrzeug")
        assertEquals(404, env.post("/v1/expenses/receipts", body(w, bytes, client, UUID.randomUUID()), w.driverToken, extra = idem()).status, "unbekanntes Fahrzeug")
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.upload_ticket WHERE tenant_id=?", w.tenant.value))
        val t = env.post("/v1/expenses/receipts", body(w, bytes, client, w.ownVehicle), w.driverToken, extra = idem())
        assertEquals(201, t.status, t.text)
        store.put(ObjectKey(w.tenant, t.json["upload_key"].asString()), bytes, "image/jpeg")
        val d = env.post("/v1/expenses/receipts/${t.json["ticket_id"].asString()}/complete", "", w.driverToken, extra = idem() + ("If-Match" to "\"1\""))
        val id = d.json["document_id"].asString()
        val edited = jpeg(4).copyOf(900)
        val view = """{"mime":"image/jpeg","content_base64":"${Base64.getEncoder().encodeToString(edited)}"}"""
        assertEquals(404, env.get("/v1/expenses/receipts/$id/edited-view", w.driverToken).status, "noch keine bearbeitete Ansicht")
        assertEquals(428, env.server.send("PUT", "/v1/expenses/receipts/$id/edited-view", view, env.headers(w.driverToken, extra = idem())).status, "If-Match ist Pflicht")
        assertEquals(404, env.put("/v1/expenses/receipts/$id/edited-view", view, w.otherToken, "\"1\"").status, "anderer Fahrer")
        assertEquals(422, env.put("/v1/expenses/receipts/$id/edited-view", """{"mime":"image/png","content_base64":"AAAA"}""", w.driverToken, "\"1\"").status, "Typ passt nicht zum Inhalt")
        val put = env.put("/v1/expenses/receipts/$id/edited-view", view, w.driverToken, "\"1\"")
        assertEquals(200, put.status, put.text); assertEquals(2, put.json["revision"].asInt()); assertEquals(b64(bytes), put.json["sha256"].asString(), "das Original bleibt unverändert")
        assertEquals(200, env.get("/v1/expenses/receipts/$id/edited-view", w.driverToken).status)
        assertEquals("image/jpeg", env.get("/v1/expenses/receipts/$id/edited-view", w.driverToken).header("Content-Type"))
        assertEquals(200, env.get("/v1/expenses/$id/original", w.driverToken).status, "Rohbild weiterhin abrufbar")
        assertEquals(404, env.get("/v1/expenses/receipts/$id/edited-view", w.otherToken).status)
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.document_revision WHERE document_id=? AND edited_view_key IS NOT NULL", UUID.fromString(id)))
    }

    @Test
    fun ohne_anmeldung_401() {
        val w = World(env); val id = UUID.randomUUID()
        assertEquals(401, env.server.post("/v1/expenses/receipts", body(w, jpeg(5), UUID.randomUUID(), null), idem()).status)
        assertEquals(401, env.server.send("PUT", "/v1/expenses/receipts/$id/edited-view", """{"mime":"image/jpeg","content_base64":"AAAA"}""", idem() + ("If-Match" to "\"1\"")).status)
        assertEquals(401, env.server.get("/v1/expenses/receipts/$id/edited-view", emptyMap()).status)
    }
}
