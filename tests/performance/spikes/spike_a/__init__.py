"""Spike (a) WP-M0-26: Hold/Confirm unter 100 parallelen Transaktionen auf PostGIS. Wegwerf-Prototyp, nicht Produktivcode."""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
for p in (REPO / "tests" / "acceptance", REPO / "tests" / "performance", REPO / "tests" / "lib", REPO / "db"):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))
