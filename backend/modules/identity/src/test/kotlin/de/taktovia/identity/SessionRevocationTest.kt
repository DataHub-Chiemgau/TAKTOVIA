package de.taktovia.identity

import de.taktovia.identity.api.Decision
import de.taktovia.identity.api.IdentityApi
import de.taktovia.identity.api.IdpAdmin
import de.taktovia.identity.api.LockOutcome
import de.taktovia.identity.api.RecoveryOutcome
import de.taktovia.identity.api.RevokeSessionOutcome
import de.taktovia.identity.api.SessionCheckUnavailableException
import de.taktovia.identity.api.SessionRef
import de.taktovia.identity.api.SessionVerdict
import de.taktovia.identity.api.UnlockOutcome
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.cache.SessionBlockCache
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Aufzeichnender Ersatz für die IdP-Administration (Keycloak-Admin-REST wird in `KeycloakIdpAdminTest` der Anwendung gegen einen HTTP-Ersatz geprüft). */
class RecordingIdp(var verified: Boolean = true) : IdpAdmin {
    val calls = CopyOnWriteArrayList<String>()
    @Volatile var down = false
    private fun rec(s: String) { if (down) throw IdpAdmin.Unavailable("IdP aus"); calls += s }
    override fun endAllSessions(issuer: String, subject: String) = rec("endAll:$subject")
    override fun endSession(issuer: String, idpSessionId: String) = rec("end:$idpSessionId")
    override fun setAccountEnabled(issuer: String, subject: String, enabled: Boolean) = rec("enabled=$enabled:$subject")
    override fun contactVerified(issuer: String, subject: String): Boolean { if (down) throw IdpAdmin.Unavailable("IdP aus"); return verified }
    override fun startRecovery(issuer: String, subject: String) = rec("recovery:$subject")
}

/** In-Memory-Sperrliste; kann ausfallen (jede Operation wirft) und protokolliert Schreibvorgänge. */
class FlakyBlockCache : SessionBlockCache {
    val entries = ConcurrentHashMap.newKeySet<String>()
    @Volatile var down = false
    override fun isBlocked(key: String): Boolean { if (down) throw IllegalStateException("redis aus"); return key in entries }
    override fun block(key: String, ttl: Duration) { if (down) throw IllegalStateException("redis aus"); entries += key }
    override fun clear(key: String) { if (down) throw IllegalStateException("redis aus"); entries -= key }
}

/**
 * WP-M1-10 / LH-6.2-IAM-009 auf Modulebene gegen die echte Datenbank (Runtime-Rolle): Sitzungsprüfung je Anfrage, Sperren, Abmelden aller Geräte,
 * Kontosperre, Wiederherstellung, Auditspur, Mandantentrennung, Ausfall des Caches. Die HTTP-Sicht (401/403/404 an der echten API) prüft
 * `de.taktovia.app.identity.SessionApiTest`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionRevocationTest {
    private lateinit var fx: IdentityFixture
    private val cache = FlakyBlockCache()
    private val idp = RecordingIdp()
    private lateinit var api: IdentityApi

    @BeforeAll fun up() {
        fx = IdentityFixture(24)
        api = IdentityApi.create(fx.tx, fx.audit, SecurityEventPort(fx.audit), fx.outbox, idp = idp, blockCache = cache)
    }
    @AfterAll fun down() = fx.close()

    private fun ctx(t: TenantId, subject: String, m: UUID, role: String = "tenant_admin", sid: String = "sid-ctx-$subject") =
        RequestContext(de.taktovia.platform.context.Actor(ActorType.HUMAN, fx.issuer, subject, sid, true), t, setOf(role),
            api.scopePolicy.scopesFor(setOf(role)), emptySet(), UUID.randomUUID(), membershipIds = setOf(m))

    private fun ref(subject: String, sid: String = "sid-" + UUID.randomUUID(), iat: Instant = Instant.now()) = SessionRef(fx.issuer, subject, sid, iat)
    private fun check(t: TenantId, r: SessionRef) = api.sessionGate.check(t, r)

    /** Mandant mit Konto-Mitgliedschaft und Administrator. */
    private class World(val t: TenantId, val user: String, val userM: UUID, val admin: String, val adminM: UUID)
    private fun world(): World {
        val t = fx.tenant(); val u = UUID.randomUUID().toString(); val a = UUID.randomUUID().toString()
        return World(t, u, fx.seedMembership(t, u, "dispatcher"), a, fx.seedMembership(t, a, "tenant_admin"))
    }

    @Test
    fun gesperrte_sitzung_wird_von_der_pruefung_abgewiesen() {
        val w = world(); val r = ref(w.user)
        assertEquals(SessionVerdict.ACTIVE, check(w.t, r), "erste Anfrage registriert die Sitzung")
        assertEquals(SessionVerdict.ACTIVE, check(w.t, r))
        val views = api.sessions.listOwn(ctx(w.t, w.user, w.userM, "dispatcher"), 10, null)
        assertEquals(1, views.size); assertEquals(null, views[0].revokedAt)
        val out = api.sessions.revokeOwn(ctx(w.t, w.user, w.userM, "dispatcher"), views[0].id, views[0].revision)
        assertInstanceOf(RevokeSessionOutcome.Revoked::class.java, out)
        assertEquals(SessionVerdict.REVOKED, check(w.t, r), "trotz noch gültigem Token keine geschützte API mehr")
        assertTrue("end:${r.idpSessionId}" in idp.calls, "IdP-Sitzung beendet: Refresh nicht mehr möglich")
        // die Sperre ist endgültig und trifft nur diese Sitzung
        assertEquals(SessionVerdict.ACTIVE, check(w.t, ref(w.user)))
        assertEquals(RevokeSessionOutcome.AlreadyRevoked, api.sessions.revokeOwn(ctx(w.t, w.user, w.userM, "dispatcher"), views[0].id, views[0].revision + 1))
    }

    @Test
    fun abmelden_und_erneut_anmelden() {
        val w = world(); val first = ref(w.user)
        assertEquals(SessionVerdict.ACTIVE, check(w.t, first))
        val c = ctx(w.t, w.user, w.userM, "dispatcher")
        api.sessions.revokeAllOwn(c)
        assertEquals(SessionVerdict.REVOKED, check(w.t, first))
        Thread.sleep(1100)   // Abmeldezeitmarke hat Sekundengranularität (token-iat in Sekunden)
        val relogin = ref(w.user)
        assertEquals(SessionVerdict.ACTIVE, check(w.t, relogin), "neue Anmeldung nach der Abmeldung ist möglich")
        assertEquals(SessionVerdict.ACTIVE, check(w.t, relogin))
    }

    @Test
    fun alle_geraete_abmelden_zweites_geraet_gesperrt() {
        val a = fx.tenant(); val b = fx.tenant(); val s = UUID.randomUUID().toString()
        val ma = fx.seedMembership(a, s, "dispatcher"); fx.seedMembership(b, s, "support")
        val phone = ref(s); val laptop = ref(s); val neverSeenInA = ref(s, iat = Instant.now().minusSeconds(30))
        listOf(phone, laptop).forEach { assertEquals(SessionVerdict.ACTIVE, check(a, it)) }
        assertEquals(SessionVerdict.ACTIVE, check(b, phone), "die Sitzung ist auch in Mandant B registriert")
        val idpBefore = idp.calls.size
        assertTrue(api.sessions.revokeAllOwn(ctx(a, s, ma, "dispatcher")).idpSynced)
        listOf(phone, laptop).forEach { assertEquals(SessionVerdict.REVOKED, check(a, it), "zweites Gerät erhält 401") }
        assertEquals(SessionVerdict.REVOKED, check(b, phone), "alle Mandanten des Kontos")
        assertEquals(SessionVerdict.REVOKED, check(a, neverSeenInA), "vor der Abmeldung ausgestelltes Token einer noch nie gesehenen Sitzung")
        assertTrue(idp.calls.drop(idpBefore).any { it == "endAll:$s" }, "IdP-Sitzungen des Kontos beendet")
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='security.session.logout_all' AND actor_ref=?", a.value, "user:$s"))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='security.session.logout_all'", b.value), "Auditspur auch im weiteren Mandanten")
    }

    @Test
    fun sperre_waehrend_50_parallelen_anfragen() {
        val w = world(); val r = ref(w.user)
        assertEquals(SessionVerdict.ACTIVE, check(w.t, r))
        val sid = api.sessions.listOwn(ctx(w.t, w.user, w.userM, "dispatcher"), 10, null).single()
        data class Obs(val startNanos: Long, val verdict: SessionVerdict)
        val observations = CopyOnWriteArrayList<Obs>(); val stop = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(50); val ready = CountDownLatch(50)
        val workers = (1..50).map {
            pool.submit {
                ready.countDown()
                while (!stop.get()) { val t0 = System.nanoTime(); observations += Obs(t0, check(w.t, r)) }
            }
        }
        ready.await(); Thread.sleep(300)
        val out = api.sessions.revokeOwn(ctx(w.t, w.user, w.userM, "dispatcher"), sid.id, sid.revision)
        val commitDone = System.nanoTime()
        assertInstanceOf(RevokeSessionOutcome.Revoked::class.java, out)
        Thread.sleep(300); stop.set(true); workers.forEach { it.get(30, TimeUnit.SECONDS) }; pool.shutdown()
        val before = observations.count { it.startNanos < commitDone }; val after = observations.filter { it.startNanos > commitDone }
        assertTrue(before > 50 && after.size > 50, "genug Anfragen vor ($before) und nach (${after.size}) der Sperre")
        assertEquals(emptyList<Obs>(), after.filter { it.verdict != SessionVerdict.REVOKED }, "keine nach dem Commit begonnene Anfrage darf erfolgreich sein")
        assertTrue(observations.any { it.verdict == SessionVerdict.ACTIVE }, "vor der Sperre war die Sitzung gültig")
    }

    @Test
    fun redis_ausfall_fail_closed() {
        val w = world(); val r = ref(w.user); val ok = ref(w.user)
        assertEquals(SessionVerdict.ACTIVE, check(w.t, r)); assertEquals(SessionVerdict.ACTIVE, check(w.t, ok))
        val c = ctx(w.t, w.user, w.userM, "dispatcher")
        val v = api.sessions.listOwn(c, 10, null).first { it.id == de.taktovia.identity.api.SessionIds.of(r.idpSessionId) }
        cache.down = true
        try {
            // Sperre bei ausgefallenem Redis: die Datenbank führt und erzwingt sie weiter
            assertInstanceOf(RevokeSessionOutcome.Revoked::class.java, api.sessions.revokeOwn(c, v.id, v.revision))
            assertEquals(SessionVerdict.REVOKED, check(w.t, r), "Cache aus: Datenbank entscheidet, gesperrt bleibt gesperrt")
            assertEquals(SessionVerdict.ACTIVE, check(w.t, ok), "Cache aus: nicht gesperrte Sitzung bleibt über die Datenbank nutzbar")
        } finally { cache.down = false }
        // Ist auch die Datenbank nicht erreichbar, wird verweigert (Ausnahme statt Freigabe)
        val dead = IdentityTestDb.runtime(1).also { it.close() }
        val brokenGate = IdentityApi.create(TenantTransactions(dead), fx.audit, SecurityEventPort(fx.audit), fx.outbox, blockCache = FlakyBlockCache().also { it.down = true })
        assertThrows<SessionCheckUnavailableException> { brokenGate.sessionGate.check(w.t, ok) }
    }

    @Test
    fun sperrliste_im_cache_setzt_vor_dem_commit_und_entfernt_bei_rollback() {
        val w = world(); val r = ref(w.user)
        assertEquals(SessionVerdict.ACTIVE, check(w.t, r))
        val c = ctx(w.t, w.user, w.userM, "dispatcher")
        val v = api.sessions.listOwn(c, 10, null).single()
        // veraltete Revision: keine Änderung, der Cache bekommt keinen bleibenden Eintrag
        val before = cache.entries.size
        assertEquals(RevokeSessionOutcome.StaleRevision, api.sessions.revokeOwn(c, v.id, v.revision + 7))
        assertEquals(before, cache.entries.size, "keine Sperre, kein Eintrag")
        assertEquals(SessionVerdict.ACTIVE, check(w.t, r))
        api.sessions.revokeOwn(c, v.id, v.revision)
        assertEquals(before + 1, cache.entries.size, "Sperre trägt ihren Eintrag in die Sperrliste ein")
        // Treffer in der Sperrliste weist ab, ohne die Datenbank zu fragen
        val dead = IdentityApi.create(TenantTransactions(IdentityTestDb.runtime(1).also { it.close() }), fx.audit, SecurityEventPort(fx.audit), fx.outbox, blockCache = cache)
        assertEquals(SessionVerdict.REVOKED, dead.sessionGate.check(w.t, r))
    }

    @Test
    fun sperrversuch_unberechtigte_rolle_verweigert() {
        val w = world()
        listOf("dispatcher", "support", "passenger", "driver", "callcenter", "planner").forEach { role ->
            val c = ctx(w.t, UUID.randomUUID().toString(), UUID.randomUUID(), role)
            listOf("lockAccount", "unlockAccount", "startAccountRecovery", "revokeSession").forEach { op ->
                val scope = if (op == "revokeSession") "sessions:write" else "accounts:write"
                assertTrue(api.authorizer.decide(c, op, listOf(scope)) is Decision.Denied, "$role darf $op nicht")
            }
        }
        val admin = ctx(w.t, w.admin, w.adminM, "tenant_admin")
        listOf("lockAccount" to "accounts:write", "unlockAccount" to "accounts:write", "startAccountRecovery" to "accounts:write", "revokeSession" to "sessions:write").forEach { (op, sc) ->
            assertEquals(Decision.Allowed, api.authorizer.decide(admin, op, listOf(sc)), "tenant_admin darf $op")
        }
        // jede Rolle darf sich selbst abmelden
        listOf("passenger", "driver", "dispatcher").forEach { role ->
            assertEquals(Decision.Allowed, api.authorizer.decide(ctx(w.t, "x", UUID.randomUUID(), role), "revokeAllMySessions", listOf("me:write")))
        }
    }

    @Test
    fun wiederherstellung_nur_verifizierter_kontakt() {
        val w = world(); val r = ref(w.user)
        assertEquals(SessionVerdict.ACTIVE, check(w.t, r))
        val admin = ctx(w.t, w.admin, w.adminM)
        idp.verified = false
        val denied = api.sessions.startRecovery(admin, UUID.fromString(w.user))
        assertEquals(RecoveryOutcome.ContactNotVerified, denied)
        assertEquals(SessionVerdict.ACTIVE, check(w.t, r), "unverifizierter Kontakt: keine Wiederherstellung, Sitzungen bleiben")
        assertFalse(idp.calls.any { it == "recovery:${w.user}" })
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='security.account.recovery_denied'", w.t.value))
        idp.verified = true
        val ok = api.sessions.startRecovery(admin, UUID.fromString(w.user))
        assertInstanceOf(RecoveryOutcome.Started::class.java, ok)
        assertEquals(SessionVerdict.REVOKED, check(w.t, r), "Wiederherstellung beendet alle bestehenden Sitzungen")
        assertTrue(idp.calls.containsAll(listOf("endAll:${w.user}", "recovery:${w.user}")))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='security.account.recovery_started' AND actor_ref=?", w.t.value, "user:${w.admin}"))
        assertEquals(RecoveryOutcome.UnknownAccount, api.sessions.startRecovery(admin, UUID.randomUUID()), "Konto ohne Mitgliedschaft im Mandanten")
    }

    @Test
    fun fremder_mandant_sitzung_404() {
        val w = world(); val other = world()
        val r = ref(w.user); check(w.t, r)
        val sessionId = api.sessions.list(ctx(w.t, w.admin, w.adminM), 10, null).single().id
        val foreignAdmin = ctx(other.t, other.admin, other.adminM)
        assertEquals(RevokeSessionOutcome.NotFound, api.sessions.revoke(foreignAdmin, sessionId, 1), "fremder Mandant kann die Sitzung nicht sperren")
        assertEquals(emptyList<Any>(), api.sessions.list(foreignAdmin, 10, null), "und nicht lesen")
        assertEquals(RevokeSessionOutcome.NotFound, api.sessions.revokeOwn(ctx(w.t, UUID.randomUUID().toString(), w.userM, "dispatcher"), sessionId, 1), "eine fremde Sitzung im eigenen Mandanten ist nicht auffindbar")
        assertEquals(SessionVerdict.ACTIVE, check(w.t, r))
        assertEquals(LockOutcome.UnknownAccount, api.sessions.lock(foreignAdmin, UUID.fromString(w.user), "x"), "fremdes Konto nicht sperrbar")
        assertEquals(SessionVerdict.ACTIVE, check(w.t, r))
    }

    @Test
    fun sperre_entsperrung_auditeintrag() {
        val w = world(); val r = ref(w.user)
        assertEquals(SessionVerdict.ACTIVE, check(w.t, r))
        val admin = ctx(w.t, w.admin, w.adminM)
        assertEquals(LockOutcome.SelfLock, api.sessions.lock(admin, UUID.fromString(w.admin), "selbst"))
        val locked = api.sessions.lock(admin, UUID.fromString(w.user), "Verdacht")
        assertInstanceOf(LockOutcome.Locked::class.java, locked); locked as LockOutcome.Locked
        assertEquals(SessionVerdict.ACCOUNT_LOCKED, check(w.t, r))
        assertEquals(SessionVerdict.ACCOUNT_LOCKED, check(w.t, ref(w.user)), "auch eine neue Sitzung des gesperrten Kontos")
        assertEquals(LockOutcome.AlreadyLocked, api.sessions.lock(admin, UUID.fromString(w.user), "nochmal"))
        assertTrue(idp.calls.contains("endAll:${w.user}") && idp.calls.contains("enabled=false:${w.user}"), "einziger Mandant: IdP-Konto gesperrt")
        assertEquals(UnlockOutcome.StaleRevision, api.sessions.unlock(admin, UUID.fromString(w.user), locked.view.revision + 5))
        assertInstanceOf(UnlockOutcome.Unlocked::class.java, api.sessions.unlock(admin, UUID.fromString(w.user), locked.view.revision))
        assertEquals(UnlockOutcome.NotLocked, api.sessions.unlock(admin, UUID.fromString(w.user), locked.view.revision + 1))
        assertEquals(SessionVerdict.REVOKED, check(w.t, r), "gesperrte Sitzungen bleiben nach der Entsperrung gesperrt")
        Thread.sleep(1100)
        assertEquals(SessionVerdict.ACTIVE, check(w.t, ref(w.user)), "neue Anmeldung nach Entsperrung")
        for (action in listOf("security.account.locked", "security.account.unlocked")) {
            assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action=? AND actor_ref=? AND object_ref=? AND occurred_at > now() - interval '5 minutes'",
                w.t.value, action, "user:${w.admin}", "account:${w.user}"), "Auditeintrag mit Akteur und Zeitpunkt: $action")
        }
        val v = api.sessions.listOwn(ctx(w.t, w.user, w.userM, "dispatcher"), 10, null).first()
        // eine Sitzungssperre durch den Administrator erzeugt ebenfalls genau einen Eintrag
        val r2 = ref(w.user); check(w.t, r2)
        val target = api.sessions.list(admin, 50, null).first { it.id == de.taktovia.identity.api.SessionIds.of(r2.idpSessionId) }
        assertInstanceOf(RevokeSessionOutcome.Revoked::class.java, api.sessions.revoke(admin, target.id, target.revision))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='security.session.revoked' AND actor_ref=? AND object_ref=?", w.t.value, "user:${w.admin}", "session:${target.id}"))
        assertTrue(v.id != target.id)
    }

    @Test
    fun gleichzeitige_sperre_und_entsperrung_definierter_endzustand() {
        repeat(5) {
            val w = world(); val admin = ctx(w.t, w.admin, w.adminM); val subject = UUID.fromString(w.user)
            val first = api.sessions.lock(admin, subject, "start") as LockOutcome.Locked
            val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
            val unlock = pool.submit<Any> { start.await(); api.sessions.unlock(admin, subject, first.view.revision) }
            val lock = pool.submit<Any> { start.await(); api.sessions.lock(admin, subject, "zweite") }
            start.countDown()
            val u = unlock.get(30, TimeUnit.SECONDS); val l = lock.get(30, TimeUnit.SECONDS); pool.shutdown()
            val verdict = check(w.t, ref(w.user, iat = Instant.now().plusSeconds(5)))
            val locks = fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='security.account.locked'", w.t.value)
            val unlocks = fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='security.account.unlocked'", w.t.value)
            when {
                // Entsperren zuerst, danach neu gesperrt: Endzustand gesperrt, zwei Sperr- und ein Entsperrereignis
                l is LockOutcome.Locked -> { assertInstanceOf(UnlockOutcome.Unlocked::class.java, u); assertEquals(SessionVerdict.ACCOUNT_LOCKED, verdict); assertEquals(2 to 1, locks to unlocks) }
                // Sperre sah noch „gesperrt“ (Konflikt), Entsperren danach: Endzustand aktiv
                else -> { assertEquals(LockOutcome.AlreadyLocked, l); assertInstanceOf(UnlockOutcome.Unlocked::class.java, u); assertEquals(SessionVerdict.ACTIVE, verdict); assertEquals(1 to 1, locks to unlocks) }
            }
        }
    }
}
