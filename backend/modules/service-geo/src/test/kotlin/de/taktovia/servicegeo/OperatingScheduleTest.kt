package de.taktovia.servicegeo

import de.taktovia.platform.problem.ViolationCode
import de.taktovia.servicegeo.api.OperatingSchedule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/** WP-M2-03: Betriebszeiten in Ortszeit, halboffene Fenster, Ausnahmetage, Zeitumstellung (25.10.2026 02:30 mit beiden Offsets). */
class OperatingScheduleTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun at(s: String): Instant = OffsetDateTime.parse(s).toInstant()
    private fun weekly(vararg w: Triple<List<String>, String, String>) = w.map { mapOf("weekdays" to it.first, "from" to it.second, "to" to it.third) }
    private fun schedule(weekly: Any?, exceptions: Any? = emptyList<Any>()) = OperatingSchedule.parse(berlin, weekly, exceptions).also { assertTrue(it.second.isEmpty(), it.second.toString()) }.first!!

    private val all = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

    @Test
    fun fenster_sind_links_geschlossen_rechts_offen_in_ortszeit() {
        val s = schedule(weekly(Triple(all, "06:00", "22:00")))
        assertFalse(s.isOperating(at("2026-12-01T05:59:59+01:00")))
        assertTrue(s.isOperating(at("2026-12-01T06:00:00+01:00")))
        assertTrue(s.isOperating(at("2026-12-01T21:59:59+01:00")))
        assertFalse(s.isOperating(at("2026-12-01T22:00:00+01:00")))
        // derselbe Zeitpunkt in UTC ausgedrückt: maßgeblich ist die Ortszeit der Dienstzone
        assertTrue(s.isOperating(at("2026-12-01T05:00:00Z")))
    }

    @Test
    fun zeitumstellung_herbst_beide_offsets_ergeben_eindeutige_antworten() {
        // 25.10.2026: 03:00 CEST (+02:00) springt auf 02:00 CET (+01:00); 02:30 kommt zweimal vor.
        val inWindow = schedule(weekly(Triple(all, "02:00", "04:00")))
        assertTrue(inWindow.isOperating(at("2026-10-25T02:30:00+02:00")))
        assertTrue(inWindow.isOperating(at("2026-10-25T02:30:00+01:00")))
        val outside = schedule(weekly(Triple(all, "06:00", "22:00")))
        assertFalse(outside.isOperating(at("2026-10-25T02:30:00+02:00")))
        assertFalse(outside.isOperating(at("2026-10-25T02:30:00+01:00")))
        // die beiden Zeitpunkte sind verschieden (eine Stunde Abstand), die Antwort je Zeitpunkt ist eindeutig
        assertEquals(3600, at("2026-10-25T02:30:00+01:00").epochSecond - at("2026-10-25T02:30:00+02:00").epochSecond)
        // Fenster endet in der wiederholten Stunde: 02:30 liegt nach dem Ende, beide Vorkommen sind außer Betrieb
        val endsAtTwo = schedule(weekly(Triple(all, "00:00", "02:30")))
        assertFalse(endsAtTwo.isOperating(at("2026-10-25T02:30:00+02:00")))
        assertFalse(endsAtTwo.isOperating(at("2026-10-25T02:30:00+01:00")))
        assertTrue(endsAtTwo.isOperating(at("2026-10-25T02:29:00+01:00")))
    }

    @Test
    fun zeitumstellung_fruehjahr_luecke_ist_kein_zeitpunkt_fenster_bleibt_gueltig() {
        val s = schedule(weekly(Triple(all, "01:00", "04:00")))   // 29.03.2026: 02:00 bis 03:00 existiert nicht
        assertTrue(s.isOperating(at("2026-03-29T01:59:00+01:00")))
        assertTrue(s.isOperating(at("2026-03-29T03:00:00+02:00")))
        assertFalse(s.isOperating(at("2026-03-29T04:00:00+02:00")))
    }

    @Test
    fun ausnahmetag_ersetzt_das_wochenschema_vollstaendig() {
        val s = schedule(weekly(Triple(all, "06:00", "22:00")), listOf(
            mapOf("date" to "2026-12-24", "windows" to emptyList<Any>()),
            mapOf("date" to "2026-12-31", "windows" to listOf(mapOf("from" to "08:00", "to" to "14:00")))))
        assertFalse(s.isOperating(at("2026-12-24T10:00:00+01:00")))      // ganztägig geschlossen
        assertTrue(s.isOperating(at("2026-12-31T08:00:00+01:00")))
        assertFalse(s.isOperating(at("2026-12-31T15:00:00+01:00")))      // Wochenschema gilt an dem Tag nicht
        assertTrue(s.isOperating(at("2026-12-23T10:00:00+01:00")))
    }

    @Test
    fun ohne_fenster_ist_der_dienst_ausser_betrieb_und_hat_keine_naechste_oeffnung() {
        val s = schedule(emptyList<Any>())
        assertFalse(s.isOperating(at("2026-12-01T12:00:00+01:00")))
        assertNull(s.nextOpening(at("2026-12-01T12:00:00+01:00"), 30))
    }

    @Test
    fun naechste_oeffnung_wochentage_und_laufendes_fenster() {
        val s = schedule(weekly(Triple(listOf("MON", "TUE"), "06:00", "22:00")))
        // Mittwoch 2026-12-02 12:00: nächste Öffnung Montag 07.12. 06:00 Ortszeit
        val n = s.nextOpening(at("2026-12-02T12:00:00+01:00"))
        assertNotNull(n); assertEquals(at("2026-12-07T06:00:00+01:00"), n!!.instant)
        // läuft gerade ein Fenster, ist es der Zeitpunkt selbst
        val now = at("2026-12-01T10:00:00+01:00")
        assertEquals(now, s.nextOpening(now)!!.instant)
    }

    @Test
    fun ungueltige_eingaben_werden_mit_pointer_gemeldet() {
        fun v(w: Any?, e: Any? = emptyList<Any>()) = OperatingSchedule.parse(berlin, w, e).second.map { it.pointer to it.code }
        assertTrue(("/weekly_hours/0/from" to ViolationCode.PATTERN_MISMATCH) in v(weekly(Triple(listOf("MON"), "25:00", "22:00"))))
        assertTrue(("/weekly_hours/0/to" to ViolationCode.INTERVAL_INVALID) in v(weekly(Triple(listOf("MON"), "10:00", "10:00"))))
        assertTrue(("/weekly_hours/0/weekdays" to ViolationCode.NOT_IN_ENUM) in v(weekly(Triple(listOf("XXX"), "06:00", "22:00"))))
        assertTrue(v(weekly(Triple(listOf("MON"), "06:00", "12:00"), Triple(listOf("MON"), "11:00", "14:00"))).any { it.second == ViolationCode.INVALID_VALUE })   // Überlappung
        assertTrue(v(emptyList<Any>(), listOf(mapOf("date" to "kaputt", "windows" to emptyList<Any>()))).contains("/exceptions/0/date" to ViolationCode.INVALID_FORMAT))
        assertTrue(v("kein array").contains("/weekly_hours" to ViolationCode.TYPE_MISMATCH))
        // 24:00 nur als Ende
        assertTrue(v(weekly(Triple(listOf("MON"), "24:00", "24:00"))).isNotEmpty())
        assertTrue(v(weekly(Triple(listOf("MON"), "22:00", "24:00"))).isEmpty())
    }
}
