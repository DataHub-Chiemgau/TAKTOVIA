"""Auswertung eines ausgefüllten Restore-Drehbuchs: RPO und RTO aus den protokollierten Zeitpunkten."""
import json
from datetime import datetime

import jsonschema

from . import PERF_DIR

SCHEMA = PERF_DIR / "schema" / "restore-drehbuch.schema.json"
REQUIRED_FIELDS = ["ausfallzeitpunkt", "letzter_wiederherstellbarer_commit", "zeitpunkt_e2e_probe", "abgleich_externe_vorgaenge"]


def _t(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def validate_form(rec):
    jsonschema.validate(rec, json.loads(SCHEMA.read_text()))


def evaluate(rec):
    """-> Kopie mit Bewertung. Verlangt Status DURCHGEFUEHRT und alle Protokollfelder; sonst ValueError."""
    validate_form(rec)
    if rec["status"] != "DURCHGEFUEHRT":
        raise ValueError("Vorlage ist nicht durchgeführt; keine Bewertung")
    missing = [k for k in ("ausfallzeitpunkt", "zeitpunkt_e2e_probe") if not rec[k]]
    if not rec["letzter_wiederherstellbarer_commit"]["zeitpunkt"]:
        missing.append("letzter_wiederherstellbarer_commit.zeitpunkt")
    if missing:
        raise ValueError("Protokollfelder fehlen: " + ", ".join(missing))
    out = json.loads(json.dumps(rec))
    rpo = (_t(rec["ausfallzeitpunkt"]) - _t(rec["letzter_wiederherstellbarer_commit"]["zeitpunkt"])).total_seconds() / 60
    rto = (_t(rec["zeitpunkt_e2e_probe"]) - _t(rec["ausfallzeitpunkt"])).total_seconds() / 3600
    if rpo < 0 or rto < 0:
        raise ValueError("Zeitpunkte widersprüchlich (Commit nach Ausfall oder Probe vor Ausfall)")
    out["bewertung"] = {"rpo_min_gemessen": round(rpo, 2), "rto_h_gemessen": round(rto, 3),
                        "rpo_erfuellt": rpo <= rec["referenzziele"]["rpo_min"], "rto_erfuellt": rto <= rec["referenzziele"]["rto_h"]}
    return out
