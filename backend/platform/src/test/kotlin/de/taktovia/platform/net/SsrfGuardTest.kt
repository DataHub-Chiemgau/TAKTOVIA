package de.taktovia.platform.net

import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.URI

/** WP-M1-38: SSRF-Ziele (interne, Loopback-, Metadaten-, private Adressen) werden abgewiesen; öffentliche https-Ziele sind zulässig. */
class SsrfGuardTest {
    private val dns = mapOf(
        "partner.example.org" to listOf("93.184.216.34"),
        "rebind.example.org" to listOf("93.184.216.34", "10.0.0.5"),
        "intern.example.org" to listOf("192.168.1.10"),
        "meta.example.org" to listOf("169.254.169.254"),
        "v6.example.org" to listOf("2606:4700:4700::1111"),
        "v6loop.example.org" to listOf("::1"),
        "mapped.example.org" to listOf("::ffff:127.0.0.1"),
    )
    private val guard = SsrfGuard(resolver = { h -> dns[h]?.map { InetAddress.getByName(it) } ?: throw java.net.UnknownHostException(h) })

    private fun literalOk(u: String) = guard.checkLiteral(u, "/url").isEmpty()

    @Test
    fun oeffentliche_https_ziele_sind_zulaessig() {
        assertTrue(literalOk("https://partner.example.org/hooks/taktovia"))
        assertTrue(literalOk("https://partner.example.org:443/x?y=1"))
        assertTrue(literalOk("https://sub.partner.example.org./x"))
        assertTrue(literalOk("https://93.184.216.34/hook"))
        assertTrue(literalOk("https://[2606:4700:4700::1111]/hook"))
        val t = guard.resolve("https://partner.example.org/hooks").getOrThrow()
        assertEquals(listOf("93.184.216.34"), t.addresses.map { it.hostAddress })
        assertEquals(443, t.port)
        assertEquals("2606:4700:4700:0:0:0:0:1111", guard.resolve("https://v6.example.org/").getOrThrow().addresses.single().hostAddress)
    }

    @Test
    fun interne_loopback_metadaten_und_private_ziele_werden_abgewiesen() {
        val bad = listOf(
            "https://127.0.0.1/hook", "https://127.1/hook", "https://localhost/hook", "https://LOCALHOST./hook", "https://api.localhost/hook",
            "https://[::1]/hook", "https://[::ffff:127.0.0.1]/hook", "https://[fe80::1]/hook", "https://[fd00::1]/hook", "https://[::]/hook", "https://[2001:db8::1]/hook",
            "https://169.254.169.254/latest/meta-data", "https://10.0.0.1/", "https://172.16.0.1/", "https://172.31.255.255/", "https://192.168.0.1/", "https://100.64.0.1/",
            "https://0.0.0.0/", "https://224.0.0.1/", "https://255.255.255.255/", "https://198.18.0.1/",
            // ungewöhnliche Schreibweisen derselben Adresse
            "https://2130706433/hook", "https://0x7f.0.0.1/hook", "https://0177.0.0.1/hook", "https://0x7f000001/", "https://127.000.000.001/", "https://017700000001/",
            // interne Namen
            "https://intranet/hook", "https://db.internal/", "https://printer.local/", "https://host.corp/", "https://kubernetes.default.svc.cluster.local/".replace(".local", ".internal"),
            // Schema, Zugangsdaten, Ports, Form
            "http://partner.example.org/hook", "ftp://partner.example.org/x", "file:///etc/passwd", "gopher://partner.example.org/", "javascript:alert(1)",
            "https://user:pw@partner.example.org/", "https://partner.example.org@127.0.0.1/", "https://partner.example.org:8080/", "https://partner.example.org:22/",
            "https://partner.example.org/ x", "https://partner.example.org/\u0000", "https://", "https:///x", "//partner.example.org/x", "partner.example.org/x", "",
            "https://пример.рф/hook", "https://partner.example.org\\@127.0.0.1/", "https://" + "a".repeat(70) + ".example.org/",
        )
        for (u in bad) assertEquals(listOf("/url" to ViolationCode.TARGET_NOT_ALLOWED), guard.checkLiteral(u, "/url").map { it.pointer to it.code }, u)
    }

    @Test
    fun aufloesung_prueft_alle_adressen_gegen_dns_rebinding_und_umleitung_auf_interne_ziele() {
        for (h in listOf("rebind.example.org", "intern.example.org", "meta.example.org", "v6loop.example.org", "mapped.example.org", "unbekannt.example.org"))
            assertTrue(guard.resolve("https://$h/hook").isFailure, h)
        // Weiterleitung eines öffentlichen Ziels auf ein internes wird verworfen, auf ein öffentliches ist zulässig
        val from = URI("https://partner.example.org/start")
        assertTrue(guard.resolveRedirect(from, "https://meta.example.org/x").isFailure)
        assertTrue(guard.resolveRedirect(from, "http://partner.example.org/x").isFailure)
        assertTrue(guard.resolveRedirect(from, "/weiter").isSuccess)
        // IP-Literale brauchen kein DNS: der Resolver wird mit ihnen nie aufgerufen
        var called = false
        val literalOnly = SsrfGuard(resolver = { called = true; emptyList() })
        assertTrue(literalOnly.resolve("https://93.184.216.34/x").isSuccess)
        assertTrue(literalOnly.resolve("https://10.0.0.1/x").isFailure)
        assertTrue(!called, "kein DNS für IP-Literale")
    }

    @Test
    fun freigegebene_ports_und_http_sind_konfigurierbar() {
        val g = SsrfGuard(allowedPorts = setOf(443, 8443), allowHttp = true, resolver = { listOf(InetAddress.getByName("93.184.216.34")) })
        assertTrue(g.checkLiteral("https://partner.example.org:8443/x", "/u").isEmpty())
        assertTrue(g.checkLiteral("http://partner.example.org:443/x", "/u").isEmpty(), "http ist nur auf ausdrücklich erlaubten Ports und nur wenn aktiviert zulässig")
        assertEquals(1, g.checkLiteral("https://partner.example.org:9443/x", "/u").size)
        // auch mit erlaubtem http bleiben private Ziele verboten
        assertEquals(1, g.checkLiteral("http://10.0.0.1:443/x", "/u").size)
    }

    @Test
    fun isPublic_trennt_oeffentlich_von_nicht_oeffentlich() {
        for (ok in listOf("8.8.8.8", "93.184.216.34", "1.1.1.1", "2606:4700:4700::1111", "172.32.0.1", "172.15.255.255", "100.63.255.255", "100.128.0.1"))
            assertTrue(SsrfGuard.isPublic(InetAddress.getByName(ok)), ok)
        for (no in listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.0.1", "192.168.5.5", "169.254.1.1", "100.64.0.1", "100.127.255.255", "0.0.0.0", "224.0.0.1", "240.0.0.1", "::1", "::", "fc00::1", "fe80::1", "ff02::1", "2001:db8::1", "2002::1", "64:ff9b::1"))
            assertTrue(!SsrfGuard.isPublic(InetAddress.getByName(no)), no)
    }
}
