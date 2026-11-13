"""WP-M2-48: Tests der M2-Gate-Abnahmesuite (Szenarien, Evidenzformat, Rückverfolgbarkeit, Umfangsdokument).

Der Gesamtlauf verlangt die JUnit-Ergebnisse des Backends (`./gradlew build`), `web/node_modules` und Chrome; fehlt etwas, schlägt der betroffene Schritt fehl (er gilt nie als bestanden). Ohne Datenbank-Zugangsdaten
wird der Gesamtlauf übersprungen, mit `TAKTOVIA_REQUIRE_DB=true` schlägt er fehl."""
import csv
import io
import json
import os
import re
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))

from m1_gate import junit, traceability  # noqa: E402
from m2_gate import run as gate_run, scenarios  # noqa: E402

DB_ENV = ("TAKTOVIA_DB_ADMIN_PASSWORD", "TAKTOVIA_DB_RUNTIME_PASSWORD", "TAKTOVIA_DB_MIGRATOR_PASSWORD")
NEED_DB = [k for k in DB_ENV if not os.environ.get(k)]


class Umfang(unittest.TestCase):
    def test_umfangsdokument_deckt_alle_szenarien_mit_geprueftem_umfang_und_zielmeilenstein(self):
        text = (HERE / "docs" / "m2-gate-umfang.md").read_text(encoding="utf-8")
        rows = {m.group(1): m.group(0) for m in re.finditer(r"(?m)^\| ((?:PH-AT|AT|E2E)-\d+) \|.*$", text)}
        self.assertEqual(sorted(rows), sorted(gate_run.ALL_IDS))
        for tid, row in rows.items():
            cells = [c.strip() for c in row.strip("|").split("|")]
            self.assertEqual(len(cells), 4, tid)
            self.assertTrue(cells[1] and cells[2] and re.search(r"M\d|P2", cells[3]), f"{tid}: geprüfter Umfang, Offenes und Zielmeilenstein müssen benannt sein")

    def test_alle_gate_tests_sind_als_szenarien_mit_source_keys_registriert(self):
        self.assertEqual(sorted(set(gate_run.ALL_IDS) - set(scenarios.SCENARIOS)), [])
        for tid in gate_run.ALL_IDS:
            self.assertGreaterEqual(len(scenarios.SCENARIOS[tid].meta["source_key"]), 2, tid)

    def test_at_01_und_at_13_nennen_den_offenen_teil_ausdruecklich(self):
        src = (HERE / "m2_gate" / "scenarios.py").read_text(encoding="utf-8")
        for tid, marker in (("def at_01", "Fahrerzuweisung in der Driver-App"), ("def at_13", "Parallelfahrtenregel"), ("def at_19", "manuelles Prüfprotokoll")):
            body = src[src.index(tid):].split("\n\n\n")[0]
            self.assertIn(marker if marker != "Parallelfahrtenregel" else "WP-M6a-29", body)
            self.assertIn("offen(ctx", body)

    def test_jeder_vom_szenario_genannte_backend_test_existiert_im_quelltext(self):
        """Ohne JUnit-Ergebnisse prüft dieser Test, dass Klasse und Methode im Quelltext vorkommen (Tippfehler fallen sofort auf)."""
        src = (HERE / "m2_gate" / "scenarios.py").read_text(encoding="utf-8")
        pairs = set(re.findall(r'\("(\w+Test)", "(\w+)"\)', src))
        self.assertGreater(len(pairs), 60)
        files: dict = {}
        for p in (REPO / "backend").glob("**/src/test/**/*.kt"):
            files[p.stem] = files.get(p.stem, "") + p.read_text(encoding="utf-8", errors="replace")
        missing = [(c, m) for c, m in sorted(pairs) if c not in files or m not in files[c]]
        self.assertEqual(missing, [])

    def test_datenbanktests_der_szenarien_existieren(self):
        src = (HERE / "m2_gate" / "scenarios.py").read_text(encoding="utf-8")
        mods = {m for call in re.findall(r"dbtests\(ctx,([^)]*)\)", src) for m in re.findall(r'"(test_[a-z0-9_]+)"', call)}
        self.assertGreater(len(mods), 10)
        self.assertEqual([m for m in sorted(mods) if not (REPO / "db" / "tests" / f"{m}.py").is_file()], [])


class Rueckverfolgbarkeit(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name); self.addCleanup(self.tmp.cleanup)
        (self.root / "docs/umsetzungsplan").mkdir(parents=True); (self.root / "docs/pflichtenheft").mkdir(parents=True); (self.root / "tests/acceptance/evidence/m2").mkdir(parents=True)

    def write(self, e2e):
        with (self.root / "docs/umsetzungsplan/arbeitspakete.csv").open("w", encoding="utf-8", newline="") as f:
            csv.writer(f, delimiter=";").writerow(["source_key", "meilenstein", "prioritaet_original", "status", "detailvertrag", "testfall"])
        with (self.root / "docs/pflichtenheft/e2e.csv").open("w", encoding="utf-8", newline="") as f:
            w = csv.writer(f, delimiter=";"); w.writerow(["test_id_original", "status", "build", "nachweis", "freigabe"]); w.writerows(e2e)

    def test_m2_zeile_mit_evidenz_aus_dem_m2_lauf_ist_zulaessig_fremder_ordner_oder_ergebnis_nicht(self):
        ev = self.root / "tests/acceptance/evidence/m2/AT-03-run-0123456789abcdef.json"
        ev.write_text(json.dumps({"run_id": "run-0123456789abcdef", "test": {"id": "AT-03"}, "software_build": {"build_id": "abc"}, "result": "PASSED", "acceptance_decision": "PENDING"}))
        nach = "run-0123456789abcdef; tests/acceptance/evidence/m2/AT-03-run-0123456789abcdef.json"
        self.write([["AT-03", "Bestanden (M2-Umfang)", "abc", nach, "Ausstehend (Auftraggeber, D-12)"], ["AT-01", "Nicht ausgeführt", "", "", ""]])
        self.assertEqual(traceability.check(self.root)[0], [])
        self.write([["AT-03", "Teilweise bestanden (M2-Umfang)", "abc", nach, "Ausstehend (Auftraggeber, D-12)"]])
        self.assertEqual(traceability.check(self.root)[0], [])
        self.write([["AT-03", "Fehlgeschlagen", "abc", nach, "Ausstehend (Auftraggeber, D-12)"]])
        self.assertTrue(any("widerspricht dem Ergebnis" in e for e in traceability.check(self.root)[0]))
        self.write([["AT-03", "Bestanden (M2-Umfang)", "abc", nach.replace("evidence/m2", "evidence/m1"), "Ausstehend (Auftraggeber, D-12)"]])
        self.assertTrue(any("gehört zur Suite M2" in e for e in traceability.check(self.root)[0]))
        self.write([["AT-03", "Bestanden (M2-Umfang)", "abc", nach, "Freigegeben"]])
        self.assertTrue(any("Abnahmeentscheidung" in e for e in traceability.check(self.root)[0]))

    def test_echte_e2e_csv_ist_aus_laeufen_befuellt(self):
        errors, _ = traceability.check()
        self.assertEqual([e for e in errors if e.startswith(("R1", "R3"))], [])

    def test_e2e_write_aendert_nur_at_zeilen_der_suite(self):
        src = REPO / "docs/pflichtenheft/e2e.csv"
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "e2e.csv"; shutil.copy(src, p)
            before = list(csv.reader(io.StringIO(p.read_text(encoding="utf-8")), delimiter=";"))
            recs = [{"test": {"id": tid}, "run_id": "run-0123456789abcdef", "result": "PASSED", "software_build": {"build_id": "deadbeef"}, "_path": f"/x/{tid}-run-0123456789abcdef.json"}
                    for tid in ("AT-03", "AT-01", "PH-AT-004", "E2E-096")]
            gate_run.write_e2e(recs, p)
            after = list(csv.reader(io.StringIO(p.read_text(encoding="utf-8")), delimiter=";"))
        changed = {a[0]: a[5] for a, b in zip(after, before) if a != b}
        self.assertEqual(changed, {"AT-01": "Teilweise bestanden (M2-Umfang)", "AT-03": "Bestanden (M2-Umfang)"})      # E2E-096 (M1-Zeile) und PH-AT-004 (keine Zeile) bleiben unberührt


@unittest.skipIf(NEED_DB and os.environ.get("TAKTOVIA_REQUIRE_DB") != "true", f"Datenbank-Zugangsdaten nicht gesetzt: {NEED_DB}")
class Gesamtlauf(unittest.TestCase):
    def test_alle_szenarien_gruen_mit_evidenz_ohne_fahrgastdaten(self):
        if NEED_DB:
            self.fail(f"Datenbank erforderlich: {NEED_DB}")
        if not junit.load_backend():
            if os.environ.get("TAKTOVIA_GATE_REQUIRE_BACKEND") == "true":
                self.fail("keine JUnit-Ergebnisse des Backends (./gradlew build vor der Gate-Suite)")
            self.skipTest("keine JUnit-Ergebnisse des Backends vorhanden (./gradlew build zuerst)")
        from taktovia_at import evidence as ev
        with tempfile.TemporaryDirectory() as out:
            records = gate_run.run("taktovia_at_m2_gate_test", Path(out))
            s = gate_run.summary(records)
            self.assertEqual([t["id"] for t in s["tests"]], gate_run.ALL_IDS)
            self.assertEqual({t["id"]: t["failed"] for t in s["tests"] if t["result"] != "PASSED"}, {})
            for r in records:
                plain = {k: v for k, v in r.items() if k != "_path"}
                self.assertEqual(r["acceptance_decision"], "PENDING")
                self.assertGreaterEqual(len(r["soll_ist"]), 5, r["test"]["id"])
                ev.validate(plain)
                self.assertEqual(ev.check_no_personal_data(plain), [])


if __name__ == "__main__":
    unittest.main()
