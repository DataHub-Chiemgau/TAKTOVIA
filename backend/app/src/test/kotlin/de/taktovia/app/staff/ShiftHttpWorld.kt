package de.taktovia.app.staff

import de.taktovia.app.geo.GeoWorld
import java.util.UUID

/** Testwelt der Schichten über die echte Anwendung (WP-M4-03): Mandant mit Betreiber, Depot, Planer und Personalrolle. Eigene Klasse. */
internal class ShiftHttpWorld {
    val g = GeoWorld("shift-" + UUID.randomUUID())
    val planner = UUID.randomUUID(); val hr = UUID.randomUUID(); val dispatcher = UUID.randomUUID()
    init { g.env.grant(g.tenant, planner, "planner"); g.env.grant(g.tenant, hr, "staff_planning"); g.env.grant(g.tenant, dispatcher, "dispatcher") }
    val plannerToken = g.env.token(planner); val hrToken = g.env.token(hr); val dispatcherToken = g.env.token(dispatcher)
    val roster: String = UUID.randomUUID().toString()
    val operator: String = g.operator
    val depot: String = g.id("/v1/depots", """{"region_id":"${g.id("/v1/regions", """{"name":"Region"}""")}","name":"Depot Nord"}""")

    fun body(start: String = "2026-12-01T22:00:00", end: String = "2026-12-02T06:00:00", extra: String = "", tz: String = "Europe/Berlin", depotId: String = depot, roster: String = this.roster, need: Int = 2) =
        """{"roster_id":"$roster","starts_local":"$start","ends_local":"$end","timezone":"$tz","operator_id":"$operator","depot_id":"$depotId","function":"Fahrdienst","required_qualification":"PERSONENBEFOERDERUNG","staffing_need":$need$extra}"""

    fun post(body: String, token: String = plannerToken) = g.env.server.send("POST", "/v1/rosters/shifts", body, g.env.headers(token, extra = g.idem()))
    fun get(path: String, token: String = plannerToken) = g.env.server.send("GET", path, null, g.env.headers(token))
    fun put(id: String, body: String, ifMatch: String?, token: String = plannerToken) =
        g.env.server.send("PUT", "/v1/rosters/shifts/$id", body, g.env.headers(token, extra = g.idem() + (if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap())))
}
