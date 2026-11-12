package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M3-11 / LH-6.21-CHK-005, CHK-006 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak): Vorlage des Typs `VEHICLE_TECHNICAL` bzw. `PASSENGER_COMPARTMENT` ohne
 * einen Pflichtpunkt ist nicht freigebbar (422 mit Verweis je fehlendem Schlüssel, nichts freigegeben), vollständig ja; Rechte (Freigabe nur Betriebsleitung/Werkstatt), fremder Mandant 404.
 */
class MandatoryTemplateApiTest {
    private val env = IdentityEnv
    private fun idem(k: String = "k-" + UUID.randomUUID()) = mapOf("Idempotency-Key" to k)
    private val tech = listOf("BELEUCHTUNG", "REIFEN_SICHTPRUEFUNG", "SCHEIBEN", "SPIEGEL", "TUEREN", "SICHERHEITSGURTE", "SICHTBARE_LECKAGEN", "WARNANZEIGEN")
    private val board = listOf("SAUBERKEIT", "FAHRGASTRAUM", "SITZPLAETZE", "EINSTIEGSBEREICH", "RUECKHALTESYSTEME", "SICHERHEITSAUSRUESTUNG")

    private fun body(type: String, keys: List<String>, cls: String = "PKW") =
        """{"name":"Pflicht $type","vehicle_class":"$cls","service_mode":"STANDARD","template_type":"$type","steps":[""" +
            keys.joinToString(",") { """{"step_code":"$it","title":"$it","input_kind":"OK_DEFECT_NA","mandatory":true,"safety_critical":false}""" } + "]}"

    private fun publish(id: String, token: String) = env.server.send("POST", "/v1/operator/checklist-templates/$id/publish", null, env.headers(token, extra = idem() + mapOf("If-Match" to "\"1\"")), null)

    @Test
    fun unvollstaendige_pflichtvorlage_422_vollstaendige_wird_freigegeben() {
        val w = InspectionApiTest.World(env)
        val author = env.token(w.author); val admin = env.token(w.reviewer)
        for ((type, keys) in listOf("VEHICLE_TECHNICAL" to tech, "PASSENGER_COMPARTMENT" to board)) {
            val missing = keys[3]
            val c = env.post("/v1/operator/checklist-templates", body(type, keys.filter { it != missing }), author, null, idem())
            assertEquals(201, c.status, c.text); assertEquals(type, c.json["template_type"].asString())
            val bad = publish(c.json["id"].asString(), admin)
            assertEquals(422, bad.status, bad.text); assertEquals("/steps/$missing", bad.json["violations"][0]["pointer"].asString()); assertEquals(1, bad.json["violations"].size())
            assertTrue(bad.json["detail"].asString().startsWith("MANDATORY_POINTS_MISSING: $missing"))
            assertEquals("DRAFT", env.get("/v1/operator/checklist-templates/${c.json["id"].asString()}", author).json["state"].asString())
            // vollständig: freigegeben, Typ bleibt sichtbar
            val ok = env.post("/v1/operator/checklist-templates", body(type, keys, cls = "BUS"), author, null, idem())
            assertEquals(201, ok.status, ok.text)
            val pub = publish(ok.json["id"].asString(), admin)
            assertEquals(200, pub.status, pub.text); assertEquals("PUBLISHED", pub.json["state"].asString()); assertEquals(type, pub.json["template_type"].asString()); assertEquals(keys.size, pub.json["steps"].size())
        }
        // Rechte und Mandanten: Fahrer und Disposition geben nicht frei, fremder Mandant 404; ungültiger Typ 422
        val c = env.post("/v1/operator/checklist-templates", body("VEHICLE_TECHNICAL", tech, cls = "KLEINBUS"), author, null, idem())
        assertEquals(403, publish(c.json["id"].asString(), w.token).status)
        assertEquals(403, publish(c.json["id"].asString(), w.dispatcherToken).status)
        assertEquals(404, publish(c.json["id"].asString(), env.token(InspectionApiTest.World(env).reviewer)).status)
        assertEquals(422, env.post("/v1/operator/checklist-templates", body("ANDERE", tech), author, null, idem()).status)
    }
}
