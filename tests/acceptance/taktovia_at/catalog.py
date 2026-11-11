"""Katalog der 106 Referenztests und 22 PH-AT-Tests (benannte, noch nicht implementierte Szenarien)."""
import csv

from . import AT_DIR

CATALOG_PATH = AT_DIR / "catalog" / "scenarios.csv"
MILESTONES = ["M1", "M2", "M3", "M4", "M5", "M6a", "M6b", "M6c", "M7a", "M7b", "M7c", "M7d", "M8a", "M8b", "M8c", "M8d", "M8e", "M9a", "M9b"]


def load():
    with open(CATALOG_PATH, newline="") as f:
        return list(csv.DictReader(f))


def implemented_ids():
    from .scenario import REGISTRY
    from . import scenarios  # noqa: F401  (registriert Szenarien)
    return set(REGISTRY)


def status_of(test_id):
    return "IMPLEMENTED" if test_id in implemented_ids() else "NOT_IMPLEMENTED"
