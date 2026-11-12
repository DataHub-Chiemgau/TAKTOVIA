package de.taktovia.bookingdispatch.api

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * Harte Grenzen eines Dienstes aus der zum Angebotszeitpunkt wirksamen Konfigurationsrevision (WP-M2-18, LH-6.5-OPT-005; Katalog `kataloge.csv` J.1). Das Konfigurationsregister
 * (WP-M1-19) lehnt Werte außerhalb des Katalogs ab (`max_wait_min` 0..240, `max_detour_pct` 0..200); die Prüfung hier ist eine zweite Sicherung, kein Ersatz.
 * Das Angebot und die bestätigte Buchung behalten ihre [configRevisionId]; eine spätere Änderung wirkt nur auf neue Angebote.
 */
data class HardLimits(val maxWaitMin: Int, val maxDetourPct: Int, val arrivalGraceMin: Int, val configRevisionId: String? = null) {
    init {
        require(maxWaitMin in 0..240) { "max_wait_min außerhalb 0..240" }
        require(maxDetourPct in 0..200) { "max_detour_pct außerhalb 0..200" }
        require(arrivalGraceMin in 0..240) { "arrival_grace_min außerhalb 0..240" }
    }
}

/**
 * Zusagefenster einer bestätigten oder gehaltenen Buchung (WP-M2-19, LH-6.5-OPT-006) samt den Grenzen ihrer Konfigurationsrevision. Unveränderlich: wird beim Hold gespeichert
 * (`capacity_hold.promise_window`, ein Trigger lässt keine Änderung zu) und stimmt beim Bestätigen mit der Zusage (`booking.promise_snapshot`) überein; eine spätere Änderung von
 * `pickup_window_min` oder `arrival_grace_min` verändert es nie. Die Karenz gilt für die Ankunft: spätestens [arrivalLatest] plus `arrivalGraceMin`.
 *
 * Messdefinitionen (Planungsannahme, offen OP-17): Wartezeit = Abholzeit minus [waitReference] (Standard: frühester Abholzeitpunkt); Umweg = (Fahrzeit an Bord minus [directSec]) /
 * [directSec], an Bord vom Ende der Bedienung am Einstieg ([pickupDwellSec]) bis zum Beginn der Bedienung am Ausstieg; Verspätung gegenüber dem späten Rand des Ankunftsfensters.
 */
data class PromiseWindow(
    val bookingId: UUID, val pickupEarliest: Instant, val pickupLatest: Instant, val arrivalEarliest: Instant, val arrivalLatest: Instant,
    val limits: HardLimits, val directSec: Int, val pickupDwellSec: Int = 0, val waitReference: Instant = pickupEarliest,
) {
    init {
        require(!pickupLatest.isBefore(pickupEarliest) && !arrivalLatest.isBefore(arrivalEarliest)) { "Fenster: spätester Rand vor frühestem" }
        require(directSec > 0) { "Direktfahrzeit muss positiv sein" }
    }

    val arrivalLatestWithGrace: Instant get() = arrivalLatest.plusSeconds(limits.arrivalGraceMin * 60L)

    fun toJson(): String = MAPPER.writeValueAsString(linkedMapOf(
        "booking_id" to bookingId.toString(), "pickup_earliest" to pickupEarliest.toString(), "pickup_latest" to pickupLatest.toString(),
        "arrival_earliest" to arrivalEarliest.toString(), "arrival_latest" to arrivalLatest.toString(), "wait_reference" to waitReference.toString(),
        "max_wait_min" to limits.maxWaitMin, "max_detour_pct" to limits.maxDetourPct, "arrival_grace_min" to limits.arrivalGraceMin,
        "config_revision_id" to limits.configRevisionId, "direct_sec" to directSec, "pickup_dwell_sec" to pickupDwellSec,
    ))

    companion object {
        private val MAPPER: JsonMapper = JsonMapper.builder().build()

        fun parse(json: String): PromiseWindow = parse(MAPPER.readTree(json))

        fun parse(n: JsonNode): PromiseWindow {
            fun s(k: String) = n.get(k).asString()
            fun i(k: String) = n.get(k).asInt()
            return PromiseWindow(
                UUID.fromString(s("booking_id")), Instant.parse(s("pickup_earliest")), Instant.parse(s("pickup_latest")), Instant.parse(s("arrival_earliest")), Instant.parse(s("arrival_latest")),
                HardLimits(i("max_wait_min"), i("max_detour_pct"), i("arrival_grace_min"), n.get("config_revision_id")?.takeIf { !it.isNull }?.asString()),
                i("direct_sec"), i("pickup_dwell_sec"), Instant.parse(s("wait_reference")),
            )
        }
    }
}

enum class WindowViolationCode {
    /** Eine zugesagte Buchung steht nicht mehr im Plan (Einstieg oder Ausstieg fehlt). */
    NOT_IN_PLAN,
    PICKUP_OUTSIDE_WINDOW, ARRIVAL_OUTSIDE_WINDOW,
    /** Wartezeit über `max_wait_min`. */
    MAX_WAIT_EXCEEDED,
    /** Umweg über `max_detour_pct`. */
    MAX_DETOUR_EXCEEDED,
    /** Ankunft nach Zusage plus `arrival_grace_min` (Verspätung). */
    ARRIVAL_AFTER_GRACE,
}

data class WindowViolation(val bookingId: UUID, val code: WindowViolationCode)

/** Geplante Abhol- und Ankunftszeit einer Buchung in einer Planrevision (Beginn der Bedienung am Einstiegs- und am Ausstiegsstopp). */
data class PlannedRide(val bookingId: UUID, val pickupAt: Instant, val dropoffAt: Instant)

/**
 * Schutz zugesagter Zeitfenster (WP-M2-19) und harte Grenzen (WP-M2-18) als reine Prüfung über einen Plan: für jede geschützte Buchung liegen Abholung im Abholfenster, Ankunft im
 * Ankunftsfenster plus Karenz, Wartezeit und Umweg innerhalb der Grenzen. Gemeinsam benutzt von der Ergebnisprüfung des Kerns (Varianten des Workers), vom Hold, vom Bestätigen und
 * vom automatischen Prüfer über alle bestätigten Fahrten.
 */
object PromiseWindowGuard {
    /** Geplante Fahrten aus der Stoppfolge (Einstieg/Ausstieg je Buchung); Buchungen ohne beide Stopps fehlen. */
    fun rides(stops: List<PlanStop>): Map<UUID, PlannedRide> {
        val pick = mutableMapOf<UUID, Instant>(); val drop = mutableMapOf<UUID, Instant>()
        stops.forEach { s -> s.boardings.forEach { pick.putIfAbsent(it.bookingId, s.plannedAt) }; s.alightings.forEach { drop.putIfAbsent(it.bookingId, s.plannedAt) } }
        return pick.keys.intersect(drop.keys).associateWith { PlannedRide(it, pick.getValue(it), drop.getValue(it)) }
    }

    fun check(w: PromiseWindow, ride: PlannedRide): List<WindowViolationCode> = buildList {
        if (ride.pickupAt.isBefore(w.pickupEarliest) || ride.pickupAt.isAfter(w.pickupLatest)) add(WindowViolationCode.PICKUP_OUTSIDE_WINDOW)
        if (ride.dropoffAt.isBefore(w.arrivalEarliest)) add(WindowViolationCode.ARRIVAL_OUTSIDE_WINDOW)
        if (ride.dropoffAt.isAfter(w.arrivalLatestWithGrace)) add(WindowViolationCode.ARRIVAL_AFTER_GRACE)
        if (java.time.Duration.between(w.waitReference, ride.pickupAt).seconds > w.limits.maxWaitMin * 60L) add(WindowViolationCode.MAX_WAIT_EXCEEDED)
        val rideSec = java.time.Duration.between(ride.pickupAt.plusSeconds(w.pickupDwellSec.toLong()), ride.dropoffAt).seconds
        if ((rideSec - w.directSec) * 100 > w.limits.maxDetourPct.toLong() * w.directSec) add(WindowViolationCode.MAX_DETOUR_EXCEEDED)
    }

    /** Prüft alle [windows] gegen die Stoppfolge [stops]; leer = alle zugesagten Fenster und Grenzen eingehalten. */
    fun check(stops: List<PlanStop>, windows: Collection<PromiseWindow>): List<WindowViolation> {
        val rides = rides(stops)
        return windows.flatMap { w ->
            val ride = rides[w.bookingId] ?: return@flatMap listOf(WindowViolation(w.bookingId, WindowViolationCode.NOT_IN_PLAN))
            check(w, ride).map { WindowViolation(w.bookingId, it) }
        }
    }
}

data class RideAuditFinding(val runId: UUID, val runRevision: Long, val bookingId: UUID, val code: WindowViolationCode)

/** [checkedRides]: geprüfte Paare (Buchung, Planrevision); [revisions]: durchsuchte Revisionen des Umlaufs. */
data class RideAuditReport(val runId: UUID, val bookings: Int, val checkedRides: Int, val revisions: Int, val findings: List<RideAuditFinding>) {
    val ok: Boolean get() = findings.isEmpty() && checkedRides > 0
}

/**
 * Automatischer Prüfer (WP-M2-18 Kriterium 1, WP-M2-19 Kriterium 4): liest die gespeicherten Zusagefenster aller bestätigten (`CONSUMED`) und gehaltenen Buchungen eines Umlaufs und
 * prüft sie gegen **jede** gespeicherte Planrevision, in der die Buchung steht (Abholfenster, Ankunftsfenster plus Karenz, Wartezeit, Umweg). Unabhängig vom Einfügen: liest nur
 * Datenbankzustand ([PromiseWindowGuard] ist die einzige gemeinsame Rechenregel).
 */
interface ConfirmedRideAudit {
    fun auditRun(ctx: de.taktovia.platform.context.RequestContext, runId: UUID): RideAuditReport
}
