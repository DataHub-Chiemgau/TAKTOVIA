package de.taktovia.app.identity

import de.taktovia.app.probe.Resp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** LH-I.4-MUL-006 / WP-M1-06: serverseitiger Tenantwechsel über die echte Anwendung. */
class TenantSwitchApiTest {
    private val env = IdentityEnv

    private fun problemShape(r: Resp) = listOf("status", "code", "type", "title").associateWith { r.json[it].toString() }

    @Test
    fun fremde_mitgliedschaft_ergibt_403_und_manipulierter_tenant_404() {
        val a = env.tenant("A-" + UUID.randomUUID()); val b = env.tenant("B-" + UUID.randomUUID()); val foreign = env.tenant("F-" + UUID.randomUUID())
        val s = UUID.randomUUID(); env.grant(a, s, "dispatcher"); env.grant(b, s, "support"); env.grant(foreign, UUID.randomUUID(), "tenant_admin")
        val token = env.token(s)

        val noMembership = env.switchTo(token, foreign)
        val noTenant = env.switchTo(token, de.taktovia.platform.tenant.TenantId(UUID.randomUUID()))
        assertEquals(403, noMembership.status, noMembership.text); assertEquals(403, noTenant.status)
        assertEquals(problemShape(noMembership), problemShape(noTenant), "existiert der Mandant? nicht unterscheidbar")
        assertFalse(noMembership.text.contains(foreign.toString()))
        assertEquals(0, env.events(foreign, "identity.tenant.switched"))
        assertTrue(env.events(a, "security.access.denied") + env.events(b, "security.access.denied") >= 2, "Verweigerungen als Sicherheitsereignis")

        // manipulierter Mandantenheader an einem normalen Endpunkt: 404 wie nicht vorhanden
        val ok = env.switchTo(token, b)
        assertEquals(200, ok.status, ok.text)
        val manip = env.get("/v1/me", token, ok.json["context_token"].asString(), mapOf("X-Tenant-Id" to a.toString()))
        assertEquals(404, manip.status)
        assertTrue(env.events(b, "security.tenant.mismatch") >= 1)
    }

    @Test
    fun wechsel_liefert_kontextbindung_und_jeder_tab_sieht_nur_seinen_mandanten() {
        val a = env.tenant("Alpha-" + UUID.randomUUID()); val b = env.tenant("Beta-" + UUID.randomUUID())
        val s = UUID.randomUUID(); env.grant(a, s, "tenant_admin"); env.grant(b, s, "support")
        repeat(2) { env.grant(a, UUID.randomUUID(), "support") }   // A hat 3 Mitgliedschaften, B hat 1
        val token = env.token(s)

        val toA = env.switchTo(token, a); val toB = env.switchTo(token, b)
        assertEquals(200, toA.status); assertEquals(200, toB.status)
        assertEquals(b.toString(), toB.json["tenant_id"].asString()); assertEquals(listOf("support"), toB.json["roles"].values().map { it.asString() })
        assertNotEquals(toA.json["context_token"].asString(), toB.json["context_token"].asString())
        assertEquals(1, env.events(b, "identity.tenant.switched"), "Wechsel auditiert im Zielmandanten")
        val ctxA = toA.json["context_token"].asString(); val ctxB = toB.json["context_token"].asString()

        // in B ist die Sitzung nur Support: keine Mitgliederverwaltung; in A als Admin schon, und nur mit A-Zeilen
        assertEquals(403, env.get("/v1/memberships", token, ctxB).status, "Rechte folgen dem aktiven Mandanten")
        val listA = env.get("/v1/memberships", token, ctxA)
        assertEquals(200, listA.status); assertEquals(3, listA.json["items"].size())
    }

    @Test
    fun zwei_tabs_unterschiedlicher_mandant_unabhaengig() {
        val a = env.tenant("Alpha-" + UUID.randomUUID()); val b = env.tenant("Beta-" + UUID.randomUUID())
        val s = UUID.randomUUID(); env.grant(a, s, "support"); env.grant(b, s, "support")
        val token = env.token(s)
        val ctxA = env.switchTo(token, a).json["context_token"].asString(); val ctxB = env.switchTo(token, b).json["context_token"].asString()
        val names = mapOf(true to a.toString(), false to b.toString())
        val pool = Executors.newFixedThreadPool(8); val start = CountDownLatch(1)
        val futures = (1..160).map { i ->
            pool.submit<Pair<Boolean, String>> {
                start.await()
                val aTab = i % 2 == 0
                aTab to env.get("/v1/me", token, if (aTab) ctxA else ctxB).json["tenant_id"].asString()
            }
        }
        start.countDown()
        val res = futures.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        res.forEach { (aTab, tenant) -> assertEquals(names[aTab], tenant, "Tab ${if (aTab) "A" else "B"} sah einen fremden Kontext") }
        assertEquals(160, res.size)
    }

    @Test
    fun kontextbindung_ist_an_konto_und_sitzung_gebunden_und_faelschungssicher() {
        val a = env.tenant(); val b = env.tenant()
        val s = UUID.randomUUID(); val other = UUID.randomUUID()
        env.grant(a, s, "support"); env.grant(b, s, "support"); env.grant(a, other, "support"); env.grant(b, other, "support")
        val token = env.token(s, sid = "sid-fest-0001")
        val ctxB = env.switchTo(token, b).json["context_token"].asString()
        assertEquals(b.toString(), env.get("/v1/me", token, ctxB).json["tenant_id"].asString())
        assertEquals(b.toString(), env.get("/v1/me", env.token(s, sid = "sid-fest-0001"), ctxB).json["tenant_id"].asString(), "neues Token derselben Sitzung (Refresh) behält die Bindung")

        val forged = listOf(ctxB.dropLast(2) + "xx", "v1.Zm9v.Zm9v", "garbage", ctxB + "A", "")
        forged.forEach { assertEquals(403, env.get("/v1/me", token, it).status, "gefälscht: $it") }
        assertEquals(403, env.get("/v1/me", env.token(other, sid = "sid-fest-0001"), ctxB).status, "Bindung eines anderen Kontos")
        assertEquals(403, env.get("/v1/me", env.token(s, sid = "andere-sitzung-9"), ctxB).status, "Bindung einer anderen IdP-Sitzung")
    }

    @Test
    fun wechsel_in_dienst_ohne_freigabe_wird_abgewiesen() {
        val t = env.tenant(); val s = UUID.randomUUID(); val allowed = UUID.randomUUID()
        env.grant(t, s, "dispatcher", listOf(allowed))
        val token = env.token(s)
        assertEquals(403, env.switchTo(token, t, UUID.randomUUID()).status, "nicht freigegebener Dienst")
        val ok = env.switchTo(token, t, allowed)
        assertEquals(200, ok.status, ok.text)
        assertEquals(listOf(allowed.toString()), ok.json["service_scope"].values().map { it.asString() })
        val viaTicket = env.get("/v1/me", token, ok.json["context_token"].asString()).json
        assertEquals(listOf(allowed.toString()), viaTicket["service_scope"].values().map { it.asString() })
        // Mitgliedschaft ohne Dienstliste: jeder Dienstwechsel scheitert
        val s2 = UUID.randomUUID(); env.grant(t, s2, "support")
        assertEquals(403, env.switchTo(env.token(s2), t, allowed).status)
    }

    @Test
    fun wechsel_mit_widerrufener_mitgliedschaft_scheitert_und_alte_bindung_wird_wirkungslos() {
        val a = env.tenant(); val b = env.tenant(); val s = UUID.randomUUID()
        env.grant(a, s, "support"); val mb = env.grant(b, s, "support")
        val token = env.token(s)
        val ctxB = env.switchTo(token, b).json["context_token"].asString()
        env.revokeDirect(b, mb)
        assertEquals(403, env.get("/v1/me", token, ctxB).status, "Bindung ohne gültige Mitgliedschaft öffnet nichts")
        assertEquals(403, env.switchTo(token, b).status)
        assertEquals(200, env.get("/v1/me", token).status, "ohne Bindung bleibt der Standardmandant A")
    }

    @Test
    fun widerruf_waehrend_anfragen_beendet_zugriff() {
        val t = env.tenant(); val adminSubject = UUID.randomUUID(); val victim = UUID.randomUUID()
        env.grant(t, adminSubject, "tenant_admin")
        val vm = env.grant(t, victim, "tenant_admin")
        val adminToken = env.token(adminSubject); val victimToken = env.token(victim)
        val n = 6; val pool = Executors.newFixedThreadPool(n)
        val revokedAt = AtomicLong(Long.MAX_VALUE); val stop = AtomicBoolean(false)
        val okBefore = AtomicInteger(); val violations = AtomicInteger(); val deniedAfter = AtomicInteger(); val unexpected = AtomicInteger()
        val workers = (1..n).map {
            pool.submit {
                while (!stop.get()) {
                    val started = System.nanoTime()
                    val status = env.get("/v1/memberships", victimToken).status
                    when {
                        started > revokedAt.get() -> if (status == 200) violations.incrementAndGet() else if (status == 403) deniedAfter.incrementAndGet() else unexpected.incrementAndGet()
                        status == 200 -> okBefore.incrementAndGet()
                        status != 403 -> unexpected.incrementAndGet()
                    }
                }
            }
        }
        Thread.sleep(400)
        val del = env.server.send("DELETE", "/v1/memberships/$vm", null, env.headers(adminToken, extra = mapOf("If-Match" to "\"1\"", "Idempotency-Key" to "k-" + UUID.randomUUID())))
        assertEquals(204, del.status, del.text)
        revokedAt.set(System.nanoTime())
        Thread.sleep(600); stop.set(true)
        workers.forEach { it.get(30, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(0, violations.get(), "keine erfolgreiche Anfrage nach dem Commit des Widerrufs")
        assertEquals(0, unexpected.get())
        assertTrue(okBefore.get() > 0 && deniedAfter.get() > 0, "vor dem Widerruf erfolgreich (${okBefore.get()}), danach verweigert (${deniedAfter.get()})")
        assertEquals(403, env.get("/v1/memberships", victimToken).status)
    }
}
