package de.taktovia.app

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Idempotenzregister an einem echten schreibenden M1-Endpunkt (`POST /v1/memberships`) hinter der vollständigen Kette aus Token-Prüfung, Mandantenkontext
 * und Rechtematrix (WP-M1-13): Authentisierung und Leseberechtigung werden bei jeder Wiederholung erneut geprüft, ein Rechteentzug wirkt sofort.
 */
class IdempotencyApiTest {
    private val env = IdentityEnv
    private fun create(token: String, subject: String, key: String?, role: String = "support"): de.taktovia.app.probe.Resp =
        env.post("/v1/memberships", """{"issuer":"${env.idp.issuer}","subject":"$subject","role_code":"$role"}""", token, extra = if (key == null) emptyMap() else mapOf("Idempotency-Key" to key))
    private fun admin(sql: String, vararg args: Any?) = de.taktovia.TaktoviaTestSupport.adminDataSourceOrNull()!!.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeUpdate() }
    }
    private fun key() = "k-" + UUID.randomUUID()

    @Test
    fun wiederholung_liefert_dasselbe_ergebnis_statt_409_und_wirkt_einmal() {
        val t = env.tenant(); val admin = UUID.randomUUID(); env.grant(t, admin, "tenant_admin")
        val token = env.token(admin); val k = key(); val subject = "idem-" + UUID.randomUUID()
        val first = create(token, subject, k); assertEquals(201, first.status, first.text)
        val retry = create(token, subject, k)
        assertEquals(201, retry.status, "ohne Register wäre es ein 409 CONFLICT (Mitgliedschaft existiert schon)")
        assertEquals(first.json["id"].asString(), retry.json["id"].asString()); assertEquals(first.header("Location"), retry.header("Location")); assertEquals(first.header("ETag"), retry.header("ETag"))
        assertEquals("true", retry.header("Idempotent-Replayed")); assertNull(first.header("Idempotent-Replayed"))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.membership WHERE tenant_id=? AND subject=?", t.value, subject))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='membership.granted' AND payload->>'subject'=?", t.value, subject), "genau ein Outbox-Eintrag")
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.idempotency_record WHERE tenant_id=? AND command_scope='createMembership' AND state='COMPLETED' AND http_status=201", t.value))
    }

    @Test
    fun anderer_body_mit_gleichem_schluessel_409_idempotency_conflict() {
        val t = env.tenant(); val admin = UUID.randomUUID(); env.grant(t, admin, "tenant_admin")
        val token = env.token(admin); val k = key()
        assertEquals(201, create(token, "a-" + UUID.randomUUID(), k).status)
        val other = create(token, "b-" + UUID.randomUUID(), k)
        assertEquals(409, other.status); assertEquals("IDEMPOTENCY_CONFLICT", other.json["code"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.membership WHERE tenant_id=? AND role_code='support'", t.value))
    }

    @Test
    fun wiederholung_wird_neu_authentisiert_und_neu_autorisiert() {
        val t = env.tenant(); val admin = UUID.randomUUID(); val adminMembership = env.grant(t, admin, "tenant_admin")
        val token = env.token(admin); val k = key(); val subject = "idem-" + UUID.randomUUID()
        assertEquals(201, create(token, subject, k).status)
        // ohne Token und mit ungültigem Token: abgewiesen, das gespeicherte Ergebnis bleibt unerreichbar
        val anonymous = env.server.post("/v1/memberships", """{"issuer":"${env.idp.issuer}","subject":"$subject","role_code":"support"}""", mapOf("Idempotency-Key" to k))
        assertEquals(401, anonymous.status); assertEquals(false, anonymous.text.contains("membership"))
        assertEquals(401, create("kein.gueltiges.token", subject, k).status)
        // anderer, zugelassener Akteur desselben Mandanten: eigener Scope, kein Zugriff auf das Ergebnis des ersten
        val admin2 = UUID.randomUUID(); env.grant(t, admin2, "tenant_admin")
        val second = create(env.token(admin2), subject, k)
        assertEquals(409, second.status, "eigener Scope: wird ausgeführt und scheitert fachlich (Mitgliedschaft existiert)"); assertEquals("CONFLICT", second.json["code"].asString())
        // Rechteentzug: dieselbe Wiederholung wird jetzt abgewiesen
        env.revokeDirect(t, adminMembership)
        assertEquals(403, create(token, subject, k).status, "Wiederholung nach Rechteentzug")
        // eine Person ohne die Berechtigung bekommt das Ergebnis eines anderen nie
        val passenger = UUID.randomUUID(); env.grant(t, passenger, "passenger")
        assertEquals(403, create(env.token(passenger), subject, k).status)
    }

    @Test
    fun schluessel_gelten_je_mandant() {
        val a = env.tenant(); val b = env.tenant(); val adminA = UUID.randomUUID(); val adminB = UUID.randomUUID()
        env.grant(a, adminA, "tenant_admin"); env.grant(b, adminB, "tenant_admin")
        val k = key(); val subject = "idem-" + UUID.randomUUID()
        val inA = create(env.token(adminA), subject, k); val inB = create(env.token(adminB), subject, k)
        assertEquals(201, inA.status); assertEquals(201, inB.status)
        assertEquals(false, inA.json["id"].asString() == inB.json["id"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.idempotency_record WHERE tenant_id=?", a.value)); assertEquals(1, env.count("SELECT count(*) FROM taktovia.idempotency_record WHERE tenant_id=?", b.value))
    }

    @Test
    fun fehlender_oder_ungueltiger_schluessel_ist_ein_feldfehler() {
        val t = env.tenant(); val admin = UUID.randomUUID(); env.grant(t, admin, "tenant_admin"); val token = env.token(admin)
        for ((label, k) in mapOf("fehlt" to null, "zu kurz" to "k".repeat(15), "zu lang" to "k".repeat(129), "Muster" to "ungültig-ungültig-1234")) {
            val r = create(token, "s-" + UUID.randomUUID(), k)
            assertEquals(422, r.status, label); assertEquals("VALIDATION_FAILED", r.json["code"].asString(), label)
            assertEquals("/\$header/Idempotency-Key", r.json["violations"][0]["pointer"].asString(), label)
        }
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.membership WHERE tenant_id=? AND role_code='support'", t.value))
    }

    @Test
    fun abgelaufener_antwortcache_erlaubt_keine_zweite_wirkung_wegen_fachlichem_unique() {
        val t = env.tenant(); val admin = UUID.randomUUID(); env.grant(t, admin, "tenant_admin")
        val token = env.token(admin); val k = key(); val subject = "idem-" + UUID.randomUUID()
        assertEquals(201, create(token, subject, k).status)
        admin("UPDATE taktovia.idempotency_record SET expires_at = now() - interval '1 minute' WHERE tenant_id=?", t.value)   // 72 h vergangen
        val again = create(token, subject, k)
        assertEquals(409, again.status, "Befehl läuft erneut an, das fachliche Unique der Mitgliedschaft verhindert die zweite Wirkung"); assertEquals("CONFLICT", again.json["code"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.membership WHERE tenant_id=? AND subject=?", t.value, subject))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='membership.granted' AND payload->>'subject'=?", t.value, subject))
    }

    @Test
    fun parallele_identische_requests_erzeugen_eine_mitgliedschaft() {
        val t = env.tenant(); val admin = UUID.randomUUID(); env.grant(t, admin, "tenant_admin")
        val token = env.token(admin); val k = key(); val subject = "idem-" + UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(12); val start = CountDownLatch(1)
        val futures = (1..12).map { pool.submit<de.taktovia.app.probe.Resp> { start.await(); create(token, subject, k) } }
        start.countDown()
        val responses = futures.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(setOf(201), responses.map { it.status }.toSet(), responses.firstOrNull { it.status != 201 }?.text)
        assertEquals(1, responses.map { it.json["id"].asString() }.toSet().size)
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.membership WHERE tenant_id=? AND subject=?", t.value, subject))
    }
}
