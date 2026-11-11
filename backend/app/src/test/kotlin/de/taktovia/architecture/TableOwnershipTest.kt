package de.taktovia.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class TableOwnershipTest {
    private val repo: Path = Path.of(System.getProperty("taktovia.repoRoot"))
    private val owners = TableOwnership.loadOwners(repo.resolve("db/table_owners.d"))

    @Test
    fun `jede Tabelle der Migrationen hat genau einen gueltigen Besitzer`() {
        val tables = TableOwnership.migrationTables(repo.resolve("db/migrations"))
        assertTrue(tables.size >= 38)
        assertEquals(tables, owners.keys, "Tabelle ohne Eintrag in db/table_owners.d (oder Eintrag ohne Tabelle)")
        assertTrue(owners.values.all { it in TableOwnership.OWNERS }, "unbekannter Besitzer")
    }

    @Test
    fun `kein Projekt schreibt fremde Tabellen`() {
        val backend = repo.resolve("backend")
        val projects = listOf("platform", "app") +
            Files.list(backend.resolve("modules")).use { s -> s.filter { Files.isDirectory(it) }.map { "modules/${it.fileName}" }.toList() } +
            Files.list(backend.resolve("adapters")).use { s -> s.filter { Files.isDirectory(it) }.map { "adapters/${it.fileName}" }.toList() }
        assertEquals(20, projects.size)
        val violations = projects.flatMap { TableOwnership.foreignWrites(backend.resolve(it), TableOwnership.projectOwner(it), owners) }
        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `roter Selbsttest - Schreibzugriff auf fremde Tabelle wird gefunden`(@TempDir tmp: Path) {
        val dir = tmp.resolve("modules/identity/src/main/kotlin").createDirectories()
        dir.resolve("Bad.kt").writeText("""val sql = "INSERT INTO taktovia.journal (id) VALUES (?)"; val ok = "UPDATE membership SET x = 1"""")
        val found = TableOwnership.foreignWrites(tmp.resolve("modules/identity"), "identity", owners)
        assertEquals(1, found.size)
        assertTrue(found.single().contains("journal"))
    }

    @Test
    fun `roter Selbsttest - Adapter darf keine Fachtabelle schreiben`(@TempDir tmp: Path) {
        val dir = tmp.resolve("adapters/psp/src/main/kotlin").createDirectories()
        dir.resolve("Bad.kt").writeText("""val sql = "delete from payment_intent"""")
        assertEquals(1, TableOwnership.foreignWrites(tmp.resolve("adapters/psp"), TableOwnership.projectOwner("adapters/psp"), owners).size)
    }

    @Test
    fun `roter Selbsttest - neue Tabelle ohne Besitzer faellt auf`(@TempDir tmp: Path) {
        tmp.resolve("V9__neu.sql").writeText("CREATE TABLE taktovia.neue_tabelle (id uuid);")
        val tables = TableOwnership.migrationTables(tmp)
        assertTrue((tables - owners.keys).isNotEmpty())
    }
}
