package de.taktovia.fleetsafety.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.geo.GeoPoint
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/** Zugesagte Fahrt (Buchung mit verbrauchtem Hold), deren Abholung nach dem Ende einer Dienstzuordnung liegt: bleibt sichtbar, Hinweis an die Leitstelle. */
data class CommittedRide(val bookingId: UUID, val state: String, val pickupEarliest: Instant, val pickupLatest: Instant)

/**
 * Zuordnung eines Fahrzeugs zu einem Dienst mit Intervall `[validFrom, validTo)` (`validTo == null`: unbegrenzt) und optionalem Bediengebiet ([serviceAreaId], eine Revision der
 * Gebietskette; wirksam ist die zum Prüfzeitpunkt gültige Revision, Planungsannahme PA-02/OP-25). [committedRidesAfterEnd] ist nur bei beendeten Zuordnungen gefüllt.
 */
data class PoolAssignmentView(
    val id: UUID, val vehicleId: UUID, val serviceId: UUID, val serviceAreaId: UUID?, val validFrom: Instant, val validTo: Instant?, val revision: Long, val createdAt: Instant,
    val committedRidesAfterEnd: List<CommittedRide> = emptyList(),
)

data class AssignPoolService(val vehicleId: UUID, val serviceId: UUID, val serviceAreaId: UUID?, val validFrom: Instant, val validTo: Instant? = null)

/** Ergebnis der Poolprüfung für Fahrzeug, Dienst und Zeitpunkt. Zeitliche und geografische Einschränkung wirken unabhängig voneinander. */
enum class PoolVerdict {
    ELIGIBLE,
    /** Keine zum Zeitpunkt gültige Zuordnung des Fahrzeugs zu diesem Dienst (nie zugeordnet, noch nicht begonnen, abgelaufen). */
    NOT_ASSIGNED,
    /** Zuordnung gültig, aber mit Gebietsbezug und ein übergebener Punkt liegt nicht im zum Zeitpunkt gültigen Gebiet; ohne Ortsangabe nicht prüfbar, daher ebenfalls nicht zulässig (fail closed). */
    OUTSIDE_SERVICE_AREA,
}

data class PoolMember(val vehicleId: UUID, val assignmentId: UUID, val serviceAreaId: UUID?)

/**
 * Fahrzeugpool der Dienste (WP-M3-06, LH-6.8-FLT-002). Ein Fahrzeug kann gleichzeitig mehreren Diensten zugeordnet sein; im selben Dienst überschneiden sich Zuordnungen nicht (409).
 * Fremde Mandanten sind nicht sichtbar (404). Anlegen und Beenden serialisieren über die Fahrzeugzeile (`FOR NO KEY UPDATE`) mit dem Hold (`FOR SHARE`, Sperrreihenfolge Lauf vor Fahrzeug):
 * der Hold liest die Zuordnung danach erneut. Jede Änderung: genau ein Audit und genau ein Outbox-Ereignis in derselben Transaktion. Beenden ändert nie eine zugesagte Fahrt;
 * sie steht als Hinweis im Ergebnis und im Ereignis `vehicle.pool_assignment_ended`.
 */
interface VehiclePool {
    fun assign(ctx: RequestContext, cmd: AssignPoolService): PoolAssignmentView

    /** Beendet eine laufende Zuordnung zum Zeitpunkt [validTo] (exklusiv; nicht vor dem Beginn, nicht in der Vergangenheit). [expectedRevision] 412 bei Abweichung, bereits beendet 409. */
    fun end(ctx: RequestContext, vehicleId: UUID, assignmentId: UUID, expectedRevision: Long, validTo: Instant): PoolAssignmentView

    /** Zuordnungen des Fahrzeugs nach (`created_at`, `id`), optional nur die zu [asOf] gültigen; je Seite höchstens [limit]+1 Zeilen. Fahrzeug unbekannt oder fremd: 404. */
    fun list(ctx: RequestContext, vehicleId: UUID, asOf: Instant?, limit: Int, after: Pair<Instant, UUID>?): List<PoolAssignmentView>

    /** „Für Dienst d zum Zeitpunkt t zulässige Fahrzeuge“ (nur Zuordnung, ohne Sperre, Schicht, Freigabe), sortiert nach Fahrzeug-ID. [locations]: Orte, die im Gebiet liegen müssen. */
    fun eligibleVehicles(ctx: RequestContext, serviceId: UUID, at: Instant, locations: List<GeoPoint> = emptyList()): List<PoolMember>

    /** Poolprüfung eines Fahrzeugs im Kontext der Anfrage. */
    fun check(ctx: RequestContext, vehicleId: UUID, serviceId: UUID, at: Instant, locations: List<GeoPoint> = emptyList()): PoolVerdict

    /** Wie [check] innerhalb einer laufenden Mandantentransaktion des Aufrufers. */
    fun check(jdbc: JdbcOperations, vehicleId: UUID, serviceId: UUID, at: Instant, locations: List<GeoPoint> = emptyList()): PoolVerdict
}
