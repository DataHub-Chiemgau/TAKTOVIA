package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/**
 * Vertragsfälle des Clusters wave12-c8 (WP-M4-39, -40: Kostenklassifikation, Umklassifikation, manuelle Fahrzeugkosten, Fahrzeugkosten je Monat): je Operation ein positiver Fall und ein Fehlerfall.
 * Eigene Klasse je Cluster; die Welt baut auf [W10C5ContractCases.World] auf (Mandant, Rechtsträger, Fahrzeug, Finanzrollen, Buchungsperiode 2026, Kontierung FUEL) und ergänzt die Kontierung der Kostenart INSURANCE.
 */
internal object W12C8ContractCases {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private fun im(rev: Long) = mapOf("If-Match" to "\"$rev\"")

    class World {
        val base = W10C5ContractCases.World()
        val tenant get() = base.tenant
        val finance: String get() = base.token
        val reviewer: String get() = base.reviewerToken
        val approver: String get() = base.approverToken
        val approver2: String get() = base.approver2Token
        val allocator: String by lazy { base.financeUser() }
        init {
            env.tx.run(tenant) { j -> j.update("INSERT INTO taktovia.accounting_mapping(tenant_id,legal_entity_id,category,expense_account,input_tax_account,payable_account,updated_by) VALUES (?,?,'INSURANCE','6300','1576','1600','contract')", tenant.value, base.le) }
        }

        fun manualBody(key: String = "ursprung-" + UUID.randomUUID(), category: String = "INSURANCE", gross: Long = 50000) =
            """{"legal_entity_id":"${base.le}","vehicle_id":"${base.vehicle}","category":"$category","gross":{"amount_minor":$gross,"currency":"EUR","exponent":2},"accounting_date":"2026-06-10","service_from":"2026-06-01","service_to":"2026-06-30","origin_key":"$key"}"""

        /** Neues manuelles Kostenereignis (Zustand IN_REVIEW, Revision 1); liefert die Kennung. */
        fun manual(): String {
            val r = env.server.send("POST", "/v1/cost-events", manualBody(), env.headers(finance, extra = idem())); check(r.status == 201) { r.text }
            return r.json["id"].asString()
        }

        /** Manuelles Kostenereignis nach der fachlichen Prüfung (REVIEWED). */
        fun reviewed(): String {
            val id = manual()
            val r = env.server.send("POST", "/v1/manual-cost-events/$id/review", """{"decision":"APPROVED","reason":"Sachlich geprüft"}""", env.headers(reviewer, extra = idem() + im(1))); check(r.status == 200) { r.text }
            return id
        }
    }

    private val classificationQuery = "valuation=ACTUAL&currency=EUR&period_from=2026-01-01&period_to=2027-01-01"

    val cases: List<ContractCase> = listOf(
        ContractCase("createManualCostEvent",
            201, { val w = World(); ContractRequest("POST", "/v1/cost-events", w.manualBody(), env.headers(w.finance, extra = idem())) },
            422, { val w = World(); ContractRequest("POST", "/v1/cost-events", w.manualBody(category = "UNBEKANNT"), env.headers(w.finance, extra = idem())) }, server = { env.server }),
        ContractCase("getManualCostEvent",
            200, { val w = World(); ContractRequest("GET", "/v1/manual-cost-events/${w.manual()}", null, env.headers(w.finance)) },
            404, { val w = World(); ContractRequest("GET", "/v1/manual-cost-events/${UUID.randomUUID()}", null, env.headers(w.finance)) }, server = { env.server }),
        ContractCase("reviewManualCostEvent",
            200, { val w = World(); ContractRequest("POST", "/v1/manual-cost-events/${w.manual()}/review", """{"decision":"APPROVED","reason":"Sachlich geprüft"}""", env.headers(w.reviewer, extra = idem() + im(1))) },
            422, { val w = World(); ContractRequest("POST", "/v1/manual-cost-events/${w.manual()}/review", """{"decision":"VIELLEICHT","reason":"Sachlich geprüft"}""", env.headers(w.reviewer, extra = idem() + im(1))) }, server = { env.server }),
        ContractCase("approveManualCostEvent",
            200, { val w = World(); ContractRequest("POST", "/v1/manual-cost-events/${w.reviewed()}/approve", """{"reason":"Freigegeben"}""", env.headers(w.approver, extra = idem() + im(1))) },
            409, { val w = World(); ContractRequest("POST", "/v1/manual-cost-events/${w.manual()}/approve", """{"reason":"Zu früh"}""", env.headers(w.approver, extra = idem() + im(1))) }, server = { env.server }),
        ContractCase("rejectManualCostEvent",
            200, { val w = World(); ContractRequest("POST", "/v1/manual-cost-events/${w.reviewed()}/reject", """{"reason":"Nicht budgetiert"}""", env.headers(w.approver, extra = idem() + im(1))) },
            409, { val w = World(); ContractRequest("POST", "/v1/manual-cost-events/${w.manual()}/reject", """{"reason":"Zu früh"}""", env.headers(w.approver, extra = idem() + im(1))) }, server = { env.server }),
        ContractCase("getCostClassification",
            200, { val w = World(); postedFuelEvent(w)
                ContractRequest("GET", "/v1/cost-events/classification?$classificationQuery", null, env.headers(w.finance)) },
            422, { val w = World(); ContractRequest("GET", "/v1/cost-events/classification?currency=EUR&period_from=2026-01-01&period_to=2027-01-01", null, env.headers(w.finance)) }, server = { env.server }),
        ContractCase("getVehicleTotalCost",
            200, { val w = World(); ContractRequest("GET", "/v1/vehicles/${w.base.vehicle}/total-cost?valuation=ACTUAL&currency=EUR&month_from=2026-06&month_to=2026-08", null, env.headers(w.finance)) },
            404, { val w = World(); ContractRequest("GET", "/v1/vehicles/${UUID.randomUUID()}/total-cost?valuation=ACTUAL&currency=EUR&month_from=2026-06&month_to=2026-08", null, env.headers(w.finance)) }, server = { env.server }),
        ContractCase("reclassifyCostEvent",
            201, { val w = World(); val e = postedFuelEvent(w); ContractRequest("POST", "/v1/cost-events/$e/reclassifications", """{"cost_behavior":"FIXED","attribution":"DIRECT","reason":"Pauschale ist fix","accounting_date":"2026-06-20"}""", env.headers(w.allocator, extra = idem())) },
            409, { val w = World(); val e = postedFuelEvent(w); ContractRequest("POST", "/v1/cost-events/$e/reclassifications", """{"cost_behavior":"VARIABLE","attribution":"DIRECT","reason":"Unverändert","accounting_date":"2026-06-20"}""", env.headers(w.allocator, extra = idem())) }, server = { env.server }),
    )

    /** Gebuchter Tankbeleg-Kostenfall (Kostenart FUEL: variabel, direkt); liefert die Ereigniskennung. */
    fun postedFuelEvent(w: World): String {
        val (doc, rev) = with(W10C5ContractCases) { w.base.readyDocument("REVIEWED") }
        val r = env.server.send("POST", "/v1/expenses/$doc/approve", """{"reason":"Freigegeben","validated_document_revision":$rev}""", env.headers(w.approver, extra = idem() + im(rev))); check(r.status == 200) { r.text }
        return r.json["posting_id"].asString()
    }
}
