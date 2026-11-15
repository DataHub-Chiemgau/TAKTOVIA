"""WP-M5-15: Struktur des AT-23-Läufers (Szenario, Eintrag in e2e.csv, Evidenz). Der Lauf selbst (Ausfallübung, Minuten) ist `python tests/acceptance/m5_at23/run.py`; seine Evidenz liegt unter
tests/acceptance/evidence/m5/ und wird hier gegen e2e.csv und die Rückverfolgbarkeit geprüft. Die Übung wird in diesem Test NICHT erneut ausgeführt (sie ist in tests/performance/test_ausfalluebung.py)."""
import csv
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))


class AT23Laeufer(unittest.TestCase):
    def test_szenario_ist_registriert_und_nennt_die_anforderung(self):
        from m5_at23 import scenarios  # noqa: F401
        from taktovia_at.scenario import REGISTRY
        fn = REGISTRY["AT-23"]
        self.assertEqual(fn.meta["source_key"], ["LH-6.19-NFR-007"])

    def test_write_e2e_traegt_nur_den_eigenen_testfall_aus_dem_lauf_ein(self):
        from m5_at23 import run
        text = (REPO / "docs" / "pflichtenheft" / "e2e.csv").read_text(encoding="utf-8")
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "e2e.csv"
            p.write_text(text, encoding="utf-8")
            record = {"result": "PASSED", "run_id": "run-0123456789abcdef", "software_build": {"build_id": "abc+lokal"}, "_path": "/x/AT-23-run-0123456789abcdef.json"}
            run.write_e2e(record, p)
            before = list(csv.reader(io.StringIO(text), delimiter=";"))
            after = list(csv.reader(io.StringIO(p.read_text(encoding="utf-8")), delimiter=";"))
        changed = [b[0] for b, a in zip(before, after) if a != b]
        self.assertEqual(len(before), len(after))
        self.assertEqual(changed, ["AT-23"])
        row = next(r for r in after if r[0] == "AT-23")
        self.assertEqual(row[5], run.STATUS_OK); self.assertIn("run-0123456789abcdef; tests/acceptance/evidence/m5/AT-23-run-0123456789abcdef.json", row[7]); self.assertIn("Ausstehend", row[8])

    def test_eingetragene_evidenz_existiert_und_passt_zu_e2e_csv(self):
        rows = list(csv.DictReader((REPO / "docs" / "pflichtenheft" / "e2e.csv").open(encoding="utf-8", newline=""), delimiter=";"))
        row = next(r for r in rows if r["test_id_original"] == "AT-23")
        if row["status"] == "Nicht ausgeführt":
            self.skipTest("AT-23 noch nicht ausgeführt")
        run_id, path = (x.strip() for x in row["nachweis"].split(";", 1))
        f = REPO / path
        self.assertTrue(f.is_file(), path)
        ev = json.loads(f.read_text(encoding="utf-8"))
        self.assertEqual((ev["run_id"], ev["test"]["id"], ev["software_build"]["build_id"], ev["acceptance_decision"]), (run_id, "AT-23", row["build"], "PENDING"))
        self.assertEqual(ev["result"], "PASSED"); self.assertTrue(all(c["ok"] for c in ev["soll_ist"]))
        steps = " ".join(c["step"] for c in ev["soll_ist"])
        for kw in ("RPO", "RTO", "zweite Belastung", "Mandantentrennung", "Abgleichbericht"):
            self.assertIn(kw, steps)
        self.assertTrue(ev["environment"]["simulated_components"], "Ersatzkomponenten sind in der Evidenz benannt")


if __name__ == "__main__":
    unittest.main()
