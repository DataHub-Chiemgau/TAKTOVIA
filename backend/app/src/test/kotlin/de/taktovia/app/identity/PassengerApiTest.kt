package de.taktovia.app.identity

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.util.concurrent.CopyOnWriteArrayList
import de.taktovia.identity.api.ContactChannel
import de.taktovia.identity.api.InMemoryVerificationSender
import de.taktovia.identity.api.VerificationSender
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * WP-M1-28, -29, -30 über die echte Anwendung (HTTP, echte Datenbank, Runtime-Rolle und Modulzugang `taktovia_fields`, lokaler Test-Issuer statt Keycloak,
 * Test-SMS-Adapter im Speicher). Nicht ausgeführt: echter Keycloak, echter SMS-/E-Mail-Versand.
 */
class PassengerApiTest {
    private val env = IdentityEnv
    private val sender get() = env.server.ctx.getBean(VerificationSender::class.java) as InMemoryVerificationSender
    private fun idem() = mapOf("Idempotency-Key" to "pax-" + UUID.randomUUID())

    /** Token eines frisch beim IdP registrierten Kontos: Client passenger-web, der Vertriebskontext (Mandant) als Hinweisclaim, noch ohne Mitgliedschaft. */
    private fun enrolmentToken(subject: UUID, tenant: TenantId, azp: String = "passenger-web", tenants: List<UUID> = listOf(tenant.value)): String =
        env.idp.sign(env.idp.humanClaims(sub = subject, roles = listOf("passenger"), acr = "loa1", tenants = tenants, azp = azp).also { it["sid"] = "sid-" + UUID.randomUUID() })

    private fun register(token: String, contact: String, channel: String = "EMAIL", locale: String = "de-DE") =
        env.post("/v1/passengers", """{"contact":{"channel":"$channel","value":"$contact"},"locale":"$locale"}""", token, extra = idem())

    private fun patch(token: String, body: String, revision: Long?) =
        env.server.send("PATCH", "/v1/me/profile", body, env.headers(token, extra = idem() + (if (revision != null) mapOf("If-Match" to "\"$revision\"") else emptyMap())))

    private class Pax(val tenant: TenantId, val subject: UUID, val token: String, val email: String)

    private fun verifiedPassenger(tenant: TenantId = env.tenant("Fahrgast-" + UUID.randomUUID())): Pax {
        val subject = UUID.randomUUID(); val token = enrolmentToken(subject, tenant); val email = "pax.${UUID.randomUUID().toString().take(8)}@example.org"
        assertEquals(201, register(token, email).status)
        val code = sender.last(ContactChannel.EMAIL, email)!!
        assertEquals(200, env.postIdem("/v1/me/contact-verifications/confirmations", """{"channel":"EMAIL","code":"$code"}""", token).status)
        return Pax(tenant, subject, token, email)
    }

    private fun stop(tenant: TenantId): UUID {
        val s = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.stop_revision(tenant_id,id,stop_id,revision,point,name,accessibility,approved_by,valid_from) VALUES (?::uuid,?::uuid,?::uuid,1,ST_SetSRID(ST_MakePoint(9.0,48.0),4326),'Fixture','{}'::jsonb,'test',now())", tenant.toString(), UUID.randomUUID().toString(), s.toString())
        return s
    }

    @Test fun registrierung_ueber_api_verifikation_und_profil() {
        val t = env.tenant("Reg-" + UUID.randomUUID()); val subject = UUID.randomUUID(); val token = enrolmentToken(subject, t); val email = "Neu.Fahrgast@Example.org"
        val r = register(token, email)
        assertEquals(201, r.status, r.text)
        assertNotNull(r.header("Location")); assertEquals("PENDING_VERIFICATION", r.json["status"].asString()); assertNotNull(r.header("ETag"))
        // „Verifikation fehlt“: der Kanal ist unverifiziert, der Kontakt wird dem Fahrgast selbst angezeigt
        val p = env.get("/v1/me/profile", token)
        assertEquals(200, p.status, p.text)
        assertEquals("UNVERIFIED", p.json["contact"]["email"]["status"].asString()); assertEquals("neu.fahrgast@example.org", p.json["contact"]["email"]["value"].asString())
        assertTrue(p.json["contact"]["phone"].isNull)
        // falscher Code 422, richtiger 200, derselbe Code danach 422
        assertEquals(422, env.postIdem("/v1/me/contact-verifications/confirmations", """{"channel":"EMAIL","code":"${wrongCode(sender.last(ContactChannel.EMAIL, "neu.fahrgast@example.org")!!)}"}""", token).status)
        val code = sender.last(ContactChannel.EMAIL, "neu.fahrgast@example.org")!!
        val ok = env.postIdem("/v1/me/contact-verifications/confirmations", """{"channel":"EMAIL","code":"$code"}""", token)
        assertEquals(200, ok.status, ok.text); assertEquals("VERIFIED", ok.json["status"].asString())
        assertEquals(422, env.postIdem("/v1/me/contact-verifications/confirmations", """{"channel":"EMAIL","code":"$code"}""", token).status)
        val after = env.get("/v1/me/profile", token)
        assertEquals("VERIFIED", after.json["contact"]["email"]["status"].asString()); assertEquals("ACTIVE", after.json["status"].asString())
        // das Konto ist jetzt Mitglied (Rolle passenger): /v1/me funktioniert, zweite Registrierung ist 409
        assertEquals(200, env.get("/v1/me", token).status)
        assertEquals(409, register(token, "andere@example.org").status)
        // Telefon als zweiter Kanal über das Profil, Verifikation gegen den Test-SMS-Adapter
        val prof = env.get("/v1/me/profile", token)
        val u = patch(token, """{"contact":{"phone":"+49 151 7654321"}}""", prof.json["revision"].asLong())
        assertEquals(200, u.status, u.text); assertEquals("UNVERIFIED", u.json["contact"]["phone"]["status"].asString())
        assertEquals(200, env.postIdem("/v1/me/contact-verifications", """{"channel":"PHONE"}""", token).status)
        val sms = sender.last(ContactChannel.PHONE, "+491517654321"); assertNotNull(sms)
        assertEquals("VERIFIED", env.postIdem("/v1/me/contact-verifications/confirmations", """{"channel":"PHONE","code":"$sms"}""", token).json["status"].asString())
    }

    private fun wrongCode(code: String) = if (code == "000000") "111111" else "000000"

    @Test fun selbstregistrierung_nur_fuer_passenger_web_mit_genau_einem_mandanten() {
        val t = env.tenant("Reg2-" + UUID.randomUUID())
        // anderer Client (Betreiber-Web) oder mehrdeutiger Vertriebskontext: 403 ohne Hinweis
        assertEquals(403, register(enrolmentToken(UUID.randomUUID(), t, azp = "operator-web"), "a@example.org").status)
        assertEquals(403, register(enrolmentToken(UUID.randomUUID(), t, tenants = listOf(t.value, UUID.randomUUID())), "b@example.org").status)
        assertEquals(403, register(enrolmentToken(UUID.randomUUID(), t, tenants = emptyList()), "c@example.org").status)
        // andere Operationen sind ohne Mitgliedschaft weiterhin verweigert (die Ausnahme gilt nur für die Registrierung)
        val s = UUID.randomUUID(); val tok = enrolmentToken(s, t)
        assertEquals(403, env.get("/v1/me/profile", tok).status)
        assertEquals(403, env.get("/v1/passengers", tok).status)
        // Mitarbeiter (Callcenter) legen über diese Operation kein Fahrgastkonto für sich an
        val cc = UUID.randomUUID(); env.grant(t, cc, "callcenter")
        assertEquals(403, register(env.token(cc), "d@example.org").status)
        // ungültige Eingaben: 422 mit Zeiger, ohne den Wert zu wiederholen
        val bad = register(tok, "kein-kontakt")
        assertEquals(422, bad.status); assertEquals("/contact/value", bad.json["violations"][0]["pointer"].asString()); assertFalse(bad.text.contains("kein-kontakt"))
        assertEquals(422, register(tok, "x@example.org", locale = "xx-XX").status)
        // gleicher Kontakt: nur ein Konto, die zweite Registrierung ist 409 (gleichförmig)
        assertEquals(201, register(tok, "e@example.org").status)
        assertEquals(409, register(enrolmentToken(UUID.randomUUID(), t), "e@example.org").status)
    }

    @Test fun mandantentrennung_fremdes_profil_und_betreibersicht() {
        val a = verifiedPassenger(); val b = verifiedPassenger()
        val ownId = env.get("/v1/me/profile", a.token).json["id"].asString()
        val foreignId = env.get("/v1/me/profile", b.token).json["id"].asString()
        assertEquals(200, env.get("/v1/passengers/$ownId", a.token).status)
        assertEquals(404, env.get("/v1/passengers/$foreignId", a.token).status, "fremde Profil-ID ergibt 404")
        assertEquals(404, env.get("/v1/passengers/${UUID.randomUUID()}", a.token).status)
        // Betreiber des Mandanten A sieht nur A, minimiert; Administrator ohne Fachrolle (PII-Sicht) und Fahrgast sind verweigert
        val cc = UUID.randomUUID(); env.grant(a.tenant, cc, "callcenter")
        val list = env.get("/v1/passengers?limit=50", env.token(cc))
        assertEquals(200, list.status, list.text)
        val ids = list.json["items"].values().map { it["id"].asString() }
        assertTrue(ownId in ids); assertFalse(foreignId in ids)
        list.json["items"].values().forEach { assertEquals(setOf("id", "status", "locale", "created_at", "revision"), it.propertyNames().toSet()) }
        assertFalse(list.text.contains(a.email), "Kontakt erscheint nicht in der Betreiberliste")
        val admin = UUID.randomUUID(); env.grant(a.tenant, admin, "tenant_admin")
        assertEquals(403, env.get("/v1/passengers", env.token(admin)).status)
        assertEquals(403, env.get("/v1/passengers", a.token).status)
        // Unterstützungsmerkmal: Fahrgast pflegt es selbst, Betreiber lesen es nur mit Feldrecht UND Zweck, nie ein anderer Mandant
        assertEquals(204, env.put("/v1/me/transport-support", """{"codes":["WHEELCHAIR_SPACE","BOARDING_HELP"]}""", a.token, null).status)
        assertEquals(listOf("BOARDING_HELP", "WHEELCHAIR_SPACE"), env.get("/v1/me/transport-support", a.token).json["codes"].values().map { it.asString() })
        assertEquals(422, env.put("/v1/me/transport-support", """{"codes":["Diagnose"]}""", a.token, null).status)
        val purpose = mapOf("X-Access-Purpose" to "BOOKING_ASSISTANCE")
        val ok = env.get("/v1/passengers/$ownId/support-needs", env.token(cc), extra = purpose)
        assertEquals(200, ok.status, ok.text); assertEquals(listOf("BOARDING_HELP", "WHEELCHAIR_SPACE"), ok.json["codes"].values().map { it.asString() })
        assertEquals(422, env.get("/v1/passengers/$ownId/support-needs", env.token(cc)).status, "ohne Zweck")
        assertEquals(403, env.get("/v1/passengers/$ownId/support-needs", a.token, extra = purpose).status, "Fahrgast hat den Feldscope nicht")
        assertEquals(403, env.get("/v1/passengers/$ownId/support-needs", env.token(admin), extra = purpose).status, "Administrator ohne Fachrolle")
        val foreignCc = UUID.randomUUID(); env.grant(b.tenant, foreignCc, "callcenter")
        assertEquals(404, env.get("/v1/passengers/$ownId/support-needs", env.token(foreignCc), extra = purpose).status, "anderer Mandant")
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ?::uuid AND action = 'passenger.support_needs.read' AND object_ref = ?", a.tenant.toString(), "passenger:$ownId"))
    }

    @Test fun profil_aendern_etag_412_428_und_ungueltige_werte() {
        val p = verifiedPassenger(); val s1 = stop(p.tenant); val s2 = stop(p.tenant)
        val first = env.get("/v1/me/profile", p.token); val rev = first.json["revision"].asLong()
        assertEquals("\"$rev\"", first.header("ETag"))
        assertEquals(428, patch(p.token, """{"locale":"en-GB"}""", null).status, "ohne If-Match")
        val ok = patch(p.token, """{"locale":"en-GB","favorites":[{"label":"Zuhause","stop_id":"$s1"}],"preferred_stops":["$s2","$s1"],"notification_channels":["PUSH","SMS"]}""", rev)
        assertEquals(200, ok.status, ok.text)
        assertEquals(412, patch(p.token, """{"locale":"de-DE"}""", rev).status, "veralteter ETag")
        // nach „erneuter Anmeldung“ (neues Token desselben Kontos) und von einem zweiten Gerät: derselbe Stand
        val again = env.get("/v1/me/profile", env.token(p.subject, acr = "loa1"))
        assertEquals(200, again.status); assertEquals("en-GB", again.json["locale"].asString())
        assertEquals(listOf("PUSH", "SMS"), again.json["notification_channels"].values().map { it.asString() })
        assertEquals(listOf(s2.toString(), s1.toString()), again.json["preferred_stops"].values().map { it.asString() })
        assertEquals(s1.toString(), again.json["favorites"][0]["stop_id"].asString())
        val cur = again.json["revision"].asLong()
        val badLocale = patch(p.token, """{"locale":"xx-XX"}""", cur); assertEquals(422, badLocale.status, badLocale.text)
        val badStop = patch(p.token, """{"preferred_stops":["${UUID.randomUUID()}"]}""", cur)
        assertEquals(422, badStop.status); assertEquals("/preferred_stops/0", badStop.json["violations"][0]["pointer"].asString())
        assertEquals(422, patch(p.token, """{"unbekannt":1}""", cur).status, "unbekanntes Feld")
        assertEquals(422, patch(p.token, """{}""", cur).status, "leere Änderung")
        assertEquals(cur, env.get("/v1/me/profile", p.token).json["revision"].asLong(), "abgewiesene Änderungen ändern nichts")
        // Kontakt: neuer Wert ist unverifiziert, ein bereits vergebener Kontakt ist 409
        val other = verifiedPassenger(p.tenant)
        assertEquals(409, patch(p.token, """{"contact":{"email":"${other.email}"}}""", cur).status)
        val changed = patch(p.token, """{"contact":{"email":"neu.${UUID.randomUUID().toString().take(6)}@example.org"}}""", cur)
        assertEquals(200, changed.status, changed.text); assertEquals("UNVERIFIED", changed.json["contact"]["email"]["status"].asString())
    }

    @Test fun favoriten_mit_koordinate_ueber_api_und_ungueltige_werte_422() {
        val p = verifiedPassenger(); val st = stop(p.tenant)
        val rev = env.get("/v1/me/profile", p.token).json["revision"].asLong()
        val ok = patch(p.token, """{"favorites":[{"label":"Zuhause","lat":48.775,"lon":9.182},{"label":"Halt","stop_id":"$st"}]}""", rev)
        assertEquals(200, ok.status, ok.text)
        val favs = ok.json["favorites"]
        assertEquals(48.775, favs[0]["lat"].asDouble(), 1e-9); assertEquals(true, favs[0]["stop_id"].isNull); assertEquals(false, favs[0]["stop_bookable"].asBoolean())
        assertEquals(st.toString(), favs[1]["stop_id"].asString()); assertEquals(true, favs[1]["stop_bookable"].asBoolean())
        val cur = ok.json["revision"].asLong()
        for ((body, pointer) in listOf(
            """{"favorites":[{"label":"X","lat":95,"lon":9}]}""" to "/favorites/0/lat",
            """{"favorites":[{"label":"X","lat":0,"lon":0}]}""" to "/favorites/0/lat",
            """{"favorites":[{"label":"X","lat":"48","lon":9}]}""" to "/favorites/0/lat",
            """{"favorites":[{"label":"X","stop_id":"${UUID.randomUUID()}"}]}""" to "/favorites/0/stop_id",
        )) {
            val r = patch(p.token, body, cur)
            assertEquals(422, r.status, r.text); assertEquals(pointer, r.json["violations"][0]["pointer"].asString())
        }
        assertEquals(422, patch(p.token, """{"favorites":[{"label":"X","lat":48,"lon":9,"extra":1}]}""", cur).status, "unbekanntes Feld")
    }

    @Test fun brute_force_ueber_api_ergibt_429_mit_retry_after() {
        val t = env.tenant("Brute-" + UUID.randomUUID()); val subject = UUID.randomUUID(); val token = enrolmentToken(subject, t); val email = "brute.${UUID.randomUUID().toString().take(6)}@example.org"
        assertEquals(201, register(token, email).status)
        val wrong = wrongCode(sender.last(ContactChannel.EMAIL, email)!!)
        val statuses = (1..5).map { env.postIdem("/v1/me/contact-verifications/confirmations", """{"channel":"EMAIL","code":"$wrong"}""", token) }
        assertEquals(listOf(422, 422, 422, 422, 429), statuses.map { it.status })
        assertNotNull(statuses.last().header("Retry-After"))
        assertEquals(429, env.postIdem("/v1/me/contact-verifications/confirmations", """{"channel":"EMAIL","code":"${sender.last(ContactChannel.EMAIL, email)}"}""", token).status)
        assertEquals(4, env.events(t, "security.contact.verification_failed"))
        assertTrue(env.events(t, "security.contact.verification_limited") >= 2)
    }

    @Test fun code_und_kontakt_nie_im_log_ueber_api() {
        // Server- und Testthreads loggen gleichzeitig: threadsichere Sammlung statt ListAppender
        val collected = CopyOnWriteArrayList<ILoggingEvent>()
        val appender = object : AppenderBase<ILoggingEvent>() { override fun append(e: ILoggingEvent) { collected += e } }.also { it.start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val before = root.level; root.level = Level.ALL; root.addAppender(appender)
        try {
            val t = env.tenant("Log-" + UUID.randomUUID()); val token = enrolmentToken(UUID.randomUUID(), t); val email = "log.geheim.${UUID.randomUUID().toString().take(6)}@example.org"
            assertEquals(201, register(token, email).status)
            val code = sender.last(ContactChannel.EMAIL, email)!!
            env.postIdem("/v1/me/contact-verifications/confirmations", """{"channel":"EMAIL","code":"${wrongCode(code)}"}""", token)
            env.postIdem("/v1/me/contact-verifications/confirmations", """{"channel":"EMAIL","code":"$code"}""", token)
            register(token, "doppelt.$email")   // 409: Fehlerpfad
            val text = collected.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
            assertFalse(text.contains(email) || text.contains(email.substringBefore('@')), "Kontakt im Log")
            assertFalse(Regex("(?<![0-9a-fA-F-])$code(?![0-9a-fA-F-])").containsMatchIn(text), "Code im Log")
        } finally { root.detachAppender(appender); root.level = before }
    }

    @Test fun datenschutz_trennung_standard_marketing_aus_revisionen_und_422() {
        val p = verifiedPassenger()
        val s0 = env.get("/v1/me/privacy", p.token); assertEquals(200, s0.status, s0.text)
        val v = s0.json["current_text_version"].asString()
        val consents = s0.json["consents"].values().associateBy { it["purpose"].asString() }
        assertFalse(consents.getValue("MARKETING")["granted"].asBoolean(), "Marketing standardmäßig nicht eingewilligt")
        assertTrue(consents.getValue("OPERATIONAL_SAFETY")["granted"].asBoolean()); assertFalse(consents.getValue("OPERATIONAL_SAFETY")["changeable"].asBoolean())
        assertEquals(0, s0.json["history"].size())
        val a = env.postIdem("/v1/me/privacy/consents", """{"purpose":"MARKETING","granted":true,"text_version":"$v"}""", p.token)
        assertEquals(201, a.status, a.text)
        val b = env.postIdem("/v1/me/privacy/consents", """{"purpose":"MARKETING","granted":false,"text_version":"$v"}""", p.token)
        assertEquals(201, b.status); assertEquals(2L, b.json["sequence"].asLong()); assertTrue(a.json["id"].asString() != b.json["id"].asString())
        val s1 = env.get("/v1/me/privacy", p.token)
        assertEquals(2, s1.json["history"].size()); assertFalse(s1.json["consents"].values().first { it["purpose"].asString() == "MARKETING" }["granted"].asBoolean())
        // nicht änderbarer/unbekannter Zweck, zusätzliche Felder, veraltete Textversion: 422 mit Zeiger
        val op = env.postIdem("/v1/me/privacy/consents", """{"purpose":"OPERATIONAL_SAFETY","granted":false,"text_version":"$v"}""", p.token)
        assertEquals(422, op.status); assertEquals("/purpose", op.json["violations"][0]["pointer"].asString())
        assertEquals(422, env.postIdem("/v1/me/privacy/consents", """{"purpose":"WERBUNG","granted":true,"text_version":"$v"}""", p.token).status)
        assertEquals(422, env.postIdem("/v1/me/privacy/consents", """{"purpose":"MARKETING","granted":true,"text_version":"$v","extra":1}""", p.token).status)
        assertEquals(422, env.postIdem("/v1/me/privacy/consents", """{"purpose":"MARKETING","granted":true,"text_version":"alt"}""", p.token).status)
        assertTrue(env.post("/v1/me/privacy/consents", """{"purpose":"MARKETING","granted":true,"text_version":"$v"}""", p.token).status in 400..499, "ohne Idempotency-Key abgewiesen")
        assertEquals(2, env.get("/v1/me/privacy", p.token).json["history"].size(), "abgewiesene Änderungen legen keine Revision an")
        // ein Konto ohne Fahrgastkonto (Betreiber) hat keine Einwilligungen: 404
        val cc = UUID.randomUUID(); env.grant(p.tenant, cc, "callcenter")
        assertEquals(404, env.get("/v1/me/privacy", env.token(cc)).status)
        assertEquals(404, env.postIdem("/v1/me/privacy/consents", """{"purpose":"MARKETING","granted":true,"text_version":"$v"}""", env.token(cc)).status)
        assertEquals(2, env.count("SELECT count(*) FROM taktovia.consent_revision WHERE tenant_id = ?::uuid", p.tenant.toString()))
    }
}
