"""Messung der Angebotskette außerhalb der HTTP-Schicht (WP-M5-12): Routingmatrix (Adapter `workers/routing` mit Cache, kalt und warm, langsame Quelle), Einfügeheuristik auf einer dokumentierten
Testflotte, Zeitbudget und Timeout-Verhalten, Warteschlange vor einem Worker-Pool bei Referenzankunftsrate.

Nicht Teil dieser Messung (und im Bericht so ausgewiesen): der Kandidatenfilter des Kerns (Kotlin/JDBC) und die Zusammenschaltung mit dem Kern; die Produktionsverdrahtung reicht Angebote heute ohne
Kandidatensuche und Solver durch (BookingConfiguration, CandidateGate.PASS_THROUGH). Die Routingquelle ist ein synthetischer Anbieter (Fahrzeit = Abstand der Haltepunkte x 90 s, optional mit
Latenz), der Routingdatenstand heißt daher `synthetic-corridor-v1`; Valhalla/OSM stehen in dieser Umgebung nicht zur Verfügung (D-09)."""
from __future__ import annotations

import multiprocessing
import random
import sys
import time
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "workers" / "routing" / "src"))
sys.path.insert(0, str(REPO / "tests" / "performance"))

from taktovia_routing.adapter import RoutingAdapter  # noqa: E402
from taktovia_routing.insertion import find_insertions, validate_variant  # noqa: E402
from taktovia_routing.port import Cell, MatrixResult, ProviderTimeout, Stop, VehicleProfile  # noqa: E402
from test_insertion_budget import MATRIX, NODES, PARAMS, TENANT, _request, build_fleet  # noqa: E402

MAP_VERSION = "synthetic-corridor-v1"
PROFILE = VehicleProfile("kleinbus", "car", 2.8, 3.5)
STOPS = {f"h{i:03d}": Stop(f"h{i:03d}", 51.0 + i * 0.0005, 13.7 + i * 0.0005) for i in range(NODES)}


class SyntheticProvider:
    """Deterministischer Kartenanbieter für den Korridor; `latency_s` simuliert eine langsame Quelle, `fail` einen Ausfall."""
    name = "synthetic"

    def __init__(self, latency_s: float = 0.0) -> None:
        self.latency_s, self.calls = latency_s, 0

    def map_version(self) -> str:
        return MAP_VERSION

    def route(self, o, d, p):  # pragma: no cover - nicht benötigt
        raise NotImplementedError

    def matrix(self, origins, destinations, profile):
        self.calls += 1
        if self.latency_s:
            time.sleep(self.latency_s)
        idx = {s.id: int(s.id[1:]) for s in STOPS.values()}
        cells = tuple(tuple(Cell(abs(idx[o.id] - idx[d.id]) * 90, abs(idx[o.id] - idx[d.id]) * 700) for d in destinations) for o in origins)
        return MatrixResult(tuple(o.id for o in origins), tuple(d.id for d in destinations), cells, profile.name, MAP_VERSION, self.name)


def adapter(latency_s: float = 0.0, timeout_s: float = 0.8) -> tuple[RoutingAdapter, SyntheticProvider]:
    prov = SyntheticProvider(latency_s)
    return RoutingAdapter(prov, STOPS, {"kleinbus": PROFILE}, timeout_s=timeout_s), prov


def matrix_list(adp: RoutingAdapter) -> list[list[int | None]]:
    ids = list(STOPS)
    m = adp.matrix(TENANT, ids, ids, "kleinbus")
    return [[None if c is None else c.time_s for c in row] for row in m.cells]


def testflotte_beschreibung(fleet) -> dict:
    return {"fahrzeuge": len(fleet), "ressourcenvektor": "8 Sitze, 1 Rollstuhlplatz, keine Zusatzausstattung", "vorbelegung": f"{sum(len(v.commitments) for v in fleet)} bestätigte Fahrten (Ziel 2 je Fahrzeug, Zusagefenster 30 min), "
            f"{sum(len(v.stops) for v in fleet)} Stopps", "gebiet": f"Korridor mit {NODES} Haltepunkten, Fahrzeit 90 s je Haltepunktabstand", "routingdatenstand": MAP_VERSION + " (synthetisch)",
            "parameter": "Pooling an, Haltedauer 60 s, max. Wartezeit 60 min, max. Umweg 150 %, Ankunftstoleranz 5 min", "seed": 7}


_FLEET = None


def fleet(vehicles: int = 250, bookings_each: int = 2):
    global _FLEET
    if _FLEET is None or len(_FLEET) != vehicles:
        _FLEET = build_fleet(vehicles, bookings_each)
    return _FLEET


def _solve(args):
    """Ein Auftrag im Worker-Prozess: Matrix (aus dem prozesslokalen Cache), Suche mit Budget; Rückgabe Phasenzeiten und Ergebnis, unabhängig geprüft."""
    seed, budget_s, submitted = args
    started = time.perf_counter()
    f = _FLEET
    req = _request(random.Random(seed), 500_000 + seed)
    t0 = time.perf_counter()
    out = find_insertions(f, req, PARAMS, MATRIX, budget_s=budget_s)
    solve_s = time.perf_counter() - t0
    bad = 0
    for var in out.variants:
        plan = next(v for v in f if v.vehicle_id == var.vehicle_id)
        if validate_variant(plan, req, PARAMS, MATRIX, var):
            bad += 1
    return {"solve_s": solve_s, "wait_s": started - submitted, "variants": len(out.variants), "partial": out.partial, "rejection": out.rejection, "invalid_variants": bad,
            "scanned": out.vehicles_scanned, "pairs": out.pairs_checked}


def queue_run(vehicles: int, rate_per_s: float, duration_s: float, workers: int, budget_s: float = 1.0, seed: int = 11) -> dict:
    """Offene Ankunftsrate vor einem Worker-Pool (`fork`, die Testflotte wird vor dem Start aufgebaut): misst Wartezeit in der Warteschlange und Lösungszeit je Anfrage."""
    fleet(vehicles)
    ctx = multiprocessing.get_context("fork")
    rnd = random.Random(seed)
    results, futures = [], []
    with ProcessPoolExecutor(workers, mp_context=ctx) as ex:
        list(ex.map(_solve, [(i, budget_s, time.perf_counter()) for i in range(workers)]))          # Prozesse warmlaufen lassen
        t_end = time.perf_counter() + duration_s
        k = 0
        while time.perf_counter() < t_end:
            futures.append((ex.submit(_solve, (1000 + k, budget_s, time.perf_counter())), time.perf_counter()))
            k += 1
            time.sleep(rnd.expovariate(rate_per_s))
        for fu, sub in futures:
            r = fu.result(); r["total_s"] = time.perf_counter() - sub; results.append(r)
    return {"anfragen": len(results), "worker": workers, "rate_je_s": rate_per_s, "ergebnisse": results}


def pct(values: list[float], p: float) -> float:
    import math
    v = sorted(values)
    return v[max(0, math.ceil(p / 100 * len(v)) - 1)]


def summarize_ms(values_s: list[float]) -> dict:
    return {"n": len(values_s), "p50_ms": round(pct(values_s, 50) * 1000, 2), "p95_ms": round(pct(values_s, 95) * 1000, 2), "p99_ms": round(pct(values_s, 99) * 1000, 2), "max_ms": round(max(values_s) * 1000, 2)} if values_s else {"n": 0}


def routing_cold_warm(n_cold: int = 10, n_warm: int = 50, latency_s: float = 0.05) -> dict:
    """Matrix 120 x 120: kalt (leerer Cache, ein Anbieteraufruf mit Latenz) gegen warm (alle Zellen aus dem Cache); langsame Quelle (Latenz über dem Zeitbudget) liefert `ProviderTimeout`, nie ein Ergebnis."""
    cold, warm = [], []
    for _ in range(n_cold):
        adp, prov = adapter(latency_s)
        t0 = time.perf_counter(); matrix_list(adp); cold.append(time.perf_counter() - t0); adp.close()
    adp, prov = adapter(latency_s)
    matrix_list(adp)
    for _ in range(n_warm):
        t0 = time.perf_counter(); matrix_list(adp); warm.append(time.perf_counter() - t0)
    calls_after_warm = prov.calls
    adp.close()
    slow, prov2 = adapter(latency_s=1.2, timeout_s=0.3)
    outcome, t0 = "kein Fehler", time.perf_counter()
    try:
        matrix_list(slow)
    except ProviderTimeout as e:
        outcome = f"ProviderTimeout ({e.code})"
    elapsed = time.perf_counter() - t0
    slow.close()
    return {"kalt": summarize_ms(cold), "warm": summarize_ms(warm), "anbieteraufrufe_warm": calls_after_warm - 1, "anbieterlatenz_ms": latency_s * 1000,
            "langsame_quelle": {"latenz_ms": 1200, "zeitbudget_ms": 300, "ergebnis": outcome, "dauer_ms": round(elapsed * 1000, 1)}}
