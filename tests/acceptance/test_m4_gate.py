"""WP-M4-63: Tests der M4-Gate-Abnahmesuite (Szenarien, Evidenzformat, Rückverfolgbarkeit, Umfangsdokument).

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
from m4_gate import run as gate_run, scenarios  # noqa: E402

DB_ENV = ("TAKTOVIA_DB_ADMIN_PASSWORD", "TAKTOVIA_DB_RUNTIME_PASSWORD", "TAKTOVIA_DB_MIGRATOR_PASSWORD")
NEED_DB = [k for k in DB_ENV if not os.environ.get(k)]
# Gate-Tests laut WP-M4-63 (Liefergegenstände): Katalogtests und COST-Szenarien (Kap. 03 §7)
GATE_KATALOG = ["PH-AT-012", "PH-AT-013", "PH-AT-015", "PH-AT-019", "PH-AT-021", "AT-37", "AT-39", "AT-40", "AT-41", "AT-45", "AT-47", "AT-48",
                "E2E-064", "E2E-065", "E2E-066", "E2E-067", "E2E-068", "E2E-072", "E2E-073", "E2E-080", "E2E-081", "E2E-082", "E2E-083", "E2E-084"]
GATE_COST = ["COST-01", "COST-02", "COST-05", "COST-07", "COST-08"]
SRC = (HERE / "m4_gate" / "scenarios.py").read_text(encoding="utf-8")


def _body(name):
    return SRC[SRC.index(f"def {name}(ctx):"):].split("\n\n\n")[0]


class Umfang(unittest.TestCase):
    def test_alle_gate_tests_sind_als_ausfuehrbare_szenarien_mit_source_keys_registriert(self):
        self.assertEqual(sorted(gate_run.CATALOG_IDS), sorted(GATE_KATALOG))
        self.assertEqual(len(GATE_KATALOG), 24)
        self.assertEqual(sorted(gate_run.COST_IDS), sorted("TECH-" + c for c in GATE_COST))
        self.assertEqual(sorted(set(gate_run.ALL_IDS) - set(scenarios.SCENARIOS)), [])
        self.assertEqual(len(gate_run.ALL_IDS), len(set(gate_run.ALL_IDS)))
        for tid in gate_run.ALL_IDS:
            self.assertGreaterEqual(len(scenarios.SCENARIOS[tid].meta["source_key"]), 1, tid)

    def test_source_keys_der_szenarien_existieren_im_anforderungskatalog(self):
        with open(REPO / "docs" / "pflichtenheft" / "anforderungen.csv", newline="", encoding="utf-8") as f:
            keys = {r["source_key"] for r in csv.DictReader(f, delimiter=";")}
        for tid in gate_run.ALL_IDS:
            for k in scenarios.SCENARIOS[tid].meta["source_key"]:
                self.assertTrue(k.startswith("ENABLER:WP-M4-") or k in keys, f"{tid}: {k}")

    def test_umfangsdokument_deckt_alle_szenarien_mit_geprueftem_umfang_und_zielmeilenstein(self):
        text = (HERE / "docs" / "m4-gate-umfang.md").read_text(encoding="utf-8")
        rows = {m.group(1): m.group(0) for m in re.finditer(r"(?m)^\| ((?:PH-AT|AT|E2E|TECH)-[A-Z0-9-]+) \|.*$", text)}
        self.assertEqual(sorted(rows), sorted(gate_run.ALL_IDS))
        for tid, row in rows.items():
            cells = [c.strip() for c in row.strip("|").split("|")]
            self.assertEqual(len(cells), 4, tid)
            self.assertTrue(cells[1] and cells[2] and re.search(r"M\d|P2|–", cells[3]), f"{tid}: geprüfter Umfang, Offenes und Zielmeilenstein müssen benannt sein")

    def test_abgegrenzte_tests_nennen_den_offenen_teil_und_die_offenen_punkte_ausdruecklich(self):
        for fn, marker in (("at_41", "OP 1"), ("at_48", "OP 2"), ("at_40", "OP 3"), ("e2e_067", "OP 4"), ("ph_at_012", "OP 5"), ("ph_at_015", "OP 6"), ("e2e_065", "OP 5"), ("m4_live", "D-05")):
            body = _body(fn)
            self.assertIn(marker, body, fn)
            self.assertIn("offen(ctx", body, fn)

    def test_jeder_vom_szenario_genannte_backend_test_existiert_im_quelltext(self):
        """Ohne JUnit-Ergebnisse prüft dieser Test, dass Klasse und Methode im Quelltext vorkommen (Tippfehler fallen sofort auf)."""
        pairs = set()
        for m in re.finditer(r'\bT\("(\w+)",((?:\s*"\w+",?)+)\)', SRC):
            for n in re.findall(r'"(\w+)"', m.group(2)):
                pairs.add((m.group(1), n))
        self.assertGreater(len(pairs), 150)
        files: dict = {}
        for p in (REPO / "backend").glob("**/src/test/**/*.kt"):
            files[p.stem] = files.get(p.stem, "") + p.read_text(encoding="utf-8", errors="replace")
        missing = [(c, m) for c, m in sorted(pairs) if c not in files or ("fun " + m) not in files[c]]
        self.assertEqual(missing, [])

    def test_datenbanktests_und_webtests_der_szenarien_existieren(self):
        mods = set(re.findall(r'"(test_\w+)"', SRC))
        self.assertGreater(len(mods), 20)
        self.assertEqual(sorted(m for m in mods if not (REPO / "db" / "tests" / f"{m}.py").is_file()), [])
        for rel, pattern in set(re.findall(r'vitest\(ctx, "([\w/]+)", "([\w./]+)"', SRC)):
            self.assertTrue((REPO / rel / pattern).exists(), f"{rel}/{pattern}")

    def test_jeder_szenarioschritt_ist_ein_echter_lauf_oder_ein_ausgewiesener_offener_punkt(self):
        """Kein Szenario darf nur aus `offen`-Schritten bestehen; jedes hat Backend-, Datenbank- oder Webprüfungen und läuft mit beiden Mandanten."""
        for name in re.findall(r"(?m)^def (\w+)\(ctx\):", SRC):
            if name in ("start", "browser_a11y_operator", "screenreader_offen"):
                continue
            body = _body(name)
            self.assertTrue(any(k in body for k in ("backend(ctx", "dbtests(ctx", "vitest(ctx")), name)
            self.assertIn("start(ctx)", body, name)

    def test_jeder_gate_test_hat_mindestens_einen_positiven_echten_lauf(self):
        for tid in gate_run.ALL_IDS:
            fn = scenarios.SCENARIOS[tid]
            body = _body(fn.__name__)
            self.assertTrue(any(k in body for k in ("backend(ctx", "dbtests(ctx", "vitest(ctx")), tid)

    def test_geld_sicherheits_und_datentrennungsregeln_haben_negative_und_konkurrierende_tests(self):
        """Akzeptanzkriterium 2/3: je Invariante (Gegenereignis, Journal, eine Kostenwirkung, Umlage) ein Test mit mindestens zwei parallelen Transaktionen (Name `parallel`)."""
        body = _body("m4_invarianten")
        for needle in ("parallele_gegenereignisse", "paralleles_posting", "parallele_allokationsrevisionen", "parallele_freigabe_identischer_belege", "zwei_freigeber_parallel"):
            self.assertIn(needle, body)

    def test_cross_szenarien_decken_live_contract_isolation_und_a11y(self):
        self.assertEqual(sorted(gate_run.CROSS_IDS), ["TECH-M4-A11Y", "TECH-M4-CONTRACT", "TECH-M4-INVARIANTEN", "TECH-M4-ISOLATION", "TECH-M4-LIVE"])
        contract = _body("m4_contract")
        for fall in ("EUR-Rundungsrest", "geschlossene Periode", "Allokationssumme ≠ Basis", "Manipulation tenant_id", "unzulässige Fremd-ID"):
            self.assertIn(fall, contract)
        a11y = _body("m4_a11y")
        for ui in ("src/dienstplan", "src/zeit", "src/belege", "src/kosten", "src/kennzahlen", "browser_a11y_operator"):
            self.assertIn(ui, a11y)


class Evidenz(unittest.TestCase):
    def test_write_e2e_traegt_build_nachweis_und_entscheidung_nur_fuer_gate_zeilen(self):
        csv_text = "test_id_original;quellabschnitt;name_original;ablauf_original;soll_original;status;build;nachweis;freigabe\nAT-37;11.2;x;y;z;Nicht ausgeführt;;;\nAT-01;11.2;x;y;z;Nicht ausgeführt;;;\nAT-99;11.2;x;y;z;Nicht ausgeführt;;;\n"
        rec = lambda tid, res, offen=False: {"test": {"id": tid}, "result": res, "run_id": "R-" + tid, "software_build": {"build_id": "b1"}, "_path": f"/x/{tid}.json",  # noqa: E731
                                              "soll_ist": [{"step": "offen (nicht geprüft): x" if offen else "ok", "ok": True}]}
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "e2e.csv"
            p.write_text(csv_text, encoding="utf-8")
            gate_run.write_e2e([rec("AT-37", "PASSED"), rec("AT-01", "PASSED")], p)
            rows = {r[0]: r for r in csv.reader(io.StringIO(p.read_text(encoding="utf-8")), delimiter=";")}
            self.assertEqual(rows["AT-37"][5:], ["Bestanden (M4-Umfang)", "b1", "R-AT-37; tests/acceptance/evidence/m4/AT-37.json", "Ausstehend (Auftraggeber, D-12)"])
            self.assertEqual(rows["AT-01"][5], "Nicht ausgeführt", "Tests anderer Gates schreibt diese Suite nie")
            self.assertEqual(rows["AT-99"][5], "Nicht ausgeführt")
            gate_run.write_e2e([rec("AT-37", "PASSED", offen=True)], p)
            self.assertTrue(list(csv.reader(io.StringIO(p.read_text(encoding="utf-8")), delimiter=";"))[1][5].startswith("Teilweise bestanden"))
            gate_run.write_e2e([rec("AT-37", "FAILED")], p)
            self.assertEqual(list(csv.reader(io.StringIO(p.read_text(encoding="utf-8")), delimiter=";"))[1][5], "Fehlgeschlagen")


@unittest.skipIf(NEED_DB and os.environ.get("TAKTOVIA_REQUIRE_DB") != "true", "Datenbank-Zugangsdaten nicht gesetzt")
class Gesamtlauf(unittest.TestCase):
    """Führt die Suite gegen eine eigene Datenbank aus (nur mit JUnit-Ergebnissen des Backends; ohne sie sind die Backend-Prüfungen rot, nie grün)."""

    def test_gesamtlauf_ist_gruen_wenn_backend_ergebnisse_vorliegen_und_erzeugt_evidenz_ohne_personendaten(self):
        if not junit.load_backend(gate_run.backend_root()):
            self.skipTest("keine JUnit-Ergebnisse (./gradlew build zuerst)")
        out = Path(tempfile.mkdtemp())
        try:
            records = gate_run.run("taktovia_at_m4_selftest", out, only=["E2E-072", "E2E-082"])
            self.assertEqual([r["result"] for r in records], ["PASSED", "PASSED"], [c for r in records for c in r["soll_ist"] if not c["ok"]])
            for f in out.glob("*.json"):
                data = json.loads(f.read_text(encoding="utf-8"))
                self.assertEqual(data["acceptance_decision"], "PENDING")
                self.assertTrue(data["soll_ist"])
                self.assertTrue(data["source_key"])
        finally:
            shutil.rmtree(out, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
