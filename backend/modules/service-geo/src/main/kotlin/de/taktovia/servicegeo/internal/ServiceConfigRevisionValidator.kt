package de.taktovia.servicegeo.internal

import de.taktovia.config.api.ConfigRevisionView
import de.taktovia.config.api.RevisionValidator
import de.taktovia.config.api.ScopeKind
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.servicegeo.api.OfferModel
import de.taktovia.servicegeo.api.OfferModelRules
import org.springframework.jdbc.core.JdbcOperations
import java.util.UUID

/**
 * Modellregel-Validator der Dienstkonfiguration (WP-M1-25, Planungsannahme PA-01): je Angebotsmodell Pflichtparameter und unzulässige Parameter ([OfferModelRules]), dazu die Bezüge
 * der Dienstkonfiguration auf den Mandanten (Betreiber muss eine Organisation des Mandanten und der Betreiber des Dienstes sein). Reine Leseprüfung in der Transaktion des Aufrufers.
 * Datentyp, Bereich, Enum der Modelle und Vollständigkeit der `service_config` prüft schon der Parameterkatalog (422 beim Anlegen).
 */
internal class ServiceConfigRevisionValidator : RevisionValidator {
    override val name = "service-config-offer-model"

    override fun check(j: JdbcOperations, ctx: RequestContext, revision: ConfigRevisionView): List<Violation> {
        if (revision.scopeKind != ScopeKind.SERVICE) return emptyList()
        val out = mutableListOf<Violation>()
        val config = revision.values["service_config"] as? Map<*, *> ?: return listOf(Violation("/values/service_config", ViolationCode.REQUIRED))
        val model = OfferModel.of(config["traffic_type"]) ?: return listOf(Violation("/values/service_config/traffic_type", ViolationCode.NOT_IN_ENUM))
        val rules = OfferModelRules.of(model)
        rules.required.filter { it !in revision.values }.forEach { out += Violation("/values/$it", ViolationCode.REQUIRED, "Für dieses Angebotsmodell erforderlich") }
        rules.forbidden.filter { it in revision.values }.forEach { out += Violation("/values/$it", ViolationCode.UNKNOWN_FIELD, "Für dieses Angebotsmodell nicht zulässig") }
        // Bezüge: der Betreiber gehört zum Mandanten (RLS blendet fremde Organisationen aus) und ist der Betreiber des Dienstes
        val operator = (config["operator_id"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (operator == null || j.queryForObject("SELECT count(*) FROM taktovia.organization WHERE id = ?", Int::class.java, operator) != 1) {
            out += Violation("/values/service_config/operator_id", ViolationCode.INVALID_VALUE, "Betreiber unbekannt")
        } else {
            val serviceOperator = j.query("SELECT operator_id FROM taktovia.service WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, revision.scopeId).firstOrNull()
            if (serviceOperator != null && serviceOperator != operator) out += Violation("/values/service_config/operator_id", ViolationCode.INVALID_VALUE, "Betreiber weicht vom Betreiber des Dienstes ab")
        }
        return out
    }
}
