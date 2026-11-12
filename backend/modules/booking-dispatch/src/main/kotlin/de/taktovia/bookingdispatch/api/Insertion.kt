package de.taktovia.bookingdispatch.api

import de.taktovia.platform.context.RequestContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Haltepunkt im Plan: unveränderliche Revision (`stop_revision`) und der Routing-Schlüssel (Haltepunktkatalog des Workers, WP-M2-10). */
data class StopRef(val stopRevisionId: UUID, val routingKey: String)

/**
 * Ein Kandidatenfahrzeug mit dem Plan der Revision [planRevision], auf der er gelesen wurde. [runId] `null`: noch kein Umlauf (neuer Umlauf, Revision 0). [stopKeys] bildet die
 * `stop_revision_id` der Planstopps auf Routing-Schlüssel ab; [protectedWindows] sind die Zusagefenster aller bestätigten und aktiv gehaltenen Buchungen des Umlaufs (WP-M2-19).
 */
data class InsertionVehicle(
    val vehicleId: UUID, val runId: UUID?, val planRevision: Long, val capacity: ResourceVector, val start: StopRef, val availableFrom: Instant, val availableUntil: Instant,
    val stops: List<PlanStop> = emptyList(), val stopKeys: Map<UUID, String> = emptyMap(), val protectedWindows: List<PromiseWindow> = emptyList(),
)

/** Bedarf einer neuen Buchung: Haltepunkte, Fenster und Ressourcenvektor. [waitReference] ist der Bezugspunkt der Wartezeit (Standard: früheste Abholzeit, OP-17). */
data class InsertionDemand(
    val bookingId: UUID, val pickup: StopRef, val dropoff: StopRef, val vector: ResourceVector,
    val pickupEarliest: Instant, val pickupLatest: Instant, val arrivalEarliest: Instant, val arrivalLatest: Instant, val waitReference: Instant? = null,
)

/** Parameter des Dienstes zum Angebotszeitpunkt (Konfigurationsrevision in [limits]). */
data class InsertionParams(val poolingAllowed: Boolean, val stopDwellSec: Int, val wheelchairBoardingSec: Int, val limits: HardLimits)

data class InsertionQuery(
    val demand: InsertionDemand, val vehicles: List<InsertionVehicle>, val params: InsertionParams, val profile: String = "car", val budget: Duration = Duration.ofMillis(1000),
)

/**
 * Vollständig validierte Variante: neue Stoppfolge für den Umlauf, gerechnet gegen [planRevision] (Hold und Bestätigung weisen eine inzwischen überholte Revision als
 * Revisionskonflikt ab, WP-M2-22). [window] ist das Zusagefenster der neuen Buchung mit den Grenzen der Konfigurationsrevision.
 */
data class InsertionVariant(
    val vehicleId: UUID, val runId: UUID?, val planRevision: Long, val newRun: Boolean, val stops: List<PlanStop>, val posPickup: Int, val posDropoff: Int,
    val addedDriveSec: Int, val window: PromiseWindow,
    /** Fahrzeit des Abschnitts in den Einstiegsstopp hinein (vom Vorgängerstopp bzw. Fahrzeugstart); Grundlage der Leerstrecke (WP-M2-20). */
    val leadInSec: Int = 0,
)

sealed interface InsertionOutcome {
    /** [partial]: Zeitbudget überschritten, nur bereits validierte Varianten; [droppedByCore]: Varianten des Workers, die die Prüfung des Kerns nicht bestanden haben. */
    data class Offered(val variants: List<InsertionVariant>, val partial: Boolean, val droppedByCore: Int) : InsertionOutcome

    /** Begründete Ablehnung ([reason]: `NO_FEASIBLE_POSITION`, `TIMEOUT_NO_VALIDATED_PLAN`, `NO_CANDIDATE_VEHICLE`, `NO_VALID_VARIANT`); [grounds] zählt die Gründe der Positionen. */
    data class Rejected(val reason: String, val grounds: Map<String, Int>) : InsertionOutcome
}

sealed interface RankedResult {
    data class Ranked(val ranking: RankedVariants, val outcome: InsertionOutcome.Offered) : RankedResult
    data class NoOffer(val rejected: InsertionOutcome.Rejected, val weights: ScoringWeights) : RankedResult
}

/** Aufruf des Routing-Workers (`POST /v1/insert`); technische Fehler als [RoutingException]. Ersetzbar für Tests. */
fun interface InsertionWorker {
    fun insert(requestJson: String, budget: Duration): String
}

/**
 * Einfügeheuristik im Kern (WP-M2-17 bis -19): baut den Eingang für den Worker (Fahrzeitmatrix über den [RoutingPort], Pläne, Zusagen, Parameter), ruft ihn mit hartem Zeitbudget
 * und prüft jede zurückgegebene Variante erneut im Kern: Routeninvarianten ([RunPlanValidator]), zugesagte Fenster und harte Grenzen aller Buchungen ([PromiseWindowGuard]),
 * Fahrzeug und Planrevision. Eine Variante, die diese Prüfung nicht besteht, wird verworfen; nie ein ungeprüfter Plan. Der Worker schreibt nichts und kennt keine Datenbank.
 */
class InsertionService(private val routing: RoutingPort, private val worker: InsertionWorker) {
    private val json = JsonMapper.builder().build()

    fun find(ctx: RequestContext, q: InsertionQuery): InsertionOutcome {
        val d = q.demand
        require(q.vehicles.isNotEmpty()) { "Keine Kandidatenfahrzeuge" }
        fun keyOf(v: InsertionVehicle, id: UUID) = v.stopKeys[id] ?: throw IllegalArgumentException("Routing-Schlüssel zu stop_revision $id fehlt")
        val keys = LinkedHashSet<String>().apply {
            add(d.pickup.routingKey); add(d.dropoff.routingKey)
            q.vehicles.forEach { v -> add(v.start.routingKey); v.stops.forEach { add(keyOf(v, it.stopRevisionId)) } }
        }.toList()
        val index = keys.withIndex().associate { it.value to it.index }
        val matrix = routing.matrix(ctx, keys, keys, q.profile)
        val direct = matrix.cells[index.getValue(d.pickup.routingKey)][index.getValue(d.dropoff.routingKey)]
            ?: return InsertionOutcome.Rejected("NO_FEASIBLE_POSITION", mapOf("NO_ROUTE" to 1))

        val body = json.writeValueAsString(linkedMapOf(
            "tenant_id" to ctx.tenant.toString(), "budget_ms" to q.budget.toMillis(),
            "candidate_vehicle_ids" to q.vehicles.map { it.vehicleId.toString() },
            "request" to linkedMapOf(
                "booking_id" to d.bookingId.toString(), "pickup_node" to index.getValue(d.pickup.routingKey), "dropoff_node" to index.getValue(d.dropoff.routingKey),
                "pickup_earliest" to d.pickupEarliest.epochSecond, "pickup_latest" to d.pickupLatest.epochSecond,
                "arrival_earliest" to d.arrivalEarliest.epochSecond, "arrival_latest" to d.arrivalLatest.epochSecond,
                "seats" to d.vector.seats, "wheelchair" to d.vector.wheelchair, "equipment" to d.vector.equipment.map { it.name }.sorted(),
                "wait_reference" to d.waitReference?.epochSecond,
            ),
            "params" to linkedMapOf(
                "pooling_allowed" to q.params.poolingAllowed, "stop_dwell_s" to q.params.stopDwellSec, "wheelchair_boarding_s" to q.params.wheelchairBoardingSec,
                "max_wait_s" to q.params.limits.maxWaitMin * 60, "max_detour_pct" to q.params.limits.maxDetourPct, "arrival_grace_s" to q.params.limits.arrivalGraceMin * 60,
                "config_revision_id" to q.params.limits.configRevisionId,
            ),
            "vehicles" to q.vehicles.map { v ->
                linkedMapOf(
                    "vehicle_id" to v.vehicleId.toString(), "tenant_id" to ctx.tenant.toString(), "run_id" to v.runId?.toString(), "plan_revision" to v.planRevision,
                    "capacity" to linkedMapOf("seats" to v.capacity.seats, "wheelchair" to v.capacity.wheelchair, "equipment" to v.capacity.equipment.map { it.name }.sorted()),
                    "start_node" to index.getValue(v.start.routingKey), "available_from" to v.availableFrom.epochSecond, "available_until" to v.availableUntil.epochSecond,
                    "stops" to v.stops.map { s ->
                        linkedMapOf(
                            "node" to index.getValue(keyOf(v, s.stopRevisionId)), "planned_at" to s.plannedAt.epochSecond,
                            "boardings" to s.boardings.map { m -> movement(m) }, "alightings" to s.alightings.map { m -> movement(m) },
                        )
                    },
                    "commitments" to v.protectedWindows.map { w ->
                        linkedMapOf(
                            "booking_id" to w.bookingId.toString(), "pickup_earliest" to w.pickupEarliest.epochSecond, "pickup_latest" to w.pickupLatest.epochSecond,
                            "arrival_earliest" to w.arrivalEarliest.epochSecond, "arrival_latest" to w.arrivalLatest.epochSecond, "arrival_grace_s" to w.limits.arrivalGraceMin * 60,
                            "max_wait_s" to w.limits.maxWaitMin * 60, "wait_reference" to w.waitReference.epochSecond, "max_detour_pct" to w.limits.maxDetourPct, "direct_s" to w.directSec,
                        )
                    },
                )
            },
            "matrix" to matrix.cells.map { row -> row.map { it?.timeSeconds } },
        ))
        val reply = json.readTree(worker.insert(body, q.budget))
        val rejection = reply.get("rejection")?.takeIf { !it.isNull }?.asString()
        val grounds = reply.get("grounds")?.propertyNames()?.associateWith { reply.get("grounds").get(it).asInt() } ?: emptyMap()
        val partial = reply.get("partial")?.asBoolean() ?: false

        val dwell = q.params.stopDwellSec + if (d.vector.wheelchair > 0) q.params.wheelchairBoardingSec else 0
        val limits = q.params.limits
        val window = PromiseWindow(d.bookingId, d.pickupEarliest, d.pickupLatest, d.arrivalEarliest, d.arrivalLatest, limits, direct.timeSeconds.coerceAtLeast(1), dwell, d.waitReference ?: d.pickupEarliest)
        var dropped = 0
        val variants = (0 until (reply.get("variants")?.size() ?: 0)).mapNotNull { k ->
            val v = reply.get("variants").get(k)
            toVariant(q, v, matrix, keys, window) ?: run { dropped++; null }
        }
        if (variants.isEmpty()) return InsertionOutcome.Rejected(rejection ?: "NO_VALID_VARIANT", grounds)
        return InsertionOutcome.Offered(variants, partial, dropped)
    }

    /**
     * Wie [find], bewertet die Varianten zusätzlich nach der Mehrzielbewertung (WP-M2-20). Die Gewichte werden genau einmal zu Beginn gelesen (Schnappschuss mit Revision); eine
     * parallele Änderung wirkt erst auf spätere Berechnungen, die Rangfolge entspricht der genannten Revision.
     */
    fun findRanked(ctx: RequestContext, q: InsertionQuery, weights: ScoringWeightsSource): RankedResult {
        val w = weights.current(ctx)
        return when (val o = find(ctx, q)) {
            is InsertionOutcome.Offered -> RankedResult.Ranked(MultiObjectiveScoring.rank(o.variants, q.vehicles, w), o)
            is InsertionOutcome.Rejected -> RankedResult.NoOffer(o, w)
        }
    }

    private fun movement(m: StopMovement) = linkedMapOf("booking_id" to m.bookingId.toString(), "seats" to m.vector.seats, "wheelchair" to m.vector.wheelchair)

    private fun toVariant(q: InsertionQuery, v: JsonNode, matrix: MatrixInfo, keys: List<String>, window: PromiseWindow): InsertionVariant? {
        val vehicle = q.vehicles.firstOrNull { it.vehicleId.toString() == v.get("vehicle_id").asString() } ?: return null
        if (v.get("plan_revision").asLong() != vehicle.planRevision) return null
        val posP = v.get("pos_pickup").asInt(); val posD = v.get("pos_dropoff").asInt()
        val raw = v.get("stops")
        if (raw.size() != vehicle.stops.size + 2 || posP !in 0 until posD || posD >= raw.size()) return null
        val d = q.demand
        val oldIt = vehicle.stops.iterator()
        val nodes = (0 until raw.size()).map { raw.get(it).get("node").asInt() }
        val stops = (0 until raw.size()).map { k ->
            val at = Instant.ofEpochSecond(raw.get(k).get("planned_at").asLong())
            val nxt = if (k + 1 < raw.size()) matrix.cells[nodes[k]][nodes[k + 1]] else null
            when (k) {
                posP -> PlanStop(k, d.pickup.stopRevisionId, at, listOf(StopMovement(d.bookingId, d.vector)), emptyList(), nxt?.timeSeconds, nxt?.distanceMeters)
                posD -> PlanStop(k, d.dropoff.stopRevisionId, at, emptyList(), listOf(StopMovement(d.bookingId, d.vector)), nxt?.timeSeconds, nxt?.distanceMeters)
                else -> oldIt.next().copy(seq = k, plannedAt = at, legDurationSec = nxt?.timeSeconds, legDistanceM = nxt?.distanceMeters)
            }
        }
        if (nodes[posP] != keys.indexOf(d.pickup.routingKey) || nodes[posD] != keys.indexOf(d.dropoff.routingKey)) return null
        // Prüfung im Kern: Routeninvarianten, zugesagte Fenster und harte Grenzen aller Buchungen, der neuen eingeschlossen
        if (RunPlanValidator.validate(vehicle.capacity, stops).isNotEmpty()) return null
        if (PromiseWindowGuard.check(stops, vehicle.protectedWindows + window).isNotEmpty()) return null
        val leadIn = if (posP == 0) keys.indexOf(vehicle.start.routingKey).let { s0 -> matrix.cells[s0][nodes[0]]?.timeSeconds } else matrix.cells[nodes[posP - 1]][nodes[posP]]?.timeSeconds
        return InsertionVariant(vehicle.vehicleId, vehicle.runId, vehicle.planRevision, vehicle.runId == null, stops, posP, posD, v.get("added_drive_s").asInt(), window, leadIn ?: 0)
    }
}
