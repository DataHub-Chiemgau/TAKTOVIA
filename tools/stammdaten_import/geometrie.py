"""Geometrieprüfung der Quelldaten ohne externe Bibliotheken: einfache Gültigkeit von MultiPolygonen (geschlossene Ringe, mindestens vier Punkte,
keine Selbstüberschneidung, Löcher innerhalb der Außenringe) und Punkt-in-Fläche. Maßgeblich bleibt die Prüfung der Anwendung (`ST_IsValid`, 422);
die lokale Prüfung dient dem Trockenlauf und der Fehlerliste vor dem Schreiben."""


def _kreuz(o, a, b):
    return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])


def _auf_strecke(p, a, b):
    return min(a[0], b[0]) <= p[0] <= max(a[0], b[0]) and min(a[1], b[1]) <= p[1] <= max(a[1], b[1])


def _schneiden(p1, p2, p3, p4):
    d1, d2, d3, d4 = _kreuz(p3, p4, p1), _kreuz(p3, p4, p2), _kreuz(p1, p2, p3), _kreuz(p1, p2, p4)
    if ((d1 > 0 > d2) or (d1 < 0 < d2)) and ((d3 > 0 > d4) or (d3 < 0 < d4)):
        return True
    return ((d1 == 0 and _auf_strecke(p1, p3, p4)) or (d2 == 0 and _auf_strecke(p2, p3, p4))
            or (d3 == 0 and _auf_strecke(p3, p1, p2)) or (d4 == 0 and _auf_strecke(p4, p1, p2)))


def _ring_gueltig(ring) -> str | None:
    if not isinstance(ring, list) or len(ring) < 4:
        return "Ring mit weniger als vier Punkten"
    for p in ring:
        if not (isinstance(p, list) and len(p) == 2 and all(isinstance(v, (int, float)) and not isinstance(v, bool) for v in p)):
            return "Koordinate ungültig"
        if not (-180 <= p[0] <= 180 and -90 <= p[1] <= 90):
            return "Koordinate außerhalb des Wertebereichs (Reihenfolge Länge, Breite)"
    if ring[0] != ring[-1]:
        return "Ring nicht geschlossen"
    n = len(ring) - 1
    for i in range(n):
        if ring[i] == ring[i + 1]:
            continue
        for j in range(i + 1, n):
            if abs(i - j) == 1 or (i == 0 and j == n - 1):
                # benachbarte Kanten teilen genau einen Endpunkt; Überlappung kollinearer Kanten erkennt der Test unten
                a, b, c = ring[i], ring[i + 1], ring[j + 1] if j == i + 1 else ring[j]
                if j == i + 1 and _kreuz(a, b, c) == 0 and _auf_strecke(c, a, b) and c != b:
                    return "Ring überlagert sich selbst"
                continue
            if _schneiden(ring[i], ring[i + 1], ring[j], ring[j + 1]):
                return "Ring schneidet sich selbst"
    flaeche = sum(ring[i][0] * ring[i + 1][1] - ring[i + 1][0] * ring[i][1] for i in range(n))
    if flaeche == 0:
        return "Ring ohne Fläche"
    return None


def _in_ring(punkt, ring) -> bool:
    x, y = punkt
    innen = False
    for i in range(len(ring) - 1):
        (x1, y1), (x2, y2) = ring[i], ring[i + 1]
        if _kreuz(ring[i], ring[i + 1], punkt) == 0 and _auf_strecke(punkt, ring[i], ring[i + 1]):
            return True   # Rand zählt als innerhalb (wie ST_Covers)
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            innen = not innen
    return innen


def multipolygon_fehler(geo) -> str | None:
    """None, wenn gültig; sonst der Grund (ohne Koordinatenwerte)."""
    if not isinstance(geo, dict) or geo.get("type") != "MultiPolygon" or not isinstance(geo.get("coordinates"), list) or not geo["coordinates"]:
        return "kein MultiPolygon"
    for poly in geo["coordinates"]:
        if not isinstance(poly, list) or not poly:
            return "Polygon ohne Außenring"
        for ring in poly:
            f = _ring_gueltig(ring)
            if f:
                return f
        for loch in poly[1:]:
            if not all(_in_ring(p, poly[0]) for p in loch):
                return "Loch außerhalb des Außenrings"
    return None


def punkt_in_multipolygon(lat, lon, geo) -> bool:
    punkt = [lon, lat]
    for poly in geo["coordinates"]:
        if _in_ring(punkt, poly[0]) and not any(_in_ring(punkt, loch) and not _auf_rand(punkt, loch) for loch in poly[1:]):
            return True
    return False


def _auf_rand(punkt, ring) -> bool:
    return any(_kreuz(ring[i], ring[i + 1], punkt) == 0 and _auf_strecke(punkt, ring[i], ring[i + 1]) for i in range(len(ring) - 1))
