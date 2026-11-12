package de.taktovia.app.geo

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import de.taktovia.identity.api.ContactChannel
import de.taktovia.identity.api.InMemoryVerificationSender
import de.taktovia.identity.api.VerificationSender
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * WP-M2-38 / LH-6.3-PAX-002 über die echte Anwendung (HTTP, echte Datenbank, Runtime-Rolle, lokaler Test-Issuer statt Keycloak): die fünf Eingabearten mit eigenen
 * Favoriten des Fahrgasts, Annahme der Treffer im Schema von `POST /v1/ride-offers`, Datentrennung, Quote, Suchtext nie in Logs oder Metriklabels, 422 bei ungültigen Eingaben.
 * Der Geocoder ist der Simulator hinter derselben Schnittstelle (keine echte OSM-Quelle, D-09).
 */
class PlaceSearchApiTest {
    private val sender get() = GeoWorld().env.server.ctx.getBean(VerificationSender::class.java) as InMemoryVerificationSender
    private val simulator get() = GeoWorld().env.server.ctx.getBean(de.taktovia.servicegeo.api.Geocoder::class.java) as SimulatorGeocoder
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    private val hours = """{"traffic_product":"AREA_ON_DEMAND","weekly_hours":[{"weekdays":["MON","TUE","WED","THU","FRI","SAT","SUN"],"from":"00:00","to":"24:00"}],"exceptions":[],"valid_from":"2026-01-01T00:00:00+01:00","valid_to":null,"reason":"Test"}"""
    private fun area(x: Double) = """{"type":"MultiPolygon","coordinates":[[[[$x,51.0],[${x + 0.1},51.0],[${x + 0.1},51.1],[$x,51.1],[$x,51.0]]]]}"""
    private fun stopBody(name: String, lat: Double, lon: Double) = """{"name":"$name","point":{"latitude":$lat,"longitude":$lon},"accessibility":{"schema_version":"1.0","step_free":"YES","wheelchair_boarding":"UNVERIFIED"},"traffic_products":["AREA_ON_DEMAND"],"active":true,"valid_from":"2026-01-01T00:00:00+01:00"}"""

    private class Pax(val subject: UUID, val token: String)

    /** Registrierter, verifizierter Fahrgast im Mandanten der Welt (Konto mit Profil, nicht nur Mitgliedschaft). */
    private fun passenger(w: GeoWorld): Pax {
        val subject = UUID.randomUUID(); val email = "pax.${UUID.randomUUID().toString().take(8)}@example.org"
        val token = w.env.idp.sign(w.env.idp.humanClaims(sub = subject, roles = listOf("passenger"), acr = "loa1", tenants = listOf(w.tenant.value), azp = "passenger-web").also { it["sid"] = "sid-" + UUID.randomUUID() })
        assertEquals(201, w.env.postIdem("/v1/passengers", """{"contact":{"channel":"EMAIL","value":"$email"},"locale":"de-DE"}""", token).status)
        assertEquals(200, w.env.postIdem("/v1/me/contact-verifications/confirmations", """{"channel":"EMAIL","code":"${sender.last(ContactChannel.EMAIL, email)!!}"}""", token).status)
        return Pax(subject, token)
    }

    private fun setFavorites(w: GeoWorld, p: Pax, json: String) {
        val rev = w.env.get("/v1/me/profile", p.token).json["revision"].asLong()
        val r = w.env.server.send("PATCH", "/v1/me/profile", """{"favorites":$json}""", w.env.headers(p.token, extra = w.idem() + mapOf("If-Match" to "\"$rev\"")))
        assertEquals(200, r.status, r.text)
    }

    @Test
    fun fuenf_eingabearten_ueber_api_und_ride_offer_schema() {
        val w = GeoWorld(); val svc = w.service(); w.put("/v1/services/$svc/operating-hours", hours, "0")
        assertEquals(201, w.post("/v1/service-areas", """{"service_id":"$svc","area":${area(13.7)},"valid_from":"2026-01-01T00:00:00+01:00","valid_to":null}""").status)
        val stop = w.post("/v1/stops", stopBody("Altmarkt", 51.05, 13.74)); assertEquals(201, stop.status, stop.text)
        val stopId = stop.json["stop_id"].asString()
        val p = passenger(w); setFavorites(w, p, """[{"label":"Zuhause","lat":51.04,"lon":13.75}]""")
        fun items(query: String) = w.get("/v1/places?service_id=$svc&$query", p.token).also { assertEquals(200, it.status, it.text) }.json["items"].values().toList()

        val address = items("q=${enc("Hauptstraße")}").single(); val poi = items("q=Zwinger").single(); val byStop = items("q=altm").single()
        val byFav = items("q=zuhau").single(); val byMap = items("map_latitude=51.0505&map_longitude=13.7405").single()
        assertEquals(listOf("ADDRESS", "POI", "STOP", "FAVORITE", "MAP_POINT"), listOf(address, poi, byStop, byFav, byMap).map { it["type"].asString() })
        assertEquals(stopId, byStop["stop_id"].asString()); assertEquals("INSIDE", address["service_area"].asString()); assertEquals(stopId, address["nearest_stop"]["stop_id"].asString())
        assertEquals(51.04, byFav["point"]["latitude"].asDouble(), 1e-9); assertTrue(byFav["stop_id"].isNull)
        for (h in listOf(address, poi, byStop, byFav, byMap)) {
            val lat = h["point"]["latitude"].asDouble(); val lon = h["point"]["longitude"].asDouble()
            assertTrue(lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0))
            // Treffer unverändert als Start in POST /v1/ride-offers: gegen das Anfrageschema des Vertrags geprüft
            val place = JsonMapper().createObjectNode().also { o -> o.set("point", h["point"]); o.put("label", h["label"].asString()); if (!h["stop_id"].isNull) o.put("stop_id", h["stop_id"].asString()) }
            val body = """{"service_id":"$svc","origin":$place,"destination":$place,"time_kind":"DEPARTURE","desired":{"local":"2026-12-01T10:00:00","zone":"Europe/Berlin","offset":"+01:00"},"passengers":1}"""
            val reg = w.env.server.registry; val op = reg.match("POST", "/v1/ride-offers")!!
            val errors = reg.schemaErrors(reg.requestSchema(op)!!, JsonMapper().readTree(body))
            assertEquals(emptyList<String>(), errors.filter { !it.contains("desired") }, "Ortstreffer ${h["type"].asString()} ist als origin/destination zulässig")
        }
    }

    @Test
    fun fremde_favoriten_nie_sichtbar_und_betreiber_ohne_konto_ohne_favoriten() {
        val w = GeoWorld(); val other = GeoWorld()
        val a = passenger(w); val b = passenger(w); val c = passenger(other)
        setFavorites(w, a, """[{"label":"Geheimes Ziel A","lat":51.04,"lon":13.75}]"""); setFavorites(other, c, """[{"label":"Geheimes Ziel C","lat":48.1,"lon":11.5}]""")
        fun labels(w: GeoWorld, token: String, q: String) = w.get("/v1/places?q=${enc(q)}", token).also { assertEquals(200, it.status, it.text) }.json["items"].values().map { it["label"].asString() }
        assertEquals(listOf("Geheimes Ziel A"), labels(w, a.token, "Geheimes"))
        assertEquals(emptyList<String>(), labels(w, b.token, "Geheimes"), "anderer Fahrgast desselben Mandanten")
        assertEquals(emptyList<String>(), labels(w, w.planner, "Geheimes"), "Betreiber ohne Fahrgastkonto")
        assertEquals(listOf("Geheimes Ziel C"), labels(other, c.token, "Geheimes"))
        assertEquals(emptyList<String>(), labels(w, a.token, "Ziel C"), "Favorit eines anderen Mandanten")
    }

    @Test
    fun geocoder_ausfall_und_unaufloesbarer_text_ueber_api() {
        val w = GeoWorld(); val p = passenger(w); w.post("/v1/stops", stopBody("Hauptmarkt", 51.05, 13.74)); setFavorites(w, p, """[{"label":"Haupt-Wohnung","lat":51.04,"lon":13.75}]""")
        simulator.failing = true
        try {
            val r = w.get("/v1/places?q=Haupt", p.token)
            assertEquals(200, r.status, r.text)
            assertEquals(setOf("STOP", "FAVORITE"), r.json["items"].values().map { it["type"].asString() }.toSet()); assertEquals(listOf("GEOCODER_UNAVAILABLE"), r.json["notices"].values().map { it.asString() })
            val map = w.get("/v1/places?map_latitude=51.05&map_longitude=13.74", p.token)
            assertEquals("MAP_POINT", map.json["items"][0]["type"].asString()); assertEquals("Punkt auf der Karte", map.json["items"][0]["label"].asString())
        } finally { simulator.failing = false }
        val none = w.get("/v1/places?q=${enc("Quuxfeld Nirgendwo")}", p.token)
        assertEquals(200, none.status); assertEquals(0, none.json["items"].size()); assertEquals(listOf("NO_MATCH"), none.json["notices"].values().map { it.asString() })
    }

    @Test
    fun ungueltige_eingaben_422_mit_query_zeiger() {
        val w = GeoWorld(); val p = passenger(w)
        fun bad(query: String, pointer: String) { val r = w.get("/v1/places?$query", p.token); assertEquals(422, r.status, "$query: ${r.text}"); assertEquals(pointer, r.json["violations"][0]["pointer"].asString(), query) }
        bad("q=", "/\$query/q"); bad("q=a", "/\$query/q"); bad("q=${"x".repeat(201)}", "/\$query/q"); bad("", "/\$query/q")
        bad("map_latitude=91&map_longitude=10", "/\$query/map_latitude"); bad("map_latitude=10&map_longitude=181", "/\$query/map_longitude")
        bad("map_latitude=0&map_longitude=0", "/\$query/map_point"); bad("map_latitude=10", "/\$query/map_longitude")
        bad("q=Haupt&map_latitude=10&map_longitude=10", "/\$query/map_point"); bad("q=Haupt&bias_latitude=95&bias_longitude=1", "/\$query/bias_latitude")
        bad("q=Haupt&service_id=kein-uuid", "/\$query/service_id"); bad("q=Haupt&limit=0", "/\$query/limit")
        assertEquals(403, w.get("/v1/places?q=Haupt", w.env.token(UUID.randomUUID())).status, "ohne Mitgliedschaft")
        assertEquals(401, w.env.server.get("/v1/places?q=Haupt").status)
    }

    @Test
    fun rate_limit_greift_je_akteur() {
        val w = GeoWorld(); val p = passenger(w); val q = passenger(w)
        val statuses = (1..31).map { w.get("/v1/places?q=Haupt", p.token) }
        assertTrue(statuses.take(30).all { it.status == 200 }, statuses.map { it.status }.toString())
        val limited = statuses.last()
        assertEquals(429, limited.status, limited.text); assertNotNull(limited.header("Retry-After")); assertEquals("RATE_LIMITED", limited.json["code"].asString())
        assertEquals(200, w.get("/v1/places?q=Haupt", q.token).status, "andere Akteure sind nicht betroffen")
        assertEquals(200, w.get("/v1/stops", p.token).status, "andere Operationen desselben Akteurs bleiben nutzbar")
    }

    @Test
    fun suchtext_nicht_in_metriklabels_und_logs() {
        val w = GeoWorld(); val p = passenger(w); w.post("/v1/stops", stopBody("Geheimhalt", 51.05, 13.74))
        val marker = "Geheimort" + UUID.randomUUID().toString().take(8)
        val collected = CopyOnWriteArrayList<ILoggingEvent>()
        val appender = object : AppenderBase<ILoggingEvent>() { override fun append(e: ILoggingEvent) { collected += e } }.also { it.start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        // Festgelegte Stufe: ab DEBUG darf kein Suchtext im Log stehen; TRACE (Parameterprotokoll von Spring-JDBC und Treiber) ist in keiner Umgebung eingeschaltet.
        val before = root.level; root.level = Level.DEBUG; root.addAppender(appender)
        try {
            w.get("/v1/places?q=$marker", p.token); w.get("/v1/places?q=Geheimhalt&bias_latitude=51.0123&bias_longitude=13.7654", p.token)
            simulator.failing = true; w.get("/v1/places?q=$marker", p.token); w.get("/v1/places?q=${enc("<script>")}", p.token); w.get("/v1/places?q=a", p.token)
            simulator.failing = false
        } finally { simulator.failing = false; root.detachAppender(appender); root.level = before }
        val text = collected.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") + " " + it.mdcPropertyMap }
        assertFalse(text.contains(marker) || text.contains("Geheimhalt") || text.contains("<script>"), "Suchtext im Log: " + collected.filter { e -> e.formattedMessage.contains(marker) || e.formattedMessage.contains("Geheimhalt") || e.formattedMessage.contains("<script>") }.joinToString { it.loggerName + ": " + it.formattedMessage })
        assertFalse(text.contains("51.0123") || text.contains("13.7654"), "Suchkoordinate im Log")
        val registry = w.env.server.ctx.getBeanProvider(MeterRegistry::class.java).getIfAvailable()
        val tags = registry?.meters?.flatMap { m -> m.id.tags.map { it.key + "=" + it.value } }?.joinToString("\n") ?: ""
        assertFalse(tags.contains(marker) || tags.contains("Geheimhalt") || tags.contains("51.0123"), "Suchtext oder Ort als Metriklabel")
    }
}
