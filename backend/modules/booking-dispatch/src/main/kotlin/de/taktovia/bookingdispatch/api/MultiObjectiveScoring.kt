package de.taktovia.bookingdispatch.api

import java.time.Duration
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Gewichte der Mehrzielbewertung (WP-M2-20, LH-6.5-OPT-008) mit der Revision der Konfiguration, aus der sie stammen ([revisionId], `config_revision`). Die Parameter heißen
 * `opt_weight_wait`, `opt_weight_detour`, `opt_weight_empty`, `opt_weight_quality` (Migration V20200; Konfigurationsregister lehnt Werte außerhalb 0..100 ab). Hier eine zweite Sicherung
 * sowie die Querprüfung, die das Einzelschema nicht kennt: mindestens ein Gewicht größer 0. Vorläufige Festlegung (OP-18), Produktionswerte nach D-05.
 */
data class ScoringWeights(val wait: Int, val detour: Int, val empty: Int, val quality: Int, val revisionId: String?) {
    init {
        require(wait in RANGE && detour in RANGE && empty in RANGE && quality in RANGE) { "Gewicht außerhalb 0..100" }
        require(wait + detour + empty + quality > 0) { "Mindestens ein Gewicht muss größer 0 sein" }
    }

    val sum: Int get() = wait + detour + empty + quality

    companion object {
        val RANGE = 0..100
        val PARAMETERS = listOf("opt_weight_wait", "opt_weight_detour", "opt_weight_empty", "opt_weight_quality")

        /** Planungsstand-Standardwerte (Schema-Default der vier Parameter). */
        fun defaults(revisionId: String? = null) = ScoringWeights(40, 20, 25, 15, revisionId)

        /** Liest die vier Parameter aus den wirksamen Werten einer Konfigurationsrevision; fehlende Werte nehmen den Standard. */
        fun fromValues(values: Map<String, Any?>, revisionId: String?): ScoringWeights {
            fun w(name: String, default: Int): Int = when (val v = values[name]) {
                null -> default
                is Int -> v
                is Long -> Math.toIntExact(v)
                is Number -> v.toInt().also { require(it.toDouble() == v.toDouble()) { "$name ist keine ganze Zahl" } }
                else -> throw IllegalArgumentException("$name ist keine Zahl")
            }
            return ScoringWeights(w(PARAMETERS[0], 40), w(PARAMETERS[1], 20), w(PARAMETERS[2], 25), w(PARAMETERS[3], 15), revisionId)
        }
    }
}

/**
 * Rohe Teilwerte einer Variante und ihre auf 0..1 normierten Nachteile (0 = bestmöglich, 1 = Grenze erreicht). Messdefinitionen (vorläufig, OP-18):
 *  - Wartezeit = Abholzeit minus Bezugspunkt, normiert auf `max_wait_min` der Zusage;
 *  - Umweg = (Bordzeit minus Direktfahrzeit) / Direktfahrzeit der neuen Buchung, normiert auf `max_detour_pct`;
 *  - Leerstrecke = Anfahrt in den Einstiegsstopp, wenn das Fahrzeug dort leer ist (sonst 0), normiert als Anteil `leer / (leer + Direktfahrzeit)`;
 *  - Betriebsqualität = geringste Zeitreserve aller Zusagen des Umlaufs (Abholung bis spätester Rand, Ankunft bis Rand plus Karenz) gegenüber [QUALITY_REFERENCE_SEC]; wenig Reserve ist schlecht.
 */
data class ScoreParts(
    val waitSec: Long, val detourPct: Double, val emptySec: Int, val slackSec: Long,
    val waitPenalty: Double, val detourPenalty: Double, val emptyPenalty: Double, val qualityPenalty: Double,
)

/** Bewertete, zulässige Variante: [score] liegt in (0,5; 1], je höher desto besser; [rank] ist 1-basiert. */
data class ScoredVariant(val variant: InsertionVariant, val score: Double, val parts: ScoreParts, val weights: ScoringWeights, val rank: Int)

/** Ausgeschlossene Variante: unzulässig, Bewertung 0 (kein Gewicht kann das ändern). */
data class ExcludedVariant(val variant: InsertionVariant, val score: Double, val reasons: List<String>)

/** Ergebnis: zulässige Varianten in Rangfolge und die verwendete Gewichtsrevision, dazu die Ausgeschlossenen (Diagnose). */
data class RankedVariants(val ranked: List<ScoredVariant>, val excluded: List<ExcludedVariant>, val weights: ScoringWeights)

/**
 * Mehrzielbewertung (Kap. 01 §1.4 Schritt 3): zuerst Zulässigkeit (Routeninvarianten, zugesagte Fenster, harte Grenzen; unzulässig = Bewertung 0, ausgeschlossen, unabhängig von
 * jedem Gewicht), dann gewichtete weiche Ziele; bei Gleichstand entscheiden Mehrfahrzeit (Leerweg/Fahrzeit), Fahrzeug-ID, Einfügepositionen. Reine, deterministische Funktion.
 */
object MultiObjectiveScoring {
    const val QUALITY_REFERENCE_SEC = 900L
    private const val SCALE = 1_000_000_000.0

    fun rank(variants: List<InsertionVariant>, vehicles: List<InsertionVehicle>, weights: ScoringWeights): RankedVariants {
        val byId = vehicles.associateBy { it.vehicleId }
        val scored = mutableListOf<Pair<InsertionVariant, Pair<Double, ScoreParts>>>()
        val excluded = mutableListOf<ExcludedVariant>()
        for (v in variants) {
            val vehicle = byId[v.vehicleId]
            val reasons = if (vehicle == null) listOf("UNKNOWN_VEHICLE") else feasibilityViolations(v, vehicle)
            if (reasons.isNotEmpty()) { excluded += ExcludedVariant(v, 0.0, reasons); continue }
            val parts = parts(v, vehicle!!)
            scored += v to (score(parts, weights) to parts)
        }
        val ordered = scored.sortedWith(
            compareByDescending<Pair<InsertionVariant, Pair<Double, ScoreParts>>> { it.second.first }
                .thenBy { it.first.addedDriveSec }.thenBy { it.first.vehicleId.toString() }.thenBy { it.first.posPickup }.thenBy { it.first.posDropoff },
        )
        return RankedVariants(ordered.mapIndexed { i, (v, sp) -> ScoredVariant(v, sp.first, sp.second, weights, i + 1) }, excluded, weights)
    }

    private fun feasibilityViolations(v: InsertionVariant, vehicle: InsertionVehicle): List<String> = buildList {
        RunPlanValidator.validate(vehicle.capacity, v.stops).forEach { add("PLAN_" + it.code.name) }
        PromiseWindowGuard.check(v.stops, vehicle.protectedWindows + v.window).forEach { add(it.code.name) }
    }.distinct()

    internal fun parts(v: InsertionVariant, vehicle: InsertionVehicle): ScoreParts {
        val ride = PromiseWindowGuard.rides(v.stops).getValue(v.window.bookingId)
        val w = v.window
        val waitSec = Duration.between(w.waitReference, ride.pickupAt).seconds.coerceAtLeast(0)
        val maxWait = w.limits.maxWaitMin * 60L
        val rideSec = Duration.between(ride.pickupAt.plusSeconds(w.pickupDwellSec.toLong()), ride.dropoffAt).seconds
        val detour = max(0.0, (rideSec - w.directSec).toDouble() / w.directSec)
        val maxDetour = w.limits.maxDetourPct / 100.0
        val occupiedBefore = v.stops.take(v.posPickup).sumOf { s -> s.boardings.sumOf { it.vector.seats + it.vector.wheelchair } - s.alightings.sumOf { it.vector.seats + it.vector.wheelchair } }
        val empty = if (occupiedBefore == 0) v.leadInSec else 0
        val rides = PromiseWindowGuard.rides(v.stops)
        val slack = (vehicle.protectedWindows + w).mapNotNull { pw -> rides[pw.bookingId]?.let { r ->
            min(Duration.between(r.pickupAt, pw.pickupLatest).seconds, Duration.between(r.dropoffAt, pw.arrivalLatestWithGrace).seconds)
        } }.minOrNull()?.coerceAtLeast(0) ?: QUALITY_REFERENCE_SEC
        return ScoreParts(
            waitSec, detour, empty, slack,
            waitPenalty = if (maxWait == 0L) (if (waitSec > 0) 1.0 else 0.0) else min(1.0, waitSec.toDouble() / maxWait),
            detourPenalty = if (maxDetour == 0.0) (if (detour > 0) 1.0 else 0.0) else min(1.0, detour / maxDetour),
            emptyPenalty = empty.toDouble() / (empty + w.directSec),
            qualityPenalty = 1.0 - min(slack, QUALITY_REFERENCE_SEC).toDouble() / QUALITY_REFERENCE_SEC,
        )
    }

    /** Bewertung `1 / (1 + gewichtete Nachteile)`; echt größer 0 für zulässige Varianten (Unzulässigkeit allein ist 0), gerundet auf 9 Stellen für Gleichstandsprüfung. */
    internal fun score(p: ScoreParts, wt: ScoringWeights): Double {
        val cost = (wt.wait * p.waitPenalty + wt.detour * p.detourPenalty + wt.empty * p.emptyPenalty + wt.quality * p.qualityPenalty) / wt.sum
        return Math.round(1.0 / (1.0 + cost) * SCALE) / SCALE
    }
}

/** Quelle der wirksamen Gewichte zum Beginn einer Angebotsberechnung (später: wirksame Konfiguration des Dienstes, WP-M2-21). */
fun interface ScoringWeightsSource {
    fun current(ctx: de.taktovia.platform.context.RequestContext): ScoringWeights
}
