"""Akzeptanztestgerüst (WP-M0-22): Szenarien mit Test-ID, Zwei-Mandanten-Seed, Evidenzdatensätze."""
import sys
from pathlib import Path

AT_DIR = Path(__file__).resolve().parents[1]
TESTS_DIR = AT_DIR.parent
REPO = TESTS_DIR.parent

for _p in (TESTS_DIR / "lib", TESTS_DIR / "sandbox" / "psp", TESTS_DIR / "sandbox" / "partner", REPO / "db"):
    if str(_p) not in sys.path:
        sys.path.insert(0, str(_p))
