"""WP-M5-08 / LH-6.19-NFR-005: Standortfrische unter Referenzlast gegen die **echte Anwendung** (Spring-Boot-Jar, Runtime-Rolle, RLS, lokaler JWT-Test-Issuer statt Keycloak; nicht ausgeführt: echter IdP).

Gelaufen wird das Profil `profiles/standortfrische.json` (Normalbetrieb: zwei Mandanten, je 250 Fahrzeuge mit Positionsstrom im Meldeintervall von 15 s, Fahrer-, Leitstellen-, Buchungs- und Lesesitzungen,
Buchungs- und Sitzungslast auf die Trageigenschaft der Messmaschine verkleinert). **Gemessene Grenze:** im vollen Referenzprofil (5.000 Lesesitzungen je Mandant, 30.000 Buchungen/Tag mal Peakfaktor) war die geteilte
Maschine (Generator, Anwendung, PostgreSQL, Issuer auf 4 CPUs) zu 99,9 % ausgelastet, die Positionsmeldungen liefen mit p95 24,8 s Antwortzeit und das Standortalter erreichte p95 69 s: dort **verfehlt**
(`protokolle/standortfrische-referenzprofil-saettigung.json`); das ist ein Befund der Messumgebung (D-05, Zielhardware offen), keine Aussage über den Normalbetrieb. Das Profil enthält
 einen Leitstellen-Beobachter, der im Takt die Fahrzeugliste (UI-07) liest und festhält, was die Anzeige je Fahrzeug zeigt (Standortalter nach Serveruhr, Zustand, Qualität, Messzeit, Empfangszeit).
Jedes 50. Fahrzeug unterbricht seinen Strom (Veraltet-Nachweis unter Last), eines je 50 sendet zusätzlich eine ältere Messung nach der neueren (falsche Reihenfolge), eines meldet einmal für ein nicht
zugeordnetes Fahrzeug. Das Messprotokoll (Kap. 08 §8.6: Erfassungs-/Empfangszeit, p95, Anteil > 30 s, Lücken, sichtbares Veraltet-Flag) steht unter `tests/performance/out/standortfrische/` (nicht versioniert).
Die Tests prüfen Messwerte und Isolation dieses Laufs; der Zielwert 30 s ist vorläufig (D-05 offen), das Ergebnis gilt für diese Messumgebung (geteilte Maschine) und ist keine Abnahme.
Voraussetzungen wie `test_referenzprofil.py` (Opt-in `TAKTOVIA_PERF_RUN=1`, bootJar, Datenbankumgebung). Ohne Opt-in übersprungen."""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[1] / "db"))

from refload import orchestrate, runner, shared, standortalter  # noqa: E402
from refload.prereq import require_app  # noqa: E402

PROFILE = HERE / "profiles" / "standortfrische.json"

_PROTO: dict = {}


def protokoll() -> dict:
    """Messprotokoll aus dem gemeinsamen Referenzlauf (einmal je Prozess); schreibt `standortfrische/protokoll.json` und `.md`."""
    if "p" not in _PROTO:
        require_app()
        p = runner.profile_from_json(PROFILE)
        rep = orchestrate.execute_profile(p, shared.out_root() / "standortfrische-lauf", title="Standortfrische im Normalbetrieb (WP-M5-08)", dataset_note="Testaufbau über API und SQL, kein Produktivbestand")
        ev = rep["results"]["standortalter"]
        ref = p.stages[0].name
        d = shared.out_root() / "standortfrische"
        d.mkdir(parents=True, exist_ok=True)
        out = {**ev, "referenzstufe": ref, "zaehler_negativproben": {k: v for k, v in rep["results"]["zaehler"].items() if k.startswith("pos_probe") or k.startswith("beobachter")},
               "urteil": "erfüllt" if (ev["je_stufe"][ref]["alter_p95_s"] or 999) < standortalter.THRESHOLD_S else "verfehlt"}
        (d / "protokoll.json").write_text(json.dumps(out, indent=1, ensure_ascii=False), encoding="utf-8")
        (d / "protokoll.md").write_text(standortalter.render_md(ev, rep["results"]["zaehler"], ref), encoding="utf-8")
        _PROTO["p"] = out
    return _PROTO["p"]


class Standortfrische(unittest.TestCase):
    def test_standortalter_p95_unter_30s_unter_referenzlast(self):
        p = protokoll(); ref = p["je_stufe"][p["referenzstufe"]]
        # genug Beobachtungen: 2 Mandanten x ~245 Fahrzeuge im Normalbetrieb über mehrere Leseläufe
        self.assertGreater(ref["beobachtungen"], 500, ref)
        # Messprotokoll weist p95, Anteil über der Schwelle, Überschreitungen, Lücken und Erfassung→Empfang aus
        for k in ("alter_p50_s", "alter_p95_s", "alter_max_s", "anteil_ueber_30s", "ueberschreitungen_ueber_30s", "erfassung_bis_empfang_p95_s"):
            self.assertIsNotNone(ref[k], k)
        self.assertIn("anzahl", p["luecken_im_normalbetrieb"]); self.assertEqual(p["status_zielwert"], "vorläufig, D-05 offen")
        self.assertLess(ref["alter_p95_s"], standortalter.THRESHOLD_S, f"Standortalter p95 in der Referenzstufe: {ref}")
        # Normalbetrieb ohne Positionsstrom-Ausfall: das Meldeintervall (15 s) bestimmt die Obergrenze, nicht die Anwendung
        self.assertEqual(ref["als_veraltet_gekennzeichnet"], ref["ueberschreitungen_ueber_30s"], "veraltet-Kennzeichen und Schwelle stimmen überein")
        # Strom unterbrochen (AT-10 unter Last): die Anzeige kennzeichnet veraltet, mit unveränderter Zeit der letzten Position
        u = p["unterbrochene_stroeme"]
        self.assertGreaterEqual(u["fahrzeuge"], 8, u); self.assertGreater(u["beobachtungen_ueber_schwelle"], 0, "Lauf zu kurz, um das Veralten zu beobachten")
        self.assertEqual(u["davon_als_veraltet_gekennzeichnet"], u["beobachtungen_ueber_schwelle"], u)
        self.assertEqual(u["fahrzeuge_mit_unveraenderter_messzeit"], u["beobachtet"], "keine Fortschreibung einer veralteten Position")
        # falsche Reihenfolge: die angezeigte Messzeit geht nie zurück; Meldung für nicht zugeordnetes Fahrzeug wurde abgewiesen
        self.assertEqual(p["reihenfolge"]["rueckgang_der_angezeigten_messzeit"], 0)
        z = p["zaehler_negativproben"]
        self.assertGreater(z.get("pos_probe_verspaetet_200", 0), 0, z)
        self.assertEqual({k for k in z if k.startswith("pos_probe_fremdes_fahrzeug")}, {"pos_probe_fremdes_fahrzeug_404"}, z)

    def test_positionsstroeme_zweier_mandanten_ohne_fremdposition(self):
        p = protokoll(); t = p["datentrennung"]
        self.assertEqual(sorted(t["fahrzeuge_je_mandant_beobachtet"]), ["A", "B"])
        for m in ("A", "B"):
            self.assertEqual(t["fremde_fahrzeuge_in_der_anzeige_je_mandant"][m], 0, f"Mandant {m} sieht Fahrzeuge des anderen Mandanten")
            self.assertGreaterEqual(t["fahrzeuge_mit_position_je_mandant"][m], 200, f"Mandant {m}: Positionsstrom nicht in der Anzeige angekommen")
            self.assertGreaterEqual(t["fahrzeuge_je_mandant_beobachtet"][m], 245)


if __name__ == "__main__":
    unittest.main()
