package de.taktovia.archfixtures.good.servicescope

import de.taktovia.platform.authz.ObjectAuthorizer
import de.taktovia.platform.authz.ScopedObject
import de.taktovia.platform.authz.ServiceScoped
import de.taktovia.platform.context.RequestContext
import org.springframework.jdbc.core.JdbcOperations
import java.util.UUID

/** Gutes Fixture: Repository mit Dienstbezug, das vor dem Zugriff den Port aufruft. */
@ServiceScoped
class CheckedAreaRepository(private val authorizer: ObjectAuthorizer) {
    fun get(jdbc: JdbcOperations, ctx: RequestContext, id: UUID, service: UUID): UUID {
        authorizer.requireRead(jdbc, ctx, ScopedObject("service_area", id, ctx.tenant, service))
        return id
    }
}
