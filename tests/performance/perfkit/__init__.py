"""Gerüst für Lastmessungen (WP-M0-25): Lastprofil, Runner mit tatsächlichen Perzentilen, Berichtsformat."""
import sys
from pathlib import Path

PERF_DIR = Path(__file__).resolve().parents[1]
TESTS_DIR = PERF_DIR.parent
REPO = TESTS_DIR.parent
for _p in (TESTS_DIR / "lib", REPO / "db"):
    if str(_p) not in sys.path:
        sys.path.insert(0, str(_p))
