package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

/**
 * WP-M3-10 / LH-6.21-CHK-003 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): Rechte (Werkstatt und Administrator verwalten, Disponent liest, Fahrer liest nur die freigegebenen
 * Revisionen), 422 mit Elementverweis, Vier-Augen-Freigabe, Unveränderlichkeit, Beispielbild über den Objektspeicher, Mandantentrennung (404).
 */
class ChecklistTemplateApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    class World(val env: IdentityEnv) {
        val tenant: TenantId = env.tenant(); val author = UUID.randomUUID(); val reviewer = UUID.randomUUID(); val dispatcher = UUID.randomUUID(); val driver = UUID.randomUUID()
        init { env.grant(tenant, author, "workshop"); env.grant(tenant, reviewer, "tenant_admin"); env.grant(tenant, dispatcher, "dispatcher"); env.grant(tenant, driver, "driver") }
        val authorToken = env.token(author); val reviewerToken = env.token(reviewer); val dispatcherToken = env.token(dispatcher); val driverToken = env.token(driver)
        private val passenger = UUID.randomUUID().also { env.grant(tenant, it, "passenger") }
        fun passengerToken() = env.token(passenger)
        fun body(cls: String = "PKW", steps: String = STEPS) = """{"name":"Fahrzeugprüfung","vehicle_class":"$cls","service_mode":"ONDEMAND","steps":$steps}"""
        companion object {
            const val STEPS = """[{"step_code":"BRAKES","title":"Bremsen","help_text":"Pedal treten","input_kind":"OK_DEFECT_NA","mandatory":true,"safety_critical":true,"na_requires_reason":true},""" +
                """{"step_code":"TYRE_DEPTH","title":"Profiltiefe","input_kind":"NUMBER","mandatory":true,"number_min":1.6,"number_max":10,"number_unit":"mm"},""" +
                """{"step_code":"REMARK","title":"Bemerkung","input_kind":"TEXT_SHORT","mandatory":false}]"""
        }
    }

    private fun create(w: World, body: String = w.body(), token: String = w.authorToken) = env.post("/v1/operator/checklist-templates", body, token, null, idem())
    private fun publish(id: String, revision: String, token: String) = env.server.send("POST", "/v1/operator/checklist-templates/$id/publish", null, env.headers(token, extra = idem() + ("If-Match" to revision)), null)

    @Test
    fun anlegen_lesen_freigeben_und_aendern_ueber_neue_revision() {
        val w = World(env)
        val c = create(w)
        assertEquals(201, c.status, c.text); assertEquals("\"1\"", c.header("ETag"))
        val id = c.json["id"].asString(); assertEquals("/v1/operator/checklist-templates/$id", c.header("Location"))
        assertEquals("DRAFT", c.json["state"].asString()); assertEquals(3, c.json["steps"].size()); assertEquals("Pedal treten", c.json["steps"][0]["help_text"].asString())
        // Fahrer sieht Entwürfe nicht in der Liste der freigegebenen Revisionen und darf nicht verwalten
        assertEquals(403, env.get("/v1/operator/checklist-templates/$id", w.driverToken).status)
        assertEquals(0, env.get("/v1/checklist-templates", w.driverToken).json["items"].size())
        assertEquals(200, env.get("/v1/operator/checklist-templates/$id", w.dispatcherToken).status)
        assertEquals(403, create(w, token = w.dispatcherToken).status)
        // Freigabe: ohne If-Match 428, veraltet 412, Ersteller selbst 403, zweite Person 200
        assertEquals(428, env.server.send("POST", "/v1/operator/checklist-templates/$id/publish", null, env.headers(w.reviewerToken, extra = idem()), null).status)
        assertEquals(412, publish(id, "\"9\"", w.reviewerToken).status)
        assertEquals(403, publish(id, "\"1\"", w.authorToken).status)
        val p = publish(id, "\"1\"", w.reviewerToken)
        assertEquals(200, p.status, p.text); assertEquals("PUBLISHED", p.json["state"].asString()); assertEquals("\"2\"", p.header("ETag")); assertEquals("user:${w.reviewer}", p.json["approved_by"].asString())
        assertEquals("user:${w.author}", p.json["created_by"].asString())
        // zweite Freigabe derselben Revision: 409; Änderung der freigegebenen Revision: 409
        assertEquals(409, publish(id, "\"2\"", w.reviewerToken).status)
        val upd = env.put("/v1/operator/checklist-templates/$id", w.body(), w.authorToken, "\"2\"")
        assertEquals(409, upd.status, upd.text)
        // Fahrer-App sieht die freigegebene Revision mit Hilfetext und Eingabetyp
        val pub = env.get("/v1/checklist-templates?vehicle_class=PKW", w.driverToken)
        assertEquals(200, pub.status, pub.text); assertEquals(1, pub.json["items"].size()); assertEquals("NUMBER", pub.json["items"][0]["steps"][1]["answer_kind"].asString())
        assertEquals(0, env.get("/v1/checklist-templates?vehicle_class=BUS", w.driverToken).json["items"].size())
        // Änderung = neue Revision; Entwurf ändern; Freigabe löst die alte Revision ab
        val n = env.server.send("POST", "/v1/operator/checklist-templates/$id/revisions", null, env.headers(w.authorToken, extra = idem() + ("If-Match" to "\"2\"")), null)
        assertEquals(201, n.status, n.text); assertEquals(2, n.json["version"].asInt()); assertEquals("DRAFT", n.json["state"].asString())
        val nid = n.json["id"].asString()
        assertEquals(409, env.server.send("POST", "/v1/operator/checklist-templates/$id/revisions", null, env.headers(w.authorToken, extra = idem() + ("If-Match" to "\"2\"")), null).status)   // schon ein Entwurf
        val u = env.put("/v1/operator/checklist-templates/$nid", w.body(steps = """[{"step_code":"LIGHTS","title":"Beleuchtung","input_kind":"OK_DEFECT_NA","mandatory":true}]"""), w.authorToken, "\"1\"")
        assertEquals(200, u.status, u.text); assertEquals("\"2\"", u.header("ETag"))
        assertEquals(200, publish(nid, "\"2\"", w.reviewerToken).status)
        assertEquals("RETIRED", env.get("/v1/operator/checklist-templates/$id", w.dispatcherToken).json["state"].asString())
        assertEquals(1, env.get("/v1/checklist-templates", w.driverToken).json["items"].size())
        assertEquals(1, env.get("/v1/operator/checklist-templates?state=RETIRED", w.dispatcherToken).json["items"].size())
        assertEquals(422, env.get("/v1/operator/checklist-templates?state=ARCHIV", w.dispatcherToken).status)
        // Audit mit Akteur, Ereignis für Nachfolger
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.checklist_template.published' AND object_ref=? AND actor_ref=?", w.tenant.value, "checklist_template:$id", "user:${w.reviewer}"))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='checklist_template.published'", w.tenant.value, UUID.fromString(nid)))
    }

    @Test
    fun ungueltiges_pruefelement_ergibt_422_mit_feldverweis_und_wird_nicht_gespeichert() {
        val w = World(env)
        fun step(extra: String) = """[{"step_code":"BRAKES",$extra}]"""
        val cases = listOf(
            "ohne Titel" to (step(""""input_kind":"OK_DEFECT_NA","mandatory":true""") to "/steps/0/title"),
            "ohne Eingabetyp" to (step(""""title":"T","mandatory":true""") to "/steps/0/input_kind"),
            "ohne Pflichtstatus" to (step(""""title":"T","input_kind":"OK_DEFECT_NA"""") to "/steps/0/mandatory"),
            "unbekannter Eingabetyp" to (step(""""title":"T","input_kind":"VIDEO","mandatory":true""") to "/steps/0/input_kind"),
        )
        for ((name, c) in cases) {
            val r = create(w, w.body(steps = c.first))
            assertEquals(422, r.status, "$name: ${r.text}"); assertTrue(r.text.contains(c.second), "$name: ${r.text}")
        }
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.checklist_template_revision WHERE tenant_id=?", w.tenant.value))
    }

    @Test
    fun beispielbild_nur_ueber_den_objektspeicher_des_eigenen_mandanten_und_fremder_mandant_404() {
        val w = World(env); val other = World(env)
        val id = create(w).json["id"].asString()
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
        val body = """{"content_type":"image/png","data_base64":"${Base64.getEncoder().encodeToString(png)}"}"""
        val put = env.put("/v1/operator/checklist-templates/$id/steps/BRAKES/example-image", body, w.authorToken, "\"1\"")
        assertEquals(200, put.status, put.text); assertTrue(put.json["steps"][0]["has_example_image"].asBoolean()); assertFalse(put.json["steps"][1]["has_example_image"].asBoolean())
        val get = env.get("/v1/operator/checklist-templates/$id/steps/BRAKES/example-image", w.dispatcherToken)
        assertEquals(200, get.status, get.text); assertEquals("image/png", get.json["content_type"].asString()); assertEquals(png.toList(), Base64.getDecoder().decode(get.json["data_base64"].asString()).toList())
        // anderer Mandant: Vorlage und Bild sind nicht auffindbar
        assertEquals(404, env.get("/v1/operator/checklist-templates/$id", other.authorToken).status)
        assertEquals(404, env.get("/v1/operator/checklist-templates/$id/steps/BRAKES/example-image", other.dispatcherToken).status)
        assertEquals(404, env.put("/v1/operator/checklist-templates/$id/steps/BRAKES/example-image", body, other.authorToken, "\"1\"").status)
        assertEquals(404, env.get("/v1/operator/checklist-templates/$id/steps/REMARK/example-image", w.dispatcherToken).status)   // kein Bild gesetzt
        assertEquals(415, env.put("/v1/operator/checklist-templates/$id/steps/BRAKES/example-image", """{"content_type":"image/gif","data_base64":"AAAA"}""", w.authorToken, "\"2\"").status)
        assertEquals(422, env.put("/v1/operator/checklist-templates/$id/steps/BRAKES/example-image", """{"content_type":"image/png","data_base64":"%%%"}""", w.authorToken, "\"2\"").status)
    }
}
