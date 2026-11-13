package de.taktovia.fleetsafety.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/** Art einer Sperre in der Leitstellenansicht: Fahrzeugsperre (alle Schichten) oder Dienstsperre (nur die genannte Schicht, WP-M3-16). */
enum class BlockKind { VEHICLE, SERVICE }

/** Eine aktive Sperre in der Leitstellenansicht samt Vorgangsnummer (nur Codes und Kennungen, keine Diagnosen). */
data class OperatorBlockView(
    val id: UUID, val vehicleId: UUID, val kind: BlockKind, val source: BlockSource, val reason: BlockReason, val caseNumber: String?, val setBy: String, val setAt: Instant,
    val shiftId: UUID?, val revision: Long,
)

/**
 * Fahrzeug der Leitstellenansicht (WP-M3-36, LH-6.7-OPS-001), Fakten aus `fleet-safety`: Sperren, Einsatzfreigabe und fehlende Voraussetzungen der **aktuellen** Schicht, Einschränkung
 * (nicht verfügbare Ressourcendimensionen aus der Freigabeprüfung, WP-M3-12), offene Schadenakten und Alarme sowie die Dienste, denen das Fahrzeug zum Zeitpunkt zugeordnet ist.
 * Position, Dienststatus und Aufträge setzt die Anwendungsschicht aus `staff-time` und `booking-dispatch` hinzu.
 */
data class OperatorVehicleFacts(
    val id: UUID, val label: String, val active: Boolean, val dispatchBlocked: Boolean, val safetyRevision: Long, val revision: Long, val createdAt: Instant,
    val serviceIds: List<UUID>, val shiftId: UUID?, val activeBlocks: List<OperatorBlockView>, val gate: ReleaseGate?, val releaseValidUntil: Instant?, val missing: List<MissingRequirement>,
    val unavailableDimensions: Set<String>, val openDefectCount: Int, val openAlertCount: Int,
)

/**
 * Leitstellenprojektion der Fahrzeuge (WP-M3-36): aktive Fahrzeuge des **berechtigten Dienstes**. Sichtbar ist ein Fahrzeug, wenn es zum Zeitpunkt der Abfrage einem Dienst im aktuellen
 * Dienstscope der Mitgliedschaften des Kontexts zugeordnet ist (Fahrzeugpool, WP-M3-06) oder gar keinem Dienst zugeordnet ist (dann gilt nur die Mandantengrenze, Regel des
 * `ObjectAuthorizer`). Der Scope wird in der Abfragetransaktion frisch gelesen: ab dem Commit eines Scope-Entzugs erscheinen die Fahrzeuge dieses Dienstes nicht mehr. Ein Filter kann den Scope nur
 * einschränken; ein Wunschdienst außerhalb des Scopes ergibt eine leere Liste ohne Hinweis auf die Existenz. Fahrzeuge anderer Mandanten gibt es nie (RLS). Nicht sichtbar heißt bei [get] 404.
 */
interface OperatorVehicles {
    /** Seite nach (`created_at`, `id`); [after] = Position des letzten gelieferten Fahrzeugs. [openAlerts] = nur Fahrzeuge mit offenem Alarm (`true`) bzw. ohne (`false`). Höchstens [limit]+1 Zeilen. */
    fun list(ctx: RequestContext, serviceId: UUID?, openAlerts: Boolean?, limit: Int, after: Pair<Instant, UUID>?): List<OperatorVehicleFacts>

    fun get(ctx: RequestContext, vehicleId: UUID): OperatorVehicleFacts
}
