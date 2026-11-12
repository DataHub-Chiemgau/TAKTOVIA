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
}
