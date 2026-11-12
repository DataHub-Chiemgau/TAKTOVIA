package de.taktovia.app.booking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WP-M2-45 (UI-04 Buchungshistorie in vier Bereichen, LH-6.3-PAX-008) über die echte Anwendung: Feld `bereich` je Buchung, Filter `GET /v1/bookings?bereich=…`, ungültiger Filter 422, Cursor einer
 * anderen Sicht abgewiesen (400, wie jeder nicht entschlüsselbare Cursor), nur eigene Buchungen. Den Wechsel bestätigt -> storniert belegt der echte Storno; „laufend“ und „abgeschlossen“ brauchen Ausführungszustände aus M3 (Offener Punkt 37)
 * und sind mit Testereignissen in `BookingHistoryAreaTest` (Modul) belegt, Vollabnahme mit WP-M3-29.
 */
class BookingAreaApiTest {
    private val s = BookingHttpSupport()

    private fun ids(token: String, query: String) = s.list(token, query).json["items"].values().asSequence().map { it["id"].asString() }.toList()

    @Test
    fun bereich_je_buchung_und_filter_stimmen_ueberein_und_storno_verschiebt() {
        val account = s.newAccount()
        val upcoming = s.confirm(s.held(account)).json["id"].asString(); val toCancel = s.confirm(s.held(account)).json["id"].asString()
        val all = s.list(account.token)
        assertEquals(200, all.status, all.text)
        assertTrue(all.json["items"].values().asSequence().all { it["bereich"].asString() == "bevorstehend" })
        assertEquals(setOf(upcoming, toCancel), ids(account.token, "?bereich=bevorstehend").toSet())
        for (empty in listOf("laufend", "vergangen", "storniert")) assertEquals(emptyList<String>(), ids(account.token, "?bereich=$empty"), empty)
        // echter Übergang bestätigt -> storniert verschiebt die Buchung (Einteilung von API-Feld und Filter identisch)
        val c = s.cancel(toCancel, account.token, ifMatch = "1")
        assertEquals(200, c.status, c.text); assertEquals("storniert", c.json["bereich"].asString())
        assertEquals(listOf(upcoming), ids(account.token, "?bereich=bevorstehend")); assertEquals(listOf(toCancel), ids(account.token, "?bereich=storniert"))
        assertEquals(setOf("bevorstehend", "storniert"), s.list(account.token).json["items"].values().asSequence().map { it["bereich"].asString() }.toSet())
        // jede Buchung in genau einem Bereich: die Vereinigung der vier Filter ist die ungefilterte Liste, die Schnittmenge leer
        val per = listOf("bevorstehend", "laufend", "vergangen", "storniert").map { ids(account.token, "?bereich=$it") }
        assertEquals(ids(account.token, "").toSet(), per.flatten().toSet()); assertEquals(2, per.sumOf { it.size })
        // GET Detail trägt dieselbe Einteilung
        assertEquals("storniert", s.get(toCancel, account.token).json["bereich"].asString())
    }

    @Test
    fun fremde_buchungen_nicht_in_liste() {
        val a = s.held(); val b = s.held()
        val idA = s.confirm(a).json["id"].asString(); val idB = s.confirm(b).json["id"].asString()
        for (area in listOf("bevorstehend", "storniert")) {
            assertFalse(idB in ids(a.account.token, "?bereich=$area")); assertFalse(idA in ids(b.account.token, "?bereich=$area"))
        }
        assertEquals(listOf(idA), ids(a.account.token, "?bereich=bevorstehend"))
        val foreign = BookingHttpSupport(); assertEquals(emptyList<String>(), foreign.list(foreign.newAccount().token, "?bereich=bevorstehend").json["items"].values().asSequence().map { it["id"].asString() }.toList())
    }

    @Test
    fun ungueltiger_bereich_422_und_fremder_cursor_abgewiesen() {
        val account = s.newAccount(); s.confirm(s.held(account)); s.confirm(s.held(account))
        val bad = s.list(account.token, "?bereich=alles")
        assertEquals(422, bad.status, bad.text); assertEquals("application/problem+json", bad.header("Content-Type")?.substringBefore(';'))
        assertEquals(422, s.list(account.token, "?bereich=").status, "leerer Wert ist kein gültiger Bereich")
        // ein Cursor der Sicht „bevorstehend“ gilt nicht für die Sicht „storniert“ und nicht für die ungefilterte Liste
        val page = s.list(account.token, "?bereich=bevorstehend&limit=1")
        assertEquals(200, page.status, page.text); val cursor = page.json["next_cursor"].asString()
        assertEquals(200, s.list(account.token, "?bereich=bevorstehend&limit=1&cursor=$cursor").status)
        assertEquals(400, s.list(account.token, "?bereich=storniert&limit=1&cursor=$cursor").status)
        assertEquals(400, s.list(account.token, "?limit=1&cursor=$cursor").status)
        // ein Cursor eines anderen Fahrgasts wird ebenfalls abgewiesen
        assertEquals(400, s.list(s.newAccount().token, "?bereich=bevorstehend&limit=1&cursor=$cursor").status)
    }
}
