package de.taktovia.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * „Ein Modul schreibt ausschließlich seine eigenen Tabellen“ (Kap. 01 §1.3, WP-M0-03).
 * Quelle der Wahrheit für den Besitz ist `db/table_owners.csv`; jede Tabelle einer Migration braucht einen Eintrag.
 * Die Schreibprüfung ist eine Quelltextsuche nach `INSERT INTO`, `UPDATE` und `DELETE FROM` auf bekannte Tabellen.
 * Sie erkennt keine dynamisch zusammengesetzten Statements; die Laufzeitgrenze bleiben getrennte DB-Zugänge (M1+).
 */
object TableOwnership {
    val OWNERS = setOf(
        "platform", "identity", "config", "service-geo", "booking-dispatch", "journey-federation", "fare-payment",
        "fleet-safety", "staff-time", "documents-finance", "communication-support", "analytics",
    )
    private val WRITE = Regex("""(?i)\b(insert\s+into|update|delete\s+from)\s+(?:taktovia\.)?([a-z_]+)\b""")
    private val CREATE = Regex("""(?im)^\s*CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:taktovia\.)?([a-z_]+)""")

    /** Liest alle CSV-Dateien in `db/table_owners.d` (je Arbeitspaket eine Datei) und verlangt eindeutige Tabellennamen. */
    fun loadOwners(dir: Path): Map<String, String> {
        val out = linkedMapOf<String, String>()
        Files.list(dir).use { s -> s.filter { it.extension == "csv" }.sorted().toList() }.forEach { f ->
            Files.readAllLines(f).drop(1).filter { it.isNotBlank() }.forEach { l ->
                val c = l.split(';'); require(c[0] !in out) { "Tabelle doppelt in table_owners.d: ${c[0]}" }; out[c[0]] = c[1]
            }
        }
        return out
    }

    fun migrationTables(dir: Path): Set<String> = Files.list(dir).use { s ->
        s.filter { it.name.startsWith("V") && it.extension == "sql" }.toList()
    }.flatMap { CREATE.findAll(Files.readString(it)).map { m -> m.groupValues[1] }.toList() }.toSet()

    /** Gibt je Verstoß „datei: operation tabelle (Besitzer X)“ zurück. */
    fun foreignWrites(projectDir: Path, projectOwner: String, owners: Map<String, String>): List<String> {
        val src = projectDir.resolve("src/main")
        if (!Files.exists(src)) return emptyList()
        return Files.walk(src).use { s ->
            s.filter { Files.isRegularFile(it) && it.extension in setOf("kt", "java", "sql", "yml", "yaml", "xml") }.toList()
        }.flatMap { f ->
            WRITE.findAll(Files.readString(f)).mapNotNull { m ->
                val table = m.groupValues[2].lowercase()
                val owner = owners[table]
                if (owner != null && owner != projectOwner) "${f.fileName}: ${m.groupValues[1].lowercase()} $table (Besitzer $owner, Schreiber $projectOwner)" else null
            }.toList()
        }
    }

    /** Besitzer-Schlüssel eines Gradle-Blattprojekts: platform, Modulverzeichnis, sonst Adapter/app ohne Tabellen. */
    fun projectOwner(relative: String): String = when {
        relative == "platform" -> "platform"
        relative.startsWith("modules/") -> relative.removePrefix("modules/")
        else -> "kein-besitzer:$relative"
    }
}
