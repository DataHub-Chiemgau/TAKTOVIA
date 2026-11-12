package de.taktovia.app.driver

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.app.probe.Resp
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.stafftime.api.StafftimeApi
import de.taktovia.stafftime.api.StafftimeSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-30 / LH-6.6-DRV-006 über die echte Anwendung (HTTP, Runtime-Rolle, echte Buchungen, echter Fahrtenplan): Check-in mit Boarding-Code, Fehlversuchssperre, Einmalverwendung, Offline-Semantik,
 * Fahrgastliste und der Nachweis, dass der Code nie im Klartext abgelegt wird. Die Codes werden hier über die Modul-API der Leitstelle ausgestellt (Testfixture, OP-18: der Übergabeweg an den
 * Fahrgast kommt mit WP-M7a-14). Gemeinsame Hilfe: [DriverRideSupport].
 */
class BoardingCodeCheckInTest {
    private val sup = DriverRideSupport()
    private val w = sup.w
    private val env = w.env
    private val staff = env.server.ctx.getBean(StafftimeApi::class.java)

    private val dispatcher: RequestContext = UUID.randomUUID().let { op ->
        val m = env.grant(w.tenant, op, "dispatcher")
        RequestContext(Actor(ActorType.HUMAN, IdentityEnv.idp.issuer, op.toString(), "sid-op", true), w.tenant, setOf("dispatcher"), setOf("rides:read"), emptySet(), UUID.randomUUID(), membershipIds = setOf(m))
    }

    private fun issue(r: DriverRideSupport.Ride): String = staff.boardingCodes.issue(dispatcher, r.booking.id).code.value
    private fun wrong(code: String) = (if (code == "000000") "000001" else "000000")
    private fun checkIn(r: DriverRideSupport.Ride, code: String?, stop: UUID = r.pickup, offline: Boolean = false, id: UUID = UUID.randomUUID(), occurred: Instant = Instant.now(), rev: Long = r.rev, token: String = r.d.token): Resp =
        sup.event(r, "CHECK_IN", stop, id = id, offline = offline, occurred = occurred, rev = rev, token = token, extra = if (code != null) ""","boarding_code":"$code"""" else "")

    private fun attempts(r: DriverRideSupport.Ride) = env.string("SELECT failed_attempts::text FROM taktovia.boarding_code WHERE booking_id = ?", r.booking.id).single().toInt()
    private fun usedBy(r: DriverRideSupport.Ride) = env.string("SELECT coalesce(used_event_id::text, '-') FROM taktovia.boarding_code WHERE booking_id = ?", r.booking.id).single()
    private fun violation(r: Resp) = r.json["violations"][0]["code"].asString()

    @Test
    fun gueltiger_code_ergibt_checkin_und_entwertet() {
        val r = sup.ride(); val code = issue(r)
        assertEquals("APPLIED", sup.event(r, "ARRIVAL", r.pickup).json["state"].asString())
        val id = UUID.randomUUID(); val ok = checkIn(r, code, id = id)
        assertEquals(200, ok.status, ok.text); assertEquals("APPLIED", ok.json["state"].asString())
        assertEquals("BOARDED", sup.legState(r)); assertEquals(id.toString(), usedBy(r), "der Code ist durch genau dieses Ereignis entwertet")
        assertEquals("VALID", env.string("SELECT code_result FROM taktovia.driver_event WHERE event_id = ?", id).single())
        assertFalse(ok.text.contains(code), "die Antwort enthält den Code nicht")
    }

    @Test
    fun falscher_code_abgewiesen() {
        val r = sup.ride(); val code = issue(r)
        val bad = checkIn(r, wrong(code))
        assertEquals(422, bad.status, bad.text); assertEquals("BOARDING_CODE_INVALID", violation(bad))
        assertEquals("PLANNED", sup.legState(r), "es entsteht kein Check-in"); assertEquals(emptyList<String>(), sup.journal(r, "CHECK_IN"))
        assertEquals(1, attempts(r), "der Fehlversuch bleibt gezählt, obwohl die Antwort 422 ist"); assertEquals("-", usedBy(r))
        assertEquals("APPLIED", checkIn(r, code).json["state"].asString(), "der richtige Code funktioniert danach weiter")
    }

    @Test
    fun verwendeter_code_abgewiesen() {
        val r = sup.ride(); val code = issue(r)
        assertEquals("APPLIED", checkIn(r, code).json["state"].asString())
        val again = checkIn(r, code)
        assertEquals(422, again.status, again.text); assertEquals("BOARDING_CODE_USED", violation(again))
        assertEquals("BOARDED", sup.legState(r)); assertEquals(1, sup.journal(r, "CHECK_IN").size)
    }

    @Test
    fun code_fremder_buchung_anderer_stopp_oder_fahrzeug_abgewiesen() {
        val a = sup.ride(); val b = sup.ride(); val codeA = issue(a); val codeB = issue(b)
        // der Code der Buchung A für den Check-in der Buchung B: falsch (Fehlversuch an B, nicht an A)
        val cross = sup.event(b, "CHECK_IN", b.pickup, extra = ""","boarding_code":"$codeA"""")
        assertEquals(422, cross.status, cross.text); assertEquals("BOARDING_CODE_INVALID", violation(cross)); assertEquals(1, attempts(b)); assertEquals(0, attempts(a))
        // anderer Stopp: der richtige Code am Ausstiegsstopp ergibt einen Klärfall und wird nicht entwertet
        val atDropoff = checkIn(a, codeA, stop = a.dropoff)
        assertEquals("REVIEW_REQUIRED", atDropoff.json["state"].asString()); assertEquals("OUTSIDE_STOP", atDropoff.json["reason_code"].asString())
        assertEquals("-", usedBy(a), "kein Check-in, kein Verbrauch"); assertEquals("PLANNED", sup.legState(a))
        // anderes Fahrzeug bzw. andere Schicht: die Buchung gehört nicht zum Plan dieser Schicht, 404, und zählt keinen Fehlversuch an der fremden Buchung
        val foreign = sup.event(b, "CHECK_IN", b.pickup, booking = a.booking.id, extra = ""","boarding_code":"${wrong(codeA)}"""")
        assertEquals(404, foreign.status, foreign.text); assertEquals(0, attempts(a), "ein fremder Fahrer kann fremde Codes nicht sperren")
        assertEquals("APPLIED", checkIn(a, codeA).json["state"].asString())
    }

    @Test
    fun fehlversuchsgrenze_sperrt_und_meldet_leitstelle() {
        val r = sup.ride(); val code = issue(r); val bad = wrong(code)
        repeat(4) { assertEquals("BOARDING_CODE_INVALID", violation(checkIn(r, bad))) }
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'driver.boarding_code_locked' AND payload->>'booking_id' = ?", r.booking.id.toString()))
        assertEquals("BOARDING_CODE_INVALID", violation(checkIn(r, bad)), "der fünfte Fehlversuch")
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'driver.boarding_code_locked' AND payload->>'booking_id' = ?", r.booking.id.toString()), "Hinweis an die Leitstelle")
        val hint = env.string("SELECT payload::text FROM taktovia.outbox WHERE event_type = 'driver.boarding_code_locked' AND payload->>'booking_id' = ?", r.booking.id.toString()).single()
        val schema = com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.dialect.Dialects.getDraft202012())
            .getSchema(java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("taktovia.repoRoot")).resolve("api/events/driver.boarding_code_locked.v1.schema.json")))
        assertEquals(emptyList<Any>(), schema.validate(hint, com.networknt.schema.InputFormat.JSON).toList().map { it.message }, "Hinweisereignis entspricht dem Schema")
        val locked = checkIn(r, code)
        assertEquals(422, locked.status, locked.text); assertEquals("BOARDING_CODE_LOCKED", violation(locked), "auch der richtige Code ist gesperrt")
        assertEquals("PLANNED", sup.legState(r)); assertEquals(5, attempts(r))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.boarding_code WHERE booking_id = ? AND locked_at IS NOT NULL", r.booking.id))
    }

    @Test
    fun code_stornierter_oder_abgelaufener_buchung_ungueltig() {
        // storniert: der Fahrgast storniert, der Plan verliert die Buchung; der Code führt zu keinem Check-in und wird nicht entwertet
        val c = sup.ride(); val codeC = issue(c)
        assertEquals(200, w.s.cancel(c.booking.id.toString(), c.booking.held.account.token, ifMatch = sup.revision(c)).status)
        val afterCancel = checkIn(c, codeC, rev = env.string("SELECT revision::text FROM taktovia.vehicle_run WHERE id = ?", c.d.run).single().toLong())
        assertTrue(afterCancel.status == 404 || afterCancel.json["state"].asString() == "REVIEW_REQUIRED", afterCancel.text)
        assertEquals("CANCELED", sup.bookingState(c)); assertEquals("PLANNED", sup.legState(c)); assertEquals("-", usedBy(c))
        // abgelaufen: ein vor zwei Tagen ausgestellter Code (24 Stunden gültig) ist ungültig
        val e = sup.ride()
        val past = StafftimeApi.create(env.server.ctx.getBean(ContextTransactions::class.java), JdbcAuditPort(), Outbox(), Clock.fixed(Instant.now().minus(Duration.ofDays(2)), ZoneOffset.UTC), StafftimeSettings())
        val codeE = past.boardingCodes.issue(dispatcher, e.booking.id).code.value
        val expired = checkIn(e, codeE)
        assertEquals(422, expired.status, expired.text); assertEquals("BOARDING_CODE_INVALID", violation(expired)); assertEquals("PLANNED", sup.legState(e)); assertEquals("-", usedBy(e))
    }

    @Test
    fun fahrgastliste_nur_eigene_schicht_ohne_kontakt_und_zahlungsdaten() {
        val d = w.driver(); val a = w.booking(); val b = w.booking(); sup.confirmed(a); sup.confirmed(b)
        val rev = w.plan(d.run, listOf(a, b)); val ra = sup.rideOf(d, a, rev)
        val other = sup.ride()
        val list = env.get("/v1/driver/shifts/${d.shift}/passengers", d.token)
        assertEquals(200, list.status, list.text)
        val refs = list.json["passengers"].values().asSequence().map { it["booking_ref"].asString() to it["next_stop"].asBoolean() }.toList()
        val numbers = listOf(a.id, b.id).map { env.string("SELECT booking_no FROM taktovia.booking WHERE id = ?", it).single() }
        assertEquals(listOf(numbers[0] to false, numbers[1] to true), refs, "aktueller Stopp: Buchung a, nächster Stopp: Buchung b")
        assertTrue(list.json["passengers"].values().asSequence().all { it["persons"].asInt() >= 1 && it.propertyNames().toSet() == setOf("route_stop_id", "next_stop", "booking_id", "booking_ref", "persons") }, "nur Kürzel und Personenzahl")
        assertFalse(Regex("(?i)email|phone|telefon|contact|iban|payment|card|name\"|code").containsMatchIn(list.text.replace("booking_ref", "").replace("current_route_stop_id", "")), "keine Kontakt-, Zahlungs- oder Codedaten: ${list.text}")
        assertFalse(list.text.contains(other.booking.id.toString()), "keine Fahrgäste fremder Schichten")
        assertEquals(404, env.get("/v1/driver/shifts/${other.d.shift}/passengers", d.token).status, "fremde Schicht")
        assertEquals(404, env.get("/v1/driver/shifts/${d.shift}/passengers", other.d.token).status)
        // Fortschritt: nach Ankunft und Check-in von a bleibt b; nach der Ankunft am zweiten Stopp ist b der aktuelle Fahrgast
        sup.event(ra, "ARRIVAL", ra.pickup); checkIn(ra, null)
        val afterA = env.get("/v1/driver/shifts/${d.shift}/passengers", d.token).json["passengers"].values().asSequence().map { it["booking_ref"].asString() to it["next_stop"].asBoolean() }.toList()
        assertEquals(listOf(numbers[1] to true), afterA)
        val stop1 = UUID.fromString(env.string("SELECT id::text FROM taktovia.route_stop WHERE run_id = ? AND run_revision = ? AND seq = 1", d.run, rev).single())
        sup.event(ra, "ARRIVAL", stop1)
        val atB = env.get("/v1/driver/shifts/${d.shift}/passengers", d.token).json["passengers"].values().asSequence().map { it["booking_ref"].asString() to it["next_stop"].asBoolean() }.toList()
        assertEquals(listOf(numbers[1] to false), atB)
    }

    @Test
    fun offline_erfasster_code_gilt_erst_nach_serverpruefung() {
        val r = sup.ride(); val code = issue(r)
        val badOffline = checkIn(r, wrong(code), offline = true, occurred = Instant.now().minusSeconds(60))
        assertEquals(200, badOffline.status, badOffline.text); assertEquals("REVIEW_REQUIRED", badOffline.json["state"].asString()); assertEquals("CODE_INVALID", badOffline.json["reason_code"].asString())
        assertEquals("PLANNED", sup.legState(r), "ein offline erfasster, falscher Code ergibt keinen Check-in"); assertEquals(1, attempts(r))
        val okOffline = checkIn(r, code, offline = true, occurred = Instant.now().minusSeconds(30))
        assertEquals("APPLIED", okOffline.json["state"].asString()); assertEquals("BOARDED", sup.legState(r))
    }

    @Test
    fun offline_doppelt_verwendeter_code_ein_checkin_ein_klaerfall() {
        val r = sup.ride(); val code = issue(r)
        val first = checkIn(r, code, offline = true, occurred = Instant.now().minusSeconds(90)); val second = checkIn(r, code, offline = true, occurred = Instant.now().minusSeconds(80))
        assertEquals("APPLIED", first.json["state"].asString()); assertEquals("REVIEW_REQUIRED", second.json["state"].asString()); assertEquals("CODE_ALREADY_USED", second.json["reason_code"].asString())
        assertEquals(listOf("APPLIED:-", "REVIEW_REQUIRED:CODE_ALREADY_USED"), sup.journal(r, "CHECK_IN"))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'driver.review_opened' AND payload->>'reason_code' = 'CODE_ALREADY_USED' AND payload->>'booking_id' = ?", r.booking.id.toString()), "ein Klärfall")
        assertEquals("BOARDED", sup.legState(r))
    }

    @Test
    fun derselbe_code_zwei_geraete_ein_checkin() {
        repeat(4) { round ->
            val r = sup.ride(); val code = issue(r)
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            val ids = listOf(UUID.randomUUID(), UUID.randomUUID())
            val fs = ids.map { id -> pool.submit<Resp> { go.await(); checkIn(r, code, id = id) } }
            go.countDown(); val rs = fs.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            val applied = rs.filter { it.status == 200 && it.json["state"].asString() == "APPLIED" }
            assertEquals(1, applied.size, "Runde $round: ${rs.map { it.status to it.text }}")
            val other = rs.single { it !== applied.single() }
            assertEquals(422, other.status, other.text); assertEquals("BOARDING_CODE_USED", violation(other))
            assertEquals(applied.single().json["event_id"].asString(), usedBy(r)); assertEquals(1, sup.journal(r, "CHECK_IN").size); assertEquals("BOARDED", sup.legState(r))
        }
    }

    @Test
    fun storno_parallel_zu_checkin() {
        repeat(4) { round ->
            val r = sup.ride(); val code = issue(r)
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            val ci = pool.submit<Resp> { go.await(); checkIn(r, code) }
            val ca = pool.submit<Resp> { go.await(); w.s.cancel(r.booking.id.toString(), r.booking.held.account.token, ifMatch = sup.revision(r)) }
            go.countDown(); val c = ci.get(60, TimeUnit.SECONDS); val s = ca.get(60, TimeUnit.SECONDS); pool.shutdown()
            if (s.status == 200) {
                assertEquals("CANCELED", sup.bookingState(r), "Runde $round"); assertEquals("PLANNED", sup.legState(r)); assertEquals("-", usedBy(r), "kein Check-in, kein Verbrauch")
                assertTrue(c.status == 404 || c.json["state"].asString() == "REVIEW_REQUIRED", c.text)
            } else {
                assertEquals(409, s.status, s.text); assertEquals("APPLIED", c.json["state"].asString(), c.text); assertEquals("BOARDED", sup.legState(r)); assertTrue(usedBy(r) != "-")
            }
        }
    }

    @Test
    fun code_nie_im_klartext() {
        val r = sup.ride(); val code = issue(r)
        // ein falscher, ein richtiger Versuch (offline und online), ein verwendeter Code
        checkIn(r, wrong(code)); sup.event(r, "ARRIVAL", r.pickup); checkIn(r, code); checkIn(r, code)
        val needle = "(^|[^0-9a-fA-F.])$code([^0-9a-fA-F]|$)"
        val tables = listOf("boarding_code", "driver_event", "outbox", "audit_event", "idempotency_record", "inbox", "booking_status_history")
        assertEquals(1, env.count("SELECT count(*) FROM (SELECT ?::text AS c) x WHERE x::text ~ ?", "($code)".drop(1).dropLast(1), needle), "Positivkontrolle: das Suchmuster findet einen Code, wenn er in einer Zeile steht")
        for (t in tables) {
            val hits = env.count("SELECT count(*) FROM taktovia.$t x WHERE x::text ~ ?", needle)   // sql-lint: ok feste Tabellenliste des Tests
            assertEquals(0, hits, "Klartext-Code in $t")
        }
        val stored = env.string("SELECT code_hash FROM taktovia.boarding_code WHERE booking_id = ?", r.booking.id).single()
        assertTrue(stored.startsWith("pbkdf2-sha256$") && !stored.contains(code), "nur der gesalzene Hash steht in der Datenbank")
        assertFalse(env.string("SELECT payload::text FROM taktovia.outbox WHERE event_type LIKE 'driver.%' AND tenant_id = ?", w.tenant.value).any { it.contains(code) }, "weder Ereignisse noch Metriken enthalten den Code")
        // die Fahrerprojektion enthält ihn ebenfalls nicht
        assertFalse(env.get("/v1/driver/shifts/${r.d.shift}/run", r.d.token).text.contains(code)); assertFalse(env.get("/v1/driver/shifts/${r.d.shift}/passengers", r.d.token).text.contains(code))
    }

    @Test
    fun verbraucher_des_bestaetigungsereignisses_stellt_genau_einen_code_aus() {
        val r = sup.ride()
        val delivered = mutableListOf<de.taktovia.stafftime.api.IssuedBoardingCode>()
        val handler = staff.boardingCodes.eventConsumer { _, issued -> delivered += issued }
        val ctxTx = env.server.ctx.getBean(ContextTransactions::class.java)
        val envelope = de.taktovia.platform.outbox.EventEnvelope(UUID.randomUUID(), "booking.confirmed", "1.0", r.booking.id, 1, Instant.now(), Instant.now(), w.tenant.value, w.tenant.value, UUID.randomUUID(),
            tools.jackson.databind.json.JsonMapper.builder().build().readTree("""{"booking_id":"${r.booking.id}"}"""))
        repeat(2) { ctxTx.runSystem(w.tenant) { j -> handler.handle(j, envelope) } }   // Wiederholung (Inbox liefert mindestens einmal): kein zweiter Code
        assertEquals(1, delivered.size); assertEquals(1, env.count("SELECT count(*) FROM taktovia.boarding_code WHERE booking_id = ?", r.booking.id))
        assertEquals("APPLIED", checkIn(r, delivered.single().code.value).json["state"].asString(), "der ausgestellte Code ist der prüfbare Code")
    }
}
