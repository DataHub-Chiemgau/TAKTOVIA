package de.taktovia.communicationsupport

import de.taktovia.communicationsupport.api.CreateTemplateRevision
import de.taktovia.communicationsupport.api.NotificationChannel
import de.taktovia.communicationsupport.api.NotificationEventTypes
import de.taktovia.communicationsupport.api.TemplateQuery
import de.taktovia.communicationsupport.api.TemplateResolution
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * LH-6.12-COM-002 / WP-M2-33: Vorlagen je Sprache, Verkehrsprodukt, Ereignistyp und Kanal gegen die echte Datenbank (Runtime-Rolle); der Versand läuft über den
 * Dispatcher des Moduls bis zum Provider-Testdouble. Die Auslieferungssprachen sind Deutsch und Englisch (OP 29, Annahme des Baus).
 */
class TemplateResolutionTest {
    private companion object {
        val f = CommFixture()
        @JvmStatic @AfterAll fun close() = f.close()
        const val DE = "Guten Tag"; const val EN = "Hello"
    }

    private fun create(w: CommFixture.World, language: String, service: UUID?, body: String, channel: NotificationChannel = NotificationChannel.SMS, eventType: String = "booking.confirmed",
                       subject: String? = null) =
        f.api.templates.create(f.context(w.tenant), CreateTemplateRevision(eventType, language, service, channel, body, subject))

    private fun send(w: CommFixture.World, n: UUID) = f.dispatcher().runTenant(w.tenant).also { assertEquals("DELIVERED", f.stateOf(n)) }
    private fun bodyOf(n: UUID) = f.gateway.sent.single { it.messageId == n }.body

    @Test
    fun sprachwechsel_nutzt_richtige_vorlage_mit_buchungsreferenz() {
        val w = f.world(1)
        create(w, "de", null, "$DE, Ihre Buchung {{booking_reference}} ist bestätigt.")
        create(w, "en", null, "$EN, your booking {{booking_reference}} is confirmed.")
        f.directory.languages[w.passenger] = "de-DE"
        val ref = "BK-SPRACHE-1"; val params = f.defaultParams(ref)
        val first = f.enqueue(w, params = params); send(w, first)
        f.directory.languages[w.passenger] = "en-GB"                                         // Sprachwechsel des Fahrgasts
        val second = f.enqueue(w, params = params); send(w, second)
        assertTrue(bodyOf(first).startsWith(DE) && ref in bodyOf(first), bodyOf(first))
        assertTrue(bodyOf(second).startsWith(EN) && ref in bodyOf(second), bodyOf(second))
        assertNotEquals(bodyOf(first), bodyOf(second))
        assertEquals("de-DE", f.strings("SELECT language FROM taktovia.notification WHERE id = ?", first).single())
        assertEquals("en-GB", f.strings("SELECT language FROM taktovia.notification WHERE id = ?", second).single())
        // je Ereignistyp getrennt: eine andere Nachricht (Storno) nimmt die Vorlage ihres Typs
        create(w, "de", null, "Storniert: {{booking_reference}}", eventType = "booking.cancelled_by_passenger")
        f.directory.languages[w.passenger] = "de"
        val cancel = f.enqueue(w, eventType = "booking.cancelled_by_passenger", params = params); send(w, cancel)
        assertTrue(bodyOf(cancel).startsWith("Storniert") && ref in bodyOf(cancel))
    }

    @Test
    fun aktivierung_ohne_buchungsreferenz_wird_abgelehnt() {
        val w = f.world(1)
        for (et in NotificationEventTypes.ALL) {
            val e = assertThrows(ProblemException::class.java) { create(w, "de", null, "Ihre Fahrt ist bestätigt", eventType = et) }
            assertEquals(ErrorCode.VALIDATION_FAILED, e.code, et)
            assertTrue(e.violations.any { it.pointer == "/body_template" }, e.violations.toString())
        }
        assertEquals(0, f.count("SELECT count(*) FROM taktovia.channel_template_revision WHERE tenant_id = ?", w.tenant.value))
        // Leerzeichen in den Klammern sind zulässig; Platzhalter anderer Schreibweise zählen nicht
        create(w, "de", null, "Ref {{ booking_reference }}")
        assertThrows(ProblemException::class.java) { create(w, "de", null, "Ref {{bookingReference}}") }
        assertThrows(ProblemException::class.java) { create(w, "de", null, "Ref {booking_reference}") }
        // unbekannter Platzhalter für den Ereignistyp, unbekannter Ereignistyp, ungültige Sprache, unmögliche Gültigkeit
        assertThrows(ProblemException::class.java) { create(w, "de", null, "{{booking_reference}} {{passenger_name}}") }
        assertThrows(ProblemException::class.java) { create(w, "de", null, "{{booking_reference}}", eventType = "marketing.newsletter") }
        assertThrows(ProblemException::class.java) { create(w, "DE_de", null, "{{booking_reference}}") }
        val now = f.clock.instant()
        assertThrows(ProblemException::class.java) { f.api.templates.create(f.context(w.tenant), CreateTemplateRevision("booking.confirmed", "de", null, NotificationChannel.SMS, "{{booking_reference}}", null, now, now)) }
        // der Dienst eines fremden Mandanten ist nicht auffindbar
        val other = f.world(1)
        val nf = assertThrows(ProblemException::class.java) { create(w, "de", other.serviceA, "{{booking_reference}}") }
        assertEquals(ErrorCode.NOT_FOUND, nf.code)
        assertEquals(1, f.count("SELECT count(*) FROM taktovia.channel_template_revision WHERE tenant_id = ?", w.tenant.value))
        assertEquals(1, f.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'communication.template.created'", w.tenant.value))
    }

    @Test
    fun rueckfallkette_und_fehlende_vorlage_ergibt_kanalfehler() {
        val w = f.world(1)
        val at = f.clock.instant().plusSeconds(1)
        fun resolve(lang: String, service: UUID?, channel: NotificationChannel = NotificationChannel.SMS) =
            f.tx.run(w.tenant) { j -> f.api.templates.resolve(j, TemplateQuery("booking.confirmed", service, lang, channel, at)) }
        fun body(r: TemplateResolution) = (r as TemplateResolution.Found).template.bodyTemplate.substringBefore(' ')
        // Aufbau: Deutsch ohne Dienst (4), Deutsch mit Dienst A (3), Englisch ohne Dienst (2), Englisch mit Dienst A (1)
        assertEquals(TemplateResolution.Missing, resolve("en", w.serviceA), "ohne jede Vorlage: Missing")
        create(w, "de", null, "DE-ohne {{booking_reference}}")
        assertEquals("DE-ohne", body(resolve("en", w.serviceA)), "4: Deutsch ohne Verkehrsprodukt")
        create(w, "de", w.serviceA, "DE-mit {{booking_reference}}")
        assertEquals("DE-mit", body(resolve("en", w.serviceA)), "3: Deutsch mit Verkehrsprodukt")
        assertEquals("DE-ohne", body(resolve("en", w.serviceB)), "anderer Dienst: Rückfall auf Deutsch ohne Dienst")
        create(w, "en", null, "EN-ohne {{booking_reference}}")
        assertEquals("EN-ohne", body(resolve("en", w.serviceA)), "2: gleiche Sprache ohne Verkehrsprodukt")
        assertEquals("EN-ohne", body(resolve("en-GB", w.serviceA)), "Regionstag fällt auf den Hauptteil zurück")
        create(w, "en", w.serviceA, "EN-mit {{booking_reference}}")
        assertEquals("EN-mit", body(resolve("en", w.serviceA)), "1: exakt")
        assertEquals("EN-ohne", body(resolve("en", w.serviceB)))
        assertEquals("DE-mit", body(resolve("de-DE", w.serviceA)))
        assertEquals("DE-mit", body(resolve("fr", w.serviceA)), "unbekannte Sprache: Standardsprache Deutsch")
        assertEquals(TemplateResolution.Missing, resolve("en", w.serviceA, NotificationChannel.EMAIL), "Kanal ist Teil des Schlüssels")
        // Gültigkeit: eine abgelaufene oder künftige Revision wirkt nicht
        f.api.templates.create(f.context(w.tenant), CreateTemplateRevision("booking.confirmed", "en", w.serviceA, NotificationChannel.SMS, "EN-spaeter {{booking_reference}}", null, at.plus(Duration.ofDays(1)), null))
        assertEquals("EN-mit", body(resolve("en", w.serviceA)))
        // höchste gültige Revision gewinnt
        create(w, "en", w.serviceA, "EN-neu {{booking_reference}}")
        assertEquals("EN-neu", body(resolve("en", w.serviceA)))

        // fehlt auch die Rückfallvorlage: Kanalfehler am Auftrag, keine leere Nachricht, kein Providerruf
        val x = f.world(1); val before = f.gateway.sent.size
        val n = f.enqueue(x, eventType = "eta.changed", params = f.defaultParams())
        f.dispatcher().runTenant(x.tenant)
        assertEquals("FAILED", f.stateOf(n)); assertEquals("TEMPLATE_UNAVAILABLE", f.failureOf(n))
        assertEquals(before, f.gateway.sent.size, "keine leere Nachricht")
        assertEquals(1, f.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'communication.notification_failed' AND aggregate_id = ?", x.tenant.value, n))
        assertEquals(1, f.alerts.warnings.count { it.tenantRef == x.tenant.value })
    }

    @Test
    fun zwei_dienste_mit_verschiedenem_verkehrsprodukt_unterschiedliche_texte() {
        val w = f.world(1); f.directory.languages[w.passenger] = "de"
        create(w, "de", w.serviceA, "Ihr Rufbus kommt. Buchung {{booking_reference}}")
        create(w, "de", w.serviceB, "Ihr Linienbedarfsverkehr kommt. Buchung {{booking_reference}}")
        create(w, "en", w.serviceA, "Your on-demand bus is coming. Booking {{booking_reference}}")
        create(w, "en", w.serviceB, "Your demand-responsive line is coming. Booking {{booking_reference}}")
        val ref = "BK-9"; val bodies = HashMap<String, String>()
        for (lang in listOf("de", "en")) for ((name, svc) in listOf("A" to w.serviceA, "B" to w.serviceB)) {
            f.directory.languages[w.passenger] = lang
            val n = f.enqueue(w, service = svc, params = f.defaultParams(ref)); send(w, n); bodies["$lang$name"] = bodyOf(n)
        }
        assertEquals(4, bodies.values.toSet().size, "vier verschiedene Texte: zwei Sprachen x zwei Verkehrsprodukte")
        assertTrue(bodies.values.all { ref in it }, "Buchungsreferenz in allen Nachrichten")
        assertTrue("Rufbus" in bodies.getValue("deA") && "Linienbedarfsverkehr" in bodies.getValue("deB") && "on-demand" in bodies.getValue("enA") && "demand-responsive" in bodies.getValue("enB"))
    }

    @Test
    fun platzhalterwert_markup_wird_maskiert_und_fehlender_wert_ergibt_kanalfehler() {
        val w = f.world(1); f.directory.languages[w.passenger] = "de"
        create(w, "de", null, "<p>Treffpunkt: {{meeting_point}} ({{booking_reference}})</p>", NotificationChannel.EMAIL, subject = "Buchung {{booking_reference}}")
        create(w, "de", null, "Treffpunkt: {{meeting_point}} ({{booking_reference}})", NotificationChannel.SMS)
        val evil = "<script>alert('x')</script> & \"quoted\" {{booking_reference}}\n"
        val mail = f.enqueue(w, channel = NotificationChannel.EMAIL, params = f.defaultParams("BK-1") + ("meeting_point" to evil)); send(w, mail)
        val sms = f.enqueue(w, channel = NotificationChannel.SMS, params = f.defaultParams("BK-2") + ("meeting_point" to evil)); send(w, sms)
        val m = f.gateway.sent.single { it.messageId == mail }
        assertFalse("<script>" in m.body, "kein Markup in der E-Mail"); assertTrue("&lt;script&gt;" in m.body && "&quot;quoted&quot;" in m.body && "&amp;" in m.body, m.body)
        assertTrue(m.body.startsWith("<p>") && m.body.endsWith("(BK-1)</p>"), "Vorlagenmarkup bleibt, Wert wird nicht erneut ausgewertet: ${m.body}")
        assertEquals("Buchung BK-1", m.subject)
        val s = f.gateway.sent.single { it.messageId == sms }.body
        assertFalse('\n' in s, "keine Steuerzeichen in SMS"); assertTrue(s.endsWith("(BK-2)"), "{{booking_reference}} im Wert wird nicht expandiert: $s")
        assertEquals(1, Regex("BK-2").findAll(s).count(), "Wert wurde nicht erneut als Platzhalter ausgewertet")

        // fehlender oder ungültiger Pflichtwert -> Kanalfehler, nie eine halbe Nachricht
        val before = f.gateway.sent.size
        val noRef = f.enqueue(w, params = f.defaultParams().minus("booking_reference"))
        val badRef = f.enqueue(w, params = f.defaultParams("BK 1<b>"))
        val noPoint = f.enqueue(w, params = f.defaultParams().minus("meeting_point"))
        f.dispatcher().runTenant(w.tenant)
        for (n in listOf(noRef, badRef, noPoint)) { assertEquals("FAILED", f.stateOf(n)); assertEquals("TEMPLATE_UNAVAILABLE", f.failureOf(n)) }
        assertEquals(before, f.gateway.sent.size)
        // unbekannte Platzhalter beim Anlegen des Auftrags
        val e = assertThrows(ProblemException::class.java) { f.enqueue(w, params = f.defaultParams() + ("passenger_name" to "Max")) }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code)
        assertThrows(ProblemException::class.java) { f.enqueue(w, eventType = "marketing.newsletter") }
    }

    @Test
    fun versendete_nachricht_behaelt_vorlagenrevision() {
        val w = f.world(1); f.directory.languages[w.passenger] = "de"
        val r1 = create(w, "de", null, "V1 {{booking_reference}}")
        val sent = f.enqueue(w); send(w, sent)
        val r2 = create(w, "de", null, "V2 {{booking_reference}}")
        assertEquals(r1.id.toString(), f.strings("SELECT template_revision_id::text FROM taktovia.notification WHERE id = ?", sent).single(), "versendete Nachricht verweist auf Revision 1")
        assertEquals(1L, r1.revision); assertEquals(2L, r2.revision)
        val next = f.enqueue(w); send(w, next)
        assertEquals(r2.id.toString(), f.strings("SELECT template_revision_id::text FROM taktovia.notification WHERE id = ?", next).single())
        // Wiederholung bleibt bei der gebundenen Revision, auch wenn inzwischen eine neuere gilt
        f.gateway.script.add(ScriptedGateway.Step.REJECT_TEMPORARY)
        val retry = f.enqueue(w); f.dispatcher().runTenant(w.tenant); assertEquals("RETRY_WAIT", f.stateOf(retry))
        create(w, "de", null, "V3 {{booking_reference}}")
        f.clock.advance(Duration.ofMinutes(11)); f.dispatcher().runTenant(w.tenant)
        assertEquals("DELIVERED", f.stateOf(retry))
        assertTrue(f.gateway.sent.filter { it.messageId == retry }.all { it.body.startsWith("V2") }, "beide Versuche mit der gebundenen Revision 2")
        // die Bindung ist in der Datenbank unveränderlich
        assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) {
            f.tx.run(w.tenant) { j -> j.update("UPDATE taktovia.notification SET template_revision_id = ? WHERE id = ?", r1.id, next) }
        }
        // Revisionen selbst sind unveränderlich (auch für den Eigentümer)
        assertThrows(Exception::class.java) { f.exec("UPDATE taktovia.channel_template_revision SET body_template = 'x {{booking_reference}}' WHERE id = ?", r1.id) }
        assertEquals("V1 {{booking_reference}}", f.api.templates.get(f.context(w.tenant), r1.id).bodyTemplate)
    }

    @Test
    fun sprachwechsel_und_aktivierung_parallel_zum_rendering_konsistent() {
        val w = f.world(1)
        // Vorlagen tragen ihre Revisionsnummer und Sprache im Text; jede Nachricht muss zu genau einer Revision und einer Sprache passen
        val stop = AtomicBoolean(false); val created = AtomicInteger(1)
        create(w, "de", null, "de r1 {{booking_reference}}"); create(w, "en", null, "en r1 {{booking_reference}}")
        val pool = Executors.newFixedThreadPool(4)
        try {
            val activator = pool.submit(Callable {
                while (!stop.get()) { val r = created.incrementAndGet(); create(w, "de", null, "de r$r {{booking_reference}}") ; create(w, "en", null, "en r$r {{booking_reference}}") }
            })
            val switcher = pool.submit(Callable { var i = 0; while (!stop.get()) { f.directory.languages[w.passenger] = if (i++ % 2 == 0) "de-DE" else "en-GB" } })
            val ids = (1..40).map { f.enqueue(w, params = f.defaultParams("BK-$it")) }
            val d = f.dispatcher()
            val workers = (1..2).map { pool.submit(Callable { d.runTenant(w.tenant) }) }
            workers.forEach { it.get() }
            stop.set(true); activator.get(); switcher.get()
            for ((i, n) in ids.withIndex()) {
                assertEquals("DELIVERED", f.stateOf(n))
                val body = bodyOf(n)
                val m = Regex("^(de|en) r(\\d+) BK-(\\d+)$").matchEntire(body) ?: error("Nachricht nicht konsistent: $body")
                assertEquals("BK-${i + 1}", "BK-" + m.groupValues[3], "Buchungsreferenz nie verloren")
                val row = f.strings("SELECT t.language || ' ' || t.revision FROM taktovia.notification n JOIN taktovia.channel_template_revision t ON t.tenant_id = n.tenant_id AND t.id = n.template_revision_id WHERE n.id = ?", n).single()
                assertEquals(m.groupValues[1] + " " + m.groupValues[2], row.replace(Regex("^(de|en)(-[A-Z]+)? "), "$1 "), "gesendeter Text = gebundene Revision (Sprache und Nummer)")
                val bound = f.strings("SELECT language FROM taktovia.notification WHERE id = ?", n).single()
                assertTrue(bound.startsWith(m.groupValues[1]), "gebundene Sprache $bound passt zum Text ${m.groupValues[1]}")
            }
        } finally { stop.set(true); pool.shutdownNow() }
    }

    @Test
    fun katalog_und_datenbank_pruefung_stimmen_ueberein() {
        val w = f.world(0)
        for (et in NotificationEventTypes.ALL) {
            assertTrue(NotificationEventTypes.isBookingRelated(et))
            assertThrows(Exception::class.java, { f.exec("INSERT INTO taktovia.channel_template_revision(tenant_id,id,event_type,language,channel,revision,body_template,valid_from,created_by) VALUES (?,?,?,'de','SMS',1,'ohne',now(),'t')", w.tenant.value, UUID.randomUUID(), et) }, et)
        }
        f.exec("INSERT INTO taktovia.channel_template_revision(tenant_id,id,event_type,language,channel,revision,body_template,valid_from,created_by) VALUES (?,?,'marketing.newsletter','de','SMS',1,'ohne',now(),'t')", w.tenant.value, UUID.randomUUID())
    }

    @Test
    fun seed_ist_idempotent_und_liefert_deutsch_und_englisch() {
        val w = f.world(1)
        val ctx = f.context(w.tenant)
        val first = f.api.templates.seedDefaults(ctx)
        assertEquals(NotificationEventTypes.ALL.size * NotificationChannel.entries.size * 2, first, "5 Ereignistypen x 3 Kanäle x 2 Sprachen")
        assertEquals(0, f.api.templates.seedDefaults(ctx), "idempotent")
        assertEquals(setOf("de", "en"), f.strings("SELECT DISTINCT language FROM taktovia.channel_template_revision WHERE tenant_id = ?", w.tenant.value).toSet())
        // jede Standardvorlage ist zulässig (Pflichtplatzhalter) und rendert mit vollständigen Werten
        for (lang in listOf("de-DE", "en-GB")) for (ch in NotificationChannel.entries) for (et in NotificationEventTypes.ALL) {
            f.directory.languages[w.passenger] = lang
            val n = f.enqueue(w, eventType = et, channel = ch); f.dispatcher().runTenant(w.tenant)
            assertEquals("DELIVERED", f.stateOf(n), "$lang $ch $et")
        }
    }
}
