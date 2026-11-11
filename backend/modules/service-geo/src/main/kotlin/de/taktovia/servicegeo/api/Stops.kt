package de.taktovia.servicegeo.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/**
 * Eine Revision eines Haltepunkts (WP-M2-05, LH-6.9-GEO-002). [stopId] ist der stabile Identifikator, [revisionId] die Zeile der Revision (darauf verweisen Fahrtenplan und
 * Zusagen). [active] ist der Aktivstatus **dieser** Revision; [trafficProducts] die zulässigen Verkehrsprodukte (mindestens eines). Wirksam `validFrom <= t < validTo`.
 */
data class StopView(
    val stopId: UUID, val revisionId: UUID, val revision: Long, val name: String, val point: GeoPoint, val accessibility: Map<String, Any?>,
    val trafficProducts: List<String>, val active: Boolean, val approvedBy: String, val validFrom: Instant, val validTo: Instant?, val createdAt: Instant,
)

/** Eingabe eines neuen Haltepunkts ([stopId] `null` = der Dienst vergibt ihn; ein vorgegebener, bereits vorhandener Wert ist 409). */
data class CreateStop(
    val stopId: UUID?, val name: String, val point: Any?, val accessibility: Any?, val trafficProducts: Any?, val active: Boolean, val validFrom: Instant, val validTo: Instant?,
)

/** Eingabe einer neuen Revision (auch Deaktivierung: [active] `false`). */
data class ReviseStop(
    val name: String, val point: Any?, val accessibility: Any?, val trafficProducts: Any?, val active: Boolean, val validFrom: Instant, val validTo: Instant?,
)

/**
 * Suchfilter. [serviceId] schaltet die Sicht „buchbar“ ein: nur zum Zeitpunkt [at] (Standard jetzt) wirksame, **aktive** Revisionen, deren Verkehrsprodukt zum Dienst
 * (wirksame Dienstversion zu [at], WP-M2-03) passt; ohne wirksame Dienstversion ist die Liste leer. [nearRadiusMeters] mit [near] begrenzt auf einen Umkreis (Meter, Kugelmodell
 * der Geographie). [active] wirkt nur bei Aufrufern mit Stammdatenrecht (`geo:write`); alle anderen sehen ausschließlich aktive, zu [at] wirksame Haltepunkte.
 */
data class StopFilter(
    val serviceId: UUID? = null, val at: Instant? = null, val near: GeoPoint? = null, val nearRadiusMeters: Int? = null, val active: Boolean? = null,
)

/**
 * Haltepunktrevisionen mit Aktivstatus (WP-M2-05). Eine Änderung ist immer eine neue Revision (Deaktivierung eingeschlossen), `If-Match`-Semantik wie bei den übrigen
 * Stammdaten (veraltet `REVISION_MISMATCH`). Koordinate außerhalb WGS84, Schemaverletzung der Barrierefreiheit oder fehlendes Verkehrsprodukt: 422 mit Feldverweis, nichts
 * gespeichert. Jede Änderung ist auditiert, `approvedBy` ist der freigebende Akteur (Annahme OP-9: Freigabe = Anlegen durch Rolle mit Stammdatenrecht).
 */
interface Stops {
    fun create(ctx: RequestContext, cmd: CreateStop): StopView
    fun revise(ctx: RequestContext, stopId: UUID, expectedRevision: Long, cmd: ReviseStop): StopView
    /** Jüngste Revision oder, mit [revision], genau diese; mit [at] die zu diesem Zeitpunkt wirksame (nur Stammdatenrecht; sonst 404 für nicht wirksame/inaktive Haltepunkte). */
    fun get(ctx: RequestContext, stopId: UUID, revision: Long? = null): StopView
    fun list(ctx: RequestContext, filter: StopFilter, limit: Int, after: Pair<Instant, UUID>?): List<StopView>
    /** „Buchbarer Haltepunkt für Dienst und Zeitpunkt“ (Angebotsbildung, Relationsregel): wirksam, aktiv, Verkehrsprodukt passt; sonst `null`. */
    fun bookable(ctx: RequestContext, serviceId: UUID, stopId: UUID, at: Instant): StopView?
}
