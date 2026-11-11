"""Deterministischer synthetischer Instanzgenerator (fester Seed, nur ``random.Random``).

Annahmen (dokumentiert, da D-05 offen ist): Gebiet 24 x 24 km, 1200 Haltepunkte (60 % in
drei Clustern), Fahrzeit = 1,3 x Luftlinie bei 8 m/s, mind. 30 s. Fahrzeuge: 70 % Typ A
(6 Sitze, 0 Rollstuhlplätze), 30 % Typ B (4 Sitze, 2 Rollstuhlplätze); 30-min-Pause je
Fahrzeug. Anfragen: Pickup-Fenster 15 min, Wegeumweg-Faktor 1,5 + 5 min, 8 % Rollstuhl.
"""

from __future__ import annotations

import math
import random
from array import array
from dataclasses import dataclass

import numpy as np

from .model import Instance, Request, Vehicle

AREA_M = 24_000
N_NODES = 1200
SPEED_MPS = 8.0
DETOUR_FACTOR = 1.3
DAY_END = 9 * 3600  # späteste Pickup-Zeit
PU_WINDOW = 900
SERVICE = 60


def build_matrix(
    seed: int, n_nodes: int = N_NODES
) -> tuple[list[array[int]], list[tuple[float, float]]]:
    rng = random.Random(seed)
    centers = [(rng.uniform(0.2, 0.8) * AREA_M, rng.uniform(0.2, 0.8) * AREA_M) for _ in range(3)]
    coords: list[tuple[float, float]] = []
    for _ in range(n_nodes):
        if rng.random() < 0.6:
            cx, cy = rng.choice(centers)
            x = min(max(rng.gauss(cx, 2500), 0), AREA_M)
            y = min(max(rng.gauss(cy, 2500), 0), AREA_M)
        else:
            x, y = rng.uniform(0, AREA_M), rng.uniform(0, AREA_M)
        coords.append((x, y))
    arr = np.array(coords)
    d = np.hypot(arr[:, None, 0] - arr[None, :, 0], arr[:, None, 1] - arr[None, :, 1])
    t = np.maximum(30, np.rint(d * DETOUR_FACTOR / SPEED_MPS)).astype(np.int32)
    np.fill_diagonal(t, 0)
    rows: list[array[int]] = []
    for row in t:
        a: array[int] = array("i")
        a.frombytes(row.tobytes())
        rows.append(a)
    return rows, coords


def make_vehicles(
    seed: int, n_vehicles: int, n_nodes: int, with_breaks: bool = True
) -> list[Vehicle]:
    rng = random.Random(seed * 7919 + 1)
    out: list[Vehicle] = []
    for vid in range(n_vehicles):
        typ_b = rng.random() < 0.3
        start = rng.randrange(0, 4 * 3600, 900)
        length = rng.choice([8 * 3600, 10 * 3600])
        out.append(
            Vehicle(
                id=vid,
                seats=4 if typ_b else 6,
                wc=2 if typ_b else 0,
                depot=rng.randrange(n_nodes),
                shift_start=start,
                shift_end=start + length,
                break_dur=1800 if with_breaks else 0,
                break_e=start + 3 * 3600,
                break_l=start + 5 * 3600 + 1800,
            )
        )
    return out


@dataclass(frozen=True, slots=True)
class Profile:
    """Zeitliche Verteilung der Pickup-Zeiten (Sekunden ab Planungsbeginn) und Fensterbreite."""

    name: str
    t_lo: int
    t_hi: int
    window: int


DAY = Profile("tag", 600, DAY_END, PU_WINDOW)  # über den Tag verteilt, 15-min-Fenster
PEAK = Profile("spitze", 2 * 3600, 4 * 3600, 1200)  # Spitzenfenster 2 h, 20-min-Fenster
WIDE = Profile("weit", 3600, 5 * 3600, 3600)  # Stresslast: 60-min-Fenster, 4 h
PROFILES = {p.name: p for p in (DAY, PEAK, WIDE)}


def make_request(
    rng: random.Random, rid: int, T: list[array[int]], n_nodes: int, profile: Profile = DAY
) -> Request:
    for _ in range(100):
        p = rng.randrange(n_nodes)
        d = rng.randrange(n_nodes)
        if 300 <= T[p][d] <= 2400:
            break
    direct = T[p][d]
    pu_e = rng.randrange(profile.t_lo, profile.t_hi, 60)
    pu_l = pu_e + profile.window
    max_ride = int(direct * 1.5) + 300
    u = rng.random()
    if u < 0.08:
        wc, seats = 1, (1 if rng.random() < 0.3 else 0)
    else:
        wc = 0
        seats = 1 if u < 0.78 else (2 if u < 0.94 else 3)
    return Request(
        id=rid, pickup=p, dropoff=d, pu_e=pu_e, pu_l=pu_l, do_e=pu_e,
        do_l=pu_l + SERVICE + max_ride, seats=seats, wc=wc, max_ride=max_ride, service=SERVICE,
    )  # fmt: skip


def make_instance(seed: int, n_vehicles: int = 250, with_breaks: bool = True) -> Instance:
    T, _ = build_matrix(seed)
    return Instance(
        vehicles=make_vehicles(seed, n_vehicles, len(T), with_breaks), T=T, seed=seed,
        n_nodes=len(T),
    )  # fmt: skip


def make_requests(
    inst: Instance, n: int, seed: int, id_offset: int = 0, profile: Profile = DAY
) -> list[Request]:
    """Deterministische Anfragefolge; Seed und ``id_offset`` trennen Zustands- und Abfrageströme."""
    rng = random.Random(seed)
    return [make_request(rng, id_offset + k, inst.T, inst.n_nodes, profile) for k in range(n)]


def straight_line_m(a: tuple[float, float], b: tuple[float, float]) -> float:
    return math.hypot(a[0] - b[0], a[1] - b[1])
