package de.taktovia.app.documents

import de.taktovia.app.contract.W10C5ContractCases
import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP-M4-32 über die echte Anwendung: Dublettenkandidaten in `GET /v1/expenses/{id}`, Entscheidung per `POST …/duplicates/{id}/decision` (If-Match, Begründung, 403 ohne Recht), Hashvergleich nur im Mandanten. */
class DuplicateApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())
    private fun im(rev: Long) = mapOf("If-Match" to "\"$rev\"")
    private fun post(path: String, body: String, token: String, rev: Long?) = env.server.send("POST", path, body, env.headers(token, extra = idem() + (rev?.let { im(it) } ?: emptyMap())))
    private fun tools.jackson.databind.JsonNode.strings() = (0 until size()).map { get(it).asString() }

    private fun dupDoc(w: W10C5ContractCases.World): Triple<String, String, Long> = with(W10C5ContractCases) { w.documentWithDuplicate() }

    @Test
    fun moeglicher_treffer_ist_einsehbar_blockiert_und_wird_mit_begruendung_entschieden() {
        val w = W10C5ContractCases.World(); val (doc, cand, rev) = dupDoc(w)
        val e = env.get("/v1/expenses/$doc", w.token).json
        val d = e["duplicates"][0]
        assertEquals("NEWER", d["role"].asString()); assertEquals("POSSIBLE", d["certainty"].asString()); assertEquals("FACTS", d["match"].asString()); assertEquals("OFFEN", d["state"].asString())
        assertEquals(cand, d["id"].asString()); assertTrue(d["score"].asInt() in 60..99); assertEquals(11900, d["other"]["amount_minor"].asInt()); assertEquals("tankstelle nord", d["other"]["merchant"].asString())
        assertTrue("DUPLICATE_REVIEW_OPEN" in e["blockers"].strings()); assertFalse(e["approvable"].asBoolean())
        val path = "/v1/expenses/$doc/duplicates/$cand/decision"
        assertEquals(428, post(path, """{"decision":"EIGENER_VORGANG","reason":"Zweite Tankung"}""", w.token, null).status)
        assertEquals(422, post(path, """{"decision":"EIGENER_VORGANG","reason":"ab"}""", w.token, rev).status); assertEquals(422, post(path, """{"decision":"VERSCHMELZEN","reason":"Zweite Tankung"}""", w.token, rev).status)
        assertEquals(422, post(path, """{"reason":"Zweite Tankung"}""", w.token, rev).status)
        assertEquals(412, post(path, """{"decision":"EIGENER_VORGANG","reason":"Zweite Tankung"}""", w.token, rev - 1).status)
        assertEquals(404, post("/v1/expenses/$doc/duplicates/${UUID.randomUUID()}/decision", """{"decision":"EIGENER_VORGANG","reason":"Zweite Tankung"}""", w.token, rev).status)
        val ok = post(path, """{"decision":"EIGENER_VORGANG","reason":"Zweite Tankung am selben Tag"}""", w.token, rev)
        assertEquals(200, ok.status, ok.text); assertEquals("EIGENER_VORGANG", ok.json["duplicates"][0]["state"].asString()); assertEquals("Zweite Tankung am selben Tag", ok.json["duplicates"][0]["reason"].asString())
        assertFalse("DUPLICATE_REVIEW_OPEN" in ok.json["blockers"].strings()); assertEquals(rev.toInt(), ok.json["revision"].asInt(), "die Entscheidung ändert die Belegrevision nicht")
        assertEquals(409, post(path, """{"decision":"GLEICHE_EVIDENZ","reason":"Doch dieselbe Tankung"}""", w.token, rev).status, "entschieden bleibt entschieden")
        assertEquals(200, post(path, """{"decision":"EIGENER_VORGANG","reason":"Wiederholung"}""", w.token, rev).status, "dieselbe Entscheidung: unverändert")
    }

    @Test
    fun ohne_recht_403_fremder_mandant_404_und_kein_treffer_ueber_mandanten() {
        val w = W10C5ContractCases.World(); val (doc, cand, rev) = dupDoc(w); val other = W10C5ContractCases.World()
        val path = "/v1/expenses/$doc/duplicates/$cand/decision"
        val driver = UUID.randomUUID(); env.grant(w.tenant, driver, "driver")
        assertEquals(403, post(path, """{"decision":"EIGENER_VORGANG","reason":"Zweite Tankung"}""", env.token(driver), rev).status)
        assertEquals(401, env.server.send("POST", path, """{"decision":"EIGENER_VORGANG","reason":"Zweite Tankung"}""", mapOf("Idempotency-Key" to "k-" + UUID.randomUUID()) + im(rev)).status)
        assertEquals(404, post(path, """{"decision":"EIGENER_VORGANG","reason":"Zweite Tankung"}""", other.token, rev).status)
        assertEquals("OFFEN", env.get("/v1/expenses/$doc", w.token).json["duplicates"][0]["state"].asString(), "abgewiesene Versuche entscheiden nichts")
        // identische Datei in einem anderen Mandanten: kein Treffer, keine Auskunft
        val bytes = ("%PDF-1.4\n" + "gemeinsam".repeat(50)).toByteArray(Charsets.ISO_8859_1)
        val a = w.fuelDocument(bytes, "RECEIPT"); val b = other.fuelDocument(bytes, "RECEIPT")
        assertEquals(0, env.get("/v1/expenses/$a", w.token).json["duplicates"].let { dup -> (0 until dup.size()).count { dup[it]["match"].asString() == "HASH" } })
        assertEquals(0, env.get("/v1/expenses/$b", other.token).json["duplicates"].size())
    }
}
