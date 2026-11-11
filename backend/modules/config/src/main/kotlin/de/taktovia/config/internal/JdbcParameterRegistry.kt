package de.taktovia.config.internal

import de.taktovia.config.api.ParameterCatalog
import de.taktovia.config.api.ParameterRegistry
import de.taktovia.config.api.ScopeKind
import de.taktovia.config.api.ValidationOutcome
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import java.time.Clock

/** Lesender Zugriff auf den Parameterkatalog (nur lesen; geändert wird der Katalog ausschließlich per Migration). */
internal class JdbcParameterRegistry(
    private val ctxTx: ContextTransactions, private val catalogs: PolicyCatalogRepository, private val clock: Clock,
) : ParameterRegistry {

    override fun catalog(ctx: RequestContext): ParameterCatalog = ctxTx.run(ctx, readOnly = true) { j ->
        val version = catalogs.currentVersion(j, clock.instant()) ?: throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Kein Parameterkatalog gültig.")
        (catalogs.load(j, version) ?: throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Parameterkatalog nicht ladbar.")).toApi()
    }

    override fun validate(ctx: RequestContext, scope: ScopeKind, values: Map<String, Any?>, schemaVersion: String?): ValidationOutcome =
        ctxTx.run(ctx, readOnly = true) { j ->
            val version = schemaVersion ?: catalogs.currentVersion(j, clock.instant())
            val catalog = version?.let { catalogs.load(j, it) }
                ?: return@run ValidationOutcome.Invalid(version ?: "", listOf(Violation("/schema_version", ViolationCode.INVALID_VALUE)))
            ParameterValidator(catalog).validate(scope, values)
        }
}
