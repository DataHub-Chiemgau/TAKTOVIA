package de.taktovia.app.readports

import de.taktovia.TaktoviaTestSupport
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.sql.DataSource

/**
 * Testaufbau der Leseport-Verträge (WP-M4-02): lädt den Seed der Referenzdaten M4 unter der Migrator-Rolle (idempotent, wie in `tests/acceptance`) und stellt die Verbindung der
 * Anwendung unter der Runtime-Rolle bereit. Eigene Klasse, keine Duplikate fremder Fixtures.
 */
internal class M4ReferenceFixture {
    val repo: Path = Path.of(System.getProperty("taktovia.repoRoot"))
    val dir: Path = repo.resolve("tests/acceptance/fixtures/m4_referenz")
    val tenantA = TenantId(UUID.fromString("00000000-0000-4000-8000-0000000000a4"))
    val tenantB = TenantId(UUID.fromString("00000000-0000-4000-8000-0000000000b4"))
    val catalog: String = Files.readString(dir.resolve("katalog.json"))

    private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }

    val ds: DataSource by lazy {
        TaktoviaTestSupport.requireDatabase()
        PGSimpleDataSource().apply {
            setURL("jdbc:postgresql://${env("TAKTOVIA_DB_HOST") ?: "127.0.0.1"}:${env("TAKTOVIA_DB_PORT") ?: "5432"}/${env("TAKTOVIA_DB_NAME") ?: "taktovia"}")
            user = "taktovia_runtime"; password = env("TAKTOVIA_DB_RUNTIME_PASSWORD")
        }
    }
    val tx by lazy { TenantTransactions(ds) }
    val ctxTx by lazy { ContextTransactions(tx, object : ContextRevalidator { override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, lock: Boolean) {} }) }

    fun loadSeed() {
        val migrator = TaktoviaTestSupport.migratorDataSourceOrNull() ?: run { TaktoviaTestSupport.skipOrFail("TAKTOVIA_DB_MIGRATOR_PASSWORD nicht gesetzt"); error("unreachable") }
        migrator.connection.use { c ->
            c.autoCommit = false
            c.createStatement().use { it.execute(Files.readString(dir.resolve("seed.sql"))) }
            c.commit()
        }
    }

    fun context(tenant: TenantId, subject: String = "m4-leser"): RequestContext =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/m4", subject, "sid-$subject", true), tenant, setOf("planner"), setOf("rosters:read"), emptySet(), UUID.randomUUID())
}
