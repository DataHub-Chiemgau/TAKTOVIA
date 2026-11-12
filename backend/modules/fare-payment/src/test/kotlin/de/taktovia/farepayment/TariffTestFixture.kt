package de.taktovia.farepayment

import com.zaxxer.hikari.HikariDataSource
import de.taktovia.farepayment.api.CreateTariffRevision
import de.taktovia.farepayment.api.TariffRevisionView
import de.taktovia.farepayment.api.TariffRuleInput
import de.taktovia.farepayment.api.Tariffs
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.Assertions.assertThrows
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

/**
 * Testwelt der Tarifrevisionen (WP-M2-07, eigene Klasse des Clusters): Anwendung unter der Runtime-Rolle, Mandanten und Dienste legt der Administrator bzw. die Fachtabellen an.
 * Die Mitgliedschaftsprüfung gehört dem Modul `identity` und ist durch eine zustimmende Wiederprüfung ersetzt (Teil der Anwendungstests).
 */
internal class TariffTestFixture : AutoCloseable {
    init { PaymentTestDb.require() }
    val ds: HikariDataSource = PaymentTestDb.runtime()
    private val adminDs: HikariDataSource = PaymentTestDb.admin()
    val admin: JdbcTemplate = JdbcTemplate(adminDs)
    val tx: TenantTransactions = TenantTransactions(ds)
    private val ctxTx: ContextTransactions = ContextTransactions(tx, object : ContextRevalidator { override fun revalidate(jdbc: JdbcOperations, ctx: RequestContext, lock: Boolean) {} })
    val tariffs: Tariffs = Tariffs.create(ctxTx, JdbcAuditPort(), Outbox())

    class World(val tenant: TenantId, val service: UUID)

    fun world(): World {
        val tenant = PaymentTestDb.newTenant(admin)
        return World(tenant, newService(tenant))
    }

    /** Weiterer Dienst im selben Mandanten (Rechtsträger, Betreiber, Konfigurationsrevision, Dienst). */
    fun newService(tenant: TenantId): UUID {
        val t = tenant.value; val le = UUID.randomUUID(); val op = UUID.randomUUID(); val svc = UUID.randomUUID(); val cfg = UUID.randomUUID()
        tx.run(tenant) { j ->
            j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", t, le)
            j.update("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,?,?,CAST(? AS text[]))", t, op, le, "Betreiber", "f-$op", "{TRANSPORT_COMPANY}")
            j.update("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'SERVICE',?,1,'1','{}','2026-01-01','user:test','Test')", t, cfg, svc)
            j.update("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,created_by) VALUES (?,?,?,'Rufbus','Europe/Berlin',?,'user:test')", t, svc, op, cfg)
        }
        return svc
    }

    fun context(tenant: TenantId, subject: String = UUID.randomUUID().toString(), type: ActorType = ActorType.HUMAN,
                scopes: Set<String> = ALL_SCOPES, services: Set<UUID> = emptySet()) =
        RequestContext(Actor(type, "https://idp.test/realms/tarif", subject, "sid-$subject", true), tenant, setOf("tenant_admin"), scopes, services, UUID.randomUUID())

    fun fixedRule(amount: Long = 450, taxRate: String = "0.07") =
        TariffRuleInput(ruleType = "FESTPREIS", amountMinor = amount, taxRate = taxRate, taxRuleRevision = "DE-2026-1")

    fun create(ctx: RequestContext, w: World, from: String = "2026-01-01T00:00:00Z", to: String? = null, tariffClass: String = "STANDARD", rules: List<TariffRuleInput>? = listOf(fixedRule())) =
        tariffs.create(ctx, CreateTariffRevision(w.service, tariffClass, Instant.parse(from), to?.let(Instant::parse), "EUR", "Tarif $from", rules))

    /** Entwurf -> geprüft -> veröffentlicht durch zwei weitere Personen (Ersteller [creator]). */
    fun published(creator: RequestContext, w: World, from: String = "2026-01-01T00:00:00Z", to: String? = null, tariffClass: String = "STANDARD", rules: List<TariffRuleInput>? = listOf(fixedRule())): TariffRevisionView {
        val draft = create(creator, w, from, to, tariffClass, rules)
        val reviewer = context(w.tenant)
        val reviewed = tariffs.review(reviewer, draft.id, draft.revision)
        return tariffs.publish(reviewer, draft.id, reviewed.revision)
    }

    fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java) { block() }

    fun adminCount(sql: String, vararg args: Any?): Int = admin.queryForObject(sql, Int::class.java, *args)!!

    override fun close() { ds.close(); adminDs.close() }

    companion object {
        val ALL_SCOPES = setOf("tariffs:read", "tariffs:write", "tariffs:publish", "services:read", "services:write")
    }
}
