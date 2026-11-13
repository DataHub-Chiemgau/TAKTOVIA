package de.taktovia.app.fleet

import de.taktovia.app.identity.RequestContexts
import de.taktovia.bookingdispatch.api.OperatorOrders
import de.taktovia.bookingdispatch.api.OperatorRunSummary
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.OperatorBlockView
import de.taktovia.fleetsafety.api.OperatorVehicleFacts
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Paging
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.stafftime.api.LatestPosition
import de.taktovia.stafftime.api.ServiceStatus
import de.taktovia.stafftime.api.StafftimeApi
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Leitstelle Livebetrieb: Fahrzeuge, Position, Verfügbarkeit, Aufträge (WP-M3-36, LH-6.7-OPS-001; WP-M3-34 für Position und Standortalter; `GET /v1/operator/vehicles` und `/{vehicle_id}`).
 * Die Fahrzeugliste mit Sperren, Freigabe und fehlenden Voraussetzungen kommt aus `fleet-safety` (nur Fahrzeuge des berechtigten Dienstes, Scope frisch je Abfrage), Positionen und Dienststatus
 * aus `staff-time`, Aufträge aus `booking-dispatch`; die App-Ebene setzt sie zusammen, kein Modul liest die Tabellen des anderen. Fremder Mandant und nicht berechtigter Dienst sind für den Aufrufer
 * nicht vorhanden (Detail 404, Liste: nie enthalten). Ein Filter (`service_id`, `state`, `open_incidents`) schränkt nur ein. Das Standortalter berechnet der Server aus der **Messzeit** und seiner
 * Uhr; ab der Schwelle (`position_stale_after_sec`, Planungsdefault 30 s) ist `position_stale` wahr, die letzte gemessene Position bleibt sichtbar und wird nie extrapoliert; ohne bekannte
 * Position ist `position` null und `position_stale` wahr. Der Filter `depot_id` wird nicht unterstützt (kein Betriebshofbezug am Fahrzeug, OP-22): 422.
 *
 * Betriebszustand (`operational_state`): aktive Sperre ergibt `BLOCKED`; sonst bleiben Pause, Störung und Ende wie gemeldet; fehlt der angetretenen Schicht eine Voraussetzung der Einsatzfreigabe,
 * ist das Fahrzeug `RELEASE_PENDING` mit den fehlenden Voraussetzungen; eine Freigabe mit entfallener Ressourcendimension (WP-M3-12) ergibt `RESTRICTED`; sonst der Dienststatus der Schicht
 * (WP-M3-31), ohne Schicht und Meldung `NOT_READY` bei gesetztem Sperrkennzeichen und sonst `AVAILABLE`.
 */
@RestController
class OperatorVehicleController(private val fleet: FleetsafetyApi, private val staff: StafftimeApi, private val orders: OperatorOrders, private val codec: CursorCodec) {

    @GetMapping("/v1/operator/vehicles")
    fun list(
        @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, @RequestParam(required = false) state: String?,
        @RequestParam(name = "depot_id", required = false) depotId: String?, @RequestParam(name = "service_id", required = false) serviceId: String?,
        @RequestParam(name = "open_incidents", required = false) openIncidents: String?, request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        if (depotId != null) throw ProblemException.invalid(Violation("/\$query/depot_id", ViolationCode.INVALID_VALUE))
        if (state != null && state !in STATES) throw ProblemException.invalid(Violation("/\$query/state", ViolationCode.NOT_IN_ENUM))
        val service = serviceId?.let { s -> runCatching { UUID.fromString(s) }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$query/service_id", ViolationCode.INVALID_FORMAT)) }
        val incidents = openIncidents?.let { v -> when (v) { "true" -> true; "false" -> false; else -> throw ProblemException.invalid(Violation("/\$query/open_incidents", ViolationCode.TYPE_MISMATCH)) } }
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "operator-vehicles")
        val lim = Paging.limit(limit)
        var after: Pair<Instant, UUID>? = Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id }
        val now = Instant.now()
        // Der Zustandsfilter hängt an Daten aus zwei weiteren Modulen; deshalb wird in Runden von höchstens 100 Fahrzeugen zusammengesetzt und gefiltert, bis die Seite voll ist (höchstens SCAN_BUDGET gelesen).
        val kept = ArrayList<Pair<OperatorVehicleFacts, Map<String, Any?>>>(); var exhausted = false; var scanned = 0; var lastScanned: OperatorVehicleFacts? = null
        while (kept.size <= lim && !exhausted && scanned < SCAN_BUDGET) {
            val round = fleet.operatorVehicles.list(ctx, service, incidents, ROUND, after)
            exhausted = round.size <= ROUND
            val rows = round.take(ROUND)
            if (rows.isEmpty()) break
            val ids = rows.map { it.id }
            val positions = staff.positions.latest(ctx, ids); val statuses = staff.serviceStatus.latestForVehicles(ctx, ids); val runs = orders.forVehicles(ctx, ids)
            for (f in rows) {
                if (kept.size > lim) break
                val body = json(f, positions[f.id], statuses[f.id], runs[f.id])
                lastScanned = f
                if (state == null || body["operational_state"] == state) kept += f to body
            }
            scanned += rows.size; after = rows.last().createdAt to rows.last().id
        }
        val hasMore = kept.size > lim || (!exhausted && kept.size <= lim && lastScanned != null && scanned >= SCAN_BUDGET)
        val shown = kept.take(lim)
        val cursorRow = if (kept.size > lim) shown.last().first else if (hasMore) lastScanned else null
        return mapOf(
            "items" to shown.map { it.second },
            "next_cursor" to cursorRow?.let { codec.encode(CursorPosition(it.createdAt, it.id), scope) },
            "as_of" to now.toString(),
        )
    }

    @GetMapping("/v1/operator/vehicles/{vehicle_id}")
    fun detail(@PathVariable("vehicle_id") vehicleId: String, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val id = runCatching { UUID.fromString(vehicleId) }.getOrNull() ?: throw ProblemException.notFound()
        val f = fleet.operatorVehicles.get(ctx, id)   // nicht sichtbar (fremder Mandant, nicht berechtigter Dienst) = 404
        return json(f, staff.positions.latest(ctx, listOf(id))[id], staff.serviceStatus.latestForVehicles(ctx, listOf(id))[id], orders.forVehicles(ctx, listOf(id))[id])
    }

    private fun json(f: OperatorVehicleFacts, p: LatestPosition?, status: ServiceStatus?, run: OperatorRunSummary?): Map<String, Any?> = linkedMapOf(
        "vehicle_id" to f.id.toString(), "vehicle_label" to f.label, "operational_state" to operationalState(f, status), "safety_revision" to f.safetyRevision,
        "release_valid_until" to f.releaseValidUntil?.toString(),
        "missing_requirements" to f.missing.map { m -> linkedMapOf("code" to m.code.name, "inspection_id" to m.inspectionId?.toString(), "step_codes" to m.stepCodes) },
        "active_blocks" to f.activeBlocks.map { block(it) },
        "position" to p?.let { linkedMapOf("latitude" to it.latitude, "longitude" to it.longitude) }, "position_measured_at" to p?.measuredAt?.toString(),
        "position_received_at" to p?.receivedAt?.toString(), "position_age_seconds" to p?.ageSeconds, "position_stale" to (p?.stale ?: true),
        "open_defect_count" to f.openDefectCount, "current_run_id" to run?.runId?.toString(), "revision" to f.revision,
        "service_ids" to f.serviceIds.map { it.toString() }, "unavailable_dimensions" to f.unavailableDimensions.sorted(), "open_alert_count" to f.openAlertCount,
        "orders" to run?.orders.orEmpty().map { o -> linkedMapOf("booking_id" to o.bookingId.toString(), "booking_no" to o.bookingNo, "state" to o.state, "planned_pickup_at" to o.plannedPickupAt.toString(), "phase" to o.phase.name) },
    )

    private fun block(b: OperatorBlockView): Map<String, Any?> = linkedMapOf(
        "id" to b.id.toString(), "vehicle_id" to b.vehicleId.toString(), "source" to b.source.name, "reason_code" to b.reason.name, "set_at" to b.setAt.toString(), "set_by" to b.setBy,
        "lifted_at" to null, "lifted_by" to null, "revision" to b.revision, "block_kind" to b.kind.name, "case_number" to b.caseNumber, "shift_id" to b.shiftId?.toString(),
    )

    /** Betriebszustand der Leitstellenansicht (siehe Klassenkopf); reine Funktion der Fakten und des Dienststatus. */
    private fun operationalState(f: OperatorVehicleFacts, status: ServiceStatus?): String = when {
        f.activeBlocks.isNotEmpty() -> "BLOCKED"
        status == ServiceStatus.BREAK || status == ServiceStatus.DISRUPTED || status == ServiceStatus.ENDED -> status.name
        f.shiftId != null && f.missing.isNotEmpty() -> "RELEASE_PENDING"
        f.unavailableDimensions.isNotEmpty() && (status == null || status.dispatchable) -> "RESTRICTED"
        status != null -> status.name
        f.dispatchBlocked -> "NOT_READY"
        else -> "AVAILABLE"
    }

    private companion object {
        val STATES = setOf("NOT_READY", "RELEASE_PENDING", "AVAILABLE", "EN_ROUTE", "WAITING", "BREAK", "DISRUPTED", "BLOCKED", "RESTRICTED", "ENDED")
        const val ROUND = 100
        const val SCAN_BUDGET = 1000
    }
}
