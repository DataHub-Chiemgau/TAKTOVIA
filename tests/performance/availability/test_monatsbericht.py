"""WP-M5-05 / LH-6.19-NFR-001: Monatsbericht und Klassifikation der synthetischen Proben.

Ausgeführt: Berichtsgenerator über einen Testzeitraum mit bekannten Ausfällen, Wartungsfenstern und Lücken (reine Rechnung, deterministisch); die echten Proben (`probe_ausfuehren`)
über echtes HTTP (urllib, Loopback) gegen die Attrappe der Plattform (`attrappe_plattform.py`, NICHT die Anwendung) mit Plattformfehler, Providerfehler, ungültigen Zugangsdaten
und ausgefallener Probe; Schemaprüfung des Berichts. NICHT ausgeführt: ein voller Produktivmonat (OP-02), Proben gegen Staging (kein Zielsystem, D-05)."""
import json
import os
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(REPO / "infra" / "availability"))
sys.path.insert(0, str(HERE))

from taktovia_availability import bericht as B  # noqa: E402
from taktovia_availability import proben as P  # noqa: E402
from attrappe_plattform import PlattformAttrappe  # noqa: E402

try:
    import jsonschema
except ImportError:  # pragma: no cover
    jsonschema = None

SCHEMA = REPO / "docs" / "umsetzungsplan" / "vertraege" / "schemas" / "verfuegbarkeitsbericht.v1.schema.json"
VON = datetime(2026, 11, 10, 0, 0, tzinfo=timezone.utc)
BIS = VON + timedelta(days=1)
VERTRAG = B.lade_vertrag()
INTERVALL = VERTRAG["probenintervall_s"]


def reihe(bereich, ergebnis_fn, von=VON, bis=BIS):
    """Je Probenintervall ein Rohdatensatz; `ergebnis_fn(minute_des_tages)` liefert das Ergebnis oder None (Probe lief nicht)."""
    out, t = [], von
    while t < bis:
        m = int((t - VON).total_seconds() // 60)
        e = ergebnis_fn(m)
        if e is not None:
            out.append({"zeitpunkt": t.isoformat(), "bereich": bereich, "ergebnis": e, "dauer_ms": 120})
        t += timedelta(seconds=INTERVALL)
    return out


def anhaengen(pfad, satz):
    with open(pfad, "a", encoding="utf-8") as f:
        f.write(json.dumps(satz) + "\n")


def hm(h, m=0):
    return h * 60 + m


def bericht(saetze, wartung=(), von=VON, bis=BIS):
    return B.erzeuge_bericht(saetze, von, bis, VERTRAG, list(wartung), "rohdaten-test.jsonl", "a" * 64, erzeugt_am=bis)


class Monatsbericht(unittest.TestCase):
    def test_ausfall_bekannter_dauer_und_wartung_im_bericht(self):
        # Kernbuchung: Ausfall 10:00 bis 10:17 (17 min) und 20 Ausfallminuten im vorab eingetragenen Wartungsfenster 03:00 bis 03:30
        # Operator: Ausfall 12:00 bis 12:10 (10 min), kein Wartungsbezug (die Wartung gilt für die Plattform insgesamt)
        kern = reihe("kernbuchung", lambda m: P.FEHLER_PLATTFORM if hm(10) <= m < hm(10, 17) or hm(3, 5) <= m < hm(3, 25) else P.OK)
        oper = reihe("operator", lambda m: P.FEHLER_PLATTFORM if hm(12) <= m < hm(12, 10) else P.OK)
        wartung = [{"von": "2026-11-10T03:00:00Z", "bis": "2026-11-10T03:30:00Z", "grund": "Datenbankwartung", "eingetragen_am": "2026-11-05T09:00:00Z"}]
        b = bericht(kern + oper, wartung)
        k, o = b["bereiche"]["kernbuchung"], b["bereiche"]["operator"]
        tol = INTERVALL / 60.0
        # Ausfall bekannter Dauer erscheint mit dieser Dauer (Toleranz ein Probenintervall)
        ausf = [a for a in k["ausfaelle"] if a["davon_wartung_min"] == 0]
        self.assertEqual(len(ausf), 1); self.assertAlmostEqual(ausf[0]["dauer_min"], 17, delta=tol); self.assertTrue(ausf[0]["von"].startswith("2026-11-10T10:00"))
        self.assertAlmostEqual(o["ausfaelle"][0]["dauer_min"], 10, delta=tol)
        # das Wartungsfenster wird herausgerechnet und ausgewiesen
        self.assertEqual(b["wartungsfenster"][0]["minuten_im_zeitraum"], 30); self.assertTrue(b["wartungsfenster"][0]["herausgerechnet"])
        inwartung = [a for a in k["ausfaelle"] if a["davon_wartung_min"] > 0]
        self.assertEqual(len(inwartung), 1); self.assertEqual(inwartung[0]["zaehlt_min"], 0); self.assertAlmostEqual(inwartung[0]["dauer_min"], 20, delta=tol)
        self.assertEqual(k["wartungsminuten"], 30); self.assertEqual(k["ausfallminuten"], 17)
        # Formel: (Messminuten - Ausfallminuten ausserhalb Wartung) / (Messminuten - Wartungsminuten)
        erwartet = 100.0 * ((1440 - 30) - 17) / (1440 - 30)
        self.assertAlmostEqual(k["verfuegbarkeit_prozent"], erwartet, places=3)
        self.assertEqual(k["aussage"], "ziel_verfehlt"); self.assertIs(k["ziel_erreicht"], False)
        # getrennt je Bereich: der Operator hat 10 Ausfallminuten bei Wartung derselben Plattform
        self.assertAlmostEqual(o["verfuegbarkeit_prozent"], 100.0 * ((1440 - 30) - 10) / (1440 - 30), places=3)
        # ohne Wartungseintrag wären die 20 Minuten Ausfall und die Verfügbarkeit niedriger (Kontrolle: das Herausrechnen wirkt)
        ohne = bericht(kern + oper, [])["bereiche"]["kernbuchung"]
        self.assertEqual(ohne["ausfallminuten"], 37); self.assertLess(ohne["verfuegbarkeit_prozent"], k["verfuegbarkeit_prozent"])

    def test_ziel_erreicht_und_verfehlt_gegen_99_9_prozent(self):
        eine = bericht(reihe("kernbuchung", lambda m: P.FEHLER_PLATTFORM if m == 100 else P.OK) + reihe("operator", lambda m: P.OK))
        self.assertAlmostEqual(eine["bereiche"]["kernbuchung"]["verfuegbarkeit_prozent"], 100 * 1439 / 1440, places=3)
        self.assertTrue(eine["bereiche"]["kernbuchung"]["ziel_erreicht"]); self.assertEqual(eine["bereiche"]["kernbuchung"]["aussage"], "ziel_erreicht")
        self.assertEqual(eine["bereiche"]["operator"]["verfuegbarkeit_prozent"], 100.0)
        zwei = bericht(reihe("kernbuchung", lambda m: P.FEHLER_PLATTFORM if m in (100, 101) else P.OK) + reihe("operator", lambda m: P.OK))
        self.assertFalse(zwei["bereiche"]["kernbuchung"]["ziel_erreicht"])          # 99,861 % < 99,9 %: getrennt je Bereich verfehlt bzw. erreicht
        self.assertTrue(zwei["bereiche"]["operator"]["ziel_erreicht"])

    def test_nachtraeglich_eingetragenes_wartungsfenster_wird_nicht_herausgerechnet(self):
        kern = reihe("kernbuchung", lambda m: P.FEHLER_PLATTFORM if hm(3) <= m < hm(3, 30) else P.OK)
        spaet = [{"von": "2026-11-10T03:00:00Z", "bis": "2026-11-10T03:30:00Z", "grund": "Wartung", "eingetragen_am": "2026-11-10T04:00:00Z"}]
        b = bericht(kern + reihe("operator", lambda m: P.OK), spaet)
        self.assertFalse(b["wartungsfenster"][0]["vorab_eingetragen"]); self.assertFalse(b["wartungsfenster"][0]["herausgerechnet"])
        self.assertEqual(b["bereiche"]["kernbuchung"]["ausfallminuten"], 30); self.assertEqual(b["bereiche"]["kernbuchung"]["wartungsminuten"], 0)

    def test_voller_kalendermonat_und_schema(self):
        von, bis = B.monat_zeitraum("2026-11")
        self.assertEqual((bis - von).days, 30)
        saetze = reihe("kernbuchung", lambda m: P.OK, von, bis) + reihe("operator", lambda m: P.OK, von, bis)
        b = B.erzeuge_bericht(saetze, von, bis, VERTRAG, [], "rohdaten.jsonl", "b" * 64, erzeugt_am=bis)
        self.assertEqual(b["zeitraum"]["kalendermonat"], "2026-11"); self.assertEqual(b["bereiche"]["kernbuchung"]["messminuten"], 30 * 1440)
        self.assertNotIn("Testzeitraum", " ".join(b["hinweise"]))
        if jsonschema:
            jsonschema.validate(b, json.loads(SCHEMA.read_text(encoding="utf-8")))
            kaputt = json.loads(json.dumps(b)); del kaputt["bereiche"]["operator"]["ausfaelle"]
            with self.assertRaises(jsonschema.ValidationError):
                jsonschema.validate(kaputt, json.loads(SCHEMA.read_text(encoding="utf-8")))
        md = B.als_markdown(b)
        for text in ("Formel", "Kernbuchung", "Operator", "Messgrenze", "vorläufig, D-05 offen", "Rohdaten"):
            self.assertIn(text, md)

    def test_probenausfall_ist_keine_messung_und_providerfehler_getrennt(self):
        # Probe selbst fällt von 06:00 bis 07:00 aus (keine Rohdaten) und von 08:00 bis 08:30 (keine_messung gemeldet); Providerfehler 14:00 bis 14:20; Plattformfehler 16:00 bis 16:05
        def fn(m):
            if hm(6) <= m < hm(7):
                return None
            if hm(8) <= m < hm(8, 30):
                return P.KEINE_MESSUNG
            if hm(14) <= m < hm(14, 20):
                return P.FEHLER_PROVIDER
            if hm(16) <= m < hm(16, 5):
                return P.FEHLER_PLATTFORM
            return P.OK
        k = bericht(reihe("kernbuchung", fn) + reihe("operator", lambda m: P.OK))["bereiche"]["kernbuchung"]
        self.assertEqual(k["keine_messung_minuten"], 90); self.assertEqual(k["fehlende_intervalle"], 60)
        self.assertEqual(k["messminuten"], 1440 - 90, "keine Messung zählt weder als Verfügbarkeit noch als Messminute")
        self.assertEqual(k["ausfallminuten"], 5); self.assertEqual(k["providerfehlerminuten"], 20)
        self.assertAlmostEqual(k["verfuegbarkeit_prozent"], 100.0 * (1350 - 5) / 1350, places=3)
        self.assertLess(k["verfuegbarkeit_inkl_providerfehler_prozent"], k["verfuegbarkeit_prozent"], "Providerfehler sind getrennt ausgewiesen, nicht im Plattformwert")
        self.assertEqual(len(k["providerfehler"]), 1); self.assertEqual(k["providerfehler"][0]["dauer_min"], 20)
        self.assertEqual([x["dauer_min"] for x in k["luecken"]], [60])
        # Messwert "100 % verfügbar" bei ausgefallener Probe wäre falsch: ohne einen einzigen Messwert gibt es keine Aussage
        leer = bericht(reihe("kernbuchung", lambda m: P.KEINE_MESSUNG) + reihe("operator", lambda m: None))["bereiche"]
        for v in leer.values():
            self.assertIsNone(v["verfuegbarkeit_prozent"]); self.assertEqual(v["aussage"], "unzureichende_abdeckung"); self.assertIsNone(v["ziel_erreicht"])
        # geringe Abdeckung (Probe nur 50 % der Zeit gelaufen) ergibt keine Erreicht-Aussage, auch bei 100 % der Messwerte ok
        halb = bericht(reihe("kernbuchung", lambda m: P.OK if m < 720 else None) + reihe("operator", lambda m: P.OK))["bereiche"]["kernbuchung"]
        self.assertEqual(halb["aussage"], "unzureichende_abdeckung"); self.assertAlmostEqual(halb["abdeckung_prozent"], 50.0, places=2)

    def test_mehrere_saetze_je_intervall_der_schlechteste_gilt_und_rohdaten_ausserhalb_zaehlen_nicht(self):
        saetze = reihe("kernbuchung", lambda m: P.OK) + reihe("operator", lambda m: P.OK)
        saetze.append({"zeitpunkt": (VON + timedelta(minutes=30, seconds=5)).isoformat(), "bereich": "kernbuchung", "ergebnis": P.FEHLER_PLATTFORM})
        saetze.append({"zeitpunkt": (VON - timedelta(days=1)).isoformat(), "bereich": "kernbuchung", "ergebnis": P.FEHLER_PLATTFORM})
        k = bericht(saetze)["bereiche"]["kernbuchung"]
        self.assertEqual(k["ausfallminuten"], 1); self.assertEqual(k["rohdatensaetze_ausserhalb_zeitraum"], 1)

    def test_rohdaten_laden_prueft_zeilen_und_bildet_pruefsumme(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "r.jsonl"
            f.write_text(json.dumps({"zeitpunkt": VON.isoformat(), "bereich": "operator", "ergebnis": "ok"}) + "\n", encoding="utf-8")
            saetze, sha = B.lade_rohdaten(f)
            self.assertEqual(len(saetze), 1); self.assertRegex(sha, "^[0-9a-f]{64}$")
            f.write_text(json.dumps({"zeitpunkt": VON.isoformat(), "bereich": "operator", "ergebnis": "verfuegbar"}) + "\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                B.lade_rohdaten(f)


class ProbenKlassifikation(unittest.TestCase):
    """Die echten Proben über echtes HTTP gegen die Attrappe."""

    ENV = {"ATTRAPPE_PW": "richtig"}

    def setUp(self):
        self.alarme = []

    def lauf(self, srv, bereich, cfg=None, env=None):
        return P.probe_ausfuehren(bereich, cfg or srv.konfiguration(), P.urllib_transport, umgebung=env if env is not None else self.ENV, alarm=self.alarme.append)

    def test_beide_proben_ok_und_probebuchung_wird_storniert(self):
        with PlattformAttrappe() as srv:
            k, o = self.lauf(srv, "kernbuchung"), self.lauf(srv, "operator")
            self.assertEqual((k["ergebnis"], o["ergebnis"]), (P.OK, P.OK), (k, o))
            self.assertEqual([s["schritt"] for s in k["schritte"]], ["anmeldung", "angebot", "hold", "bestaetigung", "storno"])
            self.assertEqual(list(srv.buchungen.values()), ["CANCELED"], "die Probebuchung ist nach dem Lauf storniert (belegt keine Kapazität)")
            self.assertEqual(self.alarme, [])

    def test_psp_down_ist_fehler_provider_und_kein_plattformausfall(self):
        with PlattformAttrappe() as srv:
            srv.api = "psp_down"
            k = self.lauf(srv, "kernbuchung")
            self.assertEqual(k["ergebnis"], P.FEHLER_PROVIDER); self.assertEqual(k["schritt"], "hold"); self.assertEqual(k["http_status"], 503)
            self.assertEqual(self.lauf(srv, "operator")["ergebnis"], P.OK, "der Operatorbereich ist vom Anbieterausfall unberührt")
            self.assertEqual(srv.buchungen, {})

    def test_plattformfehler_5xx_falscher_inhalt_und_leeres_angebot(self):
        with PlattformAttrappe() as srv:
            for modus, bereich, schritt in (("plattform_500", "kernbuchung", "angebot"), ("leeres_angebot", "kernbuchung", "angebot"), ("falscher_inhalt", "operator", "me")):
                srv.api = modus
                r = self.lauf(srv, bereich)
                self.assertEqual((r["ergebnis"], r["schritt"]), (P.FEHLER_PLATTFORM, schritt), (modus, r))
            srv.api, srv.token = "ok", "ausfall"
            self.assertEqual(self.lauf(srv, "operator")["ergebnis"], P.FEHLER_PLATTFORM, "ausgefallener Anmeldedienst ist ein Plattformfehler")
            self.assertEqual(self.alarme, [])

    def test_ungueltige_zugangsdaten_sind_keine_messung_mit_probenalarm(self):
        with PlattformAttrappe() as srv:
            r = self.lauf(srv, "operator", env={"ATTRAPPE_PW": "falsch"})
            self.assertEqual(r["ergebnis"], P.KEINE_MESSUNG); self.assertEqual(r["schritt"], "anmeldung")
            self.assertEqual(len(self.alarme), 1); self.assertEqual(self.alarme[0]["alarm"], "ProbeKeineMessung"); self.assertEqual(self.alarme[0]["bereich"], "operator")
            # Zugangsdaten nicht gesetzt: ebenfalls Probenproblem, nicht Plattform
            r2 = self.lauf(srv, "kernbuchung", env={})
            self.assertEqual(r2["ergebnis"], P.KEINE_MESSUNG); self.assertEqual(len(self.alarme), 2)
            # 403 bei gültiger Anmeldung: Probekonto ohne Berechtigung
            srv.api = "forbidden"
            self.assertEqual(self.lauf(srv, "operator")["ergebnis"], P.KEINE_MESSUNG)

    def test_ausfall_der_probe_selbst_ist_keine_messung_plattformausfall_nicht(self):
        with PlattformAttrappe() as srv:
            tot = srv.konfiguration(base_url="http://127.0.0.1:1", token_url="http://127.0.0.1:1/token", referenz_url="http://127.0.0.1:1/ref", timeout_s=1.0)
            r = self.lauf(srv, "operator", cfg=tot)
            self.assertEqual(r["ergebnis"], P.KEINE_MESSUNG, "weder Plattform noch Referenzpunkt erreichbar: die Probe ist ausgefallen, keine Aussage")
            self.assertEqual(len(self.alarme), 1)
            # Plattform nicht erreichbar, Referenzpunkt erreichbar: echter Plattformausfall
            nur_plattform = srv.konfiguration(base_url="http://127.0.0.1:1", token_url="http://127.0.0.1:1/token", timeout_s=1.0)
            r2 = self.lauf(srv, "operator", cfg=nur_plattform)
            self.assertEqual(r2["ergebnis"], P.FEHLER_PLATTFORM, r2)
            # Ausnahme im Probenprozess (z. B. fehlerhafte Konfiguration) = Probe ausgefallen
            def kaputt(*a, **k):
                raise RuntimeError("Probenprozess")
            r3 = P.probe_ausfuehren("operator", srv.konfiguration(), kaputt, umgebung=self.ENV, alarm=self.alarme.append)
            self.assertEqual(r3["ergebnis"], P.KEINE_MESSUNG); self.assertIn("Ausnahme in der Probe", r3["detail"])

    def test_rohdaten_der_proben_fuehren_zum_bericht(self):
        """Ende-zu-Ende des Messverfahrens: Proben im Takt (Zeit eingefroren per Parameter), Rohdaten als JSONL, Bericht; PSP-Ausfall landet getrennt, Plattformausfall im Ausfall."""
        with PlattformAttrappe() as srv, tempfile.TemporaryDirectory() as d:
            roh = Path(d) / "roh.jsonl"
            t0 = VON.timestamp()
            ablauf = ["ok", "ok", "plattform_500", "plattform_500", "ok", "psp_down", "psp_down", "ok"]
            for i, modus in enumerate(ablauf):
                srv.api = modus if modus != "ok" else "ok"
                for bereich in ("kernbuchung",):
                    s = P.probe_ausfuehren(bereich, srv.konfiguration(), P.urllib_transport, uhr=lambda i=i: t0 + i * INTERVALL, umgebung=self.ENV)
                    anhaengen(roh, s)
                srv.api = "ok"
                anhaengen(roh, P.probe_ausfuehren("operator", srv.konfiguration(), P.urllib_transport, uhr=lambda i=i: t0 + i * INTERVALL, umgebung=self.ENV))
            saetze, sha = B.lade_rohdaten(roh)
            b = B.erzeuge_bericht(saetze, VON, VON + timedelta(minutes=len(ablauf)), VERTRAG, [], str(roh), sha, erzeugt_am=VON)
            k = b["bereiche"]["kernbuchung"]
            self.assertEqual((k["messminuten"], k["ausfallminuten"], k["providerfehlerminuten"]), (8, 2, 2))
            self.assertEqual(b["bereiche"]["operator"]["ausfallminuten"], 0)


if __name__ == "__main__":
    unittest.main()
