"""WP-M5-29: Pilotbereitschaft (Abbruchkriterien, Rückfallbetrieb, Generalprobe, Produktionseinspielung) mit `tools/pilot_bereitschaft.py`.

Belegt (echt ausgeführt):
- Abbruchkriterien sind aus dem Monitoring messbar (Abgleich mit Alarmkatalog WP-M5-04 und Messpunktvertrag WP-M5-05); das Erreichen eines Kriteriums löst den Entscheidungsweg aus
  (Journal als Hash-Kette, genau eine Entscheidung auch bei gleichzeitigen Versuchen, nur die Entscheiderrolle, Klasse A nicht fortsetzbar, Wiederaufnahme mit zwei Personen).
- Produktionseinspielung in einer ERSATZUMGEBUNG: die echte Anwendung (Spring-Boot-Jar, Runtime-Rolle, lokaler JWT-Test-Issuer statt Keycloak) und das echte Einspielwerkzeug; das
  Migrationsprotokoll wird auf Soll gleich Ist geprüft, Probebuchung im eingespielten Gebiet des Pilotmandanten (die Probebuchung des Probemandanten selbst belegt WP-M5-05 in
  tests/performance/availability und wird hier nicht wiederholt), Prüfung der Datenbank auf Testdaten, Abweisung einer Rolle ohne Rechte am Zielmandanten.
NICHT ausgeführt, NICHT belegt: die Generalprobe in Staging mit Betreiberpersonal, die Übung des Rückfallbetriebs, die Besetzung der Prozessverantwortlichen, die Freigabe der Schwellen durch den
Auftraggeber und die Einspielung in eine echte Produktionsumgebung (es gibt weder Staging noch Produktion noch Betreiberpersonal). Generalprobe- und Übungsprotokolle werden hier nur auf Prüfbarkeit
anhand SYNTHETISCHER Protokolle getestet (Selbsttest des Prüfwerkzeugs), das ist keine Generalprobe."""
import copy
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
import uuid
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(REPO / "tools"))
sys.path.insert(0, str(HERE))
import pilot_bereitschaft as pb  # noqa: E402

DOC = pb.kriterien()


class Abbruchkriterien(unittest.TestCase):
    def test_abbruchkriterien_aus_monitoring_messbar(self):
        """Positiv: jedes Kriterium hat eine Messquelle im Alarmkatalog bzw. im Messpunktvertrag; negativ: unbekannte Quellen, fehlende Felder und falsche Verweise werden erkannt."""
        self.assertEqual(pb.kriterien_pruefen(DOC), [])
        self.assertGreaterEqual(len(DOC["kriterien"]), 4)
        arten = {c["messquelle"]["art"] for c in DOC["kriterien"]}
        self.assertEqual(arten, {"alarm", "metrik", "verfuegbarkeit"})
        self.assertTrue({"A", "B"} <= {c["mangelklasse"] for c in DOC["kriterien"]})
        d = copy.deepcopy(DOC)
        d["kriterien"][0]["messquelle"] = {"art": "alarm", "id": "GIBT_ES_NICHT"}
        d["kriterien"][1]["messquelle"] = {"art": "verfuegbarkeit", "bereich": "unbekannt", "feld": "verfuegbarkeit_prozent"}
        d["kriterien"][2]["messquelle"] = {"art": "metrik", "name": "taktovia_gibt_es_nicht", "alarm": "PSP_UNBESTIMMT"}
        d["kriterien"][3]["messquelle"] = {"art": "bauchgefuehl"}
        befunde = pb.kriterien_pruefen(d)
        self.assertEqual(len([b for b in befunde if b.startswith(("PK-01", "PK-02", "PK-03", "PK-04"))]), 4, befunde)
        d = copy.deepcopy(DOC)
        del d["kriterien"][0]["messgroesse"]
        d["kriterien"][1]["vergleich"] = "ungefaehr"
        d["kriterien"][2]["mangelklasse"] = "Z"
        d["entscheider"] = {}
        befunde = pb.kriterien_pruefen(d)
        self.assertTrue(any("messgroesse" in b for b in befunde), befunde)
        self.assertTrue(any("Vergleichsoperator" in b for b in befunde), befunde)
        self.assertTrue(any("Mangelklasse" in b for b in befunde), befunde)
        self.assertTrue(any("Entscheider" in b for b in befunde), befunde)

    def test_offene_schwelle_gilt_nie_als_eingehalten(self):
        """Solange der Auftraggeber keine Schwelle festgelegt hat, ist ein Kriterium 'nicht bewertbar' und die Bereitschaft nicht freigegeben; ein fehlender Messwert ist kein Erfolg."""
        erg = pb.bewerten(DOC, {c["id"]: 0 for c in DOC["kriterien"]})
        self.assertEqual(erg["eingehalten"], [])
        self.assertEqual(sorted(erg["nicht_bewertbar"]), sorted(c["id"] for c in DOC["kriterien"]))
        self.assertEqual(len(pb.offene_punkte_kriterien(DOC)), len(DOC["kriterien"]) + 1)
        schwellen = {c["id"]: c["schwelle_arbeitsannahme"] for c in DOC["kriterien"]}
        erg = pb.bewerten(DOC, {"PK-01": 0}, schwellen)
        self.assertEqual(erg["eingehalten"], ["PK-01"])
        self.assertEqual(sorted(erg["kein_messwert"]), ["PK-02", "PK-03", "PK-04", "PK-05"])
        befunde, offen = pb.bereitschaft()
        self.assertEqual(befunde, [])
        self.assertTrue(offen, "ohne Freigabe, Besetzung, Generalprobe und Übung ist der Pilot nicht bereit")

    def test_simuliertes_erreichen_eines_abbruchkriteriums_loest_entscheidungsweg_aus(self):
        """Negativ laut WP: ein Messwert über der Schwelle löst den Entscheidungsweg aus (Eintrag mit Stufen, Fristen, Entscheiderrolle); ein Messwert innerhalb der Schwelle nicht."""
        schwellen = {c["id"]: c["schwelle_arbeitsannahme"] for c in DOC["kriterien"]}
        with tempfile.TemporaryDirectory() as d:
            j = pb.Journal(Path(d) / "journal.jsonl", DOC)
            self.assertEqual(j.zustand(), "BETRIEB")
            ruhig = pb.bewerten(DOC, {"PK-01": 0, "PK-02": 99.9, "PK-03": 0, "PK-04": 0, "PK-05": 0}, schwellen)
            self.assertEqual(ruhig["verletzt"], [])
            self.assertEqual(j.eintraege(), [])
            laut = pb.bewerten(DOC, {"PK-01": 12, "PK-02": 99.9, "PK-03": 9, "PK-04": 0, "PK-05": 0}, schwellen)
            self.assertEqual([v["id"] for v in laut["verletzt"]], ["PK-01", "PK-03"])
            eintraege = [j.ausloesen(v) for v in laut["verletzt"]]
            self.assertEqual([e["seq"] for e in eintraege], [1, 2])
            self.assertEqual(j.zustand(), "ENTSCHEIDUNG_OFFEN")
            e = j.eintraege()[0]
            self.assertEqual((e["art"], e["kriterium"], e["messwert"], e["schwelle"], e["mangelklasse"], e["entscheider_rolle"]), ("AUSLOESUNG", "PK-01", 12, 10, "A", "betriebsleitung"))
            self.assertEqual([s["rolle"] for s in e["stufen"]], ["betrieb-bereitschaft", "betriebsleitung", "auftraggeber"])
            self.assertTrue(all(s["frist_min"] > 0 for s in e["stufen"]))
            with self.assertRaises(ValueError, msg="kein zweiter offener Eintrag zum selben Kriterium"):
                j.ausloesen(laut["verletzt"][0])
            self.assertEqual(len(j.eintraege()), 2)

    def test_entscheidung_nur_durch_betriebsleitung_genau_einmal_auch_parallel(self):
        schwellen = {c["id"]: c["schwelle_arbeitsannahme"] for c in DOC["kriterien"]}
        with tempfile.TemporaryDirectory() as d:
            j = pb.Journal(Path(d) / "journal.jsonl", DOC)
            v = pb.bewerten(DOC, {"PK-03": 9}, schwellen)["verletzt"][0]
            a = j.ausloesen(v)["seq"]
            for kwargs, text in (({"rolle": "dispatcher", "entscheider": "p1", "entscheidung": "PILOT_UNTERBRECHEN", "begruendung": "x"}, "darf nicht entscheiden"),
                                 ({"rolle": "betriebsleitung", "entscheider": "", "entscheidung": "PILOT_UNTERBRECHEN", "begruendung": "x"}, "Entscheider"),
                                 ({"rolle": "betriebsleitung", "entscheider": "p1", "entscheidung": "EGAL", "begruendung": "x"}, "ungültig"),
                                 ({"rolle": "betriebsleitung", "entscheider": "p1", "entscheidung": "PILOT_UNTERBRECHEN", "begruendung": " "}, "Begründung"),
                                 ({"rolle": "betriebsleitung", "entscheider": "p1", "entscheidung": "MIT_AUFLAGE_FORTSETZEN", "begruendung": "x"}, "Auflage")):
                with self.assertRaises(ValueError) as cm:
                    j.entscheiden(a, **kwargs)
                self.assertIn(text, str(cm.exception))
            self.assertEqual(len(j.eintraege()), 1, "abgewiesene Entscheidungen hinterlassen keinen Eintrag")
            with self.assertRaises(ValueError):
                j.entscheiden(99, "p1", "betriebsleitung", "PILOT_UNTERBRECHEN", "x")
            # konkurrierend: zehn gleichzeitige Entscheidungen zur selben Auslösung, genau eine wirkt
            ergebnisse, start = [], threading.Barrier(10)

            def versuch(n):
                start.wait()
                try:
                    pb.Journal(Path(d) / "journal.jsonl", DOC).entscheiden(a, f"person{n}", "betriebsleitung" if n % 2 else "betriebsleitung_vertretung", "PILOT_UNTERBRECHEN", "Zahlungen ungeklärt")
                    ergebnisse.append("ok")
                except ValueError as e:
                    ergebnisse.append(str(e))
            ts = [threading.Thread(target=versuch, args=(n,)) for n in range(10)]
            [t.start() for t in ts]
            [t.join() for t in ts]
            self.assertEqual(ergebnisse.count("ok"), 1, ergebnisse)
            self.assertTrue(all("bereits eine Entscheidung" in r for r in ergebnisse if r != "ok"), ergebnisse)
            e = j.eintraege()
            self.assertEqual([x["art"] for x in e], ["AUSLOESUNG", "ENTSCHEIDUNG"])
            self.assertEqual(j.zustand(), "UNTERBROCHEN")

    def test_gleichzeitige_ausloesungen_halten_die_hash_kette(self):
        schwellen = {c["id"]: c["schwelle_arbeitsannahme"] for c in DOC["kriterien"]}
        with tempfile.TemporaryDirectory() as d:
            v = pb.bewerten(DOC, {"PK-01": 50, "PK-03": 50, "PK-04": 99, "PK-05": 99}, schwellen)["verletzt"]
            self.assertEqual(len(v), 4)
            start = threading.Barrier(len(v) * 2)

            def versuch(x):
                start.wait()
                try:
                    pb.Journal(Path(d) / "j.jsonl", DOC).ausloesen(x)
                except ValueError:
                    pass
            ts = [threading.Thread(target=versuch, args=(x,)) for x in v for _ in range(2)]
            [t.start() for t in ts]
            [t.join() for t in ts]
            e = pb.Journal(Path(d) / "j.jsonl", DOC).eintraege()
            self.assertEqual(sorted(x["kriterium"] for x in e), ["PK-01", "PK-03", "PK-04", "PK-05"], "je Kriterium genau ein Eintrag, Kette lückenlos")
            self.assertEqual([x["seq"] for x in e], [1, 2, 3, 4])

    def test_klasse_a_nicht_fortsetzbar_und_wiederaufnahme_nur_mit_zwei_personen(self):
        schwellen = {c["id"]: c["schwelle_arbeitsannahme"] for c in DOC["kriterien"]}
        ruhig = {"PK-01": 0, "PK-02": 99.95, "PK-03": 0, "PK-04": 0, "PK-05": 0}
        with tempfile.TemporaryDirectory() as d:
            j = pb.Journal(Path(d) / "j.jsonl", DOC)
            a = j.ausloesen(pb.bewerten(DOC, {"PK-01": 30}, schwellen)["verletzt"][0])["seq"]
            with self.assertRaises(ValueError) as cm:
                j.entscheiden(a, "p1", "betriebsleitung", "MIT_AUFLAGE_FORTSETZEN", "Ursache bekannt", auflage="Beobachtung")
            self.assertIn("Klasse A", str(cm.exception))
            with self.assertRaises(ValueError):
                j.wiederaufnehmen("p1", "p2", ruhig, schwellen)  # noch keine Unterbrechung
            j.entscheiden(a, "p1", "betriebsleitung", "PILOT_UNTERBRECHEN", "Kernbuchung gestört")
            self.assertEqual(j.zustand(), "UNTERBROCHEN")
            for bl, ag, werte, text in (("p1", "p1", ruhig, "zwei verschiedene Personen"), ("p1", "", ruhig, "zwei verschiedene Personen"),
                                        ("p1", "p2", {**ruhig, "PK-01": 20}, "nicht eingehalten"), ("p1", "p2", {"PK-01": 0}, "nicht eingehalten")):
                with self.assertRaises(ValueError) as cm:
                    j.wiederaufnehmen(bl, ag, werte, schwellen)
                self.assertIn(text, str(cm.exception))
            with self.assertRaises(ValueError, msg="offene Schwellen verbieten die Wiederaufnahme"):
                j.wiederaufnehmen("p1", "p2", ruhig)
            j.wiederaufnehmen("p1", "p2", ruhig, schwellen)
            self.assertEqual(j.zustand(), "BETRIEB")
            # Klasse B darf mit Auflage fortgesetzt werden
            b = j.ausloesen(pb.bewerten(DOC, {"PK-03": 9}, schwellen)["verletzt"][0])["seq"]
            j.entscheiden(b, "p1", "betriebsleitung", "MIT_AUFLAGE_FORTSETZEN", "Zahlungen werden täglich geklärt", auflage="tägliche Klärung durch Finanzen")
            self.assertEqual(j.zustand(), "BETRIEB")

    def test_journal_manipulation_wird_erkannt(self):
        schwellen = {c["id"]: c["schwelle_arbeitsannahme"] for c in DOC["kriterien"]}
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "j.jsonl"
            j = pb.Journal(p, DOC)
            a = j.ausloesen(pb.bewerten(DOC, {"PK-03": 9}, schwellen)["verletzt"][0])["seq"]
            j.entscheiden(a, "p1", "betriebsleitung", "PILOT_UNTERBRECHEN", "x")
            original = p.read_text(encoding="utf-8")
            p.write_text(original.replace('"PILOT_UNTERBRECHEN"', '"MIT_AUFLAGE_FORTSETZEN"'), encoding="utf-8")
            with self.assertRaises(ValueError):
                j.eintraege()
            zeilen = original.splitlines()
            p.write_text(zeilen[1] + "\n", encoding="utf-8")  # erster Eintrag gelöscht
            with self.assertRaises(ValueError):
                j.eintraege()


class Verantwortliche(unittest.TestCase):
    def test_prozessverantwortliche_pilot_gate(self):
        doc = pb.verantwortliche()
        befunde, offen = pb.verantwortliche_pruefen(doc)
        self.assertEqual(befunde, [])
        self.assertEqual([v["prozess"] for v in doc["verantwortungen"]], list(pb.PROZESSE))
        self.assertGreaterEqual(len(offen), 10, "keine Person und keine Vertretung ist benannt: die Stellen sind offen, nicht erfunden")
        rollen = pb.rollen_der_plattform()
        for r in ("dispatcher", "workshop", "finance", "tenant_admin"):
            self.assertIn(r, rollen)
        voll = copy.deepcopy(doc)
        for i, v in enumerate(voll["verantwortungen"]):
            v.update(person=f"person-{i}-pseudonym", vertretung=f"vertretung-{i}-pseudonym", in_plattform_angelegt=True)
        self.assertEqual(pb.verantwortliche_pruefen(voll), ([], []))
        # negativ
        voll["verantwortungen"][0]["vertretung"] = voll["verantwortungen"][0]["person"]
        voll["verantwortungen"][1]["plattformrolle"] = "superadmin"
        del voll["verantwortungen"][2]
        befunde, _ = pb.verantwortliche_pruefen(voll)
        self.assertTrue(any("Vertretung ist dieselbe Person" in b for b in befunde), befunde)
        self.assertTrue(any("superadmin" in b for b in befunde), befunde)
        self.assertTrue(any("Zahlungsklärung" in b for b in befunde), befunde)


class Protokolle(unittest.TestCase):
    """Prüfbarkeit der Protokolle anhand SYNTHETISCHER Beispiele (keine Generalprobe, keine Übung)."""

    def generalprobe(self):
        return {"umgebung": "staging", "datum": "2026-12-01", "abnahme_build": "ENTWURF-W18-9d035b7", "datenstand": "synthetisch", "betriebstag_dauer_h": 10,
                "pilotstammdaten_protokoll_sha256": "a" * 64, "betreiberpersonal": [{"prozess": p, "person_pseudonym": f"p{i}"} for i, p in enumerate(pb.PROZESSE)],
                "befunde": [{"id": "B1", "mangelklasse": "C", "status": "OFFEN"}], "ergebnis": "ohne_mangel_klasse_a"}

    def test_generalprobe_protokoll_pruefung(self):
        g = self.generalprobe()
        self.assertEqual(pb.generalprobe_pruefen(g, "a" * 64), [])
        n = copy.deepcopy(g)
        n["umgebung"], n["betriebstag_dauer_h"] = "production", 2
        n["betreiberpersonal"].pop()
        n["befunde"].append({"id": "B2", "mangelklasse": "A", "status": "OFFEN"})
        n["befunde"].append({"id": "B3", "mangelklasse": "?", "status": "OFFEN"})
        b = pb.generalprobe_pruefen(n, "a" * 64)
        for text in ("staging", "Betriebstag", "Betreiberpersonal für 'Betrieb und Rufbereitschaft'", "B2", "B3", "widerspricht"):
            self.assertTrue(any(text in x for x in b), (text, b))
        self.assertTrue(any("nicht aus dem freigegebenen Migrationsprotokoll" in x for x in pb.generalprobe_pruefen(g, "b" * 64)))
        n = copy.deepcopy(g)
        n["befunde"] = [{"id": "B2", "mangelklasse": "A", "status": "BEHOBEN_UND_WIEDERHOLT"}]
        self.assertEqual(pb.generalprobe_pruefen(n, "a" * 64), [])
        for vorlage in (REPO / "docs/pilot/vorlagen/generalprobe-protokoll.json",):
            self.assertTrue(pb.generalprobe_pruefen(json.loads(vorlage.read_text(encoding="utf-8"))), "die leere Vorlage ist keine bestandene Generalprobe")

    def test_rueckfalluebung_protokoll_pruefung(self):
        schritte = json.loads((REPO / "docs/pilot/rueckfallbetrieb.json").read_text(encoding="utf-8"))["schritte"]
        self.assertGreaterEqual(len(schritte), 6)
        p = {"art": "Tischuebung", "datum": "2026-12-02", "szenario_kriterium": "PK-01", "abnahme_build": "ENTWURF-W18-9d035b7",
             "schritte": [{"schritt": s, "verantwortlich": "Leitstelle", "dauer_min": 5} for s in schritte]}
        self.assertEqual(pb.rueckfalluebung_pruefen(p, schritte), [])
        p["schritte"].pop(3)
        p["schritte"][0]["verantwortlich"] = ""
        p["art"] = "Vortrag"
        b = pb.rueckfalluebung_pruefen(p, schritte)
        self.assertTrue(any("nicht durchlaufen" in x for x in b), b)
        self.assertTrue(any("ohne Verantwortlichen" in x for x in b), b)
        self.assertTrue(any("Art" in x for x in b), b)
        for s in schritte:
            self.assertIn(s.split(":")[0].split(",")[0][:20], (REPO / "docs/pilot/pilotbereitschaft.md").read_text(encoding="utf-8").replace("Entscheidung zur", "Entscheidung zur"))

    def test_werkzeug_exit_codes_und_pilotdokument(self):
        r = subprocess.run([sys.executable, str(REPO / "tools/pilot_bereitschaft.py"), "bereitschaft"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 1, "offene Punkte: nicht freigegeben")
        a = json.loads(r.stdout)
        self.assertEqual((a["ergebnis"], a["befunde"]), ("STRUKTUR_OK_NICHT_FREIGEGEBEN", []))
        r = subprocess.run([sys.executable, str(REPO / "tools/pilot_bereitschaft.py"), "bereitschaft", "--nur-struktur"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout)
        with tempfile.TemporaryDirectory() as d:
            m = Path(d) / "m.json"
            m.write_text(json.dumps({"PK-01": 99}), encoding="utf-8")
            s = Path(d) / "s.json"
            s.write_text(json.dumps({"PK-01": 10}), encoding="utf-8")
            j = Path(d) / "j.jsonl"
            r = subprocess.run([sys.executable, str(REPO / "tools/pilot_bereitschaft.py"), "bewerten", "--messwerte", str(m), "--schwellen", str(s), "--journal", str(j)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 1)
            self.assertEqual(json.loads(r.stdout)["eintraege"], [1])
            r = subprocess.run([sys.executable, str(REPO / "tools/pilot_bereitschaft.py"), "entscheiden", "--journal", str(j), "--auslosung", "1", "--entscheider", "p1", "--rolle", "planner",
                                "--entscheidung", "PILOT_UNTERBRECHEN", "--begruendung", "x"], capture_output=True, text=True)
            self.assertEqual(r.returncode, 2)
            self.assertIn("darf nicht entscheiden", r.stderr)
            r = subprocess.run([sys.executable, str(REPO / "tools/pilot_bereitschaft.py"), "entscheiden", "--journal", str(j), "--auslosung", "1", "--entscheider", "p1", "--rolle", "betriebsleitung",
                                "--entscheidung", "PILOT_UNTERBRECHEN", "--begruendung", "Kernbuchung gestört"], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
        text = (REPO / "docs/pilot/pilotbereitschaft.md").read_text(encoding="utf-8")
        self.assertIn("NICHT FREIGEGEBEN", text)
        self.assertIn("nicht durchgeführt", text)


# ----------------------------------------------------------------------------------------------------------------------- Produktionseinspielung (Ersatzumgebung)
def _jar_da():
    from stammdaten_testwelt.app import JAR
    if JAR.exists():
        return True
    if os.environ.get("TAKTOVIA_REQUIRE_APP_JAR") == "true":
        raise AssertionError(f"{JAR} fehlt (TAKTOVIA_REQUIRE_APP_JAR=true): cd backend && ./gradlew :app:bootJar")
    return False


class MitAnwendung(unittest.TestCase):
    """Eine eigene Datenbank und ein eigener Anwendungsprozess je Klasse (Datenbanknamen gehören diesem Cluster, andere Pakete bleiben unberührt)."""
    DBNAME = ""

    @classmethod
    def setUpClass(cls):
        if not _jar_da():
            raise unittest.SkipTest("backend/app/build/libs/app.jar fehlt: cd backend && ./gradlew :app:bootJar (nicht ausgeführt)")
        from taktovia_at import db as atdb
        from stammdaten_testwelt.welt import Welt
        atdb.reset_database(cls.DBNAME)
        cls.tmpdir = tempfile.TemporaryDirectory(prefix="pilot-")
        cls.welt = Welt(cls.DBNAME)

    @classmethod
    def tearDownClass(cls):
        w = getattr(cls, "welt", None)
        if w:
            w.close()
            from taktovia_at import db as atdb
            atdb.drop_database(cls.DBNAME)
        if getattr(cls, "tmpdir", None):
            cls.tmpdir.cleanup()

    def tmp(self, name):
        return os.path.join(self.tmpdir.name, name)

    def einspielen(self, m, name, variante="A", **kw):
        from stammdaten_testwelt import referenz
        from stammdaten_import import einspielung
        pfad = referenz.schreiben(referenz.datensatz(m.id, self.welt.idp.issuer, variante), self.tmp(f"{name}.json"))
        return einspielung.einspielen(pfad, self.welt.app.base_url, kw.pop("zugaenge", m.zugaenge()), zustand_pfad=self.tmp(f"{name}.zustand.json"), protokoll_pfad=self.tmp(f"{name}.protokoll.json"),
                                      build=kw.pop("build", "ENTWURF-W18-9d035b7"), migrationsstand="V50290", freigeber="freigeber-pilot", **kw)


class Produktionseinspielung(MitAnwendung):
    DBNAME = "taktovia_at_w18c1_pilot"

    def probebuchung(self, m):
        from test_stammdaten_migration import angebotsanfrage
        W = self.welt
        subject, token = W.grant(m, "passenger")
        tid = str(m.id)
        with W.admin() as c:
            sid = c.execute("SELECT id FROM taktovia.service WHERE tenant_id=%s", (tid,)).fetchone()[0]
            fahrzeug = c.execute("SELECT id FROM taktovia.vehicle WHERE tenant_id=%s AND vehicle_label LIKE '%%-TX 101'", (tid,)).fetchone()[0]
            fahrer = c.execute("SELECT id FROM taktovia.driver WHERE tenant_id=%s ORDER BY id LIMIT 1", (tid,)).fetchone()[0]
        r = W.client.anfrage("POST", "/v1/ride-offers", token, angebotsanfrage(sid, tage=3, personen=2))
        self.assertEqual(r.status, 200)
        angebot = r.body["offers"][0]
        brutto = angebot["gross"]["amount_minor"]
        self.assertEqual(brutto, 900, "2 Personen x 450 laut eingespieltem Tarif")
        von = datetime.fromisoformat(angebot["pickup_window"]["earliest_at"].replace("Z", "+00:00")) - timedelta(hours=1)
        bis = datetime.fromisoformat(angebot["arrival_window"]["latest_at"].replace("Z", "+00:00")) + timedelta(hours=1)
        schicht, umlauf = uuid.uuid4(), uuid.uuid4()
        with W.admin() as c:  # Testaufbau wie in test_stammdaten_migration (Fahrzeugfreigabe, Schicht, Umlauf, Planbindung des Angebots sind nicht Teil der Einspielung)
            c.execute("UPDATE taktovia.vehicle SET active_block = false WHERE tenant_id = %s AND id = %s", (tid, fahrzeug))
            c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (%s,%s,%s,%s,%s,%s,'Europe/Berlin',1,'PUBLISHED')",
                      (tid, schicht, fahrer, fahrzeug, von, bis))
            c.execute("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until) VALUES (%s,%s,%s,%s,1,%s,1,%s)",
                      (tid, uuid.uuid4(), fahrzeug, schicht, uuid.uuid4(), bis))
            c.execute("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (%s,%s,%s,%s,1,'ACTIVE')", (tid, umlauf, fahrzeug, schicht))
            c.execute("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until,idp_issuer,idp_subject,status) VALUES (%s,%s,%s, now() + interval '5 years',%s,%s,'ACTIVE')",
                      (tid, uuid.uuid4(), "tok-" + uuid.uuid4().hex, W.idp.issuer, str(subject)))
            c.execute("ALTER TABLE taktovia.ride_offer DISABLE TRIGGER ride_offer_guard")
            try:
                c.execute("UPDATE taktovia.ride_offer SET run_id=%s, plan_revision=1, interval_from=0, interval_to=2 WHERE id=%s", (umlauf, angebot["id"]))
            finally:
                c.execute("ALTER TABLE taktovia.ride_offer ENABLE TRIGGER ride_offer_guard")
        maxi = {"max_gross": {"amount_minor": brutto, "currency": "EUR", "exponent": 2}}
        hold = W.client.anfrage("POST", f"/v1/ride-offers/{angebot['id']}/hold", token, maxi, if_match=angebot["revision"])
        self.assertEqual(hold.status, 201)
        buchung = W.client.anfrage("POST", "/v1/bookings", token, {"hold_id": hold.body["id"], **maxi}, if_match=hold.etag_zahl)
        self.assertEqual(buchung.status, 201, buchung.body)
        with W.admin() as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.booking WHERE tenant_id=%s", (tid,)).fetchone()[0], 1)

    def test_produktionseinspielung_mit_probebuchung_des_probemandanten(self):
        """Der Pilotmandant wird mit dem echten Werkzeug eingespielt; das Protokoll bestätigt Soll gleich Ist; Probebuchung im eingespielten Gebiet; die Datenbank enthält danach nur den
        freigegebenen Pilotmandanten und den gekennzeichneten Probemandanten, andere Mandanten sind Befunde. (Ersatzumgebung, keine Produktion.)"""
        W = self.welt
        sys.path.insert(0, str(REPO / "infra" / "availability"))
        from taktovia_availability import probemandant
        pilot = W.mandant("Pilotbetreiber " + uuid.uuid4().hex[:6])
        prot = self.einspielen(pilot, "pilot")
        self.assertEqual(pb.migrationsprotokoll_pruefen(prot, pilot.id, build="ENTWURF-W18-9d035b7"), [], prot["fehlerliste"])
        for art in pb.BESTAND_ARTEN:
            b = prot["bestaende"][art]
            self.assertEqual(b["ist"], b["soll"], art)
            self.assertGreater(b["soll"], 0, art)
        self.probebuchung(pilot)
        with W.admin() as c:
            # Probemandant wie für die synthetischen Proben (WP-M5-05, `tenant.synthetic`, unveränderlich gekennzeichnet); seine eigene Probebuchung ist Gegenstand von
            # tests/performance/availability (WP-M5-05) und wird hier nicht wiederholt.
            probe = probemandant.anlegen(c)["tenant"]
            self.assertEqual(pb.produktionsdaten_pruefen(c, {pilot.id}), [], "nur Pilotmandant und Probemandant")
            # negativ: ein weiterer, nicht gekennzeichneter Mandant (Testdaten), ein zweiter Probemandant, ein fehlender Pilotmandant, Verwechslung
            fremd = W.mandant("Testdaten " + uuid.uuid4().hex[:6])
            befunde = pb.produktionsdaten_pruefen(c, {pilot.id})
            self.assertEqual(len(befunde), 1, befunde)
            self.assertIn("Testdaten", befunde[0])
            self.assertEqual(pb.produktionsdaten_pruefen(c, {pilot.id, fremd.id}), [])
            self.assertTrue(any("Verwechslung" in b for b in pb.produktionsdaten_pruefen(c, {pilot.id, fremd.id, probe})))
            self.assertTrue(any("fehlt in der Datenbank" in b for b in pb.produktionsdaten_pruefen(c, {pilot.id, fremd.id, uuid.uuid4()})))
            probemandant.anlegen(c, name="zweiter-probemandant")
            self.assertTrue(any("2 Probemandanten" in b for b in pb.produktionsdaten_pruefen(c, {pilot.id, fremd.id})))
        # negativ: manipulierte Protokolle
        for mut, text in ((lambda p: p["bestaende"]["tarife"].update(ist=0), "tarife"), (lambda p: p["lauf"].update(modus="trockenlauf"), "kein Lauf"),
                          (lambda p: p["fehlerliste"].append({"grund": "X"}), "Fehlerliste"), (lambda p: p.update(build="anderer-build"), "Abnahme-Build"),
                          (lambda p: p["mandant"].update(tenant_id=str(uuid.uuid4())), "Mandant"), (lambda p: p["bestaende"].pop("fahrzeuge"), "fahrzeuge"),
                          (lambda p: p.update(konsistenz=[]), "Konsistenz"), (lambda p: p["lauf"].update(ergebnis="ABGESCHLOSSEN_MIT_FEHLERN"), "nicht fehlerfrei")):
            p = copy.deepcopy(prot)
            mut(p)
            b = pb.migrationsprotokoll_pruefen(p, pilot.id, build="ENTWURF-W18-9d035b7")
            self.assertTrue(any(text in x for x in b), (text, b))


class Einspielschutz(MitAnwendung):
    DBNAME = "taktovia_at_w18c1_pilot2"

    def test_einspielversuch_rolle_ohne_rechte_am_zielmandanten_wird_abgewiesen(self):
        """Negativ laut WP: Quelle des Zielmandanten mit den Zugängen eines anderen Mandanten; die Vorabprüfung bricht vor dem ersten Schreiben ab, das Protokoll ist `NICHT_GESTARTET` und
        besteht die Prüfung für die Produktionseinspielung nicht."""
        from stammdaten_import import einspielung
        W = self.welt
        ziel, fremd = W.mandant("Ziel " + uuid.uuid4().hex[:6]), W.mandant("Fremd " + uuid.uuid4().hex[:6])
        zaehl = "SELECT (SELECT count(*) FROM taktovia.service WHERE tenant_id=%s) + (SELECT count(*) FROM taktovia.vehicle WHERE tenant_id=%s)"
        with W.admin() as c:
            vorher = c.execute(zaehl, (ziel.id, ziel.id)).fetchone()[0]
        with self.assertRaises(einspielung.MandantAbweichung):
            self.einspielen(ziel, "falsch", zugaenge=fremd.zugaenge())
        prot = json.loads(Path(self.tmp("falsch.protokoll.json")).read_text())
        self.assertEqual((prot["lauf"]["ergebnis"], prot["fehlerliste"][0]["grund"]), ("NICHT_GESTARTET", "MANDANT_ABWEICHUNG"))
        self.assertTrue(pb.migrationsprotokoll_pruefen(prot, ziel.id))
        with W.admin() as c:
            self.assertEqual(vorher, c.execute(zaehl, (ziel.id, ziel.id)).fetchone()[0], "nichts geschrieben")

    def test_zwei_pilotmandanten_parallel_ohne_datenvermischung(self):
        """Konkurrierend (Datentrennung): zwei Einspielungen laufen gleichzeitig; jedes Protokoll besteht die Prüfung für den eigenen Mandanten und nicht für den anderen."""
        W = self.welt
        a, b = W.mandant("ParaA " + uuid.uuid4().hex[:6]), W.mandant("ParaB " + uuid.uuid4().hex[:6])
        ergebnis = {}

        def lauf(m, n, v):
            ergebnis[n] = self.einspielen(m, f"para-{n}", variante=v)
        ts = [threading.Thread(target=lauf, args=(a, "a", "A")), threading.Thread(target=lauf, args=(b, "b", "B"))]
        [t.start() for t in ts]
        [t.join() for t in ts]
        self.assertEqual(pb.migrationsprotokoll_pruefen(ergebnis["a"], a.id), [])
        self.assertEqual(pb.migrationsprotokoll_pruefen(ergebnis["b"], b.id), [])
        self.assertTrue(pb.migrationsprotokoll_pruefen(ergebnis["a"], b.id))
        with W.admin() as c:
            for m in (a, b):
                self.assertEqual(c.execute("SELECT count(*) FROM taktovia.vehicle WHERE tenant_id=%s", (m.id,)).fetchone()[0], ergebnis["a"]["bestaende"]["fahrzeuge"]["soll"])


if __name__ == "__main__":
    unittest.main()
