package de.taktovia.app.booking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WP-M2-37 (UI-01, LH-6.3-PAX-001) über die echte Anwendung: die Liste „eigene Buchungen“ der Startansicht (`GET /v1/bookings`) zeigt nur Buchungen des
 * angemeldeten Fahrgasts; Buchungen eines anderen Fahrgasts desselben Mandanten und eines anderen Mandanten erscheinen nie, der Direktaufruf ergibt 404.
 * Die Liste liefert Zustand und Zusage, aus denen die Oberfläche „anstehend“ ableitet (Statuswechsel verschiebt die Buchung).
 */
class BookingListApiTest {
    private val s = BookingHttpSupport()

    @Test
    fun fremde_buchungen_nie_in_liste_und_404() {
        val a = s.held(); val b = s.held()
        val idA = s.confirm(a).json["id"].asString(); val idB = s.confirm(b).json["id"].asString()
        fun ids(token: String) = s.list(token).json["items"].values().asSequence().map { it["id"].asString() }.toList()
        // jeder Fahrgast sieht genau seine Buchung
        assertEquals(listOf(idA), ids(a.account.token)); assertEquals(listOf(idB), ids(b.account.token))
        // anderer Fahrgast desselben Mandanten: Direktaufruf 404, in der Liste nicht enthalten
        assertEquals(404, s.get(idA, b.account.token).status); assertEquals(404, s.get(idB, a.account.token).status)
        // anderer Mandant (eigene Welt, eigener Fahrgast): leere Liste und 404
        val foreign = BookingHttpSupport(); val foreignToken = foreign.newAccount().token
        assertEquals(0, foreign.list(foreignToken).json["items"].size())
        assertEquals(404, foreign.get(idA, foreignToken).status)
        // Fahrgast ohne jede Buchung: leere Liste (Leerzustand der Startansicht), kein Fehler
        val leer = s.list(s.newAccount().token)
        assertEquals(200, leer.status, leer.text); assertEquals(0, leer.json["items"].size()); assertTrue(leer.json["next_cursor"].isNull)
    }

    @Test
    fun liste_liefert_zustand_und_zusage_fuer_anstehend_und_statuswechsel_verschiebt() {
        val h = s.held(); val id = s.confirm(h).json["id"].asString()
        val item = s.list(h.account.token).json["items"][0]
        assertEquals("CONFIRMING", item["state"].asString())
        assertFalse(item["promise"]["pickup_earliest"].asString().isBlank()); assertFalse(item["promise"]["arrival_latest"].asString().isBlank())
        assertTrue(item["booking_number"].asString().startsWith("BK-"))
        // Storno ändert den Zustand: die Liste zeigt ihn (die Oberfläche filtert „anstehend“ nach Zustand und spätester Ankunft)
        val c = s.cancel(id, h.account.token, ifMatch = "1")
        assertEquals(200, c.status, c.text)
        assertEquals("CANCELED", s.list(h.account.token).json["items"][0]["state"].asString())
    }

    @Test
    fun paginierung_liefert_nur_eigene_buchungen_ueber_cursor() {
        val account = s.newAccount()
        val first = s.held(account); val second = s.held(account)
        val id1 = s.confirm(first).json["id"].asString(); val id2 = s.confirm(second).json["id"].asString()
        val other = s.held(); s.confirm(other)
        val p1 = s.list(account.token, "?limit=1")
        assertEquals(200, p1.status, p1.text); assertEquals(1, p1.json["items"].size()); assertFalse(p1.json["next_cursor"].isNull)
        val p2 = s.list(account.token, "?limit=1&cursor=" + p1.json["next_cursor"].asString())
        assertEquals(200, p2.status, p2.text)
        assertEquals(setOf(id1, id2), setOf(p1.json["items"][0]["id"].asString(), p2.json["items"][0]["id"].asString()))
        assertTrue(p2.json["next_cursor"].isNull)
    }
}
