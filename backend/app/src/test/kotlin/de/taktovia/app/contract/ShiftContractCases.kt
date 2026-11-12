package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.app.staff.ShiftHttpWorld
import java.util.UUID

/** Vertragsfälle der Schichten (WP-M4-03): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung. */
internal object ShiftContractCases {
    private fun idem(w: ShiftHttpWorld, extra: Map<String, String> = emptyMap()) = w.g.env.headers(w.plannerToken, extra = w.g.idem() + extra)
    private fun hg(w: ShiftHttpWorld) = w.g.env.headers(w.plannerToken)

    val cases: List<ContractCase> = listOf(
        ContractCase("createRosterShift",
            201, { val w = ShiftHttpWorld(); ContractRequest("POST", "/v1/rosters/shifts", w.body(), idem(w)) },
            422, { val w = ShiftHttpWorld(); ContractRequest("POST", "/v1/rosters/shifts", w.body("2026-12-02T06:00:00", "2026-12-01T22:00:00"), idem(w)) }, server = { IdentityEnv.server }),
        ContractCase("listRosterShifts",
            200, { val w = ShiftHttpWorld(); w.post(w.body()); ContractRequest("GET", "/v1/rosters/shifts?roster_id=${w.roster}", headers = hg(w)) },
            403, { val w = ShiftHttpWorld(); ContractRequest("GET", "/v1/rosters/shifts?roster_id=${w.roster}", headers = w.g.env.headers(w.dispatcherToken)) }, server = { IdentityEnv.server }),
        ContractCase("getRosterShift",
            200, { val w = ShiftHttpWorld(); val id = w.post(w.body()).json["id"].asString(); ContractRequest("GET", "/v1/rosters/shifts/$id?roster_id=${w.roster}", headers = hg(w)) },
            404, { val w = ShiftHttpWorld(); ContractRequest("GET", "/v1/rosters/shifts/${UUID.randomUUID()}?roster_id=${w.roster}", headers = hg(w)) }, server = { IdentityEnv.server }),
        ContractCase("updateRosterShift",
            200, { val w = ShiftHttpWorld(); val id = w.post(w.body()).json["id"].asString(); ContractRequest("PUT", "/v1/rosters/shifts/$id", w.body(need = 3), idem(w, mapOf("If-Match" to "\"1\""))) },
            412, { val w = ShiftHttpWorld(); val id = w.post(w.body()).json["id"].asString(); ContractRequest("PUT", "/v1/rosters/shifts/$id", w.body(need = 3), idem(w, mapOf("If-Match" to "\"9\""))) }, server = { IdentityEnv.server }),
    )
}
