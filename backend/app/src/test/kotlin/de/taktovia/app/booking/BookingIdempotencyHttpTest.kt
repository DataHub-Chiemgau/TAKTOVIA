package de.taktovia.app.booking

import de.taktovia.app.probe.Resp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * WP-M2-24 / LH-6.4-BKG-012, PH-AT-011 (Buchungsanteil) über die echte Anwendung mit dem Idempotenzfilter und dem Register in der Datenbank: Hold, Confirm und Storno sind durch
 * `Idempotency-Key` gegen Doppelausführung geschützt. Scope `(Akteur, Mandant, operationId, Schlüssel)`; die Wiederholung wird nach Authentisierung und Autorisierung, aber vor dem erneuten
 * ETag-/Ablauftest erkannt.
 */
class BookingIdempotencyHttpTest {
    private val s = BookingHttpSupport()
    private fun key() = "idem-" + UUID.randomUUID()

    @Test
    fun wiederholung_confirm_liefert_gleiche_buchung_auch_nach_angebotsablauf() {
        val h = s.held(); val key = key()
        val a = s.confirm(h, key = key); val b = s.confirm(h, key = key)
        assertEquals(201, a.status, a.text); assertEquals(a.json, b.json); assertEquals("true", b.header("Idempotent-Replayed")); assertNull(a.header("Idempotent-Replayed"))
        assertEquals(a.header("Location"), b.header("Location")); assertEquals(a.header("ETag"), b.header("ETag"))
        // Angebot, Preisstand und Hold laufen ab (Testaufbau, abgeschalteter Inhaltswächter): die Wiederholung desselben Requests liefert weiterhin dieselbe Buchung, nie 409 OFFER_EXPIRED/HOLD_EXPIRED
        s.offers.expire(h.issued)
        val c = s.confirm(h, key = key)
        assertEquals(201, c.status, c.text); assertEquals(a.json["id"].asString(), c.json["id"].asString())
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ?", s.tenant.value))
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'booking.confirmed' AND tenant_id = ?", s.tenant.value), "die Wiederholung erzeugt kein weiteres Ereignis")
    }

    @Test
    fun wiederholung_hold_liefert_gleiches_ergebnis() {
        val acc = s.newAccount(); val issued = s.offers.issue(acc.token); s.offers.bind(issued)
        val gross = issued.offer["gross"]["amount_minor"].asLong(); val hk = key()
        val h1 = s.offers.hold(issued.id, acc.token, "1", gross, key = hk); val h2 = s.offers.hold(issued.id, acc.token, "1", gross, key = hk)
        assertEquals(201, h1.status, h1.text); assertEquals(h1.json, h2.json); assertEquals("true", h2.header("Idempotent-Replayed"))
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.capacity_hold WHERE tenant_id = ?", s.tenant.value))
    }

    @Test
    fun fingerprint_abweichung_ergibt_409_ohne_ausfuehrung() {
        val h = s.held(); val key = key()
        val ok = s.confirm(h, key = key); assertEquals(201, ok.status, ok.text)
        // gleicher Schlüssel, anderer Betrag: Konflikt, nichts ändert sich (PH-AT-011)
        val amount = s.confirm(h, gross = h.grossMinor + 1, key = key)
        assertEquals(409, amount.status); assertEquals("IDEMPOTENCY_CONFLICT", amount.json["code"].asString())
        // gleicher Schlüssel, anderer Hold (anderes Angebot)
        val other = s.held(h.account)
        val diffOffer = s.confirm(other, key = key)
        assertEquals(409, diffOffer.status); assertEquals("IDEMPOTENCY_CONFLICT", diffOffer.json["code"].asString())
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.capacity_hold WHERE id = ?::uuid AND state = 'HELD'", other.holdId), "der andere Hold wurde nicht ausgeführt")
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ?", s.tenant.value))
    }

    @Test
    fun fehlender_idempotency_key_wird_abgelehnt() {
        val h = s.held()
        val r = s.env.server.send("POST", "/v1/bookings", """{"hold_id":"${h.holdId}","max_gross":{"amount_minor":${h.grossMinor},"currency":"EUR","exponent":2}}""",
            s.env.headers(h.account.token, extra = mapOf("If-Match" to "\"${h.etag}\"")))
        assertEquals(422, r.status, r.text)
        assertEquals(0, s.count("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ?", s.tenant.value))
    }

    @Test
    fun schluessel_kollidiert_nicht_zwischen_mandant_akteur_operationstyp() {
        val key = key()
        val a = s.held(); val b = s.held()                                   // zwei Akteure desselben Mandanten
        val ra = s.confirm(a, key = key); val rb = s.confirm(b, key = key)
        assertEquals(201, ra.status, ra.text); assertEquals(201, rb.status, rb.text)
        assertNotEquals(ra.json["id"].asString(), rb.json["id"].asString(), "keine fremde Antwort für einen anderen Akteur")
        // anderer Mandant mit demselben Schlüssel
        val foreign = BookingHttpSupport(); val f = foreign.held()
        val rf = foreign.confirm(f, key = key)
        assertEquals(201, rf.status, rf.text); assertNotEquals(ra.json["id"].asString(), rf.json["id"].asString())
        // anderer Operationstyp (Hold) mit demselben Schlüssel
        val c = s.newAccount(); val issued = s.offers.issue(c.token); s.offers.bind(issued)
        val hold = s.offers.hold(issued.id, c.token, "1", issued.offer["gross"]["amount_minor"].asLong(), key = key)
        assertEquals(201, hold.status, hold.text)
    }

    @Test
    fun wiederholung_prueft_authentisierung_und_leseberechtigung_erneut() {
        val h = s.held(); val key = key()
        val ok = s.confirm(h, key = key); assertEquals(201, ok.status, ok.text)
        // ohne Anmeldung keine gespeicherte Antwort
        val anon = s.env.server.send("POST", "/v1/bookings", """{"hold_id":"${h.holdId}","max_gross":{"amount_minor":${h.grossMinor},"currency":"EUR","exponent":2}}""",
            mapOf("Idempotency-Key" to key, "If-Match" to "\"${h.etag}\""))
        assertEquals(401, anon.status)
        // entzogene Berechtigung: Mitgliedschaft widerrufen, dann keine gecachte Antwort mehr
        s.env.exec("UPDATE taktovia.membership SET revoked_at = clock_timestamp() WHERE tenant_id = ? AND subject = ?", s.tenant.value, h.account.subject.toString())
        val revoked = s.confirm(h, key = key)
        assertTrue(revoked.status in setOf(401, 403), "Status ${revoked.status}: ${revoked.text}")
        assertNull(revoked.header("Idempotent-Replayed"))
    }

    @Test
    fun nach_cacheablauf_keine_zweite_aktive_buchung() {
        val h = s.held(); val key = key()
        val first = s.confirm(h, key = key); assertEquals(201, first.status, first.text)
        s.env.exec("UPDATE taktovia.idempotency_record SET expires_at = now() - interval '1 hour' WHERE tenant_id = ?", s.tenant.value)   // Antwortcache abgelaufen (72 h)
        val again = s.confirm(h, key = key)
        // die fachlichen Constraints und der Hold-Lookup liefern dieselbe Buchung, keine zweite
        assertEquals(201, again.status, again.text); assertEquals(first.json["id"].asString(), again.json["id"].asString()); assertNull(again.header("Idempotent-Replayed"))
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ?", s.tenant.value))
    }

    @Test
    fun hundert_parallele_identische_requests_eine_buchung() {
        val h = s.held(); val key = key()
        val results = race((1..100).map { Callable { s.confirm(h, key = key) } })
        val ok = results.filter { it.status == 201 }
        assertTrue(ok.isNotEmpty())
        assertTrue(results.all { it.status == 201 || (it.status == 409 && it.json["code"].asString() == "OPERATION_IN_PROGRESS") }, results.map { it.status }.groupingBy { it }.eachCount().toString())
        assertEquals(1, ok.map { it.json["id"].asString() }.toSet().size, "alle Erfolge nennen dieselbe Buchung")
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ?", s.tenant.value))
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'booking.confirmed' AND tenant_id = ?", s.tenant.value))
    }

    @Test
    fun zwei_schluessel_parallel_hoechstens_eine_buchung() {
        repeat(3) {
            val h = s.held()
            val results = race(listOf(Callable { s.confirm(h, key = key()) }, Callable { s.confirm(h, key = key()) }))
            assertTrue(results.all { it.status == 201 }, results.map { it.status }.toString())
            assertEquals(1, results.map { it.json["id"].asString() }.toSet().size)
            assertEquals(1, s.count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?::uuid", h.holdId))
        }
    }

    private fun race(tasks: List<Callable<Resp>>): List<Resp> {
        val pool = Executors.newFixedThreadPool(tasks.size.coerceAtMost(40)); val start = CountDownLatch(1)
        try { val f = tasks.map { t -> pool.submit(Callable { start.await(); t.call() }) }; start.countDown(); return f.map { it.get() } } finally { pool.shutdownNow() }
    }
}
