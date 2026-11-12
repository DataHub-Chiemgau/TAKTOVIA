package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * WP-M3-15 / LH-6.21-CHK-014 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak): `PUT /v1/inspections/{id}` (Entwurf, `If-Match`, 428/412, nach Abschluss 409) und
 * `POST /v1/inspections/{id}/amendments` (Nachtrag mit Pflichtgrund, Rechte, 404, Originalantwort unverändert).
 */
class InspectionAmendmentApiTest {
    private val env = IdentityEnv
    private fun idem(k: String = "k-" + UUID.randomUUID()) = mapOf("Idempotency-Key" to k)
    private fun ans(step: String, status: String = "OK", note: String? = null) = """{"step_code":"$step","answer":"$status",${note?.let { """"note":"$it",""" } ?: ""}"answered_at":"${Instant.now().minusSeconds(20)}"}"""

    private fun started(): de.taktovia.app.contract.W11C4ContractCases.Started = de.taktovia.app.contract.W11C4ContractCases.started()

    private fun put(s: de.taktovia.app.contract.W11C4ContractCases.Started, body: String, rev: String? = "\"1\"", token: String = s.w.token) =
        env.server.send("PUT", "/v1/inspections/${s.id}", body, env.headers(token, extra = idem() + (rev?.let { mapOf("If-Match" to it) } ?: emptyMap())), "application/json")

    private fun amend(s: de.taktovia.app.contract.W11C4ContractCases.Started, body: String, token: String) = env.post("/v1/inspections/${s.id}/amendments", body, token, null, idem())

    @Test
    fun entwurf_speichern_if_match_und_nach_abschluss_nur_nachtrag() {
        val s = started()
        assertEquals(428, put(s, """{"answers":[${ans("S1")}]}""", rev = null).status)
        val saved = put(s, """{"answers":[${ans("S1")}]}""")
        assertEquals(200, saved.status, saved.text); assertEquals("\"2\"", saved.header("ETag")); assertEquals(1, saved.json["answers"].size())
        assertEquals(412, put(s, """{"answers":[${ans("S2")}]}""", rev = "\"1\"").status)   // alter Stand
        assertEquals(422, put(s, """{"answers":[${ans("S2", "VIELLEICHT")}]}""", rev = "\"2\"").status)
        assertEquals(404, put(s, """{"answers":[${ans("S2")}]}""", rev = "\"2\"", token = s.w.otherToken).status)
        // „App-Neustart“: der Entwurf ist beim Lesen vorhanden
        assertEquals(1, env.get("/v1/inspections/${s.id}", s.w.token).json["answers"].size())
        // Abschluss mit demselben Stand wie der Entwurf, danach ist Speichern nur noch als Nachtrag möglich
        val done = env.server.send("POST", "/v1/inspections/${s.id}/submit", s.submitBody(), env.headers(s.w.token, extra = idem() + mapOf("If-Match" to "\"2\"")), "application/json")
        assertEquals(200, done.status, done.text)
        val late = put(s, """{"answers":[${ans("S1", "DEFECT", "x")}]}""", rev = "\"3\"")
        assertEquals(409, late.status, late.text); assertEquals("IMMUTABLE_RECORD", late.json["code"].asString())
    }

    @Test
    fun nachtrag_mit_pflichtgrund_rechten_und_unveraenderter_originalantwort() {
        val s = started()
        val workshop = env.token(s.w.author)   // Rolle workshop
        val body = """{"step_code":"S2","kind":"CORRECTION","new_answer":"DEFECT","new_note":"Kratzer","reason":"Fahrer hat sich vertan"}"""
        // vor dem Abschluss: 409
        assertEquals(409, amend(s, body, workshop).status)
        val done = env.server.send("POST", "/v1/inspections/${s.id}/submit", s.submitBody(), env.headers(s.w.token, extra = idem() + mapOf("If-Match" to "\"1\"")), "application/json")
        assertEquals(200, done.status, done.text)
        // ohne Grund: 422; unberechtigt (Fahrer, Disposition): 403; fremder Mandant: 404
        assertEquals(422, amend(s, """{"step_code":"S2","kind":"CORRECTION","new_answer":"OK"}""", workshop).status)
        assertEquals(422, amend(s, """{"step_code":"S2","kind":"CORRECTION","new_answer":"OK","reason":"  "}""", workshop).status)
        assertEquals(403, amend(s, body, s.w.token).status)
        assertEquals(403, amend(s, body, s.w.dispatcherToken).status)
        assertEquals(404, amend(s, body, de.taktovia.app.contract.W11C4ContractCases.started().w.let { env.token(it.author) }).status)
        // berechtigt: 201, Version 1 und 2, Original unverändert
        val a1 = amend(s, body, workshop); assertEquals(201, a1.status, a1.text); assertEquals(1, a1.json["version"].asInt()); assertEquals("DEFECT", a1.json["new_answer"].asString())
        val a2 = amend(s, body.replace("CORRECTION", "SUPPLEMENT"), env.token(s.w.reviewer)); assertEquals(201, a2.status, a2.text); assertEquals(2, a2.json["version"].asInt())
        val g = env.get("/v1/inspections/${s.id}", s.w.dispatcherToken)
        val original = g.json["answers"].first { it["step_code"].asString() == "S2" }
        assertEquals("OK", original["answer"].asString()); assertEquals(2, g.json["amendments"].size()); assertEquals("PASSED", g.json["outcome"].asString()); assertTrue(!g.json["release"].isNull)
        assertEquals("\"2\"", g.header("ETag"), "der Nachtrag erhöht die Revision des Prüfnachweises nicht")
    }
}
