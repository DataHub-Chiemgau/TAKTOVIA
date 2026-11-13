"""WP-M2-48: fortlaufender Lasttest für Buchung und Disposition ab M2 (Angebotsbildung, Hold, Confirm, Status, Storno) mit zwei Mandanten gegen die echte Anwendung, Messung gegen die vorläufigen
Lastenheftziele „Angebote p95 <= 5 s“ und „Standard-API p95 <= 1 s“ (Risiko „Solverlaufzeit und Kapazitätssperren unter Last“, Umsetzungsplan §7).

Das Profil `profiles/m2-buchung.json` ist das **kleine Regressionsprofil** (50 Fahrzeuge je Mandant, 200 Sitzungen, 30 s) für den regelmäßigen Lauf ([`ci-m2-lasttest.yml.example`](ci-m2-lasttest.yml.example)); der Referenzlauf
mit 250 Fahrzeugen und 5.000 Sitzungen ist `test_referenzprofil.py` (WP-M5-10). Der Test schlägt fehl, wenn eines der beiden Ziele im Regressionsprofil verfehlt wird, wenn ein Mandant nicht
belastet wurde oder wenn die Invariantenabfrage nach dem Lauf einen Verstoß findet. Voraussetzungen wie `test_referenzprofil.py` (Opt-in `TAKTOVIA_PERF_RUN=1`)."""
from __future__ import annotations

import os
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[1] / "db"))

from refload import orchestrate, runner  # noqa: E402
from refload.prereq import require_app  # noqa: E402

_CACHE: dict = {}


def m2_run() -> dict:
    if "rep" not in _CACHE:
        require_app()
        p = runner.profile_from_json(HERE / "profiles" / "m2-buchung.json")
        out = Path(os.environ.get("TAKTOVIA_PERF_OUT", HERE / "out")) / "m2-lauf"
        _CACHE["rep"] = orchestrate.execute_profile(p, out, title="Fortlaufender Lasttest M2 (Buchung und Disposition)", race_stage="100 %", dataset_note="Regressionsprofil")
    return _CACHE["rep"]


class M2Lastprofil(unittest.TestCase):
    def test_angebot_hold_confirm_mit_zwei_mandanten_gegen_die_vorlaeufigen_ziele(self):
        rep = m2_run()
        r = rep["results"]
        self.assertEqual(sorted(r["per_tenant"]), ["A", "B"])
        for t in ("A", "B"):
            for ep in ("POST /v1/ride-offers", "POST /v1/ride-offers/{id}/hold", "POST /v1/bookings"):
                self.assertGreater(r["per_tenant_endpoint"][t][ep]["n"], 0, f"Mandant {t}: {ep} nicht gemessen")
        verdict = {e["criterion"].split(" (")[0]: e["verdict"] for e in rep["evaluation"]}
        self.assertEqual(verdict["Angebote p95 <= 5 s"], "erfüllt", [e for e in rep["evaluation"] if e["criterion"].startswith("Angebote")])
        self.assertEqual(verdict["Standard-API p95 <= 1 s"], "erfüllt", [e for e in rep["evaluation"] if e["criterion"].startswith("Standard")])
        self.assertEqual(verdict["Fehlerrate"], "erfüllt")
        self.assertFalse(rep["valid_for_acceptance"])          # Zielwerte vorläufig (D-05)

    def test_invarianten_nach_dem_lauf_und_wettlauf_letzter_platz(self):
        rep = m2_run()
        inv = rep["results"]["invarianten"]
        self.assertEqual(inv["verstoesse_gesamt"], 0, inv)
        self.assertGreater(inv["geprueft"]["buchungen"], 10)
        rc = rep["results"]["wettlauf_letzter_platz"]
        self.assertEqual((rc["holds_201"], rc["verschiedene_buchungs_ids"]), (1, 1), rc)
        iso = rep["results"]["isolation"]
        self.assertTrue(iso["gleiche_namen"] and iso["alle_fremden_ids_404"] and iso["fremde_ids_in_listen"] == 0, iso)


if __name__ == "__main__":
    unittest.main()
