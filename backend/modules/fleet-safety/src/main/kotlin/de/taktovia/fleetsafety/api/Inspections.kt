package de.taktovia.fleetsafety.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ViolationCode
import java.time.Instant
import java.util.UUID

/**
 * Prüflauf-Start (WP-M3-13, LH-6.21-CHK-002, Detailvertrag `docs/umsetzungsplan/vertraege/LH-6.21-CHK-002.md`).
 *
 * **Planungsannahme PA-08 (M3 OP-14, nicht freigegeben):** Fahrzeugklasse = `vehicle.vehicle_type` (Katalog PKW, KLEINBUS, BUS, RAMPE), Bedienart = `vehicle.operation_kind`
 * (Katalog STANDARD, ASSISTENZ); eine Vorlagenrevision gilt für das Paar (`vehicle_class`, `service_mode`). Der Katalog ist versionierte Konfiguration und wird hier nur als Format
 * (Großbuchstaben, Ziffern, Unterstrich) geprüft, nicht als feste Aufzählung.
 */
object InspectionCatalog {
    val KINDS: Set<String> = linkedSetOf("PRE_SHIFT", "FOLLOW_UP", "VEHICLE_CHANGE")
    /** Toleranz der Gerätezeit gegenüber der Serverzeit („Start nicht in der Zukunft“). */
    const val FUTURE_TOLERANCE_SECONDS = 60L
    /** Stabile Kennwörter am Anfang von `detail` des 409, wenn keine eindeutig passende Vorlage besteht (Vertrag `createInspection`). */
    const val TEMPLATE_UNAVAILABLE = "TEMPLATE_UNAVAILABLE"
    const val TEMPLATE_AMBIGUOUS = "TEMPLATE_AMBIGUOUS"
}

/**
 * Start eines Prüflaufs. Die Vorlage bestimmt allein der Server; ein mitgesendetes `template_revision_id` wird gelesen, aber **nie verwendet** (Originalabnahme CHK-002:
 * ein Client kann keine andere Vorlage wählen). Mandant und Fahrer kommen aus dem Kontext, nie aus dem Body.
 */
data class StartInspection(val shiftId: UUID, val vehicleId: UUID, val kind: String, val clientStartedAt: Instant) {
    companion object {
        private val FIELDS = setOf("shift_id", "vehicle_id", "template_revision_id", "kind", "odometer_km", "battery_soc_pct", "client_started_at")

        fun parse(raw: Map<String, Any?>): StartInspection {
            val v = Violations()
            v.unknown(raw, FIELDS, "")
            val shift = v.uuid(raw, "shift_id", true)
            val vehicle = v.uuid(raw, "vehicle_id", true)
            v.uuid(raw, "template_revision_id", false)   // wirkungslos (siehe oben), nur auf Format geprüft
            val kind = v.code(raw, "kind", InspectionCatalog.KINDS, true)
            val started = v.instant(raw, "client_started_at", true)
            // Schichtstartwerte (Kilometerstand, Ladezustand) prüft und speichert WP-M3-17; hier nur auf Typ und Bereich geprüft, nicht gespeichert.
            when (val x = raw["odometer_km"]) {
                null -> {}
                is Int, is Long -> (x as Number).toLong().let { if (it < 0) v.add("/odometer_km", ViolationCode.BELOW_MINIMUM) else if (it > 5_000_000) v.add("/odometer_km", ViolationCode.ABOVE_MAXIMUM) }
                else -> v.add("/odometer_km", ViolationCode.TYPE_MISMATCH)
            }
            when (val x = raw["battery_soc_pct"]) {
                null -> {}
                is Number -> x.toDouble().let { if (it < 0.0) v.add("/battery_soc_pct", ViolationCode.BELOW_MINIMUM) else if (it > 100.0) v.add("/battery_soc_pct", ViolationCode.ABOVE_MAXIMUM) }
                else -> v.add("/battery_soc_pct", ViolationCode.TYPE_MISMATCH)
            }
            v.throwIfAny()
            return StartInspection(shift!!, vehicle!!, kind!!, started!!)
        }
    }
}

/** Prüfschritt der festen Vorlagenrevision des Laufs (Sicht der Fahrer-App: „Prüfung n / [InspectionTemplateView.steps].size“). */
data class InspectionTemplateView(val revisionId: UUID, val templateId: UUID, val version: Int, val vehicleClass: String, val serviceMode: String, val steps: List<ChecklistStepView>)

data class InspectionAnswerView(val stepCode: String, val answer: String, val numberValue: java.math.BigDecimal?, val note: String?, val answeredAt: Instant)

data class InspectionReleaseView(val releaseId: UUID, val validUntil: Instant, val safetyRevision: Long)

data class InspectionView(
    val id: UUID, val shiftId: UUID, val vehicleId: UUID, val driverRef: UUID, val templateRevisionId: UUID, val kind: String, val status: String, val outcome: String?,
    val startedAt: Instant, val completedAt: Instant?, val receivedAt: Instant?, val revision: Long, val template: InspectionTemplateView,
    val answers: List<InspectionAnswerView> = emptyList(), val release: InspectionReleaseView? = null,
)

/** Ergebnis des Starts: [created] ist `false`, wenn für (Schicht, Fahrzeug, Art) bereits ein offener Lauf bestand (dieser wird unverändert, mit seiner Vorlagenrevision, geliefert). */
data class StartedInspection(val inspection: InspectionView, val created: Boolean)

/**
 * Prüflauf (WP-M3-13). Start nur durch den Fahrer der eigenen, **angetretenen** Schicht (Dienstanmeldung, WP-M3-25); fremde Schicht oder fremder Mandant: 404. Die Vorlagenrevision
 * wählt der Server aus Fahrzeugklasse und Bedienart des Fahrzeugs: genau eine freigegebene Revision, sonst entsteht kein Lauf, es gibt 409 (`TEMPLATE_UNAVAILABLE` bzw.
 * `TEMPLATE_AMBIGUOUS`) und die Leitstelle erhält in derselben Transaktion ein Hinweisereignis (`inspection.template_unavailable`) samt Audit. Ein offener Lauf je
 * (Schicht, Fahrzeug, Art) wird per Teil-Unique-Index erzwungen: ein zweiter Start (anderes Gerät, anderer Schlüssel) liefert den bestehenden Lauf. Der Lauf behält seine Revision,
 * auch wenn danach eine neue Vorlagenrevision freigegeben wird.
 */
interface Inspections {
    fun start(ctx: RequestContext, cmd: StartInspection): StartedInspection

    /** Lesen mit fester Vorlage und gespeicherten Antworten. Fahrer sehen nur Läufe eigener Schichten (sonst 404), Disposition und Werkstatt alle Läufe des Mandanten. */
    fun get(ctx: RequestContext, id: UUID): InspectionView
}
