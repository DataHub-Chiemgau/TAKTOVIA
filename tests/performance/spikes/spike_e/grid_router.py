"""Referenzimplementierung des Routing-Ports auf einem synthetischen Gitternetz. KEIN Routinganbieter: Laufzeiten und Fahrzeiten sind rein
rechnerisch und nicht auf reale Netze übertragbar. Dient dazu, Port, Cache-Schlüssel, Fehlerverhalten und das Messwerkzeug zu prüfen.

Netz: gleichmäßiges Gitter in der Stellvertreterregion; Fahrzeit = Manhattan-Entfernung / Geschwindigkeit des Profils. Rollstuhlprofil meidet
'Treppenzellen' (jede 7. Zelle in x-Richtung) durch Umweg; Zellen auf der gesperrten Zeile (Y_BLOCKED) sind für alle Profile nicht erreichbar.
"""
import math
import time

from .routing_port import MatrixResult, NoRoute, OutOfCoverage, RouteResult, RoutingPort

BBOX = (50.95, 13.55, 51.20, 14.00)    # lat_min, lon_min, lat_max, lon_max (Stellvertreterregion, synthetisch)
CELL_DEG = 0.001                        # ~111 m in Breite
SPEED_MPS = {"auto": 8.3, "pedestrian": 1.3, "wheelchair": 1.0}   # 30 km/h im Stadtnetz, Gehen, Rollstuhl
M_PER_DEG_LAT = 111_195.0


class SyntheticGridRouter(RoutingPort):
    name = "synthetisches-gitter (Referenz, kein Anbieter)"

    def __init__(self, version="grid-v1"):
        self._version = version

    def data_version(self):
        return self._version

    def _cell(self, p):
        lat, lon = p
        if not (BBOX[0] <= lat <= BBOX[2] and BBOX[1] <= lon <= BBOX[3]):
            raise OutOfCoverage(f"({lat},{lon}) außerhalb des Datenbestands")
        return round((lat - BBOX[0]) / CELL_DEG), round((lon - BBOX[1]) / CELL_DEG)

    def _time(self, a, b, profile):
        (ay, ax), (by, bx) = self._cell(a), self._cell(b)
        if ay == 0 or by == 0:
            raise NoRoute("Zelle auf gesperrter Zeile")
        dy, dx = abs(ay - by), abs(ax - bx)
        m_lat = dy * CELL_DEG * M_PER_DEG_LAT
        m_lon = dx * CELL_DEG * M_PER_DEG_LAT * math.cos(math.radians((a[0] + b[0]) / 2))
        dist = m_lat + m_lon
        if profile == "wheelchair":
            stairs = sum(1 for x in range(min(ax, bx), max(ax, bx) + 1) if x % 7 == 0)
            dist += stairs * 40            # Umweg je Treppenzelle
        return round(dist / SPEED_MPS[profile]), round(dist)

    def route(self, origin, dest, profile="auto"):
        t0 = time.perf_counter()
        s, d = self._time(origin, dest, profile)
        return RouteResult(s, d, self._version, profile, self.name, (time.perf_counter() - t0) * 1000)

    def matrix(self, origins, dests, profile="auto"):
        t0 = time.perf_counter()
        out = []
        for o in origins:
            row = []
            for d in dests:
                try:
                    row.append(self._time(o, d, profile)[0])
                except NoRoute:
                    row.append(None)
            out.append(row)
        return MatrixResult(out, self._version, profile, self.name, (time.perf_counter() - t0) * 1000)
