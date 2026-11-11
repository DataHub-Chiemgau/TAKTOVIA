"""Auswertung des Bewertungsrasters (WP-M0-30). Rechnet nur; die Bewertung selbst setzt das Team nach der Messung."""
import json

from . import HERE


def load_grid():
    return json.loads((HERE / "bewertungsraster.json").read_text())


def score(ratings: dict, knockouts: dict = None, grid=None):
    """ratings: {K1..K8: 0..4}; knockouts: {KO1: bool, KO2: bool} (True = ausgelöst). -> {percent, weighted, max, knockout_triggered, recommendable}."""
    grid = grid or load_grid()
    ids = [c["id"] for c in grid["criteria"]]
    missing = [i for i in ids if i not in ratings]
    if missing:
        raise ValueError("Bewertung unvollständig: " + ", ".join(missing))
    for k, v in ratings.items():
        if k not in ids or isinstance(v, bool) or not isinstance(v, int) or not 0 <= v <= 4:
            raise ValueError(f"ungültige Bewertung {k}={v!r}")
    total = sum(c["weight"] * ratings[c["id"]] for c in grid["criteria"])
    maximum = sum(c["weight"] * 4 for c in grid["criteria"])
    ko_ids = [k["id"] for k in grid["knockout"]]
    knockouts = knockouts or {}
    unknown = set(knockouts) - set(ko_ids)
    if unknown:
        raise ValueError("unbekannte K.O.-Kriterien: " + ", ".join(sorted(unknown)))
    if set(ko_ids) - set(knockouts):
        raise ValueError("K.O.-Prüfung unvollständig: " + ", ".join(sorted(set(ko_ids) - set(knockouts))))
    triggered = sorted(k for k, v in knockouts.items() if v)
    pct = round(100 * total / maximum, 1)
    return {"weighted": total, "max": maximum, "percent": pct, "knockout_triggered": triggered, "recommendable": not triggered and pct >= 60.0}
