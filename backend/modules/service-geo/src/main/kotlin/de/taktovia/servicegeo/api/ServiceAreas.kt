package de.taktovia.servicegeo.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/** Punkt in WGS84 (SRID 4326), Reihenfolge wie in GeoJSON: Länge, Breite. */
data class GeoPoint(val longitude: Double, val latitude: Double) {
    init { require(longitude in -180.0..180.0 && latitude in -90.0..90.0) { "Koordinate außerhalb des Wertebereichs WGS84" } }
}

/**
 * Eine Revision eines Bediengebiets (WP-M2-04, LH-6.9-GEO-001). [id] ist der stabile Gebietsschlüssel der Revisionskette, [revisionId] die Zeile dieser Revision
 * (darauf verweisen bestehende Zusagen). [area] ist ein GeoJSON-`MultiPolygon` (SRID 4326). Wirksam: `validFrom <= t < validTo` (links geschlossen, rechts offen).
 */
data class ServiceAreaView(
    val id: UUID, val revisionId: UUID, val serviceId: UUID, val revision: Long, val area: Map<String, Any?>, val validFrom: Instant, val validTo: Instant?,
    val createdBy: String, val createdAt: Instant,
)

/** Eingabe einer Gebietsrevision; [area] in der JSON-Form eines GeoJSON-`MultiPolygon` (Map), wird vor der Speicherung geprüft. */
data class CreateServiceArea(val serviceId: UUID, val area: Any?, val validFrom: Instant, val validTo: Instant?)
data class ReviseServiceArea(val area: Any?, val validFrom: Instant, val validTo: Instant?)

/**
 * Bediengebiete als valide Polygone mit Aktivierungszeitraum (WP-M2-04). Geometrievalidierung **vor** der Speicherung (Gültigkeit nach `ST_IsValid`, SRID 4326,
 * geschlossene Ringe, keine Selbstüberschneidung): ungültig 422 mit Feldverweis `/area…`, nichts gespeichert. Änderungen verlangen die erwartete Revision (428/412 im
 * HTTP-Rand, hier `REVISION_MISMATCH`); überlappende Wirksamkeit je Dienst 409. Eine Bearbeitung legt eine neue Revision an, ältere bleiben lesbar. Ein fremder Mandant ist
 * wie „nicht vorhanden“ (404). Lesen ist mandantenweit (Fahrgäste sehen das Bediengebiet), Schreiben verlangt Stammdatenrecht.
 */
interface ServiceAreas {
    fun create(ctx: RequestContext, cmd: CreateServiceArea): ServiceAreaView
    /** Neue Revision der Kette [areaKey]; [expectedRevision] ist die zuletzt gelesene (höchste) Revision. */
    fun revise(ctx: RequestContext, areaKey: UUID, expectedRevision: Long, cmd: ReviseServiceArea): ServiceAreaView
    /** Jüngste Revision der Kette oder, mit [revision], genau diese. */
    fun get(ctx: RequestContext, areaKey: UUID, revision: Long? = null): ServiceAreaView
    /** Jüngste Revision je Gebiet, optional eines Dienstes; stabil sortiert nach `(createdAt, revisionId)` der Revision. */
    fun list(ctx: RequestContext, serviceId: UUID?, limit: Int, after: Pair<Instant, UUID>?): List<ServiceAreaView>
    /** Zum Zeitpunkt [at] wirksames Gebiet des Dienstes, das [point] enthält (Punkt auf der Grenze zählt als innerhalb, `ST_Covers`); `null` = außerhalb (Angebotsprüfung, WP-M2-12). */
    fun covering(ctx: RequestContext, serviceId: UUID, point: GeoPoint, at: Instant): ServiceAreaView?
}
