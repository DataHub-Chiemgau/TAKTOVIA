package de.taktovia.platform.secret

import de.taktovia.platform.TestDatabase
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * WP-M1-36 (SEC-002/003, konkurrierend): Rotation unter Last ohne Ausfall und ohne neues Image (Wechselnutzer-Verfahren):
 * neuer Zugang wird angelegt, Secret-Datei atomar ersetzt, alter Zugang gesperrt. Echte Datenbankrollen, echte parallele Verbindungen.
 */
class SecretRotationTest {
    companion object {
        private val sfx = UUID.randomUUID().toString().take(8)
        private val roleA = "rot_a_$sfx"; private val roleB = "rot_b_$sfx"
        private lateinit var admin: JdbcTemplate
        private lateinit var dir: Path
        private val domain = "taktovia-development-secrets"
        private val ref = SecretRef(domain, "db_runtime_credential")
        private val url get() = "jdbc:postgresql://${System.getenv("TAKTOVIA_DB_HOST") ?: "127.0.0.1"}:${System.getenv("TAKTOVIA_DB_PORT") ?: "5432"}/${System.getenv("TAKTOVIA_DB_NAME") ?: "taktovia"}"

        private fun write(user: String, pw: String) {
            Files.createDirectories(dir.resolve(domain))
            val tmp = dir.resolve(domain).resolve(".tmp-${UUID.randomUUID()}"); Files.writeString(tmp, "$user\n$pw\n")
            Files.move(tmp, dir.resolve(domain).resolve(ref.name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        private fun pw() = UUID.randomUUID().toString()
        private fun createRole(r: String, p: String) {
            admin.execute("CREATE ROLE \"$r\" LOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT PASSWORD '$p'")
            admin.execute("GRANT CONNECT ON DATABASE \"${System.getenv("TAKTOVIA_DB_NAME") ?: "taktovia"}\" TO \"$r\"")
        }

        @BeforeAll @JvmStatic fun setup() { TestDatabase.require(); admin = JdbcTemplate(TestDatabase.admin()); dir = Files.createTempDirectory("secrets") }
        @AfterAll @JvmStatic fun teardown() {
            if (::admin.isInitialized) for (r in listOf(roleA, roleB)) runCatching { admin.execute("DROP ROLE IF EXISTS \"$r\"") }
        }
    }

    @Test fun `Rotation unter Last ohne Ausfall, danach wird das alte Secret abgewiesen`() {
        val oldPw = pw(); createRole(roleA, oldPw); write(roleA, oldPw)
        val ds = SecretBackedDataSource(url, ref, SecretResolver(domain, FileSecretStore(dir)))
        val stop = AtomicBoolean(false); val ok = AtomicInteger(); val failed = AtomicInteger(); val usedNew = AtomicInteger()
        val exec = Executors.newFixedThreadPool(8)
        try {
            val workers = (1..8).map { exec.submit(Callable {
                while (!stop.get()) {
                    try { ds.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT current_user").use { rs -> rs.next(); if (rs.getString(1) == roleB) usedNew.incrementAndGet() } } }; ok.incrementAndGet() }
                    catch (e: Exception) { failed.incrementAndGet() }
                }
            }) }
            Thread.sleep(800)
            // Rotation: neuer Zugang, Secret atomar ersetzen, alten Zugang sperren
            val newPw = pw(); createRole(roleB, newPw); write(roleB, newPw)
            Thread.sleep(500)
            admin.execute("ALTER ROLE \"$roleA\" PASSWORD '${pw()}'")
            Thread.sleep(1000)
            stop.set(true); workers.forEach { it.get() }
        } finally { exec.shutdownNow() }
        assertEquals(0, failed.get(), "keine fehlgeschlagene Verbindung während der Rotation")
        assertTrue(ok.get() > 20, "Last war tatsächlich vorhanden: ${ok.get()}")
        assertTrue(usedNew.get() > 0, "neues Secret wurde ohne Neustart übernommen")
        // altes Secret wird abgewiesen
        val e = assertThrows<SQLException> { DriverManager.getConnection(url, roleA, oldPw).close() }
        assertEquals("28P01", e.sqlState)
    }
}
