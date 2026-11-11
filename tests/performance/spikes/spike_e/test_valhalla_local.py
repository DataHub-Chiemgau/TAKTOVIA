"""Probelauf-Test: Valhalla-Adapter gegen eine echte Valhalla-Engine (pyvalhalla) auf dem synthetischen Netz. Wird übersprungen, wenn pyvalhalla fehlt.

  <Python mit pyvalhalla> -m unittest test_valhalla_local   (im Verzeichnis spike_e)
"""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
try:
    import valhalla  # noqa: F401
    HAVE = True
except ImportError:
    HAVE = False

if HAVE:
    from spike_e import HERE, measure_valhalla, valhalla_local
    from spike_e.routing_port import NoRoute, OutOfCoverage, ProviderTimeout
    from spike_e.valhalla_adapter import ValhallaAdapter


@unittest.skipUnless(HAVE, "pyvalhalla nicht installiert: echter Valhalla-Probelauf nicht ausführbar")
class EchtesValhalla(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = Path(tempfile.mkdtemp(prefix="valhalla-test-"))
        cfg, cls.build_s, _ = valhalla_local.build(cls.work)
        cls.actor = valhalla_local.make_actor(cfg)
        cls.app = valhalla_local.serve(cls.actor)
        cls.app.start()
        cls.ad = ValhallaAdapter(cls.app.url, "synthetic-v1", timeout_s=20)
        cls.catalog = json.loads((HERE / "teststrecken-valhalla-synthetisch.json").read_text())

    @classmethod
    def tearDownClass(cls):
        cls.app.stop()

    def test_alle_konstruierten_strecken_wie_erwartet(self):
        res = measure_valhalla.run_routes(self.ad, self.catalog)
        self.assertEqual([r["id"] for r in res if not r["ok"]], [], res)

    def test_profile_rollstuhl_meidet_treppe(self):
        res = {r["id"]: r for r in measure_valhalla.run_routes(self.ad, self.catalog)}
        self.assertLess(res["V06a"]["distance_m"], 150)             # Fußgänger nehmen die Treppe (ca. 100 m)
        self.assertGreater(res["V06b"]["distance_m"], 250)          # Rollstuhl: Umweg über die Straße
        self.assertGreater(res["V04b"]["duration_s"], res["V04a"]["duration_s"])

    def test_fehlercodes_der_echten_api(self):
        with self.assertRaises(OutOfCoverage):
            self.ad.route((51.05, 13.78), (52.52, 13.40))
        with self.assertRaises(NoRoute):
            self.ad.route((51.01, 13.71), (50.995, 13.7805))

    def test_matrix_form_und_unerreichbare_zellen(self):
        m = self.ad.matrix([(51.01, 13.71), (51.02, 13.72)], [(51.03, 13.73), (51.04, 13.74), (51.05, 13.75)], "auto")
        self.assertEqual((len(m.durations_s), len(m.durations_s[0])), (2, 3))
        self.assertTrue(all(isinstance(x, int) and x > 0 for row in m.durations_s for x in row))

    def test_timeout_des_adapters(self):
        slow = valhalla_local.serve(self.actor, delay_s=0.5)
        slow.start()
        try:
            with self.assertRaises(ProviderTimeout):
                ValhallaAdapter(slow.url, "v", timeout_s=0.1).route((51.01, 13.71), (51.02, 13.72))
        finally:
            slow.stop()


if __name__ == "__main__":
    unittest.main()
