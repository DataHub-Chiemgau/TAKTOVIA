package de.taktovia.app.documents

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** WP-M4-30 über die echte Anwendung: Tankwerte per `PATCH /v1/expenses/{id}` (`fuel`, If-Match), Dezimalstrings statt Gleitkomma, fehlende Pflichtwerte einzeln, fremdes Fahrzeug 404. */
class FuelReceiptApiTest {
    private val env = IdentityEnv
    private val store: TenantObjectStore get() = env.server.ctx.getBean(TenantObjectStore::class.java)
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())
    private fun im(rev: Long) = mapOf("If-Match" to "\"$rev\"")
    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(b))

    private inner class World {
        val tenant = env.tenant("Tank30-" + UUID.randomUUID()); val le = UUID.randomUUID(); val driver = UUID.randomUUID(); val finance = UUID.randomUUID(); val vehicle = UUID.randomUUID()
        init {
            env.grant(tenant, finance, "finance"); env.grant(tenant, driver, "driver")
            env.tx.run(tenant) { j ->
                j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", tenant.value, le)
                j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'org',CAST('{}' AS jsonb))", tenant.value, vehicle)
            }
        }
        val driverToken = env.token(driver); val financeToken = env.token(finance)

        fun fuelDocument(kind: String = "FUEL_RECEIPT"): String {
            val bytes = ("%PDF-1.4\n${UUID.randomUUID()}\n" + "x".repeat(600)).toByteArray(Charsets.ISO_8859_1)
            val t = env.post("/v1/expenses/receipts", """{"legal_entity_id":"$le","purpose":"RECEIPT","mime":"application/pdf","size_bytes":${bytes.size},"sha256":"${b64(bytes)}","source_channel":"DRIVER_APP","document_kind":"$kind"}""", driverToken, extra = idem())
            check(t.status == 201) { t.text }
            store.put(ObjectKey(tenant, t.json["upload_key"].asString()), bytes, "application/pdf")
            val d = env.post("/v1/expenses/receipts/${t.json["ticket_id"].asString()}/complete", "", driverToken, extra = idem() + im(1))
            check(d.status == 201 && d.json["state"].asString() == "STORED") { d.text }
            return d.json["document_id"].asString()
        }
    }

    private fun patch(path: String, body: String, token: String, ifMatch: Long?) = env.server.send("PATCH", path, body, env.headers(token, extra = idem() + (ifMatch?.let { im(it) } ?: emptyMap())))
    private fun tools.jackson.databind.JsonNode.strings() = (0 until size()).map { get(it).asString() }

    @Test
    fun tankwerte_per_patch_dezimalstrings_und_fehlende_pflichtwerte_einzeln() {
        val w = World(); val doc = w.fuelDocument()
        val before = env.get("/v1/expenses/$doc", w.driverToken).json
        assertEquals("FUEL_RECEIPT", before["document_kind"].asString()); assertEquals(listOf("vehicle_id", "fuel_type", "quantity", "unit_price", "odometer_km"), before["fuel"]["missing"].strings())
        assertTrue("FUEL_FIELDS_MISSING" in before["blockers"].strings()); assertFalse(before["approvable"].asBoolean())
        assertEquals(428, patch("/v1/expenses/$doc", """{"fuel":{"quantity":"40.5"}}""", w.driverToken, null).status)
        val p = patch("/v1/expenses/$doc", """{"fuel":{"fuel_type":"DIESEL","quantity":"40.5","unit_price":"1.679","odometer_km":120500,"fueled_at":"2026-06-15T08:00:00Z","card_id":"KARTE-1"}}""", w.driverToken, 1)
        assertEquals(200, p.status, p.text); assertEquals("\"2\"", p.header("ETag"))
        assertEquals(listOf("vehicle_id"), p.json["fuel"]["missing"].strings()); assertEquals("40.5", p.json["fuel"]["quantity"].asString()); assertEquals("1.679", p.json["fuel"]["unit_price"].asString())
        assertEquals("LITER", p.json["fuel"]["unit"].asString()); assertFalse(p.json["fuel"]["complete"].asBoolean())
        val full = patch("/v1/expenses/$doc", """{"fuel":{"vehicle_id":"${w.vehicle}"}}""", w.driverToken, 2)
        assertEquals(200, full.status, full.text); assertTrue(full.json["fuel"]["complete"].asBoolean()); assertEquals(0, full.json["fuel"]["missing"].size())
        // die Erfassung ersetzt nie die Finanzprüfung: weiter nicht freigabefähig (Pflichtfelder unbestätigt), Zustand unverändert
        assertEquals("STORED", full.json["state"].asString()); assertTrue("UNCONFIRMED_FIELDS" in full.json["blockers"].strings())
        assertEquals(412, patch("/v1/expenses/$doc", """{"fuel":{"odometer_km":5}}""", w.driverToken, 2).status)
        assertEquals(3, env.get("/v1/expenses/$doc/revisions", w.financeToken).json["items"].size())
    }

    @Test
    fun gleitkomma_negative_werte_und_unbekannte_felder_werden_abgewiesen() {
        val w = World(); val doc = w.fuelDocument()
        for (body in listOf("""{"fuel":{"quantity":40.5}}""", """{"fuel":{"unit_price":1.679}}""", """{"fuel":{"quantity":"-3"}}""", """{"fuel":{"quantity":"0"}}""", """{"fuel":{"quantity":"1.123456789"}}""",
            """{"fuel":{"quantity":"1e3"}}""", """{"fuel":{"odometer_km":12.5}}""", """{"fuel":{"odometer_km":-1}}""", """{"fuel":{"fuel_type":"KEROSIN"}}""", """{"fuel":{"fueled_at":"2026-06-15T08:00:00"}}""",
            """{"fuel":{}}""", """{"fuel":{"unbekannt":"x"}}""")) {
            assertEquals(422, patch("/v1/expenses/$doc", body, w.driverToken, 1).status, body)
        }
        assertEquals(422, patch("/v1/expenses/$doc", """{"fuel":{"quantity":"5"},"fields":{"amount_minor":100},"reason":"x"}""", w.driverToken, 1).status, "nicht mit Feldkorrektur kombinierbar")
        assertEquals(1, env.get("/v1/expenses/$doc", w.driverToken).json["revision"].asInt(), "abgewiesene Eingaben ändern den Beleg nicht")
    }

    @Test
    fun fremdes_fahrzeug_fremder_beleg_und_falsche_belegart() {
        val w = World(); val other = World(); val doc = w.fuelDocument()
        val unknown = patch("/v1/expenses/$doc", """{"fuel":{"vehicle_id":"${UUID.randomUUID()}"}}""", w.driverToken, 1)
        val foreign = patch("/v1/expenses/$doc", """{"fuel":{"vehicle_id":"${other.vehicle}"}}""", w.driverToken, 1)
        assertEquals(404, unknown.status); assertEquals(404, foreign.status); assertEquals(unknown.json["detail"]?.asString(), foreign.json["detail"]?.asString(), "kein Existenzhinweis")
        assertEquals(404, patch("/v1/expenses/$doc", """{"fuel":{"quantity":"1"}}""", other.driverToken, 1).status, "Beleg eines anderen Mandanten")
        assertEquals(422, patch("/v1/expenses/${w.fuelDocument("RECEIPT")}", """{"fuel":{"quantity":"1"}}""", w.driverToken, 1).status, "Beleg anderer Art")
        assertEquals(null, env.get("/v1/expenses/${w.fuelDocument("RECEIPT")}", w.driverToken).json["fuel"].let { if (it == null || it.isNull) null else it })
    }
}
