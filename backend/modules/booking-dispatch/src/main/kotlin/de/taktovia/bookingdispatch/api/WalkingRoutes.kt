package de.taktovia.bookingdispatch.api

import de.taktovia.bookingdispatch.internal.BookingServicesFactory
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.servicegeo.api.GeoPoint
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Ergebnisart des Fußwegs zum Abholhaltepunkt (WP-M2-44, LH-6.3-PAX-007). */
enum class WalkStatus { ROUTE, FALLBACK, NO_WALK }

/** Strukturierte Wegschritte der Textanleitung (Schema `RideWalkStep`). */
enum class WalkManeuver { START, TURN_LEFT, TURN_RIGHT, ARRIVE }

data class WalkStep(val seq: Int, val maneuver: WalkManeuver, val distanceM: Int, val durationS: Int, val instruction: String)

/**
 * Fußweg-Snapshot eines Angebots (Ablage: `ride_offer_walk`, ein Snapshot je Angebot, danach unveränderlich; die Buchung liest ihn über ihr Angebot). Karte und Textanleitung tragen denselben
 * Snapshot ([id]). Die Geometrie hat nur die Wegpunkte **zwischen öffentlichen Haltepunkten**; die Koordinate eines freien Startpunkts wird nie gespeichert (OP-13), dann steht
 * [status] `FALLBACK` mit [fallbackReason] `NO_ORIGIN_STOP` und nur der auf 10 m aufgerundeten Luftlinie.
 */
data class WalkSnapshot(
    val id: UUID, val offerId: UUID, val status: WalkStatus, val distanceM: Int, val durationS: Int, val profile: String, val mapVersion: String?, val fallbackReason: String?,
    val geometry: List<GeoPoint>, val steps: List<WalkStep>, val pickupStopName: String, val computedAt: Instant,
)

/** Startort des Fahrgasts: Koordinate (nie gespeichert) und optional ein fester Haltepunkt. */
data class WalkOrigin(val point: GeoPoint, val stopId: UUID?)

/** Abholhaltepunkt des Angebots (unveränderliche Revision). */
data class WalkTarget(val stopId: UUID, val revisionId: UUID, val name: String, val point: GeoPoint)

/** Ergebnis der Planung: ein angebotener Weg oder, wenn er `stop_walk_max_m` überschreitet, **nicht angeboten**. */
sealed interface WalkPlan {
    data class Offered(val snapshot: WalkSnapshot) : WalkPlan
    data class TooFar(val distanceM: Int, val maxM: Int) : WalkPlan
}

/**
 * Reine Planung des Fußwegs (ohne Datenbank, Netz und Uhr; vollständig testbar). Regeln:
 *  * Start gleich Haltepunkt (gleicher Haltepunkt oder höchstens [NO_WALK_M] Meter): kein Weg ([WalkStatus.NO_WALK]).
 *  * Routingergebnis vorhanden: Weg mit Geometrie, Distanz und Gehzeit des Routings (Profil `walk`, Standardgehprofil [WALK_SPEED_M_PER_S] nur im Rückfall, Offener Punkt 36); überschreitet die
 *    Distanz `stop_walk_max_m`, wird der Weg nicht angeboten ([WalkPlan.TooFar], Grenzfall: genau `maxM` ist erlaubt, +1 m nicht).
 *  * Kein Routingergebnis: Rückfall mit aufgerundeter Luftlinie (10 m) und Gehzeit nach dem Standardprofil, klar als Rückfall gekennzeichnet; auch hier gilt `maxM`.
 *
 * Formulierungsregeln der Textanleitung: ein Satz je Schritt, Imperativ in der Höflichkeitsform, Distanz in ganzen Metern, Richtung als Himmelsrichtung nur im ersten Schritt, Abbiegen
 * als „nach links/rechts“, letzter Schritt nennt den Haltepunkt; ohne Karte vollständig verständlich.
 */
object WalkPlanner {
    const val NO_WALK_M = 5
    const val WALK_SPEED_M_PER_S = 1.25
    const val TURN_THRESHOLD_DEG = 30.0
    private const val PROFILE = "walk"

    fun plan(
        origin: WalkOrigin, target: WalkTarget, maxM: Int, route: RouteInfo?, fallbackReason: String, offerId: UUID, now: Instant, snapshotId: UUID = UUID.randomUUID(),
    ): WalkPlan {
        val crow = metersBetween(origin.point, target.point)
        fun snap(status: WalkStatus, dist: Int, dur: Int, version: String?, reason: String?, geometry: List<GeoPoint>, steps: List<WalkStep>, profile: String = PROFILE) =
            WalkPlan.Offered(WalkSnapshot(snapshotId, offerId, status, dist, dur, profile, version, reason, geometry, steps, target.name, now))
        if (origin.stopId == target.stopId || crow <= NO_WALK_M)
            return snap(WalkStatus.NO_WALK, 0, 0, null, null, emptyList(), listOf(WalkStep(1, WalkManeuver.ARRIVE, 0, 0, "Sie befinden sich bereits am Treffpunkt „${target.name}“. Ein Fußweg ist nicht nötig.")))
        if (route != null) {
            if (route.distanceMeters > maxM) return WalkPlan.TooFar(route.distanceMeters, maxM)
            val geometry = route.geometry.map { GeoPoint(it.second, it.first) }      // der Routing-Worker liefert (Breite, Länge)
            return snap(WalkStatus.ROUTE, route.distanceMeters, route.timeSeconds, route.mapVersion, null, geometry, steps(geometry, route.distanceMeters, route.timeSeconds, target.name), route.profile)
        }
        val rounded = (ceil(crow / 10.0) * 10).toInt()
        if (rounded > maxM) return WalkPlan.TooFar(rounded, maxM)
        val dur = ceil(rounded / WALK_SPEED_M_PER_S).toInt()
        val text = "Der Fußweg konnte nicht berechnet werden. Der Treffpunkt „${target.name}“ liegt etwa $rounded m Luftlinie entfernt (ungefähr ${minutes(dur)} Minuten zu Fuß)."
        return snap(WalkStatus.FALLBACK, rounded, dur, null, fallbackReason, emptyList(), listOf(WalkStep(1, WalkManeuver.ARRIVE, rounded, dur, text)))
    }

    private fun minutes(seconds: Int) = maxOf(1, ceil(seconds / 60.0).toInt())

    /** Wegschritte aus der Geometrie: Abschnitte mit gleicher Richtung (Abweichung unter [TURN_THRESHOLD_DEG]) werden zusammengefasst; Gehzeit anteilig an der Gesamtzeit. */
    fun steps(geometry: List<GeoPoint>, totalDistanceM: Int, totalDurationS: Int, targetName: String): List<WalkStep> {
        val segs = geometry.zipWithNext().map { (a, b) -> Triple(metersBetween(a, b), bearing(a, b), a) }.filter { it.first >= 1.0 }
        class Group(var dist: Double, val bearing: Double, val turn: Double)
        val groups = mutableListOf<Group>()
        var ref = 0.0
        for ((i, s) in segs.withIndex()) {
            val delta = if (i == 0) 0.0 else normalize(s.second - ref)
            if (i == 0 || abs(delta) >= TURN_THRESHOLD_DEG) { groups += Group(s.first, s.second, delta); ref = s.second } else groups.last().dist += s.first
        }
        val sum = groups.sumOf { it.dist }
        val out = mutableListOf<WalkStep>()
        var usedDur = 0
        groups.forEachIndexed { i, g ->
            val d = g.dist.roundToInt()
            val dur = if (i == groups.lastIndex) maxOf(0, totalDurationS - usedDur) else (totalDurationS * g.dist / sum).roundToInt().also { usedDur += it }
            val (maneuver, text) = if (i == 0) WalkManeuver.START to "Gehen Sie Richtung ${compass(g.bearing)} los und folgen Sie dem Weg $d m."
            else if (g.turn > 0) WalkManeuver.TURN_RIGHT to "Biegen Sie nach rechts ab und gehen Sie $d m."
            else WalkManeuver.TURN_LEFT to "Biegen Sie nach links ab und gehen Sie $d m."
            out += WalkStep(out.size + 1, maneuver, d, dur, text)
        }
        out += WalkStep(out.size + 1, WalkManeuver.ARRIVE, 0, 0, "Sie haben den Treffpunkt „$targetName“ erreicht. Gesamtstrecke $totalDistanceM m.")
        return out
    }

    private fun normalize(deg: Double): Double { var d = deg % 360; if (d > 180) d -= 360; if (d <= -180) d += 360; return d }

    private fun compass(b: Double): String = listOf("Norden", "Nordosten", "Osten", "Südosten", "Süden", "Südwesten", "Westen", "Nordwesten")[(((b + 22.5) % 360) / 45).toInt()]

    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val p1 = Math.toRadians(a.latitude); val p2 = Math.toRadians(b.latitude); val dl = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dl) * cos(p2); val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun metersBetween(a: GeoPoint, b: GeoPoint): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(a.latitude); val p2 = Math.toRadians(b.latitude); val dp = p2 - p1; val dl = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
}

/**
 * Fußweg zum Abholhaltepunkt (WP-M2-44): berechnet über [RoutingPort.walk] (Profil `walk`, nur zwischen Haltepunkten) vom Startort zum Abholhaltepunkt des Angebots, führt das Ergebnis als
 * Snapshot des Angebots und liefert ihn für Angebot und Buchung. Der Routingaufruf geschieht nie in einer offenen SQL-Transaktion. Fällt das Routing aus (oder fehlt der Worker, oder ist der Start
 * kein Haltepunkt), entsteht der gekennzeichnete Rückfall. `stop_walk_max_m` kommt aus der wirksamen Dienstkonfiguration (Planungsdefault 400 m).
 */
interface WalkingRoutes {
    /** Plant den Weg zum Abholhaltepunkt des eigenen Angebots; `null`, wenn das Angebot keinen Abholhaltepunkt trägt. Fremdes Angebot: `404`. */
    fun plan(ctx: RequestContext, offerId: UUID, origin: WalkOrigin): WalkPlan?

    /** Speichert den Snapshot (ein Snapshot je Angebot, der erste gilt, danach unveränderlich) und liefert den geltenden. */
    fun store(ctx: RequestContext, snapshot: WalkSnapshot): WalkSnapshot

    /** Snapshot des eigenen Angebots; `null`, wenn keiner geführt wird oder das Angebot dem Aufrufer nicht gehört. */
    fun forOffer(ctx: RequestContext, offerId: UUID): WalkSnapshot?

    companion object {
        fun create(ctxTx: ContextTransactions, config: EffectiveConfig? = null, routing: RoutingPort? = null, stopKeys: EtaStopKeys? = null, clock: Clock = Clock.systemUTC()): WalkingRoutes =
            BookingServicesFactory.walkingRoutes(ctxTx, config, routing, stopKeys, clock)
    }
}
