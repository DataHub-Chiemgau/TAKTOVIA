"""WP-M1-42 / LH-6.18-SEC-013: Release-Gate der Datenschutz-Folgenabschätzung (tools/check_dsfa.py).

Geprüft wird die Gate-Logik gegen das echte Dateninventar und die echten Arbeitspaketdateien, mit abgeleiteten DSFA-Fassungen (freigegeben, ohne Vermerk, ohne Referenz, ...).
Die Freigabe der echten DSFA ist organisatorisch (verantwortliche Stelle, OP-32) und wird hier nicht vorgetäuscht: die echte DSFA besteht die Strukturprüfung, das Release-Gate
besteht sie erst mit Freigabevermerk.
"""
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
import check_dsfa as gate  # noqa: E402

REAL = gate.DEFAULT_DSFA
INV = gate.dateninventar.INVENTORY_DIR
WPS = REPO / "docs" / "umsetzungsplan" / "wp"

APPROVAL = {"status": "FREIGEGEBEN", "freigabe_stelle": "Datenschutz Auftraggeber (Test)", "freigabe_name": "Test Prüfer",
            "freigabe_datum": "2026-12-01", "freigabe_vermerk": "Geprüft und freigegeben (Testfixture)"}


def with_frontmatter(text: str, **kv) -> str:
    for k, v in kv.items():
        text, n = re.subn(rf"(?m)^{k}:.*$", f"{k}: {v}", text, count=1)
        assert n == 1, k
    return text


class DsfaGate(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

    def dsfa(self, text: str) -> Path:
        p = self.dir / "dsfa.md"
        p.write_text(text, encoding="utf-8")
        return p

    def run_gate(self, text: str, release: bool = True, inventar: Path = INV) -> list[str]:
        return gate.check(self.dsfa(text), inventar, WPS, release)

    def approved(self) -> str:
        return with_frontmatter(REAL.read_text(encoding="utf-8"), **APPROVAL)

    def test_gate_besteht_mit_freigegebener_dsfa(self):
        text = self.approved()
        self.assertEqual(self.run_gate(text), [])
        # Die Freigabeunterlage enthält die Bewertung der Bewegungs- und Mobilitätsdaten und Maßnahmen mit Verweisen auf umsetzende Arbeitspakete
        self.assertIn("## 4. Bewertung der Bewegungs- und Mobilitätsdaten", text)
        measures = [l for l in gate.section(text, r"5\.").splitlines() if re.match(r"\|\s*M-\d+", l)]
        self.assertGreaterEqual(len(measures), 10)
        self.assertTrue(all(re.search(r"WP-M\d+[a-z]?-\d+", l) for l in measures))
        # Die echte DSFA besteht die Strukturprüfung (jeder Stand), und alle Inventarfelder sind referenziert
        self.assertEqual(gate.check(REAL, INV, WPS, release=False), [])
        fields, unmarked = gate.mobility_fields(INV)
        self.assertGreaterEqual(len(fields), 10); self.assertEqual(unmarked, [])
        # Kommandozeile: Struktur grün; Release mit der echten (nicht freigegebenen) DSFA rot, mit der freigegebenen Fassung grün
        self.assertEqual(subprocess.run([sys.executable, str(REPO / "tools" / "check_dsfa.py")], capture_output=True).returncode, 0)
        self.assertEqual(subprocess.run([sys.executable, str(REPO / "tools" / "check_dsfa.py"), "--release", "--dsfa", str(self.dsfa(text))], capture_output=True).returncode, 0)

    def test_mobilitaetsfeld_ohne_dsfa_referenz_scheitert(self):
        inv = self.dir / "inv"; shutil.copytree(INV, inv)
        (inv / "zz-test.csv").write_text("tabelle;spalte;personenbezogen;zweck;rechtsgrundlage;schutzklasse;loeschklasse;bemerkung\n"
                                         "journey_leg;pickup_geo_hint;ja;BUCHUNGSABWICKLUNG;ART6_1_B_VERTRAG;STANDORT_ETA;LK_BETRIEB;neues Mobilitätsfeld\n", encoding="utf-8")
        errs = self.run_gate(self.approved(), inventar=inv)
        self.assertIn("Bewegungs-/Mobilitätsfeld ohne DSFA-Referenz: journey_leg.pickup_geo_hint", errs)
        # auch ein ungekennzeichnetes Feld mit Ortsbezug im Namen wird beanstandet (Kennzeichnung vergessen)
        (inv / "zz-test.csv").write_text("tabelle;spalte;personenbezogen;zweck;rechtsgrundlage;schutzklasse;loeschklasse;bemerkung\n"
                                         "journey_leg;live_position;ja;BUCHUNGSABWICKLUNG;ART6_1_B_VERTRAG;KONTAKT_UNTERSTUETZUNG;LK_BETRIEB;Kennzeichnung vergessen\n", encoding="utf-8")
        self.assertTrue(any("journey_leg.live_position" in e for e in self.run_gate(self.approved(), inventar=inv)))
        # und ohne neues Feld ist dieselbe DSFA in Ordnung (der Test ist empfindlich)
        self.assertEqual(self.run_gate(self.approved()), [])

    def test_fehlender_freigabevermerk_scheitert(self):
        self.assertTrue(any("nicht freigegeben" in e for e in self.run_gate(REAL.read_text(encoding="utf-8"))), "Entwurf besteht das Release-Gate nicht")
        errs = self.run_gate(with_frontmatter(self.approved(), freigabe_vermerk=""))
        self.assertTrue(any("Freigabevermerk fehlt" in e for e in errs), errs)
        errs = self.run_gate(with_frontmatter(self.approved(), freigabe_stelle="", freigabe_name=""))
        self.assertTrue(any("verantwortliche Stelle fehlt" in e for e in errs) and any("freigebende Person fehlt" in e for e in errs), errs)
        errs = self.run_gate(with_frontmatter(self.approved(), status="ENTWURF"))
        self.assertTrue(any("nicht freigegeben" in e for e in errs), errs)

    def test_massnahme_ohne_wp_verweis_wird_beanstandet(self):
        text = self.approved()
        bad = re.sub(r"(\| M-03 \|[^|]*\|)[^|]*(\|)", r"\1 organisatorisch, ohne Verweis \2", text, count=1)
        self.assertNotEqual(bad, text)
        self.assertIn("Maßnahme ohne Verweis auf ein umsetzendes Arbeitspaket: M-03", self.run_gate(bad))
        unknown = re.sub(r"(\| M-03 \|[^|]*\|[^|]*)WP-M1-31", r"\1WP-M1-99", text, count=1)
        self.assertNotEqual(unknown, text)
        self.assertTrue(any("unbekanntes Arbeitspaket WP-M1-99" in e for e in self.run_gate(unknown)))

    def test_dsfa_ohne_bewertung_der_bewegungsdaten_scheitert(self):
        text = self.approved()
        no_section = re.sub(r"(?s)## 4\. .*?(?=## 5\.)", "## 4. Bewertung der Bewegungs- und Mobilitätsdaten\n\nsiehe später\n\n", text)
        self.assertTrue(any("Bewertung der Bewegungs- und Mobilitätsdaten fehlt" in e for e in self.run_gate(no_section)))
        for topic in ("Standortdaten", "Fahrerdaten", "ETA"):   # geplante Standort-, Fahrer- und ETA-Daten gehören zur Bewertung
            without = re.sub(rf"(?ms)^### 4\.\d [^\n]*{topic}[^\n]*\n.*?(?=^### |^## )", "", text)
            self.assertNotEqual(without, text, topic)
            self.assertTrue(any(f"zu {topic}-Daten" in e for e in self.run_gate(without)), topic)

    def test_fehlendes_freigabedatum_scheitert(self):
        errs = self.run_gate(with_frontmatter(self.approved(), freigabe_datum=""))
        self.assertTrue(any("Freigabedatum fehlt" in e for e in errs), errs)
        errs = self.run_gate(with_frontmatter(self.approved(), freigabe_datum="2026-01-01"))   # vor dem Datum der Version
        self.assertTrue(any("vor dem Datum" in e for e in errs), errs)
        errs = self.run_gate(with_frontmatter(self.approved(), datum="kein-datum"))
        self.assertTrue(any("ohne gültiges Datum" in e for e in errs), errs)


if __name__ == "__main__":
    unittest.main()
