"""Spike (d) WP-M0-29: PSP-Sandbox. Port, Konformitätsprüfung (läuft gegen den Simulator) und Vorlagen für reale Kandidaten."""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
for p in (REPO / "tests" / "lib", REPO / "tests" / "sandbox" / "psp", REPO / "tests" / "performance"):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))
