package de.taktovia.app

import de.taktovia.TaktoviaTestSupport
import de.taktovia.platform.alert.OperatorAlertPort
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** Die Plattformbausteine sind im Anwendungskontext verfügbar und arbeiten gegen die echte Datenbank (Outbox in Mandanten-Transaktion). */
class PlatformPortsTest {
    @Test fun `Plattformports sind verdrahtet und die Outbox schreibt in der Mandanten-Transaktion`() {
        TaktoviaTestSupport.requireDatabase()
        val admin = TaktoviaTestSupport.adminDataSourceOrNull() ?: return TaktoviaTestSupport.skipOrFail("Administratorzugang nicht konfiguriert")
        val t = TenantId(UUID.randomUUID()); JdbcTemplate(admin).update("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?, 'ACTIVE')", t.value, "app-$t")
        SpringApplicationBuilder(TaktoviaApplication::class.java).run("--server.port=0").use { ctx ->
            ctx.getBean(AuditPort::class.java); ctx.getBean(SecurityEventPort::class.java); ctx.getBean(OperatorAlertPort::class.java)
            val tx = ctx.getBean(TenantTransactions::class.java); val outbox = ctx.getBean(Outbox::class.java)
            tx.run(t) { j -> outbox.append(j, de.taktovia.platform.outbox.OutboxEvent(UUID.randomUUID(), 1, "app.test.created", mapOf("a" to 1), java.time.Instant.now())) }
            assertEquals(1, tx.run(t, readOnly = true) { it.queryForObject("SELECT count(*) FROM taktovia.outbox", Int::class.java) })
        }
    }
}
