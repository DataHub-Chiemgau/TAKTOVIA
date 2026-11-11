package de.taktovia.platform.isolation

import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper

/**
 * Listen- und Export-Baustein mit **erzwungenem Tenantfilter** (WP-M1-11, LH-6.1-SYS-001). Jede Abfrage dieses Bausteins enthält `tenant_id = taktovia.current_tenant()` als erste
 * Bedingung; der Aufrufer kann ihn weder weglassen noch ersetzen, und Tabelle und Spalten sind feste Bezeichner aus einer Positivliste ([ListSource]). Der Mandant kommt aus dem
 * Tenantkontext der Transaktion (Row-Level-Security bleibt die zweite Schicht). Der Konstruktor ist nicht öffentlich: Instanzen entstehen nur über [TenantListing.of].
 * Seitenweise Schlüsselmengen-Paginierung nach `(sortColumn, id)`; Exporte lesen mit demselben Baustein, gehen also nie am Filter vorbei.
 */
class TenantListing private constructor(private val source: ListSource) {
    /** Die erzeugte Abfrage (zur Prüfung in Tests und Architekturregeln): enthält stets den Mandantenfilter. */
    fun sql(extraWhere: String? = null): String =
        "SELECT ${source.columns.joinToString(", ")} FROM taktovia.${source.table} WHERE tenant_id = taktovia.current_tenant()" +
            (if (extraWhere != null) " AND ($extraWhere)" else "") + " ORDER BY ${source.sortColumn}, id LIMIT ?"

    /** Eine Seite; [limit] + 1 Zeilen, damit der Aufrufer die Folgeseite erkennt. [extraWhere] nur feste Texte mit `?`-Parametern aus [args]. */
    fun <T> page(j: JdbcOperations, limit: Int, mapper: RowMapper<T>, extraWhere: String? = null, vararg args: Any?): List<T> {
        require(limit in 1..MAX_LIMIT) { "limit außerhalb von 1..$MAX_LIMIT" }
        require(extraWhere == null || !FORBIDDEN.containsMatchIn(extraWhere)) { "Zusatzbedingung darf den Mandantenfilter nicht umgehen" }
        return j.query(sql(extraWhere), mapper, *args, limit + 1) // sql-lint: ok Tabelle und Spalten aus fester Positivliste, Mandantenfilter fest, Werte gebunden
    }

    companion object {
        const val MAX_LIMIT = 1000
        private val FORBIDDEN = Regex("(?i)tenant_id|current_tenant|;|--|/\\*|\\bor\\b")
        fun of(source: ListSource) = TenantListing(source)
    }
}

/** Positivliste der Listenquellen: feste Tabelle, feste Spalten, feste Sortierung (nie aus Anfragewerten). */
class ListSource(val table: String, val columns: List<String>, val sortColumn: String = "id") {
    init {
        require(IDENT.matches(table) && columns.all { IDENT.matches(it) } && IDENT.matches(sortColumn)) { "ungültiger Bezeichner" }
        require("id" in columns) { "id fehlt" }
    }
    private companion object { val IDENT = Regex("[a-z][a-z0-9_]{0,62}") }
}
