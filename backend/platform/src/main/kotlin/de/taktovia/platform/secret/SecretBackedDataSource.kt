package de.taktovia.platform.secret

import org.springframework.jdbc.datasource.AbstractDataSource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * DataSource, die Benutzer und Passwort bei jedem neuen Verbindungsaufbau aus der Secret-Verwaltung liest
 * (Inhalt der Referenz: erste Zeile Benutzer, zweite Zeile Passwort). Damit wirkt eine Rotation ohne Neustart und ohne neues Image.
 * Schlägt die Anmeldung fehl (Secret wurde zwischen Lesen und Verbinden rotiert), wird einmal mit dem frisch gelesenen Wert wiederholt;
 * ein dauerhaft abgewiesenes (altes) Secret führt zum Fehler. Zur Nutzung hinter einem Pool: `Connection`-Lebensdauer des Pools kürzer
 * als das Rotationsfenster (Wechselnutzer-Verfahren, Detailvertrag LH-6.18-SEC-003).
 */
class SecretBackedDataSource(private val url: String, private val credential: SecretRef, private val resolver: SecretResolver) : AbstractDataSource() {
    override fun getConnection(): Connection {
        var last: SQLException? = null
        repeat(2) {
            val (user, password) = resolver.resolve(credential).reveal().lines().let { it[0] to it.getOrElse(1) { "" } }
            try { return DriverManager.getConnection(url, user, password) } catch (e: SQLException) {
                if (e.sqlState != "28P01" && e.sqlState != "28000") throw e   // nur Anmeldefehler wiederholen
                last = e
            }
        }
        throw last!!
    }
    override fun getConnection(username: String?, password: String?): Connection = throw UnsupportedOperationException("Zugang kommt aus der Secret-Referenz")
}
