"""Anbieterneutrale Routing-Schnittstelle (WP-M2-10, LH-6.5-OPT-007).

Vertrag: Start und Ziel jeder Anfrage sind definierte Haltepunkte (Haltepunktkoordinaten), nie frei
gesnappte Punkte. Jedes Ergebnis trägt Profil und
Kartendatenversion. Fehler sind definierte Ausnahmen mit stabilem Code; ein geschätztes Ergebnis
gibt es nie. Der Worker hat keinen Datenbankzugriff.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal, Protocol

Mode = Literal["car", "walk"]
Coordinate = tuple[float, float]  # (lat, lon), WGS84


@dataclass(frozen=True)
class Stop:
    """Definierter Haltepunkt mit Koordinate."""

    id: str
    lat: float
    lon: float


@dataclass(frozen=True)
class VehicleProfile:
    """Fahrzeugprofil als Parameter (Abmessungen/Restriktionen); Fußgänger haben keine Maße."""

    name: str
    mode: Mode
    height_m: float | None = None
    weight_t: float | None = None


class RoutingError(Exception):
    """Basis der definierten Fehler; ``code`` ist stabil und maschinenlesbar."""

    code = "ROUTING_ERROR"


class NotAStop(RoutingError):  # noqa: N818 - fachlicher Name
    code = "NOT_A_STOP"


class NoRoute(RoutingError):  # noqa: N818
    code = "NO_ROUTE"


class ProviderTimeout(RoutingError):  # noqa: N818
    code = "PROVIDER_TIMEOUT"


class ProviderUnavailable(RoutingError):  # noqa: N818
    code = "PROVIDER_UNAVAILABLE"


class UnknownProfile(RoutingError):  # noqa: N818
    code = "UNKNOWN_PROFILE"


@dataclass(frozen=True)
class RouteResult:
    origin: str
    destination: str
    time_s: int
    distance_m: int
    geometry: tuple[Coordinate, ...]
    profile: str
    map_version: str
    provider: str


@dataclass(frozen=True)
class Cell:
    time_s: int
    distance_m: int


@dataclass(frozen=True)
class MatrixResult:
    """``cells[i][j]`` gehört zu ``origins[i]`` -> ``destinations[j]``; ``None`` = nicht
    erreichbar (kein Schätzwert)."""

    origins: tuple[str, ...]
    destinations: tuple[str, ...]
    cells: tuple[tuple[Cell | None, ...], ...]
    profile: str
    map_version: str
    provider: str


class RoutingProvider(Protocol):
    """Was ein Kartenanbieter liefert. Orte kommen hier schon als geprüfte Haltepunkte an
    (Bindung leistet der ``RoutingAdapter``)."""

    name: str

    def map_version(self) -> str: ...

    def route(self, origin: Stop, destination: Stop, profile: VehicleProfile) -> RouteResult: ...

    def matrix(
        self, origins: list[Stop], destinations: list[Stop], profile: VehicleProfile
    ) -> MatrixResult: ...
