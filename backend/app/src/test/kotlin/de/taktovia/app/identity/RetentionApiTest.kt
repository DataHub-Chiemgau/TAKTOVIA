package de.taktovia.app.identity

import de.taktovia.platform.operation.OperationRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * LH-6.18-SEC-007 / WP-M1-33 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): Regel anlegen, Vier-Augen-Freigabe, Lauf über die operation
 * `RETENTION_RUN` (Operation-Runner mit dem Handler der Plattform und dem Ziel des Moduls identity), Jobstatus und Löschprotokoll, Mandantentrennung.
 */
class RetentionApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())
    private val rule = """{"data_category":"LK_PII","retention_days":30,"action":"ANONYMIZE"}"""

    @Test
    fun regel_freigabe_lauf_jobstatus_und_protokoll() {
        val tenant = env.tenant("Aufbewahrung-" + UUID.randomUUID()); val other = env.tenant("Aufbewahrung-B-" + UUID.randomUUID())
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val dispatcher = UUID.randomUUID(); val otherAdmin = UUID.randomUUID()
        env.grant(tenant, a, "tenant_admin"); env.grant(tenant, b, "tenant_admin"); env.grant(tenant, dispatcher, "dispatcher"); env.grant(other, otherAdmin, "tenant_admin")
        val ta = env.token(a); val tb = env.token(b); val td = env.token(dispatcher); val to = env.token(otherAdmin)
        // fälliges Fahrgastkonto mit Personendaten und eines mit laufender Frist
        val expired = UUID.randomUUID(); val running = UUID.randomUUID()
        for ((id, days) in listOf(expired to -400, running to 100)) env.exec(
            "INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,contact_ciphertext,support_ciphertext,retention_until) VALUES (?,?,?,?,?,now() + make_interval(days => ?))",
            tenant.value, id, "tok-$id", "KONTAKT".toByteArray(), "UNTERSTUETZUNG".toByteArray(), days)

        // Operative Rolle: weder Regeln noch Läufe
        assertEquals(403, env.get("/v1/retention-rules", td).status)
        assertEquals(403, env.post("/v1/erasure-jobs", """{"rule_id":"${UUID.randomUUID()}"}""", td, null, idem()).status)
        // Regel als Entwurf; ungültige Datenklasse und Archiv ohne Archivdauer: 422
        val created = env.post("/v1/retention-rules", rule, ta, null, idem())
        assertEquals(201, created.status, created.text); assertEquals("\"1\"", created.header("ETag")); assertEquals("DRAFT", created.json["status"].asString())
        val ruleId = created.json["id"].asString()
        assertEquals(422, env.post("/v1/retention-rules", """{"data_category":"LK_X","retention_days":30,"action":"DELETE"}""", ta, null, idem()).status)
        assertEquals(422, env.post("/v1/retention-rules", """{"data_category":"LK_PII","retention_days":30,"action":"ARCHIVE"}""", ta, null, idem()).status)
        // Entwurf: kein Lauf (409); Urheber gibt nicht selbst frei (403); fehlende Revision: 428
        assertEquals(409, env.post("/v1/erasure-jobs", """{"rule_id":"$ruleId"}""", ta, null, idem()).status)
        assertEquals(403, env.post("/v1/retention-rules/$ruleId/approvals", "{}", ta, null, idem() + ("If-Match" to "\"1\"")).status)
        assertEquals(428, env.post("/v1/retention-rules/$ruleId/approvals", "{}", tb, null, idem()).status)
        val approved = env.post("/v1/retention-rules/$ruleId/approvals", "{}", tb, null, idem() + ("If-Match" to "\"1\""))
        assertEquals(200, approved.status, approved.text); assertEquals("APPROVED", approved.json["status"].asString()); assertEquals("\"2\"", approved.header("ETag"))

        // Lauf anlegen (fortsetzbar), Ausführung über die Operation
        val started = env.post("/v1/erasure-jobs", """{"rule_id":"$ruleId"}""", ta, null, idem())
        assertEquals(201, started.status, started.text); assertEquals("RESUMABLE", started.json["status"].asString())
        val jobId = started.json["id"].asString(); val operationId = started.json["operation_id"].asString()
        assertEquals(200, env.get("/v1/operations/$operationId", ta).status)
        val runner = env.server.ctx.getBean(OperationRunner::class.java)
        assertTrue(runner.runNext(tenant) != null, "die Operation RETENTION_RUN wird vom Runner aufgenommen")
        val jobs = env.get("/v1/erasure-jobs", ta)
        assertEquals(200, jobs.status, jobs.text)
        val job = jobs.json["items"].values().single { it["id"].asString() == jobId }
        assertEquals("COMPLETED", job["status"].asString()); assertEquals(1L, job["processed_count"].asLong())
        assertEquals("SUCCEEDED", env.get("/v1/operations/$operationId", ta).json["status"].asString())
        // Wirkung: fälliger Datensatz ohne Personendaten, der andere unverändert
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.passenger_private WHERE id = ? AND erased_at IS NOT NULL AND contact_ciphertext IS NULL AND support_ciphertext IS NULL", expired))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.passenger_private WHERE id = ? AND erased_at IS NULL AND contact_ciphertext IS NOT NULL", running))
        // Löschprotokoll: ein Eintrag ohne Personendaten, auditiert
        val journal = env.get("/v1/erasure-journal?job_id=$jobId", ta)
        assertEquals(200, journal.status, journal.text); assertEquals(1, journal.json["items"].size())
        assertEquals("passenger:$expired", journal.json["items"][0]["object_ref"].asString()); assertEquals("ANONYMIZE", journal.json["items"][0]["action"].asString())
        for (action in listOf("retention.rule.changed", "retention.rule.approved", "retention.erasure.started", "retention.erasure.finished"))
            assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = ?", tenant.value, action), action)
        // Mandantentrennung: der andere Mandant sieht weder Regel noch Lauf noch Protokoll und kann die Regel nicht freigeben oder starten
        assertEquals(0, env.get("/v1/retention-rules", to).json["items"].size()); assertEquals(0, env.get("/v1/erasure-jobs", to).json["items"].size())
        assertEquals(0, env.get("/v1/erasure-journal", to).json["items"].size())
        assertEquals(404, env.post("/v1/erasure-jobs", """{"rule_id":"$ruleId"}""", to, null, idem()).status)
        assertEquals(404, env.post("/v1/retention-rules/$ruleId/approvals", "{}", to, null, idem() + ("If-Match" to "\"2\"")).status)
        // Änderung der freigegebenen Regel setzt sie auf den Entwurf zurück
        val updated = env.put("/v1/retention-rules/$ruleId", """{"retention_days":60,"action":"DELETE"}""", ta, "\"2\"")
        assertEquals(200, updated.status, updated.text); assertEquals("DRAFT", updated.json["status"].asString()); assertEquals("\"3\"", updated.header("ETag"))
        assertEquals(409, env.post("/v1/erasure-jobs", """{"rule_id":"$ruleId"}""", ta, null, idem()).status)
    }
}
