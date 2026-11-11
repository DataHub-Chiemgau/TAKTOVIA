package de.taktovia.app.probe

import de.taktovia.app.webhook.InboundWebhookResponder
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import de.taktovia.platform.webhook.InboundConfig
import de.taktovia.platform.webhook.InboundHandler
import de.taktovia.platform.webhook.InboundKey
import de.taktovia.platform.webhook.InboundWebhookReceiver
import de.taktovia.platform.webhook.StaticInboundKeys
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Schlüssel, Zähler und Grenzen des Testeingangs (nur Tests). */
object InboundProbe {
    val effects = AtomicInteger()
    val tenant: TenantId get() = TenantId(ProbeDb.tenantA)
    val secretOld = "probe-inbound-secret-old-0123456789".toByteArray()
    val secretNew = "probe-inbound-secret-new-0123456789".toByteArray()
    const val MAX_BODY = 4096
}

/** Nur in Tests (`taktovia.probe.inbound=true`): ein Eingangsendpunkt über den wiederverwendbaren [InboundWebhookResponder] mit zwei Schlüsseln (Rotation). */
@Configuration
@ConditionalOnProperty("taktovia.probe.inbound", havingValue = "true")
class InboundProbeConfig {
    @Bean fun inboundProbeResponder(tx: TenantTransactions, security: SecurityEventPort) = InboundWebhookResponder(InboundWebhookReceiver(tx,
        StaticInboundKeys(listOf(InboundKey(InboundProbe.tenant, "partner-x", "k-old", InboundProbe.secretOld, validUntil = Instant.now().plusSeconds(3600)),
            InboundKey(InboundProbe.tenant, "partner-x", "k-new", InboundProbe.secretNew))),
        security, InboundHandler { _, _, _, _ -> InboundProbe.effects.incrementAndGet() }, InboundConfig(maxBodyBytes = InboundProbe.MAX_BODY)))
}

@RestController
@RequestMapping("/v1/probe/inbound")
@ConditionalOnProperty("taktovia.probe.inbound", havingValue = "true")
class InboundProbeController(private val responder: InboundWebhookResponder) {
    @PostMapping("/{source}")
    fun receive(@PathVariable source: String, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> = responder.handle(source, request)
}
