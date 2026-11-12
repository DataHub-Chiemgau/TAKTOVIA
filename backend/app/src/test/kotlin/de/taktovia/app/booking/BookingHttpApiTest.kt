package de.taktovia.app.booking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M2-23 über die echte Anwendung (Runtime-Rolle, echte Datenbank): `POST /v1/bookings` mit `If-Match` und `Idempotency-Key`, `GET /v1/bookings/{id}`, `GET /v1/bookings` mit Cursor,
 * Objektberechtigung (404), Fehlercodes (428, 412, 409) und der Betreiberpayload ohne Kontaktdaten.
 */
class BookingHttpApiTest {
    private val s = BookingHttpSupport()

    @Test
    fun confirm_liefert_buchung_mit_etag_location_und_nummer_und_get_liest_sie() {
        val h = s.held()
        val r = s.confirm(h)
        assertEquals(201, r.status, r.text)
        val b = r.json; val id = b["id"].asString()
        assertEquals("/v1/bookings/$id", r.header("Location")); assertEquals("\"1\"", r.header("ETag"))
        assertTrue(Regex("BK-[0-9]{4}-[0-9]{6}").matches(b["booking_number"].asString())); assertEquals("CONFIRMING", b["state"].asString()); assertEquals(h.holdId, b["hold_id"].asString())
        assertEquals(h.grossMinor, b["promise"]["gross"]["amount_minor"].asLong()); assertEquals(2, b["promise"]["resource_vector"]["seats"].asInt()); assertEquals(2, b["passengers"].asInt())
        // GET: genau diese Buchung, gleiche Nummer, ETag = Revision
        val g = s.get(id, h.account.token)
        assertEquals(200, g.status, g.text); assertEquals(b, g.json); assertEquals("\"1\"", g.header("ETag"))
        // Wiederholung mit anderem Schlüssel findet dieselbe Buchung (fachlich), gleicher Schlüssel liefert das gespeicherte Ergebnis
        val again = s.confirm(h, key = "other-" + UUID.randomUUID())
        assertEquals(201, again.status); assertEquals(id, again.json["id"].asString())
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ?", s.tenant.value))
        // Liste: eigene Buchungen, ohne Cursor eine Seite
        val l = s.list(h.account.token, "?limit=1")
        assertEquals(200, l.status, l.text); assertEquals(listOf(id), l.json["items"].values().asSequence().map { it["id"].asString() }.toList()); assertTrue(l.json["next_cursor"].isNull)
    }

    @Test
    fun if_match_betrag_und_fremde_ids() {
        val h = s.held()
        assertEquals(428, s.confirm(h, ifMatch = null).status)
        assertEquals(412, s.confirm(h, ifMatch = "9").status)
        for (gross in listOf(h.grossMinor - 1, h.grossMinor + 1)) s.confirm(h, gross = gross).let { assertEquals(409, it.status, it.text); assertEquals("REQUOTE_REQUIRED", it.json["code"].asString()) }
        assertEquals(0, s.count("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ?", s.tenant.value))
        // fremder Hold und unbekannter Hold: 404
        val other = s.newAccount()
        assertEquals(404, s.confirm(h, token = other.token).status)
        assertEquals(404, s.confirm(h, holdId = UUID.randomUUID().toString()).status)
        // ohne Anmeldung 401, mit Schlüssel aber ohne Pflichtheader Idempotency-Key 422
        assertEquals(401, s.env.server.send("POST", "/v1/bookings", "{}", emptyMap()).status)
        // erfolgreich mit richtigen Angaben, danach fremde Buchung 404 für anderen Fahrgast
        val ok = s.confirm(h); assertEquals(201, ok.status, ok.text)
        assertEquals(404, s.get(ok.json["id"].asString(), other.token).status)
        assertEquals(0, s.list(other.token).json["items"].size())
    }

    @Test
    fun betriebspersonal_liest_nur_buchungen_seines_dienstes_ohne_kontaktdaten() {
        val h = s.held(); val b = s.confirm(h).json; val id = b["id"].asString()
        val inScope = s.operator(listOf(UUID.fromString(s.world.service)))
        val otherScope = s.operator(listOf(UUID.randomUUID()))
        val noScope = s.operator(emptyList())
        val g = s.get(id, inScope)
        assertEquals(200, g.status, g.text)
        // Minimierung: Fahrgastkennung, Konto und Kontakt sind nie Teil der Sicht
        val keys = g.json.propertyNames().toSet()
        assertFalse(keys.any { it.contains("passenger_id") || it.contains("contact") || it.contains("email") || it.contains("phone") || it.contains("created_by") }, keys.toString())
        assertEquals(404, s.get(id, otherScope).status); assertEquals(404, s.get(id, noScope).status)
        assertEquals(1, s.list(inScope).json["items"].size()); assertEquals(0, s.list(otherScope).json["items"].size()); assertEquals(0, s.list(noScope).json["items"].size())
        // anderer Mandant: 404
        val foreign = BookingHttpSupport(); val foreignToken = foreign.newAccount().token
        assertEquals(404, foreign.get(id, foreignToken).status)
        assertNotNull(g.header("ETag"))
    }

    @Test
    fun historie_fahrgast_reduziert_betrieb_pruefersicht_und_403_404() {
        val h = s.held(); val id = s.confirm(h).json["id"].asString()
        // Fahrgast: reduzierte Sicht der eigenen Buchung, ohne Akteur-IDs und Verknüpfungen
        val own = s.env.get("/v1/bookings/$id/history", h.account.token)
        assertEquals(200, own.status, own.text); assertEquals("reduced", own.json["view"].asString())
        val e = own.json["items"][0]
        assertEquals(1, e["revision"].asInt()); assertTrue(e["from_state"].isNull); assertEquals("CONFIRMING", e["to_state"].asString()); assertEquals("HOLD_CONFIRMED", e["reason"].asString())
        assertEquals("PASSENGER", e["actor_kind"].asString()); assertTrue(e["reason_text"].asString().isNotBlank())
        assertFalse(e.has("actor_ref")); assertFalse(e.has("links")); assertFalse(e.has("correlation_id"))
        // Prüfersicht für den Fahrgast: 403; unbekannte Sicht: 422
        assertEquals(403, s.env.get("/v1/bookings/$id/history?view=audit", h.account.token).status)
        assertEquals(422, s.env.get("/v1/bookings/$id/history?view=alles", h.account.token).status)
        // Betriebspersonal im Dienstscope: Prüfersicht mit Verknüpfungen; fremder Dienst und fremder Fahrgast: 404
        val op = s.operator(listOf(java.util.UUID.fromString(s.world.service)))
        val audit = s.env.get("/v1/bookings/$id/history", op)
        assertEquals(200, audit.status, audit.text); assertEquals("audit", audit.json["view"].asString())
        val a = audit.json["items"][0]
        assertEquals(h.holdId, a["links"]["hold_id"].asString()); assertEquals(h.offerId, a["links"]["offer_id"].asString()); assertTrue(a.has("actor_ref") && a.has("correlation_id"))
        assertEquals(404, s.env.get("/v1/bookings/$id/history", s.operator(listOf(java.util.UUID.randomUUID()))).status)
        assertEquals(404, s.env.get("/v1/bookings/$id/history", s.newAccount().token).status)
        assertEquals(404, s.env.get("/v1/bookings/$id/history", BookingHttpSupport().newAccount().token).status)
    }

    @Test
    fun storno_if_match_grund_frist_und_betreiberstorno() {
        val h = s.held(); val id = s.confirm(h).json["id"].asString()
        // 428 ohne If-Match, 412 veraltet, 422 unbekannter Grund (Schema), 403 Fahrgast mit Betreibergrund
        assertEquals(428, s.cancel(id, h.account.token, ifMatch = null).status)
        assertEquals(412, s.cancel(id, h.account.token, ifMatch = "7").status)
        assertEquals(422, s.cancel(id, h.account.token, reason = "LANGEWEILE", ifMatch = "1").status)
        val forbidden = s.cancel(id, h.account.token, reason = "VEHICLE_OUTAGE", ifMatch = "1")
        assertEquals(403, forbidden.status, forbidden.text)
        assertEquals("CONFIRMING", s.get(id, h.account.token).json["state"].asString())
        // fremder Fahrgast und Betrieb ohne Scope: 404
        assertEquals(404, s.cancel(id, s.newAccount().token, ifMatch = "1").status)
        assertEquals(404, s.cancel(id, s.operator(emptyList()), reason = "OPERATOR_CANCELLATION", ifMatch = "1").status)
        // Storno durch den Fahrgast (Abholung in 3 Tagen, Frist 60 min): Buchung storniert, Revision 3, ETag
        val ok = s.cancel(id, h.account.token, ifMatch = "1")
        assertEquals(200, ok.status, ok.text); assertEquals("CANCELED", ok.json["state"].asString()); assertEquals("\"3\"", ok.header("ETag")); assertEquals(3, ok.json["revision"].asInt())
        assertEquals("PASSENGER_REQUEST", ok.json["cancel_reason"].asString()); assertEquals("FAHRGAST", ok.json["cancel_cause_class"].asString())
        assertEquals("FAHRGAST", s.get(id, h.account.token).json["cancel_cause_class"].asString())
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.capacity_hold WHERE id = ?::uuid AND state = 'RELEASED'", h.holdId))
        val hist = s.env.get("/v1/bookings/$id/history", h.account.token).json["items"].values().asSequence().map { it["to_state"].asString() }.toList()
        assertEquals(listOf("CONFIRMING", "COMPENSATING", "CANCELED"), hist)
        // zweites Storno mit neuem Schlüssel: 409
        val again = s.cancel(id, h.account.token, ifMatch = "3")
        assertEquals(409, again.status); assertEquals("INVALID_STATE_TRANSITION", again.json["code"].asString())
        // Betreiberstorno im Dienstscope mit Grund Fahrzeugausfall
        val h2 = s.held(); val id2 = s.confirm(h2).json["id"].asString()
        val op = s.operator(listOf(java.util.UUID.fromString(s.world.service)))
        val outage = s.cancel(id2, op, reason = "VEHICLE_OUTAGE", ifMatch = "1")
        assertEquals(200, outage.status, outage.text); assertEquals("CANCELED", outage.json["state"].asString())
        assertEquals("VEHICLE_OUTAGE", outage.json["cancel_reason"].asString()); assertEquals("FAHRZEUGAUSFALL", outage.json["cancel_cause_class"].asString())
    }
}
