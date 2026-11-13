package de.taktovia.app.analytics

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * WP-M4-46 bis -50 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): drei Benutzer verschiedener Rollen mit identischen Filtern erhalten identische Kennzahlen samt Datenstand (KPI-010), Definition
 * mit Zähler/Nenner/Ausschlüssen/Zeitbezug/Filterdimensionen, Nullnenner und fehlende Daten als „nicht berechenbar“, vorläufiger Wert bei Lücken, Ablehnungen nach Grund mit Text (KPI-002 bis -004),
 * 400 für unzulässige Filterdimension und unbekannten Code, 404 für unbekannte Kennzahl, 403 ohne Recht, Mandantentrennung.
 */
class MetricsApiTest {
    private val env = IdentityEnv
    private val mapper = JsonMapper.builder().build()
    private val analytics get() = env.server.ctx.getBean(AnalyticsApi::class.java)
    private val service = UUID.randomUUID()

    private class World(val env: IdentityEnv) {
        val tenant = env.tenant("Kennzahlen-" + UUID.randomUUID())
        val dispatcher = UUID.randomUUID(); val planner = UUID.randomUUID(); val admin = UUID.randomUUID(); val driver = UUID.randomUUID(); val finance = UUID.randomUUID()
        init { env.grant(tenant, dispatcher, "dispatcher"); env.grant(tenant, planner, "planner"); env.grant(tenant, admin, "tenant_admin"); env.grant(tenant, driver, "driver"); env.grant(tenant, finance, "finance") }
        val tokens = mapOf("dispatcher" to env.token(dispatcher), "planner" to env.token(planner), "tenant_admin" to env.token(admin))
        val driverToken = env.token(driver); val financeToken = env.token(finance)
    }

    private fun attempt(t: TenantId, outcome: String = "RECORDED", primary: String? = null, secondary: List<String> = emptyList(), revision: Long = 1, id: UUID = UUID.randomUUID()) {
        val at = "2026-12-01T05:00:00Z"
        val payload = linkedMapOf<String, Any?>("attempt_id" to id.toString(), "service_id" to service.toString(), "outcome" to outcome, "time_mode" to "DEPARTURE", "requested_at" to at, "passengers" to 1, "channel" to "WEB")
        if (primary != null) payload["primary_reason"] = primary
        if (secondary.isNotEmpty()) payload["secondary_reasons"] = secondary
        val e = EventEnvelope(UUID.randomUUID(), "booking.attempt_recorded", "1.0", id, revision, Instant.parse(at), Instant.parse(at), t.value, t.value, UUID.randomUUID(), mapper.valueToTree(payload))
        analytics.facts.consume(t, e.toJson())
    }

    private fun ph021(t: TenantId) {
        attempt(t, "REJECTED", "NO_CAPACITY", listOf("OUTSIDE_AREA")); attempt(t, "REJECTED", "LEAD_TIME", listOf("OUTSIDE_HOURS", "NO_STAFF")); attempt(t, "REJECTED", "OUTSIDE_AREA")
        repeat(7) { attempt(t) }
    }

    private val q = "from=2026-12-01&to=2026-12-01"

    @Test
    fun drei_benutzer_verschiedener_rollen_erhalten_identische_kennzahlen_samt_datenstand() {
        val w = World(env); ph021(w.tenant)
        val bodies = w.tokens.mapValues { (_, token) -> env.get("/v1/analytics/metrics?$q&breakdown=rejection_reason", token).also { assertEquals(200, it.status, it.text) } }
        val values = bodies.mapValues { it.value.json["values"] }
        assertEquals(values["dispatcher"].toString(), values["planner"].toString()); assertEquals(values["planner"].toString(), values["tenant_admin"].toString())
        assertEquals(bodies["dispatcher"]!!.json["rejections"].toString(), bodies["tenant_admin"]!!.json["rejections"].toString())
        val requests = values["dispatcher"]!!.single { it["metric_id"].asString() == "requests" }
        assertEquals(10, requests["value"].asInt()); assertEquals("OK", requests["state"].asString()); assertEquals(1, requests["definition_version"].asInt()); assertEquals("2026-12-01T05:00:00Z", requests["as_of"].asString())
        assertEquals("Europe/Berlin", requests["period"]["timezone"].asString()); assertEquals("2026-11-30T23:00:00Z", requests["period"]["from_instant"].asString())
        val r = bodies["dispatcher"]!!.json["rejections"]
        assertEquals(3, r["rejections"].asInt()); assertEquals(3, r["rows"].filter { it["primary_count"].asInt() > 0 }.sumOf { it["primary_count"].asInt() })
        assertTrue(r["rows"].all { it["text"].asString().isNotBlank() && it["texts"]["en"].asString().isNotBlank() })
    }

    @Test
    fun definitionen_weisen_zaehler_nenner_ausschluesse_zeitbezug_und_filterdimensionen_aus() {
        val w = World(env)
        val r = env.get("/v1/analytics/metrics", w.tokens.getValue("planner"))
        assertEquals(200, r.status, r.text); assertTrue(r.json["values"].isNull)
        val defs = r.json["definitions"]; assertEquals(22, defs.size())
        for (d in defs) {
            for (k in listOf("numerator", "denominator", "exclusions", "time_basis")) assertTrue(d[k].asString().isNotBlank(), "${d["metric_id"].asString()}.$k")
            assertTrue(d["filter_dimensions"].isArray); assertEquals(1, d["version"].asInt())
        }
        val one = env.get("/v1/analytics/metrics?metric_id=requests", w.tokens.getValue("planner"))
        assertEquals(1, one.json["definitions"].size()); assertEquals("requests", one.json["definitions"][0]["metric_id"].asString())
    }

    @Test
    fun nullnenner_fehlende_daten_und_vorlaeufiger_wert() {
        val w = World(env)
        val empty = env.get("/v1/analytics/metrics?$q&metric_id=technical_request_errors", w.tokens.getValue("dispatcher")).json["values"][0]
        assertEquals("NOT_COMPUTABLE", empty["state"].asString()); assertEquals("NO_DATA", empty["reason"].asString()); assertTrue(empty["value"].isNull)
        attempt(w.tenant, id = UUID.randomUUID()); attempt(w.tenant, "TECHNICAL_ERROR", revision = 2)   // zweiter Versuch ohne erste Revision: Lücke
        val gap = env.get("/v1/analytics/metrics?$q&metric_id=requests", w.tokens.getValue("dispatcher")).json["values"][0]
        assertEquals("PROVISIONAL", gap["state"].asString()); assertEquals(0.5, gap["coverage"].asDouble(), 0.0001); assertEquals(2, gap["value"].asInt())
        val noDay = env.get("/v1/analytics/metrics?from=2026-12-24&metric_id=technical_request_errors", w.tokens.getValue("dispatcher")).json["values"][0]
        assertEquals("NOT_COMPUTABLE", noDay["state"].asString()); assertEquals("ZERO_DENOMINATOR", noDay["reason"].asString()); assertTrue(noDay["value"].isNull)
    }

    @Test
    fun fehlerfaelle_400_404_403_und_mandantentrennung() {
        val w = World(env); val other = World(env); ph021(w.tenant)
        val t = w.tokens.getValue("dispatcher")
        assertEquals(404, env.get("/v1/analytics/metrics?$q&metric_id=gibt_es_nicht", t).status)
        assertEquals(404, env.get("/v1/analytics/metrics?metric_id=requests&version=9", t).status)
        assertEquals(400, env.get("/v1/analytics/metrics?$q&metric_id=booking_conversion&channel=WEB", t).status, "Filterdimension nicht zulässig")
        assertEquals(400, env.get("/v1/analytics/metrics?$q&breakdown=rejection_reason&reason_code=GIBT_ES_NICHT", t).status, "unbekannter Ablehnungscode")
        assertEquals(422, env.get("/v1/analytics/metrics?from=nicht-datum", t).status, "Formatfehler meldet die Schemaprüfung des Vertrags"); assertEquals(400, env.get("/v1/analytics/metrics?to=2026-12-01", t).status)
        assertEquals(400, env.get("/v1/analytics/metrics?breakdown=rejection_reason", t).status, "Aufschlüsselung braucht einen Zeitraum")
        assertEquals(403, env.get("/v1/analytics/metrics?$q", w.driverToken).status); assertEquals(403, env.get("/v1/analytics/metrics?$q", w.financeToken).status)
        assertEquals(401, env.get("/v1/analytics/metrics?$q", "kein-token").status)
        val own = env.get("/v1/analytics/metrics?$q&metric_id=requests", t).json["values"][0]["value"].asInt()
        val foreign = env.get("/v1/analytics/metrics?$q&metric_id=requests", other.tokens.getValue("dispatcher")).json["values"][0]
        assertEquals(10, own); assertTrue(foreign["state"].asString() == "NOT_COMPUTABLE" && foreign["reason"].asString() == "NO_DATA", "der andere Mandant sieht keine Daten von ${w.tenant}")
        assertFalse(env.get("/v1/analytics/metrics?$q&breakdown=rejection_reason&reason_code=NO_CAPACITY", other.tokens.getValue("planner")).json["rejections"]["rows"].iterator().hasNext())
    }
}
