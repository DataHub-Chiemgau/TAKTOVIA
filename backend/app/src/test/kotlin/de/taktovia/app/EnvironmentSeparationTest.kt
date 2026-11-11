package de.taktovia.app

import de.taktovia.TaktoviaTestSupport
import de.taktovia.app.probe.ProbeServer
import de.taktovia.app.security.SecurityEnv
import de.taktovia.app.security.TestIdp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/**
 * LH-6.18-SEC-012 / WP-M1-40: Umgebungskennung der Anwendung = Umgebungskennung der Datenbank (Start scheitert sonst), und Token einer fremden
 * Umgebung (anderer Issuer) werden abgelehnt. Datenbankteil gegen echte PostgreSQL-Datenbanken (eine Hilfsdatenbank je Kennung).
 */
class EnvironmentSeparationTest {
    private fun env(vararg kv: Pair<String, String>) = StandardEnvironment().also { e ->
        e.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        e.propertySources.addFirst(MapPropertySource("test", kv.toMap()))
    }

    /** Legt eine Hilfsdatenbank mit nur der Kennungstabelle an (Schema wie V1400) und gibt den Namen zurück. Nur Datenbanken mit `_envk_` im Namen. */
    private fun markedDatabase(environment: String?): String {
        val admin = TaktoviaTestSupport.adminDataSourceOrNull() ?: run { TaktoviaTestSupport.skipOrFail("Administratorzugang nicht konfiguriert"); error("unreachable") }
        val base = System.getenv("TAKTOVIA_DB_NAME") ?: "taktovia"
        val name = "${base}_envk_${environment ?: "leer"}_${System.nanoTime() % 100000}"
        admin.connection.use { c ->
            c.autoCommit = true
            c.createStatement().use { st ->
                st.execute("DROP DATABASE IF EXISTS \"$name\" WITH (FORCE)")
                st.execute("CREATE DATABASE \"$name\" OWNER taktovia_migrator")
                st.execute("GRANT CONNECT ON DATABASE \"$name\" TO taktovia_runtime")
            }
        }
        val adminOnDb = org.postgresql.ds.PGSimpleDataSource().apply {
            setURL("jdbc:postgresql://${System.getenv("TAKTOVIA_DB_HOST") ?: "127.0.0.1"}:${System.getenv("TAKTOVIA_DB_PORT") ?: "5432"}/$name")
            user = System.getenv("TAKTOVIA_DB_ADMIN_USER") ?: "dev_admin"; password = System.getenv("TAKTOVIA_DB_ADMIN_PASSWORD")
        }
        adminOnDb.connection.use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE SCHEMA taktovia_meta AUTHORIZATION taktovia_migrator")
                st.execute("CREATE SCHEMA taktovia AUTHORIZATION taktovia_migrator")
                st.execute("CREATE TABLE taktovia_meta.environment_marker (singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton), environment text NOT NULL, marked_at timestamptz NOT NULL DEFAULT now())")
                if (environment != null) st.execute("INSERT INTO taktovia_meta.environment_marker(environment) VALUES ('$environment')")
                st.execute("ALTER TABLE taktovia_meta.environment_marker OWNER TO taktovia_migrator")
                st.execute("GRANT USAGE ON SCHEMA taktovia_meta TO taktovia_runtime")
                st.execute("GRANT SELECT ON taktovia_meta.environment_marker TO taktovia_runtime")
                // Startschritt der Anwendung, der in die Datenbank schreibt (WP-M1-07: Protokoll des Ausrollens der Rechtematrix); die Hilfsdatenbank braucht die Tabelle,
                // damit der Start gegen eine passende Umgebungskennung gelingt (Gegenprobe) und nur die Umgebungsprüfung entscheidet.
                st.execute("CREATE TABLE taktovia.rights_matrix_rollout (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, matrix_version integer NOT NULL, matrix_sha256 char(64) NOT NULL, previous_sha256 char(64), " +
                    "role_count integer NOT NULL, operation_count integer NOT NULL, rolled_out_at timestamptz NOT NULL DEFAULT now(), rolled_out_by text NOT NULL)")
                st.execute("ALTER TABLE taktovia.rights_matrix_rollout OWNER TO taktovia_migrator")
                st.execute("GRANT USAGE ON SCHEMA taktovia TO taktovia_runtime")
                st.execute("GRANT SELECT, INSERT ON taktovia.rights_matrix_rollout TO taktovia_runtime")
            }
        }
        return name
    }

    private fun drop(name: String) {
        require("_envk_" in name)
        TaktoviaTestSupport.adminDataSourceOrNull()!!.connection.use { c -> c.createStatement().use { it.execute("DROP DATABASE IF EXISTS \"$name\" WITH (FORCE)") } }
    }

    private fun start(db: String, environment: String?): Result<*> {
        val props = mutableListOf("server.port=0", "taktovia.idp.enabled=false", "taktovia.api.cursor-secret=test-cursor-secret-test-cursor-secret-1234",
            "spring.datasource.url=jdbc:postgresql://${System.getenv("TAKTOVIA_DB_HOST") ?: "127.0.0.1"}:${System.getenv("TAKTOVIA_DB_PORT") ?: "5432"}/$db?sslmode=disable")
        if (environment != null) props += "taktovia.environment=$environment"
        return runCatching { SpringApplicationBuilder(TaktoviaApplication::class.java).run(*props.map { "--$it" }.toTypedArray()).close() }
    }

    private fun rootMessages(e: Throwable): String = generateSequence(e) { it.cause }.joinToString(" | ") { it.message ?: "" }

    @Test
    fun start_mit_abweichender_umgebungskennung_scheitert() {
        TaktoviaTestSupport.requireDatabase()
        // Die Anwendung läuft als Entwicklung (`local`); die Datenbank trägt eine andere Kennung: kein Start, besonders nicht gegen Produktion.
        for (dbEnv in listOf("staging", "performance", "production")) {
            val db = markedDatabase(dbEnv)
            try {
                val r = start(db, "local")
                assertTrue(r.isFailure, "Entwicklungsdienst mit $dbEnv-Datenbank darf nicht starten")
                val msg = rootMessages(r.exceptionOrNull()!!)
                assertTrue(msg.contains("Umgebungstrennung verletzt") && msg.contains("(development)") && msg.contains("($dbEnv)"), msg)
            } finally { drop(db) }
        }
                val dev = markedDatabase("development")
        try {
            // Mit `local` startet dieselbe Datenbank (Gegenprobe: die Prüfung blockiert nicht pauschal)
            start(dev, "local").let { assertTrue(it.isSuccess, it.exceptionOrNull()?.let(::rootMessages)) }
            // Die umgekehrte Richtung (production-Anwendung gegen Entwicklungsdatenbank) ist nur auf Ebene der Prüfung testbar (nächster Test):
            // ein Start mit production verlangt TLS zu PostgreSQL, das die lokale Datenbank nicht bietet.
        } finally { drop(dev) }
        // Datenbank ohne Kennung: kein Start
        val unmarked = markedDatabase(null)
        try {
            val r = start(unmarked, "local")
            assertTrue(r.isFailure && rootMessages(r.exceptionOrNull()!!).contains("keine lesbare Umgebungskennung"), rootMessages(r.exceptionOrNull() ?: Exception()))
        } finally { drop(unmarked) }
    }

    @Test
    fun pruefung_bewertet_kennungen_und_die_echte_datenbank() {
        TaktoviaTestSupport.requireDatabase()
        val ds = SecurityEnv.shared.server.ctx.getBean(javax.sql.DataSource::class.java)
        // echte Datenbank der Tests (Kennung durch `db.py migrate`): development
        val g = EnvironmentGuard(env("taktovia.environment" to "local"), ds)
        assertEquals("development", g.databaseEnvironment())
        assertEquals(emptyList<String>(), g.violations())
        listOf("staging", "performance", "production").forEach {
            val v = EnvironmentGuard(env("taktovia.environment" to it), ds).violations()
            assertEquals(1, v.size, v.toString()); assertTrue(v[0].contains("($it)") && v[0].contains("(development)"), v[0])
        }
        assertEquals("development", EnvironmentGuard(env("taktovia.environment" to "development"), ds).applicationEnvironment())
        assertNull(EnvironmentGuard(env("taktovia.environment" to "prod"), ds).applicationEnvironment())
        assertTrue(EnvironmentGuard(env("taktovia.environment" to "prod"), ds).violations()[0].contains("keine bekannte Umgebung"))
        assertEquals("production", EnvironmentGuard(env(), ds).applicationEnvironment())
        assertNotNull(EnvironmentGuard(env(), ds).violations().firstOrNull())
    }

    @Test
    fun token_fremder_umgebung_wird_abgelehnt() {
        // Zwei Umgebungen = zwei Identitätsdomänen (eigener Issuer, eigene Schlüssel). Je ein Dienst vertraut nur dem Issuer seiner Umgebung.
        val prodIdp = TestIdp("taktovia-production")
        val devIdp = TestIdp("taktovia-dev")
        try {
            val dev = ProbeServer.get(*SecurityEnv.props(devIdp))
            val prod = ProbeServer.get(*SecurityEnv.props(prodIdp))
            fun call(s: ProbeServer, idp: TestIdp, token: String) = s.get("/v1/probe/leaky", idp.bearer(token)).status
            val devToken = devIdp.sign(devIdp.humanClaims())
            val prodToken = prodIdp.sign(prodIdp.humanClaims())
            assertEquals(200, call(dev, devIdp, devToken))
            assertEquals(200, call(prod, prodIdp, prodToken))
            assertEquals(401, call(prod, prodIdp, devToken), "Entwicklungstoken gegen die Produktions-API")
            assertEquals(401, call(dev, devIdp, prodToken), "Produktionstoken gegen die Entwicklungs-API")
            // gefälschter Issuer-Claim mit Schlüsseln der eigenen Domäne hilft nicht
            assertEquals(401, call(prod, prodIdp, devIdp.sign(devIdp.humanClaims().also { it["iss"] = prodIdp.issuer })))
            assertEquals(401, call(dev, devIdp, prodIdp.sign(prodIdp.humanClaims().also { it["iss"] = devIdp.issuer })))
            // gleicher Realmname und gleiche kid in einer anderen Domäne: fremder Schlüssel, kein Zugriff
            val twin = TestIdp("taktovia-production")
            try { assertEquals(401, call(prod, prodIdp, twin.sign(twin.humanClaims().also { it["iss"] = prodIdp.issuer }))) } finally { twin.close() }
        } finally { prodIdp.close(); devIdp.close() }
    }
}
