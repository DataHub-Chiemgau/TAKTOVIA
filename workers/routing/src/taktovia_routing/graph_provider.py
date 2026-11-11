"""Referenzimplementierung auf einem Straßengraphen (WP-M2-10): Dijkstra über die Fixture
``strassengraph.json``.

KEIN Kartenanbieter: Sie beweist Port, Fehlerverhalten und Restriktionen (gesperrter Abschnitt,
Einbahnstraße, Höhenrestriktion, Fußweg) auf
konstruierten Daten und dient den Tests und der lokalen Entwicklung. Der Straßengraph ist
unveränderlich; ein Kartenstandwechsel tauscht den ganzen Zustand
atomar aus, eine Anfrage liest ihn genau einmal (nie gemischte Versionen).
"""

from __future__ import annotations

import heapq
from dataclasses import dataclass
from typing import Any

from .port import (
    Cell,
    Coordinate,
    MatrixResult,
    NoRoute,
    RouteResult,
    Stop,
    VehicleProfile,
)

_COORD_TOLERANCE = 1e-7


@dataclass(frozen=True)
class _Edge:
    id: str
    a: str
    b: str
    length_m: int
    time_s: int
    oneway: bool
    closed: bool
    modes: frozenset[str]
    max_height_m: float | None
    max_weight_t: float | None


@dataclass(frozen=True)
class _State:
    map_version: str
    walk_speed_mps: float
    coords: dict[str, Coordinate]
    edges: tuple[_Edge, ...]


def _state(graph: dict[str, Any]) -> _State:
    edges = tuple(
        _Edge(
            e["id"],
            e["from"],
            e["to"],
            int(e["length_m"]),
            int(e["time_s"]),
            bool(e["oneway"]),
            bool(e["closed"]),
            frozenset(e["modes"]),
            e.get("max_height_m"),
            e.get("max_weight_t"),
        )
        for e in graph["edges"]
    )
    coords = {n["id"]: (float(n["lat"]), float(n["lon"])) for n in graph["nodes"]}
    return _State(graph["map_version"], float(graph["walk_speed_mps"]), coords, edges)


class GraphProvider:
    name = "grid"

    def __init__(self, graph: dict[str, Any]) -> None:
        self._state = _state(graph)

    def load(self, graph: dict[str, Any]) -> None:
        """Neuer Kartenstand: ersetzt den gesamten Zustand in einer Zuweisung."""
        self._state = _state(graph)

    def map_version(self) -> str:
        return self._state.map_version

    # ---- Routenberechnung -------------------------------------------------------------------

    @staticmethod
    def _node_of(state: _State, stop: Stop) -> str:
        for node, (lat, lon) in state.coords.items():
            if abs(lat - stop.lat) <= _COORD_TOLERANCE and abs(lon - stop.lon) <= _COORD_TOLERANCE:
                return node
        raise NoRoute(f"Haltepunkt {stop.id} liegt auf keinem Knoten des Netzes")

    @staticmethod
    def _usable(edge: _Edge, profile: VehicleProfile) -> bool:
        if edge.closed or profile.mode not in edge.modes:
            return False
        if profile.mode == "car":
            if (
                profile.height_m is not None
                and edge.max_height_m is not None
                and profile.height_m > edge.max_height_m
            ):
                return False
            if (
                profile.weight_t is not None
                and edge.max_weight_t is not None
                and profile.weight_t > edge.max_weight_t
            ):
                return False
        return True

    def _adjacency(
        self, state: _State, profile: VehicleProfile
    ) -> dict[str, list[tuple[str, int, int, str]]]:
        adj: dict[str, list[tuple[str, int, int, str]]] = {}
        for e in state.edges:
            if not self._usable(e, profile):
                continue
            t = e.time_s if profile.mode == "car" else round(e.length_m / state.walk_speed_mps)
            adj.setdefault(e.a, []).append((e.b, t, e.length_m, e.id))
            if not (e.oneway and profile.mode == "car"):
                adj.setdefault(e.b, []).append((e.a, t, e.length_m, e.id))
        return adj

    @staticmethod
    def _dijkstra(
        adj: dict[str, list[tuple[str, int, int, str]]], source: str
    ) -> dict[str, tuple[int, int, str | None]]:
        """Kürzeste Zeiten ab ``source``: Knoten -> (Zeit, Distanz, Vorgänger). Bei
        Gleichstand entscheidet die Knoten-ID (deterministisch)."""
        best: dict[str, tuple[int, int, str | None]] = {}
        queue: list[tuple[int, int, str, str | None]] = [(0, 0, source, None)]
        while queue:
            t, d, node, prev = heapq.heappop(queue)
            if node in best:
                continue
            best[node] = (t, d, prev)
            for nxt, w, length, _ in adj.get(node, ()):
                if nxt not in best:
                    heapq.heappush(queue, (t + w, d + length, nxt, node))
        return best

    def route(self, origin: Stop, destination: Stop, profile: VehicleProfile) -> RouteResult:
        state = self._state  # genau einmal lesen: ein Kartenstand je Anfrage
        a, b = self._node_of(state, origin), self._node_of(state, destination)
        best = self._dijkstra(self._adjacency(state, profile), a)
        if b not in best:
            raise NoRoute(f"kein Weg von {origin.id} nach {destination.id}")
        path: list[str] = []
        node: str | None = b
        while node is not None:
            path.append(node)
            node = best[node][2]
        path.reverse()
        geometry = tuple(state.coords[n] for n in path)
        # Start und Ziel sind die Haltepunktkoordinaten selbst
        geometry = ((origin.lat, origin.lon), *geometry[1:-1], (destination.lat, destination.lon))
        if len(path) == 1:
            geometry = ((origin.lat, origin.lon), (destination.lat, destination.lon))
        return RouteResult(
            origin.id,
            destination.id,
            best[b][0],
            best[b][1],
            geometry,
            profile.name,
            state.map_version,
            self.name,
        )

    def matrix(
        self, origins: list[Stop], destinations: list[Stop], profile: VehicleProfile
    ) -> MatrixResult:
        state = self._state
        adj = self._adjacency(state, profile)
        dest_nodes = [self._node_of(state, s) for s in destinations]
        rows: list[tuple[Cell | None, ...]] = []
        for o in origins:
            best = self._dijkstra(adj, self._node_of(state, o))
            rows.append(
                tuple(Cell(best[n][0], best[n][1]) if n in best else None for n in dest_nodes)
            )
        return MatrixResult(
            tuple(s.id for s in origins),
            tuple(s.id for s in destinations),
            tuple(rows),
            profile.name,
            state.map_version,
            self.name,
        )
