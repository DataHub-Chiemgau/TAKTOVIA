#!/usr/bin/env python3
"""Probelauf Valhalla (lokal, synthetisches Netz): Adapter gegen echte Valhalla-Antworten, konstruierte Restriktionsfälle, Matrixlatenz.

  <Python mit pyvalhalla> tests/performance/spikes/spike_e/measure_valhalla.py [--work DIR] [--reps 20] [--out DIR]

Ergebnis ist ein Probelauf auf einem künstlichen Netz (siehe valhalla_local.py), KEIN Anbietervergleich und keine Aussage über eine reale Region.
"""
import argparse
import json
import platform
import random
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from spike_e import HERE, osm_pbf, valhalla_local  # noqa: E402
from spike_e.routing_port import NoRoute, OutOfCoverage, RoutingError  # noqa: E402
from spike_e.valhalla_adapter import ValhallaAdapter  # noqa: E402
from perfkit import hardware  # noqa: E402
from perfkit.stats import summarize  # noqa: E402


def run_routes(adapter, catalog, loose_adapter=None):
    res = {}
    for r in catalog["routes"]:
        rec = {"id": r["id"], "category": r["category"], "profile": r["profile"], "expect": r["expect"]}
        t = time.perf_counter()
        try:
            x = (loose_adapter if (r.get("strict_snapping") is False and loose_adapter) else adapter).route(tuple(r["from"]), tuple(r["to"]), r["profile"])
            rec.update(error=None, duration_s=x.duration_s, distance_m=x.distance_m)
        except RoutingError as e:
            rec.update(error=type(e).__name__, duration_s=None, distance_m=None)
        rec["response_ms"] = round((time.perf_counter() - t) * 1000, 2)
        res[r["id"]] = rec
    for rec in res.values():
        e = rec["expect"]
        if e == "route":
            rec["ok"] = rec["error"] is None
        elif e.startswith("error:"):
            rec["ok"] = rec["error"] == e.split(":", 1)[1]
        elif e.startswith("umweg_laenger_als:"):
            other = res[e.split(":", 1)[1]]
            rec["ok"] = rec["error"] is None and other["error"] is None and rec["distance_m"] > other["distance_m"]
            rec["compared_with"] = {"id": other["id"], "distance_m": other["distance_m"], "duration_s": other["duration_s"]}
        elif e.startswith("laenge_km_mindestens:"):
            rec["ok"] = rec["error"] is None and rec["distance_m"] >= float(e.split(":", 1)[1]) * 1000
    return list(res.values())


def run_matrix(adapter, actor, reps, seed=1):
    spec = json.loads((HERE / "matrix-instanzen.json").read_text())
    g = osm_pbf.GRID
    lat0, lon0 = g["lat0"] + 0.002, g["lon0"] + 0.002
    lat1, lon1 = g["lat0"] + g["rows"] * g["dlat"] - 0.002, g["lon0"] + g["cols"] * g["dlon"] - 0.002
    rnd = random.Random(seed)
    pts = lambda n: [{"lat": round(rnd.uniform(lat0, lat1), 6), "lon": round(rnd.uniform(lon0, lon1), 6)} for _ in range(n)]  # noqa: E731
    out = {}
    for s in spec["sizes"]:
        cells = s["sources"] * s["targets"]
        n_reps = reps if cells <= 1000 else (max(5, reps // 2) if cells <= 15000 else 3)
        eng, via = [], []
        for _ in range(n_reps):
            a, b = pts(s["sources"]), pts(s["targets"])
            body = json.dumps({"sources": a, "targets": b, "costing": "auto"})
            t = time.perf_counter()
            actor.matrix(body)
            eng.append((time.perf_counter() - t) * 1000)
            t = time.perf_counter()
            adapter.matrix([(p["lat"], p["lon"]) for p in a], [(p["lat"], p["lon"]) for p in b], "auto")
            via.append((time.perf_counter() - t) * 1000)
        out[s["id"]] = {"cells": s["sources"] * s["targets"], "reps": n_reps, "engine_only_ms": summarize(eng), "via_adapter_http_ms": summarize(via),
                        "within_budget_p95_via_adapter": summarize(via)["p95_ms"] <= spec["budget_ms"]}
    return {"reference_size_id": spec["reference_size_id"], "budget_ms": spec["budget_ms"], "sizes": out}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--work", default="/tmp/taktovia-valhalla-work")
    ap.add_argument("--reps", type=int, default=20)
    ap.add_argument("--out", default=str(HERE.parent / "spike-e-data"))
    ap.add_argument("--routes-only", action="store_true", help="nur die Strecken neu messen und in eine vorhandene Ergebnisdatei übernehmen (Matrix bleibt)")
    a = ap.parse_args(argv)
    import valhalla
    cfg, build_s, pbf_bytes = valhalla_local.build(Path(a.work))
    actor = valhalla_local.make_actor(cfg)
    app = valhalla_local.serve(actor)
    app.start()
    try:
        adapter = ValhallaAdapter(app.url, "synthetic-grid-120x120-v1", timeout_s=300)
        loose = ValhallaAdapter(app.url, "synthetic-grid-120x120-v1", timeout_s=300, strict_snapping=False)
        catalog = json.loads((HERE / "teststrecken-valhalla-synthetisch.json").read_text())
        if a.routes_only:
            f = Path(a.out) / "messung-valhalla-synthetisch.json"
            res = json.loads(f.read_text())
            res["routes"] = run_routes(adapter, catalog, loose)
            res["routes_rerun_note"] = "Strecken nach Korrektur des Katalogs (V04) und des Snappings (V08) neu gemessen; Matrixmessung aus dem früheren Lauf"
            f.write_text(json.dumps(res, indent=1, ensure_ascii=False, sort_keys=True) + "\n")
            bad = [r["id"] for r in res["routes"] if not r["ok"]]
            print("->", f)
            print("Strecken wie erwartet:", len(res["routes"]) - len(bad), "von", len(res["routes"]), ("; abweichend: " + ", ".join(bad)) if bad else "")
            return 0
        res = {"label": "Valhalla lokal auf synthetischem Gitternetz (Probelauf, kein Anbietervergleich)", "pyvalhalla": getattr(valhalla, "__version__", None) or "3.9.1",
               "python": platform.python_version(), "hardware": hardware.collect(), "network": catalog["network"], "tile_build_s": round(build_s, 2), "pbf_bytes": pbf_bytes,
               "routes": run_routes(adapter, catalog, loose), "matrix": run_matrix(adapter, actor, a.reps),
               "limitations": ["künstliches Netz mit ca. 14.400 Knoten, keine reale Region, keine reale Datenqualität", "Engine im selben Prozess, ein Aufruf zur Zeit (keine Parallelität gemessen)",
                               "HTTP-Wrapper statt Valhalla-HTTP-Server", "Lizenz-, Betriebs- und Kostenkriterien nicht bewertet"]}
    finally:
        app.stop()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    f = out / "messung-valhalla-synthetisch.json"
    f.write_text(json.dumps(res, indent=1, ensure_ascii=False, sort_keys=True) + "\n")
    print("->", f)
    bad = [r["id"] for r in res["routes"] if not r["ok"]]
    print("Strecken wie erwartet:", len(res["routes"]) - len(bad), "von", len(res["routes"]), ("; abweichend: " + ", ".join(bad)) if bad else "")
    return 0


if __name__ == "__main__":
    sys.exit(main())
