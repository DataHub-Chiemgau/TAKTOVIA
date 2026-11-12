package de.taktovia.platform.geo

/** Geografischer Punkt in WGS 84 (SRID 4326): Längengrad [lon] (-180 bis 180), Breitengrad [lat] (-90 bis 90). Gemeinsamer Wertetyp der Module (WP-M3-06). */
data class GeoPoint(val lon: Double, val lat: Double) {
    init {
        require(lon.isFinite() && lon in -180.0..180.0) { "Längengrad außerhalb von -180..180" }
        require(lat.isFinite() && lat in -90.0..90.0) { "Breitengrad außerhalb von -90..90" }
    }

    companion object {
        /** JSON-Feld `[[lon,lat],…]` für gebundene Parameter (nur Zahlen, keine Zeichenketteneingabe). */
        fun toJsonArray(points: List<GeoPoint>): String = points.joinToString(",", "[", "]") { "[${it.lon},${it.lat}]" }
    }
}
