"""WP-M0-27: Tests der Einfügeheuristik (positiv, negativ, Timeout, Parallelität, Prüfer)."""

from __future__ import annotations

import random
from array import array
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace

import pytest

from taktovia_routing.spike.generator import PROFILES, make_instance, make_requests
from taktovia_routing.spike.heuristic import (
    NO_FEASIBLE,
    TIMEOUT_NONE,
    FleetState,
    RouteCache,
    build_state,
    commit,
    insert_request,
)
from taktovia_routing.spike.model import BREAK, DROPOFF, PICKUP, Instance, Request, Stop, Vehicle
from taktovia_routing.spike.validator import validate_commitments, validate_route


def line_instance(vehicles: list[Vehicle], n: int = 12) -> Instance:
    """Knoten auf einer Linie, Fahrzeit 100 s je Knotenabstand."""
    T = []
    for a in range(n):
        row: array[int] = array("i", [abs(a - b) * 100 for b in range(n)])
        T.append(row)
    return Instance(vehicles=vehicles, T=T, n_nodes=n)


def veh(vid: int = 0, seats: int = 4, wc: int = 0, depot: int = 0, brk: int = 0) -> Vehicle:
    return Vehicle(vid, seats, wc, depot, 0, 36000, brk, 3600, 7200)


def req(
    rid: int, p: int, d: int, e: int = 0, width: int = 900, seats: int = 1, wc: int = 0
) -> Request:
    direct = abs(p - d) * 100
    ride = int(direct * 1.5) + 300
    return Request(rid, p, d, e, e + width, e, e + width + 60 + ride, seats, wc, ride)


def test_positive_insert_is_valid() -> None:
    inst = line_instance([veh()])
    state = FleetState(inst)
    r = req(1, 3, 6, e=1000)
    res = insert_request(state, r)
    assert res.accepted and res.vehicle_id == 0
    assert [s.kind for s in res.stops] == [PICKUP, DROPOFF]
    inst.requests[r.id] = r
    assert validate_route(inst, inst.vehicles[0], res.stops, res.starts) == []


def test_negative_unsolvable_instance_gives_reasoned_rejection() -> None:
    inst = line_instance([veh(depot=0)])
    state = FleetState(inst)
    # Pickup an Knoten 10 (1000 s entfernt) im Fenster [0, 100]: nicht erreichbar
    res = insert_request(state, req(1, 10, 11, e=0, width=100))
    assert not res.accepted and res.reason == NO_FEASIBLE and res.stops == []


def test_negative_resource_conflict_wheelchair_without_capacity() -> None:
    inst = line_instance([veh(wc=0), veh(1, wc=0)])
    res = insert_request(FleetState(inst), req(1, 1, 2, e=500, seats=0, wc=1))
    assert not res.accepted and res.reason == NO_FEASIBLE


def test_wheelchair_goes_to_vehicle_with_wheelchair_spot() -> None:
    inst = line_instance([veh(0, wc=0, depot=1), veh(1, wc=1, depot=9)])
    res = insert_request(FleetState(inst), req(1, 1, 2, e=2000, seats=0, wc=1))
    assert res.accepted and res.vehicle_id == 1


def test_capacity_vector_is_per_dimension() -> None:
    # 1 Sitz, 1 Rollstuhlplatz: zwei gleichzeitige Sitzfahrgäste dürfen nicht passen
    inst = line_instance([veh(seats=1, wc=1)])
    state = FleetState(inst)
    a = req(1, 1, 8, e=1000, width=3000)
    ra = insert_request(state, a)
    assert ra.accepted
    commit(state, a, ra)
    b = req(2, 2, 5, e=1000, width=3000)  # überlappt zeitlich zwingend mit a
    rb = insert_request(state, b)
    if rb.accepted:  # nur zulässig, wenn b nach a abgewickelt wird (keine Gleichzeitigkeit)
        order = [(s.kind, s.req) for s in rb.stops]
        assert order.index((DROPOFF, 1)) < order.index((PICKUP, 2)) or order.index(
            (DROPOFF, 2)
        ) < order.index((PICKUP, 1))
    w = req(3, 2, 5, e=1000, width=3000, seats=0, wc=1)
    rw = insert_request(state, w)
    assert rw.accepted  # Rollstuhlplatz ist eine eigene Ressource


def test_break_is_not_straddled_by_a_ride() -> None:
    inst = line_instance([veh(brk=1800)])
    state = FleetState(inst)
    r = req(1, 1, 2, e=3500, width=4000)  # Fenster überdeckt das Pausenfenster [3600, 7200]
    res = insert_request(state, r)
    assert res.accepted
    inst.requests[r.id] = r
    kinds = [s.kind for s in res.stops]
    b = kinds.index(BREAK)
    assert not (kinds.index(PICKUP) < b < kinds.index(DROPOFF))
    assert validate_route(inst, inst.vehicles[0], res.stops, res.starts) == []


def test_existing_commitments_keep_order_and_windows() -> None:
    inst = make_instance(5, n_vehicles=20)
    reqs = make_requests(inst, 80, 6)
    state = build_state(inst, reqs)
    for c in state.routes:
        assert validate_route(inst, c.vehicle, c.stops, c.st) == []
    q = make_requests(inst, 10, 7, 10_000)
    for r in q:
        res = insert_request(state, r)
        if res.accepted:
            assert validate_commitments(state.routes[res.vehicle_id].stops, res.stops) == []


def test_forced_timeout_returns_validated_plan_or_reasoned_rejection() -> None:
    inst = make_instance(3, n_vehicles=60)
    state = build_state(inst, make_requests(inst, 150, 4))
    for r in make_requests(inst, 30, 5, 10_000):
        res = insert_request(state, r, budget_s=0.0)
        assert res.timed_out
        if res.accepted:
            inst.requests[r.id] = r
            v = inst.vehicles[res.vehicle_id]
            assert validate_route(inst, v, res.stops, res.starts) == []
            inst.requests.pop(r.id)
        else:
            assert res.reason == TIMEOUT_NONE and res.stops == []


def test_validator_detects_violations() -> None:
    inst = line_instance([veh(seats=1)])
    r1, r2 = req(1, 1, 3, e=1000), req(2, 2, 4, e=1000)
    inst.requests = {1: r1, 2: r2}
    v = inst.vehicles[0]
    good = [Stop(PICKUP, 1), Stop(DROPOFF, 1)]
    st = RouteCache(inst, v, good).st
    assert validate_route(inst, v, good, st) == []
    assert any(
        "Zeitfenster" in e for e in validate_route(inst, v, good, [st[0] + 5000, st[1] + 5000])
    )
    assert any("Fahrzeit" in e for e in validate_route(inst, v, good, [st[0], st[0]]))
    two = [Stop(PICKUP, 1), Stop(PICKUP, 2), Stop(DROPOFF, 1), Stop(DROPOFF, 2)]
    errs = validate_route(inst, v, two, [1000, 1100, 1300, 1500])
    assert any("Kapazität" in e for e in errs)
    assert any(
        "Dropoff vor Pickup" in e for e in validate_route(inst, v, [Stop(DROPOFF, 1)], [1500])
    )
    late_end = replace(v, shift_end=1100)
    assert any("Schichtende" in e for e in validate_route(inst, late_end, good, st))
    assert validate_commitments(good, [Stop(PICKUP, 2), *good]) == []
    assert validate_commitments(good, [Stop(DROPOFF, 1), Stop(PICKUP, 1)]) != []


def test_determinism_same_seed_same_result() -> None:
    def run() -> list[tuple[int, int, int, int]]:
        inst = make_instance(9, n_vehicles=40)
        state = build_state(inst, make_requests(inst, 120, 10, 0, PROFILES["spitze"]))
        out = []
        for r in make_requests(inst, 20, 11, 10_000, PROFILES["spitze"]):
            x = insert_request(state, r)
            out.append((x.vehicle_id, x.pos_pickup, x.pos_dropoff, x.delta))
        return out

    assert run() == run()


def _brute_force(state: FleetState, r: Request) -> tuple[int, int, int, int] | None:
    inst = state.inst
    inst.requests[r.id] = r
    best: tuple[int, int, int, int] | None = None
    try:
        for c in state.routes:
            real = c.real
            old = sum(inst.T[a][b] for a, b in zip(real, real[1:], strict=False))
            for i in range(len(c.stops) + 1):
                for j in range(i, len(c.stops) + 1):
                    stops = list(c.stops)
                    stops.insert(j, Stop(DROPOFF, r.id))
                    stops.insert(i, Stop(PICKUP, r.id))
                    try:
                        rc = RouteCache(inst, c.vehicle, stops)
                    except ValueError:
                        continue
                    new = sum(inst.T[a][b] for a, b in zip(rc.real, rc.real[1:], strict=False))
                    key = (new - old, c.vehicle.id, i, j)
                    if best is None or key < best:
                        best = key
    finally:
        inst.requests.pop(r.id, None)
    return best


@pytest.mark.parametrize("profile", ["tag", "spitze", "weit"])
def test_heuristic_matches_exhaustive_search(profile: str) -> None:
    """Die beschnittene Suche findet dasselbe Optimum wie vollständige Enumeration."""
    inst = make_instance(21, n_vehicles=12)
    prof = PROFILES[profile]
    state = build_state(inst, make_requests(inst, 70, 22, 0, prof))
    n_acc = 0
    for r in make_requests(inst, 25, 23, 10_000, prof):
        res = insert_request(state, r)
        ref = _brute_force(state, r)
        if ref is None:
            assert not res.accepted
        else:
            n_acc += 1
            assert res.accepted
            assert (res.delta, res.vehicle_id, res.pos_pickup, res.pos_dropoff) == ref
    assert n_acc > 5


def test_concurrent_queries_on_same_fleet_are_consistent() -> None:
    inst = make_instance(31, n_vehicles=50)
    state = build_state(inst, make_requests(inst, 150, 32))
    qs = make_requests(inst, 24, 33, 10_000)
    before = [list(c.stops) for c in state.routes]
    seq = [insert_request(state, q) for q in qs]
    shuffled = list(range(len(qs)))
    random.Random(1).shuffle(shuffled)
    with ThreadPoolExecutor(max_workers=4) as pool:
        par = dict(
            zip(shuffled, pool.map(lambda k: insert_request(state, qs[k]), shuffled), strict=True)
        )
    for k, s in enumerate(seq):
        p = par[k]
        assert (s.accepted, s.vehicle_id, s.pos_pickup, s.pos_dropoff, s.delta) == (
            p.accepted, p.vehicle_id, p.pos_pickup, p.pos_dropoff, p.delta,
        )  # fmt: skip
    assert [list(c.stops) for c in state.routes] == before  # Abfragen ändern den Zustand nicht
