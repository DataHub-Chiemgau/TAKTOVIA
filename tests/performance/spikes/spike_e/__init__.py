"""Spike (e) WP-M0-30: streckenbezogener Routingvergleich (D-09). Adapter-Schnittstelle, Referenzimplementierung, Messwerkzeug und Vorlagen."""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
for p in (REPO / "tests" / "lib", REPO / "tests" / "performance"):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))
