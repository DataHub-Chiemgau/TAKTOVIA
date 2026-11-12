package de.taktovia.documentsfinance.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.InboxOutcome
import java.time.Instant
import java.util.UUID

/** Quelle eines Kilometerstands (WP-M4-41, FCO-003). Die Werkstattquelle ist in P1 ein Importkanal ohne `repair_order` (FCO-011 ist M7/M8). */
enum class OdometerSource { DRIVER_CHECK, FUEL_RECEIPT, WORKSHOP, TELEMATICS }

enum class OdometerConflictKind {
    /** Rückläufiger Stand: ein später gemessener Stand liegt um mehr als die Toleranz unter einem früher gemessenen. */
    DECREASING,
    /** Zwei verschiedene Quellen melden im selben Zeitfenster Stände, die um mehr als die Toleranz voneinander abweichen. */
    SOURCE_MISMATCH,
}

enum class OdometerConflictStatus { OPEN, RESOLVED }
enum class ConflictDecision { CHOSEN, DISCARDED }

/**
 * Ein Kilometerstand mit Herkunft: Quelle, Bezug auf das Quellobjekt ([sourceRef], z. B. Prüflauf, Beleg), Messzeit ([measuredAt], Zeit der Messung an der Quelle) und Erfassungszeit
 * ([recordedAt], Zeit der Aufnahme ins Register). Nie geändert oder gelöscht.
 */
data class OdometerReadingView(
    val id: UUID, val vehicleId: UUID, val readingKm: Long, val source: OdometerSource, val sourceRef: String, val measuredAt: Instant, val recordedAt: Instant, val recordedBy: String,
)

/** Protokollierte Auflösung: Entscheidung (gewählter Stand oder Verwerfung beider), Akteur, Grund und Zeit. Die Rohstände bleiben unverändert erhalten. */
data class ConflictResolutionView(val decision: ConflictDecision, val chosenReadingId: UUID?, val resolvedBy: String, val reason: String, val resolvedAt: Instant)

/** Konflikt mit beiden Ständen einander gegenübergestellt. `earlier` ist der früher gemessene, `later` der später gemessene Stand. */
data class OdometerConflictView(
    val id: UUID, val vehicleId: UUID, val kind: OdometerConflictKind, val toleranceKm: Int, val earlier: OdometerReadingView, val later: OdometerReadingView, val detectedAt: Instant,
    val resolution: ConflictResolutionView?,
) { val status: OdometerConflictStatus get() = if (resolution == null) OdometerConflictStatus.OPEN else OdometerConflictStatus.RESOLVED }

/** Konfliktparameter (Planungsannahme, Offener Punkt 21): Toleranz in km und Zeitfenster für „selber Zeitraum“. */
data class OdometerPolicyView(val toleranceKm: Int, val windowMinutes: Int, val revision: Long) {
    companion object { const val DEFAULT_TOLERANCE_KM = 5; const val DEFAULT_WINDOW_MINUTES = 60 }
}

data class RecordOdometer(val source: OdometerSource, val sourceRef: String, val readingKm: Long, val measuredAt: Instant)
data class ResolveConflict(val decision: ConflictDecision, val chosenReadingId: UUID? = null, val reason: String = "")

/** Ergebnis der Aufnahme: der Stand, ob er neu war (`false` = dasselbe Quellereignis wurde schon aufgenommen) und die dabei erkannten neuen Konflikte. */
data class OdometerRecordOutcome(val reading: OdometerReadingView, val created: Boolean, val conflicts: List<OdometerConflictView>)

/**
 * Kilometerstandsregister (WP-M4-41, FCO-003): führt Kilometerstände aus Fahrercheck, Tankbeleg, Werkstatt und Telematik mit nachvollziehbarer Herkunft zusammen.
 *
 * Regeln: (1) Alle Stände sind append-only; es wird nichts überschrieben oder gelöscht. (2) Dasselbe Quellereignis (`source`, `sourceRef`) ergibt genau einen Eintrag, auch bei wiederholter Zustellung.
 * (3) Die Konflikterkennung läuft je Fahrzeug serialisiert (transaktionale Sperre), damit gleichzeitig eintreffende Stände einander sehen. (4) Das Register wählt **nie** automatisch eine Quelle aus:
 * die Auflösung ist stets eine menschliche, protokollierte Entscheidung (Akteur, Grund, Zeit; zusätzlich Audit). (5) Ein in einer Auflösung verworfener Stand (beide bei DISCARDED, sonst der nicht gewählte)
 * zählt für künftige Konflikterkennung nicht mehr, bleibt aber lesbar. (6) Telematikstände stammen bis zum Beschluss D-09 nur aus dem Simulator (Bezug beginnt mit `sim:`).
 */
interface OdometerRegister {
    /**
     * Nimmt einen Stand auf (Scope `odometer:record`; Fahrzeug eines fremden Mandanten: 404; Stand außerhalb 0 bis 10.000.000 km, Bezug leer oder über 200 Zeichen, Messzeit mehr als einen Tag in der Zukunft
     * oder Telematikstand ohne Simulatorbezug: 422).
     */
    fun record(ctx: RequestContext, vehicleId: UUID, cmd: RecordOdometer): OdometerRecordOutcome

    /** Stände eines Fahrzeugs, aufsteigend nach Messzeit, Cursor `(measuredAt, id)` (Scope `odometer:read`; fremdes Fahrzeug 404). */
    fun readings(ctx: RequestContext, vehicleId: UUID, limit: Int, after: Pair<Instant, UUID>?): List<OdometerReadingView>

    /** Konflikte eines Fahrzeugs, aufsteigend nach Erkennungszeit, optional nach Status gefiltert (Scope `odometer:read`; fremdes Fahrzeug 404). */
    fun conflicts(ctx: RequestContext, vehicleId: UUID, status: OdometerConflictStatus?, limit: Int, after: Pair<Instant, UUID>?): List<OdometerConflictView>

    /**
     * Löst einen Konflikt auf (Scope `odometer:resolve`, Finanzrolle, Mensch). Grund 3 bis 1000 Zeichen, sonst 422; `CHOSEN` verlangt einen der beiden Stände (sonst 422), `DISCARDED` keinen.
     * Konflikt oder Fahrzeug eines fremden Mandanten: 404; bereits aufgelöst: 409 (bei paralleler Auflösung gewinnt genau eine).
     */
    fun resolve(ctx: RequestContext, vehicleId: UUID, conflictId: UUID, cmd: ResolveConflict): OdometerConflictView

    /** Konfliktparameter des Mandanten (Scope `odometer:read`); ohne Konfiguration die Vorgabe (5 km, 60 Minuten). */
    fun policy(ctx: RequestContext): OdometerPolicyView

    /** Setzt Toleranz (0 bis 1000 km) und Zeitfenster (1 bis 10080 Minuten); Scope `odometer:resolve` und Finanzrolle. Wirkt nur auf künftige Stände. */
    fun updatePolicy(ctx: RequestContext, toleranceKm: Int, windowMinutes: Int): OdometerPolicyView
}

/**
 * Ereigniskonsum des Registers über die Inbox (WP-M1-14): `inspection.completed` (Quelle DRIVER_CHECK, nur mit Kilometerstand), `fuel.transaction_recorded` (Quelle FUEL_RECEIPT, erst bei
 * vollständigen Tankwerten und freigegebenem Beleg) und `expense.approved` (holt die Aufnahme eines Tankstands nach, wenn die Freigabe nach der Erfassung kam). Je Ereignis genau eine Wirkung.
 */
interface OdometerEventConsumer {
    fun consume(json: String): InboxOutcome
}

/** Ereignistypen, die das Register verarbeitet. */
object OdometerEvents {
    const val INSPECTION_COMPLETED = "inspection.completed"
    const val FUEL_RECORDED = "fuel.transaction_recorded"
    const val EXPENSE_APPROVED = "expense.approved"
    const val CONFLICT_DETECTED = "odometer.conflict_detected"
    val CONSUMED = setOf(INSPECTION_COMPLETED, FUEL_RECORDED, EXPENSE_APPROVED)
}

/** Bausteine des Kilometerstandsregisters (WP-M4-41); eigene Fabrik, damit die Belegbausteine (`DocumentServices`) unberührt bleiben. */
class OdometerServices(val register: OdometerRegister, val events: OdometerEventConsumer) {
    companion object {
        /** `telematicsProductive` = `false` (Vorgabe, bis D-09 beschlossen ist): Telematikstände nur mit Simulatorbezug. */
        fun create(
            ctxTx: de.taktovia.platform.context.ContextTransactions, audit: de.taktovia.platform.audit.AuditPort, outbox: de.taktovia.platform.outbox.Outbox, clock: java.time.Clock,
            processor: de.taktovia.platform.outbox.InboxProcessor, telematicsProductive: Boolean = false,
        ): OdometerServices = de.taktovia.documentsfinance.internal.OdometerServicesFactory.create(ctxTx, audit, outbox, clock, processor, telematicsProductive)
    }
}
