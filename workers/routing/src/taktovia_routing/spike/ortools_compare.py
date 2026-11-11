"""OR-Tools-Vergleich (Spike): dieselbe Instanz als Routing-Modell mit Pickup/Dropoff.

Modell: Zeitdimension mit harten Fenstern, Sitz- und Rollstuhldimension (Ressourcenvektor),
maximale Fahrzeit an Bord, Schichtende. Bestehende Aufträge sind Pflicht (keine Disjunktion),
die neue Anfrage ist über eine Disjunktion mit hoher Strafe optional. Startlösung = bestehende
Routen. Pausen sind im OR-Tools-Modell NICHT abgebildet; der Vergleich läuft deshalb auf
Instanzen ohne Pause (``with_breaks=False``). Ergebnisse werden mit dem unabhängigen Prüfer
validiert.

Aufruf: python -m taktovia_routing.spike.ortools_compare --out docs/spike-b-ortools.json
"""

from __future__ import annotations

import argparse
import json
import statistics
import time
from typing import Any

import numpy as np
from ortools.constraint_solver import pywrapcp, routing_enums_pb2

from .bench import SEED, hardware, pct
from .generator import PROFILES, make_instance, make_requests
from .heuristic import FleetState, build_state, insert_request
from .model import DROPOFF, PICKUP, Instance, Request, Stop
from .validator import validate_route

HORIZON = 3 * 86400
PENALTY = 10_000_000


def _np_matrix(inst: Instance) -> np.ndarray:
    return np.array([np.frombuffer(row, dtype=np.int32) for row in inst.T], dtype=np.int64)


def solve_ortools(
    state: FleetState,
    new_req: Request,
    time_limit_s: float,
    vehicle_ids: list[int] | None = None,
    tnp: np.ndarray | None = None,
    metaheuristic: str = "GUIDED_LOCAL_SEARCH",
) -> dict[str, Any]:
    """Löst Bestandsplan + neue Anfrage als Ganzes (ggf. nur für ``vehicle_ids``)."""
    t_start = time.perf_counter()
    inst = state.inst
    tnp = _np_matrix(inst) if tnp is None else tnp
    vids = list(range(len(inst.vehicles))) if vehicle_ids is None else vehicle_ids
    V = len(vids)
    reqs: list[Request] = []
    for vid in vids:
        for s in state.routes[vid].stops:
            if s.kind == PICKUP:
                reqs.append(inst.requests[s.req])
    reqs.append(new_req)
    end_idx = V
    n_nodes = V + 1 + 2 * len(reqs)
    loc = [inst.vehicles[vid].depot for vid in vids] + [0]
    p_node: dict[int, int] = {}
    d_node: dict[int, int] = {}
    for r in reqs:
        p_node[r.id] = len(loc)
        loc.append(r.pickup)
        d_node[r.id] = len(loc)
        loc.append(r.dropoff)
    loc_a = np.array(loc)
    cost = tnp[np.ix_(loc_a, loc_a)].copy()
    cost[end_idx, :] = 0
    cost[:, end_idx] = 0
    svc = np.zeros(n_nodes, dtype=np.int64)
    for r in reqs:
        svc[p_node[r.id]] = r.service
        svc[d_node[r.id]] = r.service
    time_m = cost + svc[:, None]
    manager = pywrapcp.RoutingIndexManager(n_nodes, V, list(range(V)), [end_idx] * V)
    routing = pywrapcp.RoutingModel(manager)
    # Matrixindizes laufen über Knoten; RegisterTransitMatrix erwartet Knoten-Matrix
    cost_cb = routing.RegisterTransitMatrix(cost.tolist())
    routing.SetArcCostEvaluatorOfAllVehicles(cost_cb)
    time_cb = routing.RegisterTransitMatrix(time_m.tolist())
    routing.AddDimension(time_cb, HORIZON, HORIZON, False, "Time")
    tdim = routing.GetDimensionOrDie("Time")
    solver = routing.solver()
    for k, vid in enumerate(vids):
        v = inst.vehicles[vid]
        tdim.CumulVar(routing.Start(k)).SetRange(v.shift_start, v.shift_start)
        tdim.CumulVar(routing.End(k)).SetRange(v.shift_start, v.shift_end)
    seat_dem = [0] * n_nodes
    wc_dem = [0] * n_nodes
    for r in reqs:
        seat_dem[p_node[r.id]], seat_dem[d_node[r.id]] = r.seats, -r.seats
        wc_dem[p_node[r.id]], wc_dem[d_node[r.id]] = r.wc, -r.wc
    for name, dem, caps in (
        ("Seats", seat_dem, [inst.vehicles[v].seats for v in vids]),
        ("Wheel", wc_dem, [inst.vehicles[v].wc for v in vids]),
    ):
        cb = routing.RegisterUnaryTransitVector(dem)
        routing.AddDimensionWithVehicleCapacity(cb, 0, caps, True, name)
    for r in reqs:
        pi = manager.NodeToIndex(p_node[r.id])
        di = manager.NodeToIndex(d_node[r.id])
        tdim.CumulVar(pi).SetRange(r.pu_e, r.pu_l)
        tdim.CumulVar(di).SetRange(r.do_e, r.do_l)
        routing.AddPickupAndDelivery(pi, di)
        solver.Add(routing.VehicleVar(pi) == routing.VehicleVar(di))
        solver.Add(tdim.CumulVar(pi) <= tdim.CumulVar(di))
        solver.Add(tdim.CumulVar(di) - tdim.CumulVar(pi) <= r.max_ride)
        if r.id == new_req.id:
            routing.AddDisjunction([pi], PENALTY)
            routing.AddDisjunction([di], PENALTY)
    params = pywrapcp.DefaultRoutingSearchParameters()
    params.local_search_metaheuristic = getattr(
        routing_enums_pb2.LocalSearchMetaheuristic, metaheuristic
    )
    params.time_limit.FromMilliseconds(int(time_limit_s * 1000))
    routing.CloseModelWithParameters(params)
    init_routes = [
        [manager.NodeToIndex(p_node[s.req] if s.kind == PICKUP else d_node[s.req])
         for s in state.routes[vid].stops]
        for vid in vids
    ]  # fmt: skip
    init = routing.ReadAssignmentFromRoutes(init_routes, True)
    t_built = time.perf_counter()
    sol = routing.SolveFromAssignmentWithParameters(init, params) if init else None
    t_solved = time.perf_counter()
    out: dict[str, Any] = {
        "vehicles": V,
        "requests": len(reqs),
        "nodes": n_nodes,
        "build_s": t_built - t_start,
        "solve_s": t_solved - t_built,
        "total_s": t_solved - t_start,
        "init_assignment_ok": init is not None,
        "solution": sol is not None,
    }
    if sol is None:
        out["served_new"] = False
        return out
    inst.requests[new_req.id] = new_req
    viol = 0
    served = False
    drive = 0
    try:
        for k, vid in enumerate(vids):
            stops: list[Stop] = []
            starts: list[int] = []
            idx = sol.Value(routing.NextVar(routing.Start(k)))
            while not routing.IsEnd(idx):
                node = manager.IndexToNode(idx)
                rid = next(r.id for r in reqs if p_node[r.id] == node or d_node[r.id] == node)
                kind = PICKUP if p_node[rid] == node else DROPOFF
                stops.append(Stop(kind, rid))
                starts.append(sol.Value(tdim.CumulVar(idx)))
                idx = sol.Value(routing.NextVar(idx))
            if any(s.req == new_req.id for s in stops):
                served = True
            viol += len(validate_route(inst, inst.vehicles[vid], stops, starts))
            prev = inst.vehicles[vid].depot
            for s in stops:
                r = inst.requests[s.req]
                nd = r.pickup if s.kind == PICKUP else r.dropoff
                drive += inst.T[prev][nd]
                prev = nd
    finally:
        inst.requests.pop(new_req.id, None)
    out.update(served_new=served, violations=viol, drive_total_s=drive)
    return out


def nearest_vehicles(state: FleetState, r: Request, k: int) -> list[int]:
    """Vorauswahl: k Fahrzeuge mit kürzestem Weg von einem Routenknoten zum Pickup."""
    T = state.inst.T
    scored = []
    for c in state.routes:
        if r.seats > c.vehicle.seats or r.wc > c.vehicle.wc:
            continue
        best = min([T[c.vehicle.depot][r.pickup]] + [T[nd][r.pickup] for nd in c.node])
        scored.append((best, c.vehicle.id))
    scored.sort()
    return sorted(v for _, v in scored[:k])


def stat(xs: list[float]) -> dict[str, float]:
    return {"p50": pct(xs, 0.5), "p95": pct(xs, 0.95), "max": max(xs), "mean": statistics.fmean(xs)}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--quick", action="store_true")
    args = ap.parse_args()
    profile = PROFILES["weit"]
    res: dict[str, Any] = {
        "seed": SEED,
        "profile": profile.name,
        "hardware": hardware(),
        "note": "ohne Pausen (OR-Tools-Modell bildet Pausen nicht ab)",
        "cases": [],
    }
    plan: list[tuple[str, int, float, int, int | None]] = [
        # (Variante, Aufträge, Zeitlimit, Anzahl Anfragen, K)
        ("full-1s", 250, 1.0, 8, None),
        ("full-1s", 500, 1.0, 8, None),
        ("full-1s", 1000, 1.0, 5, None),
        ("full-10s", 250, 10.0, 3, None),
        ("subset10-10s", 1000, 10.0, 3, 10),
        ("subset10-1s", 250, 1.0, 8, 10),
        ("subset10-1s", 1000, 1.0, 8, 10),
        ("subset10-1s", 4000, 1.0, 8, 10),
    ]
    if args.quick:
        plan = plan[:1] + plan[4:5]
    for variant, n, limit, nq, k in plan:
        inst = make_instance(SEED, 250, with_breaks=False)
        state = build_state(inst, make_requests(inst, n, SEED + 1, 0, profile))
        qs = make_requests(inst, nq, SEED + 2, 10_000_000, profile)
        tnp = _np_matrix(inst)
        rows = []
        for q in qs:
            h = insert_request(state, q, 1.0)
            vids = nearest_vehicles(state, q, k) if k else None
            o = solve_ortools(state, q, limit, vids, tnp)
            o["heuristic_accepted"] = h.accepted
            o["heuristic_ms"] = h.elapsed_s * 1e3
            o["heuristic_delta_s"] = h.delta if h.accepted else None
            if "drive_total_s" in o:
                sel = vids if vids is not None else list(range(len(state.routes)))
                base = sum(
                    sum(
                        inst.T[a][b]
                        for a, b in zip(
                            state.routes[x].real, state.routes[x].real[1:], strict=False
                        )
                    )
                    for x in sel
                )
                o["ortools_delta_total_s"] = o["drive_total_s"] - base
            rows.append(o)
        agg: dict[str, Any] = {
            "variant": variant,
            "orders": n,
            "orders_accepted": state.accepted,
            "queries": nq,
            "time_limit_s": limit,
            "total_s": stat([r["total_s"] for r in rows]),
            "build_s": stat([r["build_s"] for r in rows]),
            "solve_s": stat([r["solve_s"] for r in rows]),
            "served_new": sum(1 for r in rows if r.get("served_new")),
            "heuristic_accepted": sum(1 for r in rows if r["heuristic_accepted"]),
            "violations": sum(r.get("violations", 0) for r in rows),
            "init_assignment_ok": sum(1 for r in rows if r["init_assignment_ok"]),
            "rows": rows,
        }
        res["cases"].append(agg)
        print(
            variant, n, f"total p50 {agg['total_s']['p50']:.2f}s max {agg['total_s']['max']:.2f}s",
            "served", agg["served_new"], "/", nq, "heur", agg["heuristic_accepted"],
            "viol", agg["violations"], flush=True,
        )  # fmt: skip
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(res, f, indent=1, ensure_ascii=False)


if __name__ == "__main__":
    main()
