package de.taktovia.app.operator

import de.taktovia.app.booking.BookingHttpSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M3-39 / LH-6.7-OPS-004 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): Leitstellenbuchung auf `POST /v1/ride-offers` (`booking_subject` DELEGATED), `.../hold`,
 * `POST /v1/bookings`, `POST /v1/bookings/{id}/changes` und `.../cancel` mit `Idempotency-Key`, `If-Match`, Rollen- und Scopeprüfung. Die Bindung des Angebots an einen Umlauf entsteht wie in den
 * M2-Tests im Testaufbau ([de.taktovia.app.booking.OfferHttpSupport.bind]).
 */
class OperatorBookingHttpTest {
    private val s = BookingHttpSupport()
    private val env = s.env

    private class Flow(val offerId: String, val holdId: String, val etag: String, val gross: Long)

    private fun passengerRef(subject: UUID) = env.string("SELECT id::text FROM taktovia.passenger_private WHERE idp_subject = ?", subject.toString()).single()
    private fun idem() = mapOf("Idempotency-Key" to "op-" + UUID.randomUUID())
    private fun delegated(passengerId: String, extra: String = "") = ""","booking_subject":{"kind":"DELEGATED","passenger_id":"$passengerId"}$extra"""

    /** Angebot für den Fahrgast, an einen Umlauf gebunden, gehalten. */
    private fun held(op: String, passengerId: String, passengers: Int = 2): Flow {
        val r = s.world.request(s.world.body(s.world.later(3), passengers = passengers, extra = delegated(passengerId)), op)
        check(r.status == 200 && r.json["offers"].size() == 1) { "Angebot: ${r.status} ${r.text}" }
        val offer = r.json["offers"][0]; val issued = de.taktovia.app.booking.OfferHttpSupport.Issued(op, r, offer)
        s.offers.bind(issued, 4)
        val gross = offer["gross"]["amount_minor"].asLong()
        val h = s.offers.hold(issued.id, op, offer["revision"].asString(), gross)
        check(h.status == 201) { "Hold: ${h.status} ${h.text}" }
        return Flow(issued.id, h.json["id"].asString(), h.header("ETag")!!.trim('"'), gross)
    }

    private fun confirm(op: String, f: Flow, extra: String = ""","reason_code":"PHONE_REQUEST"""", key: String = "op-" + UUID.randomUUID()) =
        env.server.send("POST", "/v1/bookings", """{"hold_id":"${f.holdId}","max_gross":{"amount_minor":${f.gross},"currency":"EUR","exponent":2}$extra}""", env.headers(op, extra = mapOf("Idempotency-Key" to key, "If-Match" to "\"${f.etag}\"")))

    private fun change(op: String, id: String, f: Flow, ifMatch: String?, extra: String = ""","reason_code":"PASSENGER_REQUEST"""", key: String = "op-" + UUID.randomUUID()) =
        env.server.send("POST", "/v1/bookings/$id/changes", """{"new_hold_id":"${f.holdId}","offer_revision":${f.etag},"max_gross":{"amount_minor":${f.gross},"currency":"EUR","exponent":2}$extra}""",
            env.headers(op, extra = mapOf("Idempotency-Key" to key) + (if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap())))

    @Test
    fun anlegen_ueber_booking_subject_bindet_fahrgast_grund_ist_pflicht_und_rollen_werden_geprueft() {
        val acc = s.newAccount(); val pid = passengerRef(acc.subject)
        val service = UUID.fromString(s.world.service); val op = s.operator(listOf(service))
        // ein Fahrgast darf nicht für andere buchen (403); ohne Passenger-ID ist DELEGATED unvollständig (422)
        assertEquals(403, s.world.request(s.world.body(s.world.later(3), extra = delegated(pid)), acc.token).status)
        assertEquals(422, s.world.request(s.world.body(s.world.later(3), extra = ""","booking_subject":{"kind":"DELEGATED"}"""), op).status)
        // Dienstrecht fehlt: der Dienst ist für den Disponenten nicht vorhanden (404)
        assertEquals(404, s.world.request(s.world.body(s.world.later(3), extra = delegated(pid)), s.operator(listOf(UUID.randomUUID()))).status)
        // ein unbekannter Fahrgast ist kein gültiges Subjekt
        assertEquals(422, s.world.request(s.world.body(s.world.later(3), extra = delegated(UUID.randomUUID().toString())), op).status)
        val f = held(op, pid)
        // ohne Grund: 422 und keine Buchung; Fahrgast bestätigt das Angebot der Leitstelle nicht (404: fremder Hold)
        assertEquals(422, confirm(op, f, extra = "").status)
        assertEquals(404, confirm(acc.token, f, extra = "").status)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?::uuid", f.holdId))
        val key = "stable-" + UUID.randomUUID()
        val ok = confirm(op, f, key = key)
        assertEquals(201, ok.status, ok.text)
        val id = ok.json["id"].asString()
        // Wiederholung mit demselben Schlüssel: dieselbe Buchung, keine zweite
        val again = confirm(op, f, key = key)
        assertEquals(201, again.status); assertEquals(id, again.json["id"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?::uuid", f.holdId)); assertEquals(1, env.count("SELECT count(*) FROM taktovia.booking_change WHERE booking_id = ?::uuid AND change_kind = 'CREATE'", id))
        // gebunden an den Fahrgast, Disponent als Akteur
        assertEquals(pid, env.string("SELECT passenger_id::text FROM taktovia.booking WHERE id = ?::uuid", id).single())
        assertTrue(env.string("SELECT created_by FROM taktovia.booking WHERE id = ?::uuid", id).single().startsWith("user:"))
        assertEquals(200, s.get(id, acc.token).status, "der Fahrgast sieht seine Buchung")
        assertEquals(listOf(id), s.list(acc.token).json["items"].values().map { it["id"].asString() })
    }

    @Test
    fun aendern_und_stornieren_mit_if_match_idempotenz_und_rechten() {
        val acc = s.newAccount(); val pid = passengerRef(acc.subject)
        val service = UUID.fromString(s.world.service); val op = s.operator(listOf(service)); val outsider = s.operator(listOf(UUID.randomUUID()))
        val f1 = held(op, pid)
        val created = confirm(op, f1).json; val id = created["id"].asString(); val rev = created["revision"].asString()
        val f2 = held(op, pid)
        // ohne If-Match 428, veraltet 412, ohne Grund 422, fremder Dienst 404, Fahrgast 403
        assertEquals(428, change(op, id, f2, null).status)
        assertEquals(412, change(op, id, f2, "99").status)
        // 412 gibt den neuen Hold frei: ein neuer Wunsch wird gehalten
        val f3 = held(op, pid)
        assertEquals(422, change(op, id, f3, rev, extra = "").status)
        assertEquals(404, change(outsider, id, f3, rev).status)
        assertEquals(403, change(acc.token, id, f3, rev).status)
        assertEquals("CONFIRMING", env.string("SELECT state FROM taktovia.booking WHERE id = ?::uuid", id).single())
        // Änderung gelingt, Wiederholung mit demselben Schlüssel liefert dasselbe Ergebnis ohne zweiten Tausch
        val key = "chg-" + UUID.randomUUID()
        val done = change(op, id, f3, rev, key = key)
        assertEquals(200, done.status, done.text)
        assertEquals("CANCELED", done.json["replaced"]["state"].asString()); assertEquals(id, done.json["replaced"]["id"].asString())
        val newId = done.json["booking"]["id"].asString()
        val again = change(op, id, f3, rev, key = key)
        assertEquals(200, again.status); assertEquals(newId, again.json["booking"]["id"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.booking_change WHERE booking_id = ?::uuid AND change_kind = 'REPLACE_OFFER'", id))
        // Storno durch die Disposition mit Freitext; Wiederholung mit demselben Schlüssel erzeugt keinen zweiten Eintrag
        val ckey = "cxl-" + UUID.randomUUID()
        fun cancel(token: String, rev: String?, key: String) = env.server.send("POST", "/v1/bookings/$newId/cancel", """{"reason":"OPERATOR_CANCELLATION","reason_text":"Fahrgast hat abgesagt"}""",
            env.headers(token, extra = mapOf("Idempotency-Key" to key) + (if (rev != null) mapOf("If-Match" to "\"$rev\"") else emptyMap())))
        assertEquals(404, cancel(outsider, done.json["booking"]["revision"].asString(), "x-" + UUID.randomUUID()).status)
        val c1 = cancel(op, done.json["booking"]["revision"].asString(), ckey)
        assertEquals(200, c1.status, c1.text); assertEquals("CANCELED", c1.json["state"].asString())
        assertEquals(200, cancel(op, done.json["booking"]["revision"].asString(), ckey).status)
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.booking_change WHERE booking_id = ?::uuid AND change_kind = 'CANCEL'", newId))
        assertEquals("Fahrgast hat abgesagt", env.string("SELECT reason FROM taktovia.booking_change WHERE booking_id = ?::uuid AND change_kind = 'CANCEL'", newId).single())
        assertFalse(env.string("SELECT actor FROM taktovia.booking_change WHERE booking_id = ?::uuid AND change_kind = 'CANCEL'", newId).single().contains(pid))
    }
}
