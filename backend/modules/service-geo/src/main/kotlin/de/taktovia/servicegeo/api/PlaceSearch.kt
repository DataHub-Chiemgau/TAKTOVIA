package de.taktovia.servicegeo.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/** Die fünf Eingabearten der Ortssuche (LH-6.3-PAX-002); als Treffertyp: Adresse, POI, Haltestelle, Favorit, Kartenpunkt. */
enum class PlaceType { ADDRESS, POI, STOP, FAVORITE, MAP_POINT }

/** Lage eines Treffers zum Bediengebiet des gewählten Dienstes: ohne Dienst [UNKNOWN]; [OUTSIDE] löst in der Oberfläche den Hinweis „außerhalb Bediengebiet“ aus. */
enum class AreaStatus { INSIDE, OUTSIDE, UNKNOWN }

/** Nächster buchbarer Haltepunkt zu einem Treffer (nur mit Dienst, Umkreis [PlaceSearch.NEAREST_STOP_RADIUS_M]). */
data class NearestStop(val stopId: UUID, val name: String, val distanceMeters: Int)

/**
 * Ortstreffer: [point] ist immer eine validierte WGS84-Koordinate (nie 0/0). [stopId] ist nur bei einem **heute buchbaren** Haltepunkt gesetzt: ein Favorit mit inzwischen
 * inaktivem Haltepunkt bleibt als Ort nutzbar, trägt aber keine `stopId`. [source] nennt die Quelle (`stop_registry`, `favorite`, `map` oder die Kennung des Geocoders),
 * [dataAsOf] den Datenstand. Alle Texte sind Daten und werden nie als Markup interpretiert.
 */
data class PlaceHit(
    val type: PlaceType, val label: String, val point: GeoPoint, val stopId: UUID?, val source: String, val dataAsOf: Instant,
    val serviceArea: AreaStatus, val nearestStop: NearestStop?,
)

/** Hinweise zur Antwort: [GEOCODER_UNAVAILABLE] (Adress-/POI-Quelle ausgefallen oder zu langsam; die übrigen Quellen sind unberührt), [NO_MATCH] (nichts auflösbar). */
enum class PlaceNotice { GEOCODER_UNAVAILABLE, NO_MATCH }

/** Favorit des Fahrgasts als Suchkandidat; die Favoriten liefert die Anwendung aus dem Profil des Akteurs, dieses Modul liest nie fremde Konten. */
data class FavoritePlace(val label: String, val point: GeoPoint, val stopId: UUID?)

/**
 * Suchanfrage: entweder [text] (Adresse, POI, Haltestellenname, Favorit; 2 bis 200 Zeichen) oder [mapPoint] (Kartenpunkt), nie beides. [bias] ist die Suchkoordinate der
 * **aktuellen Anfrage** (z. B. Gerätestandort); sie wird weder gespeichert noch protokolliert und nur an den Geocoder weitergegeben. [serviceId] schaltet Bediengebiets-
 * und Haltepunktzuordnung zu.
 */
data class PlaceQuery(
    val text: String? = null, val mapPoint: GeoPoint? = null, val bias: GeoPoint? = null, val serviceId: UUID? = null,
    val favorites: List<FavoritePlace> = emptyList(), val limit: Int = 10,
)

data class PlaceSearchResult(val hits: List<PlaceHit>, val notices: Set<PlaceNotice>)

/**
 * Ortssuche mit fünf Eingabearten (WP-M2-38, LH-6.3-PAX-002). Haltestellen und Favoriten sowie der Kartenpunkt brauchen keinen externen Dienst; Adresse und POI kommen über
 * den [Geocoder]. Fällt er aus oder überschreitet die Zeit, bleiben die anderen Treffer erhalten (Hinweis [PlaceNotice.GEOCODER_UNAVAILABLE]). Suchtexte werden nicht
 * gespeichert, nicht protokolliert und nie als Metriklabel verwendet. Ungültige Eingaben (leer, zu kurz, Koordinate außerhalb WGS84 oder 0/0) ergeben 422 mit Feldverweis.
 */
interface PlaceSearch {
    fun search(ctx: RequestContext, q: PlaceQuery): PlaceSearchResult

    companion object {
        const val NEAREST_STOP_RADIUS_M = 500
        const val MIN_TEXT = 2
        const val MAX_TEXT = 200
        const val MAX_LIMIT = 20
    }
}

/** Kandidat des Geocoders; die Koordinate kommt roh und wird von der Ortssuche geprüft (ungültige Kandidaten werden verworfen). */
data class GeocodeCandidate(val type: PlaceType, val label: String, val latitude: Double, val longitude: Double, val source: String, val dataAsOf: Instant? = null)

/**
 * Anbieterneutrale Schnittstelle zur Adress- und POI-Auflösung (D-09: Karten-/Geocodingquelle und Lizenz offen; Arbeitsstand OSM-basiert). Implementierungen dürfen den
 * Suchtext und die Suchkoordinate an ihre Quelle senden und sonst nichts; sie protokollieren den Suchtext nicht. Jede Ausnahme gilt als Ausfall.
 */
interface Geocoder {
    fun search(text: String, bias: GeoPoint?, limit: Int): List<GeocodeCandidate>
    /** Adresse zu einem Kartenpunkt oder `null`. */
    fun reverse(point: GeoPoint): GeocodeCandidate?

    companion object {
        /** Standard ohne konfigurierte Quelle: meldet jede Anfrage als Ausfall (Haltestelle, Favorit und Kartenpunkt bleiben nutzbar). */
        fun unavailable(): Geocoder = object : Geocoder {
            override fun search(text: String, bias: GeoPoint?, limit: Int): List<GeocodeCandidate> = throw IllegalStateException("kein Geocoder konfiguriert")
            override fun reverse(point: GeoPoint): GeocodeCandidate? = throw IllegalStateException("kein Geocoder konfiguriert")
        }
    }
}
