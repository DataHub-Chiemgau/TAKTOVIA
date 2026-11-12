package de.taktovia.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * WP-M4-64: „Ein Modul schreibt nur eigene Tabellen“ für die drei M4-Module (`staff-time`, `documents-finance`, `analytics`) und Abgleich des
 * M4-Tabellenkatalogs (`db/m4_tabellenkatalog.csv`) mit dem Tabellenbesitz (`db/table_owners.d`). Die Laufzeitgrenze (getrennte Datenbankzugänge je Modul)
 * prüfen `db/tests/test_m4_64_modulzugaenge.py` und `test_m4_64_katalog.py`.
 */
class M4TableOwnershipTest {
    private val repo: Path = Path.of(System.getProperty("taktovia.repoRoot"))
    private val owners = TableOwnership.loadOwners(repo.resolve("db/table_owners.d"))
    private val m4 = listOf("staff-time", "documents-finance", "analytics")

    private fun catalog(): Map<String, String> = Files.readAllLines(repo.resolve("db/m4_tabellenkatalog.csv")).drop(1).filter { it.isNotBlank() }
        .associate { l -> l.split(';').let { it[0] to it[1] } }

    @Test
    fun `die drei M4-Module schreiben nur eigene Tabellen`() {
        for (module in m4) {
            val violations = TableOwnership.foreignWrites(repo.resolve("backend/modules/$module"), TableOwnership.projectOwner("modules/$module"), owners)
            assertEquals(emptyList<String>(), violations, module)
        }
    }

    @Test
    fun `jede Tabelle der M4-Module steht im Katalog und der Katalog nennt denselben Besitzer`() {
        val cat = catalog()
        // Fahrerstamm, Fahrzeugstatus, Positionen u. a. gehören fachlich zu M1/M3 (M3-Katalog), auch wenn staff-time sie besitzt.
        val m3Owned = setOf("driver", "qualification_revision", "driver_plan_ack", "position_sample", "vehicle_status_history", "vehicle_service_status", "boarding_code")
        val m4Owned = owners.filterValues { it in m4 }.filterKeys { it !in m3Owned }
        assertEquals(emptyList<String>(), m4Owned.keys.filter { it !in cat }, "M4-Tabelle ohne Eintrag in db/m4_tabellenkatalog.csv")
        for ((table, module) in cat) owners[table]?.let { assertEquals(module, it, "Besitzer von $table") }
        assertTrue(cat.size >= 20)
    }

    @Test
    fun `roter Selbsttest - Staff-Time schreibt Beleg und Analytics schreibt Schicht wird gefunden`(@TempDir tmp: Path) {
        val dir = tmp.resolve("modules/staff-time/src/main/kotlin").createDirectories()
        dir.resolve("Bad.kt").writeText("""val a = "INSERT INTO taktovia.expense_document (id) VALUES (?)"; val b = "UPDATE taktovia.staff_shift SET state = 'X'"""")
        val found = TableOwnership.foreignWrites(tmp.resolve("modules/staff-time"), "staff-time", owners)
        assertEquals(1, found.size)
        assertTrue(found.single().contains("expense_document"))
        val an = tmp.resolve("modules/analytics/src/main/kotlin").createDirectories()
        an.resolve("Bad.kt").writeText("""val b = "delete from taktovia.staff_shift"""")
        assertEquals(1, TableOwnership.foreignWrites(tmp.resolve("modules/analytics"), "analytics", owners).size)
    }
}
