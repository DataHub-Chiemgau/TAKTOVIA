package de.taktovia.documentsfinance.api

import de.taktovia.platform.context.RequestContext
import java.util.UUID

/** Art eines Kostenobjekts, dem ein Beleg zugeordnet werden darf (WP-M4-29; weitere Arten des Kern-DDL folgen mit der Belegaufteilung, WP-M4-35). */
enum class CostObjectKind { VEHICLE, COST_CENTER }

/** Kostenobjekt (Kostenstelle oder Fahrzeug) mit Rechtsträger; `vehicleId` nur bei [CostObjectKind.VEHICLE]; `serviceId` nur bei einer Kostenstelle mit Dienstbezug. */
data class CostObjectView(
    val id: UUID, val kind: CostObjectKind, val reference: String, val name: String?, val legalEntityId: UUID, val vehicleId: UUID?, val serviceId: UUID?, val active: Boolean,
)

/**
 * Zuordnung eines Belegs (WP-M4-29, DOC-004): `costObjectIds` = Kostenstellen und Fahrzeug-Kostenobjekte, `vehicleIds` = Fahrzeuge, deren Kostenobjekt beim Rechtsträger des Belegs bei Bedarf angelegt wird.
 * Die Menge **ersetzt** die bisherige Zuordnung; mindestens ein Eintrag.
 */
data class AssignCosts(val costObjectIds: Set<UUID> = emptySet(), val vehicleIds: Set<UUID> = emptySet())

/**
 * Kostenzuordnung eines Belegs zu Firma (Rechtsträger des Belegs), Kostenstelle oder Fahrzeug (WP-M4-29, DOC-004).
 *
 * **Regeln (Planungsannahme, D-06):** Der Rechtsträger ist der des Belegs (bei der Anlage gesetzt, danach unveränderlich). Ein Beleg ist freigabefähig, wenn mindestens ein aktives Kostenobjekt
 * desselben Rechtsträgers zugeordnet ist. Die Finanzrolle ordnet Kostenstellen und Fahrzeuge zu, der Erfasser (z. B. Fahrer) nur Fahrzeuge. Kostenstellen mit Dienstbezug sind nur im Dienstscope des
 * Benutzers zuordenbar (Objektberechtigung, WP-M1-08). Fremde Mandanten, nicht sichtbare und nicht erlaubte Objekte: 404 ohne Existenzhinweis; Kostenobjekt eines anderen Rechtsträgers oder inaktiv:
 * 422. Jede Änderung der Zuordnung erzeugt eine neue Belegrevision (die Freigabe bindet an die exakte Revision).
 */
interface CostAssignments {
    /** Ersetzt die Zuordnung. `expectedRevision` = `If-Match` (veraltet 412); nur im Entwurfszustand `STORED` (sonst 409 `IMMUTABLE_RECORD`). Gleiche Menge: keine neue Revision. */
    fun assign(ctx: RequestContext, documentId: UUID, expectedRevision: Long, cmd: AssignCosts): ExpenseView

    /** Aktuell zugeordnete Kostenobjekte (Erfasser und Finanzrolle; sonst 404). */
    fun assigned(ctx: RequestContext, documentId: UUID): List<CostObjectView>

    /** Kostenobjekte, die der Benutzer zuordnen darf (Mandant, Objektberechtigung); Seitenweise nach Kennung, `after` = letzte Kennung der Vorseite. */
    fun costObjects(ctx: RequestContext, kind: CostObjectKind? = null, limit: Int = 50, after: UUID? = null): List<CostObjectView>

    /** Deaktiviert ein Kostenobjekt (nur Finanzrolle). Schon zugeordnete Belege sind danach nicht mehr freigabefähig ([ReleaseBlocker.COST_OBJECT_INACTIVE]); die Wirkung ist endgültig. */
    fun deactivate(ctx: RequestContext, costObjectId: UUID): CostObjectView
}

