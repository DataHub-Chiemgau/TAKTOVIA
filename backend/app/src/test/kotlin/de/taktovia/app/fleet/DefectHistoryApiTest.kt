package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * LH-6.22-DMG-008, WP-M3-48 über die echte Anwendung: Nachtrag mit Grund (422 ohne), Versionsverlauf nur für Prüfer (403 sonst, 404 fremd), Stand zum Zeitpunkt t, ersetzter Anhang
 * bleibt erhalten, Nachtrag und Lesen im Audit, direkter SQL-Zugriff scheitert.
 */
class DefectHistoryApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())
    private val store: TenantObjectStore get() = env.server.ctx.getBean(TenantObjectStore::class.java)

    private fun defect(w: DefectApiTest.World, extra: String = ""): String {
        val r = env.post("/v1/defects", w.body(extra = extra), w.dispatcherToken, null, idem()); assertEquals(201, r.status, r.text); return r.json["id"].asString()
    }

    private fun amend(id: String, token: String, body: String) = env.post("/v1/defects/$id/amendments", body, token, null, idem())

    private fun photo(w: DefectApiTest.World, seed: Int): String {
        val b = DefectEvidenceApiTest.jpeg(seed)
        val t = env.post("/v1/defect-evidence-uploads", DefectEvidenceApiTest.ticketBody(b), w.dispatcherToken, null, idem()); assertEquals(201, t.status, t.text)
        store.put(ObjectKey(w.tenant, t.json["upload_key"].asString()), b, "image/jpeg")
        assertEquals(200, env.post("/v1/defect-evidence-uploads/${t.json["id"].asString()}/complete", "{}", w.dispatcherToken, null, idem() + ("If-Match" to "\"1\"")).status)
        return t.json["id"].asString()
    }

    @Test
    fun nachtrag_mit_grund_versionsverlauf_nur_fuer_pruefer_und_stand_zum_zeitpunkt() {
        val w = DefectApiTest.World(env); val id = defect(w); val t0 = Instant.now(); Thread.sleep(40)
        // ohne Änderungsgrund 422 mit Feldverweis, ohne Änderung 422, unbekanntes Feld 422
        val noReason = amend(id, w.dispatcherToken, """{"description":"neu"}""")
        assertEquals(422, noReason.status, noReason.text); assertEquals("/change_reason", noReason.json["violations"][0]["pointer"].asString())
        assertEquals(422, amend(id, w.dispatcherToken, """{"change_reason":"x"}""").status)
        assertEquals(422, amend(id, w.dispatcherToken, """{"change_reason":"x","status":"CLOSED"}""").status)
        assertEquals(422, amend(id, w.dispatcherToken, """{"change_reason":"","description":"neu"}""").status)
        val a1 = amend(id, w.dispatcherToken, """{"change_reason":"Beschreibung präzisiert","description":"Kratzer hinten links"}""")
        assertEquals(200, a1.status, a1.text); assertEquals(2, a1.json["content_version"].asInt()); assertEquals("Kratzer hinten links", a1.json["description"].asString()); assertEquals("\"2\"", a1.header("ETag"))
        Thread.sleep(40); val t1 = Instant.now(); Thread.sleep(40)
        val a2 = amend(id, w.workshopToken, """{"change_reason":"Kategorie berichtigt","category_code":"GLASS"}""")
        assertEquals(3, a2.json["content_version"].asInt())
        // Prüfer lesen den Verlauf; Version 1 unverändert
        val h = env.get("/v1/defects/$id/history", w.workshopToken)
        assertEquals(200, h.status, h.text)
        assertEquals(listOf(1, 2, 3), h.json["items"].values().map { it["version"].asInt() })
        assertEquals("Kratzer an der Beifahrertür", h.json["items"][0]["content"]["description"].asString()); assertEquals("BODY", h.json["items"][0]["content"]["category_code"].asString())
        assertEquals(listOf("Erstmeldung", "Beschreibung präzisiert", "Kategorie berichtigt"), h.json["items"].values().map { it["change_reason"].asString() })
        assertEquals(listOf("user:${w.dispatcher}", "user:${w.dispatcher}", "user:${w.workshop}"), h.json["items"].values().map { it["author"].asString() })
        assertEquals(200, env.get("/v1/defects/$id/history", w.adminToken).status)
        // Stand zum Zeitpunkt t
        val at0 = env.get("/v1/defects/$id/as-of?at=$t0", w.workshopToken); assertEquals(200, at0.status, at0.text)
        assertEquals(1, at0.json["version"].asInt()); assertEquals("Kratzer an der Beifahrertür", at0.json["content"]["description"].asString()); assertEquals("NEW", at0.json["status"].asString())
        assertEquals(2, env.get("/v1/defects/$id/as-of?at=$t1", w.workshopToken).json["version"].asInt())
        assertEquals(422, env.get("/v1/defects/$id/as-of?at=gestern", w.workshopToken).status)
        assertEquals(422, env.get("/v1/defects/$id/as-of", w.workshopToken).status)
        assertEquals(404, env.get("/v1/defects/$id/as-of?at=2020-01-01T00:00:00Z", w.workshopToken).status)
        // nicht berechtigte Rollen: weder Verlauf noch Stand; aktuelle Akte zeigt nur die letzte Fassung
        for (t in listOf(w.dispatcherToken, w.driverToken, w.supportToken, w.passengerToken)) {
            assertEquals(403, env.get("/v1/defects/$id/history", t).status); assertEquals(403, env.get("/v1/defects/$id/as-of?at=$t0", t).status)
        }
        assertEquals("GLASS", env.get("/v1/defects/$id", w.dispatcherToken).json["category_code"].asString())
        // Audit: Nachträge und Lesezugriffe, nie mit Textinhalt
        assertEquals(2, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.defect.amended' AND object_ref=?", w.tenant.value, "defect:$id"))
        assertTrue(env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.defect.history_read' AND object_ref=?", w.tenant.value, "defect:$id") >= 4)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND object_ref=? AND redacted_changes::text LIKE '%hinten links%'", w.tenant.value, "defect:$id"))
        // fremder Mandant
        val b = DefectApiTest.World(env)
        assertEquals(404, env.get("/v1/defects/$id/history", b.workshopToken).status)
        assertEquals(404, env.get("/v1/defects/$id/as-of?at=$t1", b.workshopToken).status)
        assertEquals(404, amend(id, b.dispatcherToken, """{"change_reason":"x","description":"gekapert"}""").status)
        assertFalse(env.get("/v1/defects/$id", w.dispatcherToken).text.contains("gekapert"))
    }

    @Test
    fun ersetzter_anhang_bleibt_und_direkte_aenderung_scheitert() {
        val w = DefectApiTest.World(env); val e1 = photo(w, 301)
        val id = defect(w, ""","evidence_ids":["$e1"]""")
        val e2 = photo(w, 302)
        val r = amend(id, w.dispatcherToken, """{"change_reason":"Foto unscharf","replace_evidence":[{"evidence_id":"$e1","ticket_id":"$e2"}]}""")
        assertEquals(200, r.status, r.text); assertEquals(listOf(e2), r.json["evidence_ids"].values().map { it.asString() })
        val h = env.get("/v1/defects/$id/history", w.workshopToken)
        assertEquals(listOf(listOf(e1), listOf(e2)), h.json["items"].values().map { v -> v["evidence"].values().map { it["id"].asString() } })
        assertEquals(e1, h.json["items"][1]["evidence"][0]["replaces_id"].asString())
        assertEquals(404, env.get("/v1/defects/$id/evidence/$e1/download", w.dispatcherToken).status)           // nicht für Nicht-Prüfer
        assertEquals(200, env.get("/v1/defects/$id/evidence/$e1/download", w.workshopToken).status)             // Prüfer lesen den Vorgänger
        assertEquals(listOf(e2), env.get("/v1/defects/$id/evidence", w.dispatcherToken).json["items"].values().map { it["id"].asString() })
        // direkter SQL-Zugriff auf Meldung, Revision und Evidenz scheitert auch für die Administration
        for (sql in listOf("DELETE FROM taktovia.defect_evidence WHERE defect_id=?", "UPDATE taktovia.defect_case_revision SET change_reason='x' WHERE defect_id=?", "DELETE FROM taktovia.defect_case WHERE id=?")) {
            val ex = runCatching { env.exec(sql, UUID.fromString(id)) }.exceptionOrNull()
            assertTrue(ex != null && generateSequence(ex) { it.cause }.filterIsInstance<java.sql.SQLException>().any { it.sqlState == "23514" }, sql)
        }
    }
}
