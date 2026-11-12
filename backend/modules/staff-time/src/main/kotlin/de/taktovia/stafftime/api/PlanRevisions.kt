package de.taktovia.stafftime.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Revisionsstatus der Fahrersicht eines Plans (WP-M3-27, LH-6.6-DRV-004; Zustandsautomat `plan_revision_fahrer.md`). Er wird bei jedem Abruf aus Plan und bestätigter Revision **abgeleitet**,
 * nie gespeichert: [CURRENT] keine Änderung gegenüber der Vergleichsbasis, [NEW_UNCONFIRMED] Änderung mit Bestätigungspflicht, [AUTO_ADOPTED] kleine Änderung, nach Regel kontrolliert
 * übernommen (angezeigt, keine Bestätigung nötig), [CONFIRMED] die aktuelle Revision ist bestätigt. „Vergangen“ gibt es nur beim Client (zwischengespeicherte Revision kleiner als die des Servers).
 */
enum class PlanRevisionState { CURRENT, NEW_UNCONFIRMED, AUTO_ADOPTED, CONFIRMED }

/** Ein entfallener Stopp gegenüber der Vergleichsbasis (nur Haltepunktname, Art, frühere Planzeit und Buchungsreferenzen, keine Personendaten). */
data class DriverRemovedStop(val stopName: String, val kind: String, val plannedAt: Instant, val bookingIds: List<UUID>)

/** Ergebnis des Vergleichs zweier Planrevisionen; [changes] sind „NEW“ und „MOVED“ je Stopp der neuen Revision (`route_stop_id`). */
data class PlanRevisionDiff(val changes: Map<UUID, StopChange>, val removed: List<DriverRemovedStop>, val reordered: Boolean, val acknowledgementRequired: Boolean) {
    val isEmpty: Boolean get() = changes.isEmpty() && removed.isEmpty() && !reordered
}

data class StopChange(val kind: String, val previousPlannedAt: Instant?)

/**
 * Regel „bestätigungspflichtig oder automatisch übernommen“ (Arbeitsannahme des Plans, im Pflichtenheft nicht festgelegt, Zustandsautomat `plan_revision_fahrer.md`): neu hinzugekommene oder
 * entfallene Stopps und eine geänderte Reihenfolge sind **bestätigungspflichtig**; eine reine Zeitverschiebung eines unveränderten Stopps ist erst ab [timeShift] bestätigungspflichtig, darunter wird sie
 * angezeigt und automatisch übernommen. Ein Stopp ist durch Haltepunktrevision, Art und Buchungsmenge identifiziert (die Zeilenkennung wechselt mit jeder Revision).
 */
object PlanRevisionRules {
    private data class Key(val stopRevisionId: UUID?, val kind: String, val bookings: List<UUID>)

    private fun key(s: DriverRunStopView) = Key(s.stopRevisionId, s.kind, s.bookingIds.sortedBy { it.toString() })

    fun diff(base: List<DriverRunStopView>, current: List<DriverRunStopView>, timeShift: Duration): PlanRevisionDiff {
        val baseByKey = base.groupBy(::key).mapValues { it.value.toMutableList() }
        val changes = LinkedHashMap<UUID, StopChange>()
        var required = false
        val matchedBase = HashSet<DriverRunStopView>()
        current.forEach { s ->
            val candidate = baseByKey[key(s)]?.removeFirstOrNull()
            if (candidate == null) { changes[s.routeStopId] = StopChange("NEW", null); required = true }
            else {
                matchedBase += candidate
                if (candidate.plannedAt != s.plannedAt) {
                    changes[s.routeStopId] = StopChange("MOVED", candidate.plannedAt)
                    if (Duration.between(candidate.plannedAt, s.plannedAt).abs() >= timeShift) required = true
                }
            }
        }
        val removed = base.filter { it !in matchedBase }.map { DriverRemovedStop(it.stopName, it.kind, it.plannedAt, it.bookingIds) }
        if (removed.isNotEmpty()) required = true
        // Reihenfolge der verbliebenen (übereinstimmenden) Stopps
        val order = current.mapNotNull { c -> base.firstOrNull { it in matchedBase && key(it) == key(c) } }
        val reordered = order.size == matchedBase.size && order.map { base.indexOf(it) } != order.map { base.indexOf(it) }.sorted()
        if (reordered) required = true
        return PlanRevisionDiff(changes, removed, reordered, required)
    }
}

/** Bestätigung einer Planrevision durch den Fahrer; [deviceId] ist optional (ein gebundenes Gerät des Kontos), [acknowledgedAt] die vom Gerät behauptete Zeit (nur zur Anzeige, die Serverzeit steht in `created_at`). */
data class AckRunCommand(val planRevision: Long, val acknowledgedAt: Instant, val deviceId: UUID? = null)

/** [created] ist falsch, wenn die Revision (auch von einem anderen Gerät desselben Fahrers) bereits bestätigt war: es gibt genau einen Eintrag je Schicht und Revision. */
data class AckRunResult(val run: DriverRunView, val created: Boolean)

/** Eine seit längerem unbestätigte, bestätigungspflichtige Planrevision (Hinweis an die Leitstelle, WP-M3-27). */
data class PlanAckOverdue(val shiftId: UUID, val vehicleId: UUID, val runId: UUID, val planRevision: Long, val since: Instant)

/** Senke für den Hinweis an die Leitstelle bei ausbleibender Bestätigung; der Dedupe (je Schicht und Revision genau einer) liegt bei der Senke (Alarmdienst, WP-M3-52). */
fun interface PlanAckOverdueSink {
    fun overdue(tenant: TenantId, hint: PlanAckOverdue)

    companion object { val NONE = PlanAckOverdueSink { _, _ -> } }
}

/**
 * Planbestätigung des Fahrers (WP-M3-27, LH-6.6-DRV-004). Die Bestätigung gilt der **aktuellen** Revision (`If-Match` = Planrevision): eine veraltete wird mit 412 abgewiesen, eine
 * fremde Schicht ist 404, derselbe Fahrer bestätigt dieselbe Revision von zwei Geräten genau einmal (idempotent). Eine übersprungene Revision lässt sich nie bestätigen, weil nur die aktuelle
 * bestätigbar ist; die Vergleichsbasis der Anzeige ist stets die zuletzt bestätigte Revision.
 */
interface DriverPlanAcks {
    fun acknowledge(ctx: RequestContext, shiftId: UUID, ifMatch: Long, cmd: AckRunCommand): AckRunResult

    /** Meldet bestätigungspflichtige Revisionen, die seit mindestens `plan_ack_overdue_min` unbestätigt sind, an die [PlanAckOverdueSink]; liefert sie zurück. Betriebsauftrag je Mandant, ohne Personenkontext. */
    fun reportOverdue(tenant: TenantId): List<PlanAckOverdue>
}

/**
 * Nutzlast des technischen Weckrufs für eine geschlossene App (WP-M3-27, OP-29): **nur** der Hinweis „neue Planrevision“ mit der Revision; weder Fahrgast- noch Ort- oder Haltepunktdaten und
 * keine Schicht- oder Buchungskennung. Die App holt den Inhalt danach über `GET /v1/driver/shifts/{id}/run` (ETag). Ein fachlicher Fahrer-Push mit Inhalt ist `LH-6.12-COM-007` (P2) und
 * nicht Teil dieses Pakets; Anbieter und Gerätetoken des Push-Kanals sind offen (OP-29, D-05).
 */
object PlanRevisionWakeup {
    @Suppress("UNUSED_PARAMETER")
    fun payload(shiftId: UUID, revision: Long): Map<String, String> = linkedMapOf("type" to "plan_revision", "revision" to revision.toString())
}
