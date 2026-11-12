package de.taktovia.platform.logging

import de.taktovia.platform.audit.AuditRedactor
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.Problem
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** WP-M5-06 (LH-6.19-NFR-015): Maskierungsregeln aus docs/betrieb/maskierung.md. Testwerte sind erfunden (RFC-2606-Domain, Testkarte, Testkonto). */
class MaskierungTest {
    private val mail = "erika.mustermann@example.test"
    private val karte = "4111 1111 1111 1111"
    private val iban = "DE89 3704 0044 0532 0130 00"
    private val telefon = "+49 176 12345678"
    private val strasse = "Musterstraße 12, 10115 Berlin"
    private val token = "ey" + "JhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.abcdefghijklmnop"   // zur Laufzeit zusammengesetzt (Secret-Scan)
    private val alle = listOf(mail, karte, iban, telefon, "Musterstraße 12", "10115 Berlin", token, "0176 12345678", "030/1234567")

    private fun klartextfrei(s: String) {
        for (w in listOf(mail, "4111", "3704 0044", "37040044", "12345678", "Musterstraße", "10115", token.take(20), "1234567"))
            assertFalse(s.contains(w), "Klartext '$w' in: $s")
        assertEquals(emptySet<Maskierung.Klasse>(), Maskierung.klartextKlassen(s), s)
    }

    private data class Passagier(val name: String, val email: String, val iban: String)

    @Test fun fehlerpfade_ohne_klartextdaten() {
        // Validierungsfehler mit Kontaktfeld im detail und in der Verletzungsnachricht
        val p = Problem(ErrorCode.VALIDATION_FAILED, UUID.randomUUID(), "Feld contact.email '$mail' ist ungültig, Telefon $telefon",
            listOf(Violation("/contact/email", ViolationCode.INVALID_FORMAT, "Wert $mail ungültig"))).toMap()
        klartextfrei(p.toString())
        // PSP-Fehlerantwort
        val psp = """{"error":{"type":"card_error","code":"card_declined","card":"$karte","billing":{"email":"$mail","iban":"$iban"},"authorization":"Bearer abcdefghijkl123456"}}"""
        val m = Maskierung.maskText(psp)
        klartextfrei(m)
        assertTrue(m.contains("card_declined"), "technischer Code bleibt lesbar")
        // Ausnahme mit Objektinhalt
        val ex = IllegalStateException("Zahlung fehlgeschlagen für ${Passagier("Erika Mustermann", mail, iban.replace(" ", ""))}", RuntimeException("Ursache: $telefon"))
        val d = Maskierung.beschreibe(ex)
        klartextfrei(d)
        assertTrue(d.contains("IllegalStateException") && d.contains("RuntimeException"))
        assertFalse(d.contains("\tat "), "kein Stapel")
        // Zeile mit Schlüssel-Wert-Geheimnissen
        klartextfrei(Maskierung.maskText("login password=geheim123 token=abc.def.ghi boarding_code=X7K9Q2 api_key: sk_live_abcdef"))
        // Adresse und deutsche Telefonnummern
        klartextfrei(Maskierung.maskText("Abholung Musterstraße 12, 10115 Berlin, Rückruf 0176 12345678 oder 030/1234567"))
    }

    @Test fun trace_attribute_problem_details_und_alarmtexte_maskiert_ohne_stacktrace() {
        val attrs = Maskierung.traceAttribute(mapOf("http.route" to "/v1/bookings", "user.email" to mail, "search.query" to "Hauptbahnhof",
            "note" to "Anruf $telefon bei $mail", "booking.id" to UUID.randomUUID(), "telemetry.sdk.name" to "opentelemetry"))
        klartextfrei(attrs.toString())
        assertEquals("/v1/bookings", attrs["http.route"])
        assertEquals("opentelemetry", attrs["telemetry.sdk.name"], "Teilwort 'tel' darf technische Schlüssel nicht treffen")
        assertEquals(Maskierung.Klasse.TOKEN.ersatz, attrs["user.email"])
        assertEquals(Maskierung.Klasse.TOKEN.ersatz, attrs["search.query"])

        val detail = Maskierung.problemDetail("Fehler bei $mail\n\tat de.taktovia.Foo.bar(Foo.kt:12)")
        klartextfrei(detail!!)
        val problem = Problem(ErrorCode.INTERNAL_ERROR, UUID.randomUUID(), "Konto $iban nicht belastbar").toMap()
        klartextfrei(problem["detail"] as String)
        assertFalse(problem.toString().contains("Exception"))

        val alarm = Maskierung.alertText("Zustellung an $mail über $telefon fehlgeschlagen (Karte $karte)")
        klartextfrei(alarm)
        assertTrue(alarm.contains("Zustellung") && alarm.contains("fehlgeschlagen"))
        // technische Kennungen bleiben
        val id = UUID.randomUUID()
        val trace = "4bf92f3577b34da6a3ce929d0e0e4736"
        assertEquals("booking $id trace $trace span 00f067aa0ba902b7", Maskierung.maskText("booking $id trace $trace span 00f067aa0ba902b7"))
    }

    @Test fun positivliste_unbekanntes_feld_nicht_protokolliert() {
        val zeile = LogZeile.bauen("booking", "booking_confirmed",
            mapOf("booking_id" to UUID.randomUUID(), "passenger_email" to mail, "start_address" to strasse, "status" to "CONFIRMED", "duration_ms" to 12))
        assertFalse(zeile.contains(mail) || zeile.contains("Musterstraße"), zeile)
        assertTrue(zeile.contains("\"dropped_fields\":[\"passenger_email\",\"start_address\"]"), zeile)
        assertTrue(zeile.contains("\"status\":\"CONFIRMED\"") && zeile.contains("\"duration_ms\":12"))
        assertEquals(mapOf("status" to "OK"), Positivliste.filtere("payment", mapOf("status" to "OK", "card_number" to karte)))
        // kein Sperrschlüssel steht auf irgendeiner Positivliste
        for ((modul, felder) in Positivliste.alleModule()) for (f in felder) assertFalse(Positivliste.istSperrschluessel(f), "$modul.$f")
        // zugelassenes Feld mit Klartextwert wird trotzdem maskiert
        klartextfrei(LogZeile.bauen("payment", "x", mapOf("psp_reference" to "ref $mail")))
        // Ausnahme in der Zeile nur beschrieben
        klartextfrei(LogZeile.bauen("payment", "failed", fehler = RuntimeException("bei $iban")))
    }

    @Test fun audit_event_bleibt_ueber_ids_nachvollziehbar() {
        val tenant = UUID.randomUUID(); val auditId = UUID.randomUUID(); val objectId = UUID.randomUUID(); val corr = UUID.randomUUID()
        val red = AuditRedactor.redact(mapOf("object_id" to objectId.toString(), "email" to mail, "status" to "ACTIVE", "tenant_id" to tenant.toString()), tenant)
        val zeile = LogKontext.mit(tenant, corr) {
            LogZeile.bauen("audit", "audit_event_written", mapOf("audit_event_id" to auditId, "object_id" to objectId, "action" to "booking.confirm", "reason_code" to "x"))
        }
        for (id in listOf(auditId, objectId, tenant, corr)) assertTrue(zeile.contains(id.toString()), "ID $id fehlt: $zeile")
        assertEquals(objectId.toString(), red.json["object_id"])
        assertEquals(tenant.toString(), red.json["tenant_id"])
        assertFalse(red.json.toString().contains(mail))
        klartextfrei(zeile)
    }

    @Test fun mandantenbezug_unter_last_nicht_vertauscht() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        val threads = 16; val je = 400
        val pool = Executors.newFixedThreadPool(threads)
        val barrier = CyclicBarrier(threads)
        val zeilen = java.util.concurrent.ConcurrentLinkedQueue<Pair<UUID, String>>()
        val futures = (0 until threads).map { n ->
            pool.submit {
                val tenant = if (n % 2 == 0) a else b
                barrier.await()
                repeat(je) { i ->
                    LogKontext.mit(tenant, UUID.randomUUID()) {
                        zeilen += tenant to LogZeile.bauen("booking", "booking_event", mapOf("booking_id" to UUID.randomUUID(), "status" to "T$i",
                            "psp_reference" to "kontakt-$n-$i@example.test"))
                    }
                    assertEquals(null, LogKontext.tenantId(), "Kontext darf nach dem Block nicht überleben")
                }
            }
        }
        futures.forEach { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(threads * je, zeilen.size)
        for ((tenant, zeile) in zeilen) {
            val other = if (tenant == a) b else a
            assertTrue(zeile.contains("\"tenant_id\":\"$tenant\""), zeile)
            assertFalse(zeile.contains(other.toString()), zeile)
            assertFalse(zeile.contains("@example.test"), zeile)
        }
    }
}
