package de.taktovia.app.documents

import de.taktovia.app.contract.W12C9ContractCases
import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP-M4-41: Kilometerstandsregister über die echte HTTP-Schnittstelle (Anmeldung, Rechte, Mandantentrennung, Konflikte, Auflösung). */
class OdometerApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())
    private fun post(w: W12C9ContractCases.World, path: String, body: String, token: String = w.token) = env.server.send("POST", path, body, env.headers(token, extra = idem()))
    private fun get(path: String, token: String) = env.server.send("GET", path, null, env.headers(token))

    @Test
    fun stand_conflikt_und_aufloesung_ueber_die_schnittstelle() {
        val w = W12C9ContractCases.World()
        val first = post(w, "/v1/vehicles/${w.vehicle}/odometer", W12C9ContractCases.reading(5_000, "wst-1", "2026-06-01T08:00:00Z"))
        assertEquals(201, first.status); assertEquals("WORKSHOP", first.json["reading"]["source"].asString()); assertTrue(first.json["created"].asBoolean())
        // dieselbe Quelle und derselbe Bezug: 200 mit dem vorhandenen Stand, kein zweiter Eintrag
        val again = post(w, "/v1/vehicles/${w.vehicle}/odometer", W12C9ContractCases.reading(5_000, "wst-1", "2026-06-01T08:00:00Z"))
        assertEquals(200, again.status); assertEquals(first.json["reading"]["id"].asString(), again.json["reading"]["id"].asString()); assertEquals(false, again.json["created"].asBoolean())
        // rückläufiger Stand: 201 mit dem Konflikt in der Antwort
        val dec = post(w, "/v1/vehicles/${w.vehicle}/odometer", W12C9ContractCases.reading(4_000, "wst-2", "2026-06-01T10:00:00Z"))
        assertEquals(201, dec.status); assertEquals("DECREASING", dec.json["conflicts"][0]["kind"].asString())
        val list = get("/v1/vehicles/${w.vehicle}/odometer", w.token).json["items"]
        assertEquals(listOf(5000L, 4000L), (0 until list.size()).map { list[it]["reading_km"].asLong() }, "beide Rohstände bleiben erhalten")
        val open = get("/v1/vehicles/${w.vehicle}/odometer/conflicts?status=OPEN", w.token).json["items"]
        assertEquals(1, open.size()); assertEquals(5000, open[0]["earlier"]["reading_km"].asInt()); assertEquals(4000, open[0]["later"]["reading_km"].asInt()); assertTrue(open[0]["resolution"].isNull)
        val c = open[0]["id"].asString(); val url = "/v1/vehicles/${w.vehicle}/odometer/conflicts/$c/resolution"
        // ohne Grund 422, mit unbekanntem Stand 422, CHOSEN ohne Stand 422; der Konflikt bleibt offen
        assertEquals(422, post(w, url, """{"decision":"CHOSEN","chosen_reading_id":"${open[0]["earlier"]["id"].asString()}"}""").status)
        assertEquals(422, post(w, url, """{"decision":"CHOSEN","chosen_reading_id":"${UUID.randomUUID()}","reason":"anderer Stand"}""").status)
        assertEquals(422, post(w, url, """{"decision":"CHOSEN","reason":"ohne Stand"}""").status)
        assertEquals(1, get("/v1/vehicles/${w.vehicle}/odometer/conflicts?status=OPEN", w.token).json["items"].size())
        // Auflösung: Akteur, Grund und Zeit stehen am Konflikt; zweite Auflösung 409
        val res = post(w, url, """{"decision":"CHOSEN","chosen_reading_id":"${open[0]["earlier"]["id"].asString()}","reason":"Fahrercheck durch Foto belegt"}""")
        assertEquals(200, res.status); assertEquals("RESOLVED", res.json["status"].asString()); assertEquals("Fahrercheck durch Foto belegt", res.json["resolution"]["reason"].asString())
        assertTrue(res.json["resolution"]["resolved_by"].asString().startsWith("user:"))
        assertEquals(409, post(w, url, """{"decision":"DISCARDED","reason":"zweiter Versuch"}""").status)
        assertEquals(1, get("/v1/vehicles/${w.vehicle}/odometer/conflicts?status=RESOLVED", w.token).json["items"].size())
        assertEquals(2, get("/v1/vehicles/${w.vehicle}/odometer", w.token).json["items"].size())
        assertEquals(1, env.events(w.base.base.tenant, "finance.odometer.conflict_resolved"))
    }

    @Test
    fun rechte_authentisierung_und_mandantentrennung() {
        val w = W12C9ContractCases.World(); val url = "/v1/vehicles/${w.vehicle}/odometer"
        val c = w.conflict()
        // ohne Anmeldung 401
        assertEquals(401, env.server.send("GET", url, null, emptyMap()).status)
        // ohne Recht 403: Fahrer haben weder Lese- noch Auflösungsrecht
        val driver = UUID.randomUUID(); env.grant(w.base.base.tenant, driver, "driver"); val driverToken = env.token(driver)
        assertEquals(403, get(url, driverToken).status); assertEquals(403, post(w, "$url/conflicts/$c/resolution", """{"decision":"DISCARDED","reason":"nicht berechtigt"}""", driverToken).status)
        // Disponent darf lesen, aber nicht auflösen und nicht importieren
        val dispatcher = UUID.randomUUID(); env.grant(w.base.base.tenant, dispatcher, "dispatcher"); val dispatcherToken = env.token(dispatcher)
        assertEquals(200, get("$url/conflicts", dispatcherToken).status)
        assertEquals(403, post(w, "$url/conflicts/$c/resolution", """{"decision":"DISCARDED","reason":"nur lesen"}""", dispatcherToken).status)
        assertEquals(403, post(w, url, W12C9ContractCases.reading(1, "x", "2026-06-01T08:00:00Z"), dispatcherToken).status)
        // anderer Mandant: Fahrzeug und Konflikt sind nicht auffindbar
        val other = W12C9ContractCases.World()
        assertEquals(404, get(url, other.token).status); assertEquals(404, get("$url/conflicts", other.token).status)
        assertEquals(404, post(other, "$url/conflicts/$c/resolution", """{"decision":"DISCARDED","reason":"fremder Mandant"}""").status)
        assertEquals(404, post(other, url, W12C9ContractCases.reading(1, "x", "2026-06-01T08:00:00Z")).status)
        // der Importkanal nimmt keine andere Quelle an (unbekannte Felder werden abgewiesen)
        assertEquals(422, post(w, url, """{"reading_km":1,"source_ref":"x","measured_at":"2026-06-01T08:00:00Z","source":"TELEMATICS"}""").status)
    }
}
