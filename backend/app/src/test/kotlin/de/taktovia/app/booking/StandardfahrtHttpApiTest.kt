package de.taktovia.app.booking

import de.taktovia.app.probe.Resp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * WP-M2-37/-41 (UI-01/UI-02, LH-6.3-PAX-001/-005) über die echte Anwendung: der Aufrufweg, den die Passenger-PWA geht (Angebot, Hold mit `If-Match`,
 * Lesen der Revision nach dem Hold, Bestätigung, Liste), und der Nachweis „Anzeige = Angebotsstand = Buchung“. Die Planbindung des Angebots setzt
 * [OfferHttpSupport.bind] (Vorbehalt dort).
 */
class StandardfahrtHttpApiTest {
    private val s = BookingHttpSupport()

    @Test
    fun standardweg_angebot_hold_bestaetigung_liste_in_vier_aufrufen() {
        val account = s.newAccount()
        val issued = s.offers.issue(account.token, 3, 1); s.offers.bind(issued, 4)
        val offer = issued.offer
        // 1. Anzeige: das Angebot enthält Zeitfenster, Preis mit Aufschlüsselung und die Revision, die die Oberfläche hält
        val shown = s.offers.get(issued).json
        assertEquals(offer["revision"].asLong(), shown["revision"].asLong())
        // 2. Hold mit der angezeigten Revision
        val hold = s.offers.hold(issued.id, account.token, shown["revision"].asString(), shown["gross"]["amount_minor"].asLong())
        assertEquals(201, hold.status, hold.text)
        // 3. die Oberfläche liest die Revision nach dem Hold neu (der Client sieht den ETag der Hold-Antwort nicht): sie muss dem ETag der Hold-Antwort entsprechen
        val afterHold = s.offers.get(issued)
        assertEquals(hold.header("ETag"), afterHold.header("ETag"))
        assertEquals("\"" + afterHold.json["revision"].asLong() + "\"", afterHold.header("ETag"))
        assertEquals(hold.json["id"].asString(), afterHold.json["hold"]["id"].asString())
        // der angezeigte Stand (Fenster, Preis, Komponenten, Haltepunkte) ist nach dem Hold unverändert
        for (f in listOf("pickup_window", "arrival_window", "gross", "fare_components", "pickup_stop", "dropoff_stop")) assertEquals(shown[f], afterHold.json[f], f)
        // 4. Bestätigung mit der Revision nach dem Hold
        val h = BookingHttpSupport.Held(account, issued.id, hold.json["id"].asString(), afterHold.json["revision"].asString(), shown["gross"]["amount_minor"].asLong(), UUID.randomUUID(), issued)
        val booking = s.confirm(h)
        assertEquals(201, booking.status, booking.text)
        // die Liste der Startansicht enthält die Buchung
        assertEquals(listOf(booking.json["id"].asString()), s.list(account.token).json["items"].values().asSequence().map { it["id"].asString() }.toList())
    }

    @Test
    fun anzeige_entspricht_promise_snapshot_und_fare_quote() {
        val h = s.held(); val shown = h.issued.offer
        val b = s.confirm(h)
        assertEquals(201, b.status, b.text)
        val p = b.json["promise"]
        // Fenster und Preis der Buchung sind exakt die des angezeigten Angebots
        assertEquals(java.time.Instant.parse(shown["pickup_window"]["earliest_at"].asString()), java.time.Instant.parse(p["pickup_earliest"].asString()))
        assertEquals(java.time.Instant.parse(shown["pickup_window"]["latest_at"].asString()), java.time.Instant.parse(p["pickup_latest"].asString()))
        assertEquals(java.time.Instant.parse(shown["arrival_window"]["earliest_at"].asString()), java.time.Instant.parse(p["arrival_earliest"].asString()))
        assertEquals(java.time.Instant.parse(shown["arrival_window"]["latest_at"].asString()), java.time.Instant.parse(p["arrival_latest"].asString()))
        assertEquals(shown["gross"], p["gross"])
        // der Preis der Buchung (promise_snapshot) ist der Preis des gebundenen fare_quote (Anzeige = fare_quote = Zusage)
        val id = b.json["id"].asString(); val gross = shown["gross"]["amount_minor"].asLong()
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.booking WHERE id = ?::uuid AND (promise_snapshot->>'gross_minor')::bigint = ?", id, gross))
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.booking b JOIN taktovia.ride_offer o ON o.tenant_id = b.tenant_id AND o.hold_id = b.hold_id JOIN taktovia.fare_quote q ON q.id = o.quote_id WHERE b.id = ?::uuid AND q.pay_at_platform_minor = ?", id, gross))
    }

    @Test
    fun parallele_aenderung_bestaetigt_nur_angezeigten_stand_oder_nichts() {
        repeat(3) {
            val h = s.held()
            // veralteter Stand (Revisionswechsel durch einen parallelen Vorgang): nichts wird bestätigt
            assertEquals(412, s.confirm(h, ifMatch = "999").status)
            assertEquals(0, s.count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?::uuid", h.holdId))
            // zwei Tabs gleichzeitig, einer mit aktuellem, einer mit veraltetem Stand: nie zwei Buchungen, jede Antwort ist 412 oder genau diese eine Buchung mit dem gehaltenen Stand
            val results = race(listOf(Callable { s.confirm(h, key = "a-" + UUID.randomUUID()) }, Callable { s.confirm(h, ifMatch = "999", key = "b-" + UUID.randomUUID()) }))
            assertTrue(results.all { it.status == 201 || it.status == 412 }, results.map { it.status }.toString())
            val ok = results.filter { it.status == 201 }
            assertTrue(ok.isNotEmpty() && ok.map { it.json["id"].asString() }.toSet().size == 1)
            assertEquals(1, s.count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?::uuid", h.holdId))
            assertEquals(h.grossMinor, ok[0].json["promise"]["gross"]["amount_minor"].asLong())
        }
    }

    @Test
    fun zwei_tabs_hoechstens_eine_buchung_je_angebot() {
        val h = s.held()
        val results = race((1..6).map { Callable { s.confirm(h, key = "tab-$it-" + UUID.randomUUID()) } })
        assertTrue(results.all { it.status == 201 || it.status == 409 }, results.map { it.status }.toString())
        assertEquals(1, results.filter { it.status == 201 }.map { it.json["id"].asString() }.toSet().size)
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?::uuid", h.holdId))
    }

    private fun race(tasks: List<Callable<Resp>>): List<Resp> {
        val pool = Executors.newFixedThreadPool(tasks.size); val start = CountDownLatch(1)
        try { val f = tasks.map { t -> pool.submit(Callable { start.await(); t.call() }) }; start.countDown(); return f.map { it.get() } } finally { pool.shutdownNow() }
    }
}
