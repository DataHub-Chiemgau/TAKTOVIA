package de.taktovia.stafftime.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.retention.RetentionTarget
import de.taktovia.platform.time.Rfc3339
import de.taktovia.stafftime.internal.PositionRetentionTarget
import java.time.Instant
import java.util.UUID

/** Ein Messpunkt der Fahrer-App. [measuredAt] ist die Gerätemesszeit (nie vertrauenswürdige Serverzeit); `speed_mps` und `heading_deg` nimmt der Vertrag an, speichert sie aber nicht (Minimierung, kein Zweck in M3). */
data class PositionSampleInput(val longitude: Double, val latitude: Double, val accuracyMeters: Double, val measuredAt: Instant)

/** Positionsmeldung des eigenen, angetretenen Dienstes (`POST /v1/driver/positions`). Der Mandant steht nie im Body. */
data class PositionBatch(val shiftId: UUID, val vehicleId: UUID, val deviceId: UUID, val samples: List<PositionSampleInput>) {
    companion object {
        const val MAX_SAMPLES = 100

        fun parse(raw: Map<String, Any?>): PositionBatch {
            val v = mutableListOf<Violation>()
            raw.keys.forEach { if (it !in setOf("shift_id", "vehicle_id", "device_id", "samples")) v += Violation("/$it", ViolationCode.UNKNOWN_FIELD) }
            fun uuid(key: String): UUID? {
                val t = raw[key] ?: run { v += Violation("/$key", ViolationCode.REQUIRED); return null }
                return (t as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: run { v += Violation("/$key", ViolationCode.INVALID_FORMAT); null }
            }
            val shift = uuid("shift_id"); val vehicle = uuid("vehicle_id"); val device = uuid("device_id")
            val samples = mutableListOf<PositionSampleInput>()
            val list = raw["samples"]
            if (list == null) v += Violation("/samples", ViolationCode.REQUIRED)
            else if (list !is List<*>) v += Violation("/samples", ViolationCode.TYPE_MISMATCH)
            else {
                if (list.isEmpty()) v += Violation("/samples", ViolationCode.TOO_FEW_ITEMS)
                if (list.size > MAX_SAMPLES) v += Violation("/samples", ViolationCode.TOO_MANY_ITEMS)
                list.take(MAX_SAMPLES).forEachIndexed { i, e ->
                    @Suppress("UNCHECKED_CAST") val m = e as? Map<String, Any?> ?: run { v += Violation("/samples/$i", ViolationCode.TYPE_MISMATCH); return@forEachIndexed }
                    m.keys.forEach { if (it !in setOf("position", "accuracy_meters", "measured_at", "speed_mps", "heading_deg")) v += Violation("/samples/$i/$it", ViolationCode.UNKNOWN_FIELD) }
                    @Suppress("UNCHECKED_CAST") val pos = m["position"] as? Map<String, Any?>
                    val lat = (pos?.get("latitude") as? Number)?.toDouble(); val lon = (pos?.get("longitude") as? Number)?.toDouble()
                    if (lat == null || lat !in -90.0..90.0) v += Violation("/samples/$i/position/latitude", if (lat == null) ViolationCode.REQUIRED else ViolationCode.INVALID_VALUE)
                    if (lon == null || lon !in -180.0..180.0) v += Violation("/samples/$i/position/longitude", if (lon == null) ViolationCode.REQUIRED else ViolationCode.INVALID_VALUE)
                    val acc = (m["accuracy_meters"] as? Number)?.toDouble()
                    if (acc == null || acc < 0 || acc > 100000) v += Violation("/samples/$i/accuracy_meters", if (acc == null) ViolationCode.REQUIRED else ViolationCode.INVALID_VALUE)
                    val at = Rfc3339.parse(m["measured_at"] as? String, "/samples/$i/measured_at")
                    if (!at.ok) v += at.violations
                    if (lat != null && lon != null && acc != null && at.ok) samples += PositionSampleInput(lon, lat, acc, at.value!!.toInstant())
                }
            }
            if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray())
            return PositionBatch(shift!!, vehicle!!, device!!, samples)
        }
    }
}

/** Grund, aus dem ein einzelner Messpunkt nicht gespeichert wurde: Messzeit in der Zukunft (Gerätezeit, mehr als die Toleranz) oder außerhalb des angetretenen Dienstes. */
enum class PositionRejection { FUTURE_TIME, OUTSIDE_SERVICE }

data class RejectedSample(val index: Int, val reason: PositionRejection)

/** [accepted] zählt neu gespeicherte und bereits gespeicherte (erneute Sendung, idempotent) Punkte; [receivedAt] ist die Serverempfangszeit. */
data class PositionResult(val accepted: Int, val rejected: List<RejectedSample>, val receivedAt: Instant)

/**
 * Letzte Position eines Fahrzeugs für die Leitstelle. [ageSeconds] folgt aus der Messzeit und der Serveruhr; [stale] ist wahr ab Überschreiten der Schwelle
 * (`position_stale_after_sec`, Planungsdefault 30 s). Es wird nie extrapoliert: die Position ist immer ein gemessener Punkt.
 */
data class LatestPosition(
    val vehicleId: UUID, val longitude: Double, val latitude: Double, val accuracyMeters: Double, val measuredAt: Instant, val receivedAt: Instant, val ageSeconds: Long, val stale: Boolean,
)

/**
 * Positionserfassung und Standortalter (WP-M3-34, Modul `staff-time`). Angenommen wird nur für die eigene, **angetretene** Schicht und ihr Fahrzeug (fremde Schicht oder fremdes
 * Fahrzeug: 404; nicht angetreten, entzogen: 409, nichts gespeichert) von einem an die Sitzung gebundenen Gerät. Messzeit und Empfangszeit stehen getrennt; die Messzeit liegt im
 * Dienst (`started_at` bis `ended_at`) und höchstens die Toleranz in der Zukunft, sonst wird der Punkt einzeln abgewiesen. Nachgelieferte Punkte werden mit ihrer Messzeit
 * gespeichert; „letzte Position“ ist die jüngste **Messzeit**, eine verspätete ältere Meldung überschreibt sie nie. In Produktion ist die Erfassung nur mit einer freigegebenen
 * Aufbewahrungsregel für `LK_STANDORT` aktiv (409 sonst). Rohpositionen löscht der Löschlauf über [PositionRetention].
 */
interface Positions {
    fun record(ctx: RequestContext, batch: PositionBatch): PositionResult

    /** Letzte Position je Fahrzeug (nur Fahrzeuge mit mindestens einer Position), für die Leitstelle (`operator:read`). */
    fun latest(ctx: RequestContext, vehicleIds: Collection<UUID>): Map<UUID, LatestPosition>
}

/** Löschziel der Rohpositionen (`LK_STANDORT`, nur Aktion DELETE); die Frist liefert die freigegebene Regel (D-11), nie ein Standardwert im Code. */
object PositionRetention {
    fun target(): RetentionTarget = PositionRetentionTarget()
}
