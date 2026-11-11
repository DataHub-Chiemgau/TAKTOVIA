"""Unabhängiger Zulässigkeitsprüfer (WP-M0-27 AK 2).

Bewusst ohne Import der Einfügeheuristik: prüft nur aus Instanz, Fahrzeug, Stopfolge und den
ausgewiesenen Startzeiten. Liefert die Liste verletzter harter Constraints (leer = zulässig).
"""

from __future__ import annotations

from collections.abc import Sequence

from .model import BREAK, DROPOFF, PICKUP, Instance, Stop, Vehicle


def validate_route(
    inst: Instance, v: Vehicle, stops: Sequence[Stop], starts: Sequence[int]
) -> list[str]:
    errs: list[str] = []
    if len(stops) != len(starts):
        return ["Länge Stopfolge != Länge Startzeiten"]
    T = inst.T
    t_free = v.shift_start  # frühester Zeitpunkt, ab dem das Fahrzeug frei ist
    loc = v.depot
    seats = wc = 0
    picked: dict[int, int] = {}
    dropped: set[int] = set()
    breaks = 0
    for k, (s, st) in enumerate(zip(stops, starts, strict=True)):
        if s.kind == BREAK:
            breaks += 1
            if seats or wc:
                errs.append(f"Pause mit Fahrgästen an Bord bei Stop {k}")
            if st < t_free:
                errs.append(f"Pausenbeginn {st} < frei ab {t_free}")
            if not (v.break_e <= st <= v.break_l):
                errs.append(f"Pausenfenster verletzt: {st}")
            t_free = st + v.break_dur
            continue
        r = inst.requests.get(s.req)
        if r is None:
            errs.append(f"unbekannte Anfrage {s.req}")
            continue
        node = r.pickup if s.kind == PICKUP else r.dropoff
        if st < t_free + T[loc][node]:
            errs.append(f"Fahrzeit unterschritten bei Stop {k} (Anfrage {r.id})")
        if s.kind == PICKUP:
            if r.id in picked:
                errs.append(f"Pickup doppelt: {r.id}")
            picked[r.id] = st
            if not (r.pu_e <= st <= r.pu_l):
                errs.append(f"Pickup-Zeitfenster verletzt: Anfrage {r.id}")
            seats += r.seats
            wc += r.wc
            if seats > v.seats or wc > v.wc:
                errs.append(f"Kapazität (Sitze/Rollstuhl) überschritten bei Stop {k}")
        elif s.kind == DROPOFF:
            if r.id not in picked:
                errs.append(f"Dropoff vor Pickup: {r.id}")
            else:
                if st - picked[r.id] > r.max_ride:
                    errs.append(f"Maximale Fahrzeit verletzt: Anfrage {r.id}")
            if r.id in dropped:
                errs.append(f"Dropoff doppelt: {r.id}")
            dropped.add(r.id)
            if not (r.do_e <= st <= r.do_l):
                errs.append(f"Dropoff-Zeitfenster verletzt: Anfrage {r.id}")
            seats -= r.seats
            wc -= r.wc
        t_free = st + r.service
        loc = node
    if set(picked) != dropped:
        errs.append("Pickup/Dropoff nicht paarweise vollständig")
    if breaks != (1 if v.break_dur > 0 else 0):
        errs.append(f"Pausenanzahl {breaks} unzulässig")
    if stops and t_free > v.shift_end:
        errs.append(f"Schichtende überschritten: {t_free} > {v.shift_end}")
    return errs


def validate_commitments(before: Sequence[Stop], after: Sequence[Stop]) -> list[str]:
    """Bestehende Zusagen bleiben erhalten: Stopfolge vorher ist Teilfolge der neuen."""
    it = iter(after)
    if not all(any(a == b for b in it) for a in before):
        return ["bestehende Stops nicht in unveränderter Reihenfolge enthalten"]
    return []
