package de.taktovia.archfixtures.good

import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import org.springframework.jdbc.core.JdbcOperations

/** Erlaubt: Aufruf direkt oder über eine Hilfsmethode derselben Klasse. */
class AuditedCommands(private val audit: AuditPort) {
    @AuditRequired
    fun direct(j: JdbcOperations, e: AuditEntry) { audit.record(j, e) }

    @AuditRequired
    fun viaHelper(j: JdbcOperations, e: AuditEntry) { write(j, e) }

    private fun write(j: JdbcOperations, e: AuditEntry) { audit.record(j, e) }
}
