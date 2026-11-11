package de.taktovia.platform

import de.taktovia.platform.retention.RetentionDataClasses
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/** WP-M1-33: Wächter ohne Datenbank. Der Archivzugang ist eine Sitzungseinstellung und darf nur von platform.retention gesetzt werden; die Datenklassen der Plattform entsprechen dem Dateninventar. */
class RetentionGuardsTest {
    private val repoRoot: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("db/dateninventar_katalog.json")) }

    @Test fun archivzugang_wird_nur_im_retention_baustein_gesetzt() {
        val offenders = Files.walk(repoRoot.resolve("backend")).use { s ->
            s.filter { it.extension == "kt" && it.toString().contains("/src/main/") && !it.toString().contains("/build/") }
                .filter { Files.readString(it).contains("app.archive_access") }
                .map { repoRoot.relativize(it).toString() }.toList()
        }
        assertEquals(listOf("backend/platform/src/main/kotlin/de/taktovia/platform/retention/RetentionModel.kt"), offenders, "app.archive_access darf nur in ArchiveAccess gesetzt werden")
    }

    @Test fun datenklassen_entsprechen_den_loeschklassen_des_dateninventars() {
        val json = Files.readString(repoRoot.resolve("db/dateninventar_katalog.json"))
        val classes = Regex("\"(LK_[A-Z_]+)\"\\s*:").findAll(json).map { it.groupValues[1] }.toSet()
        assertTrue(classes.isNotEmpty())
        assertEquals(classes, RetentionDataClasses.ALL)
    }
}
