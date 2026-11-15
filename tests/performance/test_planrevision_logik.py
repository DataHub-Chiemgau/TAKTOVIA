"""WP-M5-13: Logik des Messharness `refload/planrevision.py` ohne Anwendung (läuft immer): Perzentile, Klassifikation der Zielmenge, SSE-Zerlegung. Diese Tests messen **nichts** an der Anwendung; sie sichern,
dass die Auswertung des Messlaufs (`test_planrevision_p95.py`) Ausreißer nicht als Erfolg zählt und unterbrochene Verbindungen getrennt ausweist."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[1] / "db"))

from refload import planrevision as P  # noqa: E402

S = 1_000_000_000


def sample(lat_s: float | None, *, connected=True, catchup=False, rev=2) -> P.Sample:
    s = P.Sample("t", "v", rev, 100 * S, 100 * S, "serie", connected_at_commit=connected, via_catchup=catchup)
    s.t_recv = None if lat_s is None else int(100 * S + lat_s * S * 0.9)
    s.t_shown = None if lat_s is None else int(100 * S + lat_s * S)
    return s


class Auswertung(unittest.TestCase):
    def test_perzentile_nearest_rank(self):
        v = [float(i) for i in range(1, 101)]
        self.assertEqual(P.percentile(v, 50), 50.0)
        self.assertEqual(P.percentile(v, 95), 95.0)
        self.assertEqual(P.percentile(v, 99), 99.0)

    def test_zielmenge_p95_und_verteilung(self):
        ev = P.evaluate([sample(0.4)] * 90 + [sample(3.0)] * 8 + [sample(12.0)] * 2)
        self.assertEqual(ev["zielmenge_n"], 100)
        self.assertEqual(ev["ueber_ziel"], 2)
        self.assertTrue(ev["ziel_erfuellt"], ev)
        self.assertEqual(ev["anteil_innerhalb_ziel"], 0.98)
        self.assertEqual(sum(ev["verteilung"].values()), 100)

    def test_p95_verfehlt_wenn_mehr_als_5_prozent_ueber_zehn_sekunden(self):
        ev = P.evaluate([sample(1.0)] * 90 + [sample(11.0)] * 10)
        self.assertFalse(ev["ziel_erfuellt"], ev)
        self.assertEqual(ev["anteil_innerhalb_ziel"], 0.9)

    def test_nachgeladene_zaehlen_als_ausreisser_nicht_als_erfolg(self):
        # 96 schnelle, 4 nachgeladene (spät, aber unter 10 s dargestellt): zählen gegen das Ziel, nie als Erfolg
        samples = [sample(0.5)] * 94 + [sample(6.0, catchup=True)] * 6
        ev = P.evaluate(samples)
        self.assertEqual(ev["ausreisser_nachgeladen"], 6)
        self.assertEqual(ev["zielmenge_n"], 100)
        self.assertEqual(ev["anteil_innerhalb_ziel"], 0.94)
        self.assertFalse(ev["ziel_erfuellt"], "6 % Ausreißer dürfen das Ziel nicht erfüllen lassen")
        self.assertEqual(ev["p95_zielmenge_inkl_ausreisser_s"], "unendlich")

    def test_nicht_dargestellt_ist_ausreisser(self):
        ev = P.evaluate([sample(1.0)] * 94 + [sample(None)] * 6)
        self.assertEqual(ev["nicht_dargestellt"], 6)
        self.assertFalse(ev["ziel_erfuellt"])

    def test_unterbrochene_verbindung_getrennt_ausgewiesen(self):
        ev = P.evaluate([sample(0.5)] * 20 + [sample(4.0, connected=False)] * 5)
        self.assertEqual(ev["zielmenge_n"], 20, "unterbrochen zählt nicht zur Zielmenge")
        self.assertEqual(ev["unterbrochen_getrennt"]["n"], 5)
        self.assertEqual(ev["unterbrochen_getrennt"]["latenz"]["p95_s"], 4.0)

    def test_unterbrochen_ohne_darstellung_wird_getrennt_gezaehlt_und_nicht_der_zielmenge(self):
        ev = P.evaluate([sample(0.5)] * 20 + [sample(None, connected=False)] * 3)
        self.assertEqual(ev["zielmenge_n"], 20)
        self.assertEqual(ev["nicht_dargestellt"], 0)
        self.assertEqual(ev["unterbrochen_getrennt"]["n"], 3)
        self.assertEqual(ev["unterbrochen_getrennt"]["nicht_dargestellt"], 3)

    def test_klassifikation(self):
        self.assertEqual(P.classify(sample(1.0)), "ziel")
        self.assertEqual(P.classify(sample(1.0, connected=False)), "unterbrochen")
        self.assertEqual(P.classify(sample(1.0, catchup=True)), "nachgeladen")
        self.assertEqual(P.classify(sample(None)), "nicht_dargestellt")
        self.assertEqual(P.classify(sample(None, connected=False)), "unterbrochen")


class Sse(unittest.TestCase):
    def test_zerlegung_mit_rest_und_herzschlag(self):
        buf = "retry: 3000\nevent: ready\ndata: {\"cursor\":\"1.0\"}\n\n: heartbeat\n\nid: 5.1\nevent: live\ndata: {\"a\":1}\n\nid: 5.2\nevent: li"
        frames, rest = P.parse_sse(buf)
        self.assertEqual([f[0] for f in frames], ["ready", "live"])
        self.assertEqual(frames[1][1], "5.1")
        self.assertEqual(rest, "id: 5.2\nevent: li")
        frames2, rest2 = P.parse_sse(rest + "ve\ndata: {}\n\n")
        self.assertEqual(frames2[0][:2], ("live", "5.2")); self.assertEqual(rest2, "")


if __name__ == "__main__":
    unittest.main()
