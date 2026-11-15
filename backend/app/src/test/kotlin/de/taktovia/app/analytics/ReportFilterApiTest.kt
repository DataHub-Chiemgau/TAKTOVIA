package de.taktovia.app.analytics

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * WP-M4-58 (ANA-003, KPI-011) über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): Filterdimensionen Region und Stunde, ein manipulierter Mandantenbezug (`tenant_id`-Parameter, Header) wird nicht
 * ausgewertet, ein dienstbeschränkter Benutzer sieht höchstens seine Dienste (Filter schneiden nur ein), unzulässige Parameter sind 400, kleine Gruppen erscheinen als „unterdrückt“ statt 0.
 */
class ReportFilterApiTest {
    private val env = IdentityEnv
    private val mapper = JsonMapper.builder().build()
    private val analytics get() = env.server.ctx.getBean(AnalyticsApi::class.java)

    private class World(val env: IdentityEnv) {
        val tenant = env.tenant("Berichtsfilter-" + UUID.randomUUID())
        val op = UUID.randomUUID(); val r1 = UUID.randomUUID(); val r2 = UUID.randomUUID(); val s1 = UUID.randomUUID(); val s2 = UUID.randomUUID()
        val planner = UUID.randomUUID(); val scoped = UUID.randomUUID()
        val plannerToken: String; val scopedToken: String
        init {
            val t = tenant.value; val le = UUID.randomUUID(); val rev1 = UUID.randomUUID(); val rev2 = UUID.randomUUID()
            env.exec("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", t, le)
            env.exec("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,'Org',?,CAST('{TRANSPORT_COMPANY}' AS text[]))", t, op, le, "f-$op")
            listOf(r1 to "R1", r2 to "R2").forEach { (id, n) -> env.exec("INSERT INTO taktovia.region(tenant_id,id,name,created_by) VALUES (?,?,?,'user:test')", t, id, n) }
            listOf(Triple(s1, rev1, r1), Triple(s2, rev2, r2)).forEach { (s, rev, r) ->
                env.exec("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'SERVICE',?,1,'1','{}','2026-01-01','user:test','Test')", t, rev, s)
                env.exec("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,region_id,created_by) VALUES (?,?,?,'Dienst','Europe/Berlin',?,?,'user:test')", t, s, op, rev, r)
            }
            env.grant(tenant, planner, "planner"); env.grant(tenant, scoped, "dispatcher", listOf(s1))
            plannerToken = env.token(planner); scopedToken = env.token(scoped)
        }
    }

    private fun attempt(t: TenantId, service: UUID, at: String, outcome: String = "RECORDED", primary: String? = null) {
        val id = UUID.randomUUID()
        val payload = linkedMapOf<String, Any?>("attempt_id" to id.toString(), "service_id" to service.toString(), "outcome" to outcome, "time_mode" to "DEPARTURE", "requested_at" to at, "passengers" to 1, "channel" to "WEB")
        if (primary != null) payload["primary_reason"] = primary
        analytics.facts.consume(t, EventEnvelope(UUID.randomUUID(), "booking.attempt_recorded", "1.0", id, 1, Instant.parse(at), Instant.parse(at), t.value, t.value, UUID.randomUUID(), mapper.valueToTree(payload)).toJson())
    }

    /** Dienst 1 (Region 1): fünf Versuche um 08 Uhr Ortszeit (07:xxZ, eine Ablehnung); Dienst 2 (Region 2): vier um 08 Uhr. */
    private fun data(w: World) {
        repeat(5) { attempt(w.tenant, w.s1, "2026-12-01T07:1$it:00Z", if (it == 0) "REJECTED" else "RECORDED", if (it == 0) "NO_CAPACITY" else null) }
        repeat(4) { attempt(w.tenant, w.s2, "2026-12-01T07:2$it:00Z") }
    }

    private val q = "from=2026-12-01&to=2026-12-01&metric_id=requests"
    private fun requests(w: World, token: String, extra: String = "", headers: Map<String, String> = emptyMap()) = env.get("/v1/analytics/metrics?$q$extra", token, extra = headers)

    @Test
    fun region_und_stunde_ueber_http_und_listenhuelle() {
        val w = World(env); data(w)
        val all = requests(w, w.plannerToken); assertEquals(200, all.status, all.text)
        assertEquals(9, all.json["values"][0]["value"].asInt())
        // Listenhülle: items, next_cursor, as_of
        assertEquals(1, all.json["items"].size()); assertTrue(all.json["next_cursor"].isNull); assertNotNull(all.json["as_of"].asString())
        val r1 = requests(w, w.plannerToken, "&region_id=${w.r1}&hour=8")
        assertEquals(200, r1.status, r1.text); assertEquals(5, r1.json["values"][0]["value"].asInt()); assertEquals("8", r1.json["values"][0]["filter"]["hour"].asString()); assertEquals(w.r1.toString(), r1.json["values"][0]["filter"]["region_id"].asString())
        assertEquals(4, requests(w, w.plannerToken, "&region_id=${w.r2}&hour=8").json["values"][0]["value"].asInt())
        // Stunde 9: keine Versuche, Betreiber und Tag kombiniert
        assertEquals(0, requests(w, w.plannerToken, "&operator_id=${w.op}&day=2026-12-01&hour=9").json["values"][0]["value"].asInt())
        // Ablehnungsstatistik trägt dieselben Dimensionen
        val rej = env.get("/v1/analytics/metrics?from=2026-12-01&breakdown=rejection_reason&region_id=${w.r1}&hour=8", w.plannerToken)
        assertEquals(200, rej.status, rej.text); assertEquals(5, rej.json["rejections"]["attempts"].asInt()); assertEquals(1, rej.json["rejections"]["rejections"].asInt())
        // Definitionen nennen die Berichtsdimensionen
        val def0 = env.get("/v1/analytics/metrics?metric_id=requests", w.plannerToken).json["definitions"][0]
        val dims: List<String> = (0 until def0["report_dimensions"].size()).map { def0["report_dimensions"][it].asString() } + (0 until def0["filter_dimensions"].size()).map { def0["filter_dimensions"][it].asString() }
        assertTrue(dims.containsAll(listOf("service_id", "operator_id", "region_id", "service_area_id", "hour", "day")), dims.toString())
    }

    @Test
    fun manipulierter_mandantenbezug_wird_nicht_ausgewertet() {
        val w = World(env); data(w); val other = World(env); repeat(7) { attempt(other.tenant, other.s1, "2026-12-01T07:1$it:00Z") }
        val plain = requests(w, w.plannerToken)
        // ein fremder Mandantenbezug in Query oder Header wird nie ausgewertet: 404 wie „nicht vorhanden“ (Anfragefilter), kein Datum des anderen Mandanten
        for (r in listOf(requests(w, w.plannerToken, "&tenant_id=${other.tenant.value}"), requests(w, w.plannerToken, "", mapOf("X-Tenant-Id" to other.tenant.value.toString())))) {
            assertEquals(404, r.status, r.text); assertTrue(!r.text.contains(other.tenant.value.toString()) && !r.text.contains("\"value\":7"))
        }
        // der eigene Mandant im Filter ändert nichts; es gilt der aktive Mandant
        val own = requests(w, w.plannerToken, "", mapOf("X-Tenant-Id" to w.tenant.value.toString())); assertEquals(200, own.status, own.text)
        assertEquals(422, requests(w, w.plannerToken, "&tenant_id=${w.tenant.value}").status, "`tenant_id` ist kein Filterparameter (die Dimension Mandant ist immer der aktive)")
        assertEquals(plain.json["values"][0]["value"].asInt(), own.json["values"][0]["value"].asInt()); assertEquals(9, own.json["values"][0]["value"].asInt(), "nur der aktive Mandant")
        // Dienst, Region und Betreiber des anderen Mandanten: leere Menge, dieselbe Antwort wie nie vergebene Kennungen
        for (p in listOf("service_id=${other.s1}", "region_id=${other.r1}", "operator_id=${other.op}")) {
            val foreign = requests(w, w.plannerToken, "&$p"); val fake = requests(w, w.plannerToken, "&" + p.substringBefore('=') + "=" + UUID.randomUUID())
            assertEquals(200, foreign.status, foreign.text); assertEquals(0, foreign.json["values"][0]["value"].asInt(), p); assertEquals(fake.json["values"][0]["value"], foreign.json["values"][0]["value"]); assertEquals(fake.json["values"][0]["state"], foreign.json["values"][0]["state"])
        }
    }

    @Test
    fun dienstbeschraenkter_benutzer_sieht_hoechstens_seine_dienste_filter_erweitern_nicht() {
        val w = World(env); data(w)
        val scoped = requests(w, w.scopedToken); assertEquals(200, scoped.status, scoped.text); assertEquals(5, scoped.json["values"][0]["value"].asInt(), "nur Dienst 1")
        assertEquals(5, requests(w, w.scopedToken, "&hour=8").json["values"][0]["value"].asInt())
        // Wunsch auf Region 2, Dienst 2 oder Betreiber (Dienst 2 liegt außerhalb): kein 403, keine fremden Daten, nur die erlaubte Schnittmenge
        assertEquals(0, requests(w, w.scopedToken, "&region_id=${w.r2}").json["values"][0]["value"].asInt()); assertEquals(0, requests(w, w.scopedToken, "&service_id=${w.s2}").json["values"][0]["value"].asInt())
        assertEquals(5, requests(w, w.scopedToken, "&operator_id=${w.op}").json["values"][0]["value"].asInt(), "der Betreiber hat auch Dienst 2, der Scope schneidet ihn ab")
        assertEquals(9, requests(w, w.plannerToken).json["values"][0]["value"].asInt(), "ohne Dienstbeschränkung der ganze Mandant")
    }

    @Test
    fun unzulaessige_parameter_sind_400() {
        val w = World(env); data(w)
        // Formatfehler meldet die Schemaprüfung des Vertrags (422), fachlich unzulässige Kombinationen die Auswertung (400)
        for (bad in listOf("&hour=24", "&hour=-1", "&hour=x", "&day=2026-13-01", "&region_id=nope", "&operator_id=1", "&service_area_id=x", "&vehicle_type=kleinbus")) {
            val r = requests(w, w.plannerToken, bad); assertEquals(422, r.status, "$bad -> ${r.text}")
        }
        for (bad in listOf("&day=2026-12-09", "&vehicle_type=BUS")) { val r = requests(w, w.plannerToken, bad); assertEquals(400, r.status, "$bad -> ${r.text}") }
        // Dimension ohne Zeitraum
        assertEquals(400, env.get("/v1/analytics/metrics?hour=8", w.plannerToken).status)
        assertEquals(400, env.get("/v1/analytics/metrics?operator_id=${w.op}", w.plannerToken).status)
        // ohne metric_id erscheinen nur Kennzahlen, die alle gesetzten Dimensionen kennen (Energiekosten kennen weder Region noch Stunde)
        val some = env.get("/v1/analytics/metrics?from=2026-12-01&region_id=${w.r1}&hour=8", w.plannerToken); assertEquals(200, some.status, some.text)
        val ids = (0 until some.json["values"].size()).map { some.json["values"][it]["metric_id"].asString() }
        assertTrue("requests" in ids && "energy_cost_per_100km" !in ids, ids.toString())
    }

    @Test
    fun kleine_gruppe_ist_unterdrueckt_nicht_null() {
        val w = World(env); repeat(8) { attempt(w.tenant, w.s1, "2026-12-01T07:1$it:00Z") }; repeat(2) { attempt(w.tenant, w.s2, "2026-12-01T07:2$it:00Z", "REJECTED", "NO_CAPACITY") }
        val small = requests(w, w.plannerToken, "&region_id=${w.r2}").json["values"][0]
        assertEquals("NOT_COMPUTABLE", small["state"].asString()); assertEquals("SMALL_GROUP", small["reason"].asString()); assertTrue(small["value"].isNull)
        // die Gegengruppe (8 von 10, Komplement 2) ebenso: sonst wäre die kleine Gruppe durch Differenzbildung ablesbar
        assertEquals("SMALL_GROUP", requests(w, w.plannerToken, "&region_id=${w.r1}").json["values"][0]["reason"].asString())
        val rej = env.get("/v1/analytics/metrics?from=2026-12-01&breakdown=rejection_reason&region_id=${w.r2}", w.plannerToken).json["rejections"]
        assertTrue(rej["suppressed"].asBoolean()); assertTrue(rej["attempts"].isNull); assertTrue(rej["rejections"].isNull); assertEquals(0, rej["rows"].size())
        val funnel = env.get("/v1/analytics/metrics?from=2026-12-01&breakdown=funnel&region_id=${w.r2}", w.plannerToken).json["funnel"]
        assertTrue(funnel["suppressed"].asBoolean()); assertTrue(funnel["counts"]["requests"].isNull)
        // ohne Dimensionsfilter bleibt alles sichtbar
        assertEquals(10, requests(w, w.plannerToken).json["values"][0]["value"].asInt())
    }
}
