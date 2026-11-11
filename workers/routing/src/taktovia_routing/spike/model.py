"""Datenmodell des Spikes (Zeiten in ganzen Sekunden ab Planungsbeginn)."""

from __future__ import annotations

from array import array
from dataclasses import dataclass, field

PICKUP = 0
DROPOFF = 1
BREAK = 2


@dataclass(frozen=True, slots=True)
class Request:
    """Fahrtwunsch mit harten Zeitfenstern und Ressourcenvektor (Sitze, Rollstuhlplätze)."""

    id: int
    pickup: int  # Knotenindex in der Matrix
    dropoff: int
    pu_e: int  # frühester Pickup-Beginn
    pu_l: int  # spätester Pickup-Beginn
    do_e: int
    do_l: int  # spätester Dropoff-Beginn
    seats: int
    wc: int
    max_ride: int  # maximale Fahrzeit an Bord (Pickup-Beginn bis Dropoff-Beginn)
    service: int = 60


@dataclass(frozen=True, slots=True)
class Vehicle:
    id: int
    seats: int
    wc: int
    depot: int
    shift_start: int
    shift_end: int
    break_dur: int  # 0 = keine Pause
    break_e: int  # frühester Pausenbeginn
    break_l: int  # spätester Pausenbeginn


@dataclass(frozen=True, slots=True)
class Stop:
    kind: int  # PICKUP | DROPOFF | BREAK
    req: int  # Request-ID, -1 bei BREAK


@dataclass(slots=True)
class Instance:
    """Fahrzeuge, Anfragen und Fahrzeitmatrix. ``T[a][b]`` in Sekunden."""

    vehicles: list[Vehicle]
    T: list[array[int]]
    requests: dict[int, Request] = field(default_factory=dict)
    seed: int = 0
    n_nodes: int = 0
