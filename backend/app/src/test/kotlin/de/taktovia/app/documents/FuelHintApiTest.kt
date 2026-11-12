package de.taktovia.app.documents

import de.taktovia.app.contract.W10C5ContractCases
import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP-M4-31 über die echte Anwendung: Hinweise im Beleg (`hints`, `plausibility_notes`), Quittierung per `POST …/hints/{id}/acknowledgement` (If-Match, Begründung, 403 ohne Recht), Hinweis ändert keine Werte. */
class FuelHintApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())
    private fun im(rev: Long) = mapOf("If-Match" to "\"$rev\"")
    private fun post(path: String, body: String, token: String, rev: Long?) = env.server.send("POST", path, body, env.headers(token, extra = idem() + (rev?.let { im(it) } ?: emptyMap())))
    private fun tools.jackson.databind.JsonNode.strings() = (0 until size()).map { get(it).asString() }

    @Test
    fun hinweis_ist_einsehbar_aendert_keine_werte_und_wird_mit_begruendung_quittiert() {
        val w = W10C5ContractCases.World(); val (doc, hint, rev) = w.documentWithHint()
        val e = env.get("/v1/expenses/$doc", w.token).json
        assertEquals(1, e["hints"].size()); assertEquals("FUEL_VOLUME", e["hints"][0]["rule"].asString()); assertEquals("80", e["hints"][0]["threshold"].asString()); assertEquals("85", e["hints"][0]["actual_value"].asString())
        assertEquals("80", e["hints"][0]["basis"]["comparison_value"].asString()); assertTrue(e["hints"][0]["acknowledgement"].isNull)
        assertEquals("85", e["fuel"]["quantity"].asString(), "der erfasste Wert bleibt unverändert"); assertTrue("HINT_UNACKNOWLEDGED" in e["blockers"].strings()); assertFalse(e["approvable"].asBoolean())
        assertEquals(setOf("FUEL_PRICE", "FUEL_ODOMETER_GAP"), (0 until e["plausibility_notes"].size()).map { e["plausibility_notes"][it]["rule"].asString() }.toSet(), "Preisbasis nicht konfiguriert, kein Vorgänger-Kilometerstand")
        val path = "/v1/expenses/$doc/hints/$hint/acknowledgement"
        assertEquals(428, post(path, """{"reason":"Großtank"}""", w.token, null).status)
        assertEquals(422, post(path, """{"reason":"ab"}""", w.token, rev).status); assertEquals(422, post(path, """{}""", w.token, rev).status); assertEquals(422, post(path, """{"reason":"Großtank","extra":1}""", w.token, rev).status)
        assertEquals(412, post(path, """{"reason":"Großtank"}""", w.token, rev - 1).status)
        assertEquals(404, post("/v1/expenses/$doc/hints/${UUID.randomUUID()}/acknowledgement", """{"reason":"Großtank"}""", w.token, rev).status)
        val ok = post(path, """{"reason":"Großtank, Sondertour"}""", w.token, rev)
        assertEquals(200, ok.status, ok.text); assertEquals("Großtank, Sondertour", ok.json["hints"][0]["acknowledgement"]["reason"].asString()); assertFalse("HINT_UNACKNOWLEDGED" in ok.json["blockers"].strings())
        assertEquals("85", ok.json["fuel"]["quantity"].asString()); assertEquals(rev.toInt(), ok.json["revision"].asInt(), "die Quittierung ändert die Belegrevision nicht")
        assertEquals(200, post(path, """{"reason":"zweiter Versuch"}""", w.token, rev).status, "Wiederholung liefert den unveränderten Stand")
    }

    @Test
    fun ohne_recht_403_fremder_mandant_404() {
        val w = W10C5ContractCases.World(); val (doc, hint, rev) = w.documentWithHint(); val other = W10C5ContractCases.World()
        val path = "/v1/expenses/$doc/hints/$hint/acknowledgement"
        val driver = UUID.randomUUID(); env.grant(w.tenant, driver, "driver")
        assertEquals(403, post(path, """{"reason":"Großtank"}""", env.token(driver), rev).status, "Fahrer hat kein Prüfrecht")
        assertEquals(401, env.server.send("POST", path, """{"reason":"Großtank"}""", mapOf("Idempotency-Key" to "k-" + UUID.randomUUID()) + im(rev)).status)
        assertEquals(404, post(path, """{"reason":"Großtank"}""", other.token, rev).status, "Beleg eines anderen Mandanten")
        assertEquals(0, env.get("/v1/expenses/$doc", w.token).json["hints"].let { h -> (0 until h.size()).count { !h[it]["acknowledgement"].isNull } }, "abgewiesene Versuche quittieren nichts")
    }
}
