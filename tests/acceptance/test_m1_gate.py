"""WP-M1-45: Tests der M1-Gate-Abnahmesuite (Szenarien, Evidenz, Rückverfolgbarkeit, Umfangsdokument).

Der Gesamtlauf der elf Szenarien verlangt die JUnit-Ergebnisse des Backends (`./gradlew build`); ohne sie wird dieser Test übersprungen, mit `TAKTOVIA_GATE_REQUIRE_BACKEND=true`
(CI nach dem Backend-Job) schlägt er fehl."""
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

from m1_gate import junit, run as gate_run, scenarios, traceability  # noqa: E402

DB_ENV = ("TAKTOVIA_DB_ADMIN_PASSWORD", "TAKTOVIA_DB_RUNTIME_PASSWORD", "TAKTOVIA_DB_MIGRATOR_PASSWORD")
NEED_DB = [k for k in DB_ENV if not os.environ.get(k)]


class Umfang(unittest.TestCase):
    def test_umfangsdokument_deckt_alle_elf_tests_mit_zielmeilenstein(self):
        text = (HERE / "docs" / "m1-gate-umfang.md").read_text(encoding="utf-8")
        rows = {m.group(1): m.group(0) for m in re.finditer(r"(?m)^\| ((?:PH-AT|AT|E2E)-\d+) \|.*$", text)}
        self.assertEqual(sorted(rows), sorted(gate_run.GATE_IDS))
        for tid, row in rows.items():
            cells = [c.strip() for c in row.strip("|").split("|")]
            self.assertEqual(len(cells), 4, tid)
            self.assertTrue(cells[1] and cells[2] and re.search(r"M\d", cells[3]), f"{tid}: geprüfter Umfang, nicht prüfbarer Teilschritt und Zielmeilenstein müssen benannt sein")

    def test_alle_gate_tests_sind_als_szenarien_registriert(self):
        from taktovia_at.scenario import REGISTRY
        self.assertEqual(sorted(set(gate_run.GATE_IDS) - set(REGISTRY)), [])
        for tid in gate_run.GATE_IDS:
            self.assertGreaterEqual(len(REGISTRY[tid].meta["source_key"]), 2, tid)

    def test_semantische_contract_und_ui_faelle_sind_verknuepft_und_vorhanden(self):
        backend = REPO / "backend"
        wanted = {
            "100 identische Webhooks": [("app/src/test/kotlin/de/taktovia/app/InboundWebhookHttpTest.kt", "hundert_identische_eingaenge_ein_inbox_eintrag_99_already_received"),
                                        ("platform/src/test/kotlin/de/taktovia/platform/outbox/InboxProcessorTest.kt", "hundert identische Zustellungen")],
            "Manipulation tenant_id": [("modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/ProviderEventsTest.kt", "manipulierte_tenant_id_ist_wirkungslos"),
                                       ("app/src/test/kotlin/de/taktovia/app/driver/DriverApiTest.kt", "tenant_id_im_body")],
            "unzulässige Fremd-ID": [("app/src/test/kotlin/de/taktovia/app/isolation/CrossTenantIsolationTest.kt", "fremdes_objekt_ueber_alle_m1_endpunkte_gleichartig_abgewiesen")],
        }
        for case, files in wanted.items():
            for rel, name in files:
                self.assertIn(name, (backend / rel).read_text(encoding="utf-8"), f"{case}: {rel}")
        self.assertTrue(junit.web_test_defined("web/api-client/src/auth/auth.test.tsx", "zwei Tabs"))
        self.assertTrue(junit.web_test_defined("web/operator/src/identity/CompanyProfileForm.test.tsx", "412-Konflikt lädt neu statt zu überschreiben"))


class Rueckverfolgbarkeit(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name); self.addCleanup(self.tmp.cleanup)
        (self.root / "docs/umsetzungsplan").mkdir(parents=True); (self.root / "docs/pflichtenheft").mkdir(parents=True); (self.root / "tests/acceptance/evidence/m1").mkdir(parents=True)

    def write(self, reqs, e2e):
        head = ["source_key", "meilenstein", "prioritaet_original", "status", "detailvertrag", "testfall"]
        with (self.root / "docs/umsetzungsplan/arbeitspakete.csv").open("w", encoding="utf-8", newline="") as f:
            w = csv.writer(f, delimiter=";"); w.writerow(head); w.writerows(reqs)
        with (self.root / "docs/pflichtenheft/e2e.csv").open("w", encoding="utf-8", newline="") as f:
            w = csv.writer(f, delimiter=";"); w.writerow(["test_id_original", "status", "build", "nachweis", "freigabe"]); w.writerows(e2e)

    def test_umgesetzte_muss_zeile_ohne_testfall_wird_gemeldet(self):
        self.write([["LH-X-1", "M1", "MUSS", "Umgesetzt", "docs/v.md", ""], ["LH-X-2", "M1", "MUSS", "Bereit", "docs/v.md", "geplant:a.py::t"],
                    ["LH-X-3", "M1", "MUSS", "Umgesetzt", "docs/v.md", "AT-18"]], [])
        errors, stats = traceability.check(self.root)
        self.assertEqual([e for e in errors if e.startswith("R1")], ["R1 LH-X-1: als Umgesetzt markierte MUSS-Zeile ohne Detailvertrag oder Testfall"])
        self.assertEqual(stats["ohne_ausfuehrbaren_positiven_test"], ["LH-X-1", "LH-X-2"])   # geplante Tests zählen nicht als ausführbar
        _, strict = traceability.check(self.root, strict=True)
        self.assertTrue(any(e.startswith("R2 LH-X-2") for e in traceability.check(self.root, strict=True)[0]))

    def test_e2e_zeile_ohne_lauf_oder_mit_fremdem_nachweis_wird_gemeldet(self):
        ev = self.root / "tests/acceptance/evidence/m1/AT-18-run-0123456789abcdef.json"
        ev.write_text(json.dumps({"run_id": "run-0123456789abcdef", "test": {"id": "AT-18"}, "software_build": {"build_id": "abc"}, "result": "PASSED", "acceptance_decision": "PENDING"}))
        ok = ["AT-18", "Bestanden (M1-Umfang)", "abc", "run-0123456789abcdef; tests/acceptance/evidence/m1/AT-18-run-0123456789abcdef.json", "Ausstehend (Auftraggeber, D-12)"]
        self.write([], [ok])
        self.assertEqual(traceability.check(self.root)[0], [])
        for bad, expect in ((["AT-01", "Bestanden", "abc", "run-0123456789abcdef; tests/acceptance/evidence/m1/AT-18-run-0123456789abcdef.json", ""], "ohne Lauf der M1-Gate-Suite"),
                            (["AT-18", "Bestanden (M1-Umfang)", "", "", ""], "Build oder Nachweis"),
                            (["AT-18", "Bestanden (M1-Umfang)", "xyz", ok[3], ok[4]], "passt nicht zur Evidenzdatei"),
                            (["AT-18", "Fehlgeschlagen", "abc", ok[3], ok[4]], "widerspricht dem Ergebnis"),
                            (["AT-18", "Nicht ausgeführt", "abc", "", ""], "Build/Nachweis eingetragen"),
                            (["AT-18", "Bestanden (M1-Umfang)", "abc", ok[3], "Freigegeben"], "Abnahmeentscheidung")):
            self.write([], [bad])
            self.assertTrue(any(expect in e for e in traceability.check(self.root)[0]), (bad, traceability.check(self.root)[0]))

    def test_echte_e2e_csv_ist_aus_laeufen_befuellt(self):
        errors, stats = traceability.check()
        self.assertEqual([e for e in errors if e.startswith(("R1", "R3"))], [])
        self.assertEqual(stats["anforderungen"], 46)

    def test_e2e_write_aendert_nur_gate_zeilen(self):
        src = REPO / "docs/pflichtenheft/e2e.csv"
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "e2e.csv"; shutil.copy(src, p)
            before = list(csv.reader(io.StringIO(p.read_text(encoding="utf-8")), delimiter=";"))
            rec = {"test": {"id": "AT-18"}, "run_id": "run-0123456789abcdef", "result": "PASSED", "software_build": {"build_id": "deadbeef"}, "_path": "/x/AT-18-run-0123456789abcdef.json"}
            gate_run.write_e2e([rec], p)
            after = list(csv.reader(io.StringIO(p.read_text(encoding="utf-8")), delimiter=";"))
        changed = [a[0] for a, b in zip(after, before) if a != b]
        self.assertEqual(changed, ["AT-18"]); self.assertEqual(len(before), len(after))


@unittest.skipIf(NEED_DB and os.environ.get("TAKTOVIA_REQUIRE_DB") != "true", f"Datenbank-Zugangsdaten nicht gesetzt: {NEED_DB}")
class Gesamtlauf(unittest.TestCase):
    def test_alle_elf_gate_tests_gruen_mit_evidenz(self):
        if NEED_DB:
            self.fail(f"Datenbank erforderlich: {NEED_DB}")
        results = junit.load_backend()
        if not results:
            if os.environ.get("TAKTOVIA_GATE_REQUIRE_BACKEND") == "true":
                self.fail("keine JUnit-Ergebnisse des Backends (./gradlew build vor der Gate-Suite)")
            self.skipTest("keine JUnit-Ergebnisse des Backends vorhanden (./gradlew build zuerst)")
        from taktovia_at import evidence as ev
        with tempfile.TemporaryDirectory() as out:
            records = gate_run.run("taktovia_at_m1_gate_test", Path(out))
            summary = gate_run.summary(records)
            self.assertEqual([t["id"] for t in summary["tests"]], gate_run.GATE_IDS)
            red = {t["id"]: t["failed"] for t in summary["tests"] if t["result"] != "PASSED"}
            self.assertEqual(red, {})
            for r in records:
                self.assertEqual(r["acceptance_decision"], "PENDING")
                self.assertGreaterEqual(len(r["soll_ist"]), 5, r["test"]["id"])
                self.assertTrue(any("Rolle der Prüfungen" in c["step"] for c in r["soll_ist"]) or r["test"]["id"] not in ("PH-AT-001",))
                self.assertTrue(any(c["step"].startswith("Backend-Test") for c in r["soll_ist"]), r["test"]["id"])
                ev.validate(r if "_path" not in r else {k: v for k, v in r.items() if k != "_path"})   # Schema und keine Personendaten
                self.assertEqual(ev.check_no_personal_data({k: v for k, v in r.items() if k != "_path"}), [])


if __name__ == "__main__":
    unittest.main()
