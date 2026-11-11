package de.taktovia.archfixtures.bad.requestcontext

import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import java.util.UUID

/** Absichtlich fehlerhafte Fixtures (kein Spring-Bean: ohne Stereotyp wird nichts geroutet) (roter Selbsttest von RequestContextRules): Mandant aus dem Request, selbst gebauter Kontext. */
class BadCreateRequest(val tenantId: UUID, val name: String)
class BadBody(val tenant_id: UUID)

class BadController {
    @PostMapping("/bad/create") fun create(@RequestBody body: BadBody): String = body.tenant_id.toString()
    @GetMapping("/bad/header") fun header(@RequestHeader("X-Tenant-Id") tenant: String): String = tenant
    fun forge(): RequestContext = RequestContext(Actor(ActorType.HUMAN, "i", "s", null, true), TenantId(UUID.randomUUID()), emptySet(), emptySet(), emptySet(), UUID.randomUUID())
}
