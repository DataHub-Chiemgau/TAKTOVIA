"""Lädt die Referenzregion (``tests/acceptance/fixtures/m2_referenzregion``, WP-M2-02) für die
Routingtests."""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any

from taktovia_routing.port import Stop, VehicleProfile

DIR = (
    Path(__file__).resolve().parents[3] / "tests" / "acceptance" / "fixtures" / "m2_referenzregion"
)
TENANT_A = "00000000-0000-4000-8000-00000000000a"
TENANT_B = "00000000-0000-4000-8000-00000000000b"


def _json(name: str) -> dict[str, Any]:
    data: dict[str, Any] = json.loads((DIR / name).read_text(encoding="utf-8"))
    return data


def graph() -> dict[str, Any]:
    return _json("strassengraph.json")


def graph_without_closure(map_version: str = "referenz-2026-12-01") -> dict[str, Any]:
    """Neuer Kartenstand, in dem die Sperre e23 aufgehoben ist."""
    g = copy.deepcopy(graph())
    g["map_version"] = map_version
    for e in g["edges"]:
        if e["id"] == "e23":
            e["closed"] = False
    return g


def expectations() -> dict[str, dict[str, Any]]:
    return {r["id"]: r for r in _json("erwartete-fahrzeiten.json")["relationen"]}


def stops() -> dict[str, Stop]:
    return {s["id"]: Stop(s["id"], s["lat"], s["lon"]) for s in _json("region.json")["stops"]}


def profiles() -> dict[str, VehicleProfile]:
    out: dict[str, VehicleProfile] = {}
    for name, p in graph()["profiles"].items():
        out[name] = VehicleProfile(name, p["mode"], p.get("height_m"), p.get("weight_t"))
    return out
