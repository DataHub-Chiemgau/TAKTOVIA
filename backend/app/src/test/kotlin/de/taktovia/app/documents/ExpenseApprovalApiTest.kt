package de.taktovia.app.documents

import de.taktovia.app.contract.W10C5ContractCases
import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M4-34 über die echte Anwendung (E2E-068, E2E-064): `submit` -> `review` -> `approve` mit getrennten Personen, 403 ohne Freigaberecht und ohne Datenpreisgabe, Selbstfreigabe 403, 412/428 an der exakten Dokumentrevision,
 * Idempotenz (gleicher Schlüssel bucht nicht erneut, anderer Inhalt 409), Vier-Augen über der Schwelle, Ablehnung mit Rückgabe, Problem-Codes der Blockaden.
 */
class ExpenseApprovalApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())
    private fun im(rev: Long) = mapOf("If-Match" to "\"$rev\"")
    private fun post(path: String, body: String?, token: String, rev: Long?, key: String? = null) =
        env.server.send("POST", path, body ?: "", env.headers(token, extra = (key?.let { mapOf("Idempotency-Key" to it) } ?: idem()) + (rev?.let { im(it) } ?: emptyMap())))
    private fun ready(w: W10C5ContractCases.World, upTo: String) = with(W10C5ContractCases) { w.readyDocument(upTo) }
    private fun tools.jackson.databind.JsonNode.strings() = (0 until size()).map { get(it).asString() }
    private fun events(w: W10C5ContractCases.World) = env.tx.run(w.tenant) { j -> j.queryForObject("SELECT count(*) FROM taktovia.economic_event", Int::class.java)!! }
    private fun approveBody(rev: Long, reason: String = "Freigegeben") = """{"reason":"$reason","validated_document_revision":$rev}"""

    @Test
    fun ablauf_erfassung_pruefung_freigabe_bucht_genau_einmal_und_ist_idempotent() {
        val w = W10C5ContractCases.World(); val (doc, rev) = ready(w, "REVIEWED")
        val path = "/v1/expenses/$doc/approve"
        // Pflichten der Anfrage: If-Match (428), exakte Revision (412), Begründung (422), Authentisierung (401)
        assertEquals(428, post(path, approveBody(rev), w.approverToken, null).status)
        assertEquals(412, post(path, approveBody(rev - 1), w.approverToken, rev - 1).status, "veraltete Revision")
        assertEquals(412, post(path, approveBody(rev + 1), w.approverToken, rev).status, "If-Match und Revision im Body verschieden")
        assertEquals(422, post(path, approveBody(rev, "ab"), w.approverToken, rev).status)
        assertEquals(401, env.server.send("POST", path, approveBody(rev), idem() + im(rev)).status)
        assertEquals(0, events(w))
        val key = "approve-" + UUID.randomUUID()
        val ok = post(path, approveBody(rev), w.approverToken, rev, key)
        assertEquals(200, ok.status, ok.text); assertEquals("POSTED", ok.json["state"].asString()); assertEquals(doc, ok.json["expense_id"].asString()); assertFalse(ok.json["posting_id"].isNull)
        assertEquals(1, events(w))
        // gleicher Schlüssel, gleicher Inhalt: gespeicherte Antwort, keine zweite Buchung; anderer Inhalt: 409 IDEMPOTENCY_CONFLICT
        val again = post(path, approveBody(rev), w.approverToken, rev, key)
        assertEquals(200, again.status); assertEquals(ok.json["posting_id"].asString(), again.json["posting_id"].asString()); assertEquals(1, events(w))
        val conflict = post(path, approveBody(rev, "Anderer Inhalt"), w.approverToken, rev, key)
        assertEquals(409, conflict.status); assertEquals("IDEMPOTENCY_CONFLICT", conflict.json["code"].asString()); assertEquals(1, events(w))
        // ein neuer Schlüssel auf den gebuchten Beleg: 409, keine zweite Buchung
        assertEquals(409, post(path, approveBody(rev), w.approver2Token, rev).status); assertEquals(1, events(w))
        // der Beleg zeigt alle Schritte mit Akteur, Revision, Entscheidung und Zeit; der Exportstatus ist ein eigenes Feld
        val e = env.get("/v1/expenses/$doc", w.token).json
        assertEquals("POSTED", e["state"].asString()); assertEquals(listOf("CAPTURE", "REVIEW", "BUDGET"), (0 until e["approval_steps"].size()).map { e["approval_steps"][it]["step"].asString() })
        assertTrue((0 until 3).all { e["approval_steps"][it]["decision"].asString() == "APPROVED" && e["approval_steps"][it]["document_revision"].asLong() == rev && !e["approval_steps"][it]["decided_at"].isNull })
        assertEquals("NOT_EXPORTED", e["export_state"].asString())
        assertEquals(3, env.tx.run(w.tenant) { j -> j.queryForObject("SELECT count(*) FROM taktovia.audit_event WHERE action='finance.expense.approval_step'", Int::class.java)!! })
    }

    @Test
    fun ohne_freigaberecht_403_ohne_datenpreisgabe_selbstfreigabe_403_fremder_mandant_404() {
        val w = W10C5ContractCases.World(); val (doc, rev) = ready(w, "REVIEWED"); val other = W10C5ContractCases.World()
        val path = "/v1/expenses/$doc/approve"
        val driver = UUID.randomUUID(); env.grant(w.tenant, driver, "driver")
        val denied = post(path, approveBody(rev), env.token(driver), rev)
        assertEquals(403, denied.status); assertFalse(denied.text.contains(doc), "keine Datenpreisgabe")
        assertEquals(403, post(path, approveBody(rev), w.token, rev).status, "der Erfasser gibt nicht selbst frei")
        assertEquals(404, post(path, approveBody(rev), other.approverToken, rev).status)
        assertEquals(0, events(w))
        assertEquals("REVIEWED", env.get("/v1/expenses/$doc", w.token).json["state"].asString()); assertEquals(2, env.get("/v1/expenses/$doc", w.token).json["approval_steps"].size(), "nur Erfassung und Prüfung, kein Schritt durch verweigerte Versuche")
        // Prüfung und Ablehnung verlangen ihre eigenen Rechte: ein Fahrer darf weder prüfen noch ablehnen
        assertEquals(403, post("/v1/expenses/$doc/review", """{"decision":"REJECTED","reason":"Nicht erlaubt"}""", env.token(driver), rev).status)
        assertEquals(403, post("/v1/expenses/$doc/reject", """{"reason":"Nicht erlaubt"}""", env.token(driver), rev).status)
    }

    @Test
    fun vier_augen_ueber_der_schwelle_zwei_verschiedene_personen() {
        val w = W10C5ContractCases.World()
        env.tx.run(w.tenant) { j -> j.update("UPDATE taktovia.finance_policy SET approval_second_threshold_minor = 10000 WHERE tenant_id = ?", w.tenant.value) }   // 119,00 > 100,00
        val (doc, rev) = ready(w, "REVIEWED"); val path = "/v1/expenses/$doc/approve"
        val first = post(path, approveBody(rev), w.approverToken, rev)
        assertEquals(200, first.status, first.text); assertEquals("WAITING_SECOND_APPROVAL", first.json["state"].asString()); assertTrue(first.json["posting_id"].isNull); assertEquals(0, events(w))
        assertEquals(403, post(path, approveBody(rev), w.approverToken, rev).status, "der Erstfreigeber kann nicht zweimal freigeben")
        assertEquals(403, post(path, approveBody(rev), w.token, rev).status, "der Erfasser kann die zweite Freigabe nicht erteilen")
        assertEquals(0, events(w))
        val second = post(path, approveBody(rev), w.approver2Token, rev)
        assertEquals(200, second.status, second.text); assertEquals("POSTED", second.json["state"].asString()); assertEquals(1, events(w))
        assertEquals(listOf("CAPTURE", "REVIEW", "BUDGET", "SECOND_APPROVAL"), env.get("/v1/expenses/$doc", w.token).json["approval_steps"].let { st -> (0 until st.size()).map { st[it]["step"].asString() } })
    }

    @Test
    fun ablehnung_fuehrt_in_die_erfassung_zurueck_und_blockaden_haben_eigene_codes() {
        val w = W10C5ContractCases.World(); val (doc, rev) = ready(w, "REVIEWED")
        assertEquals(422, post("/v1/expenses/$doc/reject", """{"reason":"ab"}""", w.approverToken, rev).status)
        val rej = post("/v1/expenses/$doc/reject", """{"reason":"Budget überschritten"}""", w.approverToken, rev)
        assertEquals(200, rej.status, rej.text); assertEquals("STORED", rej.json["state"].asString()); assertEquals(0, events(w))
        assertEquals("REJECTED", rej.json["approval_steps"].let { st -> st[st.size() - 1]["decision"].asString() }); assertEquals("Budget überschritten", rej.json["approval_steps"].let { st -> st[st.size() - 1]["reason"].asString() })
        // nach der Rückgabe: erneut bearbeitbar; ohne Prüfung keine Freigabe (409); Prüfung eines Belegs in Erfassung 409
        assertEquals(409, post("/v1/expenses/$doc/approve", approveBody(rev), w.approverToken, rev).status)
        assertEquals(409, post("/v1/expenses/$doc/review", """{"decision":"APPROVED","reason":"Sachlich geprüft"}""", w.reviewerToken, rev).status)
        // Blockade mit eigenem Code: Beleg ohne Kostenzuordnung
        val bare = w.fuelDocument(); val sub = post("/v1/expenses/$bare/submit", null, w.token, 1); assertEquals(200, sub.status, sub.text)
        val blocked = post("/v1/expenses/$bare/review", """{"decision":"APPROVED","reason":"Sachlich geprüft"}""", w.reviewerToken, 1)
        assertEquals(422, blocked.status); assertEquals("UNCONFIRMED_FIELDS", blocked.json["code"].asString())
    }
}
