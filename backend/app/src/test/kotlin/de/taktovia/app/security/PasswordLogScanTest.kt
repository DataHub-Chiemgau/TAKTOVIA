package de.taktovia.app.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * LH-6.2-IAM-002 / WP-M1-04 AK 1 (Log-Stichprobe): Das System sieht Passwörter nie und schreibt sie nie in Logs. Das Testpasswort wird in alle
 * Eingabewege gelegt (Header, Query, Body, Token-Claim, Basic-Auth, ungültiges Token); danach darf es in keiner Logzeile der Anwendung stehen.
 */
class PasswordLogScanTest {
    private val env get() = SecurityEnv.shared
    private val secret = "Tp-Klartext-Geheim-7731!"

    /** Findet ein Geheimnis im Klartext und in gängigen Kodierungen (Base64, URL-Kodierung). */
    private fun leaks(logs: List<String>, value: String): List<String> {
        val variants = listOf(value, java.net.URLEncoder.encode(value, Charsets.UTF_8),
            java.util.Base64.getEncoder().encodeToString(value.toByteArray()).trimEnd('='), java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray()))
        return logs.filter { line -> variants.any { line.contains(it) } }
    }

    @Test
    fun testpasswort_nicht_in_logs() {
        // Der Scanner selbst erkennt ein Passwort (sonst wäre die Stichprobe wertlos)
        assertEquals(1, leaks(listOf("login ok", "user=a password=$secret"), secret).size)
        assertEquals(1, leaks(listOf("x " + java.util.Base64.getEncoder().encodeToString("u:$secret".toByteArray())), "u:$secret").size)

        env.server  // Anwendung zuerst starten: der Start initialisiert das Logging neu und verwirft fremde Appender
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val oldLevel = root.level
        root.level = Level.DEBUG
        root.addAppender(appender)
        try {
            val idp = env.idp
            val basic = java.util.Base64.getEncoder().encodeToString("a.dispatcher:$secret".toByteArray())
            env.server.get("/v1/probe/leaky", mapOf("Authorization" to "Basic $basic"))
            env.server.get("/v1/probe/leaky?password=$secret&access_token=$secret")
            env.server.get("/v1/probe/leaky", mapOf("Authorization" to "Bearer $secret", "X-Password" to secret))
            env.server.post("/v1/probe/free", """{"password":"$secret","username":"x"}""", idp.bearer(idp.sign(idp.humanClaims())))
            env.call(idp.sign(idp.humanClaims().also { it["password"] = secret; it["exp"] = 1 }))                              // abgelaufen, trägt das Passwort
            env.call(idp.sign(idp.humanClaims(roles = listOf("tenant_admin")).also { it["password"] = secret }))             // MFA-Abweisung
            env.call(idp.tamper(idp.sign(idp.humanClaims())) { it["password"] = secret })                                       // manipuliert
            env.call(idp.sign(idp.humanClaims().also { it["password"] = secret }))                                              // gültig
        } finally { root.detachAppender(appender); root.level = oldLevel }

        val lines = appender.list.map { it.formattedMessage + (it.throwableProxy?.let { t -> " " + t.message } ?: "") + " " + it.mdcPropertyMap }
        assertTrue(lines.isNotEmpty(), "es muss überhaupt geloggt worden sein, sonst prüft die Stichprobe nichts")
        assertEquals(emptyList<String>(), leaks(lines, secret))
        assertFalse(lines.any { it.contains("Bearer ") && it.contains(secret) })
    }
}
