# ruff: noqa: E501
"""WP-M2-17 / LH-6.5-OPT-003: Einfügeheuristik im Routing-Worker (Verträge LH-6.5-OPT-003)."""

from __future__ import annotations

import json
import random
import threading
import urllib.request
from dataclasses import replace
from pathlib import Path

import pytest

from insertion_fixtures import (
    MATRIX,
    OTHER_TENANT,
    PARAMS,
    empty_vehicle,
    mv,
    request,
    vehicle,
    x_commitment,
)
from taktovia_routing.insertion import (
    NO_CANDIDATE_VEHICLE,
    NO_FEASIBLE_POSITION,
    TIMEOUT_NO_VALIDATED_PLAN,
    Commitment,
    ScopeViolation,
    Variant,
    find_insertions,
    handle_insert,
    outcome_json,
    validate_variant,
)


def aboard_max(variant: Variant) -> int:
    """Unabhängige Zählung der gleichzeitig mitfahrenden Buchungen."""
    aboard: set[str] = set()
    peak = 0
    for s in variant.stops:
        for a in s.alightings:
            aboard.discard(a.booking_id)
        for b in s.boardings:
            aboard.add(b.booking_id)
        peak = max(peak, len(aboard))
    return peak


def test_einfuegen_zwischen_vorhandene_stopps() -> None:
    v = vehicle()
    out = find_insertions([v], request(3, 4), PARAMS, MATRIX)
    assert out.rejection is None and out.variants
    var = out.variants[0]
    # A(2) ... Einstieg(3) Ausstieg(4) ... B(5): beide neuen Stopps liegen zwischen A und B
    assert [s.node for s in var.stops] == [2, 3, 4, 5]
    assert (var.pos_pickup, var.pos_dropoff) == (1, 2)
    assert validate_variant(v, request(3, 4), PARAMS, MATRIX, var) == []
    # Zeitpropagation mit Bedienzeit: 600 + 60 + 300 = 960, +60 +300 = 1320, +60 +300 = 1680
    assert [s.planned_at for s in var.stops] == [600, 960, 1320, 1680]


def test_einfuegen_anfang_mitte_ende() -> None:
    # Anfang: das Fenster erzwingt Abholung und Ausstieg vor dem ersten Stopp A (Knoten 2)
    anfang = find_insertions([vehicle()], request(1, 2, pe=0, pl=400, ae=0, al=800), PARAMS, MATRIX)
    assert (anfang.variants[0].pos_pickup, anfang.variants[0].pos_dropoff) == (0, 1)
    # Mitte: beide Stopps zwischen A und B
    mitte = find_insertions([vehicle()], request(3, 4), PARAMS, MATRIX)
    assert (mitte.variants[0].pos_pickup, mitte.variants[0].pos_dropoff) == (1, 2)
    # Ende: X darf keinen Umweg erfahren (0 %), das späte Fenster liegt nach B
    strikt = vehicle(commitments=(x_commitment(detour=0),))
    ende = find_insertions(
        [strikt], request(5, 6, pe=1700, pl=2500, ae=1700, al=3500), PARAMS, MATRIX
    )
    var = ende.variants[0]
    assert (var.pos_pickup, var.pos_dropoff) == (2, 3) and len(var.stops) == 4
    # Einstieg und Ausstieg dürfen auch auf verschiedenen Seiten liegen: Einstieg zwischen A und B, Ausstieg nach B
    gemischt = find_insertions([vehicle()], request(4, 7), PARAMS, MATRIX)
    assert (
        gemischt.variants and gemischt.variants[0].pos_pickup < len(gemischt.variants[0].stops) - 1
    )


def test_leeres_fahrzeug_neuer_umlauf() -> None:
    v = empty_vehicle()
    out = find_insertions([v], request(1, 3), PARAMS, MATRIX)
    var = out.variants[0]
    assert var.new_run and var.run_id is None and var.plan_revision == 0
    assert [s.node for s in var.stops] == [1, 3]
    assert [s.planned_at for s in var.stops] == [300, 300 + 60 + 600]
    assert validate_variant(v, request(1, 3), PARAMS, MATRIX, var) == []


def test_keine_zulaessige_position_begruendete_ablehnung() -> None:
    # Abholfenster endet vor der frühestmöglichen Ankunft des Fahrzeugs
    out = find_insertions([vehicle()], request(8, 9, pe=0, pl=100), PARAMS, MATRIX)
    assert out.variants == [] and out.rejection == NO_FEASIBLE_POSITION
    assert out.grounds.get("PICKUP_WINDOW", 0) > 0
    # ohne Kandidaten
    assert (
        find_insertions([vehicle()], request(3, 4), PARAMS, MATRIX, candidate_ids=[]).rejection
        == NO_CANDIDATE_VEHICLE
    )
    # zu großer Bedarf: Kapazität als Grund
    voll = find_insertions([vehicle()], request(3, 4, seats=5), PARAMS, MATRIX)
    assert voll.rejection == NO_FEASIBLE_POSITION and voll.grounds["CAPACITY"] == 1


def test_alle_varianten_bestehen_validator() -> None:
    rnd = random.Random(20260211)
    fleet = [empty_vehicle(f"v{i}") for i in range(4)]
    total = checked = 0
    for k in range(40):
        pu, do = rnd.sample(range(1, 9), 2)
        pe = rnd.randrange(0, 8000, 300)
        req = request(
            pu,
            do,
            pe=pe,
            pl=pe + 1500,
            ae=pe,
            al=pe + 6000,
            bid=f"b{k}",
            seats=rnd.choice([1, 1, 2]),
        )
        out = find_insertions(fleet, req, PARAMS, MATRIX)
        for var in out.variants:
            total += 1
            plan = next(f for f in fleet if f.vehicle_id == var.vehicle_id)
            assert validate_variant(plan, req, PARAMS, MATRIX, var) == []
            assert aboard_max(var) >= 1
            checked += 1
        if out.variants:  # beste Variante übernehmen: Plan und Zusage wachsen
            var = out.variants[0]
            i = next(n for n, f in enumerate(fleet) if f.vehicle_id == var.vehicle_id)
            c = Commitment(req.booking_id, req.pickup_earliest, req.pickup_latest, req.arrival_earliest, req.arrival_latest,
                           0, None, None, PARAMS.max_detour_pct, MATRIX[pu][do])  # fmt: skip
            fleet[i] = replace(fleet[i], run_id=fleet[i].run_id or f"run-{i}", plan_revision=fleet[i].plan_revision + 1,
                               stops=tuple(var.stops), commitments=(*fleet[i].commitments, c))  # fmt: skip
    assert checked >= 40  # die Messung ist nicht leer
    # der Prüfer ist nicht blind: konstruierte Verletzungen werden gefunden
    v = vehicle()
    req = request(3, 4)
    var = find_insertions([v], req, PARAMS, MATRIX).variants[0]
    zu_frueh = replace(
        var,
        stops=[
            replace(s, planned_at=s.planned_at - 1) if k == 1 else s
            for k, s in enumerate(var.stops)
        ],
    )
    assert any("Fahrzeit" in e for e in validate_variant(v, req, PARAMS, MATRIX, zu_frueh))
    ohne_a = replace(var, stops=var.stops[1:])
    assert any("bestehende Stopps" in e for e in validate_variant(v, req, PARAMS, MATRIX, ohne_a))
    falsche_rev = replace(var, plan_revision=var.plan_revision + 1)
    assert any("Planrevision" in e for e in validate_variant(v, req, PARAMS, MATRIX, falsche_rev))
    ueberbelegt = replace(
        var,
        stops=[
            replace(s, boardings=tuple(replace(b, seats=9) for b in s.boardings)) if k == 1 else s
            for k, s in enumerate(var.stops)
        ],
    )
    assert any("Kapazität" in e for e in validate_variant(v, req, PARAMS, MATRIX, ueberbelegt))


def test_jede_variante_nennt_planrevision() -> None:
    fleet = [vehicle("va", revision=7), vehicle("vb", revision=12), empty_vehicle("vc")]
    out = find_insertions(fleet, request(3, 4), PARAMS, MATRIX)
    revs = {v.vehicle_id: v.plan_revision for v in out.variants}
    assert revs == {"va": 7, "vb": 12, "vc": 0}
    js = outcome_json(out)
    assert all(isinstance(v["plan_revision"], int) for v in js["variants"])
    assert {v["vehicle_id"]: v["plan_revision"] for v in js["variants"]} == revs


def test_pooling_aus_keine_ueberlappende_mitfahrt() -> None:
    v = vehicle()
    req = request(3, 4)
    mit = find_insertions([v], req, PARAMS, MATRIX)
    assert aboard_max(mit.variants[0]) == 2  # Kontrolle: mit Pooling überlappt die Mitfahrt
    ohne = replace(PARAMS, pooling_allowed=False)
    out = find_insertions([v], request(3, 4, pe=1700, al=36000), ohne, MATRIX)
    assert out.variants, "nach B ist eine Fahrt ohne Überlappung möglich"
    assert all(aboard_max(var) == 1 for var in out.variants)
    # nur überlappende Positionen möglich (Fenster liegt vor dem Ausstieg von X): keine Variante
    eng = find_insertions([v], request(3, 4, pl=1000, al=1700), ohne, MATRIX)
    assert eng.variants == [] and eng.grounds.get("POOLING", 0) > 0
    # der Prüfer weist eine überlappende Variante bei pooling_allowed=false ab
    assert any(
        "pooling_allowed=false" in e
        for e in validate_variant(v, req, ohne, MATRIX, mit.variants[0])
    )


def test_timeout_nur_validierter_plan_oder_ablehnung() -> None:
    fleet = [vehicle(f"v{i}") for i in range(3)]
    req = request(3, 4)
    # Budget schon verbraucht: Ablehnung, nie ein Plan
    out = find_insertions(fleet, req, PARAMS, MATRIX, budget_s=0.0)
    assert out.variants == [] and out.rejection == TIMEOUT_NO_VALIDATED_PLAN and out.partial

    # Zeit läuft nach dem ersten Fahrzeug ab: validiertes Teilergebnis
    ticks = {"n": 0}

    def clock() -> float:
        ticks["n"] += 1
        return 0.0 if ticks["n"] < 12 else 10.0

    teil = find_insertions(fleet, req, PARAMS, MATRIX, budget_s=1.0, clock=clock)
    assert teil.partial
    assert (
        teil.variants and teil.vehicles_scanned < 3
    )  # Teilergebnis: erstes Fahrzeug validiert, Rest nicht gerechnet
    for var in teil.variants:
        plan = next(f for f in fleet if f.vehicle_id == var.vehicle_id)
        assert validate_variant(plan, req, PARAMS, MATRIX, var) == []


def _post(port: int, body: dict) -> tuple[int, dict]:  # type: ignore[type-arg]
    rq = urllib.request.Request(
        f"http://127.0.0.1:{port}/v1/insert",
        json.dumps(body).encode(),
        {"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(rq, timeout=5) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


def test_fremder_mandant_und_fahrzeugscope_nicht_ueberschritten() -> None:
    fremd = vehicle("v-fremd", tenant=OTHER_TENANT)
    with pytest.raises(ScopeViolation):
        find_insertions([vehicle(), fremd], request(3, 4), PARAMS, MATRIX)
    # Fahrzeugscope: Pläne außerhalb der Kandidaten werden nicht gerechnet
    out = find_insertions(
        [vehicle("v1"), vehicle("v2")], request(3, 4), PARAMS, MATRIX, candidate_ids=["v2"]
    )
    assert {v.vehicle_id for v in out.variants} == {"v2"} and out.vehicles_scanned == 1
    # über die JSON-Schnittstelle: SCOPE_VIOLATION (422), Eingang eines anderen Mandanten wird nie gerechnet
    from http.server import HTTPServer

    from taktovia_routing.server import make_handler

    srv = HTTPServer(("127.0.0.1", 0), make_handler(None))  # type: ignore[arg-type]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        contract = Path(__file__).resolve().parent.parent / "contract" / "insert-request.json"
        body = json.loads(contract.read_text())
        status, resp = _post(srv.server_port, body)
        assert (
            status == 200
            and resp["variants"][0]["plan_revision"] == 0
            and resp["variants"][0]["new_run"] is True
        )
        body["vehicles"][0]["tenant_id"] = OTHER_TENANT
        status, resp = _post(srv.server_port, body)
        assert status == 422 and resp["error"]["code"] == "SCOPE_VIOLATION"
        body["vehicles"] = "kaputt"
        assert _post(srv.server_port, body)[0] == 400
    finally:
        srv.shutdown()


def test_parallele_anfragen_eigene_planrevision() -> None:
    v_r3 = vehicle(revision=3)
    v_r4 = vehicle(revision=4)
    results: dict[str, list[Variant]] = {}

    def run(name: str, plan, req) -> None:  # type: ignore[no-untyped-def]
        results[name] = find_insertions([plan], req, PARAMS, MATRIX).variants

    t1 = threading.Thread(target=run, args=("a", v_r3, request(3, 4, bid="N1")))
    t2 = threading.Thread(target=run, args=("b", v_r4, request(4, 6, bid="N2")))
    t1.start()
    t2.start()
    t1.join()
    t2.join()  # noqa: E702
    assert {v.plan_revision for v in results["a"]} == {3}
    assert {v.plan_revision for v in results["b"]} == {4}
    # die Eingangspläne sind unverändert (keine geteilte Zustandsänderung zwischen den Anfragen)
    assert len(v_r3.stops) == 2 and len(v_r4.stops) == 2
    assert handle_insert is not None
    assert all(mv("N1") for _ in results["a"])
