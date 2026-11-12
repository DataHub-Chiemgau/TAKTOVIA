package de.taktovia.documentsfinance

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.taktovia.documentsfinance.api.DocumentUploads
import de.taktovia.documentsfinance.api.MalwareScanner
import de.taktovia.documentsfinance.api.UploadPolicy
import de.taktovia.documentsfinance.api.Uploads
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.storage.TenantObjectStore
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.security.MessageDigest
import java.util.UUID

/** Datenbank und Anwendungsbausteine für die Upload-Tests (WP-M4-21): echte Datenbank unter der Runtime-Rolle, Mandanten und Rechtsträger legt der Administrator an. */
class UploadFixture(val store: TenantObjectStore) : AutoCloseable {
    private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }

    init {
        if (env("TAKTOVIA_DB_RUNTIME_PASSWORD") == null || env("TAKTOVIA_DB_ADMIN_PASSWORD") == null) {
            if (env("TAKTOVIA_REQUIRE_DB") == "true") throw AssertionError("Datenbank erforderlich (TAKTOVIA_REQUIRE_DB=true)")
            assumeTrue(false, "Datenbank-Zugangsdaten nicht gesetzt")
        }
    }

    private val url = "jdbc:postgresql://${env("TAKTOVIA_DB_HOST") ?: "127.0.0.1"}:${env("TAKTOVIA_DB_PORT") ?: "5432"}/${env("TAKTOVIA_DB_NAME") ?: "taktovia"}"
    private fun pool(user: String, pw: String, n: Int) = HikariDataSource(HikariConfig().also { it.jdbcUrl = url; it.username = user; it.password = pw; it.maximumPoolSize = n; it.minimumIdle = 1 })
    val ds = pool("taktovia_runtime", env("TAKTOVIA_DB_RUNTIME_PASSWORD")!!, 24)
    private val admin = pool(env("TAKTOVIA_DB_ADMIN_USER") ?: "dev_admin", env("TAKTOVIA_DB_ADMIN_PASSWORD")!!, 1)
    val tx = TenantTransactions(ds)
    val ctxTx = ContextTransactions(tx, object : ContextRevalidator { override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, lock: Boolean) {} })
    private val audit = JdbcAuditPort()
    val security = SecurityEventPort(audit)

    fun uploads(scanner: MalwareScanner, policy: UploadPolicy = UploadPolicy(maxBytes = 64 * 1024)): Uploads =
        DocumentUploads.create(ctxTx, audit, security, Outbox(), store, scanner, policy)

    class World(val tenant: TenantId, val legalEntity: UUID)

    fun world(): World {
        val t = UUID.randomUUID(); val le = UUID.randomUUID()
        exec("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,'ACTIVE')", t, "t-$t")
        tx.run(TenantId(t)) { j -> j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", t, le) }
        return World(TenantId(t), le)
    }

    fun ctx(tenant: TenantId, subject: UUID = UUID.randomUUID(), roles: Set<String> = setOf("driver"), scopes: Set<String> = setOf("receipts:write", "receipts:read")) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/m4", subject.toString(), "sid-$subject", true), tenant, roles, scopes, emptySet(), UUID.randomUUID())

    fun exec(sql: String, vararg args: Any?) { admin.connection.use { c -> c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeUpdate() } } }
    fun count(sql: String, vararg args: Any?): Int = admin.connection.use { c -> c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { it.next(); it.getInt(1) } } }
    fun strings(sql: String, vararg args: Any?): List<String> = admin.connection.use { c -> c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } } }

    override fun close() { ds.close(); admin.close() }
}

object Samples {
    fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    fun jpeg(n: Int = 2048, seed: Int = 1): ByteArray = ByteArray(n) { ((it * 31 + seed) and 0xFF).toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte(); it[3] = 0xE0.toByte() }
    fun png(n: Int = 512): ByteArray = ByteArray(n) { it.toByte() }.also { b -> byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A).copyInto(b) }
    fun pdf(extra: String = "", n: Int = 1024): ByteArray = ("%PDF-1.4\n$extra\n" + "x".repeat(n)).toByteArray(Charsets.ISO_8859_1)
}
