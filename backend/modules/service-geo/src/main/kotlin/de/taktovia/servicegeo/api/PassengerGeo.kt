package de.taktovia.servicegeo.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/**
 * Haltepunkt in der Fahrgastsicht (WP-M2-40, LH-6.3-PAX-004): nur, was für die Wahl des Abholpunkts nötig ist. Ohne interne Felder (`approved_by`, Gültigkeitsgrenzen,
 * Verkehrsprodukte, Aktivstatus): ein Haltepunkt erscheint hier nur, wenn er aktiv und wirksam ist. [distanceMeters] ist die Luftlinie zum Suchpunkt (Kugelmodell, auf Meter gerundet), `null` ohne Suchpunkt.
 */
data class PassengerStopView(val stopId: UUID, val revision: Long, val name: String, val point: GeoPoint, val accessibility: Map<String, Any?>, val distanceMeters: Int?)

/** Umgrenzung in Grad (WGS84) für Kartenausschnitt und Textbeschreibung. */
data class GeoBounds(val minLatitude: Double, val minLongitude: Double, val maxLatitude: Double, val maxLongitude: Double)

/**
 * Bediengebiet in der Fahrgastsicht: [area] ist **generalisiert** (vereinfacht, auf ca. 10 m gerundet) und nur zur Anzeige gedacht; die Gebietsprüfung des Angebots nutzt immer die genaue
 * Geometrie. [areaSquareKm] und [bounds] speisen die Textbeschreibung des Bediengebiets.
 */
data class PassengerAreaView(
    val id: UUID, val serviceId: UUID, val revision: Long, val area: Map<String, Any?>, val areaSquareKm: Double, val bounds: GeoBounds, val validFrom: Instant, val validTo: Instant?,
)

/**
 * Lesesicht für Fahrgäste auf Bediengebiet und Haltepunkte (WP-M2-40). Unabhängig von den Rechten des Aufrufers werden ausschließlich **aktive**, zum Zeitpunkt wirksame und
 * (mit Dienst) für das Verkehrsprodukt des Dienstes zulässige Haltepunkte geliefert; Mandantentrennung leistet RLS (fremde Haltepunkte: 404 bzw. leer).
 * Sortierung: mit Suchpunkt nach Entfernung, sonst nach Name; Umfang: höchstens `limit` Einträge (keine Folgeseiten, wer mehr sehen will, verengt den Umkreis).
 */
interface PassengerGeo {
    fun stops(ctx: RequestContext, serviceId: UUID?, at: Instant?, near: GeoPoint?, radiusMeters: Int?, limit: Int): List<PassengerStopView>
    /** Wirksamer, aktiver Haltepunkt; sonst 404 (auch inaktiv, abgelaufen, fremder Mandant). */
    fun stop(ctx: RequestContext, stopId: UUID, at: Instant?): PassengerStopView
    /** Zum Zeitpunkt wirksame Bediengebiete (optional eines Dienstes), generalisiert. */
    fun areas(ctx: RequestContext, serviceId: UUID?, at: Instant?, limit: Int): List<PassengerAreaView>
    /** Wirksame Revision eines Bediengebiets (stabiler Schlüssel); sonst 404. */
    fun area(ctx: RequestContext, areaKey: UUID, at: Instant?): PassengerAreaView
}
