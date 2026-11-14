"""WP-M3-56: Tests der M3-Gate-Abnahmesuite (Szenarien, Evidenzformat, Rückverfolgbarkeit, Umfangsdokument).

Der Gesamtlauf verlangt die JUnit-Ergebnisse des Backends (`./gradlew build`) und `web/node_modules`; fehlt etwas, schlägt der betroffene Schritt fehl (er gilt nie als bestanden). Ohne Datenbank-Zugangsdaten
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

from m1_gate import junit  # noqa: E402
from m2_gate import scenarios as m2_scenarios  # noqa: E402
from m3_gate import run as gate_run, scenarios  # noqa: E402

DB_ENV = ("TAKTOVIA_DB_ADMIN_PASSWORD", "TAKTOVIA_DB_RUNTIME_PASSWORD", "TAKTOVIA_DB_MIGRATOR_PASSWORD")
NEED_DB = [k for k in DB_ENV if not os.environ.get(k)]
REFERENZTESTS = ["PH-AT-020", "AT-08", "AT-09", "AT-10", "AT-11", "AT-26", "AT-27", "AT-28", "AT-29", "AT-30", "AT-31", "AT-32", "AT-33", "AT-34", "AT-35", "AT-36", "AT-50", "AT-51", "AT-60"]


class Umfang(unittest.TestCase):
    def test_alle_19_referenztests_sind_als_ausfuehrbare_szenarien_mit_source_keys_registriert(self):
        self.assertEqual(sorted(gate_run.GATE_IDS), sorted(REFERENZTESTS))
        self.assertEqual(len(REFERENZTESTS), 19)
        self.assertEqual(sorted(set(gate_run.ALL_IDS) - set(scenarios.SCENARIOS)), [])
        for tid in gate_run.ALL_IDS:
            self.assertGreaterEqual(len(scenarios.SCENARIOS[tid].meta["source_key"]), 2, tid)
        self.assertEqual(sorted(set(gate_run.REGRESSION_IDS) - set(m2_scenarios.SCENARIOS)), [], "M2-Gate-Regression")

    def test_umfangsdokument_deckt_alle_szenarien_mit_geprueftem_umfang_und_zielmeilenstein(self):
        text = (HERE / "docs" / "m3-gate-umfang.md").read_text(encoding="utf-8")
        rows = {m.group(1): m.group(0) for m in re.finditer(r"(?m)^\| ((?:PH-AT|AT|E2E)-\d+) \|.*$", text)}
        self.assertEqual(sorted(rows), sorted(gate_run.ALL_IDS))
        for tid, row in rows.items():
            cells = [c.strip() for c in row.strip("|").split("|")]
            self.assertEqual(len(cells), 4, tid)
            self.assertTrue(cells[1] and cells[2] and re.search(r"M\d|P2", cells[3]), f"{tid}: geprüfter Umfang, Offenes und Zielmeilenstein müssen benannt sein")

    def test_abgegrenzte_tests_nennen_den_offenen_teil_und_die_offenen_punkte_ausdruecklich(self):
        src = (HERE / "m3_gate" / "scenarios.py").read_text(encoding="utf-8")
        for tid, marker in (("def at_08", "OP-12"), ("def at_33", "WP-M4-21"), ("def at_34", "OP-12"), ("def at_36", "OP-13")):
            body = src[src.index(tid):].split("\n\n\n")[0]
            self.assertIn(marker, body, tid)
            self.assertIn("offen(ctx", body, tid)

    def test_jeder_vom_szenario_genannte_backend_test_existiert_im_quelltext(self):
        """Ohne JUnit-Ergebnisse prüft dieser Test, dass Klasse und Methode im Quelltext vorkommen (Tippfehler fallen sofort auf)."""
        src = (HERE / "m3_gate" / "scenarios.py").read_text(encoding="utf-8")
        pairs = set(re.findall(r'\("(\w+Test)", "(\w+)"\)', src))
        self.assertGreater(len(pairs), 100)
        files: dict = {}
        for p in (REPO / "backend").glob("**/src/test/**/*.kt"):
            files[p.stem] = files.get(p.stem, "") + p.read_text(encoding="utf-8", errors="replace")
        missing = [(c, m) for c, m in sorted(pairs) if c not in files or ("fun " + m) not in files[c]]
        self.assertEqual(missing, [])

    def test_datenbanktests_und_webtests_der_szenarien_existieren(self):
        src = (HERE / "m3_gate" / "scenarios.py").read_text(encoding="utf-8")
        mods = set(re.findall(r'"(test_\w+)"', src))
        self.assertGreater(len(mods), 10)
        self.assertEqual(sorted(m for m in mods if not (REPO / "db" / "tests" / f"{m}.py").is_file()), [])
        for rel, pattern in set(re.findall(r'vitest\(ctx, "([\w/]+)", "([\w./]+)"', src)):
            self.assertTrue((REPO / rel / pattern).exists(), f"{rel}/{pattern}")

    def test_jeder_szenarioschritt_ist_ein_echter_lauf_oder_ein_ausgewiesener_offener_punkt(self):
        """Kein Szenario darf nur aus `offen`-Schritten bestehen; jedes hat Backend-, Datenbank- oder Webprüfungen."""
        src = (HERE / "m3_gate" / "scenarios.py").read_text(encoding="utf-8")
        for name in re.findall(r"(?m)^def (ph_at_\d+|at_\d+)\(ctx\):", src):
            body = src[src.index(f"def {name}(ctx):"):].split("\n\n\n")[0]
            self.assertTrue(any(k in body for k in ("backend(ctx", "dbtests(ctx", "vitest(ctx", "ui_cases(ctx")), name)
            self.assertIn("both_tenants(ctx)", body, name)


class Parallelitaetsharness(unittest.TestCase):
    def test_harness_hat_mindestens_100_durchlaeufe_beide_reihenfolgen_und_eine_negativkontrolle(self):
        t = (REPO / "backend" / "app" / "src" / "test" / "kotlin" / "de" / "taktovia" / "app" / "fleet" / "PhAt020SafetyHarnessTest.kt").read_text(encoding="utf-8")
        self.assertRegex(t, r"blockFirstRuns = 40, holdFirstRuns = 40, raceRuns = 40")
        self.assertIn("negativkontrolle_ohne_safety_check_wird_die_harness_rot", t)
        self.assertIn("withoutSafetyCheck", t)


class Evidenz(unittest.TestCase):
    def test_write_e2e_traegt_build_nachweis_und_entscheidung_nur_fuer_gate_zeilen_und_nie_aus_regressionslaeufen(self):
        csv_text = "test_id_original;quellabschnitt;name_original;ablauf_original;soll_original;status;build;nachweis;freigabe\nAT-09;11.2;x;y;z;Nicht ausgeführt;;;\nAT-01;11.2;x;y;z;Nicht ausgeführt;;;\nAT-99;11.2;x;y;z;Nicht ausgeführt;;;\n"
        rec = lambda tid, res, offen=False, reg=False: {"test": {"id": tid}, "result": res, "run_id": "R-" + tid, "software_build": {"build_id": "b1"}, "_path": f"/x/{tid}.json", "_regression": reg,  # noqa: E731
                                                          "soll_ist": [{"step": "offen (nicht geprüft): x" if offen else "ok", "ok": True}]}
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "e2e.csv"
            p.write_text(csv_text, encoding="utf-8")
            gate_run.write_e2e([rec("AT-09", "PASSED"), rec("AT-01", "PASSED", reg=True)], p)
            rows = {r[0]: r for r in csv.reader(io.StringIO(p.read_text(encoding="utf-8")), delimiter=";")}
            self.assertEqual(rows["AT-09"][5:], ["Bestanden (M3-Umfang)", "b1", "R-AT-09; tests/acceptance/evidence/m3/AT-09.json", "Ausstehend (Auftraggeber, D-12)"])
            self.assertEqual(rows["AT-01"][5], "Nicht ausgeführt", "Regressionslauf schreibt nie in e2e.csv")
            self.assertEqual(rows["AT-99"][5], "Nicht ausgeführt")
            gate_run.write_e2e([rec("AT-09", "PASSED", offen=True)], p)
            self.assertTrue(list(csv.reader(io.StringIO(p.read_text(encoding="utf-8")), delimiter=";"))[1][5].startswith("Teilweise bestanden"))
            gate_run.write_e2e([rec("AT-09", "FAILED")], p)
            self.assertEqual(list(csv.reader(io.StringIO(p.read_text(encoding="utf-8")), delimiter=";"))[1][5], "Fehlgeschlagen")


@unittest.skipIf(NEED_DB and os.environ.get("TAKTOVIA_REQUIRE_DB") != "true", "Datenbank-Zugangsdaten nicht gesetzt")
class Gesamtlauf(unittest.TestCase):
    """Führt die Suite gegen eine eigene Datenbank aus (nur mit JUnit-Ergebnissen des Backends; ohne sie sind die Backend-Prüfungen rot, nie grün)."""

    def test_gesamtlauf_ist_gruen_wenn_backend_ergebnisse_vorliegen_und_erzeugt_evidenz_ohne_personendaten(self):
        if not junit.load_backend():
            self.skipTest("keine JUnit-Ergebnisse (./gradlew build zuerst)")
        out = Path(tempfile.mkdtemp())
        try:
            records = gate_run.run("taktovia_at_m3_selftest", out, only=["AT-35", "AT-51"])
            self.assertEqual([r["result"] for r in records], ["PASSED", "PASSED"], [c for r in records for c in r["soll_ist"] if not c["ok"]])
            for f in out.glob("*.json"):
                data = json.loads(f.read_text(encoding="utf-8"))
                self.assertEqual(data["acceptance_decision"], "PENDING")
                self.assertTrue(data["soll_ist"])
        finally:
            shutil.rmtree(out, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
