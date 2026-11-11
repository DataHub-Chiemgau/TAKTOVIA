package de.taktovia.servicegeo.internal

import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode

/**
 * Strukturprüfung eines GeoJSON-`MultiPolygon` (RFC 7946) vor der Datenbankprüfung: Typ, Rangfolge `[longitude, latitude]`, Wertebereich WGS84 (ein projiziertes
 * Koordinatensystem fällt hier auf), Ringe mit mindestens vier Positionen und **geschlossen**. Die geometrische Gültigkeit (Selbstüberschneidung, Löcher) prüft PostGIS
 * (`ST_IsValid`) in derselben Transaktion; die Spalte erzwingt sie zusätzlich per CHECK.
 */
internal object GeoJsonArea {
    private const val MAX_POLYGONS = 200; private const val MAX_RINGS = 100; private const val MAX_POSITIONS = 10_000

    fun check(raw: Any?, pointer: String = "/area"): List<Violation> {
        val m = raw as? Map<*, *> ?: return listOf(Violation(pointer, if (raw == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH))
        val v = mutableListOf<Violation>()
        m.keys.filter { it != "type" && it != "coordinates" }.forEach { v += Violation("$pointer/$it", ViolationCode.UNKNOWN_FIELD) }
        if (m["type"] != "MultiPolygon") v += Violation("$pointer/type", ViolationCode.NOT_IN_ENUM)
        val polys = m["coordinates"] as? List<*>
        if (polys == null) { v += Violation("$pointer/coordinates", if (m["coordinates"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH); return v }
        if (polys.isEmpty()) v += Violation("$pointer/coordinates", ViolationCode.TOO_FEW_ITEMS)
        if (polys.size > MAX_POLYGONS) v += Violation("$pointer/coordinates", ViolationCode.TOO_MANY_ITEMS)
        polys.forEachIndexed { i, poly ->
            val rings = poly as? List<*>
            val p = "$pointer/coordinates/$i"
            if (rings == null) { v += Violation(p, ViolationCode.TYPE_MISMATCH); return@forEachIndexed }
            if (rings.isEmpty()) v += Violation(p, ViolationCode.TOO_FEW_ITEMS)
            if (rings.size > MAX_RINGS) v += Violation(p, ViolationCode.TOO_MANY_ITEMS)
            rings.forEachIndexed { j, ring -> ring(ring, "$p/$j", v) }
        }
        return v
    }

    private fun ring(ring: Any?, p: String, v: MutableList<Violation>) {
        val pos = ring as? List<*>
        if (pos == null) { v += Violation(p, ViolationCode.TYPE_MISMATCH); return }
        if (pos.size < 4) v += Violation(p, ViolationCode.TOO_FEW_ITEMS, "Ein Ring braucht mindestens vier Positionen")
        if (pos.size > MAX_POSITIONS) v += Violation(p, ViolationCode.TOO_MANY_ITEMS)
        var bad = false
        pos.forEachIndexed { k, c ->
            val xy = c as? List<*>
            val x = (xy?.getOrNull(0) as? Number)?.toDouble(); val y = (xy?.getOrNull(1) as? Number)?.toDouble()
            when {
                xy == null || xy.size != 2 || x == null || y == null || !x.isFinite() || !y.isFinite() -> { v += Violation("$p/$k", ViolationCode.TYPE_MISMATCH); bad = true }
                x < -180.0 || x > 180.0 -> { v += Violation("$p/$k/0", if (x < -180.0) ViolationCode.BELOW_MINIMUM else ViolationCode.ABOVE_MAXIMUM, "Länge außerhalb -180 bis 180 (SRID 4326)"); bad = true }
                y < -90.0 || y > 90.0 -> { v += Violation("$p/$k/1", if (y < -90.0) ViolationCode.BELOW_MINIMUM else ViolationCode.ABOVE_MAXIMUM, "Breite außerhalb -90 bis 90 (SRID 4326)"); bad = true }
            }
        }
        if (!bad && pos.size >= 4 && pos.first() != pos.last()) {
            val a = pos.first() as List<*>; val b = pos.last() as List<*>
            if ((a[0] as Number).toDouble() != (b[0] as Number).toDouble() || (a[1] as Number).toDouble() != (b[1] as Number).toDouble())
                v += Violation(p, ViolationCode.INVALID_VALUE, "Der Ring ist nicht geschlossen (erste und letzte Position müssen gleich sein)")
        }
    }

    /** Menschenlesbarer Grund aus `ST_IsValidReason`, ohne Koordinaten. */
    fun reason(postgis: String?): String = when {
        postgis == null -> "Die Geometrie ist nicht valide"
        postgis.startsWith("Self-intersection") || postgis.startsWith("Ring Self-intersection") -> "Die Geometrie überschneidet sich selbst"
        postgis.startsWith("Too few points") -> "Ein Ring hat zu wenige Punkte"
        postgis.startsWith("Hole lies outside shell") -> "Ein Loch liegt außerhalb der Außenkontur"
        postgis.startsWith("Holes are nested") || postgis.startsWith("Nested") -> "Löcher sind verschachtelt"
        postgis.startsWith("Interior is disconnected") -> "Das Innere der Fläche ist nicht zusammenhängend"
        postgis.startsWith("Duplicate Rings") -> "Ringe sind doppelt vorhanden"
        else -> "Die Geometrie ist nicht valide"
    }
}
