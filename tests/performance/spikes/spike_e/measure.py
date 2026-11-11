#!/usr/bin/env python3
"""Messwerkzeug Spike (e): Teststrecken und Matrixlatenz gegen einen RoutingPort.

  python tests/performance/spikes/spike_e/measure.py --provider grid [--reps 30]            # Referenzimplementierung (kein Anbieter)
  python tests/performance/spikes/spike_e/measure.py --provider valhalla --url http://host:8002 --data-version <Kartenstand>   # NICHT ausgeführt

Ergebnisse von --provider grid sind Messungen der Referenzimplementierung (rechnerisches Gitter) und sagen nichts über Valhalla oder andere Anbieter.
"""
import argparse
import json
import random
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from spike_e import HERE  # noqa: E402
from spike_e.grid_router import BBOX, SyntheticGridRouter  # noqa: E402
from spike_e.routing_port import NoRoute, OutOfCoverage, ProviderTimeout, ProviderUnavailable, RoutingError, cache_key  # noqa: E402
from perfkit import hardware  # noqa: E402
from perfkit.stats import summarize  # noqa: E402


def measure_routes(port, catalog=None):
    catalog = catalog or json.loads((HERE / "teststrecken.json").read_text())
    out = []
    for r in catalog["routes"]:
        t = time.perf_counter()
        rec = {"id": r["id"], "category": r["category"], "profile": r["profile"], "expect_error": r["expect_error"]}
        try:
            res = port.route(tuple(r["from"]), tuple(r["to"]), r["profile"])
            rec.update(duration_s=res.duration_s, distance_m=res.distance_m, data_version=res.data_version, error=None,
                       cache_key=cache_key(tuple(r["from"]), tuple(r["to"]), r["profile"], res.data_version))
            rec["deviation_vs_reference"] = None if r["reference_duration_s"] is None else round((res.duration_s - r["reference_duration_s"]) / r["reference_duration_s"], 4)
        except (OutOfCoverage, NoRoute, ProviderTimeout, ProviderUnavailable, RoutingError) as e:
            rec.update(error=type(e).__name__, duration_s=None, distance_m=None)
        rec["response_ms"] = round((time.perf_counter() - t) * 1000, 3)
        rec["behaviour_as_expected"] = (rec["error"] == r["expect_error"])
        out.append(rec)
    return out


def measure_matrix(port, reps=30, seed=1, sizes=None):
    spec = sizes or json.loads((HERE / "matrix-instanzen.json").read_text())
    lat0, lon0, lat1, lon1 = BBOX
    rnd = random.Random(seed)

    def pts(n):
        return [(round(rnd.uniform(lat0 + 0.01, lat1 - 0.01), 6), round(rnd.uniform(lon0 + 0.01, lon1 - 0.01), 6)) for _ in range(n)]
    res = {}
    for s in spec["sizes"]:
        vals = []
        for _ in range(reps):
            a, b = pts(s["sources"]), pts(s["targets"])
            t = time.perf_counter()
            m = port.matrix(a, b, "auto")
            vals.append((time.perf_counter() - t) * 1000)
            assert len(m.durations_s) == s["sources"] and len(m.durations_s[0]) == s["targets"]
        res[s["id"]] = dict(summarize(vals), sources=s["sources"], targets=s["targets"], cells=s["sources"] * s["targets"], within_budget_p95=summarize(vals)["p95_ms"] <= spec["budget_ms"])
    return {"reference_size_id": spec["reference_size_id"], "budget_ms": spec["budget_ms"], "sizes": res}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--provider", choices=["grid", "valhalla"], default="grid")
    ap.add_argument("--url")
    ap.add_argument("--data-version")
    ap.add_argument("--reps", type=int, default=30)
    ap.add_argument("--out", default=str(HERE.parent / "spike-e-data"))
    a = ap.parse_args(argv)
    if a.provider == "grid":
        port, label = SyntheticGridRouter(), "synthetisches Gitter (Referenzimplementierung, kein Anbieter)"
    else:
        if not a.url or not a.data_version:
            sys.exit("Valhalla: --url und --data-version angeben (nicht ausgeführt: kein Dienst verfügbar)")
        from spike_e.routing_port import TimeoutGuard
        from spike_e.valhalla_adapter import ValhallaAdapter
        port, label = TimeoutGuard(ValhallaAdapter(a.url, a.data_version), 5.0), "Valhalla"
    res = {"provider": label, "is_reference_implementation": a.provider == "grid", "hardware": hardware.collect(), "reps": a.reps,
           "routes": measure_routes(port), "matrix": measure_matrix(port, a.reps),
           "limitations": ["Referenzimplementierung: rechnerische Zeiten, keine Aussage über reale Routinganbieter"] if a.provider == "grid" else []}
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    f = out / f"messung-{a.provider}.json"
    f.write_text(json.dumps(res, indent=1, ensure_ascii=False, sort_keys=True) + "\n")
    print("->", f)
    return 0


if __name__ == "__main__":
    sys.exit(main())
