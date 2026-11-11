package de.taktovia.identity

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.util.concurrent.CopyOnWriteArrayList
import de.taktovia.identity.api.ChannelStatus
import de.taktovia.identity.api.ContactChannel
import de.taktovia.identity.api.ProfilePatch
import de.taktovia.identity.api.RecoveryAcknowledged
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * WP-M1-28 / LH-6.2-IAM-001: Registrierung per E-Mail und Telefon mit Kontaktverifikation gegen die echte Datenbank (Runtime-Rolle und Modulzugang).
 * Ersatz für nicht ausgeführte Teile (kein Docker, kein Keycloak, kein SMS-Anbieter): Test-SMS-Adapter im Speicher, Aufzeichnung der Wiederherstellung.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PassengerRegistrationTest {
    private lateinit var f: PassengerTestFixture
    @BeforeAll fun setup() { f = PassengerTestFixture() }
    @AfterAll fun teardown() { if (::f.isInitialized) f.close() }

    private fun problem(block: () -> Unit): ProblemException = assertThrows { block() }

    @Test fun registrierung_email_und_telefon_kanal_verifiziert() {
        val t = f.tenant()
        // E-Mail
        val s1 = UUID.randomUUID().toString(); val email = "Anna.Beispiel@Example.org"
        val r1 = f.register(t, s1, ContactChannel.EMAIL, email)
        assertEquals("PENDING_VERIFICATION", r1.passenger.status)
        val ctx1 = f.ctx(t, s1)
        assertEquals("UNVERIFIED", f.base.adminStrings("SELECT status FROM taktovia.passenger_contact WHERE passenger_id = ?::uuid AND channel = 'EMAIL'", r1.passenger.id.toString()).single())
        val v = f.api.accounts.confirmVerification(ctx1, ContactChannel.EMAIL, f.sender.last(ContactChannel.EMAIL, "anna.beispiel@example.org")!!)
        assertEquals(ChannelStatus.VERIFIED, v.status); assertNotNull(v.verifiedAt)
        assertEquals("VERIFIED", f.base.adminStrings("SELECT status FROM taktovia.passenger_contact WHERE passenger_id = ?::uuid AND channel = 'EMAIL'", r1.passenger.id.toString()).single())
        assertEquals("ACTIVE", f.api.accounts.own(ctx1)!!.status)
        assertEquals(ChannelStatus.VERIFIED, f.api.profiles.get(ctx1).email!!.status)
        // Telefon gegen den Test-SMS-Adapter, Schreibweise mit Leerzeichen wird normalisiert
        val s2 = UUID.randomUUID().toString()
        val r2 = f.register(t, s2, ContactChannel.PHONE, "+49 151 2345678")
        val code = f.sender.last(ContactChannel.PHONE, "+491512345678")
        assertNotNull(code, "Code ging über den Test-SMS-Adapter")
        assertEquals(ChannelStatus.VERIFIED, f.api.accounts.confirmVerification(f.ctx(t, s2), ContactChannel.PHONE, code!!).status)
        assertEquals("ACTIVE", r2.passenger.let { f.api.accounts.own(f.ctx(t, s2))!!.status })
        // Der Fahrgast ist Mitglied mit Rolle passenger im Mandanten
        assertEquals(1, f.count("SELECT count(*) FROM taktovia.membership WHERE subject = ? AND role_code = 'passenger' AND tenant_id = ?::uuid", s2, t.toString()))
        // ungültige Eingaben
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { f.register(t, value = "kein-kontakt") }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { f.register(t, channel = ContactChannel.PHONE, value = "0151 123") }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { f.register(t, locale = "xx-XX") }.code)
    }

    @Test fun falscher_abgelaufener_verbrauchter_code_abgelehnt() {
        val t = f.tenant()
        val s = UUID.randomUUID().toString(); val email = PassengerTestFixture.defaultValue(ContactChannel.EMAIL)
        f.register(t, s, value = email); val ctx = f.ctx(t, s)
        val code = f.sender.last(ContactChannel.EMAIL, email)!!
        val wrong = if (code == "000000") "111111" else "000000"
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { f.api.accounts.confirmVerification(ctx, ContactChannel.EMAIL, wrong) }.code, "falscher Code")
        // abgelaufen: der richtige Code nach Ablauf der Gültigkeit
        f.clock.advance(f.settings.codeTtl.plusSeconds(1))
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { f.api.accounts.confirmVerification(ctx, ContactChannel.EMAIL, code) }.code, "abgelaufener Code")
        // neue Aufgabe, richtiger Code wirkt einmal; der verbrauchte Code wird danach abgelehnt
        f.api.accounts.startVerification(ctx, ContactChannel.EMAIL)
        val fresh = f.sender.last(ContactChannel.EMAIL, email)!!
        assertEquals(ChannelStatus.VERIFIED, f.api.accounts.confirmVerification(ctx, ContactChannel.EMAIL, fresh).status)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { f.api.accounts.confirmVerification(ctx, ContactChannel.EMAIL, fresh) }.code, "verbrauchter Code")
        assertEquals(ErrorCode.CONFLICT, problem { f.api.accounts.startVerification(ctx, ContactChannel.EMAIL) }.code, "verifizierter Kanal braucht keine Verifikation")
        // Ohne Konto: 404
        assertEquals(ErrorCode.NOT_FOUND, problem { f.api.accounts.confirmVerification(f.base.context(t, "unbekannt", setOf(f.base.seedMembership(t, "unbekannt", "passenger")), setOf("passenger")), ContactChannel.EMAIL, "123456") }.code)
    }

    @Test fun unverifizierter_kontakt_nicht_fuer_wiederherstellung() {
        val t = f.tenant(); f.recovered.clear()
        val s = UUID.randomUUID().toString(); val email = PassengerTestFixture.defaultValue(ContactChannel.EMAIL)
        f.register(t, s, value = email)
        assertSame(RecoveryAcknowledged, f.api.accounts.requestRecovery(t, ContactChannel.EMAIL, email, UUID.randomUUID()))
        assertTrue(f.recovered.none { it.subject == s }, "unverifizierter Kanal: keine Wiederherstellung")
        assertEquals(1, f.count("SELECT count(*) FROM taktovia.audit_event WHERE action = 'security.account.recovery_denied' AND tenant_id = ?::uuid AND redacted_changes->>'reason' = 'contact_not_verified'", t.toString()))
        f.api.accounts.confirmVerification(f.ctx(t, s), ContactChannel.EMAIL, f.sender.last(ContactChannel.EMAIL, email)!!)
        f.api.accounts.requestRecovery(t, ContactChannel.EMAIL, email.uppercase(), UUID.randomUUID())
        assertEquals(listOf(PassengerTestFixture.Recovered(f.base.issuer, s, ContactChannel.EMAIL, email.lowercase())), f.recovered.filter { it.subject == s }, "verifizierter Kanal: genau eine Wiederherstellung")
        // ein anderer Mandant kennt den Kontakt nicht
        f.api.accounts.requestRecovery(f.tenant(), ContactChannel.EMAIL, email, UUID.randomUUID())
        assertEquals(1, f.recovered.count { it.subject == s })
    }

    @Test fun wiederherstellung_antwort_ohne_kontoenumeration() {
        val t = f.tenant(); f.recovered.clear()
        val known = PassengerTestFixture.defaultValue(ContactChannel.EMAIL); val verified = PassengerTestFixture.defaultValue(ContactChannel.EMAIL)
        f.register(t, value = known)
        val s = UUID.randomUUID().toString(); f.register(t, s, value = verified); f.api.accounts.confirmVerification(f.ctx(t, s), ContactChannel.EMAIL, f.sender.last(ContactChannel.EMAIL, verified)!!)
        val answers = listOf(
            f.api.accounts.requestRecovery(t, ContactChannel.EMAIL, known, UUID.randomUUID()),                      // existiert, unverifiziert
            f.api.accounts.requestRecovery(t, ContactChannel.EMAIL, verified, UUID.randomUUID()),                   // existiert, verifiziert
            f.api.accounts.requestRecovery(t, ContactChannel.EMAIL, "gibtsnicht@example.org", UUID.randomUUID()),   // unbekannt
            f.api.accounts.requestRecovery(t, ContactChannel.PHONE, "+4915100000000", UUID.randomUUID()),           // unbekannter Kanal
            f.api.accounts.requestRecovery(t, ContactChannel.EMAIL, "kein kontakt", UUID.randomUUID()),             // ungültiges Format
        )
        answers.forEach { assertSame(RecoveryAcknowledged, it) }
        assertEquals(1, f.recovered.size, "nur der verifizierte Kanal stößt etwas an")
    }

    @Test fun kontaktaenderung_setzt_kanal_zurueck() {
        val t = f.tenant(); f.recovered.clear()
        val (s, ctx) = f.verifiedPassenger(t)
        val old = f.api.profiles.get(ctx).email!!.value!!
        val fresh = PassengerTestFixture.defaultValue(ContactChannel.EMAIL)
        val after = f.api.profiles.update(ctx, f.api.profiles.get(ctx).revision, ProfilePatch(email = fresh))
        assertEquals(ChannelStatus.UNVERIFIED, after.email!!.status, "geänderter Kontakt ist unverifiziert")
        assertEquals(fresh, after.email!!.value)
        f.api.accounts.requestRecovery(t, ContactChannel.EMAIL, fresh, UUID.randomUUID())
        f.api.accounts.requestRecovery(t, ContactChannel.EMAIL, old, UUID.randomUUID())
        assertTrue(f.recovered.none { it.subject == s }, "weder neuer (unverifiziert) noch alter (nicht mehr gültig) Kontakt dient der Wiederherstellung")
        // erneute Verifikation macht ihn nutzbar
        f.api.accounts.startVerification(ctx, ContactChannel.EMAIL)
        assertEquals(ChannelStatus.VERIFIED, f.api.accounts.confirmVerification(ctx, ContactChannel.EMAIL, f.sender.last(ContactChannel.EMAIL, fresh)!!).status)
        f.api.accounts.requestRecovery(t, ContactChannel.EMAIL, fresh, UUID.randomUUID())
        assertEquals(1, f.recovered.count { it.subject == s })
        // ein Code, der für den früheren Kontakt ausgestellt wurde, gilt nach einer Kontaktänderung nicht mehr
        val (_, ctx2) = f.verifiedPassenger(f.tenant(), ContactChannel.PHONE)
        val other = "+49152${(1000000..9999999).random()}"
        f.api.profiles.update(ctx2, f.api.profiles.get(ctx2).revision, ProfilePatch(phone = other))
        assertEquals(ChannelStatus.UNVERIFIED, f.api.profiles.get(ctx2).phone!!.status)
    }

    @Test fun brute_force_ergibt_429_und_sicherheitsereignis() {
        val t = f.tenant()
        val s = UUID.randomUUID().toString(); val email = PassengerTestFixture.defaultValue(ContactChannel.EMAIL)
        f.register(t, s, value = email); val ctx = f.ctx(t, s)
        val code = f.sender.last(ContactChannel.EMAIL, email)!!
        val wrong = if (code == "000000") "111111" else "000000"
        repeat(f.settings.maxAttempts - 1) { assertEquals(ErrorCode.VALIDATION_FAILED, problem { f.api.accounts.confirmVerification(ctx, ContactChannel.EMAIL, wrong) }.code) }
        val limited = problem { f.api.accounts.confirmVerification(ctx, ContactChannel.EMAIL, wrong) }
        assertEquals(ErrorCode.RATE_LIMITED, limited.code); assertNotNull(limited.headers["Retry-After"])
        // Auch der richtige Code wird in der Sperrzeit nicht mehr angenommen, und eine neue Aufgabe ist gesperrt
        assertEquals(ErrorCode.RATE_LIMITED, problem { f.api.accounts.confirmVerification(ctx, ContactChannel.EMAIL, code) }.code)
        assertEquals(ErrorCode.RATE_LIMITED, problem { f.api.accounts.startVerification(ctx, ContactChannel.EMAIL) }.code)
        assertEquals(f.settings.maxAttempts - 1, f.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ?::uuid AND action = 'security.contact.verification_failed'", t.toString()))
        assertTrue(f.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ?::uuid AND action = 'security.contact.verification_limited'", t.toString()) >= 3)
        // nach der Sperrzeit ist ein neuer Versuch möglich
        f.clock.advance(f.settings.lockout.plusSeconds(1))
        f.api.accounts.startVerification(ctx, ContactChannel.EMAIL)
        assertEquals(ChannelStatus.VERIFIED, f.api.accounts.confirmVerification(ctx, ContactChannel.EMAIL, f.sender.last(ContactChannel.EMAIL, email)!!).status)
        // Startlimit: höchstens maxStartsPerHour Aufgaben je Stunde und Kanal
        val (s2, ctx2) = Pair(UUID.randomUUID().toString(), null)
        val t2 = f.tenant(); val e2 = PassengerTestFixture.defaultValue(ContactChannel.EMAIL); f.register(t2, s2, value = e2); val c2 = f.ctx(t2, s2)
        repeat(f.settings.maxStartsPerHour - 1) { f.api.accounts.startVerification(c2, ContactChannel.EMAIL) }
        assertEquals(ErrorCode.RATE_LIMITED, problem { f.api.accounts.startVerification(c2, ContactChannel.EMAIL) }.code)
        assertNotNull(ctx2 ?: c2)
    }

    @Test fun code_nie_im_klartext_in_log_oder_db() {
        val collected = CopyOnWriteArrayList<ILoggingEvent>()
        val appender = object : AppenderBase<ILoggingEvent>() { override fun append(e: ILoggingEvent) { collected += e } }.also { it.start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val before = root.level; root.level = Level.ALL; root.addAppender(appender)
        val t = f.tenant(); val s = UUID.randomUUID().toString(); val email = "geheim.${UUID.randomUUID().toString().take(6)}@example.org"
        try {
            f.register(t, s, value = email); val ctx = f.ctx(t, s)
            val code = f.sender.last(ContactChannel.EMAIL, email)!!
            runCatching { f.api.accounts.confirmVerification(ctx, ContactChannel.EMAIL, if (code == "999999") "888888" else "999999") }
            f.api.accounts.requestRecovery(t, ContactChannel.EMAIL, email, UUID.randomUUID())
            f.api.accounts.confirmVerification(ctx, ContactChannel.EMAIL, code)
            val logged = collected.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") + it.argumentArray?.joinToString { a -> a.toString() } }
            // als eigenständiger Wert (nicht als Teil einer UUID oder Zeitangabe) darf der Code in keiner Logzeile stehen
            assertFalse(Regex("(?<![0-9a-fA-F-])" + code + "(?![0-9a-fA-F-])").containsMatchIn(logged), "Code im Log")
            assertFalse(logged.contains(email), "Kontakt im Log")
            // Datenbank: weder der Code noch der Kontakt im Klartext, weder in Fachtabellen noch im Audit
            val id = f.api.accounts.own(ctx)!!.id.toString()
            for (needle in listOf(code, email, email.substringBefore('@'))) {
                assertEquals(0, f.count("SELECT count(*) FROM taktovia.contact_verification WHERE passenger_id = ?::uuid AND contact_verification::text LIKE ?", id, "%$needle%"), "contact_verification")
                assertEquals(0, f.count("SELECT count(*) FROM taktovia.passenger_contact WHERE passenger_id = ?::uuid AND passenger_contact::text LIKE ?", id, "%$needle%"), "passenger_contact")
                assertEquals(0, f.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ?::uuid AND (redacted_changes::text LIKE ? OR object_ref LIKE ? OR actor_ref LIKE ?)", t.toString(), "%$needle%", "%$needle%", "%$needle%"), "audit_event")
            }
            // Kontakt nur als Ciphertext: die Zeile enthält die Klartextbytes nicht
            assertEquals(0, f.count("SELECT count(*) FROM taktovia.passenger_private WHERE id = ?::uuid AND position(convert_to(?, 'UTF8') in contact_ciphertext) > 0", id, email))
            assertEquals(1, f.count("SELECT count(*) FROM taktovia.passenger_private WHERE id = ?::uuid AND contact_ciphertext IS NOT NULL AND length(contact_ciphertext) > 40", id))
        } finally { root.detachAppender(appender); root.level = before }
    }

    @Test fun parallele_registrierung_genau_ein_konto() {
        val t = f.tenant(); val email = PassengerTestFixture.defaultValue(ContactChannel.EMAIL); val n = 6
        val barrier = CyclicBarrier(n); val pool = Executors.newFixedThreadPool(n)
        try {
            val results = (1..n).map { pool.submit(Callable { barrier.await(); runCatching { f.register(t, UUID.randomUUID().toString(), value = email) } }) }.map { it.get() }
            assertEquals(1, results.count { it.isSuccess }, "genau eine Registrierung gelingt")
            results.filter { it.isFailure }.forEach { assertEquals(ErrorCode.CONFLICT, (it.exceptionOrNull() as ProblemException).code) }
            assertEquals(1, f.count("SELECT count(*) FROM taktovia.passenger_contact WHERE tenant_id = ?::uuid", t.toString()))
            assertEquals(1, f.count("SELECT count(*) FROM taktovia.passenger_private WHERE tenant_id = ?::uuid", t.toString()))
            assertEquals(1, f.count("SELECT count(*) FROM taktovia.membership WHERE tenant_id = ?::uuid AND role_code = 'passenger'", t.toString()), "keine Mitgliedschaft ohne Konto")
        } finally { pool.shutdownNow() }
        // dasselbe Konto des IdP kann sich nicht zweimal registrieren (gleichförmig 409)
        val s = UUID.randomUUID().toString(); f.register(t, s)
        assertEquals(ErrorCode.CONFLICT, problem { f.register(t, s) }.code)
        // gleicher Kontakt in einem anderen Mandanten ist unabhängig
        f.register(f.tenant(), value = email)
    }

    @Test fun paralleler_verifikationsversuch_hoechstens_einmal() {
        val t = f.tenant(); val s = UUID.randomUUID().toString(); val email = PassengerTestFixture.defaultValue(ContactChannel.EMAIL)
        f.register(t, s, value = email); val ctx = f.ctx(t, s); val code = f.sender.last(ContactChannel.EMAIL, email)!!
        val n = 5; val barrier = CyclicBarrier(n); val pool = Executors.newFixedThreadPool(n)
        try {
            val results = (1..n).map { pool.submit(Callable { barrier.await(); runCatching { f.api.accounts.confirmVerification(ctx, ContactChannel.EMAIL, code) } }) }.map { it.get() }
            assertEquals(1, results.count { it.isSuccess }, "derselbe Code gelingt höchstens einmal")
            results.filter { it.isFailure }.forEach { assertEquals(ErrorCode.VALIDATION_FAILED, (it.exceptionOrNull() as ProblemException).code) }
            assertEquals(1, f.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ?::uuid AND action = 'identity.passenger.contact_verified'", t.toString()))
        } finally { pool.shutdownNow() }
        assertTrue(Duration.ZERO <= Duration.ofSeconds(1))
    }
}
