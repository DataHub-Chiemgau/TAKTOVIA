"""Providerunabhängige Adapter-Schnittstelle für Routing (Entwurf, D-09, Kap. 04 §4.4 route_matrix_cache). Wegwerf-Entwurf der Spike-Arbeit.

Cache-Schlüssel nach Kap. 04 §4.4: Start, Ziel, Profil, Version. Die Version ist der Datenstand des Anbieters (z. B. Kachelstand der Karte),
damit ein Datenwechsel den Cache entwertet. Jede Antwort trägt deshalb data_version.
"""
import hashlib
from dataclasses import dataclass, field

PROFILES = ("auto", "pedestrian", "wheelchair")


class RoutingError(Exception):
    pass


class OutOfCoverage(RoutingError):
    """Start oder Ziel liegt außerhalb des Datenbestands des Anbieters."""


class NoRoute(RoutingError):
    """Im Datenbestand gibt es für das Profil keine Verbindung (z. B. Rollstuhl, nur Treppe)."""


class ProviderTimeout(RoutingError):
    """Anbieter antwortet nicht innerhalb der Frist; der Aufrufer muss mit Teilergebnis/Ablehnung umgehen (kein stilles Warten)."""


class ProviderUnavailable(RoutingError):
    pass


@dataclass
class RouteResult:
    duration_s: int
    distance_m: int
    data_version: str
    profile: str
    provider: str
    response_ms: float = 0.0
    notes: list = field(default_factory=list)


@dataclass
class MatrixResult:
    durations_s: list                 # [i][j] Sekunden oder None, wenn keine Verbindung
    data_version: str
    profile: str
    provider: str
    response_ms: float = 0.0


def cache_key(origin, dest, profile, data_version):
    """origin/dest = (lat, lon); Koordinaten auf 6 Nachkommastellen (~0,1 m) gerundet."""
    if profile not in PROFILES:
        raise ValueError(f"Profil {profile!r} unbekannt")
    raw = f"{origin[0]:.6f},{origin[1]:.6f}|{dest[0]:.6f},{dest[1]:.6f}|{profile}|{data_version}"
    return hashlib.sha256(raw.encode()).hexdigest()


class RoutingPort:
    name = "abstract"

    def data_version(self) -> str: raise NotImplementedError
    def route(self, origin, dest, profile="auto") -> RouteResult: raise NotImplementedError
    def matrix(self, origins, dests, profile="auto") -> MatrixResult: raise NotImplementedError


class TimeoutGuard(RoutingPort):
    """Erzwingt eine Frist je Aufruf unabhängig vom Anbieter (Timeout-Verhalten des Adapters). Der abgebrochene Aufruf läuft im Hintergrund weiter;
    das Ergebnis wird verworfen (der Aufrufer behandelt ProviderTimeout)."""

    def __init__(self, inner: RoutingPort, timeout_s: float):
        self.inner, self.timeout_s, self.name = inner, timeout_s, inner.name

    def _call(self, fn, *a):
        import threading
        box = {}

        def run():
            try:
                box["v"] = fn(*a)
            except BaseException as e:  # noqa: BLE001
                box["e"] = e
        t = threading.Thread(target=run, daemon=True)
        t.start()
        t.join(self.timeout_s)
        if t.is_alive():
            raise ProviderTimeout(f"{self.inner.name}: keine Antwort innerhalb {self.timeout_s} s")
        if "e" in box:
            raise box["e"]
        return box["v"]

    def data_version(self): return self._call(self.inner.data_version)
    def route(self, origin, dest, profile="auto"): return self._call(self.inner.route, origin, dest, profile)
    def matrix(self, origins, dests, profile="auto"): return self._call(self.inner.matrix, origins, dests, profile)
