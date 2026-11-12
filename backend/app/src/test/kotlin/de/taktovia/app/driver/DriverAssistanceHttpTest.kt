package de.taktovia.app.driver

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode

/**
 * WP-M3-32 / LH-6.6-DRV-008 über die echte Anwendung (HTTP, echter Fahrtenplan, echte Buchungen): Rollstuhlbedarf und Ein-/Ausstiegshilfe sieht nur der Fahrer, dem die Beförderung aktuell
 * zugeordnet ist. Positiv, unbeteiligte Fahrer (auch mit bekannter Buchungs-ID), Planrevision mit Fahrerwechsel ab dem Commit, Entzug nach Fahrtabschluss und Schichtende, Abrufprotokoll ohne
 * Merkmalswerte, keine Merkmale in Logs und anderen Endpunkten. Nicht ausgeführt: die Flutter-App (lokaler Speicher nach Schichtende, `mobile/driver/test/assistance_cache_test.dart`); Push-Nutzlasten
 * gibt es für Fahrer in P1 nicht (Benachrichtigungen gehen nur an Fahrgäste, WP-M2-34).
 */
class DriverAssistanceHttpTest {
    private val w = DriverRunHttpWorld()
    private val env = w.env

    private fun stops(r: JsonNode) = r["stops"].values().asSequence().toList()
    private fun events(shift: java.util.UUID) = env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'staff.driver.assistance_viewed' AND object_ref = ?", w.tenant.value, "staff_shift:$shift")

    @Test
    fun zugeordneter_fahrer_sieht_unterstuetzung_am_stopp() {
        val d = w.driver(); val a = w.booking(assistanceCodes = listOf("BOARDING_HELP"), wheelchair = true); val b = w.booking()
        w.plan(d.run, listOf(a, b))
        val s = stops(d.get().json)
        // A steigt an Stopp 1 ein (Rollstuhl, Einstiegshilfe) und an Stopp 3 aus (Rollstuhl, Ausstiegshilfe); B ohne Bedarf
        assertEquals(true, s[0]["assistance"]["wheelchair_required"].asBoolean()); assertEquals(true, s[0]["assistance"]["boarding_assist"].asBoolean()); assertEquals(false, s[0]["assistance"]["alighting_assist"].asBoolean())
        assertNull(s[1]["assistance"], "kein Bedarf: das Feld entfällt"); assertTrue(s[1]["support_hints"].isEmpty)
        assertEquals(true, s[2]["assistance"]["wheelchair_required"].asBoolean()); assertEquals(true, s[2]["assistance"]["alighting_assist"].asBoolean()); assertEquals(false, s[2]["assistance"]["boarding_assist"].asBoolean())
        assertNull(s[3]["assistance"])
        assertEquals(1, events(d.shift), "der Abruf mit Merkmalen erzeugt genau einen Eintrag")
        val entry = env.string("SELECT redacted_changes::text FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'staff.driver.assistance_viewed' AND object_ref = ?", w.tenant.value, "staff_shift:${d.shift}").single()
        assertFalse(entry.contains("BOARDING", ignoreCase = true) || entry.contains("wheelchair", ignoreCase = true), "ohne Merkmalswerte: $entry")
    }

    @Test
    fun unbeteiligter_fahrer_erhaelt_keine_daten_auch_nicht_per_buchungs_id() {
        val d = w.driver(); val other = w.driver(); val a = w.booking(assistanceCodes = listOf("BOARDING_HELP"), wheelchair = true)
        w.plan(d.run, listOf(a)); w.plan(other.run, listOf(w.booking()))
        // Fahrer derselben Firma: die Schicht des Kollegen ist 404; die eigene Schicht enthält den Bedarf der fremden Buchung nicht
        assertEquals(404, other.get(t = other.token).let { env.get("/v1/driver/shifts/${d.shift}/run", other.token) }.status)
        val own = other.get().json
        assertTrue(stops(own).none { st -> st["booking_ids"].values().asSequence().any { it.asString() == a.id.toString() } }); assertTrue(stops(own).all { it["assistance"] == null })
        // Buchungs-ID bekannt: weder Buchung noch Historie noch Planänderungen des Fahrgastkanals sind für ihn erreichbar
        assertEquals(403, env.get("/v1/bookings/${a.id}", other.token).status)
        assertEquals(403, env.get("/v1/bookings/${a.id}/history", other.token).status)
        assertEquals(403, env.get("/v1/plan-changes", other.token).status)
        // der Fahrer-Strom nennt nur Buchungs-IDs, nie Merkmale
        val feed = env.get("/v1/driver/plan-changes", d.token); assertEquals(200, feed.status)
        for (forbidden in listOf("BOARDING_HELP", "wheelchair", "assistance", "assist")) assertFalse(feed.text.contains(forbidden, ignoreCase = true), "Strom nennt '$forbidden'")
        // anderer Mandant: Konto und Schicht unbekannt
        val foreign = DriverRunHttpWorld().driver()
        assertEquals(404, env.get("/v1/driver/shifts/${d.shift}/run", foreign.token).status)
    }

    @Test
    fun planrevision_wechselt_sichtbarkeit_ab_commit() {
        val d1 = w.driver(); val d2 = w.driver(); val a = w.booking(assistanceCodes = listOf("BOARDING_HELP"), wheelchair = true); val b = w.booking()
        w.plan(d1.run, listOf(a, b)); w.plan(d2.run, listOf(b))
        assertNotNull(stops(d1.get().json)[0]["assistance"]); assertTrue(stops(d2.get().json).all { it["assistance"] == null })
        // Revision: a wandert zum Fahrer 2 (Umlauf d1 behält b, Umlauf d2 erhält a und b)
        val rev1 = w.plan(d1.run, listOf(b)); val rev2 = w.plan(d2.run, listOf(a, b))
        val r1 = d1.get(); assertEquals("\"$rev1\"", r1.header("ETag"))
        assertTrue(stops(r1.json).all { it["assistance"] == null } && stops(r1.json).none { st -> st["booking_ids"].values().asSequence().any { it.asString() == a.id.toString() } }, "der bisherige Fahrer erhält die Hinweise ab dem Commit nicht mehr")
        val r2 = d2.get(); assertEquals("\"$rev2\"", r2.header("ETag")); assertNotNull(stops(r2.json).first { st -> st["booking_ids"][0].asString() == a.id.toString() }["assistance"])
    }

    @Test
    fun nach_fahrtabschluss_und_schichtende_nicht_mehr_abrufbar() {
        val done = w.driver(); val a = w.booking(assistanceCodes = listOf("BOARDING_HELP"), wheelchair = true)
        w.plan(done.run, listOf(a)); assertNotNull(stops(done.get().json)[0]["assistance"])
        w.complete(done.run)                                                                  // Fahrtabschluss: Umlauf COMPLETED
        val after = done.get(); assertEquals(200, after.status); assertTrue(stops(after.json).all { it["assistance"] == null }, "nach Fahrtabschluss keine Merkmale")
        assertFalse(after.text.contains("wheelchair_required"))
        val ended = w.driver(); val b = w.booking(assistanceCodes = listOf("BOARDING_HELP"), wheelchair = true)
        w.plan(ended.run, listOf(b)); assertNotNull(stops(ended.get().json)[0]["assistance"])
        ended.endShift()                                                                      // Schichtende: keine Aufträge, keine Merkmale
        val gone = ended.get(); assertEquals(200, gone.status); assertFalse(gone.json["shift_valid"].asBoolean()); assertEquals(0, gone.json["stops"].size()); assertFalse(gone.text.contains("wheelchair_required"))
    }

    @Test
    fun keine_merkmale_in_logs_push_und_anderen_endpunkten() {
        val d = w.driver(); val a = w.booking(assistanceCodes = listOf("BOARDING_HELP"), wheelchair = true); w.plan(d.run, listOf(a))
        env.server  // Anwendung ist gestartet
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger; val app = LoggerFactory.getLogger("de.taktovia") as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }; val oldLevel = app.level
        root.addAppender(appender); app.level = Level.DEBUG
        try {
            repeat(3) { assertEquals(200, d.get().status) }
            env.get("/v1/driver/plan-changes", d.token); env.get("/v1/driver/plan-changes/snapshot", d.token); env.get("/v1/driver/shifts", d.token)
            env.get("/v1/bookings/${a.id}", d.token)
            // Fehlerpfade erzeugen Logzeilen (Token ungültig, Mandantenangabe manipuliert): auch dort stehen keine Merkmale
            env.get("/v1/driver/shifts/${d.shift}/run", d.token.dropLast(5) + "AAAAA"); env.get("/v1/driver/shifts/${d.shift}/run", d.token, extra = mapOf("X-Tenant-Id" to java.util.UUID.randomUUID().toString()))
        } finally { root.detachAppender(appender); app.level = oldLevel }
        val lines = appender.list.map { e -> e.formattedMessage + " " + (e.argumentArray?.joinToString(" ") ?: "") + " " + (e.throwableProxy?.let { ThrowableProxyUtil.asString(it) } ?: "") + " " + e.mdcPropertyMap.values.joinToString(" ") }
        assertTrue(lines.isNotEmpty(), "der Test belegt nichts, wenn nichts mitgeschnitten wurde")
        for (forbidden in listOf("BOARDING_HELP", "wheelchair_required", "boarding_assist", "alighting_assist", "WHEELCHAIR_SPACE", "assistance_codes")) assertTrue(lines.none { it.contains(forbidden, ignoreCase = true) }, "Log enthält '$forbidden'")
        // andere Fahrerendpunkte tragen keine Merkmale
        for (path in listOf("/v1/driver/plan-changes", "/v1/driver/plan-changes/snapshot", "/v1/driver/shifts")) {
            val r = env.get(path, d.token); assertEquals(200, r.status); assertFalse(r.text.contains("assist", ignoreCase = true) || r.text.contains("wheelchair", ignoreCase = true), "$path nennt Merkmale")
        }
        // Push: für Fahrer gibt es keinen Auftrag zur Benachrichtigung; Nachrichtenaufträge tragen nie Unterstützungsmerkmale
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.notification WHERE tenant_id = ? AND (params::text ILIKE '%assist%' OR params::text ILIKE '%wheelchair%')", w.tenant.value))
    }
}
