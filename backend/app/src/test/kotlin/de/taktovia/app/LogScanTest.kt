package de.taktovia.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * LH-I.4-MUL-006 / WP-M1-06 AK 6: Anwendungslogs enthalten weder Token noch personenbezogene Kontextdaten. Der Test belastet die
 * Anwendung mit gültigen und ungültigen Tokens, Tenantwechsel, Manipulationsversuchen und Verweigerungen und durchsucht alle Logzeilen
 * (Nachricht, Argumente, Ausnahmetext) nach Tokens, Kontextbindungen, Subjects, Sitzungs-IDs und Mailadressen.
 */
class LogScanTest {
    @Test
    fun keine_token_oder_pii_in_logs() {
        val env = IdentityEnv
        env.server  // Anwendung (und Test-Issuer) starten, bevor mitgeschnitten wird
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val app = LoggerFactory.getLogger("de.taktovia") as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val oldLevel = app.level
        root.addAppender(appender); app.level = Level.DEBUG
        val secrets = mutableListOf<String>()
        try {
            val t = env.tenant("Klarname Mandant"); val other = env.tenant()
            val admin = UUID.randomUUID(); val member = UUID.randomUUID(); val stranger = UUID.randomUUID()
            env.grant(t, admin, "tenant_admin"); env.grant(t, member, "support"); env.grant(other, member, "support")
            val sid = "sid-scan-" + UUID.randomUUID()
            val adminToken = env.token(admin, sid = sid); val memberToken = env.token(member); val strangerToken = env.token(stranger)
            val ctx = env.switchTo(memberToken, other).json["context_token"].asString()
            secrets += listOf(adminToken, memberToken, strangerToken, ctx, admin.toString(), member.toString(), stranger.toString(), sid, "Klarname Mandant")

            // Normalfall, Verweigerungen, Manipulation, ungültige Tokens
            env.get("/v1/me", adminToken); env.get("/v1/memberships", adminToken); env.get("/v1/memberships", memberToken); env.get("/v1/me", strangerToken)
            env.get("/v1/me", memberToken, ctx); env.get("/v1/memberships", adminToken, extra = mapOf("X-Tenant-Id" to other.toString()))
            env.get("/v1/me", memberToken, ctx.dropLast(3) + "xyz"); env.switchTo(memberToken, env.tenant())
            env.get("/v1/me", env.token(admin, acr = "loa1"))
            env.get("/v1/me", env.idp.sign(env.idp.humanClaims(sub = admin).also { it["aud"] = "falsch" }))
            env.get("/v1/me", adminToken.dropLast(5) + "AAAAA")
            env.server.get("/v1/me", mapOf("Authorization" to "Bearer $adminToken"))
            val bodyAttempt = env.post("/v1/memberships", """{"issuer":"i","subject":"s","role_code":"support","tenant_id":"${other}"}""", adminToken, extra = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID()))
            assertEquals(404, bodyAttempt.status)
        } finally {
            root.detachAppender(appender); app.level = oldLevel
        }
        val lines = appender.list.map { e ->
            e.formattedMessage + " " + (e.argumentArray?.joinToString(" ") ?: "") + " " + (e.throwableProxy?.let { ThrowableProxyUtil.asString(it) } ?: "") + " " + e.mdcPropertyMap.values.joinToString(" ")
        }
        assertTrue(lines.isNotEmpty(), "der Test belegt nichts, wenn nichts mitgeschnitten wurde")
        val jwt = Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}")
        val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[a-z]{2,}")
        val findings = lines.flatMap { l ->
            buildList {
                if (jwt.containsMatchIn(l)) add("JWT in: $l")
                if (Regex("(?i)bearer\\s+\\S{8,}").containsMatchIn(l)) add("Bearer in: $l")
                if (email.containsMatchIn(l)) add("Mailadresse in: $l")
                secrets.filter { it.length >= 8 && l.contains(it) }.forEach { add("'${it.take(12)}…' in: $l") }
            }
        }
        assertEquals(emptyList<String>(), findings)
    }
}
