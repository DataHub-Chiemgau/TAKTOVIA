"""Routingadapter (WP-M2-10): bindet Orte an Haltepunkte, ruft einen Anbieter mit Zeitbudget und
cached mandantengebunden.

Regeln: (1) Start und Ziel müssen definierte Haltepunkte sein (Katalog); freie Koordinaten werden
mit ``NotAStop`` abgewiesen. (2) Zeitbudget je Aufruf; bei
Überschreitung ``ProviderTimeout``, bei Ausfall ``ProviderUnavailable``, bei fehlendem Weg
``NoRoute``: nie ein geschätztes Ergebnis, nie ein Cache-Eintrag
aus einem Fehler. (3) Jedes Ergebnis trägt Profil und Kartendatenversion; ein Ergebnis mit anderer
Version als erwartet wird verworfen. (4) Der Cache-Schlüssel
enthält den Mandanten.
"""

from __future__ import annotations

import concurrent.futures
from collections.abc import Callable, Mapping, Sequence
from typing import TypeVar

from .cache import CacheEntry, CacheKey, InMemoryRouteCache, RouteCache
from .port import (
    Cell,
    Coordinate,
    MatrixResult,
    NotAStop,
    ProviderTimeout,
    ProviderUnavailable,
    RouteResult,
    RoutingProvider,
    Stop,
    UnknownProfile,
    VehicleProfile,
)

T = TypeVar("T")
DEFAULT_TIMEOUT_S = 0.8  # innerhalb der 1 s lokalen Kandidatensuche (Planungsdefault Kap. 01 §1.4)
_STOP_TOLERANCE = 1e-7


class RoutingAdapter:
    def __init__(
        self,
        provider: RoutingProvider,
        stops: Mapping[str, Stop],
        profiles: Mapping[str, VehicleProfile],
        cache: RouteCache | None = None,
        timeout_s: float = DEFAULT_TIMEOUT_S,
    ) -> None:
        self._provider = provider
        self._stops = dict(stops)
        self._profiles = dict(profiles)
        self._cache: RouteCache = cache if cache is not None else InMemoryRouteCache()
        self._timeout = timeout_s
        self._pool = concurrent.futures.ThreadPoolExecutor(
            max_workers=8, thread_name_prefix="route"
        )

    # ---- Bindung ------------------------------------------------------------------------------

    def stop(self, stop_id: str) -> Stop:
        try:
            return self._stops[stop_id]
        except KeyError:
            raise NotAStop(f"unbekannter Haltepunkt {stop_id!r}") from None

    def stop_at(self, point: Coordinate) -> Stop:
        """Nur exakte Haltepunktkoordinaten: kein Snapping auf irgendeinen nahen Punkt oder
        die Straße."""
        for s in self._stops.values():
            if (
                abs(s.lat - point[0]) <= _STOP_TOLERANCE
                and abs(s.lon - point[1]) <= _STOP_TOLERANCE
            ):
                return s
        raise NotAStop("Koordinate ist kein definierter Haltepunkt")

    def profile(self, name: str) -> VehicleProfile:
        try:
            return self._profiles[name]
        except KeyError:
            raise UnknownProfile(f"unbekanntes Profil {name!r}") from None

    # ---- Aufruf mit Zeitbudget ----------------------------------------------------------------

    def _call(self, fn: Callable[[], T]) -> T:
        future = self._pool.submit(fn)
        try:
            return future.result(timeout=self._timeout)
        except concurrent.futures.TimeoutError:
            future.cancel()
            raise ProviderTimeout(
                f"Kartenquelle antwortet nicht innerhalb {self._timeout} s"
            ) from None

    def _check_version(self, got: str, expected: str) -> None:
        if got != expected:
            # Kartenstand wechselte zwischen Versionsabfrage und Ergebnis: nicht
            # mischen, kein Ergebnis
            raise ProviderUnavailable(
                f"Kartenstand wechselte ({expected} -> {got}), bitte wiederholen"
            )

    # ---- Operationen --------------------------------------------------------------------------

    def route(
        self, tenant_id: str, origin_id: str, destination_id: str, profile_name: str
    ) -> RouteResult:
        o, d, p = self.stop(origin_id), self.stop(destination_id), self.profile(profile_name)
        result = self._call(lambda: self._provider.route(o, d, p))
        if result.profile != p.name:
            raise ProviderUnavailable("Anbieterergebnis trägt ein anderes Profil")
        self._store(tenant_id, o, d, p, result.map_version, Cell(result.time_s, result.distance_m))
        return result

    def walk(self, tenant_id: str, origin_id: str, destination_id: str) -> RouteResult:
        return self.route(tenant_id, origin_id, destination_id, "walk")

    def route_points(
        self, tenant_id: str, origin: Coordinate, destination: Coordinate, profile_name: str
    ) -> RouteResult:
        """Wie ``route``, aber mit Koordinaten: nur zulässig, wenn beide Koordinaten exakt
        Haltepunkte sind."""
        return self.route(
            tenant_id, self.stop_at(origin).id, self.stop_at(destination).id, profile_name
        )

    def matrix(
        self,
        tenant_id: str,
        origin_ids: Sequence[str],
        destination_ids: Sequence[str],
        profile_name: str,
    ) -> MatrixResult:
        """n x m Fahrzeiten. Zellen aus dem Cache nur mit der aktuellen Kartenversion;
        fehlende Zellen rechnet der Anbieter in einem Aufruf (ein Kartenstand)."""
        os_ = [self.stop(i) for i in origin_ids]
        ds = [self.stop(i) for i in destination_ids]
        p = self.profile(profile_name)
        version = self._provider.map_version()
        cells: list[list[Cell | None]] = []
        complete = True
        for o in os_:
            row: list[Cell | None] = []
            for d in ds:
                hit = self._lookup(tenant_id, o, d, p, version)
                row.append(hit)
                complete = complete and hit is not None
            cells.append(row)
        if complete:
            return MatrixResult(
                tuple(origin_ids),
                tuple(destination_ids),
                tuple(tuple(r) for r in cells),
                p.name,
                version,
                "cache",
            )
        fresh = self._call(lambda: self._provider.matrix(os_, ds, p))
        self._check_version(fresh.map_version, version)
        for i, o in enumerate(os_):
            for j, d in enumerate(ds):
                cell = fresh.cells[i][j]
                if cell is not None:  # "nicht erreichbar" wird nicht gecacht
                    self._store(tenant_id, o, d, p, fresh.map_version, cell)
        return fresh

    # ---- Cache --------------------------------------------------------------------------------

    def _lookup(
        self, tenant_id: str, o: Stop, d: Stop, p: VehicleProfile, version: str
    ) -> Cell | None:
        key = CacheKey(tenant_id, o.id, d.id, p.name, version)
        entry = self._cache.get(key)
        if entry is None:
            return None
        if entry.map_version != version:  # Treffer mit anderer Kartenversion: verwerfen
            if isinstance(self._cache, InMemoryRouteCache):
                self._cache.drop(key)
            return None
        return entry.cell

    def _store(
        self, tenant_id: str, o: Stop, d: Stop, p: VehicleProfile, version: str, cell: Cell
    ) -> None:
        self._cache.put(CacheKey(tenant_id, o.id, d.id, p.name, version), CacheEntry(cell, version))

    def cached_route(
        self, tenant_id: str, origin_id: str, destination_id: str, profile_name: str
    ) -> Cell | None:
        """Cache-Lesezugriff mit aktueller Kartenversion (für Tests und den Kern)."""
        return self._lookup(
            tenant_id,
            self.stop(origin_id),
            self.stop(destination_id),
            self.profile(profile_name),
            self._provider.map_version(),
        )

    def map_version(self) -> str:
        return self._provider.map_version()

    def close(self) -> None:
        self._pool.shutdown(wait=False, cancel_futures=True)
