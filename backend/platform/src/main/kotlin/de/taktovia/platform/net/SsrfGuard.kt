package de.taktovia.platform.net

import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import java.math.BigInteger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

/** Eine geprüfte Zieladresse: an genau diese IPs wird verbunden (kein erneutes Auflösen, gegen DNS-Rebinding). */
data class SafeTarget(val uri: URI, val port: Int, val addresses: List<InetAddress>)

/**
 * SSRF-Baustein (WP-M1-38, gemeinsam mit WP-M1-16 Webhooks): prüft vom Mandanten oder Partner vorgegebene Ziel-URLs, bevor
 * die Plattform sie aufruft. Zulässig sind nur `https`-Ziele mit öffentlichen Adressen auf freigegebenen Ports. Abgewiesen werden
 * Loopback, Link-Local (auch Cloud-Metadaten 169.254.169.254), private und reservierte Bereiche, IPv4-in-IPv6, ungewöhnliche
 * IP-Schreibweisen (dezimal, oktal, hex), interne Hostnamen und Zugangsdaten in der URL.
 *
 * Zwei Stufen: [checkLiteral] prüft ohne DNS (für die Eingabevalidierung); [resolve] löst auf und prüft **alle** Adressen
 * (beim Speichern und unmittelbar vor jedem Aufruf, auch für jede Weiterleitung). Der Aufrufer verbindet mit [SafeTarget.addresses].
 */
class SsrfGuard(
    private val allowedPorts: Set<Int> = setOf(443),
    private val allowHttp: Boolean = false,
    private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    /** Prüfung einer aufgelösten Adresse; nur Tests ersetzen sie (lokaler Empfänger auf Loopback), Produktivcode nutzt [isPublic]. */
    private val addressCheck: (InetAddress) -> Boolean = { isPublic(it) },
) {
    private val internalSuffixes = listOf(".localhost", ".local", ".internal", ".localdomain", ".lan", ".home.arpa", ".intranet", ".corp", ".private")
    private val hostLabel = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    /** Prüfung ohne DNS: Form der URL, Schema, Port, Zugangsdaten, Hostname bzw. IP-Literal. */
    fun checkLiteral(url: String, pointer: String): List<Violation> {
        val bad = listOf(Violation(pointer, ViolationCode.TARGET_NOT_ALLOWED))
        if (url.length > 2000 || url.any { it.code < 0x21 || it.code == 0x7f }) return bad
        val uri = try { URI(url) } catch (_: URISyntaxException) { return bad }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && !(allowHttp && scheme == "http")) return bad
        if (uri.userInfo != null || uri.rawAuthority == null) return bad
        val port = if (uri.port == -1) (if (scheme == "https") 443 else 80) else uri.port
        if (port !in allowedPorts) return bad
        val host = uri.host?.lowercase()?.removeSuffix(".") ?: return bad
        return if (hostAllowed(host)) emptyList() else bad
    }

    /** Auflösen und prüfen: jede Adresse muss öffentlich sein; sonst wird das Ziel verworfen. */
    fun resolve(url: String): Result<SafeTarget> = runCatching {
        val v = checkLiteral(url, "/")
        require(v.isEmpty()) { "Ziel nicht zulässig" }
        val uri = URI(url)
        val host = uri.host.lowercase().removeSuffix(".")
        val port = if (uri.port == -1) (if (uri.scheme.equals("https", true)) 443 else 80) else uri.port
        // IP-Literale brauchen kein DNS; Namen werden aufgelöst und jede Adresse einzeln geprüft.
        val addrs = if (host.startsWith("[") || host.matches(Regex("^[0-9.]+$"))) listOf(InetAddress.getByName(host)) else resolver(host)
        require(addrs.isNotEmpty()) { "Ziel nicht auflösbar" }
        require(addrs.all { addressCheck(it) }) { "Ziel verweist auf eine nicht öffentliche Adresse" }
        SafeTarget(uri, port, addrs)
    }

    /** Weiterleitungen werden wie ein neues Ziel geprüft (relative Ziele gegen die ursprüngliche URL aufgelöst). */
    fun resolveRedirect(from: URI, location: String): Result<SafeTarget> = resolve(from.resolve(location).toString())

    private fun hostAllowed(host: String): Boolean {
        if (host.startsWith("[")) return runCatching { isPublic(InetAddress.getByName(host)) }.getOrDefault(false)
        if (host.isEmpty() || host.any { it.code > 0x7e }) return false              // IDN nur als Punycode
        if (host == "localhost" || internalSuffixes.any { host.endsWith(it) }) return false
        val labels = host.split('.')
        // IP-Literal in beliebiger Schreibweise (127.1, 0x7f.0.0.1, 2130706433, 0177.0.0.1): nur reine Dezimal-Vierer-Punkt-Form prüfen
        if (labels.all { it.matches(Regex("^(0x[0-9a-f]+|[0-9]+)$")) }) {
            val strict = labels.size == 4 && labels.all { it.matches(Regex("^(0|[1-9][0-9]{0,2})$")) && it.toInt() <= 255 }
            return strict && isPublic(InetAddress.getByName(host))
        }
        if (labels.size < 2 || labels.any { !hostLabel.matches(it) }) return false  // nur voll qualifizierte Namen
        return !labels.last().all { it.isDigit() }
    }

    companion object {
        private fun v4(s: String) = BigInteger(1, InetAddress.getByName(s).address)
        private fun v4Net(cidr: String): Pair<BigInteger, BigInteger> {
            val (a, p) = cidr.split('/'); val bits = p.toInt()
            return v4(a).shiftRight(32 - bits) to BigInteger.valueOf(bits.toLong())
        }
        private val V4_BLOCKED = listOf("0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24",
            "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/3").map(::v4Net)

        /** Öffentlich = nicht lokal, privat, reserviert oder multicast (IPv4 und IPv6). */
        fun isPublic(a: InetAddress): Boolean {
            if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
            return when (a) {
                is Inet4Address -> { val n = BigInteger(1, a.address); V4_BLOCKED.none { (net, bits) -> n.shiftRight(32 - bits.toInt()) == net } }
                is Inet6Address -> {
                    val b = a.address
                    val first = b[0].toInt() and 0xff; val second = b[1].toInt() and 0xff
                    val ula = (first and 0xfe) == 0xfc                                                 // fc00::/7
                    val doc = first == 0x20 && second == 0x01 && (b[2].toInt() == 0x0d) && (b[3].toInt() and 0xff) == 0xb8 // 2001:db8::/32
                    val teredo = first == 0x20 && second == 0x01 && b[2].toInt() == 0 && b[3].toInt() == 0                 // 2001::/32
                    val sixToFour = first == 0x20 && second == 0x02                                    // 2002::/16
                    val nat64 = first == 0x00 && second == 0x64 && (b[2].toInt() and 0xff) == 0xff && (b[3].toInt() and 0xff) == 0x9b // 64:ff9b::/32
                    !(ula || doc || teredo || sixToFour || nat64 || b.take(12).all { it.toInt() == 0 } /* ::/96 kompatible Adressen */)
                }
                else -> false
            }
        }
    }
}
