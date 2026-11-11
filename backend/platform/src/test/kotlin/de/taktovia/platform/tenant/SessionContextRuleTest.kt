package de.taktovia.platform.tenant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.writeText

/**
 * Regel (Kap. 04 §4.6): sessionweite Tenantkontexte sind verboten. Quelltextsuche über alle Backend-Quellen nach
 * `set_config(..., false)`, `SET app.tenant_id`, `SET SESSION`, `ALTER ROLE/DATABASE ... SET` und `RESET`. Ausnahmen gibt es nicht.
 */
class SessionContextRuleTest {
    companion object {
        private val FORBIDDEN = listOf(
            Regex("""(?i)set_config\s*\(\s*'app\.[a-z_]+'\s*,[^)]*,\s*(false|0)\s*\)"""),
            Regex("""(?i)\bSET\s+(SESSION\s+)?app\.tenant_id\b"""),
            Regex("""(?i)\bALTER\s+(ROLE|DATABASE)\b[^;]*\bSET\s+app\."""),
        )
        fun violations(dir: Path): List<String> = Files.walk(dir).use { s ->
            s.filter { Files.isRegularFile(it) && it.extension in setOf("kt", "java", "sql") && !it.toString().contains("/build/") && !it.toString().contains("/src/test/") }.toList()
        }.filter { f -> FORBIDDEN.any { it.containsMatchIn(Files.readString(f)) } }.map { it.fileName.toString() }
    }

    @Test fun `kein Backend- oder Migrationscode setzt einen sessionweiten Tenantkontext`() {
        val repo = Path.of(System.getProperty("taktovia.repoRoot") ?: Path.of("").toAbsolutePath().let { generateSequence(it) { p -> p.parent }.first { p -> Files.exists(p.resolve("db/migrations")) }.toString() })
        assertEquals(emptyList<String>(), violations(repo.resolve("backend")) + violations(repo.resolve("db/migrations")))
    }

    @Test fun `roter Selbsttest - sessionweite Kontexte werden gefunden`(@TempDir tmp: Path) {
        val d = tmp.resolve("x/src/main").createDirectories()
        d.resolve("A.kt").writeText("""val a = "SELECT set_config('app.tenant_id', ?, false)"""")
        d.resolve("B.sql").writeText("SET SESSION app.tenant_id = 'x';")
        d.resolve("C.sql").writeText("ALTER ROLE taktovia_runtime SET app.tenant_id = 'x';")
        d.resolve("Ok.kt").writeText("""val ok = "SELECT set_config('app.tenant_id', ?, true)"""")
        val v = violations(tmp)
        assertEquals(3, v.size, v.toString())
        assertTrue(v.none { it == "Ok.kt" })
    }
}
