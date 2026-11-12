package de.taktovia.app.staff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M4-14 / WP-M4-15 / WP-M4-20 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): Veröffentlichung mit `If-Match` und `Idempotency-Key`, Konfliktantwort, Fahrer-Eigensicht,
 * Revisionen und Historie (Auditrecht), eigene Abwesenheit.
 */
class RosterPublishApiTest {
    private class World {
        val w = ShiftHttpWorld()
        /** Fahrer mit Konto (Mitgliedschaft, Token) und Eintrag im Fahrerstamm. */
        inner class Driver(val subject: UUID, val token: String, val id: UUID)
        fun driver(): Driver {
            val subject = UUID.randomUUID(); val membership = w.g.env.grant(w.g.tenant, subject, "driver"); val id = UUID.randomUUID()
            w.g.env.exec("INSERT INTO taktovia.driver(tenant_id,id,membership_id,display_name,contact_channel,contact_value,status) VALUES (?,?,?,'Fahrer','APP','x','ACTIVE')", w.g.tenant.value, id, membership)
            w.g.env.exec("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,valid_to) VALUES (?,?,?,'PERSONENBEFOERDERUNG',1,CAST('2020-01-01T00:00:00Z' AS timestamptz),NULL)", w.g.tenant.value, UUID.randomUUID(), id)
            return Driver(subject, w.g.env.token(subject), id)
        }
        fun shift(day: String, start: String, end: String, d: Driver, endDay: String = day) =
            w.post(w.body(start = "${day}T$start:00", end = "${endDay}T$end:00", extra = ""","driver_ref":"${d.id}"""", need = 1)).also { check(it.status == 201) { it.text } }.json["id"].asString()
        fun etag() = w.get("/v1/rosters/${w.roster}", w.hrToken).json["revision"].asLong()
        fun publish(token: String = w.hrToken, ifMatch: String? = "\"${etag()}\"", key: String? = "k-publish-" + UUID.randomUUID(), reason: String = "Freigabe Woche 49", impact: String = UUID.randomUUID().toString()) =
            w.g.env.server.send("POST", "/v1/rosters/${w.roster}/publish", """{"effective_from":"2026-12-01T00:00:00Z","reason":"$reason","impact_report_id":"$impact"}""",
                w.g.env.headers(token, extra = (if (key != null) mapOf("Idempotency-Key" to key) else emptyMap()) + (if (ifMatch != null) mapOf("If-Match" to ifMatch) else emptyMap())))
        fun own(d: Driver) = w.g.env.server.send("GET", "/v1/rosters/own-shifts?from=2026-11-30&to=2026-12-06", null, w.g.env.headers(d.token))
    }

    @Test
    fun veroeffentlichung_mit_if_match_idempotenz_und_fahrer_eigensicht() {
        val x = World(); val d = x.driver(); val other = x.driver()
        val s1 = x.shift("2026-12-01", "06:00", "12:00", d)
        assertEquals(0, x.own(d).json["items"].size(), "Entwurf ist für den Fahrer unsichtbar")
        // Fehler: ohne If-Match 428, veralteter Stand 412, ohne Scope 403 (Planerrolle hat kein rosters:publish), unbekannter Dienstplan 404
        assertEquals(428, x.publish(ifMatch = null).status)
        assertEquals(412, x.publish(ifMatch = "\"999\"").status)
        assertEquals(403, x.publish(token = x.w.plannerToken).status)
        assertEquals(0, x.own(d).json["items"].size(), "kein Fehlversuch hat etwas veröffentlicht")
        // Erfolg: PublishedRevision mit ETag; Wiederholung mit demselben Schlüssel liefert dieselbe Revision
        val etag = x.etag(); val impact = UUID.randomUUID().toString(); val fixedKey = "k-publish-" + UUID.randomUUID()
        val ok = x.publish(ifMatch = "\"$etag\"", key = fixedKey, impact = impact)
        assertEquals(200, ok.status, ok.text); assertEquals("PUBLISHED", ok.json["state"].asString()); assertEquals(x.w.roster, ok.json["resource_id"].asString()); assertTrue(ok.header("ETag") != null)
        val again = x.publish(ifMatch = "\"$etag\"", key = fixedKey, impact = impact)
        assertEquals(200, again.status, again.text); assertEquals(ok.json["audit_event_id"].asString(), again.json["audit_event_id"].asString(), "dieselbe Revision, kein zweites Audit")
        assertEquals(409, x.publish(ifMatch = "\"$etag\"", key = fixedKey, impact = UUID.randomUUID().toString()).status, "gleicher Schlüssel, anderer Inhalt")
        // Fahrer: genau die veröffentlichte Revision; anderer Fahrer: nichts
        val seen = x.own(d); assertEquals(200, seen.status, seen.text)
        assertEquals(1, seen.json["items"].size()); assertEquals(s1, seen.json["items"][0]["shift_id"].asString()); assertEquals(1, seen.json["items"][0]["roster_revision"].asInt())
        assertEquals(0, x.own(other).json["items"].size(), "nur eigene Schichten")
        // Revisionen und Historie: Auditrecht nötig (Planerrolle: 403), Personalrolle liest
        assertEquals(403, x.w.get("/v1/rosters/${x.w.roster}/revisions", x.w.plannerToken).status)
        val revs = x.w.get("/v1/rosters/${x.w.roster}/revisions", x.w.hrToken)
        assertEquals(200, revs.status, revs.text); assertEquals("PUBLISHED", revs.json["items"][0]["state"].asString())
        assertEquals(1, x.w.get("/v1/rosters/${x.w.roster}/revisions/1", x.w.hrToken).json["shifts"].size())
        assertEquals(404, x.w.get("/v1/rosters/${x.w.roster}/revisions/2", x.w.hrToken).status)
        val histResp = x.w.get("/v1/rosters/${x.w.roster}/shifts/$s1/history", x.w.hrToken)
        assertEquals(200, histResp.status, histResp.text)
        val hist = histResp.json["items"]
        val entries = (0 until hist.size()).map { hist[it] }
        assertEquals(listOf("CREATED", "PUBLISHED"), entries.map { it["kind"].asString() }); assertTrue(entries.all { it["actor"].asString().startsWith("user:") && it["occurred_at"].asString().isNotEmpty() })
        // fremder Mandant (mit denselben Rechten) sieht nichts und kann nichts veröffentlichen
        val foreign = ShiftHttpWorld()
        assertEquals(404, foreign.get("/v1/rosters/${x.w.roster}/revisions", foreign.hrToken).status)
        assertEquals(404, foreign.get("/v1/rosters/${x.w.roster}/revisions/1", foreign.hrToken).status)
        assertEquals(404, foreign.get("/v1/rosters/${x.w.roster}/shifts/$s1/history", foreign.hrToken).status)
        val foreignPublish = foreign.g.env.server.send("POST", "/v1/rosters/${x.w.roster}/publish", """{"effective_from":"2026-12-01T00:00:00Z","reason":"Freigabe","impact_report_id":"${UUID.randomUUID()}"}""",
            foreign.g.env.headers(foreign.hrToken, extra = foreign.g.idem() + mapOf("If-Match" to "\"1\"")))
        assertEquals(404, foreignPublish.status, foreignPublish.text)
    }

    @Test
    fun eigene_zeiten_fremde_person_ergibt_404() {
        val x = World(); val d = x.driver(); val other = x.driver()
        val t = x.w.g.env.server
        // Eigensicht: die Person ist immer der Akteur; fremde Personen-ID in der URL (Zeitereignisse, Soll/Ist, Intervalle): 404, nie Daten
        assertEquals(404, t.send("GET", "/v1/time-events?person_ref=${other.subject}", null, x.w.g.env.headers(d.token)).status)
        assertEquals(404, t.send("GET", "/v1/time-events/target-actual?person_ref=${other.subject}&from=2026-12-01&to=2026-12-02", null, x.w.g.env.headers(d.token)).status)
        assertEquals(404, t.send("GET", "/v1/time-events/intervals?person_ref=${other.subject}&from=2026-12-01T00:00:00Z&to=2026-12-02T00:00:00Z", null, x.w.g.env.headers(d.token)).status)
        assertEquals(200, t.send("GET", "/v1/time-events/target-actual?person_ref=${d.subject}&from=2026-12-01&to=2026-12-02", null, x.w.g.env.headers(d.token)).status, "die eigene Person bleibt lesbar")
        // Soll/Ist enthält die Regelhinweise im Feld `hints` (leer ohne Auffälligkeit)
        assertEquals(0, t.send("GET", "/v1/time-events/target-actual?from=2026-12-01&to=2026-12-01", null, x.w.g.env.headers(d.token)).json["days"][0]["hints"].size())
    }

    @Test
    fun konflikt_ergibt_409_mit_konfliktliste_und_hinweise_sperren_nicht() {
        val x = World(); val d = x.driver()
        x.shift("2026-12-01", "06:00", "12:00", d); x.shift("2026-12-01", "08:00", "14:00", d)
        val e = x.publish()
        assertEquals(409, e.status, e.text); assertEquals("CONFLICT", e.json["code"].asString())
        assertTrue(e.json["violations"].any { it["code"].asString() == "ROSTER_DOUBLE_BOOKING" }, "Konfliktliste")
        assertEquals(0, x.own(d).json["items"].size())
        assertEquals("DRAFT", x.w.get("/v1/rosters/${x.w.roster}", x.w.hrToken).json["draft"]["state"].asString(), "es entsteht keine Revision")
        // Regelhinweise (Ruhezeit 1 min unter der Grenze) sind im Lesemodell sichtbar und sperren die Publikation nicht
        val y = World(); val d2 = y.driver()
        y.shift("2026-12-01", "06:00", "12:00", d2); y.shift("2026-12-01", "22:59", "23:59", d2)
        val roster = y.w.get("/v1/rosters/${y.w.roster}", y.w.hrToken).json
        assertEquals("REST_PERIOD_SHORT", roster["hints"][0]["rule"].asString()); assertEquals(660, roster["hints"][0]["expected"].asInt()); assertEquals(659, roster["hints"][0]["actual"].asInt())
        assertEquals(200, y.publish().status)
        assertEquals(1, y.w.get("/v1/rosters/${y.w.roster}", y.w.hrToken).json["hints"].size(), "Hinweise zur veröffentlichten Revision bleiben für Planer lesbar")
        assertTrue(y.w.get("/v1/rosters/${y.w.roster}?view=published", y.w.hrToken).json["hints"].isNull, "die verbindliche Sicht trägt keine Hinweise")
    }

    @Test
    fun eigene_abwesenheit_nur_eigene_und_neutral() {
        val x = World(); val d = x.driver(); val other = x.driver()
        val created = x.w.g.env.server.send("POST", "/v1/absences", """{"person_ref":"${d.subject}","from":"2026-12-03T00:00:00Z","to":"2026-12-05T00:00:00Z","kind":"LEAVE"}""", x.w.g.env.headers(x.w.hrToken, extra = x.w.g.idem()))
        assertEquals(201, created.status, created.text)
        val mine = x.w.g.env.server.send("GET", "/v1/absences/own", null, x.w.g.env.headers(d.token))
        assertEquals(200, mine.status, mine.text); assertEquals(1, mine.json["items"].size()); assertEquals(d.subject.toString(), mine.json["items"][0]["person_ref"].asString())
        assertFalse(mine.text.contains("LEAVE") || mine.text.contains("\"kind\""), "nie die Art der Abwesenheit")
        assertEquals(0, x.w.g.env.server.send("GET", "/v1/absences/own", null, x.w.g.env.headers(other.token)).json["items"].size(), "fremde Abwesenheit nie sichtbar")
        // eine fremde Person als Parameter gibt es in der Eigensicht nicht (wird ignoriert)
        assertTrue(x.w.g.env.server.send("GET", "/v1/absences/own?person_ref=${d.subject}", null, x.w.g.env.headers(other.token)).status in setOf(400, 422), "der Vertrag kennt keinen Personenparameter")
    }
}
