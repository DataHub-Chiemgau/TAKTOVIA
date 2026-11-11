package de.taktovia.app.identity

import de.taktovia.app.probe.Resp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LH-6.2-IAM-009 / WP-M1-10 über die echte Anwendung: Ressourcenserver gegen den lokalen JWT-Test-Issuer (Ersatz für Keycloak), echte Datenbank unter
 * der Runtime-Rolle, lokaler Redis als Cache der Sperrliste und HTTP-Ersatz der Keycloak-Admin-API. **Nicht ausgeführt:** echter Keycloak (Sitzungsende,
 * Konto sperren, Wiederherstellungs-Mail) und der Browser-Ablauf.
 */
class SessionApiTest {
    private val env = IdentityEnv
    private val kc = IdentityEnv.keycloak

    private class World(val env: IdentityEnv) {
        val tenant = env.tenant("Sitzung-" + UUID.randomUUID())
        val admin = UUID.randomUUID(); val user = UUID.randomUUID()
        init { env.grant(tenant, admin, "tenant_admin"); env.grant(tenant, user, "dispatcher") }
        val adminToken = env.token(admin)
    }

    private fun me(token: String) = env.get("/v1/me", token)
    private fun sessionOf(token: String): Pair<UUID, Long> {
        val id = UUID.fromString(me(token).json["session_id"].asString())
        val list = env.get("/v1/me/sessions?limit=100", token)
        assertEquals(200, list.status, list.text)
        val item = list.json["items"].first { it["id"].asString() == id.toString() }
        return id to item["revision"].asLong()
    }

    @Test
    fun gesperrte_sitzung_ruft_me_und_schreibenden_endpunkt_auf_und_erhaelt_401() {
        val w = World(env); val phone = env.token(w.user, sid = "sid-phone-" + UUID.randomUUID()); val laptop = env.token(w.user, sid = "sid-laptop-" + UUID.randomUUID())
        assertEquals(200, me(phone).status); assertEquals(200, me(laptop).status)
        val sessions = env.get("/v1/sessions?limit=100", w.adminToken)
        assertEquals(200, sessions.status, sessions.text)
        val (phoneId, rev) = sessionOf(phone)
        val callsBefore = kc.calls.size
        val revoke = env.delete("/v1/sessions/$phoneId", w.adminToken, ifMatch = rev)
        assertEquals(204, revoke.status, revoke.text)
        // das zuvor gültige Access-Token ruft nichts mehr auf: lesend wie schreibend 401, Problemformat, kein Hinweis auf den Grund im Detail der Daten
        val read = me(phone); val write = env.switchTo(phone, w.tenant)
        assertEquals(401, read.status, read.text); assertEquals(401, write.status, write.text)
        assertEquals("UNAUTHENTICATED", read.json["code"].asString())
        assertTrue(read.header("WWW-Authenticate")!!.contains("invalid_token"))
        assertEquals(401, env.get("/v1/me/sessions", phone).status)
        // Refresh nicht mehr möglich: die IdP-Sitzung wurde beendet (Admin-API-Aufruf beobachtet)
        assertTrue(kc.calls.drop(callsBefore).any { it.startsWith("DELETE /sessions/sid-phone-") }, kc.calls.toString())
        // andere Sitzung des Kontos und andere Konten bleiben unberührt; eine zweite Sperre derselben Sitzung ist ein Konflikt
        assertEquals(200, me(laptop).status)
        assertEquals(409, env.delete("/v1/sessions/$phoneId", w.adminToken, ifMatch = rev + 1).status)
        assertEquals(1, env.events(w.tenant, "security.session.revoked"))
    }

    @Test
    fun alle_geraete_abmelden_zweites_geraet_401_und_erneute_anmeldung() {
        val w = World(env); val phone = env.token(w.user); val laptop = env.token(w.user)
        assertEquals(200, me(phone).status); assertEquals(200, me(laptop).status)
        assertEquals(1, env.get("/v1/me/sessions", phone).json["items"].size().coerceAtMost(2).let { if (it >= 1) 1 else 0 })
        val out = env.postIdem("/v1/me/sessions/revoke-all", "{}", phone)
        assertEquals(204, out.status, out.text)
        assertEquals(401, me(phone).status, "Gerät, das abmeldet")
        assertEquals(401, me(laptop).status, "zweites Gerät erhält bei der nächsten Anfrage 401")
        assertTrue(kc.calls.contains("POST /users/${w.user}/logout"), "IdP-Sitzungen des Kontos beendet")
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='security.session.logout_all' AND actor_ref=?", w.tenant.value, "user:${w.user}"))
        // andere Konten sind nicht betroffen
        assertEquals(200, me(w.adminToken).status)
        // erneutes Anmelden (neue IdP-Sitzung nach der Abmeldezeitmarke) funktioniert
        Thread.sleep(1100)
        val again = env.token(w.user)
        assertEquals(200, me(again).status)
        assertEquals(1, env.get("/v1/me/sessions", again).json["items"].filter { it["revoked_at"].isNull }.size)
    }

    @Test
    fun eigene_sitzung_abmelden_und_fremde_ist_nicht_auffindbar() {
        val w = World(env); val other = UUID.randomUUID(); env.grant(w.tenant, other, "support")
        val mine = env.token(w.user); val theirs = env.token(other)
        assertEquals(200, me(mine).status); assertEquals(200, me(theirs).status)
        val (myId, rev) = sessionOf(mine); val (theirId, theirRev) = sessionOf(theirs)
        assertEquals(404, env.delete("/v1/me/sessions/$theirId", mine, ifMatch = theirRev).status, "fremde Sitzung im eigenen Mandanten")
        assertEquals(412, env.delete("/v1/me/sessions/$myId", mine, ifMatch = rev + 4).status)
        assertEquals(428, env.delete("/v1/me/sessions/$myId", mine).status)
        assertEquals(204, env.delete("/v1/me/sessions/$myId", mine, ifMatch = rev).status)
        assertEquals(401, me(mine).status); assertEquals(200, me(theirs).status)
        assertEquals(1, env.events(w.tenant, "security.session.logout"))
        // Listen zeigen nur die eigenen Sitzungen, der Administrator im Mandanten alle
        assertEquals(1, env.get("/v1/me/sessions", theirs).json["items"].size())
        assertTrue(env.get("/v1/sessions", w.adminToken).json["items"].size() >= 2)
    }

    @Test
    fun sperrversuch_durch_unberechtigte_rolle_wird_verweigert() {
        val w = World(env); val support = UUID.randomUUID(); val passenger = UUID.randomUUID(); val dispatcher = w.user
        env.grant(w.tenant, support, "support"); env.grant(w.tenant, passenger, "passenger")
        val victim = UUID.randomUUID(); env.grant(w.tenant, victim, "driver")
        val body = """{"subject":"$victim","reason":"Test"}"""
        listOf(env.token(support), env.token(passenger), env.token(dispatcher)).forEach { t ->
            assertEquals(403, env.postIdem("/v1/account-locks", body, t).status)
            assertEquals(403, env.delete("/v1/account-locks/$victim", t, ifMatch = 1).status)
            assertEquals(403, env.postIdem("/v1/account-recoveries", """{"subject":"$victim"}""", t).status)
            assertEquals(403, env.delete("/v1/sessions/${UUID.randomUUID()}", t, ifMatch = 1).status)
        }
        assertEquals(403, env.get("/v1/sessions", env.token(passenger)).status, "Sitzungen des Mandanten lesen darf nur Support/Administrator")
        assertEquals(200, env.get("/v1/sessions", env.token(support)).status)
        // das Konto ist unverändert nutzbar, es gab keine Sperre
        assertEquals(200, me(env.token(victim)).status)
        assertEquals(0, env.events(w.tenant, "security.account.locked"))
    }

    @Test
    fun kontosperre_und_entsperrung_mit_auditspur_und_idp() {
        val w = World(env); val victim = w.user; val token = env.token(victim)
        assertEquals(200, me(token).status)
        val self = env.postIdem("/v1/account-locks", """{"subject":"${w.admin}","reason":"selbst"}""", w.adminToken)
        assertEquals(409, self.status, "eigenes Konto nicht sperrbar")
        val lock = env.postIdem("/v1/account-locks", """{"subject":"$victim","reason":"Missbrauchsverdacht"}""", w.adminToken)
        assertEquals(201, lock.status, lock.text)
        assertEquals("LOCKED", lock.json["status"].asString()); assertTrue(lock.json["idp_synced"].asBoolean())
        val rev = lock.json["revision"].asLong(); assertEquals("\"$rev\"", lock.header("ETag"))
        assertEquals(401, me(token).status, "bestehende Sitzung")
        assertEquals(401, me(env.token(victim)).status, "neue Anmeldung des gesperrten Kontos")
        assertTrue(kc.calls.contains("POST /users/$victim/logout") && kc.calls.contains("PUT /users/$victim enabled=false"), kc.calls.toString())
        assertEquals(409, env.postIdem("/v1/account-locks", """{"subject":"$victim","reason":"nochmal"}""", w.adminToken).status)
        assertEquals(412, env.delete("/v1/account-locks/$victim", w.adminToken, ifMatch = rev + 3).status)
        assertEquals(404, env.delete("/v1/account-locks/${UUID.randomUUID()}", w.adminToken, ifMatch = 1).status)
        assertEquals(204, env.delete("/v1/account-locks/$victim", w.adminToken, ifMatch = rev).status)
        assertTrue(kc.calls.contains("PUT /users/$victim enabled=true"))
        assertEquals(409, env.delete("/v1/account-locks/$victim", w.adminToken, ifMatch = rev + 1).status, "nicht mehr gesperrt")
        assertEquals(401, me(token).status, "die alte Sitzung bleibt gesperrt")
        Thread.sleep(1100)
        assertEquals(200, me(env.token(victim)).status, "neue Anmeldung nach Entsperrung")
        for (a in listOf("security.account.locked", "security.account.unlocked"))
            assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action=? AND actor_ref=? AND occurred_at IS NOT NULL", w.tenant.value, a, "user:${w.admin}"), a)
    }

    @Test
    fun wiederherstellung_nur_ueber_verifizierten_kontakt() {
        val w = World(env); val token = env.token(w.user)
        assertEquals(200, me(token).status)
        kc.user(w.user).emailVerified = false
        val denied = env.postIdem("/v1/account-recoveries", """{"subject":"${w.user}"}""", w.adminToken)
        assertEquals(409, denied.status, denied.text)
        assertFalse(kc.calls.any { it.contains("/users/${w.user}/execute-actions-email") }, "keine Mail an unverifizierten Kontakt")
        assertEquals(200, me(token).status, "Sitzungen bleiben bestehen")
        assertEquals(1, env.events(w.tenant, "security.account.recovery_denied"))
        kc.user(w.user).emailVerified = true
        val ok = env.postIdem("/v1/account-recoveries", """{"subject":"${w.user}"}""", w.adminToken)
        assertEquals(201, ok.status, ok.text)
        assertTrue(kc.calls.any { it.startsWith("PUT /users/${w.user}/execute-actions-email") && it.endsWith("actions=UPDATE_PASSWORD") })
        assertEquals(401, me(token).status, "alle bestehenden Sitzungen beendet")
        assertEquals(1, env.events(w.tenant, "security.account.recovery_started"))
        assertEquals(404, env.postIdem("/v1/account-recoveries", """{"subject":"${UUID.randomUUID()}"}""", w.adminToken).status)
    }

    @Test
    fun idp_ausfall_lokal_gesperrt_und_wiederholbar() {
        val w = World(env); val a = env.token(w.user)
        assertEquals(200, me(a).status)
        kc.down = true
        try {
            val out = env.postIdem("/v1/me/sessions/revoke-all", "{}", a)
            assertEquals(503, out.status, out.text); assertEquals("PROVIDER_UNAVAILABLE", out.json["code"].asString())
        } finally { kc.down = false }
        assertEquals(401, me(a).status, "die lokale Sperre gilt trotz IdP-Ausfall")
    }

    @Test
    fun fremder_mandant_kann_sitzungen_weder_sperren_noch_lesen() {
        val a = World(env); val b = World(env)
        val token = env.token(a.user); assertEquals(200, me(token).status)
        val (id, rev) = sessionOf(token)
        assertEquals(404, env.delete("/v1/sessions/$id", b.adminToken, ifMatch = rev).status)
        assertEquals(0, env.get("/v1/sessions", b.adminToken).json["items"].filter { it["id"].asString() == id.toString() }.size)
        assertEquals(404, env.postIdem("/v1/account-locks", """{"subject":"${a.user}","reason":"fremd"}""", b.adminToken).status)
        assertEquals(404, env.postIdem("/v1/account-recoveries", """{"subject":"${a.user}"}""", b.adminToken).status)
        assertEquals(200, me(token).status)
    }

    @Test
    fun sperre_waehrend_50_parallelen_anfragen_keine_nach_commit_begonnene_erfolgreich() {
        val w = World(env); val token = env.token(w.user)
        assertEquals(200, me(token).status)
        val (id, rev) = sessionOf(token)
        data class Obs(val start: Long, val status: Int)
        val obs = CopyOnWriteArrayList<Obs>(); val stop = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(50); val ready = CountDownLatch(50)
        val workers = (1..50).map { pool.submit { ready.countDown(); while (!stop.get()) { val t0 = System.nanoTime(); obs += Obs(t0, me(token).status) } } }
        ready.await(); Thread.sleep(400)
        val res = env.delete("/v1/sessions/$id", w.adminToken, ifMatch = rev)
        val committed = System.nanoTime()
        assertEquals(204, res.status, res.text)
        Thread.sleep(400); stop.set(true); workers.forEach { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        val after = obs.filter { it.start > committed }
        assertTrue(obs.count { it.start < committed } > 50 && after.size > 50, "Anfragen vor/nach der Sperre: ${obs.size - after.size}/${after.size}")
        assertEquals(emptyList<Obs>(), after.filter { it.status != 401 }, "keine nach dem Commit der Sperre begonnene Anfrage darf erfolgreich sein")
        assertTrue(obs.any { it.status == 200 })
    }

    @Test
    fun cache_der_sperrliste_ist_ausgeschaltet_oder_wirkt_nur_als_sperrliste() {
        val redis = env.redis
        if (redis == null) { println("redis-server nicht installiert: Sperrlisten-Cache nicht geprüft"); return }
        val w = World(env); val token = env.token(w.user); assertEquals(200, me(token).status)
        val (id, rev) = sessionOf(token)
        assertEquals(204, env.delete("/v1/sessions/$id", w.adminToken, ifMatch = rev).status)
        val keys = io.lettuce.core.RedisClient.create(redis.url).connect().use { it.sync().keys("taktovia:sessblock:s:${w.tenant}:*") }
        assertEquals(1, keys.size, "die Sperre steht in der Sperrliste")
        assertEquals(401, me(token).status)
        assertNotNull(keys.first())
    }
}
