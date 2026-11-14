package de.taktovia.app.operator

import de.taktovia.app.booking.BookingHttpSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M3-37 / LH-6.7-OPS-002 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): `GET /v1/operator/bookings` mit den fünf Suchfeldern, Schnittmenge, Dienstscope, Cursor und Fehlerfällen.
 * Das Zusagefenster der Testbuchungen liegt rund drei Tage nach der Laufzeit des Tests (siehe [de.taktovia.app.booking.OfferHttpSupport]); der Zeitraumfilter wird deshalb aus der Buchung selbst abgeleitet.
 */
class OperatorBookingSearchApiTest {
    private val s = BookingHttpSupport()

    private fun text(sql: String, id: String): String = s.env.string(sql, UUID.fromString(id)).single()

    private fun ids(token: String, query: String = ""): List<String> = s.env.get("/v1/operator/bookings$query", token).also { check(it.status == 200) { it.text } }.json["items"].values().map { it["id"].asString() }

    @Test
    fun fuenf_suchfelder_schnittmenge_und_leerzustand() {
        val h1 = s.held(); val h2 = s.held()
        val b1 = s.confirm(h1).json; val b2 = s.confirm(h2).json
        val id1 = b1["id"].asString(); val id2 = b2["id"].asString(); val service = b1["service_id"].asString()
        val op = s.operator(listOf(UUID.fromString(service)))
        val number1 = b1["booking_number"].asString()
        // Buchungsnummer
        assertEquals(listOf(id1), ids(op, "?booking_number=$number1"))
        // Status (beide CONFIRMING), Fahrzeug über den Umlauf des Angebots
        assertEquals(setOf(id1, id2), ids(op, "?state=CONFIRMING").toSet())
        val vehicle1 = text("SELECT r.vehicle_id::text FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.id = b.hold_id JOIN taktovia.vehicle_run r ON r.id = h.run_id WHERE b.id = ?", id1)
        assertTrue(id1 in ids(op, "?vehicle_id=$vehicle1"))
        // Zeitraum: das Zusagefenster der Buchung liegt im Suchraum bzw. davor
        val from = b1["promise"]["pickup_earliest"].asString()
        assertTrue(id1 in ids(op, "?from=$from"))
        assertEquals(emptyList<String>(), ids(op, "?to=2020-01-01T00:00:00Z"))
        // Fahrgast: exakter Verweis, nur mit Recht auf Fahrgastdaten (Disposition ja)
        val ref = text("SELECT passenger_id::text FROM taktovia.booking WHERE id = ?", id1)
        assertEquals(listOf(id1), ids(op, "?passenger=$ref"))
        // Schnittmenge: Nummer von 1 mit Fahrgast von 2 ergibt nichts, mit Fahrgast von 1 genau 1
        val ref2 = text("SELECT passenger_id::text FROM taktovia.booking WHERE id = ?", id2)
        assertEquals(emptyList<String>(), ids(op, "?booking_number=$number1&passenger=$ref2"))
        assertEquals(listOf(id1), ids(op, "?booking_number=$number1&passenger=$ref&state=CONFIRMING"))
        // Leerzustand: 200, leere Liste, as_of, kein Fehler
        val leer = s.env.get("/v1/operator/bookings?booking_number=BK-1999-000001", op)
        assertEquals(200, leer.status, leer.text); assertEquals(0, leer.json["items"].size()); assertTrue(leer.json["next_cursor"].isNull); assertTrue(leer.json["as_of"].asString().endsWith("Z"))
        // Zeile: nur Disponierendes, Fahrgastverweis für diese Rolle vorhanden, Kennzeichen als (leere) Liste
        val row = s.env.get("/v1/operator/bookings?booking_number=$number1", op).json["items"][0]
        assertEquals(number1, row["booking_number"].asString()); assertEquals(ref, row["passenger_ref"].asString()); assertEquals(0, row["flags"].size())
        assertFalse(row.has("contact") || row.has("name") || row.has("email"))
    }

    @Test
    fun scope_rolle_fremder_mandant_cursor_und_limit() {
        val h1 = s.held(); val h2 = s.held(); val h3 = s.held()
        val bookings = listOf(h1, h2, h3).map { s.confirm(it).json }
        val service = UUID.fromString(bookings[0]["service_id"].asString())
        val op = s.operator(listOf(service))
        // kein Dienstscope: leere Liste; fremder Dienst im Filter: leer; Fahrgast: 403
        assertEquals(emptyList<String>(), ids(s.operator(emptyList())))
        assertEquals(emptyList<String>(), ids(op, "?service_id=${UUID.randomUUID()}"))
        assertEquals(403, s.env.get("/v1/operator/bookings", h1.account.token).status)
        // Rolle ohne Recht auf Fahrgastdaten: Fahrgastsuche 403, andere Felder gehen
        val planner = s.operator(listOf(service), "planner")
        val ref = text("SELECT passenger_id::text FROM taktovia.booking WHERE id = ?", bookings[0]["id"].asString())
        assertEquals(403, s.env.get("/v1/operator/bookings?passenger=$ref", planner).status)
        assertEquals(3, ids(planner).size)
        assertFalse(s.env.get("/v1/operator/bookings", planner).json["items"][0].has("passenger_ref"), "ohne Recht auf Fahrgastdaten kein Verweis")
        // anderer Mandant: nichts
        val foreign = BookingHttpSupport()
        assertEquals(emptyList<String>(), ids(foreign.operator(listOf(service))))
        // Cursor: zwei Seiten à 2 sehen jede Buchung genau einmal; manipulierter Cursor 400; limit 101 ist 422
        val p1 = s.env.get("/v1/operator/bookings?limit=2", op); assertEquals(2, p1.json["items"].size()); val cur = p1.json["next_cursor"].asString()
        val p2 = s.env.get("/v1/operator/bookings?limit=2&cursor=$cur", op); assertEquals(1, p2.json["items"].size()); assertTrue(p2.json["next_cursor"].isNull)
        assertEquals(bookings.map { it["id"].asString() }.toSet(), (p1.json["items"].values() + p2.json["items"].values()).map { it["id"].asString() }.toSet())
        assertEquals(400, s.env.get("/v1/operator/bookings?limit=2&cursor=${cur.dropLast(3)}AAA", op).status)
        assertEquals(400, s.env.get("/v1/operator/bookings?limit=2&state=CONFIRMING&cursor=$cur", op).status, "Cursor eines anderen Filters")
        assertEquals(422, s.env.get("/v1/operator/bookings?limit=101", op).status)
        assertEquals(422, s.env.get("/v1/operator/bookings?state=NOPE", op).status)
        assertEquals(422, s.env.get("/v1/operator/bookings?passenger=kein-uuid", op).status)
        assertEquals(422, s.env.get("/v1/operator/bookings?from=2026-12-02T00:00:00Z&to=2026-12-01T00:00:00Z", op).status)
    }
}
