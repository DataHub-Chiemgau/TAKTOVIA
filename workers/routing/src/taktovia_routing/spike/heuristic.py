"""Einfügeheuristik (Spike): bestes zulässiges Paar (Pickup-, Dropoff-Position) je Fahrzeug.

Harte Constraints: Zeitfenster, Ressourcenvektor (Sitze, Rollstuhl), maximale Fahrzeit an Bord,
Pause (nur ohne Fahrgäste an Bord, im Pausenfenster), Schichtende. Zielfunktion: minimaler
Zuwachs der Fahrzeit (Leerweg/Fahrzeit), Gleichstand nach Fahrzeug-ID, Positionen.
Bestehende Zusagen werden nie verändert (Stops bleiben in Reihenfolge, nur Zeiten rutschen
innerhalb ihrer Fenster nach hinten). Geliefert wird nur ein vom unabhängigen Prüfer
validierter Plan, sonst eine begründete Ablehnung (Kap. 01 §1.4).
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field

from .model import BREAK, DROPOFF, PICKUP, Instance, Request, Stop, Vehicle
from .validator import validate_commitments, validate_route

NO_FEASIBLE = "NO_FEASIBLE_INSERTION"
TIMEOUT_NONE = "TIMEOUT_NO_VALIDATED_PLAN"
VALIDATION_FAILED = "VALIDATION_FAILED"


class RouteCache:
    """Stopfolge eines Fahrzeugs plus vorberechneter früheste-Start-Zeitplan."""

    __slots__ = (
        "vehicle", "stops", "node", "kind", "e", "l", "svc", "st", "fin", "loc_after",
        "pu_idx", "maxride", "ld_s", "ld_w", "real", "rb",
    )  # fmt: skip

    def __init__(self, inst: Instance, vehicle: Vehicle, stops: list[Stop]) -> None:
        self.vehicle = vehicle
        self.stops = stops
        self.refresh(inst)

    def refresh(self, inst: Instance) -> None:
        v, T, reqs = self.vehicle, inst.T, inst.requests
        n = len(self.stops)
        self.node = [0] * n
        self.kind = [0] * n
        self.e = [0] * n
        self.l = [0] * n
        self.svc = [0] * n
        self.st = [0] * n
        self.fin = [0] * n
        self.loc_after = [0] * n
        self.pu_idx = [-1] * n
        self.maxride = [0] * n
        self.ld_s = [0] * n
        self.ld_w = [0] * n
        self.rb = [0] * (n + 1)
        self.real = [v.depot]
        t, loc, ls, lw = v.shift_start, v.depot, 0, 0
        pidx: dict[int, int] = {}
        for k, s in enumerate(self.stops):
            self.kind[k] = s.kind
            self.rb[k] = len(self.real) - 1
            if s.kind == BREAK:
                self.e[k], self.l[k], self.svc[k] = v.break_e, v.break_l, v.break_dur
                self.node[k] = loc
                start = max(t, v.break_e)
            else:
                r = reqs[s.req]
                nd = r.pickup if s.kind == PICKUP else r.dropoff
                self.node[k] = nd
                if s.kind == PICKUP:
                    self.e[k], self.l[k] = r.pu_e, r.pu_l
                    pidx[r.id] = k
                    ls += r.seats
                    lw += r.wc
                else:
                    self.e[k], self.l[k] = r.do_e, r.do_l
                    self.pu_idx[k] = pidx[r.id]
                    self.maxride[k] = r.max_ride
                    ls -= r.seats
                    lw -= r.wc
                self.svc[k] = r.service
                start = max(t + T[loc][nd], self.e[k])
                loc = nd
                self.real.append(nd)
            self.st[k] = start
            self.fin[k] = start + self.svc[k]
            self.loc_after[k] = loc
            self.ld_s[k], self.ld_w[k] = ls, lw
            t = self.fin[k]
        self.rb[n] = len(self.real) - 1
        self._check_feasible(inst)

    def _check_feasible(self, inst: Instance) -> None:
        errs = validate_route(inst, self.vehicle, self.stops, self.st)
        if errs:
            raise ValueError(f"Fahrzeug {self.vehicle.id}: Route nicht zulässig: {errs[:3]}")


@dataclass(slots=True)
class InsertResult:
    accepted: bool
    reason: str = ""
    vehicle_id: int = -1
    pos_pickup: int = -1
    pos_dropoff: int = -1
    delta: int = 0
    stops: list[Stop] = field(default_factory=list)
    starts: list[int] = field(default_factory=list)
    elapsed_s: float = 0.0
    timed_out: bool = False
    vehicles_scanned: int = 0
    candidates: int = 0  # zulässige Einfügepaare
    steps: int = 0  # Prüfschritte (Vorlauf/Nachlauf), Aufwandsmaß
    matrix_lookups: int = 0  # Matrixzugriffe (aus Schritten geschätzt, siehe Bericht)


class FleetState:
    def __init__(self, inst: Instance) -> None:
        self.inst = inst
        self.routes: list[RouteCache] = []
        for v in inst.vehicles:
            stops = [Stop(BREAK, -1)] if v.break_dur > 0 else []
            self.routes.append(RouteCache(inst, v, stops))
        self.accepted = 0
        self.rejected = 0


def _best_in_route(
    inst: Instance, c: RouteCache, r: Request, ns: list[int], counters: list[int]
) -> tuple[int, int, int] | None:
    """Bestes zulässiges (delta, i, j) für Route ``c`` oder ``None``. Exakte Simulation."""
    T = inst.T
    v = c.vehicle
    L = len(c.stops)
    node, kind, e, l_, svc, st = c.node, c.kind, c.e, c.l, c.svc, c.st
    pu_idx, maxride, ld_s, ld_w, real, rb = c.pu_idx, c.maxride, c.ld_s, c.ld_w, c.real, c.rb
    P, D = r.pickup, r.dropoff
    rs, rw = r.seats, r.wc
    cap_s, cap_w = v.seats, v.wc
    shift_end = v.shift_end
    svcP, svcD = r.service, r.service
    best: tuple[int, int, int] | None = None
    steps = 0
    nreal = len(real)
    for i in range(L + 1):
        if i == 0:
            ft, loc, bs, bw = v.shift_start, v.depot, 0, 0
        else:
            ft, loc, bs, bw = c.fin[i - 1], c.loc_after[i - 1], ld_s[i - 1], ld_w[i - 1]
        if bs + rs > cap_s or bw + rw > cap_w:
            continue
        sP = ft + T[loc][P]
        if sP < r.pu_e:
            sP = r.pu_e
        steps += 1
        if sP > r.pu_l:
            continue
        t = sP + svcP
        lp = P
        ra = real[rb[i]]
        j = i
        while True:
            steps += 1
            sD = t + T[lp][D]
            if sD < r.do_e:
                sD = r.do_e
            if sD > r.do_l or sD - sP > r.max_ride:
                break
            # Nachlauf ab Stop j mit eingefügtem D
            ok = True
            t2, lq = sD + svcD, D
            ended = True
            for k in range(j, L):
                steps += 1
                if kind[k] == BREAK:
                    s = t2 if t2 > e[k] else e[k]
                    if s > l_[k]:
                        ok = False
                        break
                    t2 = s + svc[k]
                    ns[k] = s
                    continue
                s = t2 + T[lq][node[k]]
                if s < e[k]:
                    s = e[k]
                if s > l_[k]:
                    ok = False
                    break
                if kind[k] == DROPOFF:
                    pk = pu_idx[k]
                    if s - (ns[pk] if i <= pk else st[pk]) > maxride[k]:
                        ok = False
                        break
                ns[k] = s
                if s == st[k]:
                    ended = False
                    break
                t2 = s + svc[k]
                lq = node[k]
            if ok and ended and t2 > shift_end:
                ok = False
            if ok:
                if j == i:
                    delta = T[ra][P] + T[P][D]
                    if rb[i] + 1 < nreal:
                        nx = real[rb[i] + 1]
                        delta += T[D][nx] - T[ra][nx]
                else:
                    nx = real[rb[i] + 1]
                    delta = T[ra][P] + T[P][nx] - T[ra][nx]
                    b = rb[j]
                    rbn = real[b]
                    delta += T[rbn][D]
                    if b + 1 < nreal:
                        nx2 = real[b + 1]
                        delta += T[D][nx2] - T[rbn][nx2]
                cand = (delta, i, j)
                if best is None or cand < best:
                    best = cand
            # Vorlauf: Stop j mit P (ohne D) durchlaufen
            if j == L or kind[j] == BREAK:
                break
            if ld_s[j] + rs > cap_s or ld_w[j] + rw > cap_w:
                break
            s = t + T[lp][node[j]]
            if s < e[j]:
                s = e[j]
            steps += 1
            if s > l_[j]:
                break
            if kind[j] == DROPOFF:
                pk = pu_idx[j]
                if s - (ns[pk] if i <= pk else st[pk]) > maxride[j]:
                    break
            ns[j] = s
            t = s + svc[j]
            lp = node[j]
            j += 1
    counters[0] += steps
    return best


def insert_request(state: FleetState, r: Request, budget_s: float | None = None) -> InsertResult:
    """Sucht die beste zulässige Einfügung; ``budget_s`` ist ein hartes Zeitbudget."""
    t0 = time.perf_counter()
    deadline = None if budget_s is None else t0 + budget_s
    inst = state.inst
    ns = [0] * 4096
    counters = [0]
    best: tuple[int, int, int, int] | None = None  # delta, vehicle, i, j
    res = InsertResult(accepted=False)
    for c in state.routes:
        if deadline is not None and time.perf_counter() >= deadline:
            res.timed_out = True
            break
        v = c.vehicle
        if r.seats > v.seats or r.wc > v.wc:
            continue
        if len(ns) < len(c.stops) + 2:
            ns.extend([0] * (len(c.stops) + 2))
        res.vehicles_scanned += 1
        cand = _best_in_route(inst, c, r, ns, counters)
        if cand is not None:
            res.candidates += 1
            key = (cand[0], v.id, cand[1], cand[2])
            if best is None or key < best:
                best = key
    res.steps = counters[0]
    if best is None:
        res.reason = TIMEOUT_NONE if res.timed_out else NO_FEASIBLE
        res.elapsed_s = time.perf_counter() - t0
        return res
    delta, vid, i, j = best
    c = state.routes[vid]
    new_stops = list(c.stops)
    new_stops.insert(j, Stop(DROPOFF, r.id))
    new_stops.insert(i, Stop(PICKUP, r.id))
    inst.requests[r.id] = r
    try:
        trial = RouteCache(inst, c.vehicle, new_stops)  # Simulation + Prüfung (wirft bei Fehler)
        errs = validate_route(inst, c.vehicle, new_stops, trial.st)
        errs += validate_commitments(c.stops, new_stops)
    except ValueError:
        errs = ["Simulation unzulässig"]
    if errs:
        inst.requests.pop(r.id, None)
        res.reason = VALIDATION_FAILED
        res.elapsed_s = time.perf_counter() - t0
        return res
    res.accepted = True
    res.vehicle_id, res.pos_pickup, res.pos_dropoff, res.delta = vid, i, j, delta
    res.stops, res.starts = new_stops, list(trial.st)
    res.elapsed_s = time.perf_counter() - t0
    inst.requests.pop(r.id, None)  # Abfrage verändert den Zustand nicht; commit() trägt ein
    return res


def commit(state: FleetState, r: Request, res: InsertResult) -> None:
    """Übernimmt ein validiertes Ergebnis in den Zustand."""
    if not res.accepted:
        raise ValueError("nur akzeptierte Ergebnisse übernehmbar")
    inst = state.inst
    inst.requests[r.id] = r
    state.routes[res.vehicle_id] = RouteCache(inst, inst.vehicles[res.vehicle_id], res.stops)
    state.accepted += 1


def build_state(inst: Instance, requests: list[Request]) -> FleetState:
    """Baut den Ausgangszustand durch sequentielles Einfügen (ohne Zeitbudget)."""
    state = FleetState(inst)
    for r in requests:
        res = insert_request(state, r)
        if res.accepted:
            commit(state, r, res)
        else:
            state.rejected += 1
    return state


def total_drive_time(state: FleetState) -> int:
    inst = state.inst
    tot = 0
    for c in state.routes:
        for a, b in zip(c.real, c.real[1:], strict=False):
            tot += inst.T[a][b]
    return tot
