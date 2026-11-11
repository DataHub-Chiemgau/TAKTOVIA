package de.taktovia.archfixtures.bad.servicescope

import de.taktovia.platform.authz.ServiceScoped
import org.springframework.jdbc.core.JdbcOperations
import java.util.UUID

/** Absichtlich fehlerhaft (roter Selbsttest von ServiceScopeRules): Repository mit Dienstbezug, das den ObjectAuthorizer nie aufruft. */
@ServiceScoped
class UncheckedAreaRepository(private val jdbc: JdbcOperations) {
    fun get(id: UUID): UUID? = jdbc.queryForObject("SELECT service_id FROM taktovia.service_area WHERE id = ?", UUID::class.java, id)
}
