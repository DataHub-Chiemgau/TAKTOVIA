package de.taktovia.app

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * Startprüfung „Umgebungskennung der Anwendung = Umgebungskennung der Datenbank“ (WP-M1-40, LH-6.18-SEC-012). Eine Anwendung mit
 * Nicht-Produktionskonfiguration startet nicht gegen die Produktionsdatenbank (und umgekehrt); ohne lesbare Kennung startet sie nicht.
 *
 * Anwendung: `taktovia.environment` (`local` = Entwicklung auf dem Arbeitsplatz, entspricht `development`; sonst `development`, `staging`,
 * `performance`, `production`; ohne Angabe gilt `production`, damit eine vergessene Konfiguration nie zu einer lockeren Prüfung führt).
 * Datenbank: `taktovia_meta.environment_marker` (V1400), einmalig von `db.py migrate` gesetzt und unveränderlich; gelesen mit der Runtime-Rolle.
 */
@Component
class EnvironmentGuard(private val env: Environment, private val dataSource: DataSource) : SmartInitializingSingleton {
    /** Läuft, nachdem alle Singletons angelegt sind (Konfigurationsfehler anderer Prüfungen kommen zuerst) und bevor der Webserver Anfragen annimmt. */
    override fun afterSingletonsInstantiated() {
        val problems = violations()
        check(problems.isEmpty()) { "Umgebungstrennung verletzt: ${problems.joinToString("; ")}" }
    }

    /** Kennung der Anwendung, normalisiert (`local` → `development`); `null`, wenn der Wert unbekannt ist. */
    fun applicationEnvironment(): String? = when (val v = env.getProperty("taktovia.environment")?.trim()?.ifEmpty { null } ?: "production") {
        "local" -> "development"
        in KNOWN -> v
        else -> null
    }

    fun databaseEnvironment(): String? = runCatching {
        dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT environment FROM taktovia_meta.environment_marker").use { rs ->
                    val values = buildList { while (rs.next()) add(rs.getString(1)) }
                    values.singleOrNull()
                }
            }
        }
    }.getOrNull()

    fun violations(): List<String> {
        val app = applicationEnvironment() ?: return listOf("taktovia.environment='${env.getProperty("taktovia.environment")}' ist keine bekannte Umgebung (${KNOWN.joinToString("|")}|local)")
        val db = databaseEnvironment() ?: return listOf("die Datenbank hat keine lesbare Umgebungskennung (taktovia_meta.environment_marker)")
        return if (app == db) emptyList()
        else listOf("Umgebungskennung der Anwendung ($app) ist nicht die der Datenbank ($db)")
    }

    companion object { val KNOWN = setOf("development", "staging", "performance", "production") }
}
