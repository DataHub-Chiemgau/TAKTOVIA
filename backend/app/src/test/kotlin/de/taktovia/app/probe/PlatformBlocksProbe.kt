package de.taktovia.app.probe

import de.taktovia.TaktoviaTestSupport
import de.taktovia.app.web.RequestIdentityResolver
import de.taktovia.platform.api.Etag
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.statemachine.StateMachines
import de.taktovia.platform.statemachine.StateTransitioner
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Tabelle des Testaggregats „Zahlung“ für die Prüf-Endpunkte (Automat `payment` aus WP-M1-17), gehärtet wie die echte Spalte. */
object PlatformBlocksDb {
    private var ready = false

    @Synchronized
    fun ensure() {
        if (ready) return
        ProbeDb.ensure()
        val admin = TaktoviaTestSupport.adminDataSourceOrNull() ?: return TaktoviaTestSupport.skipOrFail("Administratorzugang nicht konfiguriert")
        admin.connection.use { c ->
            c.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS public.payment_probe")
                st.execute("CREATE TABLE public.payment_probe(tenant_id uuid NOT NULL, id uuid NOT NULL, revision bigint NOT NULL DEFAULT 1, state text NOT NULL, PRIMARY KEY(tenant_id, id))")
                st.execute("ALTER TABLE public.payment_probe ENABLE ROW LEVEL SECURITY")
                st.execute("ALTER TABLE public.payment_probe FORCE ROW LEVEL SECURITY")
                st.execute("CREATE POLICY tenant_isolation ON public.payment_probe USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant())")
                st.execute("SELECT taktovia.harden_state_column('public.payment_probe', 'state', 'payment')")
                st.execute("GRANT SELECT, INSERT, UPDATE ON public.payment_probe TO taktovia_runtime")
            }
        }
        ready = true
    }
}

/** Nur in Tests: Zustandsübergänge am Testaggregat Zahlung über die echte Filterkette (Vertrag `probe/platform-blocks-api.yaml`). */
@RestController
@RequestMapping("/v1/probe/payments")
@ConditionalOnProperty("taktovia.probe.enabled", havingValue = "true")
class PaymentProbeController(private val tx: TenantTransactions, private val identity: ProbeIdentity) {
    private val machine = StateMachines.PAYMENT.onTable("public.payment_probe")
    private val transitioner = StateTransitioner()
    private fun tenant(r: HttpServletRequest) = TenantId.parse(identity.tenant(r))
    private fun body(id: UUID, state: String, revision: Long) = RevisionedBody(mapOf("id" to id.toString(), "state" to state, "revision" to revision), revision)

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(r: HttpServletRequest): RevisionedBody {
        val t = tenant(r); val id = UUID.randomUUID()
        tx.run(t) { it.update("INSERT INTO public.payment_probe(tenant_id, id, state) VALUES (?, ?, 'CREATED')", t.value, id) }
        return body(id, "CREATED", 1)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID, r: HttpServletRequest): RevisionedBody = tx.run(tenant(r), readOnly = true) { j ->
        j.query("SELECT state, revision FROM public.payment_probe WHERE id = ?", { rs, _ -> body(id, rs.getString(1), rs.getLong(2)) }, id).firstOrNull()
    } ?: throw ProblemException.notFound()

    @PostMapping("/{id}/transitions")
    fun transition(@PathVariable id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>,
                   @RequestHeader("X-Test-Scopes", required = false) scopes: String?, r: HttpServletRequest): RevisionedBody {
        if (scopes?.split(' ')?.contains("probe:payments:transition") != true) throw ProblemException(ErrorCode.FORBIDDEN)
        val expected = Etag.parseIfMatch(ifMatch)
        val t = tenant(r)
        val done = tx.run(t) { j -> transitioner.transition(j, machine, id, body["state"] as String, expected) }
        return body(id, done.to.name, done.revision)
    }
}
