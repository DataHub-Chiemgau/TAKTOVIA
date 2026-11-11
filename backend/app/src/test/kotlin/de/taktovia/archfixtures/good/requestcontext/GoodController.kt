package de.taktovia.archfixtures.good.requestcontext

import de.taktovia.platform.context.TenantSelectionRequest
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import java.util.UUID

/** Zulässig: das markierte DTO der Mandantenauswahl; ein DTO ohne Mandant. */
@TenantSelectionRequest
class GoodSwitchRequest(val tenantId: UUID)
class GoodNameRequest(val name: String)

class GoodController {
    @PostMapping("/good/switch") fun switch(@RequestBody body: GoodSwitchRequest) = body.tenantId.toString()
    @PostMapping("/good/name") fun name(@RequestBody body: GoodNameRequest) = body.name
}
