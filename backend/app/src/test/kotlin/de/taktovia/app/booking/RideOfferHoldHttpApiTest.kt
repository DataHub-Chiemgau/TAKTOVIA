package de.taktovia.app.booking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M2-09/-21/-22 über die echte Anwendung (Runtime-Rolle, echte Datenbank): Angebot mit Preisblock, Ablauf und ETag, `GET`, Hold mit `If-Match` und `Idempotency-Key`, Freigabe, Fehlercodes
 * (404, 412, 428, 409 `OFFER_EXPIRED`/`REQUOTE_REQUIRED`/`CAPACITY_UNAVAILABLE`/`IDEMPOTENCY_CONFLICT`). Preis: 2 Personen x 450, 7 % Steuer enthalten = 900.
 */
class RideOfferHoldHttpApiTest {
    private val world = RideOfferHttpWorld()
    private val s = OfferHttpSupport(world)

    @Test
    fun angebot_traegt_id_ablauf_preisblock_und_get_liefert_denselben_stand_mit_etag() {
        val i = s.issue(); val o = i.offer
        // jedes Angebot enthält ID, Ablauf, Endpreis mit Steuer-/Gebührenbestandteilen, Haltepunkte und Dienstkennung
        assertNotNull(UUID.fromString(o["id"].asString())); assertEquals(world.service, o["service_id"].asString()); assertEquals(1, o["revision"].asInt())
        assertEquals(900, o["gross"]["amount_minor"].asLong()); assertEquals("EUR", o["gross"]["currency"].asString()); assertEquals(2, o["gross"]["exponent"].asInt())
        val comps = o["fare_components"].values().asSequence().toList()
        assertEquals(setOf("TRANSPORT", "TAX"), comps.map { it["code"].asString() }.toSet())
        assertEquals(900, comps.sumOf { it["amount"]["amount_minor"].asLong() }, "Summe der Komponenten = Endpreis")
        assertTrue(comps.all { it["description"].asString().isNotBlank() && it["tariff_revision"].asString() == "1" })
        assertTrue(java.time.Instant.parse(o["expires_at"].asString()).isAfter(java.time.Instant.now().plusSeconds(200)), "Gültigkeit 300 s ab Ausstellung")
        assertNotNull(o["pickup_stop"]["stop_id"]); assertNotNull(o["pickup_stop"]["revision_id"])
        // GET: genau der angebotene Stand, ETag = Angebotsrevision, unverändert bei jedem Abruf
        val g1 = s.get(i); val g2 = s.get(i)
        assertEquals(200, g1.status, g1.text); assertEquals("\"1\"", g1.header("ETag")); assertEquals(g1.json, g2.json); assertEquals("\"1\"", g2.header("ETag"))
        assertEquals(o["id"].asString(), g1.json["id"].asString()); assertEquals(900, g1.json["gross"]["amount_minor"].asLong()); assertEquals("OFFERED", g1.json["state"].asString())
        assertTrue(g1.json["hold"].isNull); assertEquals(o["expires_at"].asString(), g1.json["expires_at"].asString())
        assertEquals(1, g1.json["demand"]["seats"].asInt() / 2, "2 Personen = 2 Sitze")
        // Wiederholung der Anfrage (gleicher Schlüssel) liefert dasselbe Angebot
        val key = "offer-retry-" + UUID.randomUUID(); val token = s.newPassenger()
        val a = world.request(world.body(world.later(4)), token, key); val b = world.request(world.body(world.later(4)), token, key)
        assertEquals(a.json, b.json); assertEquals("true", b.header("Idempotent-Replayed"))
    }

    @Test
    fun fremdes_und_unbekanntes_angebot_ist_404_und_ohne_anmeldung_401() {
        val i = s.issue()
        val other = s.newPassenger()                                             // anderer Fahrgast desselben Mandanten
        assertEquals(404, s.get(i, other).status)
        assertEquals(404, s.hold(i.id, other, "1").status); assertEquals(404, s.release(i.id, other, "1").status)
        assertEquals(404, world.w.env.get("/v1/ride-offers/${UUID.randomUUID()}", i.token).status)
        val foreign = RideOfferHttpWorld(); val foreignToken = OfferHttpSupport(foreign).newPassenger()   // anderer Mandant
        assertEquals(404, foreign.w.env.get("/v1/ride-offers/${i.id}", foreignToken).status)
        assertEquals(401, world.w.env.server.send("GET", "/v1/ride-offers/${i.id}", null, emptyMap()).status)
    }

    @Test
    fun hold_if_match_idempotenz_freigabe_und_fehlercodes() {
        val i = s.issue(); s.bind(i)
        // 428 ohne If-Match, 412 veraltet, 409 REQUOTE_REQUIRED bei abweichendem Betrag (höher wie niedriger), keine Kapazität gehalten
        assertEquals(428, s.hold(i.id, i.token, null).status)
        assertEquals(412, s.hold(i.id, i.token, "7").status)
        for (gross in listOf(899L, 901L)) s.hold(i.id, i.token, "1", gross).let { assertEquals(409, it.status, it.text); assertEquals("REQUOTE_REQUIRED", it.json["code"].asString()) }
        assertEquals(422, s.hold(i.id, i.token, "1", currency = "eur").status, "Währung nach Vertrag")
        assertEquals(0, world.w.count("SELECT count(*) FROM taktovia.capacity_hold WHERE tenant_id = ?", s.tenant.value))
        // Hold: 201 mit ETag der neuen Revision, Hold-ID und Ablauf (180 s)
        val key = "hold-key-" + UUID.randomUUID()
        val h = s.hold(i.id, i.token, "1", key = key)
        assertEquals(201, h.status, h.text); assertEquals("\"2\"", h.header("ETag")); assertEquals("/v1/ride-offers/${i.id}", h.header("Location"))
        assertEquals("HELD", h.json["state"].asString()); assertEquals(2, h.json["capacity_vector"]["seats"].asInt())
        assertTrue(java.time.Instant.parse(h.json["expires_at"].asString()).isBefore(java.time.Instant.now().plusSeconds(181)))
        // Wiederholung mit gleichem Schlüssel: dasselbe Ergebnis; anderer Inhalt: IDEMPOTENCY_CONFLICT; anderer Schlüssel auf ein bereits gehaltenes Angebot: kein zweiter Hold
        val again = s.hold(i.id, i.token, "1", key = key)
        assertEquals(201, again.status); assertEquals(h.json, again.json); assertEquals("true", again.header("Idempotent-Replayed"))
        val conflict = s.hold(i.id, i.token, "1", gross = 901, key = key)
        assertEquals(409, conflict.status); assertEquals("IDEMPOTENCY_CONFLICT", conflict.json["code"].asString())
        assertEquals(412, s.hold(i.id, i.token, "1").status, "bereits gehalten: Angebotsrevision ist weitergezählt")
        assertEquals(1, world.w.count("SELECT count(*) FROM taktovia.capacity_hold WHERE tenant_id = ?", s.tenant.value))
        val got = s.get(i); assertEquals("\"2\"", got.header("ETag")); assertEquals("HELD", got.json["state"].asString()); assertEquals(h.json["id"].asString(), got.json["hold"]["id"].asString())
        // Freigabe: If-Match Pflicht, nur der Urheber, idempotent
        assertEquals(428, s.release(i.id, i.token, null).status)
        assertEquals(404, s.release(i.id, s.newPassenger(), "2").status)
        val rel = s.release(i.id, i.token, "2")
        assertEquals(200, rel.status, rel.text); assertEquals("RELEASED", rel.json["state"].asString()); assertEquals("\"3\"", rel.header("ETag"))
        val rel2 = s.release(i.id, i.token, "2")
        assertEquals(200, rel2.status); assertEquals("RELEASED", rel2.json["state"].asString())
        assertEquals("DECLINED", s.get(i).json["state"].asString())
    }

    @Test
    fun abgelaufenes_angebot_409_und_ohne_planbindung_keine_kapazitaet() {
        val i = s.issue(); s.bind(i); s.expire(i)
        val r = s.hold(i.id, i.token, "1")
        assertEquals(409, r.status, r.text); assertEquals("OFFER_EXPIRED", r.json["code"].asString())
        assertEquals("EXPIRED", s.get(i).json["state"].asString())
        assertEquals(0, world.w.count("SELECT count(*) FROM taktovia.capacity_hold WHERE tenant_id = ?", s.tenant.value))
        // Angebot ohne Bindung an eine Planrevision (Verdrahtung ohne Einfügeheuristik): 409 CAPACITY_UNAVAILABLE, nie ein Kapazitätsversprechen
        val unbound = s.issue(); val u = s.hold(unbound.id, unbound.token, "1")
        assertEquals(409, u.status, u.text); assertEquals("CAPACITY_UNAVAILABLE", u.json["code"].asString())
    }
}
