"""Laufzeitmessung der Einfügeheuristik gegen das Zeitbudget der lokalen Kandidatensuche (WP-M2-17, Akzeptanzkriterium 4).

Referenzszenario (Planungsannahme, Produktivwerte stehen unter D-05 aus): 60 Fahrzeuge mit je bis zu 4 gepoolten Buchungen auf einem Korridor mit 120 Haltepunkten,
Fahrzeit 90 s je Haltepunktabstand. Gemessen wird die Dauer der Kandidatensuche je Anfrage (alle Fahrzeuge als Kandidaten) mit dem echten Worker-Code; das Ergebnis
hängt von der Hardware ab und ist ein Nachweis für diese Umgebung, kein Lastnachweis (kein Lastprofil, keine Parallelität).
Zweiter Teil: ein zu knappes Budget führt zu einem validierten Teilergebnis oder zur Ablehnung, nie zu einem ungeprüften Plan.
"""

from __future__ import annotations

import random
import sys
import time
import unittest
from dataclasses import replace
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "workers" / "routing" / "src"))
from taktovia_routing.insertion import (  # noqa: E402
    Capacity, Commitment, Params, Request, VehiclePlan, find_insertions, validate_variant,
)

TENANT = "00000000-0000-4000-8000-00000000000a"
NODES = 120
MATRIX = [[abs(a - b) * 90 for b in range(NODES)] for a in range(NODES)]
PARAMS = Params(pooling_allowed=True, stop_dwell_s=60, wheelchair_boarding_s=120, max_wait_s=3600, max_detour_pct=150, arrival_grace_s=300)
BUDGET_S = 1.0


def _request(rnd: random.Random, k: int) -> Request:
    pu, do = rnd.sample(range(NODES), 2)
    pe = rnd.randrange(0, 4 * 3600, 300)
    return Request(f"b{k}", TENANT, pu, do, pe, pe + 1800, pe, pe + 3 * 3600, 1, 0, frozenset())


def build_fleet(vehicles: int, bookings_each: int, seed: int = 7) -> list[VehiclePlan]:
    rnd = random.Random(seed)
    fleet = [VehiclePlan(f"v{i:04d}", TENANT, f"run-{i}", 1, Capacity(8, 1, frozenset()), rnd.randrange(NODES), 0, 12 * 3600) for i in range(vehicles)]
    placed = k = 0
    while placed < vehicles * bookings_each and k < vehicles * bookings_each * 6:
        k += 1
        req = _request(rnd, k)
        out = find_insertions(fleet, req, PARAMS, MATRIX, max_variants=1, budget_s=60.0)
        if not out.variants:
            continue
        var = out.variants[0]
        i = next(n for n, f in enumerate(fleet) if f.vehicle_id == var.vehicle_id)
        c = Commitment(req.booking_id, req.pickup_earliest, req.pickup_latest, req.arrival_earliest, req.arrival_latest, 0, None, None, PARAMS.max_detour_pct, MATRIX[req.pickup_node][req.dropoff_node])
        fleet[i] = replace(fleet[i], plan_revision=fleet[i].plan_revision + 1, stops=tuple(var.stops), commitments=(*fleet[i].commitments, c))
        placed += 1
    return fleet


class InsertionBudget(unittest.TestCase):
    def test_kandidatensuche_im_zeitbudget(self) -> None:
        fleet = build_fleet(60, 4)
        self.assertGreaterEqual(sum(len(v.commitments) for v in fleet), 120, "Referenzszenario zu dünn besetzt")
        rnd = random.Random(99)
        times: list[float] = []
        offered = 0
        for k in range(40):
            req = _request(rnd, 10_000 + k)
            t0 = time.perf_counter()
            out = find_insertions(fleet, req, PARAMS, MATRIX, budget_s=BUDGET_S)
            times.append(time.perf_counter() - t0)
            self.assertFalse(out.partial, "Budget im Referenzszenario überschritten")
            for var in out.variants:  # nie ein ungeprüfter Plan
                plan = next(f for f in fleet if f.vehicle_id == var.vehicle_id)
                self.assertEqual(validate_variant(plan, req, PARAMS, MATRIX, var), [])
            offered += bool(out.variants)
        times.sort()
        p95 = times[int(0.95 * len(times)) - 1]
        print(f"\nKandidatensuche 60 Fahrzeuge/{sum(len(v.stops) for v in fleet)} Stopps: p95 {p95 * 1000:.0f} ms, max {times[-1] * 1000:.0f} ms, Budget {BUDGET_S * 1000:.0f} ms, mit Variante {offered}/40")
        self.assertGreater(offered, 0)
        self.assertLessEqual(times[-1], BUDGET_S)

    def test_zu_knappes_budget_ergibt_teilergebnis_oder_ablehnung(self) -> None:
        fleet = build_fleet(60, 4)
        req = _request(random.Random(5), 99_999)
        t0 = time.perf_counter()
        out = find_insertions(fleet, req, PARAMS, MATRIX, budget_s=0.001)
        dauer = time.perf_counter() - t0
        self.assertTrue(out.partial)
        self.assertLess(dauer, 0.25, "Budget wird nicht eingehalten")
        if not out.variants:
            self.assertEqual(out.rejection, "TIMEOUT_NO_VALIDATED_PLAN")
        for var in out.variants:
            plan = next(f for f in fleet if f.vehicle_id == var.vehicle_id)
            self.assertEqual(validate_variant(plan, req, PARAMS, MATRIX, var), [])


if __name__ == "__main__":
    unittest.main()
