package de.taktovia.app.fleet

import de.taktovia.app.identity.RequestContexts
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.VehicleMasterView
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Paging
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.stafftime.api.LatestPosition
import de.taktovia.stafftime.api.StafftimeApi
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import jakarta.servlet.http.HttpServletRequest
import java.time.Instant

/**
 * Fahrzeuge der Leitstelle mit letzter Position, Messzeit und Veraltet-Kennzeichen (WP-M3-34, AT-10; `GET /v1/operator/vehicles`). Fahrzeugliste aus `fleet-safety`, Positionen aus
 * `staff-time` (App-Ebene setzt beide zusammen, kein Modul liest die Tabellen des anderen). Das Standortalter berechnet der Server aus der **Messzeit** und seiner Uhr; ab der Schwelle
 * (`position_stale_after_sec`, Planungsdefault 30 s) ist `position_stale` wahr, die letzte gemessene Position bleibt sichtbar und wird nie extrapoliert. Ohne bekannte Position
 * ist `position` null und `position_stale` wahr (kein Livestand vortäuschen).
 *
 * Vorläufig bis WP-M3-08/-31/-36 (Sperr- und Freigabekern, Dienststatus, Livebetrieb): `operational_state` leitet sich nur aus der Sperrkennung des Fahrzeugs ab (`NOT_READY` solange
 * `active_block`, sonst `AVAILABLE`), `release_valid_until`, `missing_requirements`, `active_blocks`, `open_defect_count` und `current_run_id` tragen noch keine Werte. Der Filter
 * `depot_id` wird nicht unterstützt (kein Betriebshofbezug am Fahrzeug, OP-22): 422.
 */
@RestController
class OperatorVehicleController(private val fleet: FleetsafetyApi, private val staff: StafftimeApi, private val codec: CursorCodec) {

    @GetMapping("/v1/operator/vehicles")
    fun list(
        @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, @RequestParam(required = false) state: String?,
        @RequestParam(name = "depot_id", required = false) depotId: String?, request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        if (depotId != null) throw ProblemException.invalid(Violation("/\$query/depot_id", ViolationCode.INVALID_VALUE))
        if (state != null && state !in STATES) throw ProblemException.invalid(Violation("/\$query/state", ViolationCode.NOT_IN_ENUM))
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "operator-vehicles")
        val lim = Paging.limit(limit)
        val page = fleet.vehicles.list(ctx, true, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        val shown = page.take(lim)
        val positions = staff.positions.latest(ctx, shown.map { it.id })
        val now = Instant.now()
        return Paging.page(page, lim, { CursorPosition(it.createdAt, it.id) }, codec, scope, now).toMap { v -> json(v, positions[v.id]) }.also { out ->
            if (state != null) {
                @Suppress("UNCHECKED_CAST") val items = out["items"] as MutableList<Map<String, Any?>>
                items.removeAll { it["operational_state"] != state }
            }
        }
    }

    private fun json(v: VehicleMasterView, p: LatestPosition?): Map<String, Any?> = linkedMapOf(
        "vehicle_id" to v.id.toString(), "vehicle_label" to (v.label ?: v.id.toString().take(8)),
        "operational_state" to (if (v.dispatchBlocked) "NOT_READY" else "AVAILABLE"), "safety_revision" to v.safetyRevision, "release_valid_until" to null,
        "missing_requirements" to emptyList<Any>(), "active_blocks" to emptyList<Any>(),
        "position" to p?.let { linkedMapOf("latitude" to it.latitude, "longitude" to it.longitude) }, "position_measured_at" to p?.measuredAt?.toString(),
        "position_received_at" to p?.receivedAt?.toString(), "position_age_seconds" to p?.ageSeconds, "position_stale" to (p?.stale ?: true),
        "open_defect_count" to 0, "current_run_id" to null, "revision" to v.revision,
    )

    private companion object {
        val STATES = setOf("NOT_READY", "RELEASE_PENDING", "AVAILABLE", "EN_ROUTE", "WAITING", "BREAK", "DISRUPTED", "BLOCKED", "RESTRICTED", "ENDED")
    }
}
