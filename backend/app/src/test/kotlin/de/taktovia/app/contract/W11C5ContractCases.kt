package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/**
 * Vertragsfälle des Clusters wave11-c5 (WP-M4-35 bis -37: Belegaufteilung, Fiskalperiode, ERP-Export): je Operation ein positiver Fall und ein Fehlerfall. Eigene Klasse je Cluster; die Welt baut auf
 * [W10C5ContractCases.World] auf (Mandant, Rechtsträger, Finanzrollen, freigegebener Beleg).
 */
internal object W11C5ContractCases {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private fun im(rev: Long) = mapOf("If-Match" to "\"$rev\"")

    /** Mandant mit gebuchtem Kostenereignis (Tankbeleg 119,00 EUR, Kostenbasis 100,00 EUR) und Kostenstellen des Rechtsträgers. */
    class World {
        val base = W10C5ContractCases.World()
        val tenant get() = base.tenant
        val finance: String get() = base.token
        val allocatorToken: String by lazy { base.financeUser() }

        fun centers(n: Int = 2): List<String> = (1..n).map {
            val id = UUID.randomUUID()
            env.tx.run(tenant) { j -> j.update("INSERT INTO taktovia.cost_object(tenant_id,id,legal_entity_id,kind,business_ref) VALUES (?,?,?,'COST_CENTER',?)", tenant.value, id, base.le, id.toString()) }
            id.toString()
        }.sorted()

        /** Gebuchtes Kostenereignis über den echten Freigabeweg; liefert die Ereigniskennung. */
        fun postedEvent(): String {
            val (doc, rev) = with(W10C5ContractCases) { base.readyDocument("REVIEWED") }
            val r = env.server.send("POST", "/v1/expenses/$doc/approve", """{"reason":"Freigegeben","validated_document_revision":$rev}""", env.headers(base.approverToken, extra = idem() + im(rev)))
            check(r.status == 200) { r.text }
            return r.json["posting_id"].asString()
        }
    }

    fun amountLines(a: String, b: String, first: Long = 6000, second: Long = 4000) =
        """[{"cost_object_type":"COST_CENTER","cost_object_id":"$a","amount":{"amount_minor":$first,"currency":"EUR","exponent":2}},{"cost_object_type":"COST_CENTER","cost_object_id":"$b","amount":{"amount_minor":$second,"currency":"EUR","exponent":2}}]"""
    fun allocationBody(lines: String, reason: String = "Aufteilung nach Testschlüssel") = """{"allocation_policy_revision":"test-key-v1","reason":"$reason","lines":$lines}"""

    private fun periodBody(le: UUID, from: String, to: String) = """{"legal_entity_id":"$le","starts_on":"$from","ends_on":"$to"}"""

    /** Offene Perioden September und Oktober 2026 über den echten Befehl; liefert die Kennungen. */
    fun World.periods(): Pair<String, String> {
        fun create(from: String, to: String): String {
            val r = env.server.send("POST", "/v1/fiscal-periods", periodBody(base.le, from, to), env.headers(finance, extra = idem())); check(r.status == 201) { r.text }; return r.json["id"].asString()
        }
        return create("2030-01-01", "2030-02-01") to create("2030-02-01", "2030-03-01")
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("createFiscalPeriod",
            201, { val w = World(); ContractRequest("POST", "/v1/fiscal-periods", periodBody(w.base.le, "2030-01-01", "2030-02-01"), env.headers(w.finance, extra = idem())) },
            422, { val w = World(); ContractRequest("POST", "/v1/fiscal-periods", periodBody(w.base.le, "2030-02-01", "2030-01-01"), env.headers(w.finance, extra = idem())) }, server = { env.server }),
        ContractCase("listFiscalPeriods",
            200, { val w = World(); with(this) { w.periods() }; ContractRequest("GET", "/v1/fiscal-periods?limit=1", null, env.headers(w.finance)) },
            422, { val w = World(); ContractRequest("GET", "/v1/fiscal-periods?limit=0", null, env.headers(w.finance)) }, server = { env.server }),
        ContractCase("closeFiscalPeriod",
            200, { val w = World(); val (p, _) = with(this) { w.periods() }; ContractRequest("POST", "/v1/fiscal-periods/$p/close", "", env.headers(w.finance, extra = idem())) },
            404, { val w = World(); ContractRequest("POST", "/v1/fiscal-periods/${UUID.randomUUID()}/close", "", env.headers(w.finance, extra = idem())) }, server = { env.server }),
        ContractCase("createCostEventReversal",
            201, { val w = World(); val e = w.postedEvent(); ContractRequest("POST", "/v1/cost-events/$e/reversals", """{"reason":"Gutschrift Lieferant","credit_gross_minor":2000}""", env.headers(w.allocatorToken, extra = idem())) },
            422, { val w = World(); val e = w.postedEvent(); ContractRequest("POST", "/v1/cost-events/$e/reversals", """{"reason":"Zu viel","credit_gross_minor":999999}""", env.headers(w.allocatorToken, extra = idem())) }, server = { env.server }),
        ContractCase("allocateCost",
            201, { val w = World(); val (a, b) = w.centers(); val e = w.postedEvent(); ContractRequest("POST", "/v1/cost-events/$e/allocations", allocationBody(amountLines(a, b)), env.headers(w.allocatorToken, extra = idem() + im(0))) },
            422, { val w = World(); val (a, b) = w.centers(); val e = w.postedEvent(); ContractRequest("POST", "/v1/cost-events/$e/allocations", allocationBody(amountLines(a, b, 6000, 4001)), env.headers(w.allocatorToken, extra = idem() + im(0))) },
            server = { env.server }),
    )
}
