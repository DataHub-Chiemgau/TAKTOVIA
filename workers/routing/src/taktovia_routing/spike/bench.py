"""Messprotokoll Spike (b), WP-M0-27. Alle Zahlen im Bericht stammen aus diesem Lauf.

Aufruf: python -m taktovia_routing.spike.bench --out docs/spike-b-messwerte.json
Messgröße: ``InsertResult.elapsed_s`` = Wanduhrzeit (perf_counter) der lokalen Kandidatensuche
einschließlich Matrixzugriff (Matrix im Speicher) und der Validierung des gelieferten Plans.
"""

from __future__ import annotations

import argparse
import json
import multiprocessing as mp
import os
import platform
import random
import statistics
import sys
import time
from array import array
from typing import Any

from .generator import PROFILES, Profile, make_instance, make_requests
from .heuristic import (
    FleetState,
    InsertResult,
    build_state,
    insert_request,
    total_drive_time,
)
from .model import Instance, Request
from .validator import validate_commitments, validate_route

SEED = 20261111
N_VEHICLES = 250
BUDGET_S = 1.0
QUERIES = 200

FLEET_LADDER = [500, 1000, 2000]  # Profil "weit", 4 Aufträge je Fahrzeug
LADDER: dict[str, list[int]] = {
    "tag": [250, 500, 1000, 2000, 4000, 8000],
    "spitze": [250, 500, 1000, 2000, 4000],
    "weit": [250, 500, 1000, 2000, 4000, 8000],
}


def pct(xs: list[float], q: float) -> float:
    s = sorted(xs)
    k = max(0, min(len(s) - 1, round(q * (len(s) - 1))))
    return s[k]


def hardware() -> dict[str, Any]:
    model = ""
    with open("/proc/cpuinfo", encoding="utf-8") as f:
        for line in f:
            if line.startswith("model name"):
                model = line.split(":", 1)[1].strip()
                break
    return {
        "nproc": os.cpu_count(),
        "cpu_model": model,
        "python": sys.version.split()[0],
        "platform": platform.platform(),
    }


def check_result(state: FleetState, r: Request, res: InsertResult) -> int:
    """Unabhängige Nachprüfung eines gelieferten Plans; Rückgabe = Anzahl Verletzungen."""
    if not res.accepted:
        return 0
    inst = state.inst
    inst.requests[r.id] = r
    try:
        v = inst.vehicles[res.vehicle_id]
        errs = validate_route(inst, v, res.stops, res.starts)
        errs += validate_commitments(state.routes[res.vehicle_id].stops, res.stops)
        return len(errs)
    finally:
        inst.requests.pop(r.id, None)


def run_queries(state: FleetState, qs: list[Request], budget: float | None) -> list[InsertResult]:
    return [insert_request(state, q, budget) for q in qs]


class _Row:
    __slots__ = ("a", "row", "seen", "n")

    def __init__(self, a: int, row: array[int], seen: set[tuple[int, int]], n: list[int]) -> None:
        self.a, self.row, self.seen, self.n = a, row, seen, n

    def __getitem__(self, b: int) -> int:
        self.seen.add((self.a, b))
        self.n[0] += 1
        return self.row[b]


def lookup_cost_ns(inst: Instance) -> float:
    rng = random.Random(1)
    pairs = [(rng.randrange(inst.n_nodes), rng.randrange(inst.n_nodes)) for _ in range(500_000)]
    T = inst.T
    best = 1e9
    for _ in range(5):
        t0 = time.perf_counter()
        for a, b in pairs:
            T[a][b]
        t1 = time.perf_counter()
        for _a, _b in pairs:
            pass
        t2 = time.perf_counter()
        best = min(best, ((t1 - t0) - (t2 - t1)) / len(pairs) * 1e9)
    return best


def count_lookups(state: FleetState, qs: list[Request]) -> dict[str, float]:
    """Zählt Matrixzugriffe und eindeutige Knotenpaare je Anfrage (separater, ungemessener Lauf)."""
    real = state.inst
    seen: set[tuple[int, int]] = set()
    n = [0]
    rows = [_Row(a, row, seen, n) for a, row in enumerate(real.T)]
    counting = Instance(real.vehicles, rows, real.requests, real.seed, real.n_nodes)  # type: ignore[arg-type]
    state.inst = counting
    per_calls: list[int] = []
    per_unique: list[int] = []
    try:
        for q in qs:
            seen.clear()
            n[0] = 0
            insert_request(state, q)
            per_calls.append(n[0])
            per_unique.append(len(seen))
    finally:
        state.inst = real
    return {
        "lookups_mean": statistics.fmean(per_calls),
        "unique_pairs_mean": statistics.fmean(per_unique),
        "unique_pairs_max": float(max(per_unique)),
    }


def summarize(times: list[float]) -> dict[str, float]:
    return {
        "p50_ms": pct(times, 0.50) * 1e3,
        "p95_ms": pct(times, 0.95) * 1e3,
        "p99_ms": pct(times, 0.99) * 1e3,
        "max_ms": max(times) * 1e3,
        "mean_ms": statistics.fmean(times) * 1e3,
    }


def measure_instance(
    inst: Instance, profile: Profile, n_orders: int, cost_ns: float
) -> tuple[dict[str, Any], FleetState, list[Request]]:
    t0 = time.perf_counter()
    state = build_state(inst, make_requests(inst, n_orders, SEED + 1, 0, profile))
    build_s = time.perf_counter() - t0
    qs = make_requests(inst, QUERIES, SEED + 2, 10_000_000, profile)
    run_queries(state, qs[:20], None)  # Warmlauf (nicht gewertet)
    res = run_queries(state, qs, BUDGET_S)
    times = [x.elapsed_s for x in res]
    viol = sum(check_result(state, q, x) for q, x in zip(qs, res, strict=True))
    state_viol = 0
    for c in state.routes:
        state_viol += len(validate_route(inst, c.vehicle, c.stops, c.st))
    lk = count_lookups(state, qs[:40])
    mean_ms = statistics.fmean(times) * 1e3
    lookup_ms = lk["lookups_mean"] * cost_ns * 1e-6
    avg_len = statistics.fmean(len(c.stops) for c in state.routes)
    out = {
        "profile": profile.name,
        "vehicles": len(inst.vehicles),
        "orders_requested": n_orders,
        "orders_accepted": state.accepted,
        "orders_rejected": state.rejected,
        "avg_stops_per_route": avg_len,
        "max_stops_per_route": max(len(c.stops) for c in state.routes),
        "build_s": build_s,
        "queries": len(qs),
        "queries_accepted": sum(x.accepted for x in res),
        "queries_timed_out": sum(x.timed_out for x in res),
        "violations_plans": viol,
        "violations_state": state_viol,
        "steps_mean": statistics.fmean(x.steps for x in res),
        "vehicles_with_candidate_mean": statistics.fmean(x.candidates for x in res),
        "within_budget": all(t <= BUDGET_S for t in times),
        **summarize(times),
        **lk,
        "matrix_share_est": min(1.0, lookup_ms / mean_ms) if mean_ms else 0.0,
        "drive_time_total_s": total_drive_time(state),
    }
    return out, state, qs


_STATE: FleetState | None = None
_QS: list[Request] = []


def _par_worker(seed: int, barrier: Any, queue: Any) -> None:
    assert _STATE is not None
    qs = list(_QS)
    random.Random(seed).shuffle(qs)
    barrier.wait()
    queue.put([insert_request(_STATE, q, BUDGET_S).elapsed_s for q in qs])


def parallel_test(state: FleetState, qs: list[Request], ks: list[int]) -> list[dict[str, Any]]:
    """k Prozesse (fork, gemeinsamer Ausgangszustand) rechnen gleichzeitig dieselbe Anfrageliste."""
    global _STATE, _QS
    _STATE, _QS = state, qs
    ctx = mp.get_context("fork")
    out = []
    for k in ks:
        barrier = ctx.Barrier(k)
        queue = ctx.Queue()
        procs = [ctx.Process(target=_par_worker, args=(i, barrier, queue)) for i in range(k)]
        for p in procs:
            p.start()
        parts = [queue.get() for _ in procs]
        for p in procs:
            p.join()
        allt = [t for p in parts for t in p]
        out.append({"parallel": k, "n": len(allt), **summarize(allt)})
    return out


def timeout_test(state: FleetState, qs: list[Request]) -> list[dict[str, Any]]:
    out = []
    full = run_queries(state, qs, None)
    for budget in (0.00002, 0.0001, 0.0005, 0.001):
        res = run_queries(state, qs, budget)
        n_acc = sum(r.accepted for r in res)
        n_timeout_rej = sum(
            1 for r in res if not r.accepted and r.reason == "TIMEOUT_NO_VALIDATED_PLAN"
        )
        n_other_rej = sum(
            1 for r in res if not r.accepted and r.reason != "TIMEOUT_NO_VALIDATED_PLAN"
        )
        viol = sum(check_result(state, q, r) for q, r in zip(qs, res, strict=True))
        worse = [
            r.delta - f.delta
            for r, f in zip(res, full, strict=True)
            if r.accepted and f.accepted and r.delta > f.delta
        ]
        both = sum(1 for r, f in zip(res, full, strict=True) if r.accepted and f.accepted)
        out.append(
            {
                "budget_ms": budget * 1e3,
                "accepted": n_acc,
                "accepted_unlimited": sum(f.accepted for f in full),
                "rejected_timeout": n_timeout_rej,
                "rejected_other": n_other_rej,
                "timed_out": sum(r.timed_out for r in res),
                "violations": viol,
                "accepted_worse_than_unlimited": len(worse),
                "accepted_compared": both,
                "mean_extra_delta_s": statistics.fmean(worse) if worse else 0.0,
                "max_overshoot_ms": max(r.elapsed_s * 1e3 - budget * 1e3 for r in res),
            }
        )
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--quick", action="store_true", help="nur kleine Größen (Rauchtest)")
    args = ap.parse_args()
    inst = make_instance(SEED, N_VEHICLES)
    cost_ns = lookup_cost_ns(inst)
    result: dict[str, Any] = {
        "seed": SEED,
        "vehicles": N_VEHICLES,
        "budget_s": BUDGET_S,
        "queries_per_instance": QUERIES,
        "hardware": hardware(),
        "matrix_lookup_cost_ns": cost_ns,
        "instances": [],
    }
    keep: dict[str, tuple[FleetState, list[Request]]] = {}
    for name, ladder in LADDER.items():
        for n in ladder[:2] if args.quick else ladder:
            inst = make_instance(SEED, N_VEHICLES)  # frische Instanz je Zustand (requests-Dict)
            row, state, qs = measure_instance(inst, PROFILES[name], n, cost_ns)
            result["instances"].append(row)
            print(
                f"{name:7s} n={n:5d} acc={row['orders_accepted']:5d} p50={row['p50_ms']:.2f}ms "
                f"p95={row['p95_ms']:.2f}ms max={row['max_ms']:.2f}ms "
                f"viol={row['violations_plans']}",
                flush=True,
            )
            keep[f"{name}-{n}"] = (state, qs)
    fleet_rows = []
    for nv in () if args.quick else FLEET_LADDER:
        inst = make_instance(SEED, nv)
        row, _, _ = measure_instance(inst, PROFILES["weit"], 4 * nv, cost_ns)
        fleet_rows.append(row)
        print(
            f"flotte  v={nv:5d} n={4 * nv:5d} acc={row['orders_accepted']:5d} "
            f"p50={row['p50_ms']:.2f}ms p95={row['p95_ms']:.2f}ms max={row['max_ms']:.2f}ms "
            f"viol={row['violations_plans']}",
            flush=True,
        )
    result["fleet_scaling"] = fleet_rows
    ref = "weit-2000" if "weit-2000" in keep else "weit-500"
    state, qs = keep[ref]
    result["stress_reference"] = ref
    result["timeout_test"] = timeout_test(state, qs[:100])
    result["parallel_test"] = parallel_test(state, qs[:100], [1, 2, 4, 8])
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=1, ensure_ascii=False)
    print("geschrieben:", args.out)


if __name__ == "__main__":
    main()
