package de.taktovia.app.reports

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.UUID

/**
 * WP-M5-19 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): Auftrag anlegen (202), Wiederholung (200), anderer Umfang unter demselben Schlüssel (409), Status lesen, berechtigter Abruf über den
 * kurzlebigen Link mit Hash, Export ohne Rolle (403), fremder Mandant (404 ohne Hinweis), manipulierter Link (404), fremdes Objekt und manipulierte `tenant_id` im Umfang (404/422), Audit ohne Exportinhalt.
 * Die Verarbeitung (Betriebsschritt) läuft über den Port, weil der Worker nur im Betrieb läuft.
 */
class ReportJobApiTest {
    private val env = IdentityEnv
    private fun idem(key: String = "k-" + UUID.randomUUID()) = mapOf("Idempotency-Key" to key)
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private val analytics get() = env.server.ctx.getBean(AnalyticsApi::class.java)

    private class World(val env: IdentityEnv) {
        val tenant = env.tenant("Export-" + UUID.randomUUID()); val hr = UUID.randomUUID(); val driver = UUID.randomUUID(); val vehicle = UUID.randomUUID()
        init {
            env.grant(tenant, hr, "staff_planning"); env.grant(tenant, driver, "driver")
            env.tx.run(tenant) { j ->
                j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,created_at) VALUES (?,?,'fixture','{}',now() - interval '1 day')", tenant.value, vehicle)
                j.update("INSERT INTO taktovia.time_event(tenant_id,id,person_ref,kind,occurred_at,device_recorded_at,received_at,source,recorded_by,payload_sha256,timezone) VALUES (?,?,?,'WORK_START',now() - interval '2 hours',now() - interval '2 hours',now() - interval '2 hours','APP',?,?,'Europe/Berlin')",
                    tenant.value, UUID.randomUUID(), "user:p1", "user:p1", "c".repeat(64))
            }
        }
        val hrToken = env.token(hr); val driverToken = env.token(driver)
    }

    private val range = """{"from":"${java.time.Instant.now().minusSeconds(86400)}","to":"${java.time.Instant.now().plusSeconds(3600)}"}"""

    @Test
    fun auftrag_anlegen_verarbeiten_lesen_und_ueber_kurzlebigen_link_abrufen() {
        val w = World(env); val key = "export-" + UUID.randomUUID(); val body = """{"kind":"TIME_PROOF","scope":$range}"""
        val created = env.post("/v1/reports/jobs", body, w.hrToken, extra = idem(key))
        assertEquals(202, created.status, created.text); val id = created.json["job_id"].asString()
        assertEquals("QUEUED", created.json["state"].asString()); assertTrue(created.json["download_url"].isNull)
        val again = env.post("/v1/reports/jobs", body, w.hrToken, extra = idem(key))
        assertTrue(again.status == 200 || again.status == 202, again.text); assertEquals(id, again.json["job_id"].asString(), "dieselbe Wiederholung liefert denselben Auftrag")
        assertEquals(409, env.post("/v1/reports/jobs", """{"kind":"TIME_PROOF","scope":{"from":"2020-01-01T00:00:00Z","to":"2020-01-02T00:00:00Z"}}""", w.hrToken, extra = idem(key)).status, "gleicher Schlüssel, anderer Umfang")
        assertEquals(1, analytics.reportJobs.runPending(w.tenant))
        val job = env.get("/v1/reports/jobs/$id", w.hrToken)
        assertEquals(200, job.status, job.text); assertEquals("SUCCEEDED", job.json["state"].asString()); assertEquals("TIME_PROOF", job.json["categories"][0]["code"].asString()); assertEquals(1, job.json["categories"][0]["rows"].asInt())
        val url = job.json["download_url"].asString(); assertTrue(url.startsWith("/v1/reports/jobs/$id?download_token="))
        val file = env.get(url, w.hrToken)
        assertEquals(200, file.status, file.text); assertTrue(file.text.contains("user:p1") && file.text.contains("\"as_of\""))
        assertEquals(job.json["result_sha256"].asString(), sha(file.text), "Hash des Abrufs stimmt mit dem Nachweis überein"); assertEquals(job.json["result_sha256"].asString(), file.header("X-Export-SHA256"))
        assertTrue(file.header("Content-Type")!!.startsWith("application/vnd.taktovia.export+json")); assertEquals("no-store", file.header("Cache-Control"))
        // Audit: Auftrag und Abruf, ohne Exportinhalt
        assertEquals(1, env.events(w.tenant, "analytics.export.requested")); assertEquals(1, env.events(w.tenant, "analytics.export.downloaded"))
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND (redacted_changes::text LIKE '%user:p1%' OR redacted_changes::text LIKE '%payload%')", w.tenant.value))
    }

    @Test
    fun ohne_rolle_fremder_mandant_manipulierter_link_und_manipulierter_umfang() {
        val w = World(env); val other = World(env)
        assertEquals(403, env.post("/v1/reports/jobs", """{"kind":"TIME_PROOF","scope":$range}""", w.driverToken, extra = idem()).status, "Fahrer ohne reports:write")
        val forged = env.post("/v1/reports/jobs", """{"kind":"TIME_PROOF","scope":{"from":"2026-01-01T00:00:00Z","to":"2026-01-02T00:00:00Z","tenant_id":"${other.tenant.value}"}}""", w.hrToken, extra = idem()).status
        assertTrue(forged == 404 || forged == 422, "manipulierte tenant_id im Umfang wird abgewiesen: $forged")
        assertEquals(422, env.post("/v1/reports/jobs", """{"kind":"UNBEKANNT","scope":{}}""", w.hrToken, extra = idem()).status)
        assertEquals(404, env.post("/v1/reports/jobs", """{"kind":"TIME_PROOF","scope":{"vehicle_id":"${other.vehicle}","from":"2026-01-01T00:00:00Z","to":"2026-01-02T00:00:00Z"}}""", w.hrToken, extra = idem()).status, "Fahrzeug eines fremden Mandanten")
        val id = env.post("/v1/reports/jobs", """{"kind":"TIME_PROOF","scope":$range}""", w.hrToken, extra = idem()).json["job_id"].asString()
        analytics.reportJobs.runPending(w.tenant)
        val url = env.get("/v1/reports/jobs/$id", w.hrToken).json["download_url"].asString()
        assertEquals(404, env.get("/v1/reports/jobs/$id", other.hrToken).status, "fremder Mandant ohne Hinweis"); assertEquals(404, env.get(url, other.hrToken).status)
        assertEquals(404, env.get(url.dropLast(3) + "AAA", w.hrToken).status, "manipulierter Link")
        assertEquals(403, env.get("/v1/reports/jobs/$id", w.driverToken).status, "Lesen braucht reports:read")
        assertTrue(env.events(w.tenant, "security.export.download_denied") >= 1, "verweigerte Abrufe sind protokolliert")
    }
}
