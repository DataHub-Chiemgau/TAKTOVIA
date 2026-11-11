package de.taktovia.app.config

import de.taktovia.TaktoviaTestSupport
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.app.probe.Resp
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M1-22 über die echte Anwendung (Filterkette, Rechtematrix, Idempotenzregister, Datenbank): `publishConfig` mit `Idempotency-Key` und `If-Match`
 * (428/412), Validierung, Vier-Augen-Freigabe, Rücknahme, `/v1/config/effective` vor und nach der Publikation, 100 parallele Wiederholungen mit gleichem Schlüssel.
 */
class ConfigPublicationApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "pub-" + UUID.randomUUID())

    private class Team(val tenant: TenantId, val alice: String, val bob: String, val passenger: String)
    private fun team(): Team {
        val t = env.tenant(); val a = UUID.randomUUID(); val b = UUID.randomUUID(); val p = UUID.randomUUID()
        env.grant(t, a, "tenant_admin"); env.grant(t, b, "planner"); env.grant(t, p, "passenger")
        return Team(t, env.token(a), env.token(b), env.token(p))
    }
    private fun at(days: Long): Instant = Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
    private fun call(token: String, path: String, ifMatch: Long?, body: String? = null, key: Map<String, String> = idem(), method: String = "POST"): Resp =
        env.server.send(method, path, body, env.headers(token, extra = key + (if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap())))
    private fun draft(t: Team, max: Int, from: Instant, token: String = t.alice): String = env.post("/v1/config/revisions",
        """{"scope_kind":"TENANT","scope_id":"${t.tenant}","schema_version":"1","values":{"max_wait_min":$max},"effective_from":"$from","reason":"Wartezeit"}""", token, extra = idem()).json["id"].asString()
    private fun report(t: Team, id: String, rv: Long = 1) = call(t.alice, "/v1/config/revisions/$id/impact", rv).also { assertEquals(200, it.status, it.text) }.json["id"].asString()
    private fun publishBody(from: Instant, report: String?, reason: String = "Veröffentlichen") =
        """{"effective_from":"$from","reason":"$reason"${if (report != null) ""","impact_report_id":"$report"""" else ""}}"""
    private fun effective(t: Team, at: Instant) = env.get("/v1/config/effective?parameter=max_wait_min&at=$at", t.alice).json["values"]["max_wait_min"]?.asInt()

    private fun count(sql: String, vararg args: Any?): Int = TaktoviaTestSupport.adminDataSourceOrNull()!!.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { it.next(); it.getInt(1) } }
    }

    /** Entwurf → Bericht → validiert (rv 2) → freigegeben (rv 3, Bob). Liefert Id, Bericht, Zeilenversion. */
    private fun approved(t: Team, max: Int, from: Instant): Triple<String, String, Long> {
        val id = draft(t, max, from); val rep = report(t, id)
        assertEquals(200, call(t.alice, "/v1/config/revisions/$id/validate", 1).status)
        val ap = call(t.bob, "/v1/config/revisions/$id/approve", 2); assertEquals(200, ap.status, ap.text)
        assertEquals("\"3\"", ap.header("ETag"))
        return Triple(id, rep, 3L)
    }

    @Test
    fun entwurf_validieren_freigeben_publizieren_wird_erst_dann_wirksam() {
        val t = team(); val from = at(2); val after = from.plusSeconds(60)
        val base = approved(t, 15, Instant.now().truncatedTo(ChronoUnit.SECONDS))
        assertEquals(200, call(t.bob, "/v1/config/revisions/${base.first}/publish", base.third, publishBody(Instant.parse(env.get("/v1/config/revisions/${base.first}", t.alice).json["effective_from"].asString()), base.second)).status)
        val (id, rep, rv) = approved(t, 25, from)
        assertEquals(15, effective(t, after), "freigegeben, aber nicht publiziert: nicht wirksam")
        val r = call(t.bob, "/v1/config/revisions/$id/publish", rv, publishBody(from, rep))
        assertEquals(200, r.status, r.text)
        assertEquals("PUBLISHED", r.json["state"].asString()); assertEquals(id, r.json["resource_id"].asString()); assertEquals("\"4\"", r.header("ETag"))
        val audit = r.json["audit_event_id"].asString()
        assertEquals(1, count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND id=? AND action='config.revision.published'", t.tenant.value, UUID.fromString(audit)))
        assertEquals(15, effective(t, from.minusSeconds(1))); assertEquals(25, effective(t, from)); assertEquals(25, effective(t, after))
        val read = env.get("/v1/config/revisions/$id", t.alice)
        assertEquals("PUBLISHED", read.json["state"].asString()); assertTrue(read.json["validated_by"].asString().startsWith("user:")); assertNotEquals(read.json["created_by"].asString(), read.json["approved_by"].asString())
        // der Vorgänger ist abgelöst (Ende gesetzt), bleibt aber lesbar
        val old = env.get("/v1/config/revisions/${base.first}", t.alice)
        assertEquals(from.toString(), old.json["effective_to"].asString()); assertEquals(15, old.json["values"]["max_wait_min"].asInt())
    }

    @Test
    fun validierung_ungueltig_und_zweite_person_pflicht() {
        val t = team(); val from = at(2)
        val id = draft(t, 20, from)
        val v = call(t.alice, "/v1/config/revisions/$id/validate", 1)
        assertEquals(200, v.status, v.text); assertEquals(true, v.json["valid"].asBoolean()); assertEquals("VALIDATED", v.json["state"].asString()); assertEquals(2, v.json["row_version"].asInt())
        assertEquals(403, call(t.alice, "/v1/config/revisions/$id/approve", 2).status, "Selbstfreigabe")
        assertEquals(403, call(t.passenger, "/v1/config/revisions/$id/approve", 2).status, "ohne config:publish")
        assertEquals(403, call(t.passenger, "/v1/config/revisions/$id/validate", 2).status)
        assertEquals(409, call(t.alice, "/v1/config/revisions/$id/validate", 2).status, "nur Entwürfe")
        assertNull(env.get("/v1/config/revisions/$id", t.alice).json["approved_by"].takeIf { !it.isNull })
    }

    @Test
    fun publish_ohne_if_match_428_veraltet_412_ohne_bericht_422_ohne_freigabe_409() {
        val t = team(); val from = at(2)
        val (id, rep, rv) = approved(t, 22, from)
        assertEquals(428, call(t.bob, "/v1/config/revisions/$id/publish", null, publishBody(from, rep)).status)
        assertEquals(412, call(t.bob, "/v1/config/revisions/$id/publish", 2, publishBody(from, rep)).status)
        val noReport = call(t.bob, "/v1/config/revisions/$id/publish", rv, """{"effective_from":"$from","reason":"Veröffentlichen","impact_report_id":"${UUID.randomUUID()}"}""")
        assertEquals(422, noReport.status); assertEquals("/impact_report_id", noReport.json["violations"][0]["pointer"].asString())
        assertEquals(422, call(t.bob, "/v1/config/revisions/$id/publish", rv, """{"effective_from":"$from","reason":"Veröffentlichen"}""").status, "Pflichtfeld laut Vertrag")
        assertEquals(403, call(t.passenger, "/v1/config/revisions/$id/publish", rv, publishBody(from, rep)).status)
        assertEquals(404, call(team().alice, "/v1/config/revisions/$id/publish", rv, publishBody(from, rep)).status, "fremder Mandant")
        val d2 = draft(t, 23, at(3)); val rep2 = report(t, d2)
        assertEquals(409, call(t.bob, "/v1/config/revisions/$d2/publish", 1, publishBody(at(3), rep2)).status, "ohne Validierung und Freigabe")
        assertEquals("DRAFT", env.get("/v1/config/revisions/$d2", t.alice).json["state"].asString())
        assertEquals(200, call(t.bob, "/v1/config/revisions/$id/publish", rv, publishBody(from, rep)).status)
    }

    @Test
    fun wiederholung_mit_gleichem_idempotency_key_liefert_dasselbe_ergebnis_ohne_zweite_wirkung() {
        val t = team(); val from = at(2); val (id, rep, rv) = approved(t, 24, from)
        val key = idem()
        val first = call(t.bob, "/v1/config/revisions/$id/publish", rv, publishBody(from, rep), key)
        val retry = call(t.bob, "/v1/config/revisions/$id/publish", rv, publishBody(from, rep), key)
        assertEquals(200, first.status, first.text); assertEquals(200, retry.status)
        assertEquals(first.json["audit_event_id"].asString(), retry.json["audit_event_id"].asString()); assertEquals("true", retry.header("Idempotent-Replayed"))
        assertEquals(1, count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND object_ref=? AND action='config.revision.published'", t.tenant.value, "config_revision:$id"))
        // gleicher Schlüssel, anderer Inhalt: 409
        assertEquals(409, call(t.bob, "/v1/config/revisions/$id/publish", rv, publishBody(from, rep, "Anderer Grund"), key).status)
    }

    @Test
    fun hundert_parallele_publish_wiederholungen_mit_gleichem_idempotency_key_eine_wirkung() {
        val t = team(); val from = at(2); val (id, rep, rv) = approved(t, 26, from)
        val key = idem(); val pool = Executors.newFixedThreadPool(50); val start = CountDownLatch(1)
        val futures = (1..100).map { pool.submit<Resp> { start.await(); call(t.bob, "/v1/config/revisions/$id/publish", rv, publishBody(from, rep), key) } }
        start.countDown()
        val responses = futures.map { it.get(120, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(setOf(200), responses.map { it.status }.toSet(), responses.firstOrNull { it.status != 200 }?.text)
        assertEquals(1, responses.map { it.json["audit_event_id"].asString() }.toSet().size, "alle Antworten nennen dasselbe Audit")
        assertEquals(1, responses.count { it.header("Idempotent-Replayed") == null }, "genau eine Erstausführung")
        assertEquals(1, count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND object_ref=? AND action='config.revision.published'", t.tenant.value, "config_revision:$id"))
        assertEquals(1, count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='config.revision_published'", t.tenant.value, UUID.fromString(id)))
    }

    @Test
    fun rollback_ueber_die_api_erzeugt_neue_revision_und_laesst_die_fehlerhafte_lesbar() {
        val t = team(); val t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val good = approved(t, 20, t0); assertEquals(200, call(t.bob, "/v1/config/revisions/${good.first}/publish", good.third, publishBody(t0, good.second)).status)
        val from = at(1); val bad = approved(t, 200, from); assertEquals(200, call(t.bob, "/v1/config/revisions/${bad.first}/publish", bad.third, publishBody(from, bad.second)).status)
        val rbFrom = from.plusSeconds(3600)
        assertEquals(428, call(t.bob, "/v1/config/revisions/${bad.first}/rollback", null, """{"effective_from":"$rbFrom","reason":"Rücknahme"}""").status)
        assertEquals(412, call(t.bob, "/v1/config/revisions/${bad.first}/rollback", 1, """{"effective_from":"$rbFrom","reason":"Rücknahme"}""").status)
        val rb = call(t.bob, "/v1/config/revisions/${bad.first}/rollback", 4, """{"effective_from":"$rbFrom","reason":"Fehlerhafter Wert zurückgenommen"}""")
        assertEquals(201, rb.status, rb.text); assertEquals("DRAFT", rb.json["state"].asString()); assertEquals(bad.first, rb.json["rolled_back_from"].asString()); assertEquals(20, rb.json["values"]["max_wait_min"].asInt())
        // Rücknahme nur mit zweiter Person: Bob ist Ersteller der Rücknahme, Alice gibt frei
        val id = rb.json["id"].asString(); val rep = report(t, id)
        assertEquals(200, call(t.bob, "/v1/config/revisions/$id/validate", 1).status)
        assertEquals(403, call(t.bob, "/v1/config/revisions/$id/approve", 2).status)
        assertEquals(200, call(t.alice, "/v1/config/revisions/$id/approve", 2).status)
        assertEquals(200, call(t.alice, "/v1/config/revisions/$id/publish", 3, publishBody(rbFrom, rep)).status)
        assertEquals(200, effective(t, from.plusSeconds(60))); assertEquals(20, effective(t, rbFrom.plusSeconds(1)))
        val faulty = env.get("/v1/config/revisions/${bad.first}", t.alice)
        assertEquals(200, faulty.json["values"]["max_wait_min"].asInt()); assertEquals("SUPERSEDED".takeIf { false } ?: "PUBLISHED", faulty.json["state"].asString(), "Ende liegt in der Zukunft: noch nicht abgelöst")
        assertEquals(1, count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='config.revision_rolled_back'", t.tenant.value, UUID.fromString(id)))
        assertEquals(409, call(t.bob, "/v1/config/revisions/${good.first}/rollback", env.get("/v1/config/revisions/${good.first}", t.alice).json["row_version"].asLong(), """{"effective_from":"$rbFrom","reason":"Rücknahme"}""").status, "erste Revision hat keine frühere")
    }

    @Test
    fun konfiguration_in_mandant_a_aendert_die_wirksame_konfiguration_von_b_nicht() {   // MUL-007 (Konfigurationsteil)
        val a = team(); val b = team(); val from = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val (ib, rb, vb) = approved(b, 30, from); assertEquals(200, call(b.bob, "/v1/config/revisions/$ib/publish", vb, publishBody(from, rb)).status)
        val probe = from.plusSeconds(600)
        val before = env.get("/v1/config/effective?at=$probe", b.alice).json
        val (ia, ra, va) = approved(a, 99, from); assertEquals(200, call(a.bob, "/v1/config/revisions/$ia/publish", va, publishBody(from, ra)).status)
        assertEquals(99, effective(a, probe))
        val after = env.get("/v1/config/effective?at=$probe", b.alice).json
        assertEquals(before.toString().replace(Regex("\"as_of\":\"[^\"]*\""), ""), after.toString().replace(Regex("\"as_of\":\"[^\"]*\""), ""), "Vorher/Nachher von B identisch")
        assertEquals(30, after["values"]["max_wait_min"].asInt())
    }
}
