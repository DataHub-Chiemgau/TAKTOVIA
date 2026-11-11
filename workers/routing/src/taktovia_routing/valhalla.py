"""Valhalla-Adapter (WP-M2-10): ``/route`` und ``/sources_to_targets`` über HTTP, Fehlerabbildung
auf die definierten Routingfehler.

GEGEN EINEN ECHTEN VALHALLA-DIENST NICHT AUSGEFÜHRT (kein Docker, kein Kartenextrakt): der
Vertragstest läuft gegen einen lokalen Stub mit den dokumentierten
Antwortformen (Spike (e), ``tests/performance/spikes/spike_e``). Orte werden streng gebunden
(``minimum_reachability`` 0, kleiner Radius), damit ein nicht
angebundener Haltepunkt als ``NoRoute`` endet statt still mit dem nächsten Netz verbunden zu werden
(Befund aus Spike (e)). Die Kartendatenversion kommt aus der
Konfiguration (Valhalla nennt sie nicht in jeder Antwort).
"""

from __future__ import annotations

import contextlib
import json
import urllib.error
import urllib.request
from typing import Any

from .port import (
    Cell,
    MatrixResult,
    NoRoute,
    ProviderTimeout,
    ProviderUnavailable,
    RouteResult,
    Stop,
    VehicleProfile,
)

NO_PATH_CODES = {442, 443, 171, 170, 172}


class ValhallaAdapter:
    name = "valhalla"

    def __init__(
        self, base_url: str, data_version: str, timeout_s: float = 2.0, snap_radius_m: int = 100
    ) -> None:
        self._base = base_url.rstrip("/")
        self._version = data_version
        self._timeout = timeout_s
        self._radius = snap_radius_m

    def map_version(self) -> str:
        return self._version

    def _post(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        req = urllib.request.Request(  # noqa: S310 - Basis-URL kommt aus der Betreiberkonfiguration
            self._base + path,
            data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=self._timeout) as resp:  # noqa: S310
                data: dict[str, Any] = json.load(resp)
                return data
        except urllib.error.HTTPError as e:
            payload: dict[str, Any] = {}
            with contextlib.suppress(ValueError, OSError):
                payload = json.load(e)
            if e.code >= 500 and payload.get("error_code") not in NO_PATH_CODES:
                raise ProviderUnavailable(f"HTTP {e.code}") from e
            if payload.get("error_code") in NO_PATH_CODES or "No path" in str(payload.get("error")):
                raise NoRoute(str(payload.get("error", "kein Weg"))) from e
            raise ProviderUnavailable(f"HTTP {e.code}") from e
        except TimeoutError as e:
            raise ProviderTimeout(str(e)) from e
        except urllib.error.URLError as e:
            if isinstance(e.reason, TimeoutError):
                raise ProviderTimeout(str(e.reason)) from e
            raise ProviderUnavailable(str(e.reason)) from e

    def _loc(self, stop: Stop) -> dict[str, Any]:
        return {
            "lat": stop.lat,
            "lon": stop.lon,
            "minimum_reachability": 0,
            "radius": self._radius,
        }

    @staticmethod
    def _costing(profile: VehicleProfile) -> tuple[str, dict[str, Any]]:
        if profile.mode == "walk":
            return "pedestrian", {}
        opts: dict[str, Any] = {}
        if profile.height_m is not None:
            opts["height"] = profile.height_m
        if profile.weight_t is not None:
            opts["weight"] = profile.weight_t
        return "auto", {"auto": opts} if opts else {}

    def route(self, origin: Stop, destination: Stop, profile: VehicleProfile) -> RouteResult:
        costing, options = self._costing(profile)
        body: dict[str, Any] = {
            "locations": [self._loc(origin), self._loc(destination)],
            "costing": costing,
            "units": "kilometers",
        }
        if options:
            body["costing_options"] = options
        data = self._post("/route", body)
        summary = data["trip"]["summary"]
        return RouteResult(
            origin.id,
            destination.id,
            round(summary["time"]),
            round(summary["length"] * 1000),
            ((origin.lat, origin.lon), (destination.lat, destination.lon)),
            profile.name,
            self._version,
            self.name,
        )

    def matrix(
        self, origins: list[Stop], destinations: list[Stop], profile: VehicleProfile
    ) -> MatrixResult:
        costing, options = self._costing(profile)
        body: dict[str, Any] = {
            "sources": [self._loc(s) for s in origins],
            "targets": [self._loc(s) for s in destinations],
            "costing": costing,
        }
        if options:
            body["costing_options"] = options
        data = self._post("/sources_to_targets", body)
        rows: list[tuple[Cell | None, ...]] = []
        for row in data["sources_to_targets"]:
            rows.append(
                tuple(
                    Cell(round(c["time"]), round(c["distance"] * 1000))
                    if c.get("time") is not None
                    else None
                    for c in row
                )
            )
        return MatrixResult(
            tuple(s.id for s in origins),
            tuple(s.id for s in destinations),
            tuple(rows),
            profile.name,
            self._version,
            self.name,
        )
