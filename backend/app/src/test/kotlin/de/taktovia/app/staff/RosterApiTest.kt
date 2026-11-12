package de.taktovia.app.staff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP-M4-04 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): `/v1/rosters` mit getrenntem Entwurf und veröffentlichtem Plan, ETag, Rechte, Mandantentrennung. */
class RosterApiTest {
    private val w by lazy { ShiftHttpWorld() }

    @Test
    fun entwurf_und_veroeffentlichter_plan_sind_getrennt_lesbar() {
        val r = w.get("/v1/rosters/${w.roster}")
        assertEquals(200, r.status, r.text); assertEquals("\"1\"", r.header("ETag")); assertEquals("2026-11-30", r.json["week_start"].asString())
        assertEquals("DRAFT", r.json["draft"]["state"].asString()); assertTrue(r.json["published"].isNull)
        val shift = w.post(w.body()).json["id"].asString()
        w.publishDraft()
        val published = w.get("/v1/rosters/${w.roster}?view=published")
        assertEquals(200, published.status, published.text); assertTrue(published.json["draft"].isNull); assertEquals(1, published.json["published"]["shift_count"].asInt())
        val etag = w.get("/v1/rosters/${w.roster}").json["revision"].asLong()
        assertEquals(412, w.g.env.server.send("POST", "/v1/rosters/${w.roster}/draft", null, w.g.env.headers(w.plannerToken, extra = w.g.idem() + mapOf("If-Match" to "\"1\""))).status)
        assertEquals(428, w.g.env.server.send("POST", "/v1/rosters/${w.roster}/draft", null, w.g.env.headers(w.plannerToken, extra = w.g.idem())).status)
        val opened = w.g.env.server.send("POST", "/v1/rosters/${w.roster}/draft", null, w.g.env.headers(w.plannerToken, extra = w.g.idem() + mapOf("If-Match" to "\"$etag\"")))
        assertEquals(201, opened.status, opened.text); assertEquals(2, opened.json["draft"]["revision"].asInt()); assertEquals("PUBLISHED", opened.json["published"]["state"].asString())
        val draftShift = w.get("/v1/rosters/shifts?roster_id=${w.roster}").json["items"][0]["id"].asString()
        assertTrue(draftShift != shift)
        assertEquals(200, w.put(draftShift, w.body(need = 5), "1").status)
        // verbindliche Sicht und veröffentlichte Schicht unverändert
        assertEquals(shift, w.get("/v1/rosters/shifts?roster_id=${w.roster}&view=published").json["items"][0]["id"].asString())
        assertEquals(2, w.get("/v1/rosters/shifts/$shift?roster_id=${w.roster}").json["staffing_need"].asInt())
    }

    @Test
    fun rechte_mandanten_und_fehler() {
        assertEquals(403, w.get("/v1/rosters/${w.roster}", w.dispatcherToken).status)
        assertEquals(403, w.get("/v1/rosters", w.dispatcherToken).status)
        assertEquals(200, w.get("/v1/rosters/${w.roster}", w.hrToken).status)
        assertEquals(404, w.get("/v1/rosters/${UUID.randomUUID()}").status)
        assertEquals(404, w.get("/v1/rosters/${w.roster}?view=published").status, "ohne Veröffentlichung gibt es verbindlich nichts")
        assertEquals(422, w.get("/v1/rosters/${w.roster}?view=entwurf").status)
        val other = ShiftHttpWorld()
        assertEquals(404, other.get("/v1/rosters/${w.roster}").status)
        assertFalse(other.get("/v1/rosters").text.contains(w.roster))
        // fremdes Depot beim Anlegen, doppelter Dienstplan, falscher Wochenbeginn
        val create = { body: String, t: String -> w.g.env.server.send("POST", "/v1/rosters", body, w.g.env.headers(t, extra = w.g.idem())) }
        assertEquals(404, create("""{"depot_id":"${other.depot}","week_start":"2026-12-07"}""", w.plannerToken).status)
        assertEquals(409, create("""{"depot_id":"${w.depot}","week_start":"2026-11-30"}""", w.plannerToken).status)
        assertEquals(422, create("""{"depot_id":"${w.depot}","week_start":"2026-12-01"}""", w.plannerToken).status)
        assertEquals(403, create("""{"depot_id":"${w.depot}","week_start":"2026-12-07"}""", w.dispatcherToken).status)
        // Schicht außerhalb der Woche und mit fremdem Depot: 422
        assertEquals(422, w.post(w.body("2026-12-08T06:00:00", "2026-12-08T14:00:00")).status)
        // Liste mit Filter
        val l = w.get("/v1/rosters?depot_id=${w.depot}&from=2026-11-30&to=2026-11-30")
        assertEquals(listOf(w.roster), l.json["items"].values().map { it["id"].asString() })
    }

    @Test
    fun offene_schichten_zaehler_und_konfliktliste_im_lesemodell() {
        val person = UUID.randomUUID()
        val open = w.post(w.body(start = "2026-12-03T06:00:00", end = "2026-12-03T14:00:00")); assertEquals(201, open.status, open.text)
        val a = w.post(w.body(start = "2026-12-01T06:00:00", end = "2026-12-01T14:00:00", extra = ""","driver_ref":"$person""""))
        assertEquals(201, a.status, a.text)
        assertEquals(listOf("ROSTER_QUALIFICATION_MISSING"), a.json["conflicts"].values().map { it["code"].asString() }, "die Person hat keine Qualifikation (Hinweis, Entwurf gespeichert)")
        val b = w.post(w.body(start = "2026-12-01T13:00:00", end = "2026-12-01T20:00:00", extra = ""","driver_ref":"$person""""))
        assertEquals(201, b.status, b.text)
        // Hinweis beim Speichern: beide Schichten genannt, der Entwurf bleibt gespeichert
        val dbl = b.json["conflicts"].values().single { it["code"].asString() == "ROSTER_DOUBLE_BOOKING" }
        assertEquals(setOf(a.json["id"].asString(), b.json["id"].asString()), dbl["shift_ids"].values().map { it.asString() }.toSet())
        val r = w.get("/v1/rosters/${w.roster}")
        assertEquals(3, r.json["conflicts"].size()); assertEquals(1, r.json["draft"]["open_shifts"].asInt()); assertEquals(3, r.json["draft"]["shift_count"].asInt())
        assertEquals(4, r.json["draft"]["unstaffed_need"].asInt(), "Bedarf 2+2+2 minus zwei zugeordnete Personen")
        // Filter „offen“
        assertEquals(listOf(open.json["id"].asString()), w.get("/v1/rosters/shifts?roster_id=${w.roster}&open=true").json["items"].values().map { it["id"].asString() })
        // Lesen einer Schicht prüft nicht: kein conflicts-Feld
        assertFalse(w.get("/v1/rosters/shifts/${a.json["id"].asString()}?roster_id=${w.roster}").json.has("conflicts"))
        // Fahrzeug ohne Person: 422
        assertEquals(422, w.post(w.body(extra = ""","vehicle_id":"${UUID.randomUUID()}"""")).status)
    }
}
