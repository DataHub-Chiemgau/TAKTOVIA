package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.app.staff.ShiftHttpWorld
import java.util.UUID

/** Vertragsfälle der Dienstpläne (WP-M4-04): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung. */
internal object RosterContractCases {
    private fun idem(w: ShiftHttpWorld, extra: Map<String, String> = emptyMap()) = w.g.env.headers(w.plannerToken, extra = w.g.idem() + extra)
    private fun hg(w: ShiftHttpWorld) = w.g.env.headers(w.plannerToken)

    val cases: List<ContractCase> = listOf(
        ContractCase("createRoster",
            201, { val w = ShiftHttpWorld(); ContractRequest("POST", "/v1/rosters", """{"depot_id":"${w.depot}","week_start":"2026-12-07"}""", idem(w)) },
            422, { val w = ShiftHttpWorld(); ContractRequest("POST", "/v1/rosters", """{"depot_id":"${w.depot}","week_start":"2026-12-09"}""", idem(w)) }, server = { IdentityEnv.server }),
        ContractCase("getRoster",
            200, { val w = ShiftHttpWorld(); w.post(w.body()); ContractRequest("GET", "/v1/rosters/${w.roster}", headers = hg(w)) },
            404, { val w = ShiftHttpWorld(); ContractRequest("GET", "/v1/rosters/${UUID.randomUUID()}", headers = hg(w)) }, server = { IdentityEnv.server }),
        ContractCase("listRosters",
            200, { val w = ShiftHttpWorld(); ContractRequest("GET", "/v1/rosters?depot_id=${w.depot}", headers = hg(w)) },
            403, { val w = ShiftHttpWorld(); ContractRequest("GET", "/v1/rosters", headers = w.g.env.headers(w.dispatcherToken)) }, server = { IdentityEnv.server }),
        ContractCase("openRosterDraft",
            201, { val w = ShiftHttpWorld(); w.post(w.body()); w.publishDraft(); ContractRequest("POST", "/v1/rosters/${w.roster}/draft", null, idem(w, mapOf("If-Match" to "\"${w.get("/v1/rosters/${w.roster}").json["revision"].asLong()}\""))) },
            409, { val w = ShiftHttpWorld(); ContractRequest("POST", "/v1/rosters/${w.roster}/draft", null, idem(w, mapOf("If-Match" to "\"1\""))) }, server = { IdentityEnv.server }),
    )
}
