package de.taktovia.documentsfinance.internal

import org.springframework.jdbc.core.JdbcOperations

/**
 * Kostenartenkatalog (`cost_category`, WP-M4-39/-40): Kostenart mit Standardverhalten und Fahrzeugkostengruppe je Mandant. Der Katalog eines Mandanten entsteht beim ersten Zugriff aus dem Seed
 * (`taktovia.seed_cost_categories()`, wiederholbar). Die Standardverhalten sind Testannahmen bis zum Beschluss D-07.
 */
internal object CostCatalog {
    class Entry(val category: String, val label: String, val costGroup: String?, val behavior: String?, val attribution: String?)

    /** Stellt den Katalog des Mandanten der laufenden Transaktion sicher (Einfügen ohne Wirkung, wenn vorhanden). */
    fun ensureSeeded(j: JdbcOperations) { j.queryForObject("SELECT taktovia.seed_cost_categories()", Int::class.java) }

    fun find(j: JdbcOperations, category: String): Entry? {
        ensureSeeded(j)
        return j.query("SELECT category, label, cost_group, default_behavior, default_attribution FROM taktovia.cost_category WHERE category = ?",
            { rs, _ -> Entry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) }, category).firstOrNull()
    }

    /**
     * Klassifikation beim Posting: Standardverhalten der Kostenart (Verhalten, Zurechnung); `split` = der Beleg ist auf mehrere Kostenobjekte aufgeteilt, die Zurechnung ist dann `ALLOCATED`.
     * Ohne Katalogeintrag oder ohne Standardverhalten: `null` = nicht klassifiziert (wird getrennt ausgewiesen, nie verworfen).
     */
    fun classify(j: JdbcOperations, category: String, split: Boolean): Pair<String, String>? {
        val e = find(j, category) ?: return null
        val behavior = e.behavior ?: return null
        return behavior to (if (split) "ALLOCATED" else e.attribution ?: return null)
    }
}
