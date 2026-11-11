package de.taktovia.app

import de.taktovia.TaktoviaTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.sql.DataSource

/** WP-M0-02 AK 2: Anwendung startet gegen die lokale Datenbank, Health „UP“, Verbindung unter der Runtime-Rolle. */
class BackendStartTest {
    companion object {
        private var ctx: ConfigurableApplicationContext? = null

        @BeforeAll @JvmStatic
        fun start() {
            TaktoviaTestSupport.requireDatabase()
            ctx = SpringApplicationBuilder(TaktoviaApplication::class.java).properties("server.port=0").run()
        }

        @AfterAll @JvmStatic
        fun stop() { ctx?.close() }
    }

    private fun get(path: String): HttpResponse<String> {
        val port = ctx!!.environment.getProperty("local.server.port")
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `health meldet UP`() {
        val r = get("/actuator/health")
        assertEquals(200, r.statusCode())
        assertTrue(r.body().contains("\"UP\""), r.body())
    }

    @Test
    fun `nur health ist exponiert und es gibt keine Fachendpunkte`() {
        assertEquals(404, get("/actuator/env").statusCode())
        assertEquals(404, get("/v1/me").statusCode())
    }

    @Test
    fun `Datenbankverbindung nutzt die Runtime-Rolle`() {
        val jdbc = JdbcTemplate(ctx!!.getBean(DataSource::class.java))
        assertEquals("taktovia_runtime", jdbc.queryForObject("SELECT current_user", String::class.java))
        assertEquals(emptyList<String>(), ctx!!.getBean(RuntimeRoleGuard::class.java).check())
    }

    @Test
    fun `Migrator oder Administrator werden vom Guard abgewiesen`() {
        val admin = TaktoviaTestSupport.adminDataSourceOrNull() ?: return TaktoviaTestSupport.skipOrFail("Administratorzugang nicht konfiguriert")
        val problems = RuntimeRoleGuard(admin).check()
        assertFalse(problems.isEmpty(), "Superuser muss abgewiesen werden")
        val migrator = TaktoviaTestSupport.migratorDataSourceOrNull() ?: return TaktoviaTestSupport.skipOrFail("Migratorzugang nicht konfiguriert")
        assertTrue(RuntimeRoleGuard(migrator).check().any { it.contains("besitzt Objekte") })
    }
}
