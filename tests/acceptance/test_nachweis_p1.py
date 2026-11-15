"""WP-M5-31: Nachweisführung P1 und M5-Gate-Bericht (`tools/nachweis_p1.py`).

Belegt (ausgeführt): die Nachweismatrix wird aus `arbeitspakete.csv`, Evidenz, `e2e.csv` und Testergebnissen erzeugt; eine Anforderung gilt nur mit einem AUSGEFÜHRTEN grünen Test als grün; eine
absichtlich entfernte Evidenz lässt die Matrix unvollständig und den Berichtslauf rot werden; Evidenz eines anderen Builds wird abgewiesen; e2e.csv-Einträge nur aus Läufen; Mängelregeln A/B/C; Gate
Partnerfreigabe (keine buchbaren Partner) und das Review-Protokoll Fachmodell. Die Tests arbeiten mit synthetischen Mini-Repositories (Selbsttest des Werkzeugs) und mit dem echten Repository.
NICHT belegt: dass das echte Repository das Gate besteht. Der Bericht über das echte Repository weist Verstöße aus (Anforderungen ohne Lauf, offene Gates); das ist das ehrliche Ergebnis, kein Fehler
des Werkzeugs. Die Abnahmeentscheidung trifft der Auftraggeber."""
import copy
import csv
import io
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(REPO / "tools"))
sys.path.insert(0, str(HERE))
import nachweis_p1 as np_  # noqa: E402

AP_KOPF = ["source_key", "id_original", "quellabschnitt", "phase_original", "prioritaet_original", "meilenstein", "arbeitspaket", "wp_titel", "wp_groesse", "wp_datei", "detailvertrag", "testfall", "status"]
E2E_KOPF = ["test_id_original", "quellabschnitt", "name_original", "ablauf_original", "soll_original", "status", "build", "nachweis", "freigabe"]
BUILD = "a" * 40
GATE_OK = {"gates": {g: {"nachweise": [{"beschreibung": "Nachweis", "verweise": [], "stand": "erbracht"}]} for g in np_.GATES}}


def evidenz(tid, keys, build=BUILD, result="PASSED", run="run-1", extra=None):
    r = {"schema_version": "1.0", "run_id": run, "test": {"id": tid, "version": "1.0.0", "name": tid}, "source_key": keys, "software_build": {"build_id": build, "origin": "test"},
         "migration_state": {"version": "V1"}, "revisions": {"config": 1}, "seed": {"hash": "x"}, "roles_and_tenants": [{"role": "dispatcher", "tenant": "A"}], "soll_ist": [], "event_trace_ids": {},
         "result": result, "defect_class": None, "responsible": "Plattform (QA)", "acceptance_decision": "PENDING", "executed_at": "2026-11-15T00:00:00Z", "environment": {"name": "test"}}
    r.update(extra or {})
    return r


class MiniRepo:
    """Synthetisches Repository: zwei P1-Anforderungen (eine mit Datei-Test, eine mit Referenztest AT-99), Evidenz, e2e.csv, Gate-Nachweise."""

    def __init__(self, d, build=BUILD):
        self.root = Path(d)
        for sub in ("docs/umsetzungsplan/nachweise", "docs/umsetzungsplan/vertraege", "docs/pflichtenheft", "tests/acceptance/evidence/m1", "backend/x/build/test-results/test", "kern"):
            (self.root / sub).mkdir(parents=True, exist_ok=True)
        (self.root / "kern/PruefTest.kt").write_text("class PruefTest { fun positiv_test() {} fun negativ_test() {} }", encoding="utf-8")
        (self.root / "docs/umsetzungsplan/vertraege/LH-X-1.md").write_text("- **Negativ:** abgewiesen.\n- **Konkurrierend:** nicht zutreffend.\n", encoding="utf-8")
        self.zeilen = [
            ["LH-X-1", "X-1", "1", "P1", "MUSS", "M1", "WP-M1-01", "t", "M", "w", "docs/umsetzungsplan/vertraege/LH-X-1.md", "kern/PruefTest.kt::positiv_test;kern/PruefTest.kt::negativ_test", "Umgesetzt"],
            ["LH-X-2", "X-2", "1", "P1", "MUSS", "M1", "WP-M1-02", "t", "M", "w", "", "AT-99", "Umgesetzt"],
            ["LH-X-3", "X-3", "1", "P2", "MUSS", "M6", "WP-M6-01", "t", "M", "w", "", "", "Geplant"],
        ]
        self.schreibe_ap()
        self.junit({"positiv_test": "ok", "negativ_test": "ok"})
        self.e2e([["AT-99", "1", "n", "a", "s", "Bestanden (Test)", build, "run-1; tests/acceptance/evidence/m1/AT-99-run-1.json", "Ausstehend"]])
        self.evid("AT-99", ["LH-X-2"], build)
        (self.root / "docs/umsetzungsplan/nachweise/gate-nachweise.json").write_text(json.dumps(GATE_OK), encoding="utf-8")
        (self.root / "docs/umsetzungsplan/nachweise/maengel.csv").write_text(";".join(np_.MAENGEL_SPALTEN) + "\n", encoding="utf-8")

    def schreibe_ap(self):
        out = io.StringIO()
        w = csv.writer(out, delimiter=";", lineterminator="\n")
        w.writerow(AP_KOPF)
        w.writerows(self.zeilen)
        (self.root / "docs/umsetzungsplan/arbeitspakete.csv").write_text(out.getvalue(), encoding="utf-8")

    def junit(self, faelle):
        tcs = "".join(f'<testcase classname="x.PruefTest" name="{n}()">' + ("<failure/>" if s == "fail" else "") + "</testcase>" for n, s in faelle.items())
        (self.root / "backend/x/build/test-results/test/TEST-x.PruefTest.xml").write_text(f'<testsuite name="x.PruefTest">{tcs}</testsuite>', encoding="utf-8")

    def e2e(self, zeilen):
        out = io.StringIO()
        w = csv.writer(out, delimiter=";", lineterminator="\n")
        w.writerow(E2E_KOPF)
        w.writerows(zeilen)
        (self.root / "docs/pflichtenheft/e2e.csv").write_text(out.getvalue(), encoding="utf-8")

    def evid(self, tid, keys, build=BUILD, result="PASSED", run="run-1", extra=None):
        p = self.root / f"tests/acceptance/evidence/m1/{tid}-{run}.json"
        p.write_text(json.dumps(evidenz(tid, keys, build, result, run, extra)), encoding="utf-8")
        return p

    def bericht(self, **kw):
        kw.setdefault("abnahme_build", BUILD)
        return np_.bericht(self.root, erwartet=2, pflicht_at=(), **kw)


class Matrix(unittest.TestCase):
    def test_nachweismatrix_enthaelt_alle_202_p1_anforderungen(self):
        """Echtes Repository: genau die 202 P1-MUSS-Anforderungen aus arbeitspakete.csv, je mit Detailvertrag, Arbeitspaket und einem eindeutigen Ergebnis; die Zählung geht auf."""
        zeilen, _ = np_.matrix(REPO)
        self.assertEqual(len(zeilen), 202)
        self.assertEqual(len({z["source_key"] for z in zeilen}), 202)
        for z in zeilen:
            self.assertEqual(set(z), set(np_.MATRIX_SPALTEN))
            self.assertIn(z["ergebnis"], ("GRUEN", "ROT", "OHNE_LAUF", "KEIN_TEST", "EVIDENZ_ABGEWIESEN"))
            self.assertTrue(z["detailvertrag"] and z["arbeitspaket"])
        b = np_.bericht(REPO)
        self.assertEqual(sum(b["zaehlung"].values()), 202)
        csv_text = np_.matrix_csv(zeilen)
        self.assertEqual(len(list(csv.DictReader(io.StringIO(csv_text), delimiter=";"))), 202)

    def test_gruen_nur_mit_ausgefuehrtem_test_und_evidenz(self):
        with tempfile.TemporaryDirectory() as d:
            m = MiniRepo(d)
            b = m.bericht()
            z = {x["source_key"]: x for x in b["matrix"]}
            self.assertEqual((z["LH-X-1"]["ergebnis"], z["LH-X-2"]["ergebnis"]), ("GRUEN", "GRUEN"))
            self.assertEqual(b["verstoesse"], [], b["verstoesse"])
            self.assertEqual(z["LH-X-2"]["build"], BUILD)
            self.assertIn("evidence/m1/AT-99-run-1.json", z["LH-X-2"]["evidenz"])
            self.assertNotIn("LH-X-3", z, "P2 gehört nicht zur Matrix P1")
            # ein roter Test macht die Zeile rot; ohne Ergebnis ist sie nicht grün; geplant zählt nicht
            m.junit({"positiv_test": "fail", "negativ_test": "ok"})
            self.assertEqual({x["source_key"]: x["ergebnis"] for x in m.bericht()["matrix"]}["LH-X-1"], "ROT")
            m.junit({})
            self.assertEqual({x["source_key"]: x["ergebnis"] for x in m.bericht()["matrix"]}["LH-X-1"], "OHNE_LAUF")
            m.zeilen[0][11] = "geplant:kern/PruefTest.kt::positiv_test"
            m.schreibe_ap()
            self.assertEqual({x["source_key"]: x["ergebnis"] for x in m.bericht()["matrix"]}["LH-X-1"], "KEIN_TEST")

    def test_negativer_test_verlangt_zwei_gruene_tests(self):
        with tempfile.TemporaryDirectory() as d:
            m = MiniRepo(d)
            m.zeilen[0][11] = "kern/PruefTest.kt::positiv_test"
            m.schreibe_ap()
            b = m.bericht()
            self.assertTrue(any("negativer/konkurrierender Test verlangt" in v for v in b["verstoesse"]), b["verstoesse"])

    def test_entfernte_evidenz_laesst_matrix_unvollstaendig_und_berichtslauf_fehlschlagen(self):
        """Negativ laut WP: eine absichtlich entfernte Evidenz erscheint in der Matrix als nicht belegt und der Berichtslauf (Exit-Code) schlägt fehl."""
        with tempfile.TemporaryDirectory() as d:
            m = MiniRepo(d)
            self.assertEqual(m.bericht()["verstoesse"], [])
            (m.root / "tests/acceptance/evidence/m1/AT-99-run-1.json").unlink()
            b = m.bericht()
            z = {x["source_key"]: x for x in b["matrix"]}
            self.assertEqual(z["LH-X-2"]["ergebnis"], "OHNE_LAUF")
            self.assertTrue(any("LH-X-2" in v for v in b["verstoesse"]), b["verstoesse"])
            self.assertTrue(any("kein Testlauf" in v or "Evidenz" in v for v in np_.e2e_pruefen(m.root, BUILD, None, ())), "e2e.csv verweist auf die entfernte Evidenz")

    def test_evidenz_eines_anderen_builds_wird_abgewiesen(self):
        """Negativ laut WP: Evidenz von einem anderen Build wird abgewiesen (kein Mischen von Builds)."""
        with tempfile.TemporaryDirectory() as d:
            m = MiniRepo(d)
            m.evid("AT-99", ["LH-X-2"], build="b" * 40)
            b = m.bericht()
            z = {x["source_key"]: x for x in b["matrix"]}
            self.assertEqual(z["LH-X-2"]["ergebnis"], "EVIDENZ_ABGEWIESEN")
            self.assertTrue(any("abgewiesen" in v and "b" * 40 in v for v in b["verstoesse"]), b["verstoesse"])
            self.assertTrue(any("nicht vom Abnahme-Build" in v for v in np_.e2e_pruefen(m.root, BUILD, None, ())), "auch e2e.csv zeigt auf Evidenz eines anderen Builds")
            # ohne festgelegten Abnahme-Build fällt das Mischen ebenfalls auf
            m.evid("AT-98", ["LH-X-1"], build="c" * 40, run="run-2")
            b = np_.bericht(m.root, erwartet=2, pflicht_at=(), abnahme_build=None)
            self.assertTrue(any("verschiedenen Builds" in v for v in b["verstoesse"]), b["verstoesse"])

    def test_evidenz_pflichtfelder_und_personendaten(self):
        """Kap. 08 §8.4: jeder Datensatz trägt die Pflichtfelder und keine unnötigen Personendaten."""
        with tempfile.TemporaryDirectory() as d:
            m = MiniRepo(d)
            p = m.root / "tests/acceptance/evidence/m1/AT-99-run-1.json"
            r = json.loads(p.read_text())
            del r["migration_state"]
            del r["acceptance_decision"]
            p.write_text(json.dumps(r), encoding="utf-8")
            v = m.bericht()["verstoesse"]
            self.assertTrue(any("Pflichtfeld 'migration_state' fehlt" in x for x in v), v)
            self.assertTrue(any("Pflichtfeld 'acceptance_decision' fehlt" in x for x in v), v)
            m.evid("AT-99", ["LH-X-2"], extra={"soll_ist": [{"kontakt_email": "erika.muster@gmx.de"}]})
            self.assertTrue(any("Personendaten" in x for x in m.bericht()["verstoesse"]))

    def test_echte_evidenz_traegt_pflichtfelder(self):
        eintraege = np_.evidenz_laden(REPO)
        self.assertGreater(len(eintraege), 50)
        for e in eintraege:
            self.assertEqual(e["fehler"], [], str(e["pfad"]))


class E2E(unittest.TestCase):
    def test_e2e_eintraege_nur_aus_laeufen_und_pflicht_at(self):
        with tempfile.TemporaryDirectory() as d:
            m = MiniRepo(d)
            self.assertEqual(np_.e2e_pruefen(m.root, BUILD, None, ("AT-99",)), [])
            v = np_.e2e_pruefen(m.root, BUILD, None, ("AT-99", "AT-25"))
            self.assertEqual(len(v), 1)
            self.assertIn("AT-25", v[0])
            # Eintrag ohne Evidenzdatei (von Hand): abgewiesen
            m.e2e([["AT-99", "1", "n", "a", "s", "Bestanden (Test)", BUILD, "run-77; tests/acceptance/evidence/m1/AT-99-run-77.json", "Ausstehend"]])
            self.assertTrue(any("aus keinem Testlauf" in x for x in np_.e2e_pruefen(m.root, BUILD, None, ())))
            # Build in e2e.csv weicht von der Evidenz ab
            m.e2e([["AT-99", "1", "n", "a", "s", "Bestanden (Test)", "d" * 40, "run-1; x", "Ausstehend"]])
            self.assertTrue(any("weicht vom Evidenzdatensatz ab" in x for x in np_.e2e_pruefen(m.root, None, None, ())))

    def test_pflicht_at_im_echten_repository_ausgewiesen(self):
        """AT-23, AT-25, AT-53, AT-54, AT-59, AT-61: jeder ist entweder aus einem Lauf eingetragen oder als Verstoß ausgewiesen (nie stillschweigend grün)."""
        v = np_.e2e_pruefen(REPO)
        eingetragen = {r["test_id_original"] for r in np_.rd(REPO / "docs/pflichtenheft/e2e.csv") if np_.E2E_ENTRAG.match(r["status"])}
        for tid in np_.PFLICHT_AT:
            self.assertEqual(any(x.startswith(tid + ":") and "kein Eintrag" in x for x in v), tid not in eingetragen, tid)


class Maengel(unittest.TestCase):
    def test_mangelregeln_a_b_c(self):
        spalten = np_.MAENGEL_SPALTEN

        def m(**kw):
            d = dict.fromkeys(spalten, "")
            d.update(id="M-1", klasse="C", titel="t", status="offen")
            d.update(kw)
            return d
        self.assertEqual(np_.maengel_pruefen([m(klasse="C", massnahmenplan="Plan")]), [])
        self.assertTrue(np_.maengel_pruefen([m(klasse="C")]))
        self.assertTrue(np_.maengel_pruefen([m(klasse="B", workaround="w", workaround_freigegeben="nein", termin="2026-12-01")]))
        self.assertTrue(np_.maengel_pruefen([m(klasse="B", workaround="w", workaround_freigegeben="ja", termin="")]))
        self.assertEqual(np_.maengel_pruefen([m(klasse="B", workaround="w", workaround_freigegeben="ja", termin="2026-12-01")]), [])
        self.assertTrue(any("blockiert die Produktion" in x for x in np_.maengel_pruefen([m(klasse="A", workaround="w", workaround_freigegeben="ja", termin="2026-12-01")])))
        self.assertEqual(np_.maengel_pruefen([m(klasse="A", status="behoben")]), [])
        self.assertTrue(np_.maengel_pruefen([m(status="irgendwie")]))

    def test_offener_mangel_klasse_a_laesst_den_bericht_fehlschlagen(self):
        with tempfile.TemporaryDirectory() as d:
            mr = MiniRepo(d)
            self.assertEqual(mr.bericht()["verstoesse"], [])
            a = dict.fromkeys(np_.MAENGEL_SPALTEN, "")
            a.update(id="M-9", klasse="A", titel="Doppelbuchung", source_keys="LH-X-1", status="offen")
            b = mr.bericht(maengel_liste=[a])
            self.assertTrue(any("Klasse A" in v for v in b["verstoesse"]))
            z = {x["source_key"]: x for x in b["matrix"]}
            self.assertEqual(z["LH-X-1"]["mangelklasse_offen"], "A")
            self.assertEqual(z["LH-X-2"]["mangelklasse_offen"], "")

    def test_echtes_maengelregister_ist_konsistent(self):
        liste = np_.maengel(REPO)
        self.assertGreaterEqual(len(liste), 3)
        keys = {r["source_key"] for r in np_.p1_zeilen(REPO)}
        for m in liste:
            self.assertEqual(set(m), set(np_.MAENGEL_SPALTEN))
            for k in m["source_keys"].split(","):
                self.assertIn(k, keys, m["id"])


class Gates(unittest.TestCase):
    def test_sieben_gates_mit_nachweis_oder_sperrwirkung_im_echten_bericht(self):
        b = np_.bericht(REPO)
        self.assertEqual([g["gate"] for g in b["gates"]], list(np_.GATES))
        for g in b["gates"]:
            self.assertIn(g["stand"], ("erbracht", "teilweise", "fehlt"))
            self.assertTrue(g["sperrwirkung"])
            self.assertEqual(g["sperrwirkung_aktiv"], g["stand"] != "erbracht")
            self.assertTrue(g["nachweise"], g["gate"])
            for n in g["nachweise"]:
                self.assertEqual(n["fehlende_verweise"] if "fehlende_verweise" in n else [], [], f"{g['gate']}: Verweis fehlt")
        md = np_.bericht_markdown(b)
        for g in np_.GATES:
            self.assertRegex(md, rf"### 2\.\d Gate {g}: ")
        for d in range(1, 13):
            self.assertIn(f"| D-{d:02d} |", md)
        self.assertIn("Abnahmeentscheidung trifft der Auftraggeber", md)
        self.assertIn("Mängelliste", md)
        # Ehrlichkeit: solange ein Gate nicht erbracht ist, nennt der Bericht Verstöße
        if any(g["stand"] != "erbracht" for g in b["gates"]):
            self.assertTrue(b["verstoesse"])
            self.assertIn("GATE NICHT BESTANDEN", md)

    def test_gate_ohne_nachweisdatei_oder_mit_fehlendem_verweis_ist_nicht_erbracht(self):
        with tempfile.TemporaryDirectory() as d:
            m = MiniRepo(d)
            g = copy.deepcopy(GATE_OK)
            g["gates"]["UX"]["nachweise"][0]["verweise"] = ["docs/gibt-es-nicht.md"]
            (m.root / "docs/umsetzungsplan/nachweise/gate-nachweise.json").write_text(json.dumps(g), encoding="utf-8")
            gates = {x["gate"]: x for x in m.bericht()["gates"]}
            self.assertEqual(gates["UX"]["stand"], "fehlt")
            self.assertEqual(gates["CI"]["stand"], "erbracht")
            (m.root / "docs/umsetzungsplan/nachweise/gate-nachweise.json").unlink()
            gates = {x["gate"]: x["stand"] for x in m.bericht()["gates"]}
            self.assertEqual({g: s for g, s in gates.items() if g != "Partnerfreigabe"}, {g: "fehlt" for g in np_.GATES if g != "Partnerfreigabe"})

    def test_gate_partnerfreigabe_keine_buchbaren_partner(self):
        """Regel für P1 (OP-20, Arbeitsannahme): alle Föderationsoperationen sind nicht gebaut und kein Hauptcode setzt EXTERNAL_BOOKING."""
        ok, belege = np_.partnerfaehigkeiten(REPO)
        self.assertTrue(ok, belege)
        self.assertTrue(any("nicht gebaut" in x for x in belege))
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "api").mkdir()
            (root / "api/federation.yaml").write_text("operationId: planJourney\noperationId: getOffer\n", encoding="utf-8")
            (root / "api/contract-pending.txt").write_text("planJourney  # x\n", encoding="utf-8")
            ok, belege = np_.partnerfaehigkeiten(root)
            self.assertFalse(ok)
            self.assertTrue(any("getOffer" in x for x in belege), belege)
            (root / "api/contract-pending.txt").write_text("planJourney\ngetOffer\n", encoding="utf-8")
            k = root / "backend/m/src/main/kotlin"
            k.mkdir(parents=True)
            (k / "X.kt").write_text("jdbc.update(\"INSERT INTO taktovia.journey_leg(a) VALUES (1, 'EXTERNAL_BOOKING')\")", encoding="utf-8")
            ok, belege = np_.partnerfaehigkeiten(root)
            self.assertFalse(ok)
            self.assertTrue(any("EXTERNAL_BOOKING gesetzt" in x for x in belege), belege)


class Fachmodell(unittest.TestCase):
    def test_review_protokoll_fachmodell(self):
        md = np_.fachmodell_markdown(REPO)
        self.assertEqual(np_.fachmodell_pruefen(md, REPO), [])
        for d in np_.GELD_BEFOERDERUNG:
            self.assertRegex(md, rf"\| {d} \| .+ \| offen .+ \| (keine produktive|Verkauf|Gemeinsamer|Anschluss|Finanzumfang|Einheitliche)")
        self.assertIn("Fachreview", md)
        self.assertIn("Zustandsautomaten", md)
        gespeichert = (REPO / "docs/umsetzungsplan/nachweise/fachmodell-review.md").read_text(encoding="utf-8")
        self.assertEqual(np_.fachmodell_pruefen(gespeichert, REPO), [])
        # negativ: fehlt die gesperrte Aktivierung einer Geldentscheidung, schlägt die Prüfung an
        kaputt = "\n".join(z for z in md.splitlines() if not z.startswith("| D-03 |"))
        self.assertTrue(any("D-03" in x for x in np_.fachmodell_pruefen(kaputt, REPO)))
        kaputt = md.replace("| ja |", "| **nein** |", 1)
        self.assertTrue(np_.fachmodell_pruefen(kaputt, REPO))

    def test_entscheidungen_d01_bis_d12_mit_stand_und_wirkung(self):
        ent = np_.entscheidungen(REPO)
        self.assertEqual([e["id"] for e in ent], [f"D-{i:02d}" for i in range(1, 13)])
        for e in ent:
            self.assertTrue(e["titel"])
            self.assertIn("offen", e["stand"], "kein Beschlussprotokoll vorhanden")
        self.assertTrue(next(e for e in ent if e["id"] == "D-11")["wirkung"], "D-11 hat Bezug zu P1-Arbeitspaketen")


class Konkurrierend(unittest.TestCase):
    def test_kapazitaets_geld_und_datentrennungstests_der_gate_suiten_laufen_mit_zwei_mandanten(self):
        """PH-AT-001 (Datentrennung) und PH-AT-004 (Kapazität unter Konkurrenz) liegen als Evidenz mit zwei Mandanten bzw. parallelen Prüfungen vor und sind nicht rot; fehlt eine Evidenz, ist das hier
        ein Befund und nicht grün."""
        nach_id = np_.evidenz_nach_testid(np_.evidenz_laden(REPO))
        e = nach_id.get("PH-AT-001")
        self.assertIsNotNone(e, "Evidenz PH-AT-001 fehlt")
        self.assertNotEqual(e["record"]["result"], "FAILED")
        schritte = " ".join(s.get("step", "") for s in e["record"]["soll_ist"])
        self.assertIn("B-UUID", schritte, "PH-AT-001 prüft die Daten des zweiten Mandanten (Mandant B) unter dem Kontext von Mandant A")
        f = nach_id.get("PH-AT-004")
        self.assertIsNotNone(f, "Evidenz PH-AT-004 fehlt")
        self.assertNotEqual(f["record"]["result"], "FAILED")
        text = json.dumps(f["record"]["soll_ist"], ensure_ascii=False).lower()
        self.assertTrue("parallel" in text or "konkurr" in text or "thread" in text, "PH-AT-004 prüft parallele Transaktionen")


class Werkzeug(unittest.TestCase):
    def test_exit_code_des_berichtslaufs(self):
        r = subprocess.run([sys.executable, str(REPO / "tools/nachweis_p1.py"), "bericht"], capture_output=True, text=True)
        a = json.loads(r.stdout)
        self.assertEqual(r.returncode, 0 if a["verstoesse"] == 0 else 1)
        self.assertEqual(sum(a["zaehlung"].values()), 202)
        self.assertEqual(set(a["gates"]), set(np_.GATES))
        r = subprocess.run([sys.executable, str(REPO / "tools/nachweis_p1.py"), "fachmodell"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout)


if __name__ == "__main__":
    unittest.main()
