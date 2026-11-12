# ruff: noqa: E501
"""Einfügeheuristik des Routing-Workers (WP-M2-17, LH-6.5-OPT-003) mit harten Grenzen (WP-M2-18,
OPT-005) und Schutz zugesagter Zeitfenster (WP-M2-19, OPT-006).

Der Worker hat keinen Datenbankzugriff: der Kern übergibt die Pläne der Kandidatenfahrzeuge (mit
der Planrevision, auf der sie gelesen wurden), die Anfrage, die Fahrzeitmatrix und die Parameter.
Zurück kommen ausschließlich Varianten, die der unabhängige Prüfer :func:`validate_variant` bestanden
haben, oder eine begründete Ablehnung. Nie ein ungeprüfter Plan. Kapazität sichert erst der Hold
in PostgreSQL; eine Variante nennt die Planrevision, gegen die sie gerechnet wurde.

Zeiten sind ganze Sekunden (Epochensekunden), Knoten sind Indizes der Fahrzeitmatrix.

Festlegungen (Planungsannahmen, Messdefinitionen sind offen, OP-17):

* Planzeit eines Stopps ist der Beginn der Bedienung (Ankunft, bei Einstieg nicht vor dem frühesten
  Abholzeitpunkt, beim Ausstieg nicht vor dem frühesten Ankunftszeitpunkt). Die Bedienung dauert
  ``stop_dwell_s`` plus ``wheelchair_boarding_s`` bei Rollstuhlbewegung am Stopp.
* Wartezeit = Abholzeit minus Bezugspunkt (``wait_reference``, Standard: frühester Abholzeitpunkt);
  der Bezugspunkt ist parametrisiert, weil das Pflichtenheft ihn nicht festlegt.
* Umweg = (Fahrzeit an Bord minus Direktfahrzeit) / Direktfahrzeit; an Bord zählt vom Ende der
  Bedienung am Einstieg bis zum Beginn der Bedienung am Ausstieg. Basis ist die Fahrzeit.
* Verspätung: Ankunft spätestens ``arrival_latest + arrival_grace_s``.
* Zugesagte Fenster und Grenzen bestehender Buchungen (``Commitment``) sind harte Nebenbedingungen
  für jede Variante; bei ``pooling_allowed=false`` ist höchstens eine Buchung gleichzeitig an Bord.
"""

from __future__ import annotations

import time
from collections import Counter
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any

# Ablehnungs- und Verwerfungsgründe (stabile Codes)
NO_FEASIBLE_POSITION = "NO_FEASIBLE_POSITION"
TIMEOUT_NO_VALIDATED_PLAN = "TIMEOUT_NO_VALIDATED_PLAN"
NO_CANDIDATE_VEHICLE = "NO_CANDIDATE_VEHICLE"

DEFAULT_BUDGET_S = 1.0  # Planungsdefault lokale Kandidatensuche (D-05)
MAX_VARIANTS = 5


class ScopeViolation(Exception):
    """Plan eines fremden Mandanten im Eingang: wird nie verarbeitet."""


class InputError(ValueError):
    """Ungültiger Eingang (Form oder Werte)."""


@dataclass(frozen=True, slots=True)
class Movement:
    booking_id: str
    seats: int = 0
    wheelchair: int = 0


@dataclass(frozen=True, slots=True)
class PlanStop:
    node: int
    planned_at: int = 0
    boardings: tuple[Movement, ...] = ()
    alightings: tuple[Movement, ...] = ()


@dataclass(frozen=True, slots=True)
class Commitment:
    """Zusage einer bestätigten oder gehaltenen Buchung: harte Fenster und die Grenzen ihrer Revision."""

    booking_id: str
    pickup_earliest: int
    pickup_latest: int
    arrival_earliest: int
    arrival_latest: int
    arrival_grace_s: int = 0
    max_wait_s: int | None = None
    wait_reference: int | None = None
    max_detour_pct: int | None = None
    direct_s: int | None = None


@dataclass(frozen=True, slots=True)
class Capacity:
    seats: int
    wheelchair: int = 0
    equipment: frozenset[str] = frozenset()


@dataclass(frozen=True, slots=True)
class VehiclePlan:
    vehicle_id: str
    tenant_id: str
    run_id: str | None  # None: noch kein Umlauf (neuer Umlauf, Planrevision 0)
    plan_revision: int
    capacity: Capacity
    start_node: int
    available_from: int
    available_until: int
    stops: tuple[PlanStop, ...] = ()
    commitments: tuple[Commitment, ...] = ()


@dataclass(frozen=True, slots=True)
class Request:
    booking_id: str
    tenant_id: str
    pickup_node: int
    dropoff_node: int
    pickup_earliest: int
    pickup_latest: int
    arrival_earliest: int
    arrival_latest: int
    seats: int = 1
    wheelchair: int = 0
    equipment: frozenset[str] = frozenset()
    wait_reference: int | None = None


@dataclass(frozen=True, slots=True)
class Params:
    pooling_allowed: bool = True
    stop_dwell_s: int = 60
    wheelchair_boarding_s: int = 120
    max_wait_s: int | None = None
    max_detour_pct: int | None = None
    arrival_grace_s: int = 0
    config_revision_id: str | None = None


@dataclass(slots=True)
class Variant:
    vehicle_id: str
    run_id: str | None
    plan_revision: int
    new_run: bool
    pos_pickup: int  # Index des Einstiegsstopps in der neuen Stoppfolge
    pos_dropoff: int
    stops: list[PlanStop]
    added_drive_s: int
    checks: dict[str, Any]  # Diagnose der geprüften Grenzen (OPT-005)
    config_revision_id: str | None = None


@dataclass(slots=True)
class Outcome:
    variants: list[Variant] = field(default_factory=list)
    rejection: str | None = None
    grounds: dict[str, int] = field(default_factory=dict)
    partial: bool = False  # Zeitbudget überschritten, nur validiertes Teilergebnis
    vehicles_scanned: int = 0
    pairs_checked: int = 0
    elapsed_s: float = 0.0


Matrix = Sequence[Sequence[int | None]]


@dataclass(frozen=True, slots=True)
class _Win:
    pe: int
    pl: int
    ae: int
    al: int  # inklusive Karenz
    max_wait: int | None
    wait_ref: int
    max_detour: int | None
    direct: int | None


def _dwell(params: Params, stop: PlanStop) -> int:
    wc = any(m.wheelchair > 0 for m in stop.boardings) or any(
        m.wheelchair > 0 for m in stop.alightings
    )
    return params.stop_dwell_s + (params.wheelchair_boarding_s if wc else 0)


def _windows(v: VehiclePlan, r: Request, p: Params, m: Matrix) -> dict[str, _Win]:
    w: dict[str, _Win] = {}
    for c in v.commitments:
        w[c.booking_id] = _Win(
            c.pickup_earliest, c.pickup_latest, c.arrival_earliest, c.arrival_latest + c.arrival_grace_s,
            c.max_wait_s, c.pickup_earliest if c.wait_reference is None else c.wait_reference,
            c.max_detour_pct, c.direct_s,
        )  # fmt: skip
    direct = m[r.pickup_node][r.dropoff_node]
    w[r.booking_id] = _Win(
        r.pickup_earliest, r.pickup_latest, r.arrival_earliest, r.arrival_latest + p.arrival_grace_s,
        p.max_wait_s, r.pickup_earliest if r.wait_reference is None else r.wait_reference,
        p.max_detour_pct, direct,
    )  # fmt: skip
    return w


def _evaluate(
    v: VehiclePlan, stops: Sequence[PlanStop], wins: Mapping[str, _Win], new_id: str,
    p: Params, m: Matrix, tight: dict[str, int] | None = None,
) -> tuple[list[int] | None, str | None]:  # fmt: skip
    """Zeitpropagation mit frühestem Start und Prüfung aller harten Bedingungen: (Zeiten, None) oder (None, Grund)."""
    t, loc = v.available_from, v.start_node
    times: list[int] = []
    pick_end: dict[str, int] = {}
    pick_at: dict[str, int] = {}
    seats = wc = 0
    aboard: set[str] = set()
    seg_viol: str | None = None
    for s in stops:
        leg = m[loc][s.node]
        if leg is None:
            return None, "NO_ROUTE"
        start = t + leg
        for b in s.boardings:
            if b.booking_id in wins:
                start = max(start, wins[b.booking_id].pe)
        for a in s.alightings:
            if a.booking_id in wins:
                start = max(start, wins[a.booking_id].ae)
        # erst aussteigen, dann einsteigen
        for a in s.alightings:
            seats -= a.seats
            wc -= a.wheelchair
            aboard.discard(a.booking_id)
        for b in s.boardings:
            seats += b.seats
            wc += b.wheelchair
            aboard.add(b.booking_id)
        if seats > v.capacity.seats or wc > v.capacity.wheelchair:
            seg_viol = seg_viol or "CAPACITY"
        if not p.pooling_allowed and len(aboard) > 1:
            seg_viol = seg_viol or "POOLING"
        end = start + _dwell(p, s)
        for b in s.boardings:
            w = wins.get(b.booking_id)
            if w is None:
                continue
            own = b.booking_id == new_id
            if start > w.pl:
                return None, "PICKUP_WINDOW" if own else "COMMITMENT_PICKUP_WINDOW"
            if w.max_wait is not None and start - w.wait_ref > w.max_wait:
                return None, "MAX_WAIT" if own else "COMMITMENT_MAX_WAIT"
            pick_end[b.booking_id] = end
            pick_at[b.booking_id] = start
        for a in s.alightings:
            w = wins.get(a.booking_id)
            if w is None or a.booking_id not in pick_end:
                continue
            own = a.booking_id == new_id
            if start > w.al:
                return None, "ARRIVAL_WINDOW" if own else "COMMITMENT_ARRIVAL_WINDOW"
            if w.max_detour is not None and w.direct is not None:
                ride = start - pick_end[a.booking_id]
                if (ride - w.direct) * 100 > w.max_detour * w.direct:
                    return None, "MAX_DETOUR" if own else "COMMITMENT_MAX_DETOUR"
            if tight is not None and own:
                tight["wait_s"] = pick_at[a.booking_id] - w.wait_ref
                tight["ride_s"] = start - pick_end[a.booking_id]
                tight["direct_s"] = w.direct or 0
                tight["arrival_margin_s"] = w.al - start
        times.append(start)
        t, loc = end, s.node
    if seg_viol is not None:
        return None, seg_viol
    if stops and t > v.available_until:
        return None, "SHIFT_END"
    return times, None


def _drive(v: VehiclePlan, stops: Sequence[PlanStop], m: Matrix) -> int:
    loc, tot = v.start_node, 0
    for s in stops:
        tot += m[loc][s.node] or 0
        loc = s.node
    return tot


def _check_scope(vehicles: Sequence[VehiclePlan], r: Request) -> None:
    for v in vehicles:
        if v.tenant_id != r.tenant_id:
            raise ScopeViolation(f"Plan {v.vehicle_id} gehört nicht zum Mandanten der Anfrage")


def _best_for_vehicle(
    v: VehiclePlan, r: Request, p: Params, m: Matrix, deadline: float | None,
    clock: Callable[[], float], grounds: Counter[str],
) -> tuple[Variant | None, int, bool]:  # fmt: skip
    """Bestes zulässiges Positionspaar je Fahrzeug: (Variante, geprüfte Paare, Zeitüberschreitung)."""
    if r.seats > v.capacity.seats or r.wheelchair > v.capacity.wheelchair:
        grounds["CAPACITY"] += 1
        return None, 0, False
    if not r.equipment <= v.capacity.equipment:
        grounds["EQUIPMENT"] += 1
        return None, 0, False
    wins = _windows(v, r, p, m)
    base_drive = _drive(v, v.stops, m)
    pick = PlanStop(r.pickup_node, 0, (Movement(r.booking_id, r.seats, r.wheelchair),))
    drop = PlanStop(r.dropoff_node, 0, (), (Movement(r.booking_id, r.seats, r.wheelchair),))
    n = len(v.stops)
    best: tuple[int, int, int] | None = None
    best_stops: list[PlanStop] = []
    checked = 0
    local: Counter[str] = Counter()
    for i in range(n + 1):
        for j in range(i, n + 1):
            if deadline is not None and clock() >= deadline:
                return (
                    _finish(v, r, p, m, wins, best, best_stops, base_drive, grounds, local),
                    checked,
                    True,
                )
            new = list(v.stops[:i]) + [pick] + list(v.stops[i:j]) + [drop] + list(v.stops[j:])
            checked += 1
            times, why = _evaluate(v, new, wins, r.booking_id, p, m)
            if times is None:
                local[why or "UNKNOWN"] += 1
                continue
            key = (_drive(v, new, m) - base_drive, i, j + 1)
            if best is None or key < best:
                best, best_stops = key, new
    return _finish(v, r, p, m, wins, best, best_stops, base_drive, grounds, local), checked, False


def _finish(
    v: VehiclePlan, r: Request, p: Params, m: Matrix, wins: Mapping[str, _Win],
    best: tuple[int, int, int] | None, stops: list[PlanStop], base_drive: int,
    grounds: Counter[str], local: Counter[str],
) -> Variant | None:  # fmt: skip
    if best is None:
        # Gründe nur sammeln, wenn das Fahrzeug nichts geliefert hat
        grounds.update(local)
        return None
    tight: dict[str, int] = {}
    times, why = _evaluate(v, stops, wins, r.booking_id, p, m, tight)
    if times is None:  # Rechenfehler: nie ausgeben
        grounds["VALIDATION_FAILED"] += 1
        return None
    timed = [
        PlanStop(s.node, t, s.boardings, s.alightings) for s, t in zip(stops, times, strict=True)
    ]
    pos_p = next(
        k for k, s in enumerate(timed) if any(b.booking_id == r.booking_id for b in s.boardings)
    )
    pos_d = next(
        k for k, s in enumerate(timed) if any(a.booking_id == r.booking_id for a in s.alightings)
    )
    var = Variant(
        v.vehicle_id, v.run_id, v.plan_revision, v.run_id is None, pos_p, pos_d, timed, best[0],
        {
            **tight,
            "max_wait_s": p.max_wait_s, "max_detour_pct": p.max_detour_pct,
            "arrival_grace_s": p.arrival_grace_s,
        },
        p.config_revision_id,
    )  # fmt: skip
    errs = validate_variant(v, r, p, m, var)
    if errs:
        grounds["VALIDATION_FAILED"] += 1
        return None
    return var


def find_insertions(
    vehicles: Sequence[VehiclePlan], request: Request, params: Params, matrix: Matrix,
    candidate_ids: Sequence[str] | None = None, budget_s: float = DEFAULT_BUDGET_S,
    max_variants: int = MAX_VARIANTS, clock: Callable[[], float] = time.perf_counter,
) -> Outcome:  # fmt: skip
    """Sucht je Kandidatenfahrzeug das beste zulässige Paar (Einstieg, Ausstieg).

    ``candidate_ids`` begrenzt den Fahrzeugscope (Ergebnis des Kandidatenfilters, WP-M2-15); Pläne
    außerhalb werden nicht gerechnet. Pläne eines fremden Mandanten sind ein Fehler
    (:class:`ScopeViolation`). Bei Überschreitung von ``budget_s`` kommen nur bereits validierte
    Varianten zurück (``partial``), sonst die Ablehnung ``TIMEOUT_NO_VALIDATED_PLAN``.
    """
    t0 = clock()
    deadline = t0 + budget_s
    out = Outcome()
    _check_scope(vehicles, request)
    if request.pickup_node == request.dropoff_node:
        raise InputError("Einstieg und Ausstieg am selben Haltepunkt")
    scope = [v for v in vehicles if candidate_ids is None or v.vehicle_id in set(candidate_ids)]
    if not scope:
        out.rejection = NO_CANDIDATE_VEHICLE
        out.elapsed_s = clock() - t0
        return out
    grounds: Counter[str] = Counter()
    found: list[tuple[tuple[int, str, int, int], Variant]] = []
    for v in sorted(scope, key=lambda x: x.vehicle_id):
        if clock() >= deadline:
            out.partial = True
            break
        var, checked, timed_out = _best_for_vehicle(
            v, request, params, matrix, deadline, clock, grounds
        )
        out.vehicles_scanned += 1
        out.pairs_checked += checked
        if var is not None:
            found.append(
                ((var.added_drive_s, var.vehicle_id, var.pos_pickup, var.pos_dropoff), var)
            )
        if timed_out:
            out.partial = True
            break
    found.sort(key=lambda kv: kv[0])
    out.variants = [var for _, var in found[:max_variants]]
    out.grounds = dict(grounds)
    if not out.variants:
        out.rejection = TIMEOUT_NO_VALIDATED_PLAN if out.partial else NO_FEASIBLE_POSITION
    out.elapsed_s = clock() - t0
    return out


def validate_variant(v: VehiclePlan, r: Request, p: Params, m: Matrix, var: Variant) -> list[str]:
    """Unabhängiger Prüfer einer Variante aus Stopfolge und ausgewiesenen Zeiten (leer = zulässig).

    Rechnet nicht mit der Propagation der Heuristik, sondern prüft die gelieferten Zeiten gegen
    Fahrzeit, Fenster, Grenzen, Kapazität, Pooling, Reihenfolge und unveränderte Zusagen.
    """
    errs: list[str] = []
    if var.vehicle_id != v.vehicle_id or var.run_id != v.run_id:
        errs.append("Fahrzeug oder Umlauf weicht vom Plan ab")
    if var.plan_revision != v.plan_revision:
        errs.append("Planrevision weicht vom gerechneten Plan ab")
    # bestehende Stopps bleiben in Reihenfolge und Inhalt erhalten
    old = [(s.node, s.boardings, s.alightings) for s in v.stops]
    new_old = [
        (s.node, s.boardings, s.alightings)
        for s in var.stops
        if not _only_new(s, r.booking_id)
    ]  # fmt: skip
    if old != new_old:
        errs.append("bestehende Stopps verändert, entfernt oder umsortiert")
    pi = [
        k for k, s in enumerate(var.stops) if any(b.booking_id == r.booking_id for b in s.boardings)
    ]
    di = [
        k
        for k, s in enumerate(var.stops)
        if any(a.booking_id == r.booking_id for a in s.alightings)
    ]
    if len(pi) != 1 or len(di) != 1 or not pi[0] < di[0]:
        return [*errs, "Einstieg muss genau einmal und vor dem Ausstieg vorkommen"]
    wins = _windows(v, r, p, m)
    t_free, loc = v.available_from, v.start_node
    seats = wc = 0
    aboard: dict[str, tuple[int, int]] = {}  # Buchung -> (Beginn, Ende der Bedienung am Einstieg)
    for k, s in enumerate(var.stops):
        leg = m[loc][s.node]
        if leg is None or s.planned_at < t_free + leg:
            errs.append(f"Fahrzeit unterschritten bei Stopp {k}")
        for a in s.alightings:
            seats -= a.seats
            wc -= a.wheelchair
        for b in s.boardings:
            seats += b.seats
            wc += b.wheelchair
        if seats < 0 or wc < 0 or seats > v.capacity.seats or wc > v.capacity.wheelchair:
            errs.append(f"Kapazität verletzt bei Stopp {k}")
        end = s.planned_at + _dwell(p, s)
        for b in s.boardings:
            aboard[b.booking_id] = (s.planned_at, end)
            w = wins.get(b.booking_id)
            if w is not None:
                if not w.pe <= s.planned_at <= w.pl:
                    errs.append(f"Abholfenster verletzt: {b.booking_id}")
                if w.max_wait is not None and s.planned_at - w.wait_ref > w.max_wait:
                    errs.append(f"Wartezeit über Grenze: {b.booking_id}")
        for a in s.alightings:
            w = wins.get(a.booking_id)
            if w is not None:
                if not w.ae <= s.planned_at <= w.al:
                    errs.append(f"Ankunftsfenster verletzt: {a.booking_id}")
                start = aboard.get(a.booking_id)
                if start is not None and w.max_detour is not None and w.direct is not None:
                    ride = s.planned_at - start[1]
                    if (ride - w.direct) * 100 > w.max_detour * w.direct:
                        errs.append(f"Umweg über Grenze: {a.booking_id}")
            aboard.pop(a.booking_id, None)
        if not p.pooling_allowed and len({*aboard}) > 1:
            errs.append(
                f"Mitfahrt verschiedener Buchungen bei Stopp {k} trotz pooling_allowed=false"
            )
        t_free, loc = end, s.node
    if seats != 0 or wc != 0:
        errs.append("Belegung am Ende nicht null")
    if var.stops and t_free > v.available_until:
        errs.append("Schichtende überschritten")
    return errs


def _only_new(s: PlanStop, booking_id: str) -> bool:
    moves = [*s.boardings, *s.alightings]
    return bool(moves) and all(mv.booking_id == booking_id for mv in moves)


# ---- JSON-Schnittstelle (Kern <-> Worker) ----


def _int(d: Mapping[str, Any], key: str, default: int | None = None) -> int:
    v = d.get(key, default)
    if isinstance(v, bool) or not isinstance(v, int):
        raise InputError(f"{key}: Ganzzahl erwartet")
    return v


def _opt(d: Mapping[str, Any], key: str) -> int | None:
    return None if d.get(key) is None else _int(d, key)


def _moves(items: Sequence[Mapping[str, Any]]) -> tuple[Movement, ...]:
    return tuple(
        Movement(str(i["booking_id"]), _int(i, "seats", 0), _int(i, "wheelchair", 0)) for i in items
    )


def parse_request(
    body: Mapping[str, Any],
) -> tuple[list[VehiclePlan], Request, Params, Matrix, list[str] | None, float]:
    """Wandelt den Eingang (JSON, siehe contract/insert-request.json) in Modellobjekte."""
    try:
        tenant = str(body["tenant_id"])
        rq = body["request"]
        req = Request(
            str(rq["booking_id"]), tenant, _int(rq, "pickup_node"), _int(rq, "dropoff_node"),
            _int(rq, "pickup_earliest"), _int(rq, "pickup_latest"),
            _int(rq, "arrival_earliest"), _int(rq, "arrival_latest"),
            _int(rq, "seats", 1), _int(rq, "wheelchair", 0), frozenset(rq.get("equipment", [])),
            _opt(rq, "wait_reference"),
        )  # fmt: skip
        pr = body.get("params", {})
        params = Params(
            bool(pr.get("pooling_allowed", True)), _int(pr, "stop_dwell_s", 60), _int(pr, "wheelchair_boarding_s", 120),
            _opt(pr, "max_wait_s"), _opt(pr, "max_detour_pct"), _int(pr, "arrival_grace_s", 0), pr.get("config_revision_id"),
        )  # fmt: skip
        vehicles: list[VehiclePlan] = []
        for vj in body["vehicles"]:
            cap = vj["capacity"]
            vehicles.append(
                VehiclePlan(
                    str(vj["vehicle_id"]), str(vj["tenant_id"]), vj.get("run_id"), _int(vj, "plan_revision", 0),
                    Capacity(_int(cap, "seats"), _int(cap, "wheelchair", 0), frozenset(cap.get("equipment", []))),
                    _int(vj, "start_node"), _int(vj, "available_from"), _int(vj, "available_until"),
                    tuple(PlanStop(_int(s, "node"), _int(s, "planned_at", 0), _moves(s.get("boardings", [])), _moves(s.get("alightings", []))) for s in vj.get("stops", [])),
                    tuple(
                        Commitment(
                            str(c["booking_id"]), _int(c, "pickup_earliest"), _int(c, "pickup_latest"),
                            _int(c, "arrival_earliest"), _int(c, "arrival_latest"), _int(c, "arrival_grace_s", 0),
                            _opt(c, "max_wait_s"), _opt(c, "wait_reference"), _opt(c, "max_detour_pct"), _opt(c, "direct_s"),
                        )
                        for c in vj.get("commitments", [])
                    ),
                )
            )  # fmt: skip
        matrix = [
            [None if x is None else _int({"x": x}, "x") for x in row] for row in body["matrix"]
        ]
        size = len(matrix)
        if any(len(row) != size for row in matrix):
            raise InputError("matrix: quadratisch erwartet")
        nodes = [req.pickup_node, req.dropoff_node]
        for v in vehicles:
            nodes += [v.start_node, *(s.node for s in v.stops)]
        if any(not 0 <= n < size for n in nodes):
            raise InputError("Knoten außerhalb der Matrix")
        cand = body.get("candidate_vehicle_ids")
        budget = float(body.get("budget_ms", DEFAULT_BUDGET_S * 1000)) / 1000.0
    except (KeyError, TypeError, AttributeError) as e:
        raise InputError(f"Eingang unvollständig: {e}") from e
    return vehicles, req, params, matrix, None if cand is None else [str(c) for c in cand], budget


def _stop_json(s: PlanStop, seq: int) -> dict[str, Any]:
    def mv(ms: tuple[Movement, ...]) -> list[dict[str, Any]]:
        return [
            {"booking_id": x.booking_id, "seats": x.seats, "wheelchair": x.wheelchair} for x in ms
        ]

    return {
        "seq": seq,
        "node": s.node,
        "planned_at": s.planned_at,
        "boardings": mv(s.boardings),
        "alightings": mv(s.alightings),
    }


def outcome_json(o: Outcome) -> dict[str, Any]:
    return {
        "variants": [
            {
                "vehicle_id": v.vehicle_id, "run_id": v.run_id, "plan_revision": v.plan_revision, "new_run": v.new_run,
                "pos_pickup": v.pos_pickup, "pos_dropoff": v.pos_dropoff, "added_drive_s": v.added_drive_s,
                "config_revision_id": v.config_revision_id, "checks": v.checks,
                "stops": [_stop_json(s, k) for k, s in enumerate(v.stops)],
            }
            for v in o.variants
        ],
        "rejection": o.rejection, "grounds": o.grounds, "partial": o.partial,
        "stats": {"vehicles_scanned": o.vehicles_scanned, "pairs_checked": o.pairs_checked, "elapsed_s": round(o.elapsed_s, 6)},
    }  # fmt: skip


def handle_insert(body: Mapping[str, Any]) -> dict[str, Any]:
    vehicles, req, params, matrix, cand, budget = parse_request(body)
    return outcome_json(find_insertions(vehicles, req, params, matrix, cand, budget))
