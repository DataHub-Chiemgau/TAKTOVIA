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

/** WP-M4-29 über die echte Anwendung: Kostenzuordnung per `PATCH /v1/expenses/{id}` (If-Match), zuordenbare Kostenobjekte, 404 ohne Existenzhinweis bei fremden Kennungen, Freigabefähigkeit. */
class CostAssignmentApiTest {
    private val env = IdentityEnv
    private val store: TenantObjectStore get() = env.server.ctx.getBean(TenantObjectStore::class.java)
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())
    private fun im(rev: Long) = mapOf("If-Match" to "\"$rev\"")
    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(b))

    private inner class World {
        val tenant = env.tenant("Kosten29-" + UUID.randomUUID()); val le = UUID.randomUUID(); val le2 = UUID.randomUUID(); val finance = UUID.randomUUID(); val driver = UUID.randomUUID()
        val ks = UUID.randomUUID(); val ksOther = UUID.randomUUID(); val vehicle = UUID.randomUUID()
        init {
            env.grant(tenant, finance, "finance"); env.grant(tenant, driver, "driver")
            env.tx.run(tenant) { j ->
                j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE'), (?,?,'LE2','DE')", tenant.value, le, tenant.value, le2)
                j.update("INSERT INTO taktovia.cost_object(tenant_id,id,legal_entity_id,kind,business_ref,name) VALUES (?,?,?,'COST_CENTER','K-1','Werkstatt'), (?,?,?,'COST_CENTER','K-2','Anderer Rechtsträger')", tenant.value, ks, le, tenant.value, ksOther, le2)
                j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'org',CAST('{}' AS jsonb))", tenant.value, vehicle)
            }
        }
        val financeToken = env.token(finance); val driverToken = env.token(driver)

        fun document(token: String = financeToken): String {
            val bytes = ("%PDF-1.4\n${UUID.randomUUID()}\n" + "x".repeat(600)).toByteArray(Charsets.ISO_8859_1)
            val t = env.post("/v1/expenses/receipts", """{"legal_entity_id":"$le","purpose":"RECEIPT","mime":"application/pdf","size_bytes":${bytes.size},"sha256":"${b64(bytes)}"}""", token, extra = idem())
            check(t.status == 201) { t.text }
            store.put(ObjectKey(tenant, t.json["upload_key"].asString()), bytes, "application/pdf")
            val d = env.post("/v1/expenses/receipts/${t.json["ticket_id"].asString()}/complete", "", token, extra = idem() + im(1))
            check(d.status == 201 && d.json["state"].asString() == "STORED") { d.text }
            return d.json["document_id"].asString()
        }
    }

    private fun patch(path: String, body: String, token: String, ifMatch: Long?) = env.server.send("PATCH", path, body, env.headers(token, extra = idem() + (ifMatch?.let { im(it) } ?: emptyMap())))

    @Test
    fun zuordnung_per_patch_macht_den_beleg_freigabefaehig() {
        val w = World(); val doc = w.document()
        val before = env.get("/v1/expenses/$doc", w.financeToken).json
        assertFalse(before["approvable"].asBoolean()); assertTrue((0 until before["blockers"].size()).any { before["blockers"][it].asString() == "COST_ASSIGNMENT_MISSING" })
        assertEquals(428, patch("/v1/expenses/$doc", """{"cost_assignment":{"cost_object_ids":["${w.ks}"]}}""", w.financeToken, null).status)
        assertEquals(422, patch("/v1/expenses/$doc", """{"cost_assignment":{"cost_object_ids":[]}}""", w.financeToken, 1).status, "leere Zuordnung")
        assertEquals(422, patch("/v1/expenses/$doc", """{"cost_assignment":{"cost_object_ids":["${w.ks}"]},"fields":{"amount_minor":100},"reason":"x"}""", w.financeToken, 1).status, "nicht mit Feldkorrektur kombinierbar")
        val ok = patch("/v1/expenses/$doc", """{"cost_assignment":{"cost_object_ids":["${w.ks}"],"vehicle_ids":["${w.vehicle}"]}}""", w.financeToken, 1)
        assertEquals(200, ok.status, ok.text); assertEquals("\"2\"", ok.header("ETag"))
        assertFalse(ok.json["approvable"].asBoolean(), "Pflichtfelder sind noch unbestätigt (WP-M4-28)"); assertEquals(1, ok.json["blockers"].size()); assertEquals("UNCONFIRMED_FIELDS", ok.json["blockers"][0].asString()); assertEquals(2, ok.json["cost_objects"].size())
        assertEquals(setOf("COST_CENTER", "VEHICLE"), (0 until 2).map { ok.json["cost_objects"][it]["kind"].asString() }.toSet())
        assertEquals(412, patch("/v1/expenses/$doc", """{"cost_assignment":{"vehicle_ids":["${w.vehicle}"]}}""", w.financeToken, 1).status)
        // Ersetzen durch nur das Fahrzeug; die Revisionen zeigen die Zuordnung
        assertEquals(200, patch("/v1/expenses/$doc", """{"cost_assignment":{"vehicle_ids":["${w.vehicle}"]}}""", w.financeToken, 2).status)
        val revs = env.get("/v1/expenses/$doc/revisions", w.financeToken).json["items"]
        assertEquals(3, revs.size()); assertEquals(2, revs[2]["fields"]["cost_assignment"]["old"].size()); assertEquals(1, revs[2]["fields"]["cost_assignment"]["new"].size())
    }

    @Test
    fun fremde_und_unzulaessige_zuordnung_wird_abgewiesen() {
        val w = World(); val other = World(); val doc = w.document()
        val unknown = patch("/v1/expenses/$doc", """{"cost_assignment":{"cost_object_ids":["${UUID.randomUUID()}"]}}""", w.financeToken, 1)
        val foreign = patch("/v1/expenses/$doc", """{"cost_assignment":{"cost_object_ids":["${other.ks}"]}}""", w.financeToken, 1)
        assertEquals(404, unknown.status); assertEquals(404, foreign.status)
        assertEquals(listOf(unknown.json["type"]?.asString(), unknown.json["title"]?.asString(), unknown.json["detail"]?.asString()), listOf(foreign.json["type"]?.asString(), foreign.json["title"]?.asString(), foreign.json["detail"]?.asString()), "kein Existenzhinweis")
        assertEquals(404, patch("/v1/expenses/$doc", """{"cost_assignment":{"vehicle_ids":["${other.vehicle}"]}}""", w.financeToken, 1).status)
        assertEquals(422, patch("/v1/expenses/$doc", """{"cost_assignment":{"cost_object_ids":["${w.ksOther}"]}}""", w.financeToken, 1).status, "Kostenobjekt eines anderen Rechtsträgers")
        assertEquals(404, patch("/v1/expenses/${other.document()}", """{"cost_assignment":{"cost_object_ids":["${w.ks}"]}}""", w.financeToken, 1).status, "fremder Beleg")
        assertEquals(404, patch("/v1/expenses/$doc", """{"cost_assignment":{"cost_object_ids":["${w.ks}"]}}""", w.driverToken, 1).status, "Beleg eines anderen Erfassers")
        assertEquals(1, env.get("/v1/expenses/$doc", w.financeToken).json["revision"].asInt(), "keine abgewiesene Zuordnung ändert den Beleg")
    }

    @Test
    fun kostenobjekte_lesen_nur_zuordenbare_mandantengetrennt_mit_cursor() {
        val w = World(); val other = World()
        val all = env.get("/v1/cost-objects", w.financeToken)
        assertEquals(200, all.status, all.text)
        assertEquals(setOf(w.ks.toString(), w.ksOther.toString()), (0 until all.json["items"].size()).map { all.json["items"][it]["id"].asString() }.toSet())
        assertFalse(all.text.contains(other.ks.toString()), "keine Objekte eines anderen Mandanten")
        val page = env.get("/v1/cost-objects?limit=1", w.financeToken)
        assertEquals(1, page.json["items"].size()); val cursor = page.json["next_cursor"].asString()
        val next = env.get("/v1/cost-objects?limit=1&cursor=$cursor", w.financeToken)
        assertEquals(1, next.json["items"].size()); assertFalse(next.json["items"][0]["id"].asString() == page.json["items"][0]["id"].asString())
        assertEquals(400, env.get("/v1/cost-objects?limit=1&cursor=kaputt", w.financeToken).status)
        // Fahrer sehen nur Fahrzeuge (Kostenstellen sind nicht zuordenbar)
        val driver = env.get("/v1/cost-objects", w.driverToken)
        assertEquals(200, driver.status, driver.text); assertFalse(driver.text.contains(w.ks.toString()))
        assertEquals(422, env.get("/v1/cost-objects?kind=PROJECT", w.financeToken).status)
    }
}
