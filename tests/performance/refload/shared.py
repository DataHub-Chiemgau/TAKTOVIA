"""Gemeinsamer, einmal je Prozess ausgeführter Referenzlauf für `test_referenzprofil.py` und `test_angebot_p95.py` (Modul-Singleton: unabhängig davon, unter welchem Namen unittest die Testmodule lädt)."""
from __future__ import annotations

import os
from pathlib import Path

from . import orchestrate, runner
from .prereq import require_app

PERF = Path(__file__).resolve().parents[1]
CACHE: dict = {}


def out_root() -> Path:
    return Path(os.environ.get("TAKTOVIA_PERF_OUT", PERF / "out"))


def reference_run() -> dict:
    """Der Lauf wird einmal je Prozess ausgeführt; Bericht und Rohdaten liegen unter `tests/performance/out/referenzlauf/` (nicht versioniert)."""
    if "rep" not in CACHE:
        require_app()
        p = runner.profile_from_json(PERF / "profiles" / "referenz-skalierung.json")
        scale = float(os.environ.get("TAKTOVIA_PERF_STAGE_SCALE", "0.4"))
        for s in p.stages:
            s.duration_s = max(10.0, s.duration_s * scale)
        p.warmup_s = max(5.0, p.warmup_s * scale)
        out = out_root() / "referenzlauf"
        CACHE["rep"] = orchestrate.execute_profile(p, out, title="Referenzlast und Skalierungstest (WP-M5-10)", race_stage="100 %", dataset_note="Testaufbau über API und SQL, kein Produktivbestand")
        CACHE["p"], CACHE["out"] = p, out
    return CACHE["rep"]
