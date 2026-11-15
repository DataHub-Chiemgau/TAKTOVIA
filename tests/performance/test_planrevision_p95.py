"""WP-M5-13 / LH-6.19-NFR-004: Fahrer-Planrevision, Commit bis Darstellung beim Client, unter Referenzlast gegen die **echte Anwendung** (Spring-Boot-Jar, Runtime-Rolle, RLS, Live-Kanal, Fahrerabruf; lokaler
JWT-Test-Issuer statt Keycloak, nicht ausgeführt: echter IdP).

Gemessen wird mit einer **Gerätesimulation** (`refload/planrevision.py`), nicht mit der Flutter-App: Flutter und Zielgeräte stehen in dieser Umgebung nicht zur Verfügung (OP-27). Der Messpunkt „Revision dargestellt“
von UI-10 ist als `mobile/driver/lib/run/display_probe.dart` gebaut (nicht ausgeführt); die Simulation meldet den Zeitpunkt, zu dem sie den Plan der Revision vom Server geladen und übernommen hat.
Der Planschreiber bildet `JdbcRunPlans.appendIn` unter der Runtime-Rolle nach (die Anwendung kann 250 Umläufe je Mandant ohne Buchungen nicht ändern); der Relay ist in der Anwendung nicht verdrahtet,
der Live-Kanal liest `live_event`. Alles steht im Protokoll unter „Grenzen“. Das Ergebnis gilt für diese Messumgebung (geteilte Maschine: Generator, Anwendung, PostgreSQL, Issuer auf denselben CPUs),
der Zielwert 10 s ist vorläufig (D-05 offen): keine Abnahme.

Voraussetzungen wie `test_standortfrische.py` (Opt-in `TAKTOVIA_PERF_RUN=1`, bootJar, Datenbankumgebung). Ohne Opt-in übersprungen. Protokoll: `tests/performance/out/planrevision/` (nicht versioniert),
der dokumentierte Lauf steht unter `tests/performance/protokolle/planrevision-m5-13.{json,md}`."""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[1] / "db"))

from refload import planrevision as P  # noqa: E402
from refload import planrevision_run as R  # noqa: E402
from refload import shared  # noqa: E402
from refload.prereq import require_app  # noqa: E402

_PROTO: dict = {}


def protokoll() -> dict:
    """Ein Messlauf je Prozess; schreibt `planrevision/protokoll.json` und `.md`."""
    if "p" not in _PROTO:
        require_app()
        d = shared.out_root() / "planrevision"
        p = R.execute(d)
        p["urteil"] = "erfüllt" if p["phasen"]["serie"]["ziel_erfuellt"] else "verfehlt"
        (d / "protokoll.json").write_text(json.dumps(p, indent=1, ensure_ascii=False, default=str), encoding="utf-8")
        (d / "protokoll.md").write_text(R.render_md(p), encoding="utf-8")
        _PROTO["p"] = p
    return _PROTO["p"]


class PlanrevisionP95(unittest.TestCase):
    def test_p95_commit_bis_darstellung_unter_referenzlast(self):
        p = protokoll(); s = p["phasen"]["serie"]
        # Referenzlast: 250 verbundene Fahrzeuge je Mandant, zwei Mandanten, Grundlast der Positionsmeldungen
        self.assertEqual(p["verbunden_zu_beginn"], {"A": 250, "B": 250}, p["verbunden_zu_beginn"])
        self.assertEqual(p["parameter"]["geraete_gesamt"], 500)
        self.assertEqual(p["erstabruf_20_geraete"], [["200", "RELEASED", "1", "2"]], "Ausgangsplan: freigegeben, Revision 1, zwei Stopps")
        self.assertGreater(p["hintergrundlast"]["n"], 100, "Grundlast (Positionsmeldungen) lief nicht")
        self.assertEqual(p["hintergrundlast"]["error_count"], 0, p["hintergrundlast"])
        # Stichprobe und Verteilung sind protokolliert
        self.assertGreaterEqual(s["zielmenge_n"], 300, s)
        for k in ("p50_s", "p95_s", "p99_s", "max_s", "n"):
            self.assertIsNotNone(s["latenz"][k], k)
        self.assertEqual(sum(s["verteilung"].values()), s["latenz"]["n"])
        self.assertEqual(p["status_zielwert"], "vorläufig, D-05 offen")
        self.assertEqual(p["uhrenabgleich"]["anfang"]["runden"], 25)
        self.assertGreater(p["chain_db"]["ereignisse_run_revised"], 300)
        self.assertIn("nicht verdrahtet", p["chain_db"]["relay"])
        # Zielwert: p95 <= 10 s über die Zielmenge (Ausreißer zählen als Fehlschlag), alle Revisionen dargestellt
        self.assertEqual(s["nicht_dargestellt"], 0, s)
        self.assertEqual(s["ausreisser_nachgeladen"], 0, s)
        self.assertLessEqual(s["latenz"]["p95_s"], P.ZIEL_S, f"p95 Commit bis Darstellung: {s['latenz']}")
        self.assertTrue(s["ziel_erfuellt"], s)

    def test_hoehere_revision_wird_dargestellt_und_fremde_revision_nie_zugestellt(self):
        p = protokoll()
        # zwei Planänderungen kurz nacheinander: dargestellt wird die höhere Revision, nie ein Rückschritt
        d = p["konkurrenz_info"]["doppelt"]
        self.assertEqual(len(d), 20, d)
        for x in d:
            self.assertEqual(x["rev_b"], x["rev_a"] + 1, x)
            self.assertEqual(x["dargestellt"], x["rev_b"], f"höhere Revision nicht dargestellt: {x}")
            self.assertEqual(x["rueckschritte"], 0, x)
        z = p["fremdzustellung"]
        self.assertEqual(z["rueckschritte_gesamt"], 0, z)
        # Zwei Mandanten parallel ohne Fremdzustellung: jedes Gerät erhielt nur Ereignisse des eigenen Umlaufs und der eigenen Schicht
        self.assertEqual(z["fremde_ereignisse_gesamt"], 0, z)
        self.assertEqual([e for e in z["geraete_fehler"] if e.startswith("run_") or e == "plan_nicht_darstellbar"], [], z)
        self.assertGreater(z["ereignisse_gesamt"], 500)
        # Fremder Fahrtenplan (anderer Mandant, anderer Fahrer): einheitlich 404
        n = p["negativ"]
        self.assertEqual(set(n["fremder_mandant"]), {404}, n); self.assertEqual(set(n["anderer_fahrer"]), {404}, n)
        self.assertEqual(sorted(p["stichprobe_je_mandant"]), ["A", "B"], p["stichprobe_je_mandant"])

    def test_relay_neustart_nachladen_zaehlt_als_ausreisser(self):
        p = protokoll()
        # Verbindung kurz unterbrochen: zählt nicht zur Zielmenge, wird getrennt ausgewiesen und nach der Wiederverbindung (Cursor) dennoch dargestellt
        u = p["phasen"]["unterbrochen"]
        self.assertEqual(u["zielmenge_n"], 0, u)
        self.assertEqual(u["unterbrochen_getrennt"]["n"], p["unterbrochen_info"]["geraete"], u)
        self.assertEqual(u["unterbrochen_getrennt"]["nicht_dargestellt"], 0, u)
        self.assertGreaterEqual(u["unterbrochen_getrennt"]["latenz"]["min_s"], 1.5, "Zustellung kann nicht vor der Wiederverbindung liegen")
        # Verteilweg angehalten und wieder freigegeben: alle Revisionen werden nachgeliefert, aber als Ausreißer ausgewiesen, nie als Erfolg
        s = p["phasen"]["stall"]
        self.assertEqual(s["nicht_dargestellt"], 0, s)
        self.assertGreater(s["ausreisser_nachgeladen"], 0, s)
        self.assertGreater(p["stall_latenz"]["max_s"], 3.0, p["stall_latenz"])
        self.assertFalse(s["ziel_erfuellt"], "Ausreißer der Verteilweg-Störung dürfen das Ziel nicht erfüllen lassen")
        # die Störung beeinflusst die Zielmenge der Serie nicht (eigene Phase)
        self.assertEqual(p["phasen"]["serie"]["ausreisser_nachgeladen"], 0)

    def test_fahrerereignis_mit_alter_revision_liefert_konfliktantwort(self):
        p = protokoll(); a = p["konkurrenz_info"]["ack_parallel"]
        self.assertEqual(len(a), 20, a)
        self.assertTrue(all(x["vorab_status"] == 200 for x in a), [x["vorab_status"] for x in a])      # Kontrolle: Bestätigung der aktuellen Revision wirkt
        for x in a:
            # parallel zur Planänderung: entweder wirkte die Bestätigung vor dem Commit (200) oder sie wird als veraltet abgewiesen (412); nie ein anderer Ausgang
            self.assertIn(x["parallel_status"], (200, 412), x)
            if x["parallel_status"] == 412:
                self.assertEqual(x["parallel_code"], "REVISION_MISMATCH", x)
            # nach dem Commit ist die alte Revision veraltet: definierte Konfliktantwort mit der aktuellen Revision im ETag
            self.assertEqual(x["nach_commit_status"], 412, x)
            self.assertEqual(x["nach_commit_code"], "REVISION_MISMATCH", x)
            self.assertEqual(x["etag_nach_commit"], f'"{x["neue_revision"]}"', x)


if __name__ == "__main__":
    unittest.main()
