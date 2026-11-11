package de.taktovia.servicegeo.internal

import de.taktovia.config.api.ConfigApi
import de.taktovia.config.api.EffectiveRequest
import de.taktovia.config.api.EffectiveStatus
import de.taktovia.config.api.RevisionValidator
import de.taktovia.platform.context.RequestContext
import de.taktovia.servicegeo.api.OfferModel
import de.taktovia.servicegeo.api.OfferModelRules
import de.taktovia.servicegeo.api.ServiceConfigurationView
import de.taktovia.servicegeo.api.ServiceConfigurations
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Wirksame Modellregeln eines Dienstes über den Konfigurations-Port (`ConfigApi.effective`): eine konsistente Auflösung der Dienstkonfiguration und der fünf modellabhängigen Werte. */
internal class JdbcServiceConfigurations(private val config: ConfigApi, private val clock: Clock) : ServiceConfigurations {
    override val revisionValidator: RevisionValidator = ServiceConfigRevisionValidator()

    override fun effective(ctx: RequestContext, serviceId: UUID, at: Instant?): ServiceConfigurationView {
        val asOf = at ?: clock.instant()
        val names = setOf("service_config") + OfferModelRules.PARAMETERS
        val view = config.effective.resolve(ctx, EffectiveRequest(serviceId = serviceId, at = asOf, parameters = names))
        val byName = view.items.associateBy { it.parameter }
        val sc = byName["service_config"]?.takeIf { it.status == EffectiveStatus.EFFECTIVE }
        val model = (sc?.value as? Map<*, *>)?.get("traffic_type")?.let { OfferModel.of(it) }
        val params = OfferModelRules.PARAMETERS.mapNotNull { n -> byName[n]?.takeIf { it.status == EffectiveStatus.EFFECTIVE }?.let { n to it.value } }.toMap()
        return ServiceConfigurationView(serviceId, asOf, model, model?.let { OfferModelRules.of(it) }, params, sc?.revisionId, sc?.effectiveFrom)
    }
}
