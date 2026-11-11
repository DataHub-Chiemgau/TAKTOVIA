package de.taktovia.app

import de.taktovia.app.probe.ProbeDb
import de.taktovia.app.probe.ProbeServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** 429 mit Retry-After; Quote je Mandant und Akteur, ohne Einfluss auf andere (Kap. 05 §5.1). */
class RateLimitTest {
    private val s get() = ProbeServer.get("taktovia.api.rate-limit.requests=5", "taktovia.api.rate-limit.window-seconds=60")
    private fun hdr(tenant: java.util.UUID, actor: String) = mapOf("X-Test-Tenant" to tenant.toString(), "X-Test-Actor" to actor)

    @Test
    fun quote_429_retry_after_ohne_einfluss_auf_andere() {
        val abuser = hdr(ProbeDb.tenantA, "abuser")
        val statuses = (1..8).map { s.get("/v1/probe/items?limit=1", abuser).status }
        assertEquals(List(5) { 200 } + List(3) { 429 }, statuses)
        val denied = s.get("/v1/probe/items?limit=1", abuser)
        assertEquals(429, denied.status)
        assertEquals("RATE_LIMITED", denied.json["code"].asString())
        val retry = denied.header("Retry-After")!!.toInt()
        assertTrue(retry in 1..60, "Retry-After $retry")
        assertEquals("application/problem+json", denied.header("Content-Type")!!.substringBefore(';'))
        // anderer Akteur im selben Mandanten, anderer Mandant mit demselben Akteurnamen: unbeeinflusst
        assertEquals(200, s.get("/v1/probe/items?limit=1", hdr(ProbeDb.tenantA, "legitim")).status)
        assertEquals(200, s.get("/v1/probe/items?limit=1", hdr(ProbeDb.tenantB, "abuser")).status)
    }

    @Test
    fun quotenzaehler_bleiben_unter_paralleler_last_getrennt() {
        val pool = Executors.newFixedThreadPool(16)
        try {
            fun burst(h: Map<String, String>, n: Int) = (1..n).map { pool.submit(Callable { s.get("/v1/probe/items?limit=1", h).status }) }
            val heavy = burst(hdr(ProbeDb.tenantA, "parallel-heavy"), 40)
            val light = burst(hdr(ProbeDb.tenantB, "parallel-light"), 4)
            val h = heavy.map { it.get() }; val l = light.map { it.get() }
            assertEquals(5, h.count { it == 200 }, "genau die Quote wird gewährt, auch parallel: $h")
            assertEquals(35, h.count { it == 429 })
            assertEquals(List(4) { 200 }, l, "der legitime Akteur eines anderen Mandanten bleibt unbeeinträchtigt")
        } finally { pool.shutdownNow() }
    }
}
