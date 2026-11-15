"""Tests des Release-Gates (WP-M5-22, LH-6.20-QAT-002): CI verweigert das Release bei fehlschlagender Pflichtprüfung.

Die Tests prüfen den Mechanismus (tools/release_gate/gate.py) mit echten Unterprozessen, echten JUnit-Dateien und der echten Stufenliste
(`pflichtpruefungen.json`): je Stufe wird ein absichtlich roter Befehl bzw. ein rotes Testergebnis eingesetzt, und es darf weder Manifest noch Signatur entstehen.
Der echte Gesamtlauf über Backend, Datenbank und Contract-Tests ist `python tools/release_gate/gate.py run` (in CI der Job `release-gate`); hier steht er nicht,
weil er den Gradle-Build voraussetzt. Aufruf: python -m unittest discover -s tests/acceptance/release -p 'test_*.py'
"""
import copy
import datetime as dt
import json
import os
import re
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "tools" / "release_gate"))
import gate  # noqa: E402

KEY = "k" * 40
GREEN = ["{python}", "-c", "import sys; sys.exit(0)"]
RED = ["{python}", "-c", "import sys; print('absichtlich rot'); sys.exit(1)"]
TODAY = dt.date(2026, 11, 15)


def real_config():
    return gate.load_config()


def synthetic(red_stage=None, kind="befehl"):
    """Die echten Stufen, jede mit einer einzigen Befehlsprüfung (grün); in `red_stage` ist sie rot."""
    cfg = copy.deepcopy(real_config())
    for s in cfg["stufen"]:
        s["pruefungen"] = [{"id": f"{s['id']}-probe", "art": "befehl", "argv": RED if s["id"] == red_stage else GREEN}]
    return cfg


def junit_xml(directory: Path, cases):
    """cases: [(Klasse, Test, 'PASSED'|'FAILED'|'SKIPPED')]"""
    body = ""
    for cls, name, st in cases:
        inner = {"PASSED": "", "FAILED": "<failure message='rot'/>", "SKIPPED": "<skipped/>"}[st]
        body += f"<testcase classname='de.x.{cls}' name='{name}()'>{inner}</testcase>"
    (directory / "TEST-x.xml").write_text(f"<testsuite>{body}</testsuite>", encoding="utf-8")


class GateTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.out = self.root / "release"
        self.env = mock.patch.dict(os.environ, {gate.KEY_ENV: KEY, "TAKTOVIA_BUILD_ID": "build-test-1", "TAKTOVIA_GATE_EXTERN_GITLEAKS": "success", "TAKTOVIA_GATE_EXTERN_OSV": "success"})
        self.env.start()
        self.addCleanup(self.env.stop)

    def run_gate(self, cfg, **kw):
        kw.setdefault("modus", "ci")
        kw.setdefault("now", TODAY)
        kw.setdefault("ergebnisse", self.root / "ergebnisse")
        kw.setdefault("ausnahmen", [])
        return gate.run_gate(cfg, **kw)

    def assertNoRelease(self, result):
        path, why = gate.write_release(result, self.out)
        self.assertIsNone(path, why)
        self.assertFalse((self.out / gate.MANIFEST).exists())
        self.assertFalse((self.out / gate.SIGNATURE).exists())
        return why


class ReleaseGateTest(GateTestCase):
    def test_gruener_lauf_artefakt_mit_build_id_migrationsstand_pruefliste(self):
        cfg = synthetic()
        r = self.run_gate(cfg)
        self.assertTrue(r.passed and r.release_faehig)
        path, why = gate.write_release(r, self.out)
        self.assertEqual(path, self.out / gate.MANIFEST, why)
        m = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(m["build_id"], "build-test-1")
        self.assertEqual(m["migrationsstand"], gate.migration_state())
        self.assertTrue(m["migrationsstand"]["hoechste_version"] and m["migrationsstand"]["sha256"])
        self.assertEqual({s["stufe"] for s in m["pflichtpruefungen"]}, {s["id"] for s in cfg["stufen"]})
        self.assertEqual(len(m["bestandene_pruefungen"]), len(cfg["stufen"]))
        self.assertIn("unit/unit-probe", m["bestandene_pruefungen"])
        self.assertTrue((self.out / gate.SIGNATURE).read_text().startswith("hmac-sha256:"))
        self.assertEqual(gate.verify_release(self.out, cfg), [])
        # Manipulation des Manifests (z. B. eine Stufe nachträglich "bestanden") bricht die Signatur
        (self.out / gate.MANIFEST).write_text((self.out / gate.MANIFEST).read_text().replace("build-test-1", "build-test-2"), encoding="utf-8")
        self.assertIn("Signatur stimmt nicht", gate.verify_release(self.out, cfg))

    def test_je_stufe_roter_test_verhindert_signatur(self):
        ids = [s["id"] for s in real_config()["stufen"]]
        # die in WP-M5-22 geforderten Stufen sind alle vorhanden
        for required in ("unit", "datenbankintegration", "api-contract", "migration", "architektur", "isolation", "semantische-contract-faelle", "kapazitaetsinvarianten", "lieferkette"):
            self.assertIn(required, ids)
        for sid in ids:
            with self.subTest(stufe=sid):
                r = self.run_gate(synthetic(red_stage=sid))
                self.assertEqual({s.id: s.status for s in r.stages}[sid], "FAILED")
                self.assertFalse(r.passed)
                self.assertNoRelease(r)

    def test_ueberspringen_einer_pflichtstufe_ohne_ausnahme_scheitert(self):
        for sid in [s["id"] for s in real_config()["stufen"]]:
            with self.subTest(stufe=sid):
                r = self.run_gate(synthetic(), ueberspringen={sid})
                self.assertEqual({s.id: s.status for s in r.stages}[sid], "SKIPPED_BLOCKED")
                self.assertIn("ohne gültige Ausnahme", self.assertNoRelease(r) + [s.detail for s in r.stages if s.id == sid][0])
        # --stufen: nicht angeforderte Stufen gelten als übersprungen
        r = self.run_gate(synthetic(), nur={"unit"})
        self.assertEqual(sum(s.status == "SKIPPED_BLOCKED" for s in r.stages), len(r.stages) - 1)
        self.assertNoRelease(r)
        # unbekannte Stufe zum Überspringen ist ein Problem, nie stillschweigend grün
        r = self.run_gate(synthetic(), ueberspringen={"gibt-es-nicht"})
        self.assertFalse(r.passed)

    def test_semantische_contract_faelle_sind_pflichtstufe(self):
        cfg = real_config()
        stage = next(s for s in cfg["stufen"] if s["id"] == "semantische-contract-faelle")
        self.assertEqual([c["art"] for c in stage["pruefungen"]], ["semantisch"])
        # Kap. 05 §5.8 für P1 und PH-AT-011
        self.assertEqual(set(cfg["semantische_faelle"]), {"abgelaufener_hold", "sca_laenger_als_hold", "confirm_timeout_spaet_positiv", "hundert_identische_webhooks",
                                                          "manipulation_tenant_id", "unzulaessige_fremd_id", "offline_checkin_alte_planrevision", "ph_at_011_idempotenz_fingerprint"})
        self.assertEqual(gate.check_semantic(cfg), [])
        # roter Selbsttest: ein Fall ohne Test, ein Test, der nicht existiert, und ein roter Test
        rows = gate.read_csv(gate.SEMANTIC)
        p = self.root / "sem.csv"
        header = "fall;referenz;art;ort;name;token;bemerkung\n"
        no_case = [r for r in rows if r["fall"] != "abgelaufener_hold"]
        p.write_text(header + "".join(";".join(r[k] for k in ("fall", "referenz", "art", "ort", "name", "token", "bemerkung")) + "\n" for r in no_case), encoding="utf-8")
        self.assertIn("abgelaufener_hold: kein Test zugeordnet", gate.check_semantic(cfg, path=p))
        bad = [dict(r) for r in rows]
        bad[0]["name"] = "gibt_es_nicht"
        p.write_text(header + "".join(";".join(r[k] for k in ("fall", "referenz", "art", "ort", "name", "token", "bemerkung")) + "\n" for r in bad), encoding="utf-8")
        self.assertTrue(any("gibt_es_nicht" in f for f in gate.check_semantic(cfg, path=p)))
        results = {(Path(r["ort"]).stem, r["name"]): "PASSED" for r in rows}
        results[(Path(rows[0]["ort"]).stem, rows[0]["name"])] = "FAILED"
        self.assertTrue(any("nicht grün" in f for f in gate.check_semantic(cfg, results)))
        # grün mit Ergebnissen: alle zugeordneten Tests grün
        results[(Path(rows[0]["ort"]).stem, rows[0]["name"])] = "PASSED"
        self.assertEqual(gate.check_semantic(cfg, results), [])

    def test_kapazitaetsinvarianten_mit_zwei_parallelen_transaktionen_pflichtstufe(self):
        cfg = real_config()
        stage = next(s for s in cfg["stufen"] if s["id"] == "kapazitaetsinvarianten")
        self.assertEqual([c["art"] for c in stage["pruefungen"]], ["kapazitaet"])
        self.assertEqual(gate.check_capacity(cfg), [])
        rows = gate.read_csv(gate.CAPACITY)
        self.assertTrue(rows and all(int(r["min_parallel"]) >= 2 for r in rows))
        # PH-AT-004 und PH-AT-011 sind Pflichtstufe
        self.assertTrue({"PH-AT-004", "PH-AT-011"} <= {r["referenz"] for r in rows})
        # rote Selbsttests gegen einen Ersatz-Repository-Baum: ein Test ohne parallele Mittel und eine Zeile mit nur einer Transaktion
        fake = self.root / "repo"
        t = fake / "t"
        t.mkdir(parents=True)
        (t / "SeriellTest.kt").write_text("class SeriellTest { @Test fun nur_eine_transaktion() {} }", encoding="utf-8")
        (t / "ParallelTest.kt").write_text("val pool = Executors.newFixedThreadPool(4)\nclass ParallelTest { @Test fun parallel() {} }", encoding="utf-8")
        mini = {"kapazitaetsinvarianten": ["a", "b"]}
        p = self.root / "kap.csv"
        p.write_text("invariante;referenz;art;ort;name;min_parallel;bemerkung\n"
                     "a;x;kotlin;t/SeriellTest.kt;nur_eine_transaktion;2;\nb;x;kotlin;t/ParallelTest.kt;parallel;1;\n", encoding="utf-8")
        funde = gate.check_capacity(mini, repo=fake, path=p)
        self.assertTrue(any("keine parallelen Transaktionen" in f for f in funde), funde)
        self.assertTrue(any("min_parallel 1 < 2" in f for f in funde), funde)
        # eine rote Invariante in den Ergebnissen
        results = {(Path(r["ort"]).stem, r["name"]): "PASSED" for r in rows}
        k = (Path(rows[0]["ort"]).stem, rows[0]["name"])
        results[k] = "FAILED"
        self.assertTrue(any("nicht grün" in f for f in gate.check_capacity(cfg, results)))

    def test_roter_pflichttest_verhindert_release_artefakt(self):
        """Ein rotes (oder fehlendes) JUnit-Ergebnis einer Pflichtprüfung macht die Stufe rot; auch eine Ausnahme heilt eine ausgeführte rote Stufe nie."""
        cfg = synthetic()
        cfg["stufen"][0]["pruefungen"] = [{"id": "kern", "art": "junit", "klassen": ["FachTest"], "mindestens": 2}]
        res = self.root / "ergebnisse"
        res.mkdir()
        junit_xml(res, [("FachTest", "a", "PASSED"), ("FachTest", "b", "FAILED")])
        r = self.run_gate(cfg, junit_dirs=[res])
        self.assertEqual(r.stages[0].status, "FAILED")
        self.assertIn("FachTest.b: FAILED", r.stages[0].checks[0].detail)
        self.assertNoRelease(r)
        # mit Ausnahme für diese Stufe: ausgeführt und rot bleibt gesperrt
        ex = [{"stufe": cfg["stufen"][0]["id"], "freigeber": "Rolle Release-Freigabe", "grund": "x", "ablauf": "2026-11-20", "erfasst": "2026-11-15"}]
        r = self.run_gate(cfg, junit_dirs=[res], ausnahmen=ex)
        self.assertFalse(r.passed)
        self.assertNoRelease(r)
        # übersprungener Testfall zählt nicht als bestanden; fehlende Klasse ebenso
        junit_xml(res, [("FachTest", "a", "PASSED"), ("FachTest", "b", "SKIPPED")])
        self.assertEqual(self.run_gate(cfg, junit_dirs=[res]).stages[0].status, "FAILED")
        junit_xml(res, [("AndereTest", "a", "PASSED")])
        self.assertEqual(self.run_gate(cfg, junit_dirs=[res]).stages[0].status, "FAILED")
        # alles grün
        junit_xml(res, [("FachTest", "a", "PASSED"), ("FachTest", "b", "PASSED")])
        r = self.run_gate(cfg, junit_dirs=[res])
        self.assertTrue(r.passed)
        self.assertIsNotNone(gate.write_release(r, self.out)[0])

    def test_ausnahme_ohne_freigeber_grund_ablauf_gesperrt(self):
        sid = "migration"
        full = {"stufe": sid, "freigeber": "Rolle Release-Freigabe (Platzhalter im Test)", "grund": "Migrationsumgebung ausgefallen, Nachlauf am selben Tag", "ablauf": "2026-11-20", "erfasst": "2026-11-15"}
        for fehlt in ("freigeber", "grund", "ablauf"):
            with self.subTest(fehlt=fehlt):
                ex = {k: v for k, v in full.items() if k != fehlt}
                r = self.run_gate(synthetic(), ueberspringen={sid}, ausnahmen=[ex])
                self.assertEqual({s.id: s.status for s in r.stages}[sid], "SKIPPED_BLOCKED")
                self.assertIn(fehlt, [s.detail for s in r.stages if s.id == sid][0])
                self.assertNoRelease(r)
                leer = dict(ex, **{fehlt: "   "})
                self.assertFalse(self.run_gate(synthetic(), ueberspringen={sid}, ausnahmen=[leer]).passed)
        # abgelaufen, zu lang, Ausnahme für eine andere Stufe
        for bad, why in ((dict(full, ablauf="2026-11-14"), "abgelaufen"), (dict(full, ablauf="2026-12-30"), "Laufzeit"), (dict(full, ablauf="morgen"), "ISO-Datum"), (dict(full, stufe="unit"), "keine Ausnahme")):
            with self.subTest(bad=why):
                r = self.run_gate(synthetic(), ueberspringen={sid}, ausnahmen=[bad])
                self.assertFalse(r.passed)
                self.assertIn(why, [s.detail for s in r.stages if s.id == sid][0])
        # vollständig und gültig: Stufe übersprungen, Release möglich, Ausnahme steht im Manifest
        r = self.run_gate(synthetic(), ueberspringen={sid}, ausnahmen=[full])
        self.assertTrue(r.passed)
        path, why = gate.write_release(r, self.out)
        self.assertIsNotNone(path, why)
        m = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(m["ausnahmen"][0]["freigeber"], full["freigeber"])
        self.assertEqual({s["stufe"]: s["status"] for s in m["pflichtpruefungen"]}[sid], "SKIPPED_WITH_EXCEPTION")
        # die echte Ausnahmedatei enthält keine Ausnahme ohne die drei Angaben
        for a in gate.load_exceptions():
            self.assertTrue(all(str(a.get(k, "")).strip() for k in ("freigeber", "grund", "ablauf")), a)

    def test_funktion_zu_test_zuordnung_vollstaendig(self):
        cfg = real_config()
        self.assertEqual(cfg["abdeckung"]["funktionen"], ["Angebot", "Hold", "Confirm", "Ablauf", "Storno", "Planrevision", "Einfuegeheuristik", "Constraint-Validator"])
        self.assertEqual(gate.check_coverage(cfg), [])
        rows = gate.read_csv(gate.COVERAGE)
        for fn in cfg["abdeckung"]["funktionen"]:
            for stufe in cfg["abdeckung"]["stufen"]:
                self.assertTrue([r for r in rows if r["funktion"] == fn and r["stufe"] == stufe], f"{fn}/{stufe}")
        # rote Selbsttests: Lücke, nicht existierender Test, Datenbanktest als Unit-Test, fehlender Vertragsfall, roter Test
        header = "funktion;stufe;art;ort;name;token;bemerkung\n"
        cols = ("funktion", "stufe", "art", "ort", "name", "token", "bemerkung")

        def write(rs):
            p = self.root / "abd.csv"
            p.write_text(header + "".join(";".join(r[k] for k in cols) + "\n" for r in rs), encoding="utf-8")
            return p
        self.assertTrue(any("Storno: kein contract-Test zugeordnet" in f for f in gate.check_coverage(cfg, path=write([r for r in rows if not (r["funktion"] == "Storno" and r["stufe"] == "contract")]))))
        ghost = [dict(r) for r in rows]
        ghost[1]["name"] = "gibt_es_nicht"
        self.assertTrue(any("gibt_es_nicht" in f for f in gate.check_coverage(cfg, path=write(ghost))))
        mislabeled = [dict(r) for r in rows]
        i = next(i for i, r in enumerate(mislabeled) if r["stufe"] == "integration" and r["art"] == "kotlin")
        mislabeled[i]["stufe"] = "unit"
        self.assertTrue(any("kein Unit-Test" in f for f in gate.check_coverage(cfg, path=write(mislabeled))))
        nocase = [dict(r) for r in rows]
        j = next(i for i, r in enumerate(nocase) if r["art"] == "contract")
        nocase[j]["name"] = "listProbeGibtEsNicht"
        self.assertTrue(any("listProbeGibtEsNicht" in f for f in gate.check_coverage(cfg, path=write(nocase))))
        results = {(Path(r["ort"]).stem, r["name"]): "PASSED" for r in rows if r["art"] != "contract"}
        results[gate.CONTRACT_RUNNER] = "PASSED"
        self.assertEqual(gate.check_coverage(cfg, results), [])
        victim = next(r for r in rows if r["art"] == "kotlin")
        results[(Path(victim["ort"]).stem, victim["name"])] = "FAILED"
        self.assertTrue(any("nicht grün" in f for f in gate.check_coverage(cfg, results)))
        results[(Path(victim["ort"]).stem, victim["name"])] = "PASSED"
        results[gate.CONTRACT_RUNNER] = "FAILED"
        self.assertTrue(any("Vertragslauf" in f for f in gate.check_coverage(cfg, results)))


class ReleaseGateModusTest(GateTestCase):
    def test_probelauf_wird_nie_signiert_und_extern_gilt_als_nicht_ausgefuehrt(self):
        cfg = synthetic()
        cfg["stufen"][-1]["pruefungen"].append({"id": "osv", "art": "extern", "name": "osv", "beschreibung": "OSV"})
        r = self.run_gate(cfg, modus="probelauf")
        self.assertEqual(r.stages[-1].checks[-1].status, "NOT_RUN")
        self.assertFalse(r.release_faehig)
        self.assertIn("Probelauf", self.assertNoRelease(r))
        # CI-Modus: das Ergebnis des externen Jobs entscheidet
        with mock.patch.dict(os.environ, {"TAKTOVIA_GATE_EXTERN_OSV": "failure"}):
            self.assertEqual(self.run_gate(cfg).stages[-1].checks[-1].status, "FAILED")
        with mock.patch.dict(os.environ, {"TAKTOVIA_GATE_EXTERN_OSV": "success"}):
            self.assertTrue(self.run_gate(cfg).release_faehig)

    def test_ohne_signierschluessel_kein_release(self):
        r = self.run_gate(synthetic())
        with mock.patch.dict(os.environ, {gate.KEY_ENV: "kurz"}):
            self.assertIn("Signierschlüssel", self.assertNoRelease(r))

    def test_stufenliste_und_ci_verdrahtung(self):
        cfg = real_config()
        ids = [s["id"] for s in cfg["stufen"]]
        self.assertEqual(len(ids), len(set(ids)))
        ci = (REPO / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
        m = re.search(r"^  release-gate:\n(.*?)(?=^  [a-z][\w-]*:\n|\Z)", ci, re.M | re.S)
        self.assertIsNotNone(m, "Job release-gate fehlt in ci.yml")
        job = m.group(1)
        need = re.search(r"needs:\s*\[([^\]]*)\]", job)
        jobs = set(re.findall(r"^  ([a-z][\w-]*):\n", ci.split("\njobs:\n", 1)[1], re.M)) - {"release-gate"}
        self.assertEqual({n.strip() for n in need.group(1).split(",")}, jobs - {"mobile"}, "release-gate muss auf alle verbindlichen Jobs warten (mobile ist continue-on-error)")
        self.assertIn("release_gate/gate.py run", job)
        self.assertIn("--ci", job)
        self.assertNotIn("continue-on-error:", job)
        step = re.search(r"- name: Pflichtprüfungen[^\n]*\n((?:        .*\n)+)", job)
        self.assertIsNotNone(step, "Schritt der Pflichtprüfungen fehlt")
        self.assertNotIn("if:", step.group(1), "der Gate-Schritt darf nicht bedingt oder durchlässig sein")
        self.assertIn("TAKTOVIA_RELEASE_SIGNING_KEY", job)
        # jede Stufe verweist auf vorhandene Befehlsdateien
        for s in cfg["stufen"]:
            for c in s["pruefungen"]:
                if c["art"] == "befehl":
                    for a in c["argv"]:
                        if re.match(r"(tools|api|tests)/[\w/.-]+\.py$", a):
                            self.assertTrue((REPO / c.get("cwd", ".") / a).is_file(), a)


if __name__ == "__main__":
    unittest.main()
