"""Tests der Port-Konformitätsprüfung (Spike d). Alle Ergebnisse sind SIMULATOR-Ergebnisse, kein PSP-Nachweis."""
import sys
import unittest
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from spike_d import conformance, psp_port  # noqa: E402
from spike_d.simulator_adapter import SimulatorAdapter  # noqa: E402
import psp_sim  # noqa: E402


class FreshKeyAdapter(SimulatorAdapter):
    """Fehlerhafter Adapter (Gegenprobe): vergibt je Aufruf einen neuen Idempotenzschlüssel, wiederholt also 'auf Verdacht'."""
    name = "fresh-key-adapter"

    def _call(self, method, path, body=None, key=None, write=True):
        return super()._call(method, path, body, (key + "-" + uuid.uuid4().hex) if key else None, write)


class Konformitaet(unittest.TestCase):
    def setUp(self):
        self.sim = psp_sim.PspSimulator(3, "2026-01-01T00:00:00Z")
        self.app = psp_sim.build_app(self.sim)
        self.app.start()

    def tearDown(self):
        self.app.stop()

    def test_simulator_besteht_alle_pruefungen(self):
        res = conformance.run(SimulatorAdapter(self.app.url, timeout=1.0), conformance.Ctl(self.app.url), latency_n=5)
        self.assertEqual(res["summary"]["failed"], 0, [r for r in res["results"] if r["ok"] is False])
        self.assertEqual(res["summary"]["not_run"], 0)
        ids = {r["id"] for r in res["results"]}
        for op in ("authorize", "capture", "void", "refund"):
            self.assertIn(f"C4-{op}-lost", ids)
            self.assertIn(f"C4-{op}-timeout", ids)
        self.assertTrue(res["is_simulator"])
        self.assertIn("Simulator", res["limitations"][0])
        self.assertTrue(all(res["latency_ms"][op]["n"] == 5 for op in ("authorize", "capture", "refund", "status")))

    def test_gegenprobe_adapter_ohne_stabilen_schluessel_faellt_durch(self):
        res = conformance.run(FreshKeyAdapter(self.app.url, timeout=1.0), conformance.Ctl(self.app.url), latency_n=2)
        failed = {r["id"] for r in res["results"] if r["ok"] is False}
        self.assertIn("C5a", failed)
        self.assertTrue({"C4-capture-lost", "C4-refund-lost"} & failed, failed)

    def test_ohne_steuerschnittstelle_bleibt_fehlerinjektion_nicht_ausgefuehrt(self):
        res = conformance.run(SimulatorAdapter(self.app.url), None, latency_n=2)
        self.assertEqual(res["summary"]["failed"], 0)
        self.assertEqual(res["summary"]["not_run"], 2)
        self.assertFalse(res["is_simulator"])


class Aufloesung(unittest.TestCase):
    def setUp(self):
        self.sim = psp_sim.PspSimulator(4)
        self.app = psp_sim.build_app(self.sim)
        self.app.start()
        self.port = SimulatorAdapter(self.app.url, timeout=0.5)
        self.ctl = conformance.Ctl(self.app.url)

    def tearDown(self):
        self.app.stop()

    def test_unbekannt_wird_ueber_status_aufgeloest_nie_blind_wiederholt(self):
        self.ctl.fault("capture", "lost_response", "R1")
        p = self.port.authorize("R1", 100, "EUR", "pm_ok", "k0", "o0")
        with self.assertRaises(psp_port.Unknown):
            self.port.capture(p.payment_id, 100, "k1", "o1")
        done, view = psp_port.resolve_unknown(self.port, "R1", "capture", "o1")
        self.assertTrue(done)
        self.assertEqual(view.state, "CAPTURED")

    def test_statusabfrage_nicht_verfuegbar_fuehrt_zu_manuellem_klaerfall(self):
        self.port.authorize("R2", 100, "EUR", "pm_ok", "k0")
        self.ctl.fault("status", "unavailable", "*", times=-1)
        with self.assertRaises(psp_port.Unknown) as cm:
            psp_port.resolve_unknown(self.port, "R2", "capture")
        self.assertIn("Klärfall", str(cm.exception))

    def test_authorize_ohne_wirkung_ist_unbekannte_referenz(self):
        self.assertEqual(psp_port.resolve_unknown(self.port, "NIE-ANGELEGT", "authorize"), (False, None))


class Kandidat(unittest.TestCase):
    def test_kandidatenadapter_ohne_zugang_nicht_ausfuehrbar(self):
        import os
        from spike_d.candidate_adapter_template import CandidateAdapter
        for k in ("PSP_CANDIDATE_BASE_URL", "PSP_CANDIDATE_API_KEY"):
            os.environ.pop(k, None)
        with self.assertRaises(RuntimeError) as cm:
            CandidateAdapter()
        self.assertIn("nicht ausführbar", str(cm.exception))

    def test_keine_zugangsdaten_im_spike_verzeichnis(self):
        import re
        for f in Path(__file__).parent.rglob("*"):
            if f.is_file() and f.suffix in (".py", ".md", ".json"):
                t = f.read_text()
                self.assertNotRegex(t, r"(?i)\b(sk|pk|rk)_(live|test)_[A-Za-z0-9]{10,}")
                self.assertNotRegex(t, r"(?i)api[_-]?key\s*[:=]\s*['\"][A-Za-z0-9]{16,}['\"]")


if __name__ == "__main__":
    unittest.main()
