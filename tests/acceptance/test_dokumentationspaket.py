"""WP-M5-27 / LH-6.20-QAT-006: Dokumentationspaket (Betriebshandbuch, Administratorhandbuch, Fahrerschulung, Datenschutzdokumentation) gegen den Build (tools/check_dokumentationspaket.py).

Grenze (ehrlich): geprüft werden Versionierung, Bezug auf den Build (jede beschriebene Oberfläche, Beschriftung und Schnittstelle existiert im Quelltext), Personendaten, Platzhalter,
Zugänglichkeit der Dokumentstruktur, Inhaltsabgleich der Fahrerschulung gegen das Betriebshandbuch und die Vollständigkeit des Standardbetrieb-Ablaufs samt Protokollprüfung.
NICHT ausgeführt und NICHT belegt: die Demonstration durch den Abnehmer ohne Entwicklerhilfe, der Durchlauf durch eine projektfremde Person, die Fahrerschulung in einer Schulungsumgebung
(es gibt keine Abnehmer, keine Schulungsumgebung und kein Flutter in dieser Umgebung); die Prüfung mit einem Screenreader. Das ausgefüllte Protokoll im Test ist synthetisch (Selbsttest
des Prüfwerkzeugs), keine Abnahme."""
import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
import check_dokumentationspaket as cd  # noqa: E402

PAKET = json.loads((REPO / "docs/dokumentation/paket.json").read_text(encoding="utf-8"))
DOK = {d["id"]: REPO / d["pfad"] for d in PAKET["dokumente"]}
BUILD = cd.Build(REPO)


def text(id_):
    return DOK[id_].read_text(encoding="utf-8")


class HandbuchGegenBuild(unittest.TestCase):
    def test_handbuchschritte_und_oberflaechen_existieren_im_abnahme_build(self):
        """Positiv: jede Oberfläche, Beschriftung und Schnittstelle der Dokumente existiert im Quelltext; negativ: fehlende Route, Beschriftung, Textschlüssel und operationId schlagen an."""
        self.assertEqual(cd.abgleich_build(REPO, BUILD), [])
        zaehler = 0
        for id_ in ("administratorhandbuch", "fahrerschulung", "standardbetrieb"):
            zaehler += len(cd.REF.findall(text(id_)))
        self.assertGreaterEqual(zaehler, 60, "das Paket muss seine Schritte tatsächlich an Oberflächen binden")
        self.assertGreaterEqual(len(cd.API_REF.findall(text("administratorhandbuch"))), 8)
        # negativ
        self.assertTrue(cd.referenzen_pruefen("**Gebiete**{{route:/admin/gibt-es-nicht}}", "x", BUILD))
        self.assertTrue(cd.referenzen_pruefen("Pfad {{route:/admin/gibt-es-nicht}}", "x", BUILD))
        self.assertTrue(cd.referenzen_pruefen("**Falsche Beschriftung**{{nav:/admin/tarife}}", "x", BUILD))
        self.assertEqual(cd.referenzen_pruefen("**Gebiete, Stops, Tarife**{{nav:/admin/gebiete}}", "x", BUILD), [])
        self.assertTrue(cd.referenzen_pruefen("**Dienst antreten**{{app:shift.gibtesnicht}}", "x", BUILD))
        self.assertTrue(cd.referenzen_pruefen("**Dienst abbrechen**{{app:shift.start}}", "x", BUILD))
        self.assertEqual(cd.referenzen_pruefen("**Dienst antreten**{{app:shift.start}}", "x", BUILD), [])
        self.assertTrue(cd.referenzen_pruefen("Siehe {{api:gibtEsNicht}}", "x", BUILD))
        self.assertEqual(cd.referenzen_pruefen("Siehe {{api:createService}}", "x", BUILD), [])
        self.assertTrue(cd.referenzen_pruefen("**Tarifrevision anlegen**{{src:web/operator/src/tarife/Gibtsnicht.tsx}}", "x", BUILD))
        self.assertTrue(cd.referenzen_pruefen("**Knopf den es nicht gibt**{{src:web/operator/src/tarife/Tarife.tsx}}", "x", BUILD))
        self.assertEqual(cd.referenzen_pruefen("Pfad {{route:/admin/tarife}} als reine Pfadangabe", "x", BUILD), [])
        self.assertTrue(cd.referenzen_pruefen("Nur {{app:shift.start}} ohne Beschriftung", "x", BUILD))

    def test_veraltetes_handbuch_wird_gegen_geaenderten_build_erkannt(self):
        """Der Build ändert sich (Beschriftung umbenannt): der Abgleich schlägt an; mit dem alten Stand ist er grün."""
        orig = text("administratorhandbuch")
        self.assertEqual(cd.referenzen_pruefen(orig, "a", BUILD), [])
        geaendert = copy.copy(BUILD)
        geaendert.nav = BUILD.nav.replace("'Gebiete, Stops, Tarife'", "'Betriebsgebiete'")
        befunde = cd.referenzen_pruefen(orig, "a", geaendert)
        self.assertTrue(any("Gebiete, Stops, Tarife" in b for b in befunde), befunde)

    def test_links_und_anker_loesen_auf(self):
        for id_, pfad in DOK.items():
            self.assertEqual(cd.links_pruefen(pfad.read_text(encoding="utf-8"), pfad, REPO), [], id_)
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "a.md"
            p.write_text("# A\n\n[kaputt](gibts-nicht.md) und [anker](#nope)\n", encoding="utf-8")
            self.assertEqual(len(cd.links_pruefen(p.read_text(encoding="utf-8"), p, Path(d))), 2)


class Versionierung(unittest.TestCase):
    def test_dokumente_versioniert_ohne_personendaten_und_platzhalter(self):
        """Positiv: vier Dokumente mit Version, Build-Bezug und Eintrag im Übergabeprotokoll; keine Personendaten, keine Platzhalter, Fristvermerke (D-11). Negativ: Treffer werden erkannt."""
        self.assertEqual(cd.versionierung(REPO), [])
        self.assertEqual(cd.inhalt_pruefen(REPO), [])
        ids = {d["id"] for d in PAKET["dokumente"]}
        self.assertTrue({"betriebshandbuch", "administratorhandbuch", "fahrerschulung", "datenschutzdokumentation"} <= ids)
        for id_ in ids:
            fm = cd.frontmatter(text(id_))
            self.assertEqual(fm["abnahme_build"], PAKET["abnahme_build"], id_)
            self.assertEqual(fm["sprache"], "de")
        # negativ: Personendaten
        self.assertTrue(cd.personendaten_und_platzhalter("Kontakt: erika.muster@gmx.de", "x"))
        self.assertEqual(cd.personendaten_und_platzhalter("Kontakt: erika.muster@example.org", "x"), [])
        self.assertTrue(cd.personendaten_und_platzhalter("Rufen Sie 0176 12345678 an", "x"))
        self.assertTrue(cd.personendaten_und_platzhalter("IBAN DE89 3704 0044 0532 0130 00", "x"))
        self.assertTrue(cd.personendaten_und_platzhalter("Karte 4111 1111 1111 1111", "x"))
        # negativ: Platzhalter
        for p in ("TODO: später", "Frist: <einsetzen>", "Wert TBD", "| a | |", "Text [...]"):
            self.assertTrue(cd.personendaten_und_platzhalter(p, "x"), p)
        # negativ: Frist ohne Vermerk
        self.assertTrue(cd.fristvermerke("Die Löschfrist beträgt 30 Tage.\n", "x"))
        self.assertEqual(cd.fristvermerke(("Die Löschfrist ist durch Auftraggeber festzulegen.\n") * 3, "x"), [])

    def test_versionsabweichung_und_fehlender_build_bezug_werden_erkannt(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "docs/dokumentation").mkdir(parents=True)
            paket = copy.deepcopy(PAKET)
            paket["dokumente"] = [{"id": f"d{i}", "pfad": f"docs/dokumentation/d{i}.md"} for i in range(4)]
            (root / "docs/dokumentation/paket.json").write_text(json.dumps(paket), encoding="utf-8")
            for i in range(4):
                fm = f"---\ndokument_version: 1.0\nabnahme_build: {paket['abnahme_build']}\nsprache: de\nstand: 2026-11-15\n---\n# D\n" if i else "# D ohne Kopf\n"
                (root / f"docs/dokumentation/d{i}.md").write_text(fm, encoding="utf-8")
            (root / "docs/dokumentation/uebergabeprotokoll.md").write_text("| d1.md | x | 1.0 |\n", encoding="utf-8")
            befunde = cd.versionierung(root)
            self.assertTrue(any("d0.md: Kopfzeile 'dokument_version' fehlt" in b for b in befunde), befunde)
            self.assertTrue(any("d2.md: Version 1.0 steht nicht im Übergabeprotokoll" in b for b in befunde), befunde)
            self.assertTrue(any("Bezugs-Commit" in b for b in befunde), befunde)


class Fahrerschulung(unittest.TestCase):
    def test_fahrerschulung_deckt_sicherheitsablaeufe_ab(self):
        """Positiv: Pflichtprüfung mit kritischem Mangel, Fahrzeugsperre, Offline, Ausfall der App und der Notbetrieb-Inhalt des Betriebshandbuchs sind abgedeckt; negativ: Entfernen erkannt."""
        schulung = text("fahrerschulung")
        handbuch = text("betriebshandbuch")
        self.assertEqual(cd.schulung_pruefen(schulung, handbuch), [])
        for anker in ("112-ausfall-der-driver-app-verbindung",):
            self.assertIn(anker, cd.betriebshandbuch_anker(REPO))
        for thema in ("Pflichtprüfung mit kritischem Mangel", "Fahrzeugsperre", "Ausfall der App"):
            ohne = schulung.replace(thema, "Etwas anderes")
            self.assertTrue(any(thema in b for b in cd.schulung_pruefen(ohne, handbuch)), thema)
        # Notbetriebsinhalt des Handbuchs, der in der Schulung fehlt
        handbuch_neu = handbuch.replace("| neue verbindliche Buchung |", "| Tankkartenabrechnung |", 1)
        self.assertTrue(any("Tankkartenabrechnung" in b or "Tankkartenabrechnung".lower() in b.lower() for b in cd.schulung_pruefen(schulung, handbuch_neu)))
        # Übungsablauf je Thema der Contract-Liste
        for t in cd.SCHULUNG_ABLAEUFE:
            self.assertIn(t, schulung)
        self.assertIn("synthetisch", schulung)
        self.assertIn("Schulungsumgebung", schulung)

    def test_dokumente_zugaenglich_ueberschriften_alternativtexte(self):
        """Positiv: genau eine H1, keine übersprungenen Ebenen, Alternativtexte, Tabellenkopf; negativ: Verstöße werden erkannt. Maßstab (OP-19) ist hier die Dokumentstruktur, nicht WCAG-Konformität."""
        for id_, pfad in DOK.items():
            self.assertEqual(cd.zugaenglichkeit(pfad.read_text(encoding="utf-8"), id_), [], id_)
        self.assertTrue(cd.zugaenglichkeit("# A\n### C\n", "x"))
        self.assertTrue(cd.zugaenglichkeit("# A\n# B\n", "x"))
        self.assertTrue(cd.zugaenglichkeit("# A\n![](bild.png)\n", "x"))
        self.assertTrue(cd.zugaenglichkeit("# A\n![Bild](bild.png)\n", "x"))
        self.assertEqual(cd.zugaenglichkeit("# A\n![Dienststart-Ansicht der Fahrer-App](bild.png)\n", "x"), [])
        self.assertTrue(cd.zugaenglichkeit("# A\n[](ziel.md)\n", "x"))
        self.assertTrue(cd.zugaenglichkeit("# A\n\n| a | b |\n| 1 | 2 |\n", "x"))


class Standardbetrieb(unittest.TestCase):
    def test_standardbetrieb_demonstration_mit_protokoll(self):
        """Trockenlauf der Demonstration: alle sechs Abläufe verweisen auf Handbuchkapitel, Oberfläche und Ersatznachweis; ein Protokoll wird nach der Vorlage geprüft (hier synthetisch)."""
        self.assertEqual(cd.standardbetrieb_pruefen(REPO, BUILD), [])
        a = cd.ablaeufe(text("standardbetrieb"))
        self.assertEqual([x["name"] for x in a], list(cd.STANDARDBETRIEB))
        vorlage = (REPO / "docs/dokumentation/vorlagen/demonstrationsprotokoll.md").read_text(encoding="utf-8")
        for x in a:
            self.assertIn(x["id"], vorlage)
        ok = "\n".join(
            ["- **Abnehmer:** Synthetische Testperson (Selbsttest des Prüfwerkzeugs)", "- **Datum:** 2026-11-15", "- **Abnahme-Build:** " + PAKET["abnahme_build"], "- **Unterstützung durch das Entwicklungsteam:** keine", "",
             "| ID | Ablauf | Ergebnis | Abweichung |", "|---|---|---|---|"]
            + [f"| {x['id']} | {x['name']} | bestanden | |" for x in a]
        )
        self.assertEqual(cd.protokoll_pruefen(ok, a), [])
        # negativ: Ablauf fehlt, Abweichung ohne Beschreibung, ungültiges Ergebnis, Kopf leer
        self.assertTrue(any("SB-06" in b for b in cd.protokoll_pruefen(ok.replace(f"| {a[5]['id']} |", "| SB-99 |"), a)))
        self.assertTrue(any("ohne Beschreibung" in b for b in cd.protokoll_pruefen(ok.replace("| SB-02 | " + a[1]["name"] + " | bestanden |", "| SB-02 | " + a[1]["name"] + " | nicht bestanden |"), a)))
        self.assertTrue(any("ungültig" in b for b in cd.protokoll_pruefen(ok.replace("| bestanden |", "| irgendwie |", 1), a)))
        self.assertTrue(any("Abnehmer" in b for b in cd.protokoll_pruefen(ok.replace("Synthetische Testperson (Selbsttest des Prüfwerkzeugs)", ""), a)))
        # negativ: Ablauf ohne Oberfläche und mit fehlendem Nachweis
        schlecht = text("standardbetrieb").replace(a[0]["oberflaeche"], "keine")
        self.assertTrue(cd.ablaeufe(schlecht)[0]["oberflaeche"] == "keine")

    def test_werkzeug_exit_code_und_ausgabe(self):
        r = subprocess.run([sys.executable, str(REPO / "tools" / "check_dokumentationspaket.py")], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual(json.loads(r.stdout)["ergebnis"], "OHNE_BEFUND")
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "protokoll.md"
            p.write_text("- **Abnehmer:** \n", encoding="utf-8")
            r = subprocess.run([sys.executable, str(REPO / "tools" / "check_dokumentationspaket.py"), "--protokoll", str(p)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 1)
            self.assertIn("BEFUNDE", r.stdout)


if __name__ == "__main__":
    unittest.main()
