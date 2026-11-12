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

    val cases: List<ContractCase> = listOf(
        ContractCase("allocateCost",
            201, { val w = World(); val (a, b) = w.centers(); val e = w.postedEvent(); ContractRequest("POST", "/v1/cost-events/$e/allocations", allocationBody(amountLines(a, b)), env.headers(w.allocatorToken, extra = idem() + im(0))) },
            422, { val w = World(); val (a, b) = w.centers(); val e = w.postedEvent(); ContractRequest("POST", "/v1/cost-events/$e/allocations", allocationBody(amountLines(a, b, 6000, 4001)), env.headers(w.allocatorToken, extra = idem() + im(0))) },
            server = { env.server }),
    )
}
