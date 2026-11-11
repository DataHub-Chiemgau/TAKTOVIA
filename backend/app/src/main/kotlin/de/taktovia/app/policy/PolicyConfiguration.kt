package de.taktovia.app.policy

import de.taktovia.config.api.ConfigApi
import de.taktovia.config.api.EffectiveRequest
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.policy.PolicyCheck
import de.taktovia.platform.policy.PolicyDefinition
import de.taktovia.platform.policy.PolicyRuleSource
import de.taktovia.platform.policy.PolicyScope
import de.taktovia.platform.policy.ResolvedRule
import de.taktovia.platform.policy.ResolvedRules
import de.taktovia.platform.policy.ServerPolicyCheck
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant

/**
 * Regelquelle der Regelprüfung (WP-M1-26): die Präzedenzauflösung des Moduls `config` (WP-M1-21) in der Transaktion des bestätigenden Befehls. Die Quelle liefert
 * Wert und Herkunft der wirksamen Regeln; Regelwerte des Clients gibt es in diesem Weg nicht.
 */
class ConfigPolicyRuleSource(private val config: ConfigApi) : PolicyRuleSource {
    override fun resolve(jdbc: JdbcOperations, ctx: RequestContext, scope: PolicyScope, parameters: Set<String>, at: Instant): ResolvedRules {
        val view = config.effective.resolveInTransaction(jdbc, ctx, EffectiveRequest(parameters = parameters, regionId = scope.regionId, serviceId = scope.serviceId, vehicleId = scope.vehicleId, at = at))
        return ResolvedRules(view.asOf, view.schemaVersion, view.items.associate { it.parameter to ResolvedRule(it.parameter, it.value, it.level?.name, it.revisionId, it.revision) })
    }
}

/**
 * Verdrahtung der Regelprüfung. Die Regelsätze der bestätigenden Befehle (`PolicyDefinition`-Beans) liefern die Fachmodule mit M2 (Buchungsbestätigung, Tarifprüfung);
 * in M1 gibt es keinen produktiven bestätigenden Befehl, der Port wird an einem Testbefehl nachgewiesen.
 */
@Configuration
class PolicyConfiguration {
    @Bean fun policyRuleSource(config: ConfigApi): PolicyRuleSource = ConfigPolicyRuleSource(config)

    @Bean fun policyCheck(source: PolicyRuleSource, definitions: ObjectProvider<PolicyDefinition>, audit: AuditPort): PolicyCheck =
        ServerPolicyCheck(source, definitions.orderedStream().toList(), audit)
}
