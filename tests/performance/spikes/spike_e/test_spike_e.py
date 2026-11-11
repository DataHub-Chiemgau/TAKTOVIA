"""Tests der Routing-Port-Spike-Werkzeuge (WP-M0-30). Ergebnisse sind Ergebnisse der Referenzimplementierung bzw. des Stubs, keine Anbieterwerte."""
import json
import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from spike_e import HERE, fake_valhalla, measure, scoring  # noqa: E402
from spike_e.grid_router import BBOX, SyntheticGridRouter  # noqa: E402
from spike_e.routing_port import (NoRoute, OutOfCoverage, ProviderTimeout, ProviderUnavailable, RoutingPort, TimeoutGuard,  # noqa: E402
                                  cache_key)
from spike_e.valhalla_adapter import ValhallaAdapter  # noqa: E402


class Port(unittest.TestCase):
    def setUp(self):
        self.g = SyntheticGridRouter()
        self.a, self.b = (51.050, 13.740), (51.060, 13.756)

    def test_route_positiv_je_profil(self):
        auto, ped, wc = (self.g.route(self.a, self.b, p) for p in ("auto", "pedestrian", "wheelchair"))
        self.assertGreater(auto.distance_m, 1000)
        self.assertLess(auto.duration_s, ped.duration_s)
        self.assertLessEqual(ped.duration_s, wc.duration_s)
        self.assertEqual((auto.data_version, auto.profile), ("grid-v1", "auto"))

    def test_matrix_form_symmetrie_diagonale(self):
        pts = [(51.0 + i * 0.01, 13.6 + i * 0.01) for i in range(1, 6)]
        m = self.g.matrix(pts, pts, "auto")
        self.assertEqual((len(m.durations_s), len(m.durations_s[0])), (5, 5))
        for i in range(5):
            self.assertEqual(m.durations_s[i][i], 0)
            for j in range(5):
                self.assertEqual(m.durations_s[i][j], m.durations_s[j][i])

    def test_ausserhalb_datenbestand_und_keine_verbindung(self):
        with self.assertRaises(OutOfCoverage):
            self.g.route(self.a, (52.52, 13.40))
        with self.assertRaises(NoRoute):
            self.g.route((BBOX[0], 13.7), self.b, "wheelchair")
        m = self.g.matrix([self.a, (BBOX[0], 13.7)], [self.b], "auto")
        self.assertEqual([r[0] is None for r in m.durations_s], [False, True])
        with self.assertRaises(OutOfCoverage):
            self.g.matrix([self.a], [(60.0, 10.0)])

    def test_cache_schluessel_start_ziel_profil_version(self):
        k = cache_key(self.a, self.b, "auto", "v1")
        self.assertEqual(k, cache_key(self.a, self.b, "auto", "v1"))
        for other in (cache_key(self.b, self.a, "auto", "v1"), cache_key(self.a, (51.060001, 13.756), "auto", "v1"),
                      cache_key(self.a, self.b, "pedestrian", "v1"), cache_key(self.a, self.b, "auto", "v2")):
            self.assertNotEqual(k, other)
        self.assertEqual(k, cache_key((51.0500000001, 13.74), self.b, "auto", "v1"))   # Rundung auf 6 Stellen
        with self.assertRaises(ValueError):
            cache_key(self.a, self.b, "fahrrad", "v1")


class Timeout(unittest.TestCase):
    class Slow(RoutingPort):
        name = "langsam"

        def route(self, o, d, profile="auto"):
            time.sleep(1.0)

        matrix = route

        def data_version(self):
            return "x"

    def test_guard_bricht_ab_und_blockiert_nicht(self):
        g = TimeoutGuard(self.Slow(), 0.15)
        t = time.perf_counter()
        with self.assertRaises(ProviderTimeout):
            g.route((51.0, 13.6), (51.1, 13.7))
        with self.assertRaises(ProviderTimeout):
            g.matrix([(51.0, 13.6)], [(51.1, 13.7)])
        self.assertLess(time.perf_counter() - t, 0.6)

    def test_guard_reicht_fehler_und_ergebnisse_durch(self):
        g = TimeoutGuard(SyntheticGridRouter(), 1.0)
        self.assertEqual(g.route((51.05, 13.74), (51.06, 13.75)).provider, SyntheticGridRouter.name)
        with self.assertRaises(OutOfCoverage):
            g.route((51.05, 13.74), (10.0, 10.0))


class ValhallaAdapterVertrag(unittest.TestCase):
    """Vertragstest gegen den Stub mit den dokumentierten Antwortformen. KEIN Nachweis gegen einen echten Valhalla-Dienst."""

    def serve(self, **kw):
        app = fake_valhalla.build(**kw)
        app.start()
        self.addCleanup(app.stop)
        return app

    def test_route_matrix_und_profile(self):
        app = self.serve()
        ad = ValhallaAdapter(app.url, "map-2026-06")
        g = SyntheticGridRouter()
        a, b = (51.050, 13.740), (51.060, 13.756)
        for p in ("auto", "pedestrian", "wheelchair"):
            r = ad.route(a, b, p)
            self.assertEqual((r.duration_s, r.data_version), (g.route(a, b, p).duration_s, "map-2026-06"))
        m = ad.matrix([a, b], [a, b, (51.07, 13.77)], "auto")
        self.assertEqual((len(m.durations_s), len(m.durations_s[0])), (2, 3))
        self.assertEqual(m.durations_s, g.matrix([a, b], [a, b, (51.07, 13.77)], "auto").durations_s)

    def test_fehlerabbildung(self):
        app = self.serve()
        ad = ValhallaAdapter(app.url, "v")
        with self.assertRaises(OutOfCoverage):
            ad.route((51.05, 13.74), (52.52, 13.40))
        with self.assertRaises(NoRoute):
            ad.route((BBOX[0], 13.7), (51.05, 13.74), "wheelchair")
        app5 = self.serve(fail_status=503)
        with self.assertRaises(ProviderUnavailable):
            ValhallaAdapter(app5.url, "v").route((51.05, 13.74), (51.06, 13.75))

    def test_timeout_und_nicht_erreichbar(self):
        app = self.serve(delay_s=0.6)
        with self.assertRaises(ProviderTimeout):
            ValhallaAdapter(app.url, "v", timeout_s=0.1).route((51.05, 13.74), (51.06, 13.75))
        with self.assertRaises(ProviderUnavailable):
            ValhallaAdapter("http://127.0.0.1:9", "v", timeout_s=0.5).route((51.05, 13.74), (51.06, 13.75))


class Messwerkzeug(unittest.TestCase):
    def test_teststreckenkatalog_vollstaendig(self):
        cat = json.loads((HERE / "teststrecken.json").read_text())
        cats = {r["category"] for r in cat["routes"]}
        for must in ("fahrzeug_kurz", "fahrzeug_mittel", "fahrzeug_lang", "fuss_haltepunkt_zugang", "rollstuhl_barrierefrei", "ausserhalb_datenbestand"):
            self.assertIn(must, cats)
        self.assertTrue(any(c.startswith("restriktion") for c in cats))
        self.assertEqual({r["profile"] for r in cat["routes"]}, {"auto", "pedestrian", "wheelchair"})
        self.assertEqual(len({r["id"] for r in cat["routes"]}), len(cat["routes"]))
        self.assertFalse(cat["region"]["real_world_validated"])
        self.assertTrue(all(r["reference_duration_s"] is None for r in cat["routes"]))     # Referenzfahrzeiten stehen aus (Beistellung)

    def test_messung_auf_referenzimplementierung(self):
        res = measure.measure_routes(SyntheticGridRouter())
        self.assertEqual(len(res), 12)
        self.assertTrue(all(r["behaviour_as_expected"] for r in res), [r for r in res if not r["behaviour_as_expected"]])
        self.assertTrue(all(r["duration_s"] is not None for r in res if r["error"] is None))
        m = measure.measure_matrix(SyntheticGridRouter(), reps=3)
        self.assertEqual(set(m["sizes"]), {"M-1x25", "M-1x250", "M-2x250", "M-25x25", "M-100x100", "M-250x250"})
        self.assertEqual(m["sizes"]["M-2x250"]["cells"], 500)
        self.assertEqual(m["reference_size_id"], "M-2x250")

    def test_referenzgroesse_deckt_250_fahrzeuge(self):
        spec = json.loads((HERE / "matrix-instanzen.json").read_text())
        ref = next(s for s in spec["sizes"] if s["id"] == spec["reference_size_id"])
        self.assertEqual(ref["targets"], 250)
        self.assertEqual(spec["budget_ms"], 1000)


class Raster(unittest.TestCase):
    def test_gewichte_summe_100_und_ko(self):
        g = scoring.load_grid()
        self.assertEqual(sum(c["weight"] for c in g["criteria"]), 100)
        self.assertEqual({k["criterion"] for k in g["knockout"]}, {"K4", "K3"})

    def test_rechenprobe(self):
        ok = {f"K{i}": 3 for i in range(1, 9)}
        r = scoring.score(ok, {"KO1": False, "KO2": False})
        self.assertEqual((r["weighted"], r["max"], r["percent"], r["recommendable"]), (300, 400, 75.0, True))
        self.assertFalse(scoring.score(ok, {"KO1": True, "KO2": False})["recommendable"])
        low = {f"K{i}": 2 for i in range(1, 9)}
        self.assertFalse(scoring.score(low, {"KO1": False, "KO2": False})["recommendable"])      # 50 % < 60 %

    def test_negativ_unvollstaendige_oder_ungueltige_bewertung(self):
        ok = {f"K{i}": 3 for i in range(1, 9)}
        ko = {"KO1": False, "KO2": False}
        bad = dict(ok)
        del bad["K3"]
        for ratings, kos in ((bad, ko), ({**ok, "K1": 5}, ko), ({**ok, "K1": True}, ko), ({**ok, "K9": 1}, ko), (ok, {"KO1": False}), (ok, {**ko, "KO9": True})):
            with self.assertRaises(ValueError):
                scoring.score(ratings, kos)


if __name__ == "__main__":
    unittest.main()
