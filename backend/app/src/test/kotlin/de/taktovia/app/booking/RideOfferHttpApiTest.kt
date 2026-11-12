package de.taktovia.app.booking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP-M2-11 bis -13 über die echte Anwendung (Runtime-Rolle, echte Datenbank): `POST /v1/ride-offers` mit Rechten, Idempotenz, 200 mit Grund und 422. */
class RideOfferHttpApiTest {
    private val world by lazy { RideOfferHttpWorld() }

    @Test
    fun anfrage_liefert_zeitfenster_und_versuch() {
        val r = world.request(world.body(world.later(3)))
        assertEquals(200, r.status, r.text)
        assertEquals(1, r.json["offers"].size()); assertTrue(r.json["rejection"].isNull)
        val o = r.json["offers"][0]
        assertEquals("ADVANCE", o["booking_kind"].asString()); assertEquals("DEPARTURE", o["time_kind"].asString())
        assertTrue(o["pickup_window"]["latest_at"].asString() > o["pickup_window"]["earliest_at"].asString())
        assertEquals(1, world.w.count("SELECT count(*) FROM taktovia.travel_attempt WHERE id = ?", UUID.fromString(r.json["attempt_id"].asString())))
    }

    @Test
    fun fachliche_ablehnung_ist_200_mit_normiertem_grund() {
        val r = world.request(world.body(world.later(-2)))
        assertEquals(200, r.status, r.text)
        assertEquals(0, r.json["offers"].size()); assertEquals("LEAD_TIME", r.json["rejection"]["code"].asString())
        assertTrue(r.json["rejection"]["next_action"].asString().isNotBlank())
        val horizon = world.request(world.body(world.later(400)))
        assertEquals(200, horizon.status); assertEquals("HORIZON", horizon.json["rejection"]["code"].asString())
    }

    @Test
    fun zulaessigkeit_gebiet_und_rollstuhl_ueber_http() {
        val outside = world.request(world.body(world.later(3)).replace("13.7401", "14.9"))
        assertEquals(200, outside.status, outside.text); assertEquals("OUTSIDE_AREA", outside.json["rejection"]["code"].asString()); assertEquals(0, outside.json["offers"].size())
        // Die Welt hat nur Haltepunkte mit wheelchair_boarding YES: Rollstuhlanfrage ist zulaessig
        val chair = world.request(world.body(world.later(3), extra = ",\"wheelchair_spaces\":1"))
        assertEquals(200, chair.status, chair.text); assertEquals(1, chair.json["offers"].size())
    }

    @Test
    fun formal_fehlerhaft_ist_422_und_idempotenz_wirkt() {
        assertEquals(422, world.request(world.body(world.later(3), passengers = 0)).status)
        val dst = world.request(world.body("2027-03-28T02:30:00+01:00"))
        assertEquals(422, dst.status, dst.text)
        assertEquals(422, world.request(world.body("2026-10-25T02:30:00")).status)
        val key = "retry-" + UUID.randomUUID()
        val a = world.request(world.body(world.later(3)), key = key); val b = world.request(world.body(world.later(3)), key = key)
        assertEquals(200, a.status); assertEquals(a.json, b.json); assertEquals("true", b.header("Idempotent-Replayed"))
        val c = world.request(world.body(world.later(3), passengers = 3), key = key)
        assertEquals(409, c.status); assertEquals("IDEMPOTENCY_CONFLICT", c.json["code"].asString())
    }

    @Test
    fun fremder_dienst_404_und_ohne_anmeldung_401() {
        assertEquals(404, world.request(world.body(world.later(3), serviceId = UUID.randomUUID().toString())).status)
        val other = RideOfferHttpWorld()
        assertEquals(404, other.request(world.body(world.later(3))).status, "Dienst eines anderen Mandanten ist nicht sichtbar")
        val anon = world.w.env.server.send("POST", "/v1/ride-offers", world.body(world.later(3)), mapOf("Idempotency-Key" to "anon-" + UUID.randomUUID()))
        assertEquals(401, anon.status)
    }
}
