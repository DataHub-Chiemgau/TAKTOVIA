"""Leere Schnittstelle des Valhalla-Adapters (WP-M0-07).

Die Implementierung folgt mit WP-M0-30 (Routingvergleich) bzw. M2. Hier steht nur der
Vertrag: eine Fahrzeitmatrix in Sekunden für Koordinatenlisten.
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Protocol

Coordinate = tuple[float, float]  # (lat, lon), WGS84


class MatrixProvider(Protocol):
    """Liefert Fahrzeiten in ganzen Sekunden; ``None`` steht für nicht erreichbar."""

    def travel_time_matrix(
        self, origins: Sequence[Coordinate], destinations: Sequence[Coordinate]
    ) -> list[list[int | None]]: ...


class ValhallaAdapter:
    """Platzhalter: nicht implementiert, bricht bewusst laut ab statt Werte zu erfinden."""

    def travel_time_matrix(
        self, origins: Sequence[Coordinate], destinations: Sequence[Coordinate]
    ) -> list[list[int | None]]:
        raise NotImplementedError("Valhalla-Adapter ist erst ab WP-M0-30 / M2 vorgesehen")
