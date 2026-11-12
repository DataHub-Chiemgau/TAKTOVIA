package de.taktovia.stafftime

import de.taktovia.stafftime.api.BoardingCodeValue
import de.taktovia.stafftime.api.DriverEventCommand
import de.taktovia.stafftime.api.DriverEventType
import de.taktovia.stafftime.internal.BoardingCodeHash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** WP-M3-30: Hash und Schutz des Boarding-Codes ohne Datenbank (Format, Salz, Pfeffer, Schreibschutz in `toString` und im Fingerabdruck des Ereignisses). */
class BoardingCodeHashTest {
    @Test
    fun hash_ist_gesalzen_ueberpruefbar_und_enthaelt_den_code_nicht() {
        val a = BoardingCodeHash.hash("123456", ""); val b = BoardingCodeHash.hash("123456", "")
        assertNotEquals(a, b, "zufälliges Salz je Code"); assertTrue(BoardingCodeHash.verify("123456", "", a)); assertFalse(BoardingCodeHash.verify("123457", "", a))
        assertFalse(a.contains("123456")); assertTrue(a.length in 20..300 && !a.contains("plain", ignoreCase = true), "passt zur Spaltenprüfung von boarding_code")
        assertFalse(BoardingCodeHash.verify("123456", "anderer-pfeffer", BoardingCodeHash.hash("123456", "pfeffer")), "ohne den serverseitigen Pfeffer ist der Hash nicht prüfbar")
        assertTrue(BoardingCodeHash.verify("123456", "pfeffer", BoardingCodeHash.hash("123456", "pfeffer")))
        assertFalse(BoardingCodeHash.verify("123456", "", "kaputt"), "unbekanntes Format wird nie als gültig gelesen")
    }

    @Test
    fun neue_codes_haben_sechs_ziffern() {
        repeat(200) { assertTrue(Regex("[0-9]{6}").matches(BoardingCodeHash.newCode())) }
        assertThrows(IllegalArgumentException::class.java) { BoardingCodeValue("12345") }
        assertThrows(IllegalArgumentException::class.java) { BoardingCodeValue("12345a") }
    }

    @Test
    fun code_steht_weder_in_tostring_noch_im_fingerabdruck_des_ereignisses() {
        val code = BoardingCodeValue("482913"); val t = Instant.parse("2026-12-01T06:00:00Z")
        val with = DriverEventCommand(UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(), 1, DriverEventType.CHECK_IN, UUID.randomUUID(), UUID.randomUUID(), t, t, false, boardingCode = code)
        assertFalse(with.toString().contains("482913"), "Logs und Crashreports enthalten den Code nie"); assertFalse(code.toString().contains("482913"))
        assertFalse(with.contentFingerprint().contains("482913"), "der Fingerabdruck wird als SHA-256 abgelegt: der Code würde umkehrbar")
        assertEquals(with.contentFingerprint(), with.copy(boardingCode = BoardingCodeValue("111111")).contentFingerprint(), "nur ob ein Code mitgesendet wurde")
        assertNotEquals(with.contentFingerprint(), with.copy(boardingCode = null).contentFingerprint())
    }
}
