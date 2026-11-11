"""Lastprofil laden und prüfen. Ein Profil ohne Pflichtangaben wird abgewiesen (WP-M0-25, negativer Test)."""
import json
from pathlib import Path

import jsonschema

from . import PERF_DIR

SCHEMA = PERF_DIR / "schema" / "profile.schema.json"


class ProfileError(Exception):
    def __init__(self, problems):
        super().__init__("; ".join(problems))
        self.problems = problems


def load(path_or_dict):
    data = path_or_dict if isinstance(path_or_dict, dict) else json.loads(Path(path_or_dict).read_text())
    return validate(data)


def validate(p: dict):
    v = jsonschema.Draft202012Validator(json.loads(SCHEMA.read_text()))
    problems = [f"{'/'.join(str(x) for x in e.absolute_path) or '$'}: {e.message}" for e in v.iter_errors(p)]
    if not problems:
        share = p["partner_share"]
        if abs(share["local"] + share["partner"] - 1.0) > 1e-9:
            problems.append("partner_share: local + partner muss 1 ergeben")
        if p["mode"] == "measurement":
            for k, o in p["open_parameters"].items():
                if o["value"] is None:
                    problems.append(f"open_parameters.{k}: für mode=measurement muss ein Wert vereinbart sein (offener Pflichtparameter, D-05)")
            if p["candidate_fleet"]["vehicles_per_tenant"] is None:
                problems.append("candidate_fleet.vehicles_per_tenant: für mode=measurement erforderlich")
    if problems:
        raise ProfileError(problems)
    return p
